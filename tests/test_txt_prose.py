r"""`txt` prose is prose, not Isar [txt-prose].

Isabelle has two document commands for the two positions prose can occupy:
`text` between declarations, and **`txt` between proof steps**.  Only the first
was in `TEXT_OPEN_RE`, so a `txt` body was never recorded as a prose block —
and since that is the single source for `graph._noise_spans`, the step and
method scanners read its English as Isar.

The scale, from `scripts/probe_txt_blocks.py` over the whole AFP: 542 blocks in
103 theories across 51 entries, yielding **97 phantom proof steps**, 26 of them
carrying a `by`/`apply` introducer whose next word was then mined as a proof
method.  The tokens that gave it away are English, not Isar: `the`, `a`, `an`,
`means`, `moving`, `replacing`.  It contradicted `_scan_methods`' own promise
that "an `apply`/`by` mentioned in prose does not register as a method use".

Note what does NOT change: `live_source()` still shows the prose, because it
redacts only what the *tokenizer* reports and a document block is introduced by
a command, not a lexical marker.  The two are deliberately separate notions;
the masking that matters here happens at line level, which is why one
alternation in `TEXT_OPEN_RE` was the whole fix.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

from isabelle_query import parsing, shape  # noqa: E402

HEAD = "theory T imports Main begin\n"
FOOT = "\nend\n"

# Formal_SSA/Minimality.thy:543-548, trimmed: an `\<^item>` list whose third
# bullet reads "reachable by at least two different direct assignments".  That
# `by` is English.  The `apply` in the last bullet is added from the same
# corpus's prose vocabulary to cover the other introducer.
REAL_PROSE = r'''
lemma phiArg_nontrivial: "True"
proof -
  have step: "True" by simp
  txt \<open>They can either be:
    \<^item> The result of a direct assignment to v.
    \<^item> The result of a necessary $\phi$ function r' . This however means
      that r' was reachable by at least two different direct assignments to v.
    \<^item> Another unnecessary $\phi$ function, apply the same argument.\<close>
  show "True" by simp
qed
'''


class TxtIsAProseBlock(unittest.TestCase):

    def test_txt_block_is_recorded(self):
        sec = section_from(HEAD + REAL_PROSE + FOOT)
        # The `txt` line and its closing `\<close>` line bracket the block.
        blocks = parsing.extract_text_blocks(sec.source())
        self.assertEqual(len(blocks), 1, f"blocks: {blocks}")
        start, end = blocks[0]
        self.assertTrue(sec.source()[start - 1].strip().startswith("txt"))
        self.assertIn("\\<close>", sec.source()[end - 1])

    def test_no_proof_step_lands_in_the_prose(self):
        sec = section_from(HEAD + REAL_PROSE + FOOT)
        entry = next(e for e in sec.entries if e.name == "phiArg_nontrivial")
        pm = shape.analyze_proof(sec, entry)
        prose = range(*[b + (0, 1)[i] for i, b in
                        enumerate(parsing.extract_text_blocks(sec.source())[0])])
        inside = [(s.line, s.kw) for s in pm.steps if s.line in prose]
        self.assertEqual(inside, [], f"phantom steps: {inside}")

    def test_the_english_by_registers_no_method(self):
        # `simp` twice, from the two real steps, and nothing from the prose.
        #
        # Be clear about what this pins TODAY: `_leading_method` also requires
        # the token after `by`/`apply` to be in the bound method table, and
        # neither `at` nor `the` is a method, so this assertion holds without
        # the prose fix as well.  The table check has been *masking* the leak.
        # It is asserted here because [introducer-no-table] removes that check
        # -- at which point English after a prose `by` would be promoted to a
        # named method, and this becomes the test that catches it.  That is the
        # whole reason this fix has to land first.
        counts, _ = cli._scan_methods([section_from(HEAD + REAL_PROSE + FOOT)])
        self.assertNotIn("at", counts)
        self.assertNotIn("the", counts)
        self.assertEqual(counts.get("simp"), 2)


class TheOtherSpellings(unittest.TestCase):

    def test_text_block_still_recognised(self):
        # Regression guard: the alternation must not have displaced `text`.
        sec = section_from(HEAD + '\ntext \\<open>Between declarations.\\<close>\n'
                           + 'lemma a: "True" by simp\n' + FOOT)
        self.assertEqual(len(parsing.extract_text_blocks(sec.source())), 1)

    def test_text_raw_still_recognised(self):
        sec = section_from(HEAD + '\ntext_raw \\<open>\\\\clearpage\\<close>\n'
                           + 'lemma a: "True" by simp\n' + FOOT)
        self.assertEqual(len(parsing.extract_text_blocks(sec.source())), 1)

    def test_bare_cartouche_spelling(self):
        # `txt ‹...›` is the same command written with the cartouche glyph.
        # Absent from the AFP, but recognising one spelling and not the other is
        # a leak waiting for the project that prefers it — and `COMMENT_LINE_RE`
        # already takes both.
        sec = section_from(HEAD + '\nlemma a: "True"\nproof -\n'
                           + '  txt ‹proved by hand›\n'
                           + '  show "True" by simp\nqed\n' + FOOT)
        self.assertEqual(len(parsing.extract_text_blocks(sec.source())), 1)

    def test_txt_raw_is_not_a_command(self):
        # Pins a decision, not an accident.  `txt_raw` is NOT in
        # `_isabelle_namespace.KEYWORDS` (extracted from a running
        # Isabelle2025-2) and has zero occurrences in the AFP, so treating it as
        # a document command would be inventing one.  If a future Isabelle
        # reinstates it, this test is the place that says so.
        from isabelle_query import _isabelle_namespace as isa_ns
        self.assertIn("txt", isa_ns.KEYWORDS)
        self.assertNotIn("txt_raw", isa_ns.KEYWORDS)
        sec = section_from(HEAD + '\ntxt_raw \\<open>not a command\\<close>\n'
                           + FOOT)
        self.assertEqual(parsing.extract_text_blocks(sec.source()), [])


if __name__ == "__main__":
    unittest.main()
