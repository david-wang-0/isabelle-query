"""The committed-namespace *fallback* selection (no built heap / no Isabelle).

`shape census` and the interactive shape verbs must agree on the method-kind /
automation axis, so when nothing resolves a HOL-base project binds the SAME broad
census table the census uses — only a positively-identified non-HOL logic keeps
the minimal Pure floor + warning.  These tests pin the flipped-default predicate
(`common.is_known_nonhol_base`), the project-level decision (`cli._use_broad_
fallback`), and that the dispatch actually rebinds `graph`'s method table.

The key case is `-R <sub-session>`: the cross-session parent (a substrate) is out
of scope, so the base chain stops at that session *name*.  `is_hol_base` can't see
it's HOL, but the fallback must still pick the broad table — unknown ≠ non-HOL.
"""
import collections
import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import support  # noqa: F401,E402  (side effect: puts src/ on sys.path)
from isabelle_query import _isabelle_namespace as _isa_ns  # noqa: E402
from isabelle_query import cli, graph  # noqa: E402
from isabelle_query.common import is_known_nonhol_base  # noqa: E402

Sess = collections.namedtuple("Sess", "name parent")


class IsKnownNonHolBase(unittest.TestCase):
    def test_positively_non_hol(self):
        for b in ("Pure", "ZF", "ZF-Constructible", "FOL", "FOLP", "CTT",
                  "Sequents"):
            self.assertTrue(is_known_nonhol_base(b), b)

    def test_hol_and_unknown_are_not_flagged(self):
        # HOL family AND an arbitrary in-corpus session name (an out-of-scope
        # substrate parent) both default to *not* non-HOL.
        for b in ("HOL", "HOL-Library", "HOL-Analysis",
                  "Multitape_TM_Substrate", "Collections"):
            self.assertFalse(is_known_nonhol_base(b), b)


class UseBroadFallback(unittest.TestCase):
    def test_hol_sub_session_out_of_scope_parent(self):
        # -R ae: parent is the substrate *session*, whose own = HOL is not in
        # scope.  Unknown, not non-HOL -> broad table.
        infos = [Sess("Multitape_Alphabet_Enlargement", "Multitape_TM_Substrate")]
        self.assertTrue(cli._use_broad_fallback(infos))

    def test_hol_whole_project_chains_to_hol(self):
        infos = [Sess("Multitape_Alphabet_Enlargement", "Multitape_TM_Substrate"),
                 Sess("Multitape_TM_Substrate", "HOL")]
        self.assertTrue(cli._use_broad_fallback(infos))

    def test_zf_project_vetoes_broad(self):
        self.assertFalse(cli._use_broad_fallback([Sess("Recursion-Addition",
                                                       "ZF")]))
        # transitive ZF cluster: immediate parent is a session, root is ZF-*
        self.assertFalse(cli._use_broad_fallback(
            [Sess("Forcing", "ZF-Constructible")]))

    def test_pure_project_vetoes_broad(self):
        self.assertFalse(cli._use_broad_fallback([Sess("SpecCheck", "Pure")]))

    def test_mixed_project_vetoes_broad(self):
        # one non-HOL session anywhere is enough to hold back the HOL union.
        infos = [Sess("HolPart", "HOL"), Sess("ZfPart", "ZF")]
        self.assertFalse(cli._use_broad_fallback(infos))

    def test_empty_project_defaults_broad(self):
        self.assertTrue(cli._use_broad_fallback([]))


class BindCommittedFallback(unittest.TestCase):
    """The dispatch rebinds `graph`'s method table for HOL, and warns + leaves the
    Pure floor for non-HOL.  `auto`/`blast` are the discriminators: absent from the
    Pure floor, present in the broad census union."""

    def setUp(self):
        self._saved = (graph._PROOF_METHODS, graph._ATTRIBUTES, graph._KEYWORDS)
        # Start from the minimal Pure floor (import-time default) so `auto` is
        # absent and a rebind to the broad table is observable.
        graph.configure_namespace(_isa_ns.PROOF_METHODS, _isa_ns.ATTRIBUTES,
                                  _isa_ns.KEYWORDS)
        self.assertNotIn("auto", graph._PROOF_METHODS)   # precondition

    def tearDown(self):
        graph.configure_namespace(*self._saved)

    def test_hol_binds_broad_table(self):
        cli._bind_committed_fallback(
            [Sess("Multitape_Alphabet_Enlargement", "Multitape_TM_Substrate")])
        self.assertIn("auto", graph._PROOF_METHODS)
        self.assertIn("blast", graph._PROOF_METHODS)

    def test_nonhol_keeps_pure_floor_and_warns(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            cli._bind_committed_fallback([Sess("Recursion-Addition", "ZF")])
        self.assertNotIn("auto", graph._PROOF_METHODS)   # floor unchanged
        msg = err.getvalue()
        self.assertIn("ZF", msg)
        self.assertIn("not HOL", msg)

    def test_pure_only_project_is_silent(self):
        # a genuinely Pure project: the floor IS exact, so no warning.
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            cli._bind_committed_fallback([Sess("SpecCheck", "Pure")])
        self.assertEqual(err.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
