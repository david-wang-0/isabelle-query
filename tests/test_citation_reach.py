r"""A citation is attributed only to a declaration its theory can SEE.

`callers` / `callees` / `unused` / `graph citation` resolved a cited token by
NAME alone: find `mono` on a line, look up every entry called `mono`, report
the line as a caller of all of them.  Within one session that is right —
everything there sees everything the session declares.  Over a corpus it is
not.  The AFP has 74 entries spelled `mono`, and `MonoBoolTranAlgebra`'s
`Mono_Bool_Tran` (whose in-project closure is *itself alone*; its imports are
another entry's) was reported as a caller of all of them.  The `mono` there is
HOL's `Orderings.mono`, arriving through an `imports` query deliberately does
not follow.

The rule is a NECESSARY condition on visibility, not a sufficient one: a site
in T may name a declaration in D iff `D == T` or D is in T's transitive
in-project `imports` closure.  So it can only ever DROP an attribution, which
is what `TheRuleOnlyDrops` pins — the single invariant that makes the change
safe to reason about, because no output can grow except `unused`.

Whole AFP, `scripts/probe_citation_reach.py`:

    edges           3,020,075 -> 1,139,375   (-62%, 0 gained)
    with no caller     81,259 ->    88,977   (+7,718)
    callers mono -c      1,261 ->       232

`unused` growing is the point, not a cost: an entry kept alive only by a
citation its citer could not see is dead.

These tests write real files, because the filter reads `imports` clauses off
disk.  A section parsed from a buffer has an UNKNOWN closure, and the rule then
declines to filter — see `AnUnknownClosureFiltersNothing`, which is the
degradation direction that matters: guessing "imports nothing" would delete
real edges, the exact failure this exists to avoid.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402
from isabelle_query import cli, graph  # noqa: E402
from isabelle_query.model import CmdFlags  # noqa: E402

A = 'theory A\nimports Main\nbegin\ndefinition base :: "nat" where "base = 0"\nend\n'
# B imports A, so it can see `base`.
B = 'theory B\nimports A\nbegin\nlemma uses_base: "base = base" by simp\nend\n'
# C imports nothing in-project, and writes the same token.  Its `base` is
# something else — a bound variable, or a constant from a library import.
C = 'theory C\nimports Main\nbegin\nlemma looks_like: "base = base" by simp\nend\n'
# D reaches A only transitively, through B.
D = 'theory D\nimports B\nbegin\nlemma via_b: "base = base" by simp\nend\n'


class ReachFixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        for name, text in (("A", A), ("B", B), ("C", C), ("D", D)):
            (self.dir / f"{name}.thy").write_text(text, encoding="utf-8")
        (self.dir / "ROOT").write_text(
            "session Demo = HOL +\n  theories\n    A\n    B\n    C\n    D\n",
            encoding="utf-8")
        cli._ROOT_OVERRIDE = self.dir
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def callers_of(self, name, reach="closure"):
        g = graph._build_call_graph(self.sections, reach=reach)
        return g.callers.get(name, set())


class VisibilityDecidesAttribution(ReachFixture):

    def test_an_importer_still_cites(self):
        self.assertIn("uses_base", self.callers_of("base"))

    def test_a_transitive_importer_still_cites(self):
        # D -> B -> A.  The closure is transitive, not just direct imports.
        self.assertIn("via_b", self.callers_of("base"))

    def test_a_theory_that_cannot_see_it_does_not(self):
        self.assertNotIn("looks_like", self.callers_of("base"))

    def test_name_mode_restores_the_old_answer(self):
        self.assertIn("looks_like", self.callers_of("base", reach="name"))

    def test_a_theory_cites_its_own_declaration(self):
        # The fast path in `_Visibility.sees`, and the common case: no closure
        # walk is needed to know a theory can see what it declares itself.
        vis = graph._Visibility(self.sections)
        self.assertTrue(vis.sees("A", "base"))


class TheRuleOnlyDrops(ReachFixture):
    """The invariant: closure-scoped edges are a SUBSET of name-scoped ones."""

    def edges(self, reach):
        g = graph._build_call_graph(self.sections, reach=reach)
        return {(c, n) for n, cs in g.callers.items() for c in cs}

    def test_closure_edges_are_a_subset(self):
        self.assertTrue(self.edges("closure") <= self.edges("name"))

    def test_something_was_actually_dropped(self):
        # Guard against the subset test passing vacuously.
        self.assertTrue(self.edges("name") - self.edges("closure"))


class FindCallersAgrees(ReachFixture):
    """`callers` (the single-name scan) and the graph must not disagree.

    They are two different code paths over the same question — one scans for a
    single name, the other builds every edge at once — and `[citation-reach]`
    had to be applied at both or the two verbs would answer differently for the
    same lemma.
    """

    def hits(self, name, reach="closure"):
        from isabelle_query.commands import _find_callers
        return {t for t, _ln, _txt in _find_callers(self.sections, name,
                                                    reach=reach)}

    def test_the_invisible_theory_is_skipped(self):
        self.assertNotIn("C", self.hits("base"))

    def test_the_importers_are_kept(self):
        self.assertEqual(self.hits("base"), {"B", "D"})

    def test_name_mode_restores_it(self):
        self.assertIn("C", self.hits("base", reach="name"))

    def test_a_name_the_project_never_declares_is_not_filtered(self):
        # The rule scopes a name to its declarations.  With none, there is
        # nothing to scope to, and filtering would drop a real mention rather
        # than a wrong one.
        vis = graph._Visibility(self.sections)
        self.assertTrue(vis.sees("C", "nothing_declares_this"))


class UnusedMayHonestlyGrow(ReachFixture):
    """An entry kept alive only by an unreachable citation is dead."""

    def unused(self, reach):
        g = graph._build_call_graph(self.sections, derived=True, reach=reach)
        return {n for n in g.all_names if not g.callers.get(n)}

    def test_base_is_live_because_b_really_cites_it(self):
        self.assertNotIn("base", self.unused("closure"))

    def test_an_entry_only_cited_out_of_reach_becomes_unused(self):
        # `looks_like` is C's own lemma and nothing cites it either way; the
        # interesting direction is that the set can only GROW.
        self.assertTrue(self.unused("closure") >= self.unused("name"))


class AnUnknownClosureFiltersNothing(unittest.TestCase):
    r"""A section whose `imports` cannot be re-read is not filtered.

    `parse_thy_imports` returns ``[]`` for a file that does not exist, so
    "imports nothing" and "cannot be read" look identical through it — and they
    must not be.  A section built from a buffer (the `-` stdin route,
    `api.parse_theory(..., lines=...)`, or any test helper that writes a temp
    file and unlinks it) would otherwise lose every cross-theory edge it has,
    silently.

    Degrading to "sees everything" is the only safe direction for a rule whose
    whole justification is that it can only drop.
    """

    def test_buffer_parsed_sections_keep_their_edges(self):
        # `section_from` unlinks its temp file, so these paths do not exist.
        secs = [section_from(A, "A"), section_from(B, "B")]
        g = graph._build_call_graph(secs)
        self.assertIn("uses_base", g.callers["base"])

    def test_the_closure_is_reported_as_unknown(self):
        vis = graph._Visibility([section_from(A, "A"), section_from(B, "B")])
        self.assertIsNone(vis.closure("B"))


class TheFlagIsOnEveryAttributingVerb(unittest.TestCase):
    """`--reach` reaches every verb whose numbers it moves.

    `refs` is included though the issue did not name it: it tallies from the
    same graph, so scoping the graph moves its output too, and a switch that
    does not cover a changed verb is not a compatibility switch.
    """

    # `unused` and `graph` take no subject; the other three do.
    VERBS = {"callers": ["x"], "callees": ["x"], "refs": ["T"],
             "unused": [], "graph": []}

    def test_the_flag_is_accepted(self):
        parser = cli._build_parser()
        for verb, args in self.VERBS.items():
            with self.subTest(verb=verb):
                ns = parser.parse_args([verb, *args])
                self.assertEqual(getattr(ns, "reach", None), "closure")
                ns = parser.parse_args([verb, *args, "--reach", "name"])
                self.assertEqual(ns.reach, "name")

    def test_a_verb_that_attributes_nothing_does_not_carry_it(self):
        # `deps` / `uses` work at the imports-clause level and `shape` /
        # `methods` never attribute a token to an entry, so the flag would be
        # inert there — an inert flag is a promise the tool cannot keep.
        parser = cli._build_parser()
        for verb, args in (("deps", ["T"]), ("methods", [])):
            with self.subTest(verb=verb):
                self.assertIsNone(getattr(parser.parse_args([verb, *args]),
                                          "reach", None))

    def test_the_default_matches_the_library_default(self):
        # `CmdFlags.reach` and `_build_call_graph`'s default must agree, or the
        # CLI and a library caller get different graphs — the `trivial_frac`
        # mistake, which failed selectively and so read as data.
        self.assertEqual(CmdFlags().reach, "closure")


if __name__ == "__main__":
    unittest.main()
