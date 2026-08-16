"""The import-time proof-method table, and what a *library* caller gets.

`shape.analyze_proof` reads `graph._PROOF_METHODS` late-bound, and the CLI binds
a table at dispatch (`cli._configure_namespace`).  A caller that imports the
package and calls `analyze_proof` directly binds nothing — so the import-time
default decides its numbers, and for a long time that default was the minimal
Pure floor while every CLI run on a HOL project got the broad census union.

The gap was silent and axis-specific, which is what made it expensive to find:
`simp` and `rule` ARE in the Pure floor, so a spot-checked `by simp` proof agreed
with the census exactly, while `by auto` / `by blast` / `by metis` steps carried
`Step.method == ""` and their proofs reported `trivial_frac is None` — "discharges
nothing" for a proof that is maximally trivial.  Measured over 40 AFP entries /
102,927 steps: the floor extracted a method on 23.1% of steps against the union's
53.5%, and 62.3% of proofs disagreed on `trivial_frac`
(`scripts/probe_library_namespace.py`).

So these tests pin the *default*, not a rebinding: no `configure_namespace` call
appears in them, deliberately — that is the whole point.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402

from isabelle_query import _census_namespace as _census  # noqa: E402
from isabelle_query import _isabelle_namespace as _isa_ns  # noqa: E402
from isabelle_query import graph, shape  # noqa: E402

THY = r'''theory T imports Main begin

lemma triv: "(1::nat) + 1 = 2"
  by auto

lemma structured: "(2::nat) + 2 = 4"
proof -
  have "(2::nat) + 2 = 4" by blast
  thus ?thesis by metis
qed

end
'''


class ImportTimeDefault(unittest.TestCase):
    """What `import isabelle_query` binds, with nothing else called."""

    def test_default_is_the_broad_union_not_the_pure_floor(self):
        self.assertEqual(graph._PROOF_METHODS, _census.PROOF_METHODS)
        self.assertEqual(graph._ATTRIBUTES, _census.ATTRIBUTES)

    def test_the_automation_methods_are_recognised(self):
        for m in ("auto", "blast", "metis", "induct", "force", "fastforce"):
            with self.subTest(method=m):
                self.assertNotIn(m, _isa_ns.PROOF_METHODS)   # absent from Pure
                self.assertIn(m, graph._PROOF_METHODS)       # present by default

    def test_keywords_stay_the_pure_table(self):
        # Outer syntax is logic-invariant, so there is only one keyword table and
        # the census module deliberately carries none.
        self.assertEqual(graph._KEYWORDS, _isa_ns.KEYWORDS)


class LibraryCallerGetsMethods(unittest.TestCase):
    """`analyze_proof` called directly, exactly as a downstream script does."""

    def setUp(self):
        self.sec = section_from(THY, "T")

    def _pm(self, name):
        entry = next(e for e in self.sec.entries if e.name == name)
        pm = shape.analyze_proof(self.sec, entry)
        self.assertIsNotNone(pm, f"{name} produced no ProofMetrics")
        return pm

    def test_a_one_liner_by_auto_carries_its_method(self):
        pm = self._pm("triv")
        self.assertEqual([s.method for s in pm.steps], ["auto"])

    def test_trivial_frac_is_1_not_none(self):
        # The reported symptom: `None` ("discharges nothing") for a proof that
        # discharges everything with automation.
        self.assertEqual(shape.trivial_frac(self._pm("triv").steps), 1.0)

    def test_structured_proof_methods_and_kinds(self):
        pm = self._pm("structured")
        self.assertEqual([s.method for s in pm.steps if s.method],
                         ["blast", "metis"])
        counts = shape.method_kind_counts(pm.steps)
        self.assertEqual(counts["search"], 2)      # blast + metis
        self.assertEqual(sum(counts.values()), 2)  # `qed` discharges nothing


class ExplicitTableSelection(unittest.TestCase):
    """The two committed tables are reachable by name, and round-trip."""

    def setUp(self):
        self._saved = (graph._PROOF_METHODS, graph._ATTRIBUTES, graph._KEYWORDS)
        self.addCleanup(lambda: graph.configure_namespace(*self._saved))

    def test_use_pure_namespace_steps_down_to_the_floor(self):
        graph.use_pure_namespace()
        self.assertEqual(graph._PROOF_METHODS, _isa_ns.PROOF_METHODS)
        self.assertNotIn("auto", graph._PROOF_METHODS)

    def test_use_census_namespace_restores_the_default(self):
        graph.use_pure_namespace()
        graph.use_census_namespace()
        self.assertEqual(graph._PROOF_METHODS, _census.PROOF_METHODS)

    def test_the_floor_is_what_silently_dropped_the_method(self):
        # The bug, reproduced on purpose: same call, same proof, empty method.
        graph.use_pure_namespace()
        sec = section_from(THY, "T")
        entry = next(e for e in sec.entries if e.name == "triv")
        pm = shape.analyze_proof(sec, entry)
        self.assertEqual([s.method for s in pm.steps], [""])
        self.assertIsNone(shape.trivial_frac(pm.steps))


if __name__ == "__main__":
    unittest.main()
