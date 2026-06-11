"""`query enclosing FILE:LINE` — the span-containment inverse of `outline`.

The feature answers the build-chase question "which entry owns line N?".
It is pure composition over the existing index: `_parse_locus` splits the
locus, `_resolve_theory` routes the FILE half (path *or* bare name), and
`_enclosing_entry` does the `[thy_line, thy_end]` containment lookup the
rest of the tool already relies on.  So the tests pin three things:

  * the locus parser's edge cases (last-colon split, bad LINE),
  * that a line maps to the entry whose span contains it (and to the right
    statement/proof *role* within it),
  * that the header region (before the first entry) reports no owner, and
    that malformed / unresolved loci go to stderr without derailing a batch.

Spans are read back off the parsed section rather than hardcoded, so the
assertions don't drift if the parser's line attribution shifts.
"""

import contextlib
import io
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

# The fixture is shaped so each test has something to grip: a `section`
# header sits between the entries, making the region before `widget` a
# genuine unowned gap (the no-owner case); `size_bound` spans a statement
# and a proof (the statement/proof role split); `comm_add` is clear of both.
# It lives on disk (not inline) so the *same* theory drives both the
# in-memory `section_from` tests here and the on-disk load-path tests in
# `OnDiskFixture` — one source, no drift.
FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")
OWNERS_THY = os.path.join(FIXTURES, "Owners.thy")
with open(OWNERS_THY, encoding="utf-8") as _fh:
    THY = _fh.read()


def _run(loci):
    """Run cmd_enclosing on a one-theory index, capturing (stdout, stderr)."""
    sec = section_from(THY, "Owners")
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        cli.cmd_enclosing([sec], loci)
    return out.getvalue(), err.getvalue()


def _entry(name):
    return next(e for e in section_from(THY, "Owners").entries
                if e.name == name)


class ParseLocus(unittest.TestCase):
    """`_parse_locus` -> (file, lo, hi); single line gives lo == hi."""

    def test_path_form(self):
        self.assertEqual(cli._parse_locus("sub/Foo.thy:42"),
                         ("sub/Foo.thy", 42, 42))

    def test_bare_name_form(self):
        self.assertEqual(cli._parse_locus("Foo:42"), ("Foo", 42, 42))

    def test_range_form(self):
        # `A..B` reuses the `lines` range grammar, so a span pastes back in.
        self.assertEqual(cli._parse_locus("Foo:8..12"), ("Foo", 8, 12))

    def test_splits_on_last_colon(self):
        # A file token that itself contains a colon keeps it; only the final
        # `:LINE` is peeled off.
        self.assertEqual(cli._parse_locus("Foo:4:42"), ("Foo:4", 42, 42))

    def test_strips_trailing_rg_match_marker(self):
        # `theory:line:` is ripgrep's match marker (what callers emits); the
        # trailing `:` must not defeat the round-trip back into enclosing.
        self.assertEqual(cli._parse_locus("Foo:42:"), ("Foo", 42, 42))

    def test_strips_trailing_rg_context_marker(self):
        # `theory:line-` is rg's context marker (callers `-U` lines).
        self.assertEqual(cli._parse_locus("Foo:42-"), ("Foo", 42, 42))

    def test_no_colon_is_rejected(self):
        self.assertIsNone(cli._parse_locus("Foo"))

    def test_empty_line_is_rejected(self):
        self.assertIsNone(cli._parse_locus("Foo:"))

    def test_non_numeric_line_is_rejected(self):
        self.assertIsNone(cli._parse_locus("Foo:bar"))

    def test_zero_and_negative_rejected(self):
        self.assertIsNone(cli._parse_locus("Foo:0"))
        self.assertIsNone(cli._parse_locus("Foo:-3"))

    def test_inverted_range_rejected(self):
        # `_parse_line_range` requires lo <= hi.
        self.assertIsNone(cli._parse_locus("Foo:12..8"))


class Containment(unittest.TestCase):
    """A locus maps to the entry whose span contains its line."""

    def test_definition_owns_its_line(self):
        widget = _entry("widget")
        out, err = _run([f"Owners:{widget.thy_line}"])
        self.assertIn("widget (DEF)", out)
        # House `theory:line` form (no `.thy`), so the locus round-trips.
        self.assertIn("Owners:%d →" % widget.thy_line, out)
        self.assertNotIn(".thy:", out)
        self.assertEqual(err, "")

    def test_lemma_statement_line_is_in_statement(self):
        sb = _entry("size_bound")
        out, _ = _run([f"Owners:{sb.thy_line}"])
        self.assertIn("size_bound (LEMMA)", out)
        self.assertIn("(in statement)", out)

    def test_lemma_proof_line_is_in_proof(self):
        sb = _entry("size_bound")
        self.assertGreater(sb.proof_line, 0)   # sanity: it has a proof
        out, _ = _run([f"Owners:{sb.proof_line}"])
        self.assertIn("size_bound (LEMMA)", out)
        self.assertIn("(in proof)", out)

    def test_span_end_line_still_owned(self):
        # thy_end is inclusive — the last line of the span belongs to it.
        sb = _entry("size_bound")
        out, _ = _run([f"Owners:{sb.thy_end}"])
        self.assertIn("size_bound (LEMMA)", out)

    def test_extent_annotation_present(self):
        # Reuses _format_extent, so the `[src A-B, N lines]` block shows.
        widget = _entry("widget")
        out, _ = _run([f"Owners:{widget.thy_line}"])
        self.assertIn(f"[src {widget.thy_line}-{widget.thy_end},", out)


class RangeMode(unittest.TestCase):
    """`FILE:A..B` lists every entry whose span overlaps the range."""

    def _owner_lines(self, out):
        return [ln for ln in out.splitlines() if " → " in ln]

    def test_range_lists_all_overlapping_entries(self):
        # A range clipping the end of `widget` and the start of `size_bound`
        # must name both — the "which lemmas does this hunk touch" case.
        widget, sb = _entry("widget"), _entry("size_bound")
        lo, hi = widget.thy_line, sb.thy_line
        out, err = _run([f"Owners:{lo}..{hi}"])
        self.assertIn("widget (DEF)", out)
        self.assertIn("size_bound (LEMMA)", out)
        self.assertNotIn("comm_add", out)        # well outside the range
        self.assertEqual(err, "")
        for ln in self._owner_lines(out):         # each line round-trips
            self.assertIn(f"Owners:{lo}..{hi} →", ln)

    def test_range_inside_one_entry_lists_only_it(self):
        sb = _entry("size_bound")
        self.assertGreater(sb.thy_end, sb.thy_line)   # multi-line (sanity)
        out, _ = _run([f"Owners:{sb.thy_line}..{sb.thy_line + 1}"])
        self.assertEqual(len(self._owner_lines(out)), 1)
        self.assertIn("size_bound (LEMMA)", out)

    def test_range_in_header_overlaps_nothing(self):
        first = min(e.thy_line for e in section_from(THY, "Owners").entries
                    if e.thy_line > 0)
        self.assertGreater(first, 2)
        out, _ = _run(["Owners:1..2"])
        self.assertIn("no entries overlap", out)

    def test_rg_marker_round_trips_through_command(self):
        # A callers-style `theory:line:` locus (rg match marker) must resolve.
        sb = _entry("size_bound")
        out, err = _run([f"Owners:{sb.proof_line}:"])
        self.assertIn("size_bound (LEMMA)", out)
        self.assertIn("(in proof)", out)
        self.assertEqual(err, "")


class NoOwner(unittest.TestCase):
    """Lines outside every entry span, and out-of-range lines."""

    def test_header_region_has_no_owner(self):
        first = min(e.thy_line for e in section_from(THY, "Owners").entries
                    if e.thy_line > 0)
        self.assertGreater(first, 1)            # there IS a header gap
        out, err = _run(["Owners:1"])
        self.assertIn("no enclosing entry", out)
        self.assertEqual(err, "")

    def test_past_end_of_file(self):
        sec = section_from(THY, "Owners")
        out, _ = _run([f"Owners:{sec.thy_lines + 100}"])
        self.assertIn("past end of Owners", out)


class Errors(unittest.TestCase):
    """Malformed / unresolved loci go to stderr, never stdout."""

    def test_malformed_locus_to_stderr(self):
        out, err = _run(["Owners"])             # no :LINE
        self.assertEqual(out, "")
        self.assertIn("expected FILE:LINE", err)

    def test_unknown_theory_to_stderr(self):
        out, err = _run(["Nonesuch:5"])
        self.assertEqual(out, "")
        self.assertIn("no such theory", err)

    def test_batch_continues_past_a_bad_locus(self):
        # A bad locus must not suppress the good ones in the same call —
        # that batching is the whole reason for the gate-free list form.
        widget = _entry("widget")
        out, err = _run(["Owners", f"Owners:{widget.thy_line}"])
        self.assertIn("widget (DEF)", out)      # the good one still printed
        self.assertIn("expected FILE:LINE", err)

    def test_batch_emits_one_line_per_good_locus(self):
        widget, sb = _entry("widget"), _entry("size_bound")
        out, _ = _run([f"Owners:{widget.thy_line}", f"Owners:{sb.thy_line}"])
        self.assertEqual(len([ln for ln in out.splitlines() if " → " in ln]), 2)


class CliSurface(unittest.TestCase):
    """Parser wiring: the `at` alias, the nargs='+' locus list."""

    def setUp(self):
        self.parser = cli._build_parser()

    def test_enclosing_parses(self):
        ns = self.parser.parse_args(["enclosing", "Foo.thy:42"])
        self.assertEqual(ns.locus, ["Foo.thy:42"])
        self.assertIs(ns.func, cli._run_enclosing)

    def test_at_alias_parses(self):
        ns = self.parser.parse_args(["at", "Foo:1", "Bar:2"])
        self.assertEqual(ns.locus, ["Foo:1", "Bar:2"])
        self.assertIs(ns.func, cli._run_enclosing)

    def test_locus_is_required(self):
        with self.assertRaises(SystemExit):
            self.parser.parse_args(["enclosing"])


class OnDiskFixture(unittest.TestCase):
    """End-to-end over the real load path: parse the committed `.thy` from
    disk and resolve loci through it.

    This reaches what `section_from` cannot — `section_from` unlinks its
    temp file, so the *path form* of `_resolve_theory` (matching a locus's
    FILE against a section's real resolved path) is only exercisable against
    a fixture that stays on disk.
    """

    def _sections(self):
        secs = []
        cli._sections_from_dir(Path(FIXTURES), set(), secs)
        return secs

    def _enclosing(self, loci):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            cli.cmd_enclosing(self._sections(), loci)
        return out.getvalue(), err.getvalue()

    def test_fixture_parses_from_disk(self):
        names = {e.name for s in self._sections() for e in s.entries}
        self.assertTrue({"widget", "size_bound", "comm_add"} <= names)

    def test_name_form_locus(self):
        sb = _entry("size_bound")
        out, err = self._enclosing([f"Owners:{sb.thy_line}"])
        self.assertIn("size_bound (LEMMA)", out)
        self.assertEqual(err, "")

    def test_path_form_locus_resolves_against_on_disk_path(self):
        # The capability section_from can't reach: a FILE *path* token
        # matched to a section by its real resolved path.
        sb = _entry("size_bound")
        out, err = self._enclosing([f"{OWNERS_THY}:{sb.thy_line}"])
        self.assertIn("size_bound (LEMMA)", out)
        self.assertEqual(err, "")

    def test_full_stack_via_parser_and_root(self):
        # The whole `query -R <dir> enclosing Owners:N` wiring: parser ->
        # _run_enclosing -> _load_sections -> load_index -> cmd_enclosing.
        sb = _entry("size_bound")
        parser = cli._build_parser()
        ns = parser.parse_args(
            ["-R", FIXTURES, "enclosing", f"Owners:{sb.thy_line}"])
        saved = cli._ROOT_OVERRIDE
        cli._ROOT_OVERRIDE = Path(FIXTURES).resolve()
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                ns.func(ns)
        finally:
            cli._ROOT_OVERRIDE = saved
        self.assertIn("size_bound (LEMMA)", out.getvalue())


if __name__ == "__main__":
    unittest.main()
