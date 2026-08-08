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
