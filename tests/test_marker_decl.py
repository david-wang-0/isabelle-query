r"""A document marker is a formal comment, not part of the command or the name.

`\<^marker>\<open>tag important\<close>` is Isabelle's document-tagging marker.
Its body is not Isar, and the marker may be written glued to the command
keyword, in the name slot, or immediately after the name.  Three consequences,
all of them wrong before this:

  * `definition\<^marker>\<open>tag important\<close> istopology :: "..."` matched
    NOTHING.  `DECL_RE` requires `(?=\s|$)` after the keyword, and the
    custom-command path reads the lead token as `definition\<^marker>`, which is
    in no keyword table.  The whole declaration disappeared — 509 of them in
    `HOL/Analysis` alone, which tags its definitions for the document build
    throughout;
  * with a space before the marker the name parser captured the MARKER as the
    name (`\<^marker>\<open>tag`), because `\<...>` runs count as name
    characters;
  * with the marker after the name it captured `coprod_final_sink\<^marker>` —
    the name run continued straight through it.

The fix is one fact stated twice.  Isabelle's lexer treats `\<comment>`,
`\<^cancel>`, `\<^latex>` and `\<^marker>` alike: each owns the cartouche that
follows it, and none of it is live source.  So the tokenizer redacts all four
(it already redacted the first two), which makes the marker invisible to every
scanner reading the outer view; and the name grammar stops treating a
STRUCTURAL symbol as a name character, which is the same statement at the
lexical level.

It is not only an entry-set loss.  `shape`'s classifier is seeded from
`sec.entries`, so in an affected theory a name that should be a `const`
classified as a `var`.

D2 and D6 in David Wang's Scala port's `dev/DIVERGENCES.md`; reproduced by
`scripts/probe_scala_port_findings.py`.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import names, section_from  # noqa: E402
from isabelle_query import parsing  # noqa: E402
from isabelle_query.commands import _user_pattern  # noqa: E402

# Every spelling below is a real one.  `definition\<^marker>` and
# `subsection\<^marker>` are Bounded_Functions / Markov_Models; the nested
# `contributor \<open>...\<close>` body is HOL-Library; `lipschitzI_on\<^marker>`
# is Interval_Analysis.
MARKED = r"""theory Marked
imports Main
begin

subsection\<^marker>\<open>tag unimportant\<close> \<open>Supremum Norm\<close>

definition\<^marker>\<open>tag important\<close> istopology :: "bool \<Rightarrow> bool"
  where "istopology x = x"

lemma \<^marker>\<open>tag important\<close> sets_final_sink': "True" by simp

lemma \<^marker>\<open>tag important\<close>sets_Plus_algebra: "True" by simp

lemma \<^marker>\<open>contributor \<open>Martin Desharnais\<close>\<close> desharnais: "True"
  by simp

definition lipschitzI_on\<^marker>\<open>tag important\<close> :: "bool"
  where "lipschitzI_on = True"

datatype\<^marker>\<open>tag unimportant\<close> colour = Red | Green

end
"""

# The guard: the two markers that were ALREADY redacted, and names that
# legitimately carry markup.  Narrowing the name grammar must not narrow it
# past `\<^sub>`, and adding markers to the tokenizer must not disturb notes.
UNMARKED = r"""theory Plain
imports Main
begin

definition split\<^sub>i_tree :: "bool" where "split\<^sub>i_tree = True"

definition \<phi>step :: "bool" where "\<phi>step = True"

lemma annotated: "True" \<comment> \<open>a marginal note\<close>
  by simp

lemma cancelled: "True" \<^cancel>\<open>lemma ghost: "False"\<close>
  by simp

end
"""


class MarkerGluedToKeyword(unittest.TestCase):
    r"""`definition\<^marker>\<open>...\<close> name` — the declaration itself."""

    def setUp(self):
        self.sec = section_from(MARKED, "Marked")
        self.names = names(self.sec)

    def test_the_declaration_is_found_at_all(self):
        self.assertIn("istopology", self.names)

    def test_a_marked_datatype_is_found(self):
        self.assertIn("colour", self.names)

    def test_the_entry_starts_on_its_own_line(self):
        e = next(e for e in self.sec.entries if e.name == "istopology")
        self.assertEqual(e.thy_line, 7)
        self.assertEqual(e.tag, "DEF")


class MarkerInTheNameSlot(unittest.TestCase):
    r"""`lemma \<^marker>\<open>...\<close> name:` — the marker is not the name."""

    def setUp(self):
        self.names = names(section_from(MARKED, "Marked"))

    def test_the_name_after_the_marker_is_taken(self):
        self.assertIn("sets_final_sink'", self.names)

    def test_no_space_is_needed_after_the_close(self):
        self.assertIn("sets_Plus_algebra", self.names)

    def test_a_nested_cartouche_body_is_stepped_over(self):
        # `\<^marker>\<open>contributor \<open>Martin Desharnais\<close>\<close>`
        # — the body itself holds a cartouche, so skipping it needs balancing,
        # not a search for the first `\<close>`.
        self.assertIn("desharnais", self.names)

    def test_no_marker_is_indexed_as_a_name(self):
        self.assertEqual([n for n in self.names if "marker" in n], [])


class MarkerAfterTheName(unittest.TestCase):
    r"""`definition lipschitzI_on\<^marker>\<open>...\<close> :: "..."` (D6)."""

    def setUp(self):
        self.names = names(section_from(MARKED, "Marked"))

    def test_the_name_stops_at_the_marker(self):
        self.assertIn("lipschitzI_on", self.names)

    def test_every_declaration_is_found_exactly_once(self):
        self.assertEqual(self.names,
                         ["istopology", "sets_final_sink'", "sets_Plus_algebra",
                          "desharnais", "lipschitzI_on", "colour"])


class MarkerBodyIsNotLiveSource(unittest.TestCase):
    """A marker's body is prose, so nothing in it is a citable name."""

    def setUp(self):
        self.sec = section_from(MARKED, "Marked")
        self.live = "\n".join(self.sec.live_source())

    def test_the_body_is_blanked(self):
        self.assertNotIn("Desharnais", self.live)
        self.assertNotIn("contributor", self.live)

    def test_the_tag_word_is_blanked(self):
        # `tag` and `important` would otherwise read as citations of any entry
        # spelled that way — 345 AFP files carry markers.
        self.assertNotIn("important", self.live)

    def test_the_marker_is_not_recorded_as_a_note(self):
        # `note_starts` is where a GENUINE `\<comment>` opens.  A marker is
        # redacted like one but annotates the document build, not the reader,
        # so `_attach_annotations` must not charge its body to an entry.
        _spans, notes, _inner, _open = parsing.scan_regions(
            MARKED.splitlines(), want_inner=True)
        self.assertEqual(notes, {})


class MarkedHeading(unittest.TestCase):
    r"""`subsection\<^marker>\<open>...\<close> \<open>Title\<close>` is a heading.

    The second recognition site, and it needs its own fix: `_heading_at` reads
    the RAW line, because a heading's title *is* a cartouche — the outer view
    that blanks the marker blanks the title with it.
    """

    def test_the_heading_is_in_the_outline(self):
        found = parsing.extract_sections(MARKED.splitlines())
        self.assertIn(("subsection", "Supremum Norm", 5), found)


class TheHeadingFormsStillWork(unittest.TestCase):
    """Guard: passes before and after.  The recogniser was restructured from
    one regex into keyword + marker-skip + title, so every form it already
    accepted has to keep working — and every non-heading keep failing."""

    def sections(self, text):
        return parsing.extract_sections(text.splitlines())

    def test_the_plain_form(self):
        self.assertEqual(self.sections("section \\<open>Plain\\<close>"),
                         [("section", "Plain", 1)])

    def test_the_quoted_title_form(self):
        # 3,980 AFP headings, e.g. `section "Preliminary lemmas"`.
        self.assertEqual(self.sections('subsection "Quoted"'),
                         [("subsection", "Quoted", 1)])

    def test_the_unicode_cartouche_form(self):
        self.assertEqual(self.sections("chapter ‹Uni›"),
                         [("chapter", "Uni", 1)])

    def test_no_space_before_the_opener(self):
        self.assertEqual(self.sections("paragraph\\<open>Tight\\<close>"),
                         [("paragraph", "Tight", 1)])

    def test_an_indented_heading(self):
        self.assertEqual(self.sections("   subsubsection \\<open>In\\<close>"),
                         [("subsubsection", "In", 1)])

    def test_the_split_form(self):
        # The command alone on its line, its title on the next.
        self.assertEqual(self.sections("section\n  \\<open>Split\\<close>"),
                         [("section", "Split", 1)])

    def test_a_longer_word_does_not_lead_with_a_heading(self):
        # `sections \<open>...\<close>` is not a `section`.  `SECTION_RE` got
        # this implicitly by demanding an opener straight after the word; the
        # split recogniser has to say it.
        self.assertEqual(self.sections("sections \\<open>No\\<close>"), [])

    def test_a_bare_word_with_no_title_is_not_a_heading(self):
        self.assertEqual(self.sections("section\nlemma foo: \"True\""), [])


class TheAlreadyRedactedMarkersStillWork(unittest.TestCase):
    """Guard: passes before and after.  Two markers were already handled."""

    def setUp(self):
        self.sec = section_from(UNMARKED, "Plain")
        self.names = names(self.sec)

    def test_a_comment_note_is_still_recorded(self):
        _spans, notes, _inner, _open = parsing.scan_regions(
            UNMARKED.splitlines(), want_inner=True)
        self.assertTrue(notes, "the `\\<comment>` note stopped being recorded")

    def test_cancelled_text_declares_nothing(self):
        self.assertNotIn("ghost", self.names)

    def test_every_declaration_is_found(self):
        self.assertEqual(self.names,
                         ["split\\<^sub>i_tree", "\\<phi>step",
                          "annotated", "cancelled"])


class MarkupNamesStillWork(unittest.TestCase):
    """Guard: a name may carry markup — narrowing must stop at STRUCTURE."""

    def setUp(self):
        self.names = names(section_from(UNMARKED, "Plain"))

    def test_a_subscripted_name_is_one_name(self):
        self.assertIn("split\\<^sub>i_tree", self.names)

    def test_a_greek_leading_name_is_one_name(self):
        self.assertIn("\\<phi>step", self.names)

    def test_a_user_pattern_still_escapes_every_markup_token(self):
        # `_user_pattern` escapes `\<...>` spans so a printed name can be
        # pasted back as a search.  That is a LEXICAL question — "is this a
        # markup token" — and must keep answering yes for the structural
        # symbols the NAME grammar now refuses.
        self.assertEqual(_user_pattern(r"\<open>"), re.escape(r"\<open>"))
        self.assertEqual(_user_pattern(r"a\<^sub>1"),
                         "a" + re.escape(r"\<^sub>") + "1")


if __name__ == "__main__":
    unittest.main()
