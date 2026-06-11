"""The `-` PATH sentinel reads a theory from standard input.

`[stdin-path]` in todo.md: piped content that never hit disk should be
queryable, so `git show REF:FILE | query lines - A..B` can inspect a
pre-migration proof without a scratch file.  Two hooks implement it:

  * `_load_sections` grows a `-` branch, parsing the piped stream as a
    theory (entries, live/comment classification, owning-entry labels), so
    the whole *search* family (`grep`/`largest`/`sorry`) gets it at once;
  * `cmd_lines` reads stdin directly, since it bypasses section parsing.

These pin the stdin read, the synthetic `<stdin>` location label, the
one-shot guard (stdin can't be re-read), the preserved line numbering, and
the argparse wiring that lets `-` reach each command.
"""

import argparse
import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

THY = """theory Demo imports Main begin

text \\<open>Prose mentioning foo, skipped by default.\\<close>

lemma foo_bar: "P x = P x"
proof -
  have "P x = P x" by simp
  show ?thesis sorry
qed

lemma baz: "True" by simp

end
"""


@contextlib.contextmanager
def _stdin(text):
    """Feed `text` as the process's standard input for the duration."""
    saved = cli.sys.stdin
    cli.sys.stdin = io.StringIO(text)
    try:
        yield
    finally:
        cli.sys.stdin = saved


def _capture(fn, *args):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args)
    return buf.getvalue()


class LoadSectionsStdin(unittest.TestCase):
    """`_load_sections` parses `-` as a syntax-aware theory section."""

    def _load(self, text, files=("-",)):
        with _stdin(text):
            return cli._load_sections(argparse.Namespace(files=list(files)))

    def test_dash_reads_and_parses_stdin_as_theory(self):
        secs = self._load(THY)
        self.assertEqual(len(secs), 1)
        sec = secs[0]
        # Parsed with the full entry grammar, not degraded to plain text.
        self.assertTrue(sec.is_thy)
        self.assertEqual({e.name for e in sec.entries}, {"foo_bar", "baz"})

    def test_stdin_section_uses_synthetic_location_label(self):
        sec = self._load(THY)[0]
        self.assertEqual(sec.theory, cli._STDIN_NAME)
        # `.name` is what grep/sorry print as the location column.
        self.assertEqual(sec.path.name, cli._STDIN_NAME)

    def test_source_is_cached_so_no_disk_read_of_synthetic_path(self):
        # The synthetic <stdin> path has nothing to read; source() must serve
        # the in-memory lines rather than fall back to path.read_text().
        sec = self._load(THY)[0]
        self.assertEqual(sec.source()[4], 'lemma foo_bar: "P x = P x"')

    def test_repeated_dash_reads_stdin_only_once(self):
        # stdin is a one-shot stream: a second `-` must not yield a second
        # (empty) section from the now-exhausted stream.
        secs = self._load(THY, files=("-", "-"))
        self.assertEqual(len(secs), 1)


class GrepStdin(unittest.TestCase):
    """grep over stdin keeps its live-vs-prose classification."""

    def _grep(self, pattern, with_comments=False):
        flags = cli.CmdFlags()
        flags.with_comments = with_comments
        with _stdin(THY):
            secs = cli._load_sections(argparse.Namespace(files=["-"]))
        return _capture(cli.cmd_grep, secs, pattern, flags)

    def test_default_skips_prose_block(self):
        out = self._grep("foo")
        self.assertIn("1 live match", out)
        self.assertIn("foo_bar", out)
        self.assertNotIn("[in comment/text]", out)

    def test_with_comments_includes_prose(self):
        out = self._grep("foo", with_comments=True)
        self.assertIn("[in comment/text]", out)
        # Location is labelled <stdin>, not a temp path.
        self.assertIn(f"{cli._STDIN_NAME}:3", out)


class SorryStdin(unittest.TestCase):
    def test_sorry_over_stdin_reports_owning_entry(self):
        with _stdin(THY):
            secs = cli._load_sections(argparse.Namespace(files=["-"]))
        out = _capture(cli.cmd_sorry, secs, False)
        self.assertIn(f"{cli._STDIN_NAME}:8", out)
        self.assertIn("foo_bar", out)
        self.assertIn("1 sorry", out)


class LinesStdin(unittest.TestCase):
    """`lines -` reads stdin directly (no section parsing) and keeps the
    piped content's own 1-based numbering."""

    def _lines(self, text, ranges):
        with _stdin(text):
            return _capture(cli.cmd_lines, "-", ranges)

    def test_reads_range_from_stdin_with_preserved_numbers(self):
        out = self._lines(THY, ["5..6"])
        self.assertEqual(
            out.splitlines(),
            ['5| lemma foo_bar: "P x = P x"', "6| proof -"])

    def test_single_line(self):
        out = self._lines(THY, ["1"])
        self.assertEqual(out.strip(), "1| theory Demo imports Main begin")


class ParserWiring(unittest.TestCase):
    """`-` reaches each command through argparse."""

    def setUp(self):
        self.parser = cli._build_parser()

    def test_lines_accepts_dash_as_file(self):
        ns = self.parser.parse_args(["lines", "-", "1..3"])
        self.assertEqual(ns.file, "-")
        self.assertEqual(ns.ranges, ["1..3"])

    def test_search_family_accepts_dash_in_files(self):
        for cmd, extra in (("grep", ["foo"]), ("largest", []), ("sorry", [])):
            with self.subTest(cmd=cmd):
                ns = self.parser.parse_args([cmd, *extra, "-"])
                self.assertEqual(ns.files, ["-"])


if __name__ == "__main__":
    unittest.main()
