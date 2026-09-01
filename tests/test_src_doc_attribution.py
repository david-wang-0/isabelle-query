r"""`[src-doc-attribution]` — a leading `text` doc block belongs to the entry
it documents, not the preceding one.

The bug (filed during the AR Forward split): `src`/`thy_end` was
computed as `next_entry_thy_line - 1`, so the inter-entry blank *and the
following entry's leading `text` docstring* were charged to the **preceding**
entry.  Two visible symptoms, both reproduced here:

  (a) `enclosing FILE:L` for an `L` inside a leading doc block named the
      *previous* lemma, not the lemma that doc documents;
  (b) the `src A..B` shown by show / find / outline / enclosing overstated the
      entry by that trailing doc.

`body` (thy_line..body_end_line) was always correct and stays so — these tests
pin that the *src* span and the *attribution* now agree with the body.

The fixture mirrors the report's `ar_write_back_loop` / `ar_write_fwd_loop_gen`
shape: a proved lemma, then a small `text` block documenting the next lemma,
then that lemma.  Spans are read off the parsed section, so the assertions
track the parser rather than hardcoding line numbers.
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

THY = r'''theory T imports Main begin

lemma back_loop:
  "P x"
  by simp

text \<open>
  This block documents the forward lemma below.
  It is fwd_loop_gen's docstring, not back_loop's trailing text.
\<close>

lemma fwd_loop_gen:
  "Q x"
  by simp

end
'''


def _sec():
    return section_from(THY, "T")


def _entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


class PreambleOwnership(unittest.TestCase):
    def test_doc_block_is_the_following_entry_preamble(self):
        sec = _sec()
        fwd = _entry(sec, "fwd_loop_gen")
        self.assertIsNotNone(fwd.preamble)
        # src starts at the doc block, well before the declaration line.
        self.assertEqual(fwd.src_start, fwd.preamble[0])
        self.assertLess(fwd.src_start, fwd.thy_line)

    def test_preceding_entry_src_stops_before_the_doc(self):
        sec = _sec()
        back, fwd = _entry(sec, "back_loop"), _entry(sec, "fwd_loop_gen")
        # back_loop must NOT reach into fwd_loop_gen's docstring.
        self.assertLess(back.thy_end, fwd.preamble[0])
        # and its src no longer overstates past its own body + trailing blanks.
        self.assertGreaterEqual(back.thy_end, back.body_end_line)

    def test_body_span_is_unchanged_and_inside_src(self):
        sec = _sec()
        fwd = _entry(sec, "fwd_loop_gen")
        # body is the lemma itself (decl..proof end), strictly inside src.
        self.assertEqual(fwd.body_end_line, fwd.thy_end)   # nothing trails it
        self.assertGreater(fwd.thy_line, fwd.src_start)    # preamble precedes


class EnclosingAttribution(unittest.TestCase):
    """Symptom (a): a line in the leading doc must resolve to the documented
    entry, not the preceding one."""

    def _owner(self, sec, line_no):
        return cli._enclosing_entry(sec, line_no)

    def test_doc_line_resolves_to_following_entry(self):
        sec = _sec()
        fwd = _entry(sec, "fwd_loop_gen")
        doc_mid = fwd.preamble[0] + 1            # a line inside the doc block
        owner = self._owner(sec, doc_mid)
        self.assertIsNotNone(owner)
        self.assertEqual(owner.name, "fwd_loop_gen")
        self.assertEqual(cli._locus_role(owner, doc_mid), "in preamble")

    def test_back_loop_still_owns_its_proof(self):
        sec = _sec()
        back = _entry(sec, "back_loop")
        owner = self._owner(sec, back.proof_line)
        self.assertEqual(owner.name, "back_loop")
        self.assertEqual(cli._locus_role(owner, back.proof_line), "in proof")

    def test_binary_search_index_agrees(self):
        # The fast _entry_at_line path (call-graph / grep owner) must agree
        # with the linear _enclosing_entry on the doc line.
        sec = _sec()
        fwd = _entry(sec, "fwd_loop_gen")
        idx = cli._build_line_index([sec])[sec.path]
        owner = cli._entry_at_line(idx, fwd.preamble[0] + 1)
        self.assertEqual(owner.name, "fwd_loop_gen")


class ExtentRendering(unittest.TestCase):
    """Symptom (b): the `[src ...]` annotation must not overstate."""

    def test_following_entry_extent_shows_preamble_and_body(self):
        sec = _sec()
        fwd = _entry(sec, "fwd_loop_gen")
        ext = cli._format_extent(fwd)
        # src starts at the doc, body starts at the declaration.
        self.assertIn(f"src {fwd.src_start}..{fwd.thy_end}", ext)
        self.assertIn(f"body {fwd.thy_line}..{fwd.body_end_line}", ext)

    def test_preceding_entry_extent_does_not_reach_the_doc(self):
        sec = _sec()
        back, fwd = _entry(sec, "back_loop"), _entry(sec, "fwd_loop_gen")
        ext = cli._format_extent(back)
        self.assertIn(f"src {back.src_start}..{back.thy_end}", ext)
        # the doc block's start is past back_loop's src end
        self.assertNotIn(str(fwd.preamble[0]), ext.split("body")[0])


class EndToEndEnclosing(unittest.TestCase):
    """The user-visible `query enclosing` output for a doc-block line."""

    def _run(self, locus):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            cli.cmd_enclosing([_sec()], [locus])
        return out.getvalue(), err.getvalue()

    def test_doc_line_names_the_documented_lemma(self):
        sec = _sec()
        fwd = _entry(sec, "fwd_loop_gen")
        out, err = self._run(f"T:{fwd.preamble[0] + 1}")
        self.assertIn("fwd_loop_gen (LEMMA)", out)
        self.assertIn("(in preamble)", out)
        self.assertNotIn("back_loop", out)
        self.assertEqual(err, "")


if __name__ == "__main__":
    unittest.main()
