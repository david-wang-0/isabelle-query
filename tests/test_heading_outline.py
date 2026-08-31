r"""`outline` shows every heading, in every spelling [heading-outline].

Two patterns used to answer "is this line a heading". One fed the `outline`
view and was written tight — anchored at column 0, a space required before the
cartouche, ASCII spelling only. `_HEADING_OPEN_RE` fed the prose mask and was
written wide, because a mask that misses prose corrupts every scan downstream
while a view that shows a junk heading merely looks untidy.  Today there is one
recogniser, `_heading_at`.

Both instincts are right in isolation and the conclusion was wrong: a heading is
a heading, so the recogniser is a fact about Isar, not about the consumer. The
disagreement cost `outline` **14,238 of the AFP's 40,726 headings** — 35%,
silently. Dilworth writes every heading as `section "..."`, so its outline had
no structure in it at all: 0 headings before, 10 after.

There is now one recogniser, `parsing._heading_at`, and these tests pin both
that it sees each spelling and that the two consumers agree — the second being
the property whose absence caused this.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402

from isabelle_query import parsing  # noqa: E402

HEAD = "theory T imports Main begin\n"
FOOT = "\nend\n"

# One of every form that occurs: the six commands from Isabelle's keyword
# table, both cartouche spellings, the quoted string, indentation, no space
# before the opener, and the split opener.
ALL_SPELLINGS = HEAD + r'''
chapter \<open>Spaced ASCII cartouche\<close>
section\<open>No space before the cartouche\<close>
subsection ‹Unicode cartouche›
  subsubsection \<open>Indented\<close>
paragraph "Quoted string"
subparagraph
  \<open>Split opener\<close>
lemma m: "True" by simp
''' + FOOT


class OutlineSeesEverySpelling(unittest.TestCase):

    def setUp(self):
        self.sec = section_from(ALL_SPELLINGS)
        self.found = parsing.extract_sections(self.sec.source(),
                                              self.sec.text_blocks)

    def test_every_spelling_is_found(self):
        self.assertEqual(len(self.found), 6, f"found: {self.found}")

    def test_the_command_word_is_reported(self):
        self.assertEqual([lvl for lvl, _t, _n in self.found],
                         ["chapter", "section", "subsection", "subsubsection",
                          "paragraph", "subparagraph"])

    def test_the_title_is_clean_of_its_delimiters(self):
        # Each opener has its own closer, so the title must not keep a stray
        # `\<close>`, `›` or `"` — three closers, one extraction.
        self.assertEqual([t for _lvl, t, _n in self.found],
                         ["Spaced ASCII cartouche",
                          "No space before the cartouche",
                          "Unicode cartouche", "Indented", "Quoted string",
                          "Split opener"])

    def test_the_split_opener_is_reported_at_its_command_word(self):
        # Not at the title line: the locus a user pastes back should be the
        # command, which is what every other heading reports.
        _lvl, _title, line = self.found[-1]
        self.assertEqual(self.sec.source()[line - 1].strip(), "subparagraph")


class TheTwoConsumersAgree(unittest.TestCase):
    """The invariant whose absence was the bug.

    `outline` and the prose mask must find the *same* headings — anything one
    sees and the other does not is either a heading whose English is scanned as
    Isar, or a heading the user cannot see.
    """

    FIXTURES = (ALL_SPELLINGS,
                # a heading keyword inside prose: neither may claim it
                HEAD + 'text \\<open>\n  see chapter "Dynamic Programming"\n'
                       '\\<close>\nlemma m: "True" by simp\n' + FOOT,
                # wrapped titles of both kinds
                HEAD + 'section \\<open>One that\n  wraps\\<close>\n'
                       'section "Another that\nwraps"\n'
                       'lemma m: "True" by simp\n' + FOOT)

    def test_same_heading_lines_from_both(self):
        for n, src in enumerate(self.FIXTURES):
            with self.subTest(fixture=n):
                sec = section_from(src)
                view = {line for _lvl, _t, line in parsing.extract_sections(
                    sec.source(), sec.text_blocks)}
                mask = {start for start, _end in sec.heading_spans}
                self.assertEqual(view, mask)


class OutlineDoesNotInventHeadings(unittest.TestCase):

    def test_a_textbook_citation_in_prose_is_not_a_chapter(self):
        # Monad_Memo_DP/example/Bellman_Ford.thy:246, which `outline` reported
        # as `chapter: Dynamic Programming (line 246)` until the prose guard.
        sec = section_from(HEAD + r'''
text \<open>
  The correctness proof closely follows Kleinberg and Tardos:
  chapter "Dynamic Programming".
\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(
            parsing.extract_sections(sec.source(), sec.text_blocks), [])

    def test_the_dof_star_form_is_not_accepted(self):
        # `section*[label::type]\<open>...\<close>` is Isabelle_DOF's own
        # command, not base syntax — it is absent from `KEYWORDS`, so taking it
        # would be inventing a command rather than reading the table.  36 in
        # the AFP, all in Isabelle_DOF's manual.
        sec = section_from(HEAD + r'''
section*[morphisms::technical]\<open>Proofs over Ontologies\<close>
lemma m: "True" by simp
''' + FOOT)
        self.assertEqual(
            parsing.extract_sections(sec.source(), sec.text_blocks), [])


if __name__ == "__main__":
    unittest.main()
