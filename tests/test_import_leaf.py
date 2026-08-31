r"""An import spelled as a PATH, and a theory name that is not an identity.

Two holes in one mapping — `imports`-token to loaded theory — and both of them
DELETE citations, because the visibility closure is a necessary condition that
may only drop.  A theory the closure cannot reach is a theory whose
declarations every citer is told it cannot see.

**The leaf.**  `_resolve_import` matched an exact name, else the tail after
the last `.`.  Neither sees a path:

    imports "../WFair"                                HOL/UNITY/Simple/Token.thy:10
    imports "variants/a_norreqid/A_Aodv_Loop_Freedom" AFP/AODV/All.thy

`"." in imp` is true — of the `.` in `..` — so the tail rule yielded `/WFair`,
which names nothing.  Discovery had no such trouble (`isabelle-layout` follows
the path and the theory IS loaded); only the edge to it was invisible.  The
same hole opens from the theory end, where a ROOT spelling `theories
"Simple/Reach"` gives the SECTION that name and a sibling's bare `imports
Reach` matches no key.  Isabelle takes the last segment on both sides
(`Thy_Header.import_name`).

**The union.**  `_Visibility` keyed its adjacency with the last-wins
`_sections_by_theory`, so of two theories sharing a name one section's imports
stood for the other's.  The fix is a union rather than a better tiebreak:
a closure that is too LARGE is merely weak, one that is too SMALL deletes.

Measured before the fix (`scripts/probe_import_leaf.py`,
`scripts/probe_visibility_by_name.py`):

    src/HOL   83 tokens, 92 theories reach further,     2,803 citations deleted
    AFP    2,513 tokens, 4,295 theories (JinjaThreads      65,745 deleted
                          13 -> 548)
    AFP    7,881 of 64,738 visibility decisions flip under the union

`callers rev` over `src/HOL` went 610 -> 670, which is `--reach name`'s answer
exactly: over a corpus where everything really does import `Main`, the filter
should drop nothing, and the 60 it dropped were the hole.

These tests write real files and real ROOTs, because both halves read an
`imports` clause off disk and a theory's NAME comes from how a ROOT spelled
it.  The union fixture needs one ROOT PER DIRECTORY: a ROOT that says
`theories "alpha/Dup"` gives the section that name, and then there is no
collision to test [disambig-loci].
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import cli, graph  # noqa: E402

# --- leaf fixture: one session, a subdirectory, both spellings -------------
BASE = ('theory Base\nimports Main\nbegin\n'
        'definition base :: "nat" where "base = 0"\n'
        'end\n')
# Named `Sub/Leaf` by the ROOT, and reaching its sibling by a relative path —
# the HOL/UNITY spelling.
LEAF = ('theory Leaf\nimports "../Base"\nbegin\n'
        'lemma leaf_uses: "base = base" by simp\n'
        'end\n')
# Imports that theory by its LEAF, which is not the name it is loaded under —
# the other end of the same rule.
BARE = ('theory Bare\nimports Leaf\nbegin\n'
        'lemma bare_uses: "base = base" by simp\n'
        'end\n')
# A path that names nothing loaded stays external, as does a session-qualified
# library import.
ALIEN = ('theory Alien\nimports "../nowhere/Absent" "HOL-Library.FuncSet"\n'
         'begin\nlemma alien_uses: "base = base" by simp\nend\n')


class LeafFixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        (self.dir / "Sub").mkdir()
        (self.dir / "Base.thy").write_text(BASE, encoding="utf-8")
        (self.dir / "Sub" / "Leaf.thy").write_text(LEAF, encoding="utf-8")
        (self.dir / "Bare.thy").write_text(BARE, encoding="utf-8")
        (self.dir / "Alien.thy").write_text(ALIEN, encoding="utf-8")
        (self.dir / "ROOT").write_text(
            'session Demo = HOL +\n  theories\n    Base\n    "Sub/Leaf"\n'
            '    Bare\n    Alien\n', encoding="utf-8")
        cli._ROOT_OVERRIDE = self.dir
        self.sections = cli.load_index()
        self.by_theory = graph._sections_by_theory(self.sections)

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def callers_of(self, name, reach="closure"):
        g = graph._build_call_graph(self.sections, reach=reach)
        return g.callers.get(name, set())


class TheFixtureReallyUsesBothSpellings(LeafFixture):
    """Without this the rest could pass on a tree that has neither hole."""

    def test_the_root_path_becomes_the_theory_name(self):
        # If this ever stops holding, the `Bare` half below is testing that a
        # bare import matches a bare name, which was never broken.
        self.assertIn("Sub/Leaf", self.by_theory)
        self.assertNotIn("Leaf", self.by_theory)

    def test_the_subdirectory_theory_is_loaded(self):
        self.assertEqual(
            {"Base", "Sub/Leaf", "Bare", "Alien"}, set(self.by_theory))


class APathSpelledImportResolves(LeafFixture):

    def test_a_relative_path_finds_its_leaf(self):
        self.assertEqual("Base",
                         graph._resolve_import("../Base", self.by_theory))

    def test_a_deep_path_finds_its_leaf(self):
        # The AFP spelling: `imports "variants/a_norreqid/A_Aodv..."`.
        self.assertEqual(
            "Base", graph._resolve_import("a/b/c/Base", self.by_theory))

    def test_a_bare_name_finds_a_path_spelled_theory(self):
        self.assertEqual("Sub/Leaf",
                         graph._resolve_import("Leaf", self.by_theory))

    def test_the_exact_name_still_wins(self):
        self.assertEqual("Sub/Leaf",
                         graph._resolve_import("Sub/Leaf", self.by_theory))

    def test_a_session_qualified_import_still_resolves_by_its_tail(self):
        self.assertEqual("Base",
                         graph._resolve_import("Other.Base", self.by_theory))


class ExternalStaysExternal(LeafFixture):
    """The leaf rules may not invent a local theory out of a library import."""

    def test_a_library_import_is_still_none(self):
        self.assertIsNone(
            graph._resolve_import("HOL-Library.FuncSet", self.by_theory))

    def test_a_path_naming_nothing_loaded_is_still_none(self):
        self.assertIsNone(
            graph._resolve_import("../nowhere/Absent", self.by_theory))

    def test_main_is_still_none(self):
        self.assertIsNone(graph._resolve_import("Main", self.by_theory))

    def test_a_theory_reaching_nothing_in_project_is_still_filtered(self):
        # Alien's only imports are external, so it cannot see `base` and the
        # rule must still say so — the leaf rules widen resolution, not the
        # filter.
        self.assertNotIn("alien_uses", self.callers_of("base"))


class TheClosureCrossesTheEdge(LeafFixture):
    """The point of the resolution: an unreachable theory deletes citations."""

    def test_the_path_importer_reaches_its_target(self):
        vis = graph._Visibility(self.sections)
        self.assertIn("Base", vis.closure("Sub/Leaf"))

    def test_the_path_importers_citation_survives(self):
        self.assertIn("leaf_uses", self.callers_of("base"))

    def test_the_bare_importer_reaches_transitively(self):
        # Bare -> Sub/Leaf -> Base, and every hop needs a different rule.
        vis = graph._Visibility(self.sections)
        self.assertEqual({"Bare", "Sub/Leaf", "Base"}, set(vis.closure("Bare")))

    def test_the_bare_importers_citation_survives(self):
        self.assertIn("bare_uses", self.callers_of("base"))

    def test_the_filter_now_drops_nothing_it_should_not(self):
        # The whole-corpus shape of the fix: everything that can really see
        # `base` is attributed, and only `Alien` is not.
        self.assertEqual({"leaf_uses", "bare_uses"}, self.callers_of("base"))


# --- union fixture: one name, two sections, one ROOT each ------------------
A_TARGET = ('theory A_Target\nimports Main\nbegin\n'
            'definition a_target :: "nat" where "a_target = 0"\n'
            'end\n')
B_TARGET = ('theory B_Target\nimports Main\nbegin\n'
            'definition b_target :: "nat" where "b_target = 0"\n'
            'end\n')
A_DUP = 'theory Dup\nimports A_Target\nbegin\nend\n'
B_DUP = 'theory Dup\nimports B_Target\nbegin\nend\n'
# Cites both, and reaches each only through whichever `Dup` carries the edge.
CITE = ('theory Cite\nimports Dup\nbegin\n'
        'lemma cites_a: "a_target = a_target" by simp\n'
        'lemma cites_b: "b_target = b_target" by simp\n'
        'end\n')


class UnionFixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        for entry, files, roots in (
                ("alpha", (("A_Target", A_TARGET), ("Dup", A_DUP),
                           ("Cite", CITE)), ("A_Target", "Dup", "Cite")),
                ("beta", (("B_Target", B_TARGET), ("Dup", B_DUP)),
                 ("B_Target", "Dup"))):
            (self.dir / entry).mkdir()
            for name, text in files:
                (self.dir / entry / f"{name}.thy").write_text(
                    text, encoding="utf-8")
            body = "".join(f"    {r}\n" for r in roots)
            (self.dir / entry / "ROOT").write_text(
                f"session {entry.capitalize()} = HOL +\n  theories\n{body}",
                encoding="utf-8")
        cli._ROOT_OVERRIDE = self.dir
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def callers_of(self, name, reach="closure"):
        g = graph._build_call_graph(self.sections, reach=reach)
        return g.callers.get(name, set())


class TheFixtureReallyCollides(UnionFixture):

    def test_two_sections_share_the_name_Dup(self):
        dups = [s for s in self.sections if s.theory == "Dup"]
        self.assertEqual(2, len(dups))
        self.assertNotEqual(dups[0].path, dups[1].path)

    def test_the_last_wins_index_keeps_only_one(self):
        # This is the defect in one line: the map every closure was built from
        # cannot represent both.
        self.assertEqual(1, len([n for n in graph._sections_by_theory(
            self.sections) if n == "Dup"]))


class ASharedNameUnionsItsEdges(UnionFixture):
    r"""Order-independent: BOTH targets must be reachable.

    Which `Dup` a last-wins map keeps is load order, so asserting one
    direction passes by luck half the time.  Asserting both fails before the
    fix whichever section won [name-is-not-identity].
    """

    def test_the_closure_reaches_both_targets(self):
        vis = graph._Visibility(self.sections)
        self.assertEqual({"Cite", "Dup", "A_Target", "B_Target"},
                         set(vis.closure("Cite")))

    def test_both_citations_survive(self):
        self.assertIn("cites_a", self.callers_of("a_target"))
        self.assertIn("cites_b", self.callers_of("b_target"))

    def test_the_union_is_deterministic(self):
        vis = graph._Visibility(self.sections)
        self.assertEqual(["A_Target", "B_Target"], vis._read_imports("Dup"))


class UnionOnlyWidens(UnionFixture):
    """It may not invent an edge, only refuse to lose one."""

    def test_an_unrelated_name_is_still_filtered(self):
        # `A_Target` declares nothing `B_Target` needs; the union widens
        # `Dup`'s closure, not everyone's.
        vis = graph._Visibility(self.sections)
        self.assertEqual({"A_Target"}, set(vis.closure("A_Target")))

    def test_name_mode_is_unchanged(self):
        self.assertIn("cites_a", self.callers_of("a_target", reach="name"))
        self.assertIn("cites_b", self.callers_of("b_target", reach="name"))


if __name__ == "__main__":
    unittest.main()
