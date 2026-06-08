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


if __name__ == "__main__":
    unittest.main()
