"""`--statement` / `--stmt` across the find/show complement.

One flag, two roles, matching the find/show split:

  * `find --statement PAT` is the **input** side — it changes *what the
    regex is tested against* (each entry's declaration slice, not its
    name), so it surfaces lemmas *stated about* a constant whatever they
    are named.  It does NOT change how matches are rendered.
  * `show NAME --statement` is the **output** side — it changes *how the
    entry is rendered* (declaration only, no proof), a strictly narrower
    view than the default render or `-V/--verbatim` (which both include
    the proof).  It does NOT change how the name is matched.

The fixture is built so name-search and statement-search deliberately
diverge: `size_bound` mentions `widget` only in its *statement*, so a
name search misses it and a statement search finds it — that single entry
is the whole point of the feature.
"""

import contextlib
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

# `size_bound`'s NAME has no "widget"; its STATEMENT (the `assumes`) does.
# That asymmetry is what separates name-search from statement-search.
THY = """theory Slice imports Main begin

definition widget :: nat where
  "widget = 42"

lemma size_bound:
  assumes "x > widget"
  shows "x \\<noteq> 0"
  using assms by auto

lemma comm_add: "a + b = b + (a::nat)"
  by simp

end
"""

PROOF_LINE = "using assms by auto"


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


# Both the `--all`/first render header (`--- NAME (TAG) — ...`) and the
# `--names` line (`NAME (TAG) — ...`) start with an entry name then ` (TAG)`.
_NAME_LINE = re.compile(r"^(?:--- )?(\S+) \([A-Z]+\) —")


def _names(output):
    """Entry names from header / name-listing lines, in printed order."""
    return [m.group(1) for line in output.splitlines()
            if (m := _NAME_LINE.match(line))]


class FindStatementMatch(unittest.TestCase):
    """`find --statement` matches the declaration slice (input side)."""

    def setUp(self):
        self.secs = [section_from(THY, "Slice")]

    def _find(self, pat, statement, mode="names"):
        f = cli.CmdFlags()
        f.mode = mode
        f.statement = statement
        return _capture(cli.cmd_find, self.secs, pat, f)

    def test_name_search_misses_statement_only_reference(self):
        # `widget` appears in size_bound's *statement* but not its name,
        # so the default (name) search must not return it.
        out = self._find("widget", statement=False)
        self.assertIn("widget", _names(out))          # the definition itself
        self.assertNotIn("size_bound", _names(out))

    def test_statement_search_finds_statement_only_reference(self):
        # The headline capability: size_bound is *stated about* widget.
        out = self._find("widget", statement=True)
        self.assertIn("size_bound", _names(out))
        self.assertIn("widget", _names(out))
        # comm_add's statement never mentions widget -> excluded either way.
        self.assertNotIn("comm_add", _names(out))

    def test_statement_search_does_not_match_proof_body(self):
        # `assms` lives only in size_bound's PROOF, never in a statement.
        # A statement search must not see it (that is the proof/statement
        # split's whole job — it would be a `grep`, not a `find`).
        self.assertEqual(_names(self._find("assms", statement=True)), [])
        # `grep`-style proof text is reachable; just not via --statement.

    def test_count_mode_reflects_statement_matches(self):
        self.assertEqual(self._find("widget", True, mode="count").strip(), "2")
        self.assertEqual(self._find("widget", False, mode="count").strip(), "1")

    def test_statement_is_match_only_not_render(self):
        # On find, --statement selects the match locus; the matched entries
        # are still rendered the usual way (statement + proof preview), so
        # the proof line is present in the rendered output.
        out = self._find("widget", statement=True, mode="all")
        self.assertIn(PROOF_LINE, out)


class ShowStatementRender(unittest.TestCase):
    """`show --statement` renders the declaration only (output side)."""

    def setUp(self):
        self.secs = [section_from(THY, "Slice")]

    def _show(self, name, **kw):
        f = cli.CmdFlags()
        for k, v in kw.items():
            setattr(f, k, v)
        return _capture(cli.cmd_show, self.secs, name, f)

    def test_statement_render_drops_the_proof(self):
        out = self._show("size_bound", statement=True)
        self.assertIn("lemma size_bound:", out)
        self.assertIn('shows "x \\<noteq> 0"', out)
        self.assertNotIn(PROOF_LINE, out)   # proof excluded — the new view

    def test_default_render_keeps_the_proof(self):
        # Contrast: the default render shows the statement + a proof preview.
        self.assertIn(PROOF_LINE, self._show("size_bound"))

    def test_verbatim_keeps_the_proof(self):
        # `-V` is the *full* slice (statement + proof), so --statement is a
        # strictly narrower view, not a synonym for verbatim.
        self.assertIn(PROOF_LINE, self._show("size_bound", verbatim=True))

    def test_statement_render_does_not_change_matching(self):
        # show still matches by name (exact/substring); --statement only
        # affects rendering, so a name that doesn't exist still misses.
        self.assertEqual(_names(self._show("nonesuch", statement=True)), [])


class RenderEntryPrecedence(unittest.TestCase):
    """`render_entry` slice selectors, independent of the CLI."""

    def setUp(self):
        self.sec = section_from(THY, "Slice")
        self.entry = next(e for e in self.sec.entries if e.name == "size_bound")

    def test_statement_narrower_than_verbatim_if_both_set(self):
        # The CLI makes these mutually exclusive on `show`; if both still
        # arrive, the narrower (statement) view wins defensively.
        out = cli.render_entry(self.sec, self.entry, statement=True, verbatim=True)
        self.assertNotIn(PROOF_LINE, out)

    def test_statement_text_helper_is_the_declaration(self):
        text = cli._statement_text(self.sec, self.entry)
        self.assertIn("lemma size_bound:", text)
        self.assertNotIn(PROOF_LINE, text)


class CliSurface(unittest.TestCase):
    """Parser-level wiring: aliases, dest, and the show-only mutex."""

    def setUp(self):
        self.parser = cli._build_parser()

    def test_both_spellings_set_statement(self):
        for spelling in ("--statement", "--stmt"):
            with self.subTest(spelling=spelling):
                ns = self.parser.parse_args(["find", "X", spelling])
                self.assertTrue(ns.statement)

    def test_find_allows_statement_with_verbatim(self):
        # On find the two are orthogonal (match locus vs render), so they
        # compose: match the statement, render the full slice.
        ns = self.parser.parse_args(["find", "X", "--statement", "-V"])
        self.assertTrue(ns.statement)
        self.assertTrue(ns.verbatim)

    def test_show_rejects_statement_with_verbatim(self):
        with self.assertRaises(SystemExit):
            self.parser.parse_args(["show", "X", "-V", "--statement"])

    def test_show_statement_default_is_false(self):
        ns = self.parser.parse_args(["show", "X"])
        self.assertFalse(ns.statement)


if __name__ == "__main__":
    unittest.main()
