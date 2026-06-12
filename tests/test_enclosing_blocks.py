"""`enclosing` proof-internal drill-down — the nearest enclosing *block*.

`[enclosing-drilldown]`: for a line inside a large structured proof the
owning entry is usually the part you already know; the useful answer is the
*smallest live block* the line sits in, emitted as a pasteable `A..B` range
(`▸ have key 11..14`).  Three modes form an outer→inner spectrum:

  * ``"entry"``   — the owning entry only (the original behaviour),
  * ``"nearest"`` — the innermost enclosing block (default),
  * ``"blocks"``  — the full nesting path, entry then each block outer→inner.

The scanner (`_proof_blocks`) is deliberately conservative and *fail-safe*:
openers/closers are line-anchored (a `proof`/`{` inside a term string is
ignored) and an unbalanced stack returns None, so output degrades to the
entry rather than emitting a span it can't stand behind.  The fixture
(`Nested.thy`) is shaped to grip every branch: a flat `by` proof, two
levels of nested `have … proof … qed`, a raw `{ … }` block, and an in-proof
line that sits outside every nested block.
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")
NESTED_THY = os.path.join(FIXTURES, "Nested.thy")
with open(NESTED_THY, encoding="utf-8") as _fh:
    THY = _fh.read()


def _sec():
    return section_from(THY, "Nested")


def _entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


def _blocks(name):
    sec = _sec()
    return cli._proof_blocks(sec, _entry(sec, name))


def _as_tuples(blocks):
    """`(label, start, end)` sorted by span — order-independent comparison."""
    return [(cli._block_label(b), b.start, b.end)
            for b in sorted(blocks, key=lambda b: (b.start, b.end))]


def _run(loci, mode="nearest"):
    sec = _sec()
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        cli.cmd_enclosing([sec], loci, mode)
    return out.getvalue(), err.getvalue()


class ProofBlocks(unittest.TestCase):
    """`_proof_blocks` finds the nested blocks, *excluding* the entry's own
    outermost proof (which the entry already represents)."""

    def test_structured_nested_haves(self):
        self.assertEqual(
            _as_tuples(_blocks("structured")),
            [("have apos_1", 9, 16), ("have key", 11, 14)])

    def test_main_proof_is_not_reported(self):
        # The lemma's own `proof -` (line 8) spans almost the whole entry;
        # it must not appear as a block — only the inner haves do.
        spans = {(b.start, b.end) for b in _blocks("structured")}
        self.assertNotIn((8, 18), spans)

    def test_raw_brace_block(self):
        self.assertEqual(_as_tuples(_blocks("braced")), [("{ }", 22, 24)])

    def test_flat_proof_has_no_blocks(self):
        # `lemma flat: "True" by simp` — a flat proof drills to nothing.
        self.assertEqual(_blocks("flat"), [])

    def test_unbalanced_proof_fails_safe(self):
        # A proof missing its outer `qed`: the scan ends with the stack
        # non-empty and returns None, so the caller falls back to the entry.
        bad = section_from(
            "theory U imports Main begin\n"
            "\n"
            'lemma bad: "P"\n'
            "proof -\n"
            '  have x: "Q"\n'
            "  proof -\n"
            '    show "Q" by blast\n'
            "  qed\n"          # closes the inner have only; outer qed missing
            "\n"
            "end\n", "U")
        self.assertIsNone(cli._proof_blocks(bad, _entry(bad, "bad")))


class EnclosingBlocks(unittest.TestCase):
    """`_enclosing_blocks` returns the containing blocks outermost→innermost,
    so the last element is the nearest."""

    def test_innermost_is_last(self):
        labels = [cli._block_label(b)
                  for b in cli._enclosing_blocks(_blocks("structured"), 13)]
        self.assertEqual(labels, ["have apos_1", "have key"])

    def test_line_in_outer_block_only(self):
        # Line 15 is inside apos_1 but past key's `qed` (14).
        labels = [cli._block_label(b)
                  for b in cli._enclosing_blocks(_blocks("structured"), 15)]
        self.assertEqual(labels, ["have apos_1"])

    def test_line_in_main_proof_only(self):
        # Line 17 is in the proof but outside every nested block.
        self.assertEqual(cli._enclosing_blocks(_blocks("structured"), 17), [])


class CliModes(unittest.TestCase):

    def test_default_shows_nearest_block(self):
        out, _ = _run(["Nested:13"])
        self.assertIn("structured (LEMMA)", out)
        self.assertIn("▸ have key 11..14", out)
        self.assertNotIn("apos_1", out)              # nearest only, not the path

    def test_entry_mode_suppresses_drilldown(self):
        out, _ = _run(["Nested:13"], mode="entry")
        self.assertIn("structured (LEMMA)", out)
        self.assertNotIn("▸", out)

    def test_blocks_mode_shows_full_path(self):
        out, _ = _run(["Nested:13"], mode="blocks")
        self.assertIn("▸ have apos_1 9..16", out)
        self.assertIn("have key", out)
        self.assertIn("11..14", out)

    def test_outer_block_when_outside_inner(self):
        out, _ = _run(["Nested:15"])
        self.assertIn("▸ have apos_1 9..16", out)
        self.assertNotIn("key", out)

    def test_flat_proof_degrades_to_entry(self):
        out, _ = _run(["Nested:4"])
        self.assertIn("flat (LEMMA)", out)
        self.assertNotIn("▸", out)

    def test_in_proof_but_no_block_degrades(self):
        out, _ = _run(["Nested:17"])
        self.assertNotIn("▸", out)

    def test_brace_block(self):
        out, _ = _run(["Nested:23"])
        self.assertIn("▸ { } 22..24", out)

    def test_entry_mode_matches_pre_drilldown_output(self):
        # `--entry` must reproduce exactly the old entry-only line.
        out, _ = _run(["Nested:13"], mode="entry")
        sec = _sec()
        e = _entry(sec, "structured")
        self.assertIn(f"Nested:13 → structured ({e.tag}) — Nested "
                      f"{cli._format_extent(e)}  (in proof)", out)


class RoundTrip(unittest.TestCase):
    """A breadcrumb's `start..end` is a real locus: prefix the theory and it
    parses straight back, so `▸ have key 11..14` → `lines Nested 11..14`."""

    def test_block_field_span_parses_back(self):
        b = cli._Block("have", "key", 11, 14)
        self.assertEqual(cli._block_field(b), "have key 11..14")
        self.assertEqual(cli._parse_locus(f"Nested:{b.start}..{b.end}"),
                         ("Nested", 11, 14))


class ParserWiring(unittest.TestCase):

    def setUp(self):
        self.parser = cli._build_parser()

    def test_defaults_to_nearest(self):
        ns = self.parser.parse_args(["enclosing", "Foo:1"])
        self.assertFalse(ns.entry)
        self.assertFalse(ns.blocks)

    def test_blocks_flag(self):
        ns = self.parser.parse_args(["enclosing", "-b", "Foo:1"])
        self.assertTrue(ns.blocks)

    def test_entry_and_blocks_mutually_exclusive(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                self.parser.parse_args(["enclosing", "-e", "-b", "Foo:1"])


if __name__ == "__main__":
    unittest.main()
