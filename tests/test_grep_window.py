"""`grep PATH:A..B` scopes the search to a line window.

The "this token matches hundreds of times in one theory, I want a single
region" case — instead of `query grep PAT Foo | awk 'NR>=A&&NR<=B'`.  The
window reuses the `enclosing` `A..B` grammar (via `_parse_locus`), is parsed
only for grep (`_load_sections(windows=True)`), and is honoured at the single
scan chokepoint `_grep_sections`.
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

OWNERS = os.path.join(os.path.dirname(__file__), "fixtures", "Owners.thy")


def _grep(argv):
    parser = cli._build_parser()
    ns = parser.parse_args(["grep"] + argv)
    secs = cli._load_sections(ns, parse="infer", windows=True)
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        cli.cmd_grep(secs, ns.pattern, cli._flags_from_ns(ns))
    return buf.getvalue()


class GrepWindow(unittest.TestCase):

    def test_no_window_finds_all(self):
        # `widget` appears on the definition (lines 5, 6) and size_bound's
        # statement (line 9) — three live matches.
        self.assertEqual(_grep(["widget", OWNERS, "-c"]).strip(), "3")

    def test_window_restricts_count(self):
        # Only the `assumes "x > widget"` line (9) falls inside 8..12.
        self.assertEqual(_grep(["widget", f"{OWNERS}:8..12", "-c"]).strip(),
                         "1")

    def test_window_excludes_outside_lines(self):
        out = _grep(["widget", f"{OWNERS}:8..12"])
        self.assertIn("Owners.thy:9", out)
        self.assertNotIn("Owners.thy:5", out)
        self.assertNotIn("Owners.thy:6", out)

    def test_single_line_window(self):
        # `Foo:LINE` is the `A..B` form with A == B — scope to one line.
        self.assertEqual(_grep(["widget", f"{OWNERS}:6", "-c"]).strip(), "1")


class SplitPathWindow(unittest.TestCase):

    @staticmethod
    def _empty_index():
        return lambda: []        # only file-existence matters for these

    def test_window_when_file_resolves(self):
        win, tok = cli._split_path_window(f"{OWNERS}:8..12", self._empty_index())
        self.assertEqual(win, (8, 12))
        self.assertEqual(tok, OWNERS)

    def test_no_window_without_colon(self):
        win, tok = cli._split_path_window(OWNERS, self._empty_index())
        self.assertIsNone(win)
        self.assertEqual(tok, OWNERS)

    def test_no_window_when_file_missing(self):
        # A `:range` on a non-existent, non-theory file is left untouched, so
        # the normal resolver reports it (not silently treated as a window).
        win, tok = cli._split_path_window("nope.thy:1..9", self._empty_index())
        self.assertIsNone(win)
        self.assertEqual(tok, "nope.thy:1..9")


if __name__ == "__main__":
    unittest.main()
