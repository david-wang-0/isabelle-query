r"""Every `theory:line` a verb prints names ONE theory [disambig-loci].

`[disambig-names]` shipped the label mechanism (`render.theory_labels`) and
the resolver half, and put the label on `largest` alone.  Every other emitter
still printed the bare theory name, so `largest` said
`Virtual_Substitution/QE` and `enclosing Virtual_Substitution/QE:3495`
answered `QE:3495` — the tool accepting a qualified name and handing back an
ambiguous one, which is the round-trip hole `[locus-roundtrip]` exists to
close.

**Measuring it reclassified the item.**  It was filed as cosmetic threading
("the work is threading a label map, not deciding anything").  It is not:
`cmd_callers` re-derived its hit's section from the theory NAME through
`graph._sections_by_theory`, a last-wins `{name: section}` map, and then read
the owner column and the `-U` context lines out of whatever section won.  Over
the AFP that is **9,239 of `callers assms`' 161,426 rows** printing a line
that does not occur in the file their owner came from — and all 59 ambiguous
rows of `callers wf`.  `WrongFileAttribution` is that defect; the rest of this
file is the label.

The fixture mirrors the AFP's shape rather than inventing one, because the
shape is what makes the names collide: two entries, each with its own ROOT,
each declaring a bare `Examples`.  A ROOT that spells a theory WITH a
directory (`theories "alpha/Examples"`) gives the section that spelling as its
name, so there is nothing to disambiguate — the first fixture written here did
exactly that and quietly tested nothing.

Alpha's `Examples` is deliberately five lines while Beta's citation sits on
line 6, so a row read out of the wrong file lands past the end of the file it
was read from.  The wrongness is then visible in the output rather than merely
possible.
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import cli  # noqa: E402
from isabelle_query.commands import _resolve_theory  # noqa: E402

# `Base` declares the cited name and carries the one structured proof, so the
# `shape` verbs (which need a goal step, not a one-line `by`) have something to
# rank.  Every entry gets its own copy: `_find_callers` filters by import
# closure, and `isabelle-layout` follows imports only WITHIN an entry, so a
# citation of another entry's `shared` is correctly invisible.
BASE = ("theory Base\nimports Main\nbegin\n"
        'lemma shared: "True" by simp\n\n'
        'lemma structured: "True \\<and> True"\n'
        'proof\n'
        '  show "True" by simp\n'
        '  show "True" by simp\n'
        'qed\nend\n')

FILES = {
    "alpha/ROOT": "session Alpha = HOL +\n  theories\n    Base\n    Examples\n",
    "alpha/Base.thy": BASE,
    # 1 theory / 2 imports / 3 begin / 4 a_owner / 5 end
    "alpha/Examples.thy": (
        "theory Examples\nimports Base\nbegin\n"
        'lemma a_owner: "True" using shared by simp\nend\n'),
    "beta/ROOT": "session Beta = HOL +\n  theories\n    Base\n    Examples\n",
    "beta/Base.thy": BASE,
    # 4 b_pad / 6 b_owner / 8 b_after (sorry) — b_owner is past alpha's end
    "beta/Examples.thy": (
        "theory Examples\nimports Base\nbegin\n"
        'lemma b_pad: "True" by simp\n\n'
        'lemma b_owner: "True" using shared by simp\n\n'
        'lemma b_after: "True" sorry\nend\n'),
    "solo/ROOT": "session Solo = HOL +\n  theories\n    Base\n    Unique\n",
    "solo/Base.thy": BASE,
    "solo/Unique.thy": (
        "theory Unique\nimports Base\nbegin\n"
        'lemma only_one: "True" using shared by simp\nend\n'),
}


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


class CorpusFixture(unittest.TestCase):
    """Two colliding `Examples`, plus a theory whose name is used once."""

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
        want = (self.dir / f"{rel}.thy")
        return next(s for s in self.sections if s.path == want)

    def loci(self, out, context=False):
        """Every `NAME:LINE` token the output opens a row with.

        `callers` defaults to `-U 2`, so its rows are interleaved with context
        lines carrying ripgrep's trailing `-`.  Those are loci too and must
        still parse, but they are not MATCH loci — an earlier draft of this
        file collected them together and then asserted the matched text on a
        context line.
        """
        found = []
        for line in out.splitlines():
            # Every token, not just the first: `shape widest` leads with the
            # metric value, so a first-token-only scan read its table as
            # having no loci at all and the assertion passed on nothing.
            for tok in line.split():
                tail = tok.rsplit(":", 1)[-1] if ":" in tok else ""
                if not tail:
                    continue
                marked = tail.endswith("-")
                if tail.rstrip("-").isdigit() and marked == context:
                    found.append(tok)
        return found


class WrongFileAttribution(CorpusFixture):
    """The defect measuring turned up: an owner read out of another file.

    `_find_callers` now reports the hit's SECTION, so the owner column and the
    context lines come from the file the row prints.  Before, they came from
    whichever same-named section `_sections_by_theory` happened to keep — here
    that is Beta's, so Alpha's row was attributed to `b_pad`, a lemma in a
    different file, with `b_pad`'s span beside it.
    """

    def _callers(self, context=0):
        f = cli.CmdFlags()
        f.context = context
        return _capture(cli.cmd_callers, self.sections, "shared", f)

    def test_each_row_is_owned_by_a_lemma_in_its_own_file(self):
        out = self._callers()
        rows = [ln for ln in out.splitlines() if "using shared" in ln]
        self.assertEqual(len(rows), 3)
        owner_of = {}
        for row in rows:
            loc, rest = row.split(None, 1)
            owner_of[loc] = rest.split()[0]
        self.assertEqual(owner_of["alpha/Examples:4"], "a_owner")
        self.assertEqual(owner_of["beta/Examples:6"], "b_owner")
        self.assertEqual(owner_of["Unique:4"], "only_one")

    def test_no_row_names_an_entry_from_another_file(self):
        # The specific wrong answer: Alpha's line 4 attributed to `b_pad`.
        out = self._callers()
        alpha = next(ln for ln in out.splitlines()
                     if ln.strip().startswith("alpha/Examples:4"))
        self.assertNotIn("b_pad", alpha)
        self.assertNotIn("b_owner", alpha)

    def test_context_lines_come_from_the_matched_file(self):
        # Alpha line 5 is `end`; Beta line 5 is blank.  Reading the context out
        # of the wrong file is silent — a blank line looks like a blank line —
        # so the assertion is on the content, not on the count.
        out = self._callers(context=1)
        ctx = [ln.strip() for ln in out.splitlines()
               if ln.strip().startswith("alpha/Examples:5-")]
        self.assertEqual(len(ctx), 1)
        self.assertTrue(ctx[0].endswith("end"), ctx[0])


class EveryLocusIsQualified(CorpusFixture):
    """The nine emitters the item lists, each checked on what it prints."""

    def test_callers(self):
        out = _capture(cli.cmd_callers, self.sections, "shared", cli.CmdFlags())
        self.assertIn("alpha/Examples:4", out)
        self.assertIn("beta/Examples:6", out)

    def test_methods(self):
        out = _capture(cli.cmd_methods, self.sections, "simp", cli.CmdFlags())
        self.assertIn("alpha/Examples:4", out)
        self.assertIn("beta/Examples:4", out)

    def test_methods_names_mode(self):
        f = cli.CmdFlags()
        f.mode = "names"
        out = _capture(cli.cmd_methods, self.sections, "simp", f)
        self.assertIn("alpha/Examples:4", out)

    def test_grep(self):
        out = _capture(cli.cmd_grep, self.sections, "using shared",
                       cli.CmdFlags())
        self.assertIn("alpha/Examples.thy:4", out)
        self.assertIn("beta/Examples.thy:6", out)

    def test_grep_names_mode(self):
        f = cli.CmdFlags()
        f.mode = "names"
        out = _capture(cli.cmd_grep, self.sections, "using shared", f)
        self.assertIn("alpha/Examples.thy:4", out)

    def test_sorry(self):
        out = _capture(cli.cmd_sorry, self.sections, False)
        self.assertIn("beta/Examples.thy:8", out)

    def test_enclosing(self):
        out = _capture(cli.cmd_enclosing, self.sections, ["beta/Examples:6"])
        self.assertTrue(out.startswith("beta/Examples:6 →"), out)

    def test_enclosing_range(self):
        out = _capture(cli.cmd_enclosing, self.sections, ["beta/Examples:4..6"])
        self.assertIn("beta/Examples:4..6", out)

    def test_largest(self):
        out = _capture(cli.cmd_largest, self.sections, 20)
        self.assertIn("alpha/Examples", out)
        self.assertIn("beta/Examples", out)

    def test_shape_steps(self):
        from isabelle_query import shape_cmds
        out = _capture(shape_cmds.cmd_shape_steps, self.sections,
                       all_steps=True)
        self.assertTrue(any(t.startswith("alpha/Examples:")
                            for t in self.loci(out)), out)

    def test_shape_widest(self):
        # Ranked on `Base`'s structured proof: `widest` reports goal steps, and
        # a one-line `by simp` has none.
        from isabelle_query import shape_cmds
        out = _capture(shape_cmds.cmd_shape_widest, self.sections, top=20)
        self.assertTrue(any("/Base:" in t for t in self.loci(out)), out)

    def test_shape_lemma(self):
        from isabelle_query import shape_cmds
        out = _capture(shape_cmds.cmd_shape_lemma, self.sections, "b_owner")
        self.assertIn("beta/Examples:", out)


class TheEchoSaysWhatWasResolved(CorpusFixture):
    """`enclosing` echoes the LABEL, not the token the user typed.

    Both directions matter.  A locus given as an ambiguous `Examples:6` comes
    back qualified, so the output states which of the two the tool picked
    instead of leaving the reader to guess; and a locus given as a path comes
    back in the house `theory:line` form rather than as the path.  Echoing the
    input verbatim is the easy answer and defeats the purpose of the echo.
    """

    def test_an_ambiguous_input_is_echoed_qualified(self):
        out = _capture(cli.cmd_enclosing, self.sections, ["Examples:6"])
        self.assertRegex(out, r"^(alpha|beta)/Examples:6")

    def test_a_path_input_is_echoed_as_a_theory(self):
        p = self.dir / "beta" / "Examples.thy"
        out = _capture(cli.cmd_enclosing, self.sections, [f"{p}:6"])
        self.assertTrue(out.startswith("beta/Examples:6 →"), out)

    def test_past_end_names_the_theory_it_measured(self):
        # `Examples:99` resolves to one of the two; the message quotes a line
        # count, and a bare name leaves it unclear whose count that is.
        out = _capture(cli.cmd_enclosing, self.sections, ["Examples:99"])
        self.assertRegex(out, r"past end of (alpha|beta)/Examples")


class TheLocusIsValidInput(CorpusFixture):
    """Every printed locus parses and resolves back to the file it names."""

    def test_callers_loci_round_trip(self):
        out = _capture(cli.cmd_callers, self.sections, "shared", cli.CmdFlags())
        for tok in self.loci(out):
            with self.subTest(locus=tok):
                name, lo, _hi = cli._parse_locus(tok)
                sec = _resolve_theory(self.sections, name)
                self.assertIsNotNone(sec, f"{name} does not resolve")
                self.assertIn("using shared", sec.source()[lo - 1])

    def test_grep_loci_round_trip_with_their_suffix(self):
        out = _capture(cli.cmd_grep, self.sections, "using shared",
                       cli.CmdFlags())
        for tok in self.loci(out):
            with self.subTest(locus=tok):
                name, lo, _hi = cli._parse_locus(tok)
                self.assertTrue(name.endswith(".thy"), name)
                sec = _resolve_theory(self.sections, name)
                self.assertIsNotNone(sec, f"{name} does not resolve")
                self.assertIn("using shared", sec.source()[lo - 1])

    def test_context_loci_round_trip_too(self):
        # The `-` marker is ripgrep's "context, not match"; `_parse_locus`
        # strips it, so a context row is as pasteable as a match row.
        f = cli.CmdFlags()
        f.context = 2
        out = _capture(cli.cmd_callers, self.sections, "shared", f)
        toks = self.loci(out, context=True)
        self.assertTrue(toks, out)
        for tok in toks:
            with self.subTest(locus=tok):
                name, _lo, _hi = cli._parse_locus(tok)
                self.assertIsNotNone(_resolve_theory(self.sections, name))

    def test_enclosing_accepts_its_own_output(self):
        first = _capture(cli.cmd_enclosing, self.sections, ["beta/Examples:6"])
        again = _capture(cli.cmd_enclosing, self.sections,
                         [self.loci(first)[0]])
        self.assertEqual(first, again)


class OnlyACollisionQualifies(CorpusFixture):
    """A name used once stays bare — the label is not a path prefix habit."""

    def test_a_unique_name_stays_bare(self):
        out = _capture(cli.cmd_callers, self.sections, "shared", cli.CmdFlags())
        self.assertIn("Unique:4", out)
        self.assertNotIn("solo/Unique", out)

    def test_grep_keeps_a_unique_file_bare(self):
        out = _capture(cli.cmd_grep, self.sections, "only_one", cli.CmdFlags())
        self.assertIn("Unique.thy:4", out)
        self.assertNotIn("solo/Unique.thy", out)


class PlainFilesKeepTheirName(unittest.TestCase):
    r"""A non-`.thy` grep positional reports its real filename.

    `theory_labels` strips the suffix, because a theory is cited as `Examples`
    and never as `Examples.thy`.  `file_locus` puts it back, so the file view
    is unchanged for prose — the case that would otherwise have turned
    `notes.md` into `notes`.
    """

    def test_a_markdown_positional_keeps_its_suffix(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "notes.md"
            p.write_text("a line about shared\n", encoding="utf-8")
            sec = cli._parse_plain("notes", p)
            out = _capture(cli.cmd_grep, [sec], "shared", cli.CmdFlags())
            self.assertIn("notes.md:1", out)


if __name__ == "__main__":
    unittest.main()
