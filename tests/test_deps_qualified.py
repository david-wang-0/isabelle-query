"""`deps` / `uses` resolve session-qualified in-project imports.

Regression for `[deps-qualified]`.  `parse_thy_imports` returns the raw
`imports`-clause token, but the section index is keyed by **bare** theory
name.  A same-session import is written bare and matched; a cross-session
import is session-qualified (`"NDTHT_Base.Substrate"`) and was misrouted —
`deps` tagged it `[out-of-project]` and `uses` *silently dropped* the
importer.  `_resolve_import` closes the gap by tail-matching the qualified
token, while a genuinely external import (`HOL-Library.FuncSet`) still
resolves to None and prints its raw token under `[out-of-project]`.

The fixture is loaded off disk (not via `section_from`, which unlinks its
tempfile): `cmd_deps` re-reads each section's `path` through
`parse_thy_imports`, so the files must outlive the parse.
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


def _write_tree(base, files):
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


# Two "sessions" by directory: base/ exports Substrate; ae/'s EncodingWrap
# imports it *qualified* (the cross-session form) alongside a genuinely
# external HOL-Library import.  A recursive-glob load (the top dir has no
# ROOT) picks up both theories — enough to exercise the name resolution the
# bug is about, independent of how the sections were discovered.
TREE = {
    "base/Substrate.thy":
        'theory Substrate imports Main begin\n'
        'lemma s_l: "True" by simp\n'
        'end\n',
    "ae/EncodingWrap.thy":
        'theory EncodingWrap\n'
        '  imports "NDTHT_Base.Substrate" "HOL-Library.FuncSet"\n'
        'begin\n'
        'lemma e_l: "True" by simp\n'
        'end\n',
}


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


class DepsQualified(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        _write_tree(root, TREE)
        ns = argparse.Namespace(files=[str(root)])
        self.sections = cli._load_sections(ns)

    def tearDown(self):
        self._tmp.cleanup()

    def _deps(self, theory, **kw):
        return _capture(cli.cmd_deps, self.sections, theory, **kw)

    def test_fixture_loads_both_theories(self):
        self.assertEqual({s.theory for s in self.sections},
                         {"Substrate", "EncodingWrap"})

    def test_qualified_import_is_direct(self):
        out = self._deps("EncodingWrap")
        self.assertIn("Substrate", out)
        self.assertIn("[direct]", out)
        # The qualified in-project import must NOT print as its raw token...
        self.assertNotIn("NDTHT_Base.Substrate", out)

    def test_external_import_alone_is_out_of_project(self):
        out = self._deps("EncodingWrap")
        oop = [ln for ln in out.splitlines() if "[out-of-project]" in ln]
        self.assertEqual(len(oop), 1)               # only the HOL-Library one
        self.assertIn("HOL-Library.FuncSet", oop[0])  # raw token preserved

    def test_reverse_lists_qualified_importer(self):
        # The silent-drop bug: `uses Substrate` had omitted EncodingWrap.
        out = self._deps("Substrate", reverse=True)
        self.assertIn("EncodingWrap", out)
        self.assertNotIn("No in-project theory imports", out)

    def test_reverse_recursive_lists_importer(self):
        out = self._deps("Substrate", reverse=True, recursive=True)
        self.assertIn("EncodingWrap", out)

    def test_recursive_forward_reaches_qualified_child(self):
        # Recursive forward must walk *through* the resolved bare name, not
        # the raw qualified token (which would re-miss on the next hop).
        out = self._deps("EncodingWrap", recursive=True)
        self.assertIn("Substrate", out)
        self.assertIn("[direct]", out)


class ResolveImportUnit(unittest.TestCase):
    """`_resolve_import` in isolation: bare hit, qualified tail, external None.

    The index is keyed by bare theory name exactly as `cmd_deps` builds it;
    the values are irrelevant to resolution, so sentinels stand in."""

    def setUp(self):
        self.idx = {"Substrate": object(), "EncodingWrap": object()}

    def test_bare_same_session(self):
        self.assertEqual(cli._resolve_import("Substrate", self.idx), "Substrate")

    def test_qualified_cross_session_resolves_by_tail(self):
        self.assertEqual(
            cli._resolve_import("NDTHT_Base.Substrate", self.idx), "Substrate")

    def test_external_qualified_is_none(self):
        self.assertIsNone(cli._resolve_import("HOL-Library.FuncSet", self.idx))

    def test_external_bare_is_none(self):
        self.assertIsNone(cli._resolve_import("Main", self.idx))


if __name__ == "__main__":
    unittest.main()
