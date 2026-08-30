r"""Two entries can share a span, and the line index has to survive it.

`_build_line_index` sorts `(src_start, thy_end, Entry)` triples so a line can
be resolved to its owning entry by binary search.  Python compares tuples
element by element and only reaches a later component when the earlier ones are
equal — so the moment two entries share BOTH integers, the sort falls through
to comparing two `Entry` dataclasses, which have no ordering, and raises
`TypeError`.

One `axiomatization` line declaring several names is exactly that case, and it
is not exotic: `FOL/ex/Locale_Test/Locale_Test1` has four such pairs, and `ZF`
has them in `ZF_Base`, `Coind/Static` and `ex/LList`.  The failure is total
rather than partial — every verb that builds the index (`grep`, `sorry`,
`enclosing`, and the whole usage family once it needs a call graph) died with a
traceback and exit 1 before printing anything, on any corpus containing such a
line.

Found by the differential harness in David Wang's Scala port, which had to pin
132 cases on it to run its matrix on FOL and ZF at all.

The fix sorts on the two integers only.  Python's sort is stable, so entries
with equal spans keep source order, which is what every consumer here assumes.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

# Verbatim from FOL/ex/Locale_Test/Locale_Test1:544-550.  Two names share one
# continuation line, so `gle` and `gless` get identical (src_start, thy_end).
AXIOMATIZATION = r"""theory Ties
imports Main
begin

axiomatization
  gle :: \<open>'a => 'a => o\<close> and gless :: \<open>'a => 'a => o\<close> and
  gle' :: \<open>'a => 'a => o\<close> and gless' :: \<open>'a => 'a => o\<close>
where
  grefl: \<open>gle(x, x)\<close> and gless_def: \<open>gless(x, y)\<close> and
  grefl': \<open>gle'(x, x)\<close> and gless'_def: \<open>gless'(x, y)\<close>

lemma below: \<open>True\<close> by simp

end
"""


class LineIndexTies(unittest.TestCase):

    def setUp(self):
        self.sec = section_from(AXIOMATIZATION, "Ties")

    def test_the_fixture_really_produces_a_tie(self):
        """Guard the guard: without a shared span this test proves nothing."""
        spans = [(e.src_start, e.thy_end) for e in self.sec.entries
                 if e.thy_line > 0]
        self.assertGreater(len(spans), len(set(spans)),
                           f"fixture produced no duplicate span: {spans}")

    def test_the_index_builds(self):
        index = cli._build_line_index([self.sec])
        self.assertIn("Ties", index)

    def test_tied_entries_keep_source_order(self):
        index = cli._build_line_index([self.sec])["Ties"]
        ordered = [e.name for _, _, e in index]
        source_order = [e.name for e in self.sec.entries if e.thy_line > 0]
        self.assertEqual(
            [n for n in source_order if n in ordered], ordered,
            "a stable sort must leave equal spans in source order")

    def test_the_index_is_sorted_by_span(self):
        index = cli._build_line_index([self.sec])["Ties"]
        keys = [(lo, hi) for lo, hi, _ in index]
        self.assertEqual(keys, sorted(keys))

    def test_enclosing_still_answers_below_the_tie(self):
        """The tie must not disturb lookup of the entries after it."""
        index = cli._build_line_index([self.sec])["Ties"]
        line = next(e.thy_line for e in self.sec.entries if e.name == "below")
        owners = [e.name for lo, hi, e in index if lo <= line <= hi]
        self.assertIn("below", owners)


if __name__ == "__main__":
    unittest.main()
