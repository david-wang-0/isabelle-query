"""`session_theories` must load an entry's in-entry import *closure*, not
just its ROOT-declared roots — and `classify_import` must draw the
in-entry / cross-entry / infrastructure line correctly.

This pins the fix for the AFP under-count: an entry that declares a few leaf
theories and pulls the rest in via `imports` (AODV declares 1, builds 73)
was previously loaded as just the declared roots.  The closure follows every
in-entry import style Isabelle allows — bare (`Mid`), self-qualified
(`Demo.SelfQual`), and relative path (`"sub/Leaf"`, `"../Base"`) — while
skipping cross-entry (`Other.Foo`) and base-library (`Main`,
`HOL-Library.FuncSet`) references, and excluding orphan `.thy` files that no
declared root imports.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import support  # noqa: F401,E402  (side effect: puts src/ on sys.path)
from isabelle_query.common import (  # noqa: E402
    classify_import,
    parse_root_sessions,
    session_theories,
)


def _build(base: Path, files: dict) -> None:
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


# One entry `Demo` exercising every import style, plus an orphan.
#   Top   -> Mid, Main(infra)
#   Mid   -> "sub/Leaf"(path), Demo.SelfQual(self-qualified)
#   Leaf  -> "../Base"(path up), Other.Foo(cross-entry)
#   Base  -> "HOL-Library.FuncSet"(infra)
#   SelfQual -> Base
#   Orphan-> Base           (present on disk, imported by nobody reachable)
_DEMO = {
    "Demo/ROOT": 'session Demo = HOL +\n  directories "sub"\n  theories\n    Top\n',
    "Demo/Top.thy": "theory Top imports Mid Main begin\nend\n",
    "Demo/Mid.thy": 'theory Mid imports "sub/Leaf" Demo.SelfQual begin\nend\n',
    "Demo/sub/Leaf.thy": 'theory Leaf imports "../Base" Other.Foo begin\nend\n',
    "Demo/Base.thy": 'theory Base imports "HOL-Library.FuncSet" begin\nend\n',
    "Demo/SelfQual.thy": "theory SelfQual imports Base begin\nend\n",
    "Demo/Orphan.thy": "theory Orphan imports Base begin\nend\n",
}


class SessionClosure(unittest.TestCase):
    def _sessions(self, demo_dir: Path):
        return parse_root_sessions(demo_dir / "ROOT")

    def test_closure_reaches_every_in_entry_style(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, _DEMO)
            (session,) = self._sessions(d / "Demo")
            stems = {p.stem for _n, p in session_theories(session)}
            # Top's declared root + bare, self-qualified, and both path imports.
            self.assertEqual(stems, {"Top", "Mid", "Leaf", "SelfQual", "Base"})

    def test_orphan_theory_is_excluded(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, _DEMO)
            (session,) = self._sessions(d / "Demo")
            stems = {p.stem for _n, p in session_theories(session)}
            self.assertNotIn("Orphan", stems)  # on disk, imported by nobody

    def test_follow_imports_false_gives_declared_only(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, _DEMO)
            (session,) = self._sessions(d / "Demo")
            stems = {p.stem
                     for _n, p in session_theories(session, follow_imports=False)}
            self.assertEqual(stems, {"Top"})  # just the ROOT-declared root

    def test_no_duplicate_paths(self):
        # Base is reached via two paths (Leaf's "../Base" and SelfQual's Base);
        # it must appear exactly once.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, _DEMO)
            (session,) = self._sessions(d / "Demo")
            paths = [p.resolve() for _n, p in session_theories(session)]
            self.assertEqual(len(paths), len(set(paths)))


class ClassifyImport(unittest.TestCase):
    def _session(self, d: Path):
        _build(d, _DEMO)
        (session,) = parse_root_sessions(d / "Demo" / "ROOT")
        return session

    def test_bare_in_entry(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._session(Path(d))
            kind, path = classify_import("Mid", s)
            self.assertEqual(kind, "in_entry")
            self.assertIsNotNone(path)

    def test_self_qualified_is_in_entry(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._session(Path(d))
            kind, path = classify_import("Demo.SelfQual", s)
            self.assertEqual(kind, "in_entry")
            self.assertEqual(path.stem, "SelfQual")

    def test_relative_path_is_in_entry(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._session(Path(d))
            importer = Path(d) / "Demo" / "sub" / "Leaf.thy"
            kind, path = classify_import('"../Base"', s, importer=importer)
            self.assertEqual(kind, "in_entry")
            self.assertEqual(path.stem, "Base")

    def test_cross_entry_qualified_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._session(Path(d))
            kind, path = classify_import("Other.Foo", s)
            self.assertEqual(kind, "cross_entry")
            self.assertIsNone(path)

    def test_infra_bare_and_qualified_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._session(Path(d))
            self.assertEqual(classify_import("Main", s)[0], "infra")
            self.assertEqual(
                classify_import("HOL-Library.FuncSet", s)[0], "infra")

    def test_path_import_cannot_escape_entry(self):
        # A path climbing above the entry root resolves to no in-entry theory
        # (containment guard), so it is not followed.
        with tempfile.TemporaryDirectory() as d:
            s = self._session(Path(d))
            importer = Path(d) / "Demo" / "Top.thy"
            kind, _ = classify_import('"../../secret"', s, importer=importer)
            self.assertNotEqual(kind, "in_entry")


if __name__ == "__main__":
    unittest.main()
