r"""Extra names a single declaration binds — `Entry.bindings` [declared-names].

Isabelle binds more than one name per command and `query` records one.  Each
name it misses is a `find` that misses, a `show` that says "No entries
matching", a `callers -r` that reports "not found in the entry index" — and,
worse than a miss, a `callers` that reports the name's OWN declaration as a
citation of itself, because `graph._def_sites` can only exclude a declaration
site it knows about.

Reference case, hand-checked against Isabelle's `theory/thms` export for
`Universal_Turing_Machine.Rec_Def`: the six introduction rules of
`inductive terminate` (`Rec_Def.thy:47..60`) are exported as the bare names
`termi_z termi_s termi_id termi_cn termi_pr termi_mn`, at positions that
bracket the label as written.  Indexing them recovered 102 distinct names
Isabelle declares over ten public sessions (`scripts/probe_export_recall.py`).
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402


def _bindings(snippet, name=None):
    """`{name: kind}` bound by the entry called `name` (or the only entry)."""
    sec = section_from(snippet)
    ents = [e for e in sec.entries if name is None or e.name == name]
    assert ents, f"no entry named {name!r} in {[e.name for e in sec.entries]}"
    return {n: k for e in ents for n, k in e.bindings}


HEAD = "theory T imports Main begin\n"
FOOT = "\nend\n"


class InductiveRuleNames(unittest.TestCase):

    def test_the_reference_case(self):
        # Rec_Def.thy:47..60, trimmed; six rules, `where` on its own line.
        got = _bindings(HEAD + '''
inductive terminate :: "recf \\<Rightarrow> nat list \\<Rightarrow> bool"
  where
    termi_z: "terminate z [n]"
  | termi_s: "terminate s [n]"
  | termi_id: "\\<lbrakk>n < m\\<rbrakk> \\<Longrightarrow> terminate (id m n) xs"
''' + FOOT, "terminate")
        self.assertEqual(got, {"termi_z": "rule", "termi_s": "rule",
                               "termi_id": "rule"})

    def test_unnamed_rules_bind_nothing(self):
        self.assertEqual(_bindings(HEAD + '''
inductive p :: "nat \\<Rightarrow> bool"
  where
    "p 0"
  | "p n \\<Longrightarrow> p (Suc n)"
''' + FOOT, "p"), {})

    def test_attributes_on_the_label(self):
        self.assertEqual(_bindings(HEAD + '''
inductive p :: "nat \\<Rightarrow> bool"
  where
    base[simp, intro]: "p 0"
  | step [intro!]: "p n \\<Longrightarrow> p (Suc n)"
''' + FOOT, "p"), {"base": "rule", "step": "rule"})

    def test_a_for_clause_binds_no_rules(self):
        # `inductive_set p for A :: ... and I :: ...` fixes PARAMETERS with
        # `and`; neither the parameters nor their type ascriptions are rules,
        # and `A ::` must never be read as a label (`::` is not `:`).
        self.assertEqual(_bindings(HEAD + '''
inductive_set reach :: "nat set"
  for A :: "nat set" and I :: "nat set"
  where
    reach_init: "x \\<in> I \\<Longrightarrow> x \\<in> reach A I"
''' + FOOT, "reach"), {"reach_init": "rule"})

    def test_a_bar_inside_a_term_is_not_a_rule_separator(self):
        # Scanned on the OUTER view, so inner syntax cannot mint a name.  The
        # `|` and the `xs:` here are both inside the quoted term.
        self.assertEqual(_bindings(HEAD + '''
fun f :: "nat \\<Rightarrow> nat"
  where
    "f n = (case n of 0 \\<Rightarrow> 1 | xs: Suc m \\<Rightarrow> m)"
''' + FOOT, "f"), {})

    def test_the_entrys_own_name_is_not_a_binding(self):
        self.assertEqual(_bindings(HEAD + '''
definition c :: "nat"
  where c: "c = 0"
''' + FOOT, "c"), {})

    def test_a_repeated_label_is_recorded_once(self):
        # Asserted on the LIST, not the {name: kind} view: a dict dedupes on
        # its own, so a dict assertion here cannot fail.
        sec = section_from(HEAD + '''
inductive p :: "nat \\<Rightarrow> bool"
  where
    base: "p 0"
  | base: "p 1"
''' + FOOT)
        [e] = [e for e in sec.entries if e.name == "p"]
        self.assertEqual(e.bindings, [("base", "rule")])

    def test_a_one_line_declaration(self):
        self.assertEqual(_bindings(HEAD +
                                   'inductive p where r1: "p 0"' + FOOT, "p"),
                         {"r1": "rule"})


class LabelPattern(unittest.TestCase):
    """`RULE_LABEL_RE`'s own contract, asserted directly.

    The `(?!:)` guard — a type ascription's `::` is not a label — is
    DEFENSIVE: over 120 AFP entries, dropping it changes nothing (547 labels
    either way), because the def-route grammar always puts a `::` after a name
    in the head, never straight after `where` or `|`.  It is kept for the
    custom `thy_decl` commands that take the same route with a grammar of
    their own choosing, and pinned here rather than through the parser,
    which cannot currently feed it such input."""

    def test_a_type_ascription_is_not_a_label(self):
        self.assertEqual(
            cli.RULE_LABEL_RE.findall('where x :: "nat" | y :: "bool"'), [])

    def test_a_single_colon_is(self):
        self.assertEqual(
            cli.RULE_LABEL_RE.findall('where x: "P" | y: "Q"'), ["x", "y"])

    def test_a_locale_element_colon_is_never_a_type_ascription(self):
        # Redundant once `fixes` resets the element kind — 1,830 elements
        # either way over 120 AFP entries — but it states the grammar rather
        # than relying on the reset being exhaustive.
        self.assertIsNone(cli._LOCALE_LABEL_RE.match(' g :: "T"'))
        self.assertEqual(cli._LOCALE_LABEL_RE.match(' a: "P"').group(1), "a")

    def test_a_selector_colon_is_never_a_type_ascription(self):
        # Same defensive status: BNF writes `(sel: type)` with one colon, and
        # dropping the guard changes nothing over 120 AFP entries (176
        # selectors either way).
        self.assertEqual(cli._SELECTOR_RE.findall("(x :: nat)"), [])
        self.assertEqual(cli._SELECTOR_RE.findall("(x: nat)"), ["x"])


class LabelsOutsideTheInductiveFamily(unittest.TestCase):
    """The label grammar is not special to `inductive`: over 120 AFP entries
    it also carries 70 `primrec` equation names, 17 `definition`s, 14 `fun`
    and 6 `function` (`scripts/probe_rule_labels.py`)."""

    def test_definition_with_a_named_equation(self):
        # `Semigroups_Big:38  definition F ... eq_fold:`
        self.assertEqual(_bindings(HEAD + '''
definition F :: "nat \\<Rightarrow> nat"
  where eq_fold: "F x = x"
''' + FOOT, "F"), {"eq_fold": "rule"})

    def test_primrec_with_named_equations(self):
        # `AT:660  primrec nodup ... nodup_nil: / nodup_step:`
        self.assertEqual(_bindings(HEAD + '''
primrec nodup :: "nat list \\<Rightarrow> bool"
  where
    nodup_nil: "nodup [] = True"
  | nodup_step: "nodup (x # xs) = (x \\<notin> set xs)"
''' + FOOT, "nodup"), {"nodup_nil": "rule", "nodup_step": "rule"})


class AndSiblings(unittest.TestCase):
    r"""`fun f and g and h where ...` declares three constants; only the first
    was recorded.  They are bindings rather than separate `Entry`s because one
    command has one span and one termination proof: three entries would give
    `enclosing` three equally-valid owners for every line and make `largest`
    count the declaration three times."""

    def test_mutually_recursive_functions(self):
        # `Alpha_Beta_Linear:97  fun maxmin ... and minmax`
        self.assertEqual(_bindings(HEAD + '''
fun maxmin :: "nat \\<Rightarrow> nat" and minmax :: "nat \\<Rightarrow> nat"
  where
    "maxmin 0 = 0"
  | "minmax 0 = 0"
''' + FOOT, "maxmin"), {"minmax": "sibling"})

    def test_three_way(self):
        self.assertEqual(_bindings(HEAD + '''
fun f :: "nat \\<Rightarrow> nat" and g :: "nat \\<Rightarrow> nat"
    and h :: "nat \\<Rightarrow> nat"
  where "f 0 = 0"
''' + FOOT, "f"), {"g": "sibling", "h": "sibling"})

    def test_a_for_clause_is_not_a_sibling_list(self):
        # THE trap.  `inductive_set p for A :: ... and I :: ...` fixes
        # PARAMETERS with `and`; a scan that cuts the head only at `where`
        # reads every `for` clause as a list of declared constants.
        self.assertEqual(_bindings(HEAD + '''
inductive_set reach :: "nat set"
  for A :: "nat set" and I :: "nat set"
  where "x \\<in> reach A I"
''' + FOOT, "reach"), {})

    def test_an_and_inside_a_term_is_invisible(self):
        self.assertEqual(_bindings(HEAD + '''
fun f :: "bool \\<Rightarrow> bool"
  where "f x = (x \\<and> and_of x)"
''' + FOOT, "f"), {})

    def test_a_name_ending_in_and_is_not_a_separator(self):
        # `fun band and c where ...` (type ascriptions are optional).  Without
        # a word boundary the scan matches the `and` INSIDE `band`, then reads
        # the real separator as the sibling's name — binding `and`, not `c`.
        self.assertEqual(_bindings(HEAD + '''
fun band and c
  where "band 0 = (0::nat)"
''' + FOOT, "band"), {"c": "sibling"})

    def test_the_helpers_own_name_guard(self):
        # Defensive, like the rule scan's: Isabelle has no valid
        # `fun f and f`, so the parser cannot feed this.  Asserted on the
        # helper directly rather than left untested.
        self.assertEqual(
            cli._and_siblings(['fun f :: "nat" and f :: "nat" where'],
                              1, 1, "f", "FUN"), [])

    def test_definition_takes_no_and_list(self):
        # `definition` / `abbreviation` have no `and`-list in Isabelle's
        # grammar, so an `and` in one of their heads is not a sibling.
        self.assertEqual(_bindings(HEAD + '''
definition d :: "nat"
  where "d = 0"
''' + FOOT, "d"), {})

    def test_a_custom_command_is_not_guessed_at(self):
        # AOT's `AOT_register_type_constraints Individual: ... and
        # Proposition: ...` separates type-constraint CATEGORIES with `and`,
        # not constants.  A custom command's grammar is its own; the tag gate
        # excludes it, since a custom keyword only ever maps to DEF/THEOREM.
        src = ('theory T imports Main\n'
               '  keywords "AOT_register_type_constraints" :: thy_decl\n'
               'begin\n'
               'AOT_register_type_constraints\n'
               '  Individual: nat and\n'
               '  Proposition: bool\n' + FOOT)
        sec = section_from(src)
        self.assertEqual([e.bindings for e in sec.entries
                          if e.bindings and any(k == "sibling"
                                                for _, k in e.bindings)], [])

    def test_siblings_and_rules_coexist(self):
        got = _bindings(HEAD + '''
inductive p :: "nat \\<Rightarrow> bool" and q :: "nat \\<Rightarrow> bool"
  where
    pq_base: "p 0"
  | pq_step: "q n \\<Longrightarrow> p n"
''' + FOOT, "p")
        self.assertEqual(got, {"q": "sibling", "pq_base": "rule",
                               "pq_step": "rule"})


class ProofBodiesAreNotScanned(unittest.TestCase):
    """`obtain x where H: "Q"` has the same shape as a rule label but binds a
    LOCAL Isar fact, not a theory-level name.  The `goal` route never scans
    for rule labels, which excludes the whole class by construction —
    `Misc:733`'s `obtain S' where S:` was the case that found this."""

    def test_obtain_in_a_proof_binds_nothing(self):
        got = _bindings(HEAD + '''
lemma foo: "True"
proof -
  obtain S' where S: "S' = (0::nat)" by blast
  show ?thesis by simp
qed
''' + FOOT, "foo")
        self.assertEqual(got, {})

    def test_a_shows_conjunct_is_still_tagged_conjunct(self):
        got = _bindings(HEAD + '''
lemma bar:
  assumes "True"
  shows a: "1 = (1::nat)" and b: "2 = (2::nat)"
  by simp_all
''' + FOOT, "bar")
        self.assertEqual(got, {"a": "conjunct", "b": "conjunct"})


class TypeDeclarations(unittest.TestCase):
    r"""A `datatype` declares constructors, and optionally discriminators and
    selectors — all real constants, all citable, none of them the datatype's
    own name.  Over 40 AFP entries: 366 constructors, 92 selectors, 4
    discriminators."""

    def test_constructors(self):
        # `Additive_Sharing:7  datatype Role = Party1 | Party2 | Party3`
        self.assertEqual(_bindings(HEAD +
                                   'datatype Role = Party1 | Party2 | Party3'
                                   + FOOT, "Role"),
                         {"Party1": "constructor", "Party2": "constructor",
                          "Party3": "constructor"})

    def test_a_multi_line_datatype(self):
        # Needs the extent fix: the `typedecl` route used to stop at the
        # declaration line, so only first-line constructors were visible.
        self.assertEqual(_bindings(HEAD + '''
datatype 'a t =
    A nat
  | B "'a list"
  | C
''' + FOOT, "t"),
                         {"A": "constructor", "B": "constructor",
                          "C": "constructor"})

    def test_a_discriminator(self):
        # `PDDL_STRIPS_Semantics:66  datatype variable = varname: Var name`
        self.assertEqual(_bindings(HEAD +
                                   'datatype variable = varname: Var name'
                                   + FOOT, "variable"),
                         {"varname": "discriminator", "Var": "constructor"})

    def test_selectors(self):
        # `PDDL_STRIPS_Semantics:54  predAtm (predicate: predicate) ...`
        self.assertEqual(_bindings(HEAD + '''
datatype 'ent atom = predAtm (predicate: predicate) (args: "'ent list")
                   | Eq (lhs: 'ent) (rhs: 'ent)
''' + FOOT, "atom"),
                         {"predAtm": "constructor", "predicate": "selector",
                          "args": "selector", "Eq": "constructor",
                          "lhs": "selector", "rhs": "selector"})

    def test_a_constructor_spelled_with_markup(self):
        # `Canton_Transaction_Tree:26  datatype view\<^sub>m = View\<^sub>m`.
        # A plain `[A-Za-z][\w']*` reads that as `View` and indexes a name
        # that does not exist.
        self.assertEqual(
            _bindings(HEAD + 'datatype t = View\\<^sub>m nat' + FOOT, "t"),
            {"View\\<^sub>m": "constructor"})

    def test_a_constructor_named_for_its_type_is_not_a_binding(self):
        # `ADS_Construction:518  datatype 'a list_R1 = list_R1 (unR: ...)`
        self.assertEqual(
            _bindings(HEAD + 'datatype \'a list_R1 = list_R1 (unR: "\'a")'
                      + FOOT, "list_R1"),
            {"unR": "selector"})

    def test_argument_types_are_not_constructors(self):
        # Read on the outer view: a quoted argument type is blanked, so the
        # names inside it cannot be mistaken for further alternatives.
        self.assertEqual(
            _bindings(HEAD + 'datatype t = A "nat \\<Rightarrow> bool option"'
                      + FOOT, "t"),
            {"A": "constructor"})

    def test_a_record_declares_no_constructors(self):
        # `record point = parent + x :: nat` uses `=` for the parent-type
        # clause and declares fields as bare `name :: type` lines — a
        # different grammar, deliberately not scanned here.
        self.assertEqual(_bindings(HEAD + '''
record point =
  x :: nat
  y :: nat
''' + FOOT, "point"), {})


class LocaleAndClass(unittest.TestCase):
    r"""A `locale` / `class` declares a name, and `find hpk` found nothing.
    Over 120 AFP entries this adds 1,047 LOCALE and 177 CLASS entries and
    1,798 assumption names — the largest single class in `[declared-names]`."""

    SRC = ('theory T imports Main begin\n'
           'locale hpk =\n'
           '  fixes f :: "nat \\<Rightarrow> nat"\n'
           '    and g :: "nat \\<Rightarrow> nat"\n'
           '  assumes commute: "f (g x) = g (f x)"\n'
           '      and idem[simp]: "f (f x) = f x"\n'
           '  defines h_def: "h \\<equiv> f"\n'
           'begin\n'
           'lemma inner: "True" by simp\n'
           'end\n'
           'end\n')

    def test_the_locale_is_an_entry(self):
        sec = section_from(self.SRC)
        [e] = [e for e in sec.entries if e.name == "hpk"]
        self.assertEqual(e.tag, "LOCALE")

    def test_the_span_is_the_head_only(self):
        # Up to but NOT including `begin` (line 8).  Covering the body would
        # give `enclosing` two equally-valid owners for every line in it, and
        # make `largest` rank a locale above the proofs it contains.
        sec = section_from(self.SRC)
        [e] = [e for e in sec.entries if e.name == "hpk"]
        self.assertEqual((e.thy_line, e.decl_end_line), (2, 7))

    def test_assumptions_and_defines_are_bound_with_their_kind(self):
        self.assertEqual(_bindings(self.SRC, "hpk"),
                         {"commute": "assumption", "idem": "assumption",
                          "h_def": "definition"})

    def test_fixed_parameters_are_not_bound(self):
        # `fixes f :: "T" and g :: "T"` binds PARAMETERS, not facts.  Their
        # `::` is rejected by the single-colon requirement.
        self.assertNotIn("f", _bindings(self.SRC, "hpk"))
        self.assertNotIn("g", _bindings(self.SRC, "hpk"))

    def test_an_entry_inside_still_targets_the_locale(self):
        sec = section_from(self.SRC)
        [e] = [e for e in sec.entries if e.name == "inner"]
        self.assertEqual(e.target, "hpk")

    def test_a_class_is_an_entry_too(self):
        sec = section_from('theory T imports Main begin\n'
                           'class ord =\n'
                           '  fixes less :: "\'a \\<Rightarrow> \'a \\<Rightarrow> bool"\n'
                           '  assumes irrefl: "\\<not> less x x"\n'
                           'begin\n'
                           'end\n'
                           'end\n')
        [e] = [e for e in sec.entries if e.name == "ord"]
        self.assertEqual(e.tag, "CLASS")
        self.assertEqual([n for n, _ in e.bindings], ["irrefl"])

    def test_context_and_interpretation_declare_nothing(self):
        # `context foo begin` REOPENS an existing target and
        # `interpretation` INSTANTIATES one; neither declares a name, so
        # neither may mint an entry.
        sec = section_from('theory T imports Main begin\n'
                           'locale foo begin\n'
                           'end\n'
                           'context foo begin\n'
                           'lemma a: "True" by simp\n'
                           'end\n'
                           'interpretation bar: foo by standard\n'
                           'end\n')
        self.assertEqual(sorted(e.name for e in sec.entries), ["a", "foo"])

    def test_a_second_fixes_group_resets_the_element_kind(self):
        r"""`Akra_Bazzi_Real:501` — Isabelle allows a `fixes` group AFTER an
        `assumes`.  Tracking only the last non-`and` FACT keyword left
        `current` at "assumption", so the trailing `and C :: real` bound a
        parameter as an assumption.  `fixes`/`constrains`/`for` clear it.
        """
        got = _bindings('theory T imports Main begin\n'
                        'locale akra_bazzi_real =\n'
                        '  fixes integrable integral\n'
                        '  assumes integral: "True"\n'
                        '  fixes g :: "nat \\<Rightarrow> real"\n'
                        '    and C :: real\n'
                        'begin\n'
                        'end\n'
                        'end\n', "akra_bazzi_real")
        self.assertEqual(got, {"integral": "assumption"})

    def test_a_for_clause_after_assumes_binds_nothing(self):
        got = _bindings('theory T imports Main begin\n'
                        'locale L = base +\n'
                        '  assumes a: "True"\n'
                        '  for x and y\n'
                        'begin\n'
                        'end\n'
                        'end\n', "L")
        self.assertEqual(got, {"a": "assumption"})

    def test_a_label_must_follow_its_keyword_immediately(self):
        # An unnamed `assumes` followed by a `notes`: searching forward for a
        # label instead of matching at the keyword finds the `notes` label and
        # files it under the wrong kind.
        got = _bindings('theory T imports Main begin\n'
                        'locale L =\n'
                        '  assumes "True"\n'
                        '  notes n = conjI\n'
                        '  notes m: conjI\n'
                        'begin\n'
                        'end\n'
                        'end\n', "L")
        self.assertEqual(got, {"m": "note"})

    def test_the_helpers_parameter_and_own_name_guards(self):
        # Both are defensive and overlap with the single-colon rule on real
        # input, so they are isolated here rather than left looking tested.
        # A `fixes` group clears the current element...
        self.assertEqual(
            cli._locale_facts(["locale L = assumes a: True fixes x and y: T"],
                              1, 1, "L"),
            [("a", "assumption")])
        # ...and the locale never binds its own name.
        self.assertEqual(
            cli._locale_facts(["locale L = assumes L: True"], 1, 1, "L"), [])

    def test_an_anonymous_context_mints_no_entry(self):
        sec = section_from('theory T imports Main begin\n'
                           'context fixes x :: nat begin\n'
                           'lemma a: "True" by simp\n'
                           'end\n'
                           'end\n')
        self.assertEqual([e.name for e in sec.entries], ["a"])


class Resolution(unittest.TestCase):
    """A bound name resolves to the entry that binds it, and — the point of
    indexing it — stops reading as a citation of itself."""

    SRC = HEAD + '''
inductive terminate :: "nat \\<Rightarrow> bool"
  where
    termi_z: "terminate 0"
  | termi_s: "terminate n \\<Longrightarrow> terminate (Suc n)"

lemma uses_it: "terminate 0"
  by (auto intro: termi_z)
''' + FOOT

    def test_the_declaration_is_not_its_own_caller(self):
        # Before indexing, `callers termi_z` returned the `| termi_z: "..."`
        # line itself: `_build_def_sites` can only exclude a declaration site
        # it knows about.  On the real corpus two of the three reported
        # callers of `Universal_Turing_Machine`'s `termi_z` were its own
        # declarations.
        hits = cli._find_callers([section_from(self.SRC)], "termi_z")
        self.assertEqual([(t, ln) for t, ln, _ in hits], [("Test", 9)],
                         f"expected only the `intro:` citation, got {hits}")

    def test_the_declaration_site_is_registered(self):
        sec = section_from(self.SRC)
        sites = cli._build_def_sites([sec], names={"termi_z"})
        self.assertIn("termi_z", sites["Test"])

    def test_find_matches_via_a_bound_name(self):
        sec = section_from(self.SRC)
        hits = [e.name for e in sec.entries
                if "termi_s" in e.bound_names]
        self.assertEqual(hits, ["terminate"])


if __name__ == "__main__":
    unittest.main()
