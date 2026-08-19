r"""The `isabelle-layout` names `common.py` re-exports must all still be there.

`pyproject.toml` used to cap the dependency at `<0.2.0` on the reasoning that a
pre-1.0 parser everything rests on is not somewhere to learn about a breaking
change from a user's traceback.  The cap is gone, and this test is what
replaces it.

That trade is now live rather than hypothetical: `isabelle-layout` is published
on PyPI and releases on its own cadence, which it has already exercised — query
saw 0.1.1, 0.2.0 and 0.2.2 in the space of two days, and pip will pick up the
next one without asking.  This test passed unchanged across all three, which is
the evidence that it is the right instrument and not merely a comforting one.

The mechanism it pins is already load-bearing: `common.py` imports every one of
these at MODULE level, so a name that disappears upstream fails
`import isabelle_query.common` and takes the entire suite red.  That is loud
enough, but not informative: the failure is one `ImportError` from whichever
test happened to import first.  Naming the surface here turns it into a single
failure that says which name went, and whether it was one query had any
business importing.

Eight of these are PRIVATE (`_`-prefixed).  That is the real cost of the split
and the reason the cap existed at all — a package may move a private name in a
patch release without telling anyone.  The list below is therefore also a
worklist: every private entry retired is a version range not needed.  See
`common.py`'s module docstring for why each is still reached for.

What this does NOT cover is a name that survives but changes BEHAVIOUR.  Only
tests that exercise it catch that, and eight of these have none —
`scripts/probe_discovery_closure.py` and `probe_parents_oracle.py` are the
corpus-level check for the ones that matter.
"""

import importlib
import unittest

# (module, attribute) — mirroring `isabelle_query.common`'s import block.
PUBLIC = [
    ("isabelle_layout", "SessionInfo"),
    ("isabelle_layout", "default_t_dir"),
    ("isabelle_layout", "discover_roots"),
    ("isabelle_layout", "iter_sessions"),
    ("isabelle_layout", "iter_thy_files"),
    ("isabelle_layout", "parse_root_sessions"),
    ("isabelle_layout", "parse_thy_imports"),
    ("isabelle_layout", "resolve_base_logic"),
    ("isabelle_layout", "resolve_session_theory"),
    ("isabelle_layout", "session_theories"),
    ("isabelle_layout.distribution", "is_hol_base"),
    ("isabelle_layout.distribution", "is_known_nonhol_base"),
    ("isabelle_layout._lexer", "strip_block_comments"),
    ("isabelle_layout._lexer", "strip_cartouches"),
    ("isabelle_layout._lexer", "strip_comments"),
    ("isabelle_layout.project", "LEGACY_MARKER_NAME"),
]

PRIVATE = [
    ("isabelle_layout.distribution", "_NONHOL_DISTRIBUTION_BASES"),
    ("isabelle_layout.project", "_read_marker"),
    ("isabelle_layout.roots", "_parse_root_directories"),
    ("isabelle_layout.roots", "_parse_root_theories"),
    ("isabelle_layout.roots", "_resolve_thy_file"),
    ("isabelle_layout.roots", "_tokenize_root"),
    ("isabelle_layout.theories", "_INFRA_ROOTS"),
    ("isabelle_layout.theories", "_THY_HEADER_RE"),
]


class LayoutSurface(unittest.TestCase):

    def test_every_imported_name_resolves(self):
        for mod, attr in PUBLIC + PRIVATE:
            with self.subTest(name=f"{mod}.{attr}"):
                m = importlib.import_module(mod)
                self.assertTrue(
                    hasattr(m, attr),
                    f"isabelle_query.common imports {mod}.{attr}, which this "
                    f"isabelle-layout no longer provides.  Either restore the "
                    f"import or stop reaching for it; with no upper bound on "
                    f"the dependency, this test is the only thing standing "
                    f"between that removal and a user's traceback.")

    def test_common_re_exports_them_under_its_own_names(self):
        r"""`common.py`'s surface is a contract in its own right.

        Eight names are deliberately re-exported under a DIFFERENT spelling
        from the one upstream uses, so checking the upstream name would miss
        the case that actually breaks a caller: `classify_import` went private
        upstream and is re-exposed here public, the three strippers went public
        upstream and are re-exposed here private, and `MARKER_NAME` keeps its
        historical value while upstream's constant moved to a neutral one.
        """
        from isabelle_query import common
        aliases = [
            # upstream spelling -> the spelling `common` promises
            "SessionInfo", "default_t_dir", "discover_roots", "iter_sessions",
            "iter_thy_files", "parse_root_sessions", "parse_thy_imports",
            "resolve_base_logic", "resolve_session_theory", "session_theories",
            "is_hol_base", "is_known_nonhol_base",
            # renamed on the way through
            "_strip_block_comments", "_strip_cartouches", "_strip_comments",
            "_read_marker", "classify_import", "parse_root_directories",
            "parse_root_theories", "resolve_thy_file",
            "_INFRA_ROOTS", "_THY_HEADER_RE", "_tokenize_root",
            "_NONHOL_DISTRIBUTION_BASES", "MARKER_NAME",
        ]
        for attr in aliases:
            with self.subTest(name=attr):
                self.assertTrue(hasattr(common, attr),
                                f"isabelle_query.common no longer offers "
                                f"{attr}; downstream scripts import it.")
        # Re-exporting upstream's neutral `.isabelle-layout` here would have
        # silently changed the value of a constant this tool documents.
        self.assertEqual(common.MARKER_NAME, ".isabelle-query")

    def test_common_defines_no_code_of_its_own(self):
        r"""`common.py` is imports and nothing else [watchdog-guard].

        It held one function, `run_guarded`, retained pending a review of the
        upstream `bin/` tooling that called it.  That review is done: the
        tooling moved into `isabelle-watchdog`, which carries its own copy at
        `isabelle_watchdog/guard.py`, so query's was the last thread connecting
        the two packages and it is gone.

        Asserting emptiness rather than that one name's absence is the point.
        A re-export module is a place where a helper is easy to park "just for
        now", and each one parked here is logic living below the layer that
        uses it.  If something genuinely belongs to query, it belongs in the
        module that calls it; if it belongs to layout, it belongs upstream.
        """
        from isabelle_query import common
        own = sorted(
            name for name, obj in vars(common).items()
            if not name.startswith("__")
            and getattr(obj, "__module__", None) == common.__name__)
        self.assertEqual(own, [], "common.py is a re-export shim; these are "
                                  "defined in it rather than imported")

    def test_the_private_surface_has_not_grown(self):
        # Not a style rule: each private name is a reason query cannot state a
        # loose dependency with a straight face.  A new one should be a
        # deliberate decision, not something that arrives in a refactor.
        self.assertEqual(len(PRIVATE), 8)


if __name__ == "__main__":
    unittest.main()
