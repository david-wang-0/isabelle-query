"""`discover_roots` must scope like `isabelle build -D`.

When a directory carries a `ROOTS` index, discovery follows only the
subdirectories it lists (recursing into nested `ROOTS`), so scan scope
equals build scope — a sibling session deliberately omitted from `ROOTS`
is excluded from the tool exactly as it is from the build.  Without an
index, discovery falls back to a recursive walk so single-ROOT and
ad-hoc layouts still resolve.  These tests pin both modes.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import support  # noqa: F401,E402  (side effect: puts src/ on sys.path)
from isabelle_query.common import discover_roots  # noqa: E402


def _build(base: Path, files: dict) -> None:
    """Materialise a tree: ``{relative_path: contents}``, dirs implied."""
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


class DiscoverRoots(unittest.TestCase):
    def _roots(self, root_dir: Path) -> list[str]:
        """ROOT files discovered under `root_dir`, as posix paths relative to
        it, sorted — convenient for asserting against a literal set."""
        base = root_dir.resolve()
        return sorted(p.relative_to(base).as_posix()
                      for p in discover_roots(root_dir))

    def test_index_limits_scope_to_listed_dirs(self):
        # `exc` has a ROOT on disk but is omitted from ROOTS -> excluded,
        # just as `isabelle build -D` would never elaborate it.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOTS": "inc\n",
                "inc/ROOT": "session inc = HOL\n",
                "exc/ROOT": "session exc = HOL\n",
            })
            self.assertEqual(self._roots(d), ["inc/ROOT"])

    def test_index_ignores_comments_and_blank_lines(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOTS": "# a chapter comment\n\n  inc  \n",
                "inc/ROOT": "session inc = HOL\n",
                "exc/ROOT": "session exc = HOL\n",
            })
            self.assertEqual(self._roots(d), ["inc/ROOT"])

    def test_index_skips_listed_but_missing_dir(self):
        # A ROOTS entry with no directory on disk is silently ignored.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOTS": "inc\nghost\n",
                "inc/ROOT": "session inc = HOL\n",
            })
            self.assertEqual(self._roots(d), ["inc/ROOT"])

    def test_top_level_root_alongside_index_is_kept(self):
        # AFP's `thys/` carries both a ROOTS index and a top-level ROOT
        # (the chapter-definition file); both the top ROOT and the listed
        # entry's ROOT are returned.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOTS": "inc\n",
                "ROOT": "chapter AFP\n",
                "inc/ROOT": "session inc = HOL\n",
            })
            self.assertEqual(self._roots(d), ["ROOT", "inc/ROOT"])

    def test_nested_roots_are_followed(self):
        # A listed dir may itself carry a ROOTS index; recursion follows it
        # while still excluding siblings omitted at each level.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOTS": "grp\n",
                "grp/ROOT": "session grp = HOL\n",
                "grp/ROOTS": "leaf\n",
                "grp/leaf/ROOT": "session leaf = HOL\n",
                "grp/other/ROOT": "session other = HOL\n",  # omitted -> excluded
            })
            self.assertEqual(self._roots(d), ["grp/ROOT", "grp/leaf/ROOT"])

    def test_duplicate_index_entries_dedup(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOTS": "inc\ninc\n",
                "inc/ROOT": "session inc = HOL\n",
            })
            self.assertEqual(self._roots(d), ["inc/ROOT"])

    def test_no_index_recursive_walk_skips_hidden(self):
        # No ROOTS -> recursive fallback finds every ROOT (including nested),
        # but skips hidden directories.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "one/ROOT": "session one = HOL\n",
                "two/nested/ROOT": "session two = HOL\n",
                ".hidden/ROOT": "session hidden = HOL\n",
            })
            self.assertEqual(self._roots(d),
                             ["one/ROOT", "two/nested/ROOT"])

    def test_no_index_hidden_ancestor_does_not_suppress(self):
        # root_dir living *under* a hidden directory must not cause the walk to
        # skip everything: "hidden" is judged relative to root_dir, not the
        # absolute path.  (The old full-`parts` test found nothing here.)
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                ".cache/proj/ROOT": "session p = HOL\n",
                ".cache/proj/sub/ROOT": "session q = HOL\n",
            })
            root = d / ".cache" / "proj"
            self.assertEqual(self._roots(root), ["ROOT", "sub/ROOT"])

    def test_no_index_dotdot_in_path_not_treated_as_hidden(self):
        # A `..` in the path as given is not a hidden component, so a relative
        # root such as `../proj` resolves normally.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOT": "session s = HOL\n",
                "sub/ROOT": "session t = HOL\n",
            })
            root = d / "sub" / ".."  # filesystem-equals d, but carries '..'
            self.assertEqual(len(discover_roots(root)), 2)

    def test_missing_directory_returns_empty(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(discover_roots(Path(d) / "nope"), [])


if __name__ == "__main__":
    unittest.main()
