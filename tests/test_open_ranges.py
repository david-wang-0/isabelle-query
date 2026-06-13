"""Open-ended line ranges: `A..` (to EOF) and `..B` (from line 1).

Regression for the open-range grammar.  `_parse_line_range` is the *single*
split point for every range surface — `lines`, and the `enclosing` / `grep`
`FILE:A..B` locus — so the open forms light up across all three at once.
An open *upper* bound returns ``end is None`` (the parser holds no file, so
"to EOF" is each sink's job, resolved against its own source length); an
open *lower* bound needs no sentinel — the start of a file is always line 1
— so it resolves in the parser itself.

The theory fixture is built on disk in `setUp` (`cmd_enclosing` / `cmd_grep`
re-read each section's `path` via `sec.source()`), promoted from the manual
smoke probes that first exercised the feature so the behaviour stays pinned.
"""

import argparse
import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


# A small multi-entry theory whose `simp` proofs sit on known lines, so a
# window/range can include or exclude individual entries by number:
#   1  theory Demo imports Main begin
#   3  lemma alpha   (src 3..4)
#   5  lemma beta    (src 5..6)
#   7  definition gamma   (src 7..8)
#   9  lemma delta   (src 9..11)
#  11  end
DEMO = (
    'theory Demo imports Main begin\n'
    '\n'
    'lemma alpha: "True" by simp\n'
    '\n'
    'lemma beta: "1 = 1" by simp\n'
    '\n'
    'definition gamma :: nat where "gamma = 3"\n'
    '\n'
    'lemma delta: "2 = 2" by simp\n'
    '\n'
    'end\n'
)


class ParseLineRange(unittest.TestCase):
    """`_parse_line_range`: the one split point, now including the open forms."""

    def test_closed_range(self):
        self.assertEqual(cli._parse_line_range("5..9"), (5, 9))

    def test_single_line(self):
        self.assertEqual(cli._parse_line_range("7"), (7, 7))

    def test_open_upper_is_none(self):
        # `A..` → (A, None): the parser has no file, so EOF is the sink's job.
        self.assertEqual(cli._parse_line_range("5600.."), (5600, None))

    def test_open_lower_resolves_to_one(self):
        # `..B` → (1, B): the start of a file is always line 1, no sentinel.
        self.assertEqual(cli._parse_line_range("..12"), (1, 12))

    def test_both_open_is_whole_file(self):
        self.assertEqual(cli._parse_line_range(".."), (1, None))

    def test_descending_closed_rejected(self):
        with self.assertRaises(ValueError):
            cli._parse_line_range("9..5")

    def test_zero_start_rejected(self):
        with self.assertRaises(ValueError):
            cli._parse_line_range("0..3")

    def test_empty_single_rejected(self):
        # A bare empty token is still malformed (only the `..` sides may empty).
        with self.assertRaises(ValueError):
            cli._parse_line_range("")

    def test_garbage_rejected(self):
        with self.assertRaises(ValueError):
            cli._parse_line_range("abc")


class LinesOpenRange(unittest.TestCase):
    """`lines` resolves an open upper bound to EOF, an open lower to line 1.

    `cmd_lines` takes the source as a list, so no fixture file is needed —
    the `NR` it prints is the list's own 1-based index."""

    SRC = [f"l{n}" for n in range(1, 7)]   # l1 .. l6

    def _lines(self, *ranges):
        return _capture(cli.cmd_lines, self.SRC, list(ranges))

    def test_open_upper_runs_to_eof(self):
        self.assertEqual(self._lines("4.."), "4| l4\n5| l5\n6| l6\n")

    def test_open_lower_runs_from_one(self):
        self.assertEqual(self._lines("..2"), "1| l1\n2| l2\n")

    def test_both_open_is_whole_file(self):
        self.assertEqual(self._lines(".."),
                         "".join(f"{n}| l{n}\n" for n in range(1, 7)))

    def test_colon_form_open_upper_round_trips(self):
        # `FILE:A..` is reconstructed back to the `A..` space form before
        # re-parsing; it must not become `A..None`.
        self.assertEqual(self._lines("4.."), "4| l4\n5| l5\n6| l6\n")

    def test_open_upper_past_eof_echoes_open_spec(self):
        # The diagnostic must echo `9..`, never the resolved `9..None`/`9..6`.
        err = io.StringIO()
        with contextlib.redirect_stderr(err), \
                contextlib.redirect_stdout(io.StringIO()):
            cli.cmd_lines(self.SRC, ["9.."])
        self.assertIn("range 9..: past end of file", err.getvalue())
        self.assertNotIn("None", err.getvalue())


class _DemoTree(unittest.TestCase):
    """Shared on-disk Demo.thy fixture for the theory-aware surfaces."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "Demo.thy").write_text(DEMO)

    def tearDown(self):
        self._tmp.cleanup()


class EnclosingOpenRange(_DemoTree):

    def _enc(self, *loci, **kw):
        ns = argparse.Namespace(files=[str(self.root)])
        sections = cli._load_sections(ns)
        return _capture(cli.cmd_enclosing, sections, list(loci), **kw)

    def test_open_upper_lists_to_eof(self):
        # beta(5), gamma(7), delta(9) overlap [5, EOF]; alpha(3..4) does not.
        out = self._enc("Demo:5..")
        self.assertIn("beta", out)
        self.assertIn("gamma", out)
        self.assertIn("delta", out)
        self.assertNotIn("alpha", out)

    def test_open_upper_echoes_resolved_span(self):
        # The printed locus resolves `..` to the concrete last line so it
        # round-trips: `Demo:5..11`, never `Demo:5..None`.
        out = self._enc("Demo:5..")
        self.assertIn("Demo:5..11", out)
        self.assertNotIn("None", out)

    def test_open_lower_lists_from_start(self):
        out = self._enc("Demo:..6")
        self.assertIn("alpha", out)
        self.assertIn("beta", out)
        self.assertNotIn("delta", out)


class GrepOpenWindow(_DemoTree):

    def _grep_window(self, token):
        ns = argparse.Namespace(files=[token])
        sections = cli._load_sections(ns, windows=True)
        return _capture(cli.cmd_grep, sections, "simp", cli.CmdFlags())

    def test_open_window_restricts_to_tail(self):
        # `simp` is on lines 3 (alpha), 5 (beta), 9 (delta).  An open window
        # from line 7 must see only delta.
        demo = str(self.root / "Demo.thy")
        out = self._grep_window(f"{demo}:7..")
        self.assertIn("delta", out)
        self.assertNotIn("alpha", out)
        self.assertNotIn("beta", out)


if __name__ == "__main__":
    unittest.main()
