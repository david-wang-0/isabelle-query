"""Catalogue of parser corner cases that currently lose the name.

Each test asserts the *desired* result and is marked ``@unittest.expectedFailure``.
Today they stay green (the failure is expected); the moment the parser is
improved to handle a case, unittest reports an "unexpected success" — a built-in
prompt to delete the marker.  This turns the intricate AFP analysis behind these
gaps into an executable to-do list toward 100% coverage.

Frequencies are approximate counts over ndtht/afp/thys (~360k entries) at the
time of writing; see the call-graph/parser commits for the full analysis.

What is *not* here (because it is correct, not a gap): genuinely anonymous
declarations — `lemma "P"`, `lemma [simp]: ...`, `abbreviation \<open>...\<close>`
— must stay '?'.  Those are asserted as passing tests in test_names.py.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402


def names_of(snippet):
    return [e.name for e in section_from(snippet).entries]


class RecoverableParserGaps(unittest.TestCase):

    @unittest.expectedFailure
    def test_name_on_next_line_inductive_set(self):
        # ~1,866 AFP entries: the keyword stands alone, the name is on the
        # following line.  Needs continuation lookahead in extract_entries.
        snippet = r'''theory T imports Main begin
inductive_set
  myset :: "nat set"
where base: "0 \<in> myset"
end
'''
        self.assertIn("myset", names_of(snippet))

    @unittest.expectedFailure
    def test_name_on_next_line_definition(self):
        snippet = r'''theory T imports Main begin
definition
  foo :: "nat" where "foo = 0"
end
'''
        self.assertIn("foo", names_of(snippet))

    @unittest.expectedFailure
    def test_name_after_inline_comment(self):
        # ~190 AFP entries: a \<comment> \<open>...\<close> annotation precedes
        # the name.  Needs the prefix-stripper to skip a leading comment.
        snippet = r'''theory T imports Main begin
definition \<comment> \<open>a note\<close> bar :: "nat" where "bar = 0"
end
'''
        self.assertIn("bar", names_of(snippet))

    @unittest.expectedFailure
    def test_abbreviation_lhs_head_name(self):
        # Abbreviations defined by an equation in a cartouche/quotes carry no
        # separate label; the useful name is the head of the LHS.  Needs the
        # parser to read the first token before \<equiv>.
        snippet = r'''theory T imports Main begin
abbreviation "language_ltlc \<phi> \<equiv> {\<xi>. \<xi> \<Turnstile> \<phi>}"
end
'''
        self.assertIn("language_ltlc", names_of(snippet))

    @unittest.expectedFailure
    def test_custom_fact_command_keyword(self):
        # AFP entries (e.g. AOT) define their own fact commands through the
        # command framework, conventionally <Prefix>_theorem.  Recognising the
        # family would also fix the inflated entry spans they cause.
        snippet = r'''theory T imports Main begin
AOT_theorem "foo:1": \<open>p \<rightarrow> p\<close>
end
'''
        self.assertIn("foo:1", names_of(snippet))


if __name__ == "__main__":
    unittest.main()
