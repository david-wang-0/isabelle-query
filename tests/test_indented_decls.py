r"""Declarations are recognised at command position, not at column 0.

Isar is whitespace-insensitive.  `DECL_RE` was anchored at column 0, which
answered "is this a command?" with "is it flush left?" — a different question,
and one real Isabelle answers differently all the time:

  * `Error_Monad_Add` indents its whole body inside the theory's own `begin`,
    with no locale anywhere.  53 lines, 14 declarations, 0 recognised.
  * the category-theory entries indent inside `locale`.  `BicategoryOfSpans` is
    14,655 lines and reported no entries at all.

Over 120 AFP entries the anchor hid 5,204 declarations (9.4% of the total) in
171 theories, 91 of which reported zero.  Moving recognition onto
`outer_source()` recovered all of them and lost NOTHING — the entry-set diff is
+5,204 / -0 (`scripts/dump_entries.py`, `scripts/audit_entry_diff.py`).

The anchor was also doing a second job, and this file pins that it survived:
it kept a declaration keyword written inside a term, a comment or an ML body
from minting a phantom entry.  `outer_source` blanks all three, so recognition
keeps the protection without the layout dependency.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402


def names(sec):
    return [e.name for e in sec.entries]


class Indented(unittest.TestCase):
    def test_inside_a_context_block(self):
        # `PDDL_STRIPS_Checker:137` — `context ast_domain begin`, everything
        # under it indented by two spaces.
        sec = section_from('theory A imports Main begin\n'
                           'context foo begin\n'
                           '  definition g where "g x = x"\n'
                           '  lemma bar: "g x = x" by (simp add: g_def)\n'
                           'end\n'
                           'end\n')
        self.assertEqual(names(sec), ["g", "bar"])

    def test_at_theory_top_level_with_no_locale(self):
        # `Error_Monad_Add` — plain author style, not locale structure.  This
        # is why a locale-only fix would have missed most of the gap.
        sec = section_from('theory A imports Main begin\n'
                           '  abbreviation "assert_opt P \\<equiv> P"\n'
                           '  lemma foo: "True" by simp\n'
                           'end\n')
        self.assertEqual(names(sec), ["assert_opt", "foo"])

    def test_deeply_indented(self):
        # Nesting depth reaches 5 in the AFP; indentation is not a signal at
        # any depth, so no amount of it should matter.
        sec = section_from('theory A imports Main begin\n'
                           'locale L begin\n'
                           'context begin\n'
                           '        lemma deep: "True" by simp\n'
                           'end\n'
                           'end\n'
                           'end\n')
        # `L` is an entry in its own right since `[declared-names]`: a locale
        # declares a name.  The anonymous `context begin` is not.
        self.assertEqual(names(sec), ["L", "deep"])

    def test_after_an_inline_comment_on_the_same_line(self):
        r"""`Alpha_Beta_Linear:1966` — `(*TODO: ...*)fun abtl :: ...`.

        Newly recognised, and not by design so much as by consequence: the
        comment is blanked in the outer view, so the command is at the start of
        what remains.  Column 0 held `(`.
        """
        sec = section_from('theory A imports Main begin\n'
                           '(* TODO: rename *)definition g where "g x = x"\n'
                           'end\n')
        self.assertEqual(names(sec), ["g"])

    def test_the_name_is_read_from_the_raw_line(self):
        r"""Recognition uses the outer view; extraction must not.

        A definition's name can live INSIDE its term
        (`definition "lift_opt m e \<equiv> ..."`), which the outer view blanks.
        Reading the name from there would yield nothing.
        """
        sec = section_from('theory A imports Main begin\n'
                           '  definition "lift_opt m e \\<equiv> m"\n'
                           'end\n')
        self.assertEqual(names(sec), ["lift_opt"])


class NotACommand(unittest.TestCase):
    """The job the anchor was really doing, kept."""

    def test_a_keyword_inside_a_term_is_not_a_declaration(self):
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "P x =\n'
                           '   lemma_like y"\n'
                           '  by simp\n'
                           'end\n')
        self.assertEqual(names(sec), ["foo"])

    def test_a_declaration_inside_a_comment_is_not_a_declaration(self):
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True" by simp\n'
                           '(* superseded:\n'
                           '   definition old where "old = 1"\n'
                           '*)\n'
                           'end\n')
        self.assertEqual(names(sec), ["foo"])

    def test_an_ml_fun_is_not_a_declaration(self):
        # `fun` is a keyword in both Isabelle and ML, and ML bodies are full
        # of it.  The ML cartouche is redacted, so it never reaches the grammar.
        sec = section_from('theory A imports Main begin\n'
                           'ML \\<open>\n'
                           '  fun mk_thm ctxt = ctxt\n'
                           '\\<close>\n'
                           'lemma foo: "True" by simp\n'
                           'end\n')
        self.assertEqual(names(sec), ["foo"])

    def test_prose_in_a_text_block_is_not_a_declaration(self):
        sec = section_from('theory A imports Main begin\n'
                           'text \\<open>\n'
                           'lemma of the day: be careful\n'
                           '\\<close>\n'
                           'lemma foo: "True" by simp\n'
                           'end\n')
        self.assertEqual(names(sec), ["foo"])


class DoesNotOverrun(unittest.TestCase):
    r"""The statement scan must stop at an INDENTED declaration too.

    This is the half that had to move in the same step.  The scan that looks
    for a proof runs forward from a declaration and breaks when it meets the
    next one; while that break was column-0 anchored, making declarations
    recognisable at any indentation would have left every indented one invisible
    to it — so a statement would swallow the rest of its locale body.

    That is precisely the `Berlekamp_Hensel` failure (15 consecutive lemmas
    lost to one mis-parsed statement), which no unit test caught at the time —
    only a corpus diff of the entry set did.
    """

    def test_an_indented_declaration_ends_the_previous_statement(self):
        sec = section_from('theory A imports Main begin\n'
                           'context foo begin\n'
                           '  lemma one:\n'
                           '    "True"\n'
                           '\n'
                           '  lemma two: "True" by simp\n'
                           '  lemma three: "True" by simp\n'
                           'end\n'
                           'end\n')
        self.assertEqual(names(sec), ["one", "two", "three"])

    def test_an_indented_definition_ends_the_previous_one(self):
        sec = section_from('theory A imports Main begin\n'
                           'context foo begin\n'
                           '  definition g :: "nat \\<Rightarrow> nat"\n'
                           '    where "g x = x"\n'
                           '  definition h :: "nat \\<Rightarrow> nat"\n'
                           '    where "h x = x"\n'
                           'end\n'
                           'end\n')
        self.assertEqual(names(sec), ["g", "h"])


class SpanBoundaries(unittest.TestCase):
    r"""`_structural_command_lines` reads command position too.

    It was anchored at column 0 on the stated grounds that "an indented `end`
    closing a nested proof does not cut anything" — but `end` does not close a
    proof (`qed` does), and an indented `end` closes a nested `context`, which
    is exactly a boundary worth reporting.  The anchor suppressed real cuts
    inside every indented block AND accepted false ones from prose.

    Over 120 AFP entries the change moved 847 spans: 840 shrank (a block close
    now cuts) and 6 GREW — each of those a false boundary removed, which is the
    more interesting direction and is why both are pinned here.
    """

    def span(self, snippet, name):
        sec = section_from(snippet)
        e = next(x for x in sec.entries if x.name == name)
        return e.src_start, e.thy_end

    def test_an_indented_end_closes_the_span(self):
        # `foo` must not run on through the `end` that closes its context.
        _start, end = self.span('theory A imports Main begin\n'
                                'locale L begin\n'
                                '  context begin\n'
                                '    lemma foo: "True" by simp\n'
                                '  end\n'
                                '  lemma bar: "True" by simp\n'
                                'end\n'
                                'end\n', "foo")
        self.assertEqual(end, 4)

    def test_prose_beginning_with_a_command_word_is_not_a_boundary(self):
        r"""`BytecodeLogicJmlTypes/Logic:152` — a `text` block whose line
        begins with the English word "context"; `Belief_Revision/AGM_Logic:302`
        begins with "lemmas".  Both truncated the declaration above them."""
        _start, end = self.span('theory A imports Main begin\n'
                                'definition g where "g x = x"\n'
                                'text \\<open>\n'
                                'context is discharged in the proof below, and\n'
                                'lemmas are stated without complex inferences\n'
                                '\\<close>\n'
                                'end\n', "g")
        self.assertGreaterEqual(end, 5)

    def test_verbatim_code_is_not_a_boundary(self):
        r"""`AutoCorres2/open_struct:1314` — C source inside a
        `\<^verbatim>\<open>...\<close>` cartouche, beginning `typedef struct`.
        `typedef` is an Isabelle span-boundary command; that C is not it."""
        _start, end = self.span('theory A imports Main begin\n'
                                'definition g where "g x = x"\n'
                                'text \\<open>\n'
                                '\\<^verbatim>\\<open>\n'
                                'typedef struct foo { int x; };\n'
                                '\\<close>\n'
                                '\\<close>\n'
                                'end\n', "g")
        self.assertGreaterEqual(end, 5)


if __name__ == "__main__":
    unittest.main()
