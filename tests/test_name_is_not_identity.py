r"""A theory NAME is not a section's identity [name-is-not-identity].

Three indexes are built by one loop over the sections and read back by
another, per section:

    graph._build_line_index    {key: [(src_start, thy_end, Entry)]}
    graph._noise_ranges        {key: [prose line ranges]}
    graph._build_def_sites     {key: {name: def-site ranges}}

Each keyed on `sec.theory`, which is unique in a session and not in a corpus:
**461 AFP theory names are used by more than one theory**, so 758 of its 9,910
sections lost that lookup and were handed another file's spans.  381,710 of
the 449,860 lines in those sections got a different owner, and 38,068 were
classified prose-vs-live the wrong way round.

The two prose-and-def indexes are the worse half, because they SUPPRESS: a
line inside the other file's `text` block is dropped as documentation, and a
line inside the other file's declaration is dropped as a definition site.
Both drop a real citation silently, which is the failure mode a citation graph
can least afford.  Over the AFP the fix drops **48,177** misattributed edges,
restores **43,912** suppressed ones, and `unused` grows 95,696 -> 104,028.

Found while shipping [disambig-loci], which fixed the fourth instance —
`cmd_callers` re-deriving its hit's section through the same collapse — and
whose commit claims located hits carry their own section.  That claim is only
true if these do too.

The invariant most of this file asserts is deliberately order-independent:
**an owner must be an entry of the file the row names.**  Which section wins a
name-keyed map depends on load order, so a test that pins one direction passes
by luck half the time.
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import cli, graph  # noqa: E402

BASE = ('theory Base\nimports Main\nbegin\n'
        'lemma target: "True" by simp\nend\n')

# Alpha: lines 5..8 are a `text` block, and it MENTIONS `target` — so if the
# prose ranges collapse the wrong way, this becomes a false citation.
ALPHA = (
    "theory Preliminaries\nimports Base\nbegin\n"       # 1-3
    'lemma a_head: "True" by simp\n'                     # 4
    "text \\<open>\n"                                    # 5
    "  a paragraph about target\n"                       # 6
    "  still prose\n"                                    # 7
    "\\<close>\n"                                        # 8
    'lemma a_tail: "True" by simp\n'                     # 9
    "end\n")                                             # 10

# Beta: line 6 is a LIVE citation of `target`, at the line Alpha calls prose.
BETA = (
    "theory Preliminaries\nimports Base\nbegin\n"        # 1-3
    'lemma b_head: "True" by simp\n'                     # 4
    "\n"                                                 # 5
    'lemma b_cites: "True" using target by simp\n'       # 6
    "\n"                                                 # 7
    'lemma b_tail: "True" by simp\n'                     # 8
    "end\n")                                             # 9

FILES = {
    "alpha/ROOT": ("session Alpha = HOL +\n  theories\n    Base\n"
                   "    Preliminaries\n"),
    "alpha/Base.thy": BASE,
    "alpha/Preliminaries.thy": ALPHA,
    "beta/ROOT": ("session Beta = HOL +\n  theories\n    Base\n"
                  "    Preliminaries\n"),
    "beta/Base.thy": BASE,
    "beta/Preliminaries.thy": BETA,
}


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


class CollidingCorpus(unittest.TestCase):
    """Two entries, each declaring a bare `Preliminaries` and a bare `Base`."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        for rel, text in FILES.items():
            p = self.dir / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
        cli._ROOT_OVERRIDE = self.dir
        self.dir = self.dir.resolve()
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def sec(self, rel):
        return next(s for s in self.sections
                    if s.path == self.dir / f"{rel}.thy")

    def test_the_fixture_really_collides(self):
        """Guard the guard: without a shared name this file proves nothing."""
        names = [s.theory for s in self.sections]
        self.assertEqual(names.count("Preliminaries"), 2, names)
        self.assertEqual(names.count("Base"), 2, names)


class EachSectionKeepsItsOwnIndex(CollidingCorpus):
    """One entry per SECTION, not per name — the defect, stated directly."""

    def test_line_index(self):
        idx = graph._build_line_index(self.sections)
        self.assertEqual(len(idx), len(self.sections))

    def test_noise_ranges(self):
        self.assertEqual(len(graph._noise_ranges(self.sections)),
                         len(self.sections))

    def test_def_sites(self):
        self.assertEqual(len(graph._build_def_sites(self.sections)),
                         len(self.sections))

    def test_the_owner_of_a_line_comes_from_that_file(self):
        idx = graph._build_line_index(self.sections)
        a, b = self.sec("alpha/Preliminaries"), self.sec("beta/Preliminaries")
        # Line 6 is Alpha's prose (owned by the entry it introduces) and
        # Beta's live citation.  One line number, two files, two answers.
        self.assertEqual(
            graph._entry_at_line(idx[a.path], 6).name, "a_tail")
        self.assertEqual(
            graph._entry_at_line(idx[b.path], 6).name, "b_cites")

    def test_prose_is_prose_only_in_the_file_that_wrote_it(self):
        noise = graph._noise_ranges(self.sections)
        a, b = self.sec("alpha/Preliminaries"), self.sec("beta/Preliminaries")
        self.assertTrue(any(6 in r for r in noise[a.path]))
        self.assertFalse(any(6 in r for r in noise[b.path]))


class SuppressionIsTheWorstHalf(CollidingCorpus):
    r"""A collapsed prose range drops a real citation, silently.

    Beta's line 6 cites `target` in live proof text; Alpha calls line 6 prose.
    Read through a name-keyed map one of the two is wrong, and which one
    depends on load order — so both directions are asserted here.
    """

    def test_the_live_citation_is_found(self):
        hits = cli._find_callers(self.sections, "target")
        found = {(s.theory, ln) for s, ln, _t in hits}
        self.assertIn(("Preliminaries", 6), found)

    def test_the_prose_mention_is_not(self):
        hits = cli._find_callers(self.sections, "target")
        for sec, ln, text in hits:
            with self.subTest(line=ln):
                self.assertNotIn("a paragraph about", text)

    def test_exactly_one_citation(self):
        # Two `Preliminaries`, one live citation between them.
        self.assertEqual(len(cli._find_callers(self.sections, "target")), 1)

    def test_the_call_graph_agrees_with_callers(self):
        g = graph._build_call_graph(self.sections)
        self.assertEqual(g.callers.get("target"), {"b_cites"})


class AnOwnerBelongsToTheFileItNames(CollidingCorpus):
    """The order-independent invariant, checked on every row a verb prints.

    `grep` and `methods` both fill their owner column through
    `_entry_at_line`, so a collapsed line index gives them an owner from
    another file — with a span from that file beside it.
    """

    def entries_of(self, path):
        return {e.name for s in self.sections if s.path == path
                for e in s.entries}

    def _check(self, hits, path_at, owner_at):
        checked = 0
        for h in hits:
            owner = owner_at(h)
            if owner is None:
                continue
            checked += 1
            path = path_at(h)
            with self.subTest(path=path.name, owner=owner.name):
                self.assertIn(owner.name, self.entries_of(path))
        self.assertGreater(checked, 0, "nothing was checked")

    def test_grep_owners(self):
        import re
        hits = cli._grep_sections(self.sections, re.compile("True"))
        self._check(hits, lambda h: h[0], lambda h: h[3])

    def test_method_owners(self):
        _counts, located = graph._scan_methods(self.sections, only="simp")
        self._check(located, lambda h: h[0], lambda h: h[2])


class ExternalSkipsFilesNotNames(CollidingCorpus):
    r"""`callers --external` drops the file(s) declaring the name.

    Keyed by name it dropped every file SHARING a name with a declaring one —
    so a citation in Beta's `Preliminaries` would vanish because Alpha's
    `Preliminaries` happened to declare something.  Here `target` is declared
    in both `Base` files and cited from Beta's `Preliminaries`, which declares
    it nowhere, so `--external` must keep the hit.
    """

    def test_a_citation_outside_the_declaring_file_survives(self):
        hits = cli._find_callers(self.sections, "target", external=True)
        self.assertEqual([(s.theory, ln) for s, ln, _t in hits],
                         [("Preliminaries", 6)])


if __name__ == "__main__":
    unittest.main()
