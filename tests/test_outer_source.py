r"""`TheorySection.outer_source` — the source reduced to command position.

Isar is whitespace-insensitive, so "column 0" is a proxy for "a command can
start here" and a poor one: an author who indents a theory body drops out of
the index entirely (`Error_Monad_Add`, 53 lines, 0 entries), while the same
anchor would read a `lemma` written inside a term as a declaration if it ever
moved.  The tokenizer already tracks the states that decide the question — it
just never reported them.

Two views, one shape contract (line count and every column preserved):

  * `live_source`  blanks NOISE — comments, ML, cancelled text.  Keeps terms,
    because `lemma "mono f"` cites `mono` and a citation scan must see it.
  * `outer_source` blanks noise AND inner syntax.  A term is live Isar that is
    nonetheless not a place a command can begin.

Neither is a refinement of the other in usefulness — they answer different
questions — but as character sets `outer ⊆ live`, which is asserted below.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402


def outer(snippet):
    return section_from(snippet).outer_source()


class Shape(unittest.TestCase):
    """The contract every scanner leans on: nothing moves."""

    SNIP = ('theory A imports Main begin\n'
            'lemma foo: "mono f"  (* a note *)\n'
            '  by simp\n'
            'end\n')

    def test_line_count_is_preserved(self):
        sec = section_from(self.SNIP)
        self.assertEqual(len(sec.outer_source()), len(sec.source()))

    def test_every_column_is_preserved(self):
        sec = section_from(self.SNIP)
        self.assertEqual([len(l) for l in sec.outer_source()],
                         [len(l) for l in sec.source()])

    def test_outer_is_a_subset_of_live(self):
        # Character by character: anything blanked in `live` stays blanked.
        sec = section_from(self.SNIP)
        for raw, live, out in zip(sec.source(), sec.live_source(),
                                  sec.outer_source()):
            for c_raw, c_live, c_out in zip(raw, live, out):
                if c_live == " " and c_raw != " ":
                    self.assertEqual(c_out, " ")


class WhatIsBlanked(unittest.TestCase):
    def test_a_quoted_term_goes(self):
        got = outer('theory A imports Main begin\n'
                    'lemma foo: "mono f"\n'
                    '  by simp\n'
                    'end\n')
        self.assertEqual(got[1], 'lemma foo: ' + ' ' * 8)

    def test_a_cartouche_term_goes(self):
        got = outer('theory A imports Main begin\n'
                    'lemma foo: \\<open>mono f\\<close>\n'
                    '  by simp\n'
                    'end\n')
        self.assertEqual(got[1].rstrip(), 'lemma foo:')

    def test_a_comment_goes_too(self):
        # outer_source blanks everything live_source does, and more.
        got = outer('theory A imports Main begin\n'
                    'lemma foo: "x"  (* why *)\n'
                    '  by simp\n'
                    'end\n')
        self.assertNotIn("why", got[1])

    def test_a_multi_line_term_is_blanked_on_every_line(self):
        got = outer('theory A imports Main begin\n'
                    'lemma foo:\n'
                    '  "mono f \\<Longrightarrow>\n'
                    '   mono g"\n'
                    '  by simp\n'
                    'end\n')
        self.assertEqual(got[2].strip(), "")
        self.assertEqual(got[3].strip(), "")

    def test_a_keyword_inside_a_term_is_blanked(self):
        r"""The false positive the column-0 anchor was really buying insurance
        against — and which `outer_source` removes at the source."""
        got = outer('theory A imports Main begin\n'
                    'lemma foo: "the lemma begin end thing"\n'
                    '  by simp\n'
                    'end\n')
        self.assertEqual(got[1].split('"')[0].strip(), "lemma foo:")
        self.assertNotIn("begin", got[1][11:])


class WhatSurvives(unittest.TestCase):
    """What is left is exactly the outer command skeleton."""

    def test_the_command_keyword_survives(self):
        got = outer('theory A imports Main begin\n'
                    'lemma foo: "x" by simp\n'
                    'end\n')
        self.assertIn("lemma foo:", got[1])
        self.assertIn("by simp", got[1])

    def test_an_indented_declaration_survives(self):
        # `Error_Monad_Add` indents its whole body inside the theory's own
        # `begin`.  Nothing about that changes where the command is.
        got = outer('theory A imports Main begin\n'
                    '  definition f where "f x = x"\n'
                    '  lemma foo: "f x = x" by (simp add: f_def)\n'
                    'end\n')
        self.assertEqual(got[1].strip().split('"')[0].strip(),
                         "definition f where")
        self.assertIn("lemma foo:", got[2])

    def test_block_tokens_survive_outside_terms(self):
        got = outer('theory A imports Main begin\n'
                    'context foo begin\n'
                    'lemma bar: "x" by simp\n'
                    'end\n'
                    'end\n')
        self.assertIn("begin", got[1])
        self.assertIn("end", got[3])


class BlockBalance(unittest.TestCase):
    r"""The property that makes a stack model viable.

    Every Isar target block — theory, locale, class, context, instantiation,
    overloading, bundle, experiment, notepad — opens with the token `begin` and
    closes with `end`, whatever command introduced it.  So block structure needs
    no opener-to-closer table, just one pair counted at outer-syntax position.
    Measured over 1,662 AFP theories: balanced in 100.00%, max depth 5
    (`scripts/probe_block_structure.py`).
    """

    def depth(self, snippet):
        d = 0
        for line in outer(snippet):
            for tok in line.split():
                tok = tok.strip("()[],")
                if tok == "begin":
                    d += 1
                elif tok == "end":
                    d -= 1
        return d

    def test_a_plain_theory_balances(self):
        self.assertEqual(self.depth('theory A imports Main begin\n'
                                    'lemma foo: "x" by simp\n'
                                    'end\n'), 0)

    def test_a_nested_context_balances(self):
        self.assertEqual(self.depth('theory A imports Main begin\n'
                                    'locale L begin\n'
                                    'context fixes y begin\n'
                                    '  lemma foo: "x" by simp\n'
                                    'end\n'
                                    'end\n'
                                    'end\n'), 0)

    def test_a_term_mentioning_end_does_not_unbalance_it(self):
        # Counting on the raw source would go negative here.
        self.assertEqual(self.depth('theory A imports Main begin\n'
                                    'lemma foo: "front_end = back_end"\n'
                                    '  by simp\n'
                                    'end\n'), 0)

    def test_a_comment_mentioning_begin_does_not_unbalance_it(self):
        self.assertEqual(self.depth('theory A imports Main begin\n'
                                    '(* begin the interesting part *)\n'
                                    'lemma foo: "x" by simp\n'
                                    'end\n'), 0)


if __name__ == "__main__":
    unittest.main()
