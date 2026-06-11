"""`lines` accepts both `FILE RANGE...` and colon-form `FILE:RANGE ...`.

The colon form is the `enclosing` locus grammar reused: a span printed by
`outline` / `largest` / `enclosing` (now `A..B`) pastes back into `lines` as
`FILE:A..B`.  Both forms coexist; the colon batch must name one file.
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

OWNERS = os.path.join(os.path.dirname(__file__), "fixtures", "Owners.thy")


def _run_lines(argv):
    parser = cli._build_parser()
    ns = parser.parse_args(["lines"] + argv)
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        cli._run_lines(ns)
    return buf.getvalue()


class FileAndRangesSplit(unittest.TestCase):

    def test_classic_form(self):
        self.assertEqual(
            cli._lines_file_and_ranges(["Foo", "1..10", "20..30"]),
            ("Foo", ["1..10", "20..30"]))

    def test_colon_form_one_file(self):
        self.assertEqual(
            cli._lines_file_and_ranges(["Foo:1..10", "Foo:20..30"]),
            ("Foo", ["1..10", "20..30"]))

    def test_colon_form_single_line(self):
        # `Foo:6` is the `A..B` form with A == B.
        self.assertEqual(cli._lines_file_and_ranges(["Foo:6"]),
                         ("Foo", ["6..6"]))


class EndToEnd(unittest.TestCase):

    def test_classic_and_colon_agree(self):
        classic = _run_lines([OWNERS, "5..6"])
        colon = _run_lines([f"{OWNERS}:5..6"])
        self.assertEqual(classic, colon)
        self.assertIn("definition widget :: nat where", classic)

    def test_colon_multi_range_one_file(self):
        out = _run_lines([f"{OWNERS}:5..6", f"{OWNERS}:15..16"])
        self.assertIn("definition widget :: nat where", out)   # line 5
        self.assertIn("lemma comm_add", out)                   # line 15
        self.assertIn("--", out)                               # range separator


class Errors(unittest.TestCase):

    def test_multi_file_colon_rejected(self):
        with self.assertRaises(SystemExit):
            cli._lines_file_and_ranges(["A:1..2", "B:3..4"])

    def test_mixed_forms_rejected(self):
        with self.assertRaises(SystemExit):
            cli._lines_file_and_ranges(["A:1..2", "3..4"])

    def test_no_range_rejected(self):
        with self.assertRaises(SystemExit):
            cli._lines_file_and_ranges(["Foo"])


if __name__ == "__main__":
    unittest.main()
