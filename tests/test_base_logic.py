"""`resolve_base_logic` / `is_hol_base`: classify a session's object logic by
**chain-resolving** its ROOT parent to a distribution root, so the const_canon
guard (and the census-union rationale) can tell a HOL entry from a ZF / Pure one.

The chain is the point: the AFP's ZF cluster sits two+ hops from its
`ZF-Constructible` base (`Independence_CH` → `Transitive_Models` → … ), which an
immediate-parent test misclassifies as HOL.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import support  # noqa: F401,E402  (side effect: puts src/ on sys.path)
from isabelle_query.common import (  # noqa: E402
    is_hol_base, resolve_base_logic)


class ResolveBaseLogic(unittest.TestCase):
    def setUp(self):
        # A HOL entry; a HOL entry parented on another AFP session; the ZF
        # cluster (transitive); a direct ZF entry; a Pure entry.
        self.parents = {
            "Alpha": "HOL",
            "Beta": "Collections",          # AFP HOL session ...
            "Collections": "HOL-Library",   # ... which itself roots at HOL
            "Forcing": "ZF-Constructible",
            "Independence_CH": "Transitive_Models",
            "Transitive_Models": "Delta_System_Lemma",
            "Delta_System_Lemma": "ZF-Constructible",
            "Recursion-Addition": "ZF",
            "SpecCheck": "Pure",
        }

    def test_direct_parent_is_a_distribution_root(self):
        self.assertEqual(resolve_base_logic("Alpha", self.parents), "HOL")
        self.assertEqual(resolve_base_logic("Recursion-Addition", self.parents),
                         "ZF")
        self.assertEqual(resolve_base_logic("SpecCheck", self.parents), "Pure")

    def test_transitive_chain_reaches_the_root(self):
        # The exact case an immediate-parent test gets wrong.
        self.assertEqual(resolve_base_logic("Independence_CH", self.parents),
                         "ZF-Constructible")
        self.assertEqual(resolve_base_logic("Forcing", self.parents),
                         "ZF-Constructible")
        # An AFP-parented HOL entry still resolves to HOL (no false non-HOL).
        self.assertEqual(resolve_base_logic("Beta", self.parents), "HOL-Library")

    def test_cycle_is_guarded(self):
        self.assertEqual(resolve_base_logic("a", {"a": "b", "b": "a"}), "b")


class IsHolBase(unittest.TestCase):
    def test_hol_family_allowlisted(self):
        for b in ("HOL", "HOL-Library", "HOL-Analysis", "HOL-ZF"):
            self.assertTrue(is_hol_base(b), b)

    def test_other_object_logics_are_non_hol(self):
        for b in ("Pure", "ZF", "ZF-Constructible", "FOL", "CTT", "Sequents"):
            self.assertFalse(is_hol_base(b), b)


if __name__ == "__main__":
    unittest.main()
