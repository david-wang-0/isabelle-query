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


if __name__ == "__main__":
    unittest.main()
