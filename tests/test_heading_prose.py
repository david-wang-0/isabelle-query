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
        # no space before `\<open>`, which the old tight outline pattern did not
        # match.  One recogniser (`_heading_at`) now answers for both.
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


class EveryHeadingSpelling(unittest.TestCase):
    r"""The residue [heading-prose] left behind, closed by [heading-outline].

    That fix recognised four commands and two cartouche spellings.  Isabelle's
    own keyword table has six commands, and the title may also be a plain
    quoted string — so 4,895 AFP heading lines were still read as Isar, all of
    them invisible to a probe that only knew the forms already handled.
    """

    def test_a_quoted_title_is_prose(self):
        # `section "Foo"` — legacy but accepted, and 3,980 AFP headings use it.
        # Dilworth writes every one of its headings this way, so its `outline`
        # was empty and its prose was entirely live.
        sec = section_from(HEAD + r'''
section "Existence of a chain cover proved by induction"

lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(len(sec.heading_spans), 1, f"{sec.heading_spans}")
        counts, _ = cli._scan_methods([sec])
        self.assertNotIn("induction", counts)
        self.assertEqual(counts.get("simp"), 1)

    def test_paragraph_and_subparagraph_are_headings(self):
        # Present in `_isabelle_namespace.KEYWORDS`, absent from the scanner:
        # 888 AFP headings.  The table is the authority for what a command is.
        sec = section_from(HEAD + r'''
paragraph \<open>Proved by induction on the list\<close>
subparagraph "And by cases on the head"
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(len(sec.heading_spans), 2, f"{sec.heading_spans}")
        counts, _ = cli._scan_methods([sec])
        self.assertNotIn("induction", counts)
        self.assertNotIn("cases", counts)

    def test_a_split_opener_carries_its_title_line(self):
        # The command word alone, title on the next line — the same shape
        # `_TEXT_BARE_RE` already handled for `text`.  Two in the AFP.
        sec = section_from(HEAD + r'''
section
  \<open>Sufficient criteria for being a morphism\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(sec.heading_spans, [(3, 4)])

    def test_a_wrapped_quoted_title_carries_its_continuation(self):
        # Dilworth.thy:294.  A quoted title closes at the next quote, not at
        # end of line — the two AFP cases that wrap are exactly the residue a
        # "rare enough to skip" call would have left live.
        sec = section_from(HEAD + r'''
section "Size of an antichain is less than or equal to the
size of a chain cover proved by induction"
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(sec.heading_spans, [(3, 4)])
        counts, _ = cli._scan_methods([sec])
        self.assertNotIn("induction", counts)

    def test_a_heading_keyword_inside_prose_is_not_a_heading(self):
        # Monad_Memo_DP/example/Bellman_Ford.thy:246 — an English sentence
        # citing a textbook chapter.  Accepting the quoted form made this
        # reachable, and unguarded it put a phantom `chapter` in `outline`.
        # A command cannot start inside a document block; that is the rule.
        sec = section_from(HEAD + r'''
text \<open>
  The proof follows Kleinberg and Tardos: "Algorithm Design",
  chapter "Dynamic Programming".
\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(sec.heading_spans, [])
        self.assertEqual(parsing.extract_sections(
            sec.source(), sec.text_blocks), [])

    def test_an_unbalanced_quote_in_prose_cannot_mask_live_code(self):
        # The reason the guard is not cosmetic.  With an *odd* number of quotes
        # the title scan runs to the next quote anywhere in the file — here
        # that is the lemma's own statement, three lines down.
        sec = section_from(HEAD + r'''
text \<open>
  See Kleinberg's chapter "Dynamic Programming
\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(sec.heading_spans, [])
        counts, _ = cli._scan_methods([sec])
        self.assertEqual(counts.get("simp"), 1)


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
