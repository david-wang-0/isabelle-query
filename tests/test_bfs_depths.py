"""The unified `_bfs_depths` and the two depth conventions it serves.

`_bfs_depths(neighbors, seeds, *, seed_depth)` replaced three hand-rolled
walks (the old `_transitive_closure` for callers/callees, the old
`_bfs_depths` for reverse imports, and an inline forward-imports BFS).  The
risk in merging them was the depth convention — seed@0 for the call closures
vs direct@0 for the import graph — so these tests pin both conventions in the
helper directly *and* the multi-hop depth labels end-to-end, which no prior
test exercised (test_deps_qualified checks reachability, not depth values).
"""

import argparse
import contextlib
import io
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


class BfsDepthsUnit(unittest.TestCase):
    """The helper in isolation: both seed-depth conventions, shortest path,
    cycle safety, multi-seed, and the lazy-callback contract."""

    # a -> b, c ; b -> d ; c -> d   (a diamond)
    GRAPH = {"a": {"b", "c"}, "b": {"d"}, "c": {"d"}, "d": set()}

    def _nb(self, graph):
        return lambda n: graph.get(n, set())

    def test_seed_depth_zero_counts_the_seed(self):
        # The call-graph convention: the seed itself is depth 0.
        d = cli._bfs_depths(self._nb(self.GRAPH), {"a"})
        self.assertEqual(d, {"a": 0, "b": 1, "c": 1, "d": 2})

    def test_seed_depth_minus_one_puts_direct_at_zero(self):
        # The import-graph convention: the seed is a phantom hop, so its
        # direct neighbours are "direct" = depth 0.
        d = cli._bfs_depths(self._nb(self.GRAPH), ["a"], seed_depth=-1)
        self.assertEqual(d, {"a": -1, "b": 0, "c": 0, "d": 1})

    def test_shortest_path_on_a_diamond(self):
        # d is reachable by two length-2 paths; depth is the shortest, 2.
        self.assertEqual(cli._bfs_depths(self._nb(self.GRAPH), {"a"})["d"], 2)

    def test_cycle_is_safe(self):
        g = {"a": {"b"}, "b": {"a"}}
        self.assertEqual(cli._bfs_depths(self._nb(g), {"a"}), {"a": 0, "b": 1})

    def test_multi_seed_all_seeds_at_seed_depth(self):
        d = cli._bfs_depths(self._nb(self.GRAPH), {"b", "c"})
        self.assertEqual(d, {"b": 0, "c": 0, "d": 1})   # a is not forward-reachable

    def test_callback_is_lazy_and_called_once_per_node(self):
        # The side-effect contract the forward-deps resolver relies on:
        # neighbors() runs exactly once per visited node (so out-of-project
        # imports aren't double-counted).
        calls = []
        adj = {"a": ["b"], "b": ["c"], "c": []}

        def nb(n):
            calls.append(n)
            return adj.get(n, [])

        d = cli._bfs_depths(nb, ["a"], seed_depth=-1)
        self.assertEqual(d, {"a": -1, "b": 0, "c": 1})
        self.assertEqual(sorted(calls), ["a", "b", "c"])


class CallGraphTransitiveDepth(unittest.TestCase):
    """callers/callees -r flow through `_bfs_depths` with seed@0; pin a
    two-hop chain's depths (seed popped by the command afterwards)."""

    SRC = r'''theory T imports Main begin
definition base :: "nat" where "base = 0"
lemma mid: "base = base" by (simp add: base_def)
lemma top: "mid = mid" using mid by simp
end
'''

    def test_callees_depths_through_the_chain(self):
        g = cli._build_call_graph([section_from(self.SRC)])
        d = cli._bfs_depths(lambda n: g.callees.get(n, set()), {"top"})
        self.assertEqual(d.get("top"), 0)   # seed
        self.assertEqual(d.get("mid"), 1)   # top -> mid
        self.assertEqual(d.get("base"), 2)  # top -> mid -> base

    def test_callers_depths_through_the_chain(self):
        g = cli._build_call_graph([section_from(self.SRC)])
        d = cli._bfs_depths(lambda n: g.callers.get(n, set()), {"base"})
        self.assertEqual(d.get("base"), 0)  # seed
        self.assertEqual(d.get("mid"), 1)   # mid cites base
        self.assertEqual(d.get("top"), 2)   # top cites mid


# A -> B -> C import chain (each `imports` the next); C imports Main.
_CHAIN = {
    "C.thy": 'theory C imports Main begin\nlemma c_l: "True" by simp\nend\n',
    "B.thy": 'theory B imports C begin\nlemma b_l: "True" by simp\nend\n',
    "A.thy": 'theory A imports B begin\nlemma a_l: "True" by simp\nend\n',
}


class DepsDepthLabels(unittest.TestCase):
    """End-to-end `deps -r` / `uses -r` depth labels over a 3-theory chain —
    the "direct = 0, then depth 1, ..." convention the import graph renders."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        for rel, content in _CHAIN.items():
            (root / rel).write_text(content)
        self.sections = cli._load_sections(argparse.Namespace(files=[str(root)]))

    def tearDown(self):
        self._tmp.cleanup()

    def _line_for(self, out, theory):
        for ln in out.splitlines():
            if re.match(rf"\s+{re.escape(theory)}\s+\(", ln):
                return ln
        return ""

    def test_forward_recursive_depth_labels(self):
        out = _capture(cli.cmd_deps, self.sections, "A", recursive=True)
        self.assertIn("[direct]", self._line_for(out, "B"))    # A imports B
        self.assertIn("[depth 1]", self._line_for(out, "C"))   # B imports C

    def test_reverse_recursive_depth_labels(self):
        out = _capture(cli.cmd_deps, self.sections, "C",
                       reverse=True, recursive=True)
        self.assertIn("[direct]", self._line_for(out, "B"))    # B imports C
        self.assertIn("[depth 1]", self._line_for(out, "A"))   # A imports B

    def test_forward_non_recursive_is_direct_only(self):
        out = _capture(cli.cmd_deps, self.sections, "A")
        self.assertIn("[direct]", self._line_for(out, "B"))
        self.assertEqual("", self._line_for(out, "C"))         # C is not direct


if __name__ == "__main__":
    unittest.main()
