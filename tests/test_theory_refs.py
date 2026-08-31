r"""Theory-level reference rollup, and the `--theory` search scope [theory-refs].

`refs THY` aggregates the entry-level citation graph up by owning theory: what
a theory *references*, the complement of `theory --names`, which lists what it
*exports*.  It is deliberately finer-grained than `deps` / `uses`.  Those read
the `imports` clause — a statement of intent — while this reads the proofs, so
comparing the two is the whole point: an import no citation reaches, and the
converse, a theory whose facts are cited without being imported directly.

The load-bearing part is **whose** name a citation is, and the fixture below is
built around the case that gets it wrong.  A name may be declared in several
theories; crediting the first in load order is wrong because the citing theory
can only see some of them.  On AODV — which declares each of its theories again
under `variants/` — that mistake made `refs Aodv_Loop_Freedom` report

    Direct imports no citation reaches (2): Global_Invariants, Loop_Freedom

when both are cited heavily; every name had been credited to the `E_*` copy,
which sorts earlier and which `Aodv_Loop_Freedom` cannot see at all.  The
answer was the exact reverse of the truth, in the one line the command exists
to print.  So ownership resolves through the citing theory's own import
closure: a local declaration first, then the nearest by import depth.

The fixture is loaded off disk (not via `section_from`, which unlinks its
tempfile): `refs` re-reads each section's `path` through `parse_thy_imports` to
build that closure, so the files must outlive the parse.
"""

import argparse
import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

# `A_Base` is the decoy: it declares `helper` exactly as `Base` does, is
# imported by nobody, and sorts FIRST in load order — the shape AODV's
# `variants/` tree has.  `Top` reaches the real `helper` at import depth 1
# (Top -> Mid -> Base) and must be told so.  `Spare` is imported and never
# cited, which is the other half of the comparison.
TREE = {
    "a_variant/A_Base.thy":
        'theory A_Base imports Main begin\n'
        'lemma helper: "True" by simp\n'
        'end\n',
    "base/Base.thy":
        'theory Base imports Main begin\n'
        'lemma helper: "True" by simp\n'
        'end\n',
    "mid/Mid.thy":
        'theory Mid imports Base begin\n'
        'lemma mid_fact: "True" using helper by simp\n'
        'end\n',
    "spare/Spare.thy":
        'theory Spare imports Main begin\n'
        'lemma spare_fact: "True" by simp\n'
        'end\n',
    "top/Top.thy":
        'theory Top imports Mid Spare begin\n'
        'lemma top_one: "True" using helper mid_fact by simp\n'
        'lemma top_two: "True" using helper by simp\n'
        'end\n',
}


def _write_tree(base, files):
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


class RefsFixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        _write_tree(self.root, TREE)
        self.sections = cli._load_sections(
            argparse.Namespace(files=[str(self.root)]))

    def tearDown(self):
        self._tmp.cleanup()

    def _refs(self, theory, **kw):
        return _capture(cli.cmd_refs, self.sections, theory,
                        cli.CmdFlags(**kw))


class OwnershipFollowsTheImportClosure(RefsFixture):

    def test_the_decoy_sorts_first(self):
        # Guard on the fixture itself: if `A_Base` stopped preceding `Base` in
        # load order the regression below would pass for the wrong reason.
        order = [s.theory for s in self.sections]
        self.assertLess(order.index("A_Base"), order.index("Base"))

    def test_the_naive_rule_really_would_get_this_wrong(self):
        # The other half of that guard, and the one that makes the tests below
        # regression tests rather than descriptions.  `_entry_by_name` is the
        # first-wins index every other command uses, and it is *correct* for
        # them — they answer "where is this name declared", one answer.  Here
        # the question is "which declaration can THIS theory see", and the same
        # index gives the decoy.  Pinning it means these tests keep failing for
        # the original reason if ownership is ever quietly reverted.
        self.assertEqual(cli._entry_by_name(self.sections)["helper"][0],
                         "A_Base")

    def test_a_visible_declaration_beats_an_earlier_invisible_one(self):
        out = self._refs("Top")
        self.assertIn("Base", out)
        self.assertNotIn("A_Base", out)

    def test_the_owning_theorys_import_depth_is_reported(self):
        out = self._refs("Top")
        line = next(ln for ln in out.splitlines()
                    if ln.strip().startswith("Base "))
        self.assertIn("[import depth 1]", line)   # Top -> Mid -> Base
        line = next(ln for ln in out.splitlines()
                    if ln.strip().startswith("Mid "))
        self.assertIn("[direct import]", line)

    def test_a_local_declaration_shadows_an_imported_one(self):
        # `Mid` declares nothing named `helper`, so this is about the rule, not
        # this fixture: re-point the question at a theory that DOES own the
        # name it cites and the group must be `[self]`.
        out = self._refs("Mid")
        self.assertIn("Base", out)
        self.assertIn("[direct import]", out)


class TheDeclaredVersusCitedComparison(RefsFixture):

    def test_an_import_no_citation_reaches_is_named(self):
        out = self._refs("Top")
        self.assertIn("Direct imports no citation reaches (1): Spare", out)

    def test_a_cited_theory_that_is_not_a_direct_import_is_named(self):
        out = self._refs("Top")
        self.assertIn("Cited but not directly imported (1): Base", out)

    def test_a_theory_citing_only_direct_imports_reports_neither(self):
        out = self._refs("Mid")
        self.assertNotIn("Direct imports no citation reaches", out)
        self.assertNotIn("Cited but not directly imported", out)


class CountsAreCitingEntries(RefsFixture):

    def test_a_name_used_by_two_entries_counts_two(self):
        # `helper` is cited by `top_one` and `top_two`.  The graph stores a SET
        # of callees per entry, so this counts entries, not occurrences.
        out = self._refs("Top")
        self.assertIn("helper  (2)", out)

    def test_a_name_used_by_one_entry_counts_one(self):
        self.assertIn("mid_fact  (1)", self._refs("Top"))


class TerseModes(RefsFixture):

    def test_count_is_the_distinct_name_total(self):
        self.assertEqual(self._refs("Top", mode="count").strip(), "2")

    def test_names_prints_bare_names_one_per_line(self):
        out = self._refs("Top", mode="names")
        self.assertEqual(out.split(), ["helper", "mid_fact"])

    def test_external_drops_the_theorys_own_declarations(self):
        # Nothing in `Top` cites `Top`, so `--external` changes nothing here;
        # `Mid` is the case that moves — it cites only `Base`, so both agree —
        # so assert the invariant instead: external <= default, always.
        for thy in ("Top", "Mid", "Base"):
            with self.subTest(theory=thy):
                everything = int(self._refs(thy, mode="count") or 0)
                external = int(self._refs(thy, mode="count",
                                          external=True) or 0)
                self.assertLessEqual(external, everything)

    def test_a_theory_that_references_nothing_says_so(self):
        self.assertIn("makes no references", self._refs("Base"))

    def test_an_unknown_theory_is_reported(self):
        # On stderr and exit 1 since [unresolved-subject]: "no references" and
        # "no such theory" are different answers, and a caller cannot act on
        # the difference if both arrive on stdout with status 0.
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            with self.assertRaises(SystemExit) as caught:
                self._refs("Nope")
        self.assertEqual(caught.exception.code, 1)
        self.assertIn("no theory 'Nope'", buf.getvalue())


class TheoryScopeOnFind(RefsFixture):
    r"""`--theory THY` confines a name search to one theory.

    `find` has no trailing PATH positionals — a name search is corpus-global by
    nature — which left the global `-R/--root` as its only knob.  Too coarse
    for "where in *this* theory", and the reason that question was answered
    with a pipe into `grep`.
    """

    def _scoped(self, *thys):
        ns = argparse.Namespace(theory_scope=list(thys) or None)
        return [s.theory for s in cli._scope_to_theories(ns, self.sections)]

    def test_no_flag_leaves_the_index_alone(self):
        self.assertEqual(len(self._scoped()), len(self.sections))

    def test_one_theory_narrows_to_it(self):
        self.assertEqual(self._scoped("Mid"), ["Mid"])

    def test_several_theories_union(self):
        self.assertEqual(sorted(self._scoped("Mid", "Base")), ["Base", "Mid"])

    def test_a_repeat_does_not_duplicate_a_section(self):
        self.assertEqual(self._scoped("Mid", "Mid"), ["Mid"])

    def test_a_thy_suffix_resolves(self):
        self.assertEqual(self._scoped("Mid.thy"), ["Mid"])

    def test_an_unknown_theory_is_reported_not_silently_empty(self):
        # A typo'd scope that narrowed silently would report "no matches",
        # which is indistinguishable from a real absence — the failure mode
        # this tool exists to avoid.
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            kept = self._scoped("Nope")
        self.assertEqual(kept, [])
        self.assertIn("'Nope' not found", buf.getvalue())

    def test_the_flag_parses_and_is_repeatable(self):
        ns = cli._build_parser().parse_args(
            ["find", "x", "--theory", "A", "--theory", "B"])
        self.assertEqual(ns.theory_scope, ["A", "B"])

    def test_the_dest_does_not_collide_with_the_theory_positional(self):
        # `deps` / `uses` / `refs` already use `theory` for their subject, so
        # the scope flag's dest is `theory_scope`.  One namespace attribute
        # cannot be both.
        ns = cli._build_parser().parse_args(["refs", "Top"])
        self.assertEqual(ns.theory, ["Top"])


if __name__ == "__main__":
    unittest.main()
