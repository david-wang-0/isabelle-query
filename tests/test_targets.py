r"""`Entry.blocks` / `Entry.in_target` — which locale does this declaration
belong to?

Isar makes the question unusually cheap.  Every *target* block — `locale`,
`class`, `context`, `instantiation`, `overloading`, `bundle`, `experiment`,
`notepad`, and the theory itself — is opened by the token `begin` and closed by
`end`, whatever command introduced it.  There is no opener→closer table: ONE
pair, counted at outer-syntax position.

A `begin` does not name itself and the opening command may sit lines above it,
so the rule is: remember the most recent target-opening command; the next
`begin` consumes it.  Measured at 4,003/4,003 blocks attributed over 120 AFP
entries (`scripts/probe_locale_naming.py`), and cross-checked against Isabelle's
own qualified names via `export_theory`, where teaching the comparison about
targets closed 41 of 45 residual disagreements
(`scripts/probe_export_oracle.py`).

Two kinds of evidence, deliberately kept apart: lexical nesting says where the
text sits, `(in foo)` says where the declaration *goes* regardless of where it
sits, and Isabelle lets them disagree (31 entries over the corpus do).
`Entry.target` resolves that — the modifier wins, because it retargets.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402


def entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


class LexicalNesting(unittest.TestCase):

    def test_lemma_inside_a_locale_records_it(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'locale foo =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'lemma outside: "True" by simp\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").blocks, (("locale", "foo"),))
        self.assertEqual(entry(sec, "inside").target, "foo")

    def test_after_the_end_the_target_is_gone(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'locale foo =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'lemma outside: "True" by simp\n'
            'end\n')
        self.assertEqual(entry(sec, "outside").blocks, ())
        self.assertEqual(entry(sec, "outside").target, "")

    def test_the_theory_block_is_not_reported(self):
        # Every entry is inside the theory's own begin/end, so naming it would
        # be noise on every single line.
        sec = section_from(
            'theory T imports Main begin\n'
            'lemma plain: "True" by simp\n'
            'end\n')
        self.assertEqual(entry(sec, "plain").blocks, ())

    def test_context_reopening_a_locale(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'context hpk\n'
            'begin\n'
            'definition K0 :: nat where "K0 = 0"\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "K0").blocks, (("context", "hpk"),))

    def test_anonymous_context_nests_but_is_not_named(self):
        # `context fixes x begin` opens a real block with no name.  It must
        # still nest (or the matching `end` would pop the locale), but there is
        # nothing to report.
        sec = section_from(
            'theory T imports Main begin\n'
            'locale foo =\n'
            '  fixes y :: nat\n'
            'begin\n'
            'context fixes x :: nat\n'
            'begin\n'
            'lemma deep: "True" by simp\n'
            'end\n'
            'lemma shallow: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "deep").blocks, (("locale", "foo"),))
        # The anonymous block's `end` popped IT, not the locale.
        self.assertEqual(entry(sec, "shallow").blocks, (("locale", "foo"),))

    def test_an_unrecognised_opener_still_nests(self):
        # AutoCorres2 declares `keywords "if_architecture_context" ::
        # thy_decl_block` — a custom command that opens a block.  The push
        # happens on the `begin` token whether or not the opener was
        # recognised, so an unknown one costs only its name: `deep` is still
        # in the locale, and — the part that matters — the custom block's own
        # `end` pops IT rather than the locale, so `shallow` is too.
        sec = section_from(
            'theory T imports Main begin\n'
            'locale foo =\n'
            '  fixes y :: nat\n'
            'begin\n'
            'if_architecture_context (ARM)\n'
            'begin\n'
            'lemma deep: "True" by simp\n'
            'end\n'
            'lemma shallow: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "deep").blocks, (("locale", "foo"),))
        self.assertEqual(entry(sec, "shallow").blocks, (("locale", "foo"),))

    def test_a_declared_but_never_opened_locale_is_not_inherited(self):
        # `locale A = ...` with no `begin` opens nothing.  The next opener must
        # replace it, or `notepad`'s block would be attributed to A.
        sec = section_from(
            'theory T imports Main begin\n'
            'locale A = fixes x :: nat\n'
            'locale B = fixes y :: nat\n'
            'begin\n'
            'lemma here: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "here").blocks, (("locale", "B"),))

    def test_class_and_instantiation_are_targets(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'instantiation nat :: tape begin\n'
            'definition tape_of_nat where "tape_of_nat n = n"\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "tape_of_nat").blocks,
                         (("instantiation", "nat"),))

    def test_two_openers_and_two_begins_on_one_line(self):
        # `Big_Step_Sterm` writes exactly this.  A line-granular scan would
        # attribute the second block to the first opener; the scan is
        # positional for this reason.
        sec = section_from(
            'theory T imports Main begin\n'
            'context srules begin context begin\n'
            'lemma deep: "True" by simp\n'
            'end end\n'
            'end\n')
        self.assertEqual(entry(sec, "deep").blocks, (("context", "srules"),))

    def test_an_indented_block_is_still_a_block(self):
        # Isar attaches no meaning to indentation, so neither does this.
        sec = section_from(
            'theory T imports Main begin\n'
            '  locale foo =\n'
            '    fixes x :: nat\n'
            '  begin\n'
            '    lemma inside: "True" by simp\n'
            '  end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").blocks, (("locale", "foo"),))


class ExplicitTarget(unittest.TestCase):

    def test_in_modifier_is_recorded(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'lemma (in foo) retargeted: "True" by simp\n'
            'end\n')
        e = entry(sec, "retargeted")
        self.assertEqual(e.in_target, "foo")
        self.assertEqual(e.blocks, ())
        self.assertEqual(e.target, "foo")

    def test_in_modifier_beats_lexical_nesting(self):
        # Isabelle retargets: a `lemma (in bar)` written inside `locale foo`
        # belongs to `bar`.  Both facts are kept; `target` picks the modifier.
        sec = section_from(
            'theory T imports Main begin\n'
            'locale foo =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma (in bar) elsewhere: "True" by simp\n'
            'end\n'
            'end\n')
        e = entry(sec, "elsewhere")
        self.assertEqual(e.blocks, (("locale", "foo"),))
        self.assertEqual(e.in_target, "bar")
        self.assertEqual(e.target, "bar")

    def test_a_term_mentioning_in_is_not_a_target(self):
        # The scan reads the outer view, so `(in ...)` inside a statement is
        # blanked before it can be mistaken for a modifier.
        sec = section_from(
            'theory T imports Main begin\n'
            'lemma quoted: "f (in_set xs) = (in_set xs)" by simp\n'
            'end\n')
        self.assertEqual(entry(sec, "quoted").in_target, "")


class Rendering(unittest.TestCase):

    def test_enclosing_names_the_locale(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'locale foo =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        out = self._enclosing(sec, "Test:5")
        self.assertIn("locale foo", out)
        self.assertIn("inside", out)

    def test_theory_level_entry_gets_no_scope_step(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'lemma plain: "True" by simp\n'
            'end\n')
        self.assertNotIn("▸", self._enclosing(sec, "Test:2"))

    def _enclosing(self, sec, locus):
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            cli.cmd_enclosing([sec], [locus])
        return buf.getvalue()


class TargetNameSpellings(unittest.TestCase):
    r"""A target's name is spelled like any other Isabelle name.

    The reader used to be `[A-Za-z_][A-Za-z_0-9'.]*` against the OUTER view,
    which fails two ways that look nothing alike.  A markup symbol ends the
    match early, so `locale split\<^sub>i_tree` was indexed as `split` — not a
    missing name but a WRONG one, and six locales in `BTree/BPlusTree_ImpSplit`
    collapsed onto that single string.  A quoted name (`locale "functor" =`) is
    inner syntax, which outer blanks entirely, so it read as no name at all.

    Over 120 AFP entries the fix renames 27 entries (18 truncated, 9 unnamed)
    and adds nothing, removes nothing and moves no span; five theories had one
    string standing for several distinct locales.  See
    `scripts/probe_target_names.py`, which reports 0 recoverable misses.
    """

    def test_a_markup_symbol_does_not_end_the_name(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'locale split\\<^sub>i_tree =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "split\\<^sub>i_tree")

    def test_two_locales_differing_only_in_markup_stay_distinct(self):
        # The collision is the real cost of truncation: `BPlusTree_ImpSplit`
        # had six locales indexed as `split`, so a name lookup could not tell
        # them apart and `enclosing` named the wrong one five times in six.
        sec = section_from(
            'theory T imports Main begin\n'
            'locale split\\<^sub>i_tree =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma one: "True" by simp\n'
            'end\n'
            'locale split\\<^sub>i_list =\n'
            '  fixes y :: nat\n'
            'begin\n'
            'lemma two: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "one").target, "split\\<^sub>i_tree")
        self.assertEqual(entry(sec, "two").target, "split\\<^sub>i_list")
        self.assertEqual(
            {e.name for e in sec.entries if e.tag == "LOCALE"},
            {"split\\<^sub>i_tree", "split\\<^sub>i_list"})

    def test_a_name_that_is_only_a_symbol(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'locale \\<Z> =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "\\<Z>")
        self.assertEqual(entry(sec, "\\<Z>").tag, "LOCALE")

    def test_a_quoted_locale_name(self):
        # `functor` is a keyword-ish word an author must quote; outer blanks
        # the quotes, so this name is readable only from the live view.
        sec = section_from(
            'theory T imports Main begin\n'
            'locale "functor" =\n'
            '  fixes x :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "functor")

    def test_a_quoted_instantiation_type(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'instantiation "pseqp" :: ord\n'
            'begin\n'
            'definition foo :: nat where "foo = 0"\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "foo").target, "pseqp")

    def test_a_symbol_instantiation_type(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'instantiation \\<o> :: AOT_subst\n'
            'begin\n'
            'definition foo :: nat where "foo = 0"\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "foo").target, "\\<o>")

    def test_a_qualified_target_keeps_its_dot(self):
        # `context Rings.dvd begin` — rare (3 over 120 AFP entries) but the
        # reason the new grammar keeps `.`, which `SYM_NAME_RE` does not have.
        sec = section_from(
            'theory T imports Main begin\n'
            'context Rings.dvd begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "Rings.dvd")


class TargetsThatCorrectlyHaveNoName(unittest.TestCase):
    """Reading the name from the live view must not INVENT one.

    Live keeps terms and cartouches, so a reader pointed at it can pick up
    text that outer hid for good reason.  These pin the cases where '' is the
    right answer — 430 of them over 120 AFP entries, against 0 misses.
    """

    def test_a_bare_context_stays_anonymous(self):
        # `context` alone opens an anonymous context whose elements follow.
        # All 288 bare `context` openers over 120 AFP entries are followed by
        # an element or `begin`, never by a name, so there is no lookahead.
        sec = section_from(
            'theory T imports Main begin\n'
            'context\n'
            '  fixes h :: nat\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "")
        self.assertEqual(entry(sec, "inside").blocks, ())

    def test_a_context_element_is_not_a_name(self):
        sec = section_from(
            'theory T imports Main begin\n'
            'context fixes h :: nat begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "")

    def test_a_cartouche_is_not_a_name(self):
        # A cartouche survives the live view; the symbol alternation would
        # match `\<open>` happily, so the reserved-prefix guard has to hold.
        sec = section_from(
            'theory T imports Main begin\n'
            'context \\<open>bogus\\<close>\n'
            'begin\n'
            'lemma inside: "True" by simp\n'
            'end\n'
            'end\n')
        self.assertEqual(entry(sec, "inside").target, "")


if __name__ == "__main__":
    unittest.main()
