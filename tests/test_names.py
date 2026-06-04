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
        # captured as a name (they were before the reserved-prefix guard)
        self.assertEqual(PN(r"\<comment> \<open>a note\<close> rest"), "?")
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

    def test_anonymous_def_does_not_borrow_following_prose(self):
        # a genuinely anonymous definition (implicit LHS name) followed by a
        # text cartouche must stay '?': the lookahead reads only the FIRST
        # content line and must not run on to mint a name from the prose
        snippet = r'''theory T imports Main begin
definition
  "trans_rel \<equiv> {(a, b). foo a b}"
text\<open>Final remark\<close>
end
'''
        sec = section_from(snippet)
        defs = [e for e in sec.entries if e.tag == "DEF"]
        self.assertEqual(len(defs), 1)
        self.assertEqual(defs[0].name, "?")   # not 'text\<open>Final'


if __name__ == "__main__":
    unittest.main()
