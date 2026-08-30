r"""`unused -r`'s cascade depth is a fact about the source, not about the run.

`--recursive` promises a level: depth 0 is "no callers at all", depth 1 is
"became unused once the depth-0 entries are removed", and so on.  The loop
computing it iterated

    for name in graph.all_names - set(unused) - keep:
        if callers and callers <= set(unused):
            unused[name] = depth

and re-read `unused` — which the loop body is growing — on every test.  So a
name whose only caller was marked EARLIER IN THE SAME PASS was given its
caller's depth instead of one more, and how far a chain collapsed depended on
the order names came out of a set, i.e. on the process's string hash seed.
Three runs of `unused -r` on one unchanged corpus gave three different answers.

The divergence was confined to the marker — strip `[cascade depth N]` and the
outputs were byte-identical — but a build-hygiene check whose output cannot be
diffed against yesterday's is not much of a check.

Found by the differential harness in David Wang's Scala port, recorded there as
D10; `scripts/probe_scala_port_findings.py` is the hash-seed reproduction.

The fix tests each pass against the frontier as it stood BEFORE the pass, which
is what the help text describes.  That also makes the result independent of
visit order outright, so the ladder below is a specification and not a snapshot.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402
from isabelle_query.commands import _compute_unused_recursive  # noqa: E402

# A three-link chain.  `a_top` is cited by nobody, `b_mid` only by `a_top`,
# `c_leaf` only by `b_mid` — so removing `a_top` kills `b_mid`, and removing
# THAT kills `c_leaf`.  Named so that alphabetical order is the order which
# collapses the chain under the old rule: visiting `b_mid` before `c_leaf`
# marks `b_mid` and then lets `c_leaf` see it within the same pass.
CHAIN = r"""theory Cascade
imports Main
begin

lemma c_leaf: \<open>True\<close> by simp

lemma b_mid: \<open>True\<close> using c_leaf by simp

lemma a_top: \<open>True\<close> using b_mid by simp

end
"""

# Two independent chains sharing a depth-0 root, so a pass has more than one
# name to mark and order has more chance to matter.
FORK = r"""theory Fork
imports Main
begin

lemma left_leaf: \<open>True\<close> by simp

lemma right_leaf: \<open>True\<close> by simp

lemma joint: \<open>True\<close> using left_leaf right_leaf by simp

lemma root: \<open>True\<close> using joint by simp

end
"""


def depths(snippet, theory):
    graph = cli._build_call_graph([section_from(snippet, theory)])
    return _compute_unused_recursive(graph)


class CascadeDepth(unittest.TestCase):

    def test_a_chain_gets_one_level_per_link(self):
        self.assertEqual(depths(CHAIN, "Cascade"),
                         {"a_top": 0, "b_mid": 1, "c_leaf": 2})

    def test_a_fork_levels_both_arms_together(self):
        self.assertEqual(depths(FORK, "Fork"),
                         {"root": 0, "joint": 1,
                          "left_leaf": 2, "right_leaf": 2})

    def test_depth_is_one_more_than_the_deepest_caller(self):
        """The invariant the level-synchronised pass establishes."""
        sec = section_from(CHAIN, "Cascade")
        graph = cli._build_call_graph([sec])
        d = _compute_unused_recursive(graph)
        for name, depth in d.items():
            callers = graph.callers.get(name, set())
            if not callers:
                self.assertEqual(depth, 0, f"{name} has no callers")
                continue
            self.assertEqual(depth, 1 + max(d[c] for c in callers),
                             f"{name} is not one level below its callers")

    def test_the_unused_set_is_unchanged_by_levelling(self):
        """Only the depths move: the fixed point is the same set."""
        self.assertEqual(set(depths(CHAIN, "Cascade")),
                         {"a_top", "b_mid", "c_leaf"})


if __name__ == "__main__":
    unittest.main()
