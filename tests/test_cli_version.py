"""`query --version` reports the installed version and exits cleanly.

The version has a single source of truth — `pyproject.toml` — baked into the
dist metadata at install time and read back via `importlib.metadata`, so
there is no second literal to keep in sync.  The flag is a lazy custom
`argparse.Action`, so the metadata lookup is not paid on every `query` run.
The assertions stay env-agnostic: they tie the printed string to the
resolver and to a 0-exit, without hardcoding a version number (which would
break on every bump) — and they hold even where the package is not installed
(the resolver then returns its fallback string).
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402


class VersionFlag(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def _run_version(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaises(SystemExit) as cm:
                self.parser.parse_args(["--version"])
        return cm.exception.code, buf.getvalue().strip()

    def test_version_exits_zero(self):
        code, _ = self._run_version()
        self.assertEqual(code, 0)

    def test_version_prints_query_and_resolved_version(self):
        _, out = self._run_version()
        self.assertTrue(out.startswith("query "))
        # The printed version is exactly what the resolver returns — this ties
        # the flag's output to the single resolver, with no hardcoded number.
        self.assertEqual(out, f"query {cli._resolve_version()}")

    def test_resolver_returns_nonempty(self):
        self.assertTrue(cli._resolve_version())


class VersionPosition(unittest.TestCase):
    """`--version` is accepted wherever the user has got to on the line.

    Asking which version you are running should not require retyping the
    command, so the flag rides on every subparser the way `-R/--root` does —
    including the nested `shape` verbs.  It fires while arguments are being
    read, so it wins over the rest of the line, a missing required positional
    included (the same contract as `--help`).
    """

    def setUp(self):
        self.parser = cli._build_parser()

    def _version_of(self, argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaises(SystemExit) as cm:
                self.parser.parse_args(argv)
        return cm.exception.code, buf.getvalue().strip()

    def test_short_form_at_top_level(self):
        self.assertEqual(self._version_of(["-V"]),
                         (0, f"query {cli._resolve_version()}"))

    def test_after_a_subcommand(self):
        code, out = self._version_of(["callers", "foo", "--version"])
        self.assertEqual((code, out), (0, f"query {cli._resolve_version()}"))

    def test_after_a_nested_shape_verb(self):
        code, out = self._version_of(["shape", "census", "--version"])
        self.assertEqual((code, out), (0, f"query {cli._resolve_version()}"))

    def test_wins_over_a_missing_required_positional(self):
        # `callers` needs a NAME; --version still reports and exits 0 rather
        # than erroring out with usage.
        code, out = self._version_of(["callers", "--version"])
        self.assertEqual((code, out), (0, f"query {cli._resolve_version()}"))

    def test_short_form_still_means_verbatim_on_show(self):
        # `-V` is `--verbatim` on show/find and predates the version alias, so
        # it must NOT have been repointed: silently changing an existing flag's
        # meaning is a worse break than confining the alias to the top level.
        ns = self.parser.parse_args(["show", "foo", "-V"])
        self.assertTrue(ns.verbatim)


if __name__ == "__main__":
    unittest.main()
