r"""`Entry.annotations` — every marginal note in an entry's span, tagged.

A `\<comment> \<open>...\<close>` note is the author's prose about an entry, and
an entry is three parts in source order — declaration line, statement, proof —
so WHERE the note sits says which part it is about.  That is the whole tagging
rule: line arithmetic against `thy_line` and `proof_line`, no text inspection.

Only `proof` notes used to be attached, under the name `roadmap`.  Over 120 AFP
entries that kept 497 of 3,912 notes (12.7%) and discarded the rest; the tagged
rule keeps 2,901 (74.2%).  The single worst case was the `definition` family:
having no proof, a definition could never contribute a note, so `show --comments
-only` on one printed nothing at all — while a definition's marginal notes are
exactly where its construction gets narrated.

`Entry.roadmap` survives as a derived view (the `proof`-tagged subset), so the
widening added data without moving any.  Verified at corpus scale by
`scripts/probe_annotations.py`, which recomputes the old rule from scratch and
diffs it: 0 drift over 1,662 theories.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

NOTE = r'\<comment> \<open>{}\<close>'


def entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


def tagged(sec, name, kind=None):
    """[(line, content)] for `name`'s annotations, optionally one kind only."""
    return [(ln, c) for ln, c, k in entry(sec, name).annotations
            if kind is None or k == kind]


class DefinitionBodies(unittest.TestCase):
    r"""The case that motivated widening: a definition has no proof."""

    def test_notes_in_a_definition_body_attach(self):
        # `Shuffle:54` — a `do { ... }` definition narrated round by round.
        sec = section_from('theory A imports Main begin\n'
                           'definition f :: "nat \\<Rightarrow> nat" where\n'
                           '  "f x = (let a = x  ' + NOTE.format("1st round") + '\n'
                           '          in a + 1)"  ' + NOTE.format("2nd round") + '\n'
                           'end\n')
        self.assertEqual(tagged(sec, "f"),
                         [(3, "1st round"), (4, "2nd round")])

    def test_a_definitions_notes_are_tagged_statement(self):
        # A definition is all statement: there is no proof for a note to be
        # about, so everything below the declaration line is `statement`.
        sec = section_from('theory A imports Main begin\n'
                           'definition f :: "nat \\<Rightarrow> nat" where\n'
                           '  "f x = x"  ' + NOTE.format("identity") + '\n'
                           'end\n')
        self.assertEqual([k for _, _, k in entry(sec, "f").annotations],
                         ["statement"])

    def test_a_definition_contributes_no_roadmap(self):
        # It has no derivation, so the roadmap view stays empty — the prose is
        # reachable, but not by claiming it narrates a proof.
        sec = section_from('theory A imports Main begin\n'
                           'definition f :: "nat \\<Rightarrow> nat" where\n'
                           '  "f x = x"  ' + NOTE.format("identity") + '\n'
                           'end\n')
        self.assertEqual(entry(sec, "f").roadmap, [])


class Statements(unittest.TestCase):
    """A note above the proof annotates WHAT is proved, not how."""

    def test_assumption_glosses_attach_as_statement(self):
        # `Lifschitz_Consistency:100` glosses every assumption in turn.
        sec = section_from('theory A imports Main begin\n'
                           'theorem foo:\n'
                           '  assumes a: "True"  ' + NOTE.format("sound system") + '\n'
                           '  assumes b: "True"  ' + NOTE.format("and a plan") + '\n'
                           '  shows "True"\n'
                           '  by simp\n'
                           'end\n')
        self.assertEqual(tagged(sec, "foo", "statement"),
                         [(3, "sound system"), (4, "and a plan")])

    def test_a_statement_note_is_not_in_the_roadmap(self):
        sec = section_from('theory A imports Main begin\n'
                           'theorem foo:\n'
                           '  assumes a: "True"  ' + NOTE.format("sound system") + '\n'
                           '  shows "True"\n'
                           '  by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").roadmap, [])


class Proofs(unittest.TestCase):
    """The historical roadmap, unchanged."""

    def test_a_note_in_the_proof_body_is_tagged_proof(self):
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True"\n'
                           'proof -\n'
                           '  show "True" by simp  ' + NOTE.format("the work") + '\n'
                           'qed\n'
                           'end\n')
        self.assertEqual(tagged(sec, "foo", "proof"), [(4, "the work")])
        self.assertEqual(entry(sec, "foo").roadmap, [(4, "the work")])

    def test_roadmap_is_exactly_the_proof_subset(self):
        sec = section_from('theory A imports Main begin\n'
                           'theorem foo:\n'
                           '  assumes a: "True"  ' + NOTE.format("what") + '\n'
                           '  shows "True"\n'
                           'proof -\n'
                           '  show "True" by simp  ' + NOTE.format("how") + '\n'
                           'qed\n'
                           'end\n')
        e = entry(sec, "foo")
        self.assertEqual([k for _, _, k in e.annotations],
                         ["statement", "proof"])
        self.assertEqual(e.roadmap, [(6, "how")])

    def test_annotations_are_in_source_order(self):
        sec = section_from('theory A imports Main begin\n'
                           'theorem foo:  ' + NOTE.format("one") + '\n'
                           '  shows "True"  ' + NOTE.format("two") + '\n'
                           '  by simp  ' + NOTE.format("three") + '\n'
                           'end\n')
        self.assertEqual([c for _, c, _ in entry(sec, "foo").annotations],
                         ["one", "two", "three"])


class OneLinerOrdering(unittest.TestCase):
    r"""The `proof` test must come BEFORE the `decl` test.

    `lemma foo: "P" by simp` is the commonest fact shape in the AFP, and its
    declaration line IS its proof line.  Tagging `decl` first would retag every
    such note and empty the roadmap of exactly the shape that needs it — a
    silent loss, since `annotations` would still hold the note and only the
    derived `roadmap` view would go quiet.

    Measured: of the 65 notes sitting on a declaration line over 120 AFP
    entries, one is this shape, and it stays in the roadmap (497, not 496).
    """

    def test_a_one_line_proof_note_is_a_proof_note(self):
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True" by simp  ' + NOTE.format("quick") + '\n'
                           'end\n')
        e = entry(sec, "foo")
        self.assertEqual(e.thy_line, e.proof_line)   # the shape under test
        self.assertEqual([k for _, _, k in e.annotations], ["proof"])
        self.assertEqual(e.roadmap, [(2, "quick")])

    def test_a_decl_note_with_no_proof_is_a_decl_note(self):
        # `Merkle_Interface` — `type_synonym` glossed on its own line.  No
        # proof_line, so nothing preempts the `decl` test.
        sec = section_from('theory A imports Main begin\n'
                           'type_synonym hash = nat  ' + NOTE.format("hashes") + '\n'
                           'end\n')
        e = entry(sec, "hash")
        self.assertEqual(e.proof_line, 0)            # the shape under test
        self.assertEqual([k for _, _, k in e.annotations], ["decl"])


class Unowned(unittest.TestCase):
    """What is still charged to no entry, and why that is deliberate."""

    def test_a_note_above_the_first_declaration_attaches_to_nothing(self):
        # Theory-level prose: it is about the theory, not about the lemma that
        # happens to follow it.  21.3% of AFP notes.
        sec = section_from('theory A imports Main begin\n'
                           'declare foo [simp]  ' + NOTE.format("theory-level") + '\n'
                           'lemma bar: "True" by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "bar").annotations, [])

    def test_a_note_in_a_commented_out_block_attaches_to_nothing(self):
        r"""A `\<comment>` inside `(* ... *)` annotates deleted text.

        Text alone cannot tell it from a live note — they are spelled
        identically — so this leans on the tokenizer's `notes` set, which knows
        the scanner was in `comment` state when it went past.
        """
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True"\n'
                           '  by simp\n'
                           '(* an old attempt:\n'
                           '   by auto  ' + NOTE.format("dead prose") + '\n'
                           '*)\n'
                           'end\n')
        self.assertEqual([c for _, c, _ in entry(sec, "foo").annotations], [])


class NoteContent(unittest.TestCase):
    r"""Cartouches nest, and a note's content has to survive that.

    6,014 of the AFP's 21,683 notes (27.7%) contain a nested cartouche, because
    glossing a statement means naming the term it is about.  Cutting at the
    first `\<close>` truncated every one of them mid-sentence.
    """

    def content(self, line):
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True" by simp  ' + line + '\n'
                           'end\n')
        return entry(sec, "foo").annotations[0][1]

    def test_a_nested_cartouche_is_kept_whole(self):
        # `Lifschitz_Consistency:109` — cut at the first close this read
        # "We have that \<open>f(as)", losing the predicate of the sentence.
        self.assertEqual(
            self.content(r'\<comment> \<open>We have that '
                         r'\<open>f(as)\<close> is applicable\<close>'),
            r"We have that \<open>f(as)\<close> is applicable")

    def test_a_flat_note_is_unchanged(self):
        self.assertEqual(self.content(NOTE.format("plain prose")),
                         "plain prose")

    def test_two_nested_cartouches_in_a_row(self):
        self.assertEqual(
            self.content(r'\<comment> \<open>\<open>a\<close> and '
                         r'\<open>b\<close>\<close>'),
            r"\<open>a\<close> and \<open>b\<close>")

    def test_a_note_running_past_its_line_takes_the_rest(self):
        # No matching close on this line, so there is nothing to cut at.
        self.assertEqual(self.content(r'\<comment> \<open>starts here'),
                         "starts here")

    def test_the_unicode_cartouche_spelling_is_extracted(self):
        r"""The tokenizer accepts `‹`, so the extractor must too.

        The AFP normalises to the ASCII spelling (0 occurrences of
        `\<comment> ‹` in the whole corpus), so this costs nothing there — but
        a note the tokenizer recognises and the extractor drops is a silent
        hole, and `query` reads working trees, not just the AFP.
        """
        self.assertEqual(self.content('\\<comment> ‹hand-written›'),
                         "hand-written")


class ProseView(unittest.TestCase):
    """`show --comments-only` is the prose view; it must show the prose."""

    def test_a_definition_has_a_prose_view_at_all(self):
        # Before tagging, this printed "(no comment context for this entry)":
        # a definition has no proof, so it could never have a roadmap.
        sec = section_from('theory A imports Main begin\n'
                           'definition f :: "nat \\<Rightarrow> nat" where\n'
                           '  "f x = x"  ' + NOTE.format("identity") + '\n'
                           'end\n')
        out = cli.render_entry(sec, entry(sec, "f"), comments="only")
        self.assertIn("identity", out)
        self.assertNotIn("no comment context", out)

    def test_kinds_are_grouped_and_labelled(self):
        sec = section_from('theory A imports Main begin\n'
                           'theorem foo:\n'
                           '  assumes a: "True"  ' + NOTE.format("what") + '\n'
                           '  shows "True"\n'
                           'proof -\n'
                           '  show "True" by simp  ' + NOTE.format("how") + '\n'
                           'qed\n'
                           'end\n')
        out = cli.render_entry(sec, entry(sec, "foo"), comments="only")
        self.assertLess(out.index("statement:"), out.index("what"))
        self.assertLess(out.index("what"), out.index("proof:"))
        self.assertLess(out.index("proof:"), out.index("how"))

    def test_the_default_view_does_not_repeat_a_visible_note(self):
        """A definition's body is printed in full, notes included.

        Previewing them underneath would print the same prose twice, so the
        preview is limited to notes past `decl_end_line` — the ones the slice
        does not already show.
        """
        sec = section_from('theory A imports Main begin\n'
                           'definition f :: "nat \\<Rightarrow> nat" where\n'
                           '  "f x = x"  ' + NOTE.format("identity") + '\n'
                           'end\n')
        out = cli.render_entry(sec, entry(sec, "f"), comments="on")
        self.assertEqual(out.count("identity"), 1)

    def test_comments_off_still_suppresses_annotations(self):
        sec = section_from('theory A imports Main begin\n'
                           'theorem foo:\n'
                           '  shows "True"\n'
                           'proof -\n'
                           '  show "True" by simp  ' + NOTE.format("how") + '\n'
                           'qed\n'
                           'end\n')
        out = cli.render_entry(sec, entry(sec, "foo"), comments="off")
        self.assertNotIn("| line", out)


if __name__ == "__main__":
    unittest.main()
