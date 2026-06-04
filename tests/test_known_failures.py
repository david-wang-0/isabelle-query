r"""Catalogue of parser corner cases that currently lose the name.

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
    def test_infix_abbreviation_operator_name(self):
        # An infix/mixfix definition writes the operator BETWEEN its operands,
        # so the LHS-head heuristic (test_names.ParseName) returns the first
        # operand variable, not the constant.  Reading the true name needs
        # mixfix-aware parsing of the equation; until then this is a known gap.
        snippet = r'''theory T imports Main begin
abbreviation "x \<oplus> y \<equiv> plus x y"
end
'''
        self.assertIn(r"\<oplus>", names_of(snippet))

    # NOTE: two cases once listed here are now handled and have moved to
    # tests/test_names.py as passing tests:
    #   * custom fact-command keywords (AOT's `AOT_theorem`) — the header
    #     keyword scanner (tests/test_keywords.py) reads the `keywords "X" ::
    #     kind` clause that *is* Isabelle's keyword table;
    #   * name on a following line, margin-comment-prefixed name, and the
    #     abbreviation/definition LHS-head name — the continuation lookahead,
    #     _strip_decl_prefix comment skip, and _lhs_head_name respectively.


if __name__ == "__main__":
    unittest.main()
