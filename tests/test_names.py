"""Name-parsing robustness — the cases that produced `?` before, plus the
silent-truncation bug the old `\\w[\\w']*` pattern hid.

Each assertion is a small, self-contained reproduction of a real AFP form,
so the suite documents *why* the parser is shaped the way it is.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from, tags_by_name  # noqa: E402

PN = cli._parse_name
PT = cli._parse_typedecl_name
PD = cli._parse_def_name


class ParseName(unittest.TestCase):
    def test_bare_identifier(self):
        self.assertEqual(PN("foo: ..."), "foo")
        self.assertEqual(PN("foo' : ..."), "foo'")
        self.assertEqual(PN("foo_bar123 :: nat"), "foo_bar123")

    def test_quoted_special_name(self):
        # Colons/brackets/hyphens force double-quoting at the decl site.
        self.assertEqual(PN('"beta-C-cor:3": ...'), "beta-C-cor:3")
        self.assertEqual(PN('"vdash-properties:1[1]": ...'), "vdash-properties:1[1]")

    def test_locale_prefix_is_skipped(self):
        self.assertEqual(PN("(in jozsa) jozsa_dim [simp]: ..."), "jozsa_dim")
        self.assertEqual(PN("(in quantum_machine) no_cloning: ..."), "no_cloning")

    def test_other_modifier_prefix(self):
        self.assertEqual(PN("(nonexhaustive) Abs_hmultiset :: ..."), "Abs_hmultiset")

    def test_nested_modifier_prefix(self):
        # balanced-paren skip must not stop at the first ')'
        self.assertEqual(PN("(in foo (bar)) baz: ..."), "baz")

    def test_symbolic_name(self):
        self.assertEqual(PN(r"\<psi>\<^sub>1\<^sub>0:: ..."), r"\<psi>\<^sub>1\<^sub>0")
        self.assertEqual(PN(r"\<tau>rtrancl3p :: ..."), r"\<tau>rtrancl3p")
        self.assertEqual(PN(r"\<alpha>ah :: ..."), r"\<alpha>ah")

    def test_symbolic_suffix_not_truncated(self):
        # Old code returned 'finally' / 'not' / 'And' (a wrong, keyword-
        # colliding truncation); the real name keeps its subscript.
        self.assertEqual(PN(r"finally\<^sub>n :: ..."), r"finally\<^sub>n")
        self.assertEqual(PN(r"not\<^sub>n where ..."), r"not\<^sub>n")

    def test_anonymous_stays_unparsed(self):
        # genuinely unnamed lemmas must remain '?', not get an invented name
        self.assertEqual(PN('[simp]: "..."'), "?")
        self.assertEqual(PN(": ..."), "?")

    def test_reserved_control_symbols_are_not_names(self):
        # cartouche/comment delimiters at the name position must not be
        # captured as a name (they were before the reserved-prefix guard).
        # A lone margin comment yields no name; a comment *followed* by a name
        # is the separate skip case (test_margin_comment_before_name_is_skipped).
        self.assertEqual(PN(r"\<comment> \<open>a note\<close>"), "?")
        self.assertEqual(PN(r"\<open>P x \<longrightarrow> Q x\<close>"), "?")

    def test_quoted_statement_is_not_a_name(self):
        # `lemma "P"` (no trailing ':') is anonymous — the quoted text is the
        # statement, not a name.  Only a quoted *label* (followed by ':',
        # possibly after [attributes]) is a name.
        self.assertEqual(PN('"set xs = set ys" by simp'), "?")
        self.assertEqual(PN(r'"f x \<equiv> g x" '), "?")
        self.assertEqual(PN('"my_rule": "P"'), "my_rule")
        self.assertEqual(PN('"my_rule" [simp]: "P"'), "my_rule")

    def test_bare_reserved_keyword_is_not_a_name(self):
        # `lemma assumes ...` / `... (eqvt) by ...` / `lemma shows NAME:` are
        # anonymous (or the real name follows); the bare keyword sitting in the
        # name slot must not be captured as the name.
        self.assertEqual(PN(r'assumes "s \<in> S" and "fair rs"'), "?")
        self.assertEqual(PN("by lexicographic_order"), "?")
        self.assertEqual(PN("fixes p :: nat"), "?")
        self.assertEqual(PN("shows negmax_maxmin: ..."), "?")

    def test_quoted_reserved_keyword_is_a_name(self):
        # a *quoted* keyword is a deliberately-quoted legitimate name — the
        # guard rejects only the bare form (`fun "for"`, `lemma "if":`).
        self.assertEqual(PN('"for" :: "nat list"'), "for")
        self.assertEqual(PN('"if": "P"'), "if")
        self.assertEqual(PN('"and" :: "fm"'), "and")

    def test_margin_comment_before_name_is_skipped(self):
        # ~190 AFP entries: a `\<comment> \<open>...\<close>` annotation sits
        # between the keyword and the name; the prefix-stripper skips it.
        self.assertEqual(PN(r'\<comment> \<open>a note\<close> bar :: "nat"'), "bar")
        # nested cartouche inside the comment body
        self.assertEqual(PN(r'\<comment> \<open>see \<open>X\<close>\<close> baz ::'), "baz")
        # a comment with no following name stays '?'
        self.assertEqual(PN(r'\<comment> \<open>only a note\<close>'), "?")


class ParseDefName(unittest.TestCase):
    """`_parse_def_name` = `_parse_name` plus an implicit-name fallback: a
    definition/abbreviation written as a quoted equation carries no label, so
    the name is the head of the LHS."""

    def test_explicit_label_still_wins(self):
        # a normal labelled/identified definition is unaffected
        self.assertEqual(PD("foo :: nat where ..."), "foo")
        self.assertEqual(PD('"my_rule": "P"'), "my_rule")

    def test_implicit_lhs_head(self):
        self.assertEqual(PD(r'"language_ltlc \<phi> \<equiv> {\<xi>. P}"'),
                         "language_ltlc")
        self.assertEqual(PD('"foo = 0"'), "foo")
        self.assertEqual(PD('"pad m s = replicate m x @ s"'), "pad")

    def test_implicit_lhs_head_through_locale_prefix(self):
        self.assertEqual(PD(r'(in grp) "e \<equiv> 1"'), "e")

    def test_no_connective_is_not_a_definition(self):
        # a quoted body that is not an equation (no \<equiv>/=) is not an
        # implicit-name definition — it stays '?', never invents a name
        self.assertEqual(PD('"P x" by simp'), "?")
        self.assertEqual(PD(r'"\<forall>x. Q x"'), "?")


class ParseTypedeclName(unittest.TestCase):
    def test_leading_type_variable(self):
        self.assertEqual(PT("'a foo = ..."), "foo")
        self.assertEqual(PT("('a, 'b) bar = ..."), "bar")

    def test_type_args_without_space(self):
        self.assertEqual(PT("('si,'nsi)simple_state_impl = ..."), "simple_state_impl")

    def test_quoted_keyword_name(self):
        # `term` is a keyword, so it is quoted even as a datatype name
        self.assertEqual(PT('\'a "term" = ...'), "term")

    def test_modifier_then_type_args(self):
        self.assertEqual(PT("(discs_sels) ('a, 'b) fmla = ..."), "fmla")

    def test_symbolic_type_name(self):
        self.assertEqual(PT(r"\<upsilon> = ..."), r"\<upsilon>")


class ExtractEntriesEndToEnd(unittest.TestCase):
    SNIPPET = r'''theory T imports Main begin

definition (in ord) min_set :: "'a set" where "min_set = {}"

theorem "beta-C-cor:3": "True" by auto

lemma (in jozsa) jozsa_dim [simp]: "True" by auto

abbreviation \<psi>\<^sub>1 :: "nat" where "\<psi>\<^sub>1 = 1"

datatype 'a "term" = C 'a

lemma [simp]: "True" by auto

end
'''

    def test_names_and_tags(self):
        sec = section_from(self.SNIPPET)
        by_name = tags_by_name(sec)
        self.assertEqual(by_name.get("min_set"), "DEF")
        self.assertEqual(by_name.get("beta-C-cor:3"), "THEOREM")
        self.assertEqual(by_name.get("jozsa_dim"), "LEMMA")
        self.assertEqual(by_name.get(r"\<psi>\<^sub>1"), "ABBREV")
        self.assertEqual(by_name.get("term"), "DATATYPE")

    def test_anonymous_lemma_is_the_only_question_mark(self):
        sec = section_from(self.SNIPPET)
        unparsed = [e for e in sec.entries if e.name == "?"]
        # exactly the `lemma [simp]:` with no name
        self.assertEqual(len(unparsed), 1)
        self.assertEqual(unparsed[0].tag, "LEMMA")


class DefinitionalCommands(unittest.TestCase):
    r"""Every definitional command that introduces a citable constant must be
    indexed as an entry, not just `definition` / `fun`.

    `function` is the one that bit upstream (todo `[function-defs]`): it is a
    `thy_goal_defn` — it *defines* a constant and then *proves* its
    well-definedness (`by pat_completeness auto`, a separate `termination`) — so
    it was absent from `DECL_RE` and its constant never reached the index.  We
    tag it `FUN` and route it through the `def` branch like `fun`; the trailing
    `by` / `termination` proof lines fall into the body span naturally (the def
    loop only breaks at a blank line, a new command, or a doc block).

    The other three the report *guessed* were affected — `primrec`,
    `inductive`, `inductive_set` — were already in `DECL_RE`; these assertions
    pin that, so the scope of the fix stays on record as `function`-only.
    """

    def tags_of(self, snippet):
        return tags_by_name(section_from(snippet))

    def test_function_indexes_its_constant(self):
        snippet = r'''theory T imports Main begin
function wrap_enc :: "nat \<Rightarrow> nat" where
  "wrap_enc 0 = 0"
| "wrap_enc (Suc n) = wrap_enc n"
  by pat_completeness auto
termination by lexicographic_order
end
'''
        self.assertEqual(self.tags_of(snippet).get("wrap_enc"), "FUN")

    def test_function_sequential_option_is_stripped(self):
        snippet = r'''theory T imports Main begin
function (sequential) merge :: "nat list \<Rightarrow> nat list" where
  "merge [] = []"
  by pat_completeness auto
end
'''
        self.assertEqual(self.tags_of(snippet).get("merge"), "FUN")

    def test_function_body_span_covers_the_termination_proof(self):
        # The cut-planning use case (todo `[src-doc-attribution]`) needs the
        # body to enclose the whole definition, termination proof included.
        snippet = r'''theory T imports Main begin
function f :: "nat \<Rightarrow> nat" where
  "f 0 = 0"
  by pat_completeness auto
termination by lexicographic_order

end
'''
        sec = section_from(snippet)
        f = next(e for e in sec.entries if e.name == "f")
        self.assertEqual(f.thy_line, 2)
        self.assertEqual(f.body_end_line, 5)   # through `termination by ...`

    def test_primrec_inductive_already_indexed(self):
        # Pin the report's three "likely also broken" commands as already-OK,
        # so the fix's scope (function-only) is documented.
        snippet = r'''theory T imports Main begin
primrec plen :: "nat list \<Rightarrow> nat" where
  "plen [] = 0"
inductive even2 :: "nat \<Rightarrow> bool" where
  "even2 0"
inductive_set Reach :: "nat set" where
  "0 \<in> Reach"
end
'''
        tags = self.tags_of(snippet)
        self.assertEqual(tags.get("plen"), "FUN")
        self.assertEqual(tags.get("even2"), "IND")
        self.assertEqual(tags.get("Reach"), "INDSET")


class ContinuationLineName(unittest.TestCase):
    """A decl whose keyword stands alone carries its name on a following line
    (~1,866 AFP entries).  `extract_entries` peeks forward without consuming
    the line, so the name is recovered while spans stay correct."""

    def names_of(self, snippet):
        return [e.name for e in section_from(snippet).entries]

    def test_inductive_set_name_below(self):
        snippet = r'''theory T imports Main begin
inductive_set
  myset :: "nat set"
where base: "0 \<in> myset"
end
'''
        self.assertIn("myset", self.names_of(snippet))

    def test_definition_name_below(self):
        snippet = r'''theory T imports Main begin
definition
  foo :: "nat" where "foo = 0"
end
'''
        self.assertIn("foo", self.names_of(snippet))

    def test_datatype_name_below(self):
        # typedecl route: the type-arg list and name sit on the next line
        snippet = r'''theory T imports Main begin
datatype
  'a tree = Leaf | Node "'a tree" 'a "'a tree"
end
'''
        self.assertIn("tree", self.names_of(snippet))

    def test_locale_prefix_alone_then_name_below(self):
        # `definition (in foo)` strips to empty -> must still look ahead
        snippet = r'''theory T imports Main begin
definition (in ord)
  min_set :: "'a set" where "min_set = {}"
end
'''
        self.assertIn("min_set", self.names_of(snippet))

    def test_blank_and_comment_lines_are_skipped(self):
        snippet = r'''theory T imports Main begin
definition

  \<comment> \<open>a note\<close>
  spaced :: "nat" where "spaced = 0"
end
'''
        self.assertIn("spaced", self.names_of(snippet))

    def test_following_command_means_no_name(self):
        # a lone keyword immediately followed by another command really had no
        # name on its own line: the next command must not be mined as the name
        snippet = r'''theory T imports Main begin
definition
lemma foo: "True" by auto
end
'''
        names = self.names_of(snippet)
        self.assertNotIn("lemma", names)   # 'lemma' is not a name
        self.assertIn("foo", names)        # the lemma keeps its own name

    def test_same_line_name_still_parses(self):
        # the lookahead must not disturb the common same-line form
        snippet = r'''theory T imports Main begin
definition bar :: "nat" where "bar = 0"
end
'''
        self.assertIn("bar", self.names_of(snippet))

    def test_lhs_head_name_through_lookahead(self):
        # a lone `definition` whose name is implicit in the quoted equation on
        # the next line is named by its LHS head — and because the lookahead
        # reads only the FIRST content line, the following prose is not a
        # candidate (the over-run that once minted 'text\<open>Final')
        snippet = r'''theory T imports Main begin
definition
  "trans_rel \<equiv> {(a, b). foo a b}"
text\<open>Final remark\<close>
end
'''
        sec = section_from(snippet)
        defs = [e for e in sec.entries if e.tag == "DEF"]
        self.assertEqual(len(defs), 1)
        self.assertEqual(defs[0].name, "trans_rel")   # LHS head, not the prose


if __name__ == "__main__":
    unittest.main()
