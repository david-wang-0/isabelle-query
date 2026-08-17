r"""Section headings are prose [heading-prose].

`chapter` / `section` / `subsection` / `subsubsection` carry a cartouche of
English, and that cartouche was in no prose list at all — not `text_blocks`, not
`nonisar_ranges`. So every heading in every theory was scanned as Isar: 35,856
headings in the AFP, 36,342 lines (`scripts/probe_prose_openers.py`), two orders
of magnitude more than the `txt` blocks of [txt-prose].

The expensive half was not proof methods but **citations**. A heading like

    section \<open>Consequences proved using helper\<close>

parses "using helper" as a fact list, so the enclosing scope gains an edge to
`helper` — which is the failure `test_nonisar_regions` describes: `callers`
invents a caller, and a lemma named only in a heading looks used and drops out
of `unused`. That is a dead lemma the user cannot find, hidden by its own
section title.

`heading_spans` is a field of its own rather than more `text_blocks`, and the
tests below pin both halves of that choice: headings must reach
`graph._noise_spans`, and they must *not* reach `_attach_preambles`, where a
heading would become the docstring of whatever declaration follows it.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

from isabelle_query import graph, parsing  # noqa: E402

HEAD = "theory T imports Main begin\n"
FOOT = "\nend\n"


class HeadingsAreNotIsar(unittest.TestCase):

    def test_a_heading_does_not_cite(self):
        # The reproduction. `helper` is mentioned only in a section title.
        sec = section_from(HEAD + r'''
lemma helper: "True" by simp

subsection \<open>Consequences proved using helper below\<close>

lemma user: "True" by simp
''' + FOOT)
        cg = graph._build_call_graph([sec])
        self.assertEqual(dict(cg.callers).get("helper", set()), set())

    def test_a_heading_does_not_name_a_method(self):
        # Auto2_Imperative_HOL/Mapping_Str.thy:63 — "Mapping defined by a set of
        # key-value pairs".  That "by a" put a method called `a` into the tally,
        # which is how this bug was found: `query methods` grew an entry that is
        # an English article.
        counts, _ = cli._scan_methods([section_from(HEAD + r'''
subsection \<open>Mapping defined by a set of key-value pairs\<close>

lemma m: "True" by simp
''' + FOOT)])
        self.assertNotIn("a", counts)
        self.assertEqual(counts.get("simp"), 1)

    def test_every_heading_level_and_both_spellings(self):
        sec = section_from(HEAD + r'''
chapter \<open>One\<close>
section \<open>Two\<close>
subsection ‹Three›
subsubsection\<open>Four, with no space before the cartouche\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(len(sec.heading_spans), 4,
                         f"spans: {sec.heading_spans}")

    def test_a_wrapped_heading_carries_its_continuation_lines(self):
        # Smith_Normal_Form/Diagonal_To_Smith.thy:1172 — note `subsection` with
        # no space before `\<open>`, which `SECTION_RE` (the outline pattern)
        # does not match.  Masking must use the wider pattern.
        sec = section_from(HEAD + r'''
subsection\<open>Implementation and formal proof
  of the matrices $P$ and $Q$ which transform the input matrix by means
  of elementary operations.\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(len(sec.heading_spans), 1,
                         f"spans: {sec.heading_spans}")
        start, end = sec.heading_spans[0]
        self.assertEqual(end - start, 2, f"span {start}..{end} is not 3 lines")
        counts, _ = cli._scan_methods([sec])
        self.assertNotIn("means", counts)


class HeadingsAreNotDocstrings(unittest.TestCase):
    """Why `heading_spans` is separate from `text_blocks`."""

    def test_a_heading_is_masked_but_is_not_a_preamble(self):
        sec = section_from(HEAD + r'''
subsection \<open>About the helper\<close>
lemma helper: "True" by simp
''' + FOOT)
        entry = next(e for e in sec.entries if e.name == "helper")
        # Masked for scanners...
        noise = graph._noise_spans(sec)
        self.assertIn(sec.heading_spans[0], noise)
        # ...but not adopted as the declaration's documentation.  Folding
        # headings into `text_blocks` would have given one to every entry that
        # happens to sit under a heading.
        self.assertIsNone(entry.preamble)
        self.assertEqual(sec.text_blocks, [])

    def test_a_text_block_still_is_a_preamble(self):
        # The control: the same adjacency, with `text` instead of a heading.
        sec = section_from(HEAD + r'''
text \<open>Why the helper exists.\<close>
lemma helper: "True" by simp
''' + FOOT)
        entry = next(e for e in sec.entries if e.name == "helper")
        self.assertIsNotNone(entry.preamble)


if __name__ == "__main__":
    unittest.main()
