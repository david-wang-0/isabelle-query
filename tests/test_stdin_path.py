"""The `-` PATH sentinel reads a theory from standard input.

`[stdin-path]` in todo.md: piped content that never hit disk should be
queryable, so `git show REF:FILE | query lines - A..B` can inspect a
pre-migration proof without a scratch file.

Routing (`-`/path/name → a source) is shared across every `CMD FILES`-shaped
command through `_resolve_file_source`; the parse policy (syntax-aware vs.
raw) is the command's own property, applied by `_section_from`:

  * the search family (`grep`/`largest`/`sorry`) parses sources into
    TheorySections — `largest`/`sorry` always syntax-aware, `grep` inferred
    from the `.thy` suffix (stdin, suffix-less, defaults to syntax);
  * `lines` is ignore-syntax: it routes the token the same way but hands the
    raw lines straight to `cmd_lines`, no parsing.

These pin the shared routing, the parse-policy split, the synthetic
`<stdin>` location label, the one-shot guard (stdin can't be re-read), the
preserved line numbering, and the argparse wiring that lets `-` reach each
command.
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
from support import cli, section_from  # noqa: E402

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
    """`lines -` routes stdin through the shared resolver (no section
    parsing) and keeps the piped content's own 1-based numbering.  Driven
    through `_run_lines` so the routing seam is exercised, not bypassed."""

    def _lines(self, text, ranges):
        with _stdin(text):
            return _capture(cli._run_lines,
                            argparse.Namespace(file="-", ranges=ranges))

    def test_reads_range_from_stdin_with_preserved_numbers(self):
        out = self._lines(THY, ["5..6"])
        self.assertEqual(
            out.splitlines(),
            ['5| lemma foo_bar: "P x = P x"', "6| proof -"])

    def test_single_line(self):
        out = self._lines(THY, ["1"])
        self.assertEqual(out.strip(), "1| theory Demo imports Main begin")


class SharedRouting(unittest.TestCase):
    """`_resolve_file_source` is the one token→source resolver shared by the
    search family and `lines`, so routing can't drift between them."""

    def test_existing_file_resolves_to_itself(self):
        with tempfile.NamedTemporaryFile("w", suffix=".thy",
                                         delete=False) as fh:
            fh.write(THY)
            path = Path(fh.name)
        try:
            src = cli._resolve_file_source(str(path), path.resolve(),
                                           lambda: [])
            self.assertEqual(src.path, path.resolve())
            self.assertEqual(src.label, path.stem)
            self.assertFalse(src.from_stdin)
        finally:
            os.unlink(path)

    def test_bare_name_resolves_via_index(self):
        # A token that is not an on-disk path falls back to a theory NAME
        # looked up in the (here faked) index — the outline/show/defs path.
        sec = section_from(THY, "Demo")
        src = cli._resolve_file_source("Demo", Path("Demo").resolve(),
                                       lambda: [sec])
        self.assertEqual(src.path, sec.path)
        self.assertEqual(src.label, sec.path.stem)

    def test_unknown_token_exits_with_hint(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            with self.assertRaises(SystemExit):
                cli._resolve_file_source("Nope", Path("Nope").resolve(),
                                         lambda: [])
        self.assertIn("not a path or known theory", err.getvalue())


class ParsePolicy(unittest.TestCase):
    """`_section_from` applies the *command's* parse policy; the `.thy`
    suffix is only evidence for the inferred (`grep`) case."""

    def _src(self, path):
        return cli.FileSource(Path(path).stem, Path(path),
                              preread=THY.splitlines())

    def test_syntax_forces_grammar_even_on_nonthy(self):
        # largest/sorry policy: entries are the output, so parse regardless
        # of the (here misleading) `.md` extension.
        sec = cli._section_from(self._src("note.md"), "syntax")
        self.assertTrue(sec.is_thy)
        self.assertEqual({e.name for e in sec.entries}, {"foo_bar", "baz"})

    def test_infer_is_plain_on_nonthy(self):
        sec = cli._section_from(self._src("note.md"), "infer")
        self.assertFalse(sec.is_thy)
        self.assertEqual(sec.entries, [])

    def test_infer_is_syntax_on_thy(self):
        sec = cli._section_from(self._src("Demo.thy"), "infer")
        self.assertTrue(sec.is_thy)

    def test_infer_defaults_stdin_to_syntax(self):
        # stdin has no suffix; the inferred policy still parses it (the
        # load-bearing case is a piped theory).
        src = cli.FileSource(cli._STDIN_NAME, cli._STDIN_PATH,
                             preread=THY.splitlines())
        self.assertTrue(src.from_stdin)
        sec = cli._section_from(src, "infer")
        self.assertTrue(sec.is_thy)
        self.assertEqual({e.name for e in sec.entries}, {"foo_bar", "baz"})


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
