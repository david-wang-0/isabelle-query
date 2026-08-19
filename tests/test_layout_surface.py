r"""Every `isabelle-layout` name query imports must still be there.

`pyproject.toml` used to cap the dependency at `<0.2.0` on the reasoning that a
pre-1.0 parser everything rests on is not somewhere to learn about a breaking
change from a user's traceback.  The cap is gone, and this test is what
replaces it.

That trade is live rather than hypothetical: `isabelle-layout` is published on
PyPI and releases on its own cadence, which it has already exercised — query
saw 0.1.1, 0.2.0 and 0.2.2 in the space of two days, and pip will pick up the
next one without asking.  This test has passed unchanged across all of them,
which is the evidence that it is the right instrument and not merely a
comforting one.

Without it the failure mode is loud but uninformative: every one of these is
imported at MODULE level, so a name that disappears upstream takes the entire
suite red with a single `ImportError` from whichever test imported first.
Naming the surface here turns that into one failure that says which name went,
and whether it was one query had any business importing.

**The private surface is now empty**, and that is the point of the list rather
than an incidental fact about it.  Eight of these names used to be
`_`-prefixed, reached through `isabelle_query.common` — the real cost of the
split, and the reason the cap existed, since a package may move a private name
in a patch release without telling anyone.  They were wanted by query's
duplicates of upstream's own tests and by nothing else, so retiring those files
retired the exposure with them [common-shim].  Query now imports ten public
names and no private ones, which is what lets the uncapped dependency be a
considered position instead of a standing risk.  Keep it that way: a private
import added here is a version range needed again.

What a surface check does NOT cover is a name that survives but changes
BEHAVIOUR.  Mostly that is upstream's job to test and it does — the second
class below is the exception, the cases layout has no test for and query
cannot afford to lose.  `scripts/probe_discovery_closure.py` and
`probe_parents_oracle.py` are the corpus-level check for the rest.
"""

import ast
import importlib
import tempfile
import unittest
from pathlib import Path

from isabelle_layout import parse_thy_imports

_REPO = Path(__file__).resolve().parent.parent

# (module, attribute) — every `isabelle_layout` name query imports, anywhere.
PUBLIC = [
    ("isabelle_layout", "SessionInfo"),
    ("isabelle_layout", "default_t_dir"),
    ("isabelle_layout", "discover_roots"),
    ("isabelle_layout", "iter_sessions"),
    ("isabelle_layout", "parse_root_sessions"),
    ("isabelle_layout", "parse_thy_imports"),
    ("isabelle_layout", "resolve_base_logic"),
    ("isabelle_layout", "resolve_session_theory"),
    ("isabelle_layout", "session_theories"),
    ("isabelle_layout.distribution", "is_known_nonhol_base"),
]

# Deliberately empty — see the module docstring.  This is a worklist that got
# finished, not a category that happens to have no members today.
PRIVATE: list[tuple[str, str]] = []


class LayoutSurface(unittest.TestCase):

    def test_every_imported_name_resolves(self):
        for mod, attr in PUBLIC + PRIVATE:
            with self.subTest(name=f"{mod}.{attr}"):
                m = importlib.import_module(mod)
                self.assertTrue(
                    hasattr(m, attr),
                    f"query imports {mod}.{attr}, which this isabelle-layout "
                    f"no longer provides.  Either restore the import or stop "
                    f"reaching for it; with no upper bound on the dependency, "
                    f"this test is the only thing standing between that "
                    f"removal and a user's traceback.")

    def test_query_imports_nothing_private_from_layout(self):
        r"""The list above is enforced, not merely maintained [common-shim].

        A private name reached through a shim was invisible at its call site —
        `common.classify_import` reads public.  With the shim gone every import
        says where it comes from, so the invariant can be checked directly:
        walk every `.py` in the repository and fail on an import from a
        `_`-prefixed layout module or of a `_`-prefixed layout name.

        This is the test that keeps the uncapped dependency honest.  Query
        promises compatibility with layout's *public* API, which upstream is
        obliged to version; a private name carries no such promise and can move
        in a patch release, so one added here silently reintroduces the risk
        the version cap used to cover.
        """
        offenders = []
        for path in sorted(_REPO.glob("[!.]*/**/*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
            for node in ast.walk(tree):
                if not isinstance(node, ast.ImportFrom) or not node.module:
                    continue
                if not node.module.startswith("isabelle_layout"):
                    continue
                private_mod = any(part.startswith("_")
                                  for part in node.module.split("."))
                for alias in node.names:
                    if private_mod or alias.name.startswith("_"):
                        rel = path.relative_to(_REPO)
                        offenders.append(
                            f"{rel}:{node.lineno} {node.module}.{alias.name}")
        self.assertEqual(offenders, [], "\n".join(
            ["query must import only layout's PUBLIC API:"] + offenders))

    def test_the_private_surface_has_not_grown(self):
        # Not a style rule: each private name is a reason query cannot state a
        # loose dependency with a straight face.  A new one should be a
        # deliberate decision, not something that arrives in a refactor.
        self.assertEqual(len(PRIVATE), 0)


class BehaviourUpstreamDoesNotTest(unittest.TestCase):
    r"""Not the surface but the semantics, for cases layout has no test for.

    Retiring query's duplicates of layout's own test files [common-shim] is
    safe exactly where upstream covers the same ground, and
    `scripts/probe_duplicated_tests.py` compares the two case by case.  It
    found three of the four files fully covered and two cases in
    `test_thy_header.py` with no counterpart upstream, which is what this
    class is: the residue, kept because deleting the only test of a behaviour
    is not the same as deleting a duplicate of one.

    Both are the document-preparation tag, and query has already been bitten
    by it once.  Isar allows a `%tag` after any command keyword, so AFP's
    `AODV/All.thy` opens `theory %invisible All`.  A header parser anchored on
    `theory NAME imports` reads `%invisible` as the name, fails to match
    `imports`, and drops the clause — and because AODV's ROOT declares only
    `All` and reaches its other 72 theories through that closure, the whole
    entry silently collapsed to one theory.  See `29da607`.

    That failure is invisible from layout's side: it is a lost import clause,
    which only matters to something building a closure out of import clauses.
    Query is that something, so this is query's test to keep, whatever upstream
    chooses to cover.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def _imports(self, text):
        p = self.dir / "T.thy"
        p.write_text(text, encoding="utf-8")
        return parse_thy_imports(p)

    def test_document_tag_with_a_space(self):
        self.assertEqual(
            self._imports('theory % invisible T\nimports Bar\nbegin\nend\n'),
            ["Bar"])

    def test_quoted_document_tag(self):
        self.assertEqual(
            self._imports('theory %"vis" T\nimports Bar\nbegin\nend\n'),
            ["Bar"])


if __name__ == "__main__":
    unittest.main()
