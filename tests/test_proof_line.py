r"""`Entry.proof_line` — where a fact's proof starts.

Found from an odd direction: `\<comment>` notes were being dropped "because the
entry has no proof", and 12 of those entries were lemmas that plainly had one.
Over 120 AFP entries, 2,513 of 40,361 facts (6.23%) had `proof_line = 0`.

It is not a roadmap field.  Four consumers read it, and the roadmap is the
least of them:

  * `commands._proof_blocks` returns [] outright when it is 0, so
    `enclosing -b` silently offers no drill-down;
  * `_proof_extent` / `body_end_line` falls back to the declaration end;
  * `shape` contributes no steps for the proof;
  * `_attach_annotations` cannot bound the proof body.

Three shapes defeated the original scan, which started on the line BELOW the
declaration and stopped at the first blank line.  Each is asserted here, and
each is a real AFP shape, cited by name.

The guard tests matter as much as the recovery ones: this scan runs forward
from a declaration, so a rule that is too eager consumes the NEXT declaration.
An early version of the parity tracking did exactly that and swallowed 15
consecutive lemmas of `Berlekamp_Hensel`, which no unit test noticed — only a
corpus diff of the entry set did.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402


def entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


def names(sec):
    return [e.name for e in sec.entries]


class OneLiner(unittest.TestCase):
    """The proof shares a line with the statement (1,857 AFP facts)."""

    def test_proof_on_the_declaration_line(self):
        # `Aodv:506` — `lemma foo [simp]: "P" by simp`
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo [simp]: "True" by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 2)

    def test_proof_on_the_statements_continuation_line(self):
        # `Abstract_Rewriting:78` — the declaration line carries no statement,
        # and the line below carries both the statement and the proof.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "True" by auto\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 3)

    def test_proof_after_the_term_closes(self):
        # `Akra_Bazzi_Method:84` — the term ENDS on the proof's line, so only
        # the text after the closing quote may be read as the proof.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "True \\<Longrightarrow>\n'
                           '   True" by (simp_all)\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 4)

    def test_dot_proof(self):
        # `Merkle_Interface:160` — `lemma foo: "P" ..`
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True" ..\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 2)


class BlankLineBeforeTheProof(unittest.TestCase):
    """A blank line ends the STATEMENT, not the search (656 AFP facts)."""

    def test_blank_between_statement_and_proof(self):
        # `Additive_Sharing:310`
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "True"\n'
                           '\n'
                           'proof -\n'
                           '  show "True" by simp\n'
                           'qed\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 5)

    def test_blank_inside_a_multi_line_term(self):
        # `Shuffle:2227` — a `do { ... }` block with blank lines between its
        # rounds.  The blank is INSIDE the term, so it ends nothing.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "x = (do {\n'
                           '     let n = 1;\n'
                           '\n'
                           '     return n\n'
                           '  })"\n'
                           '\n'
                           '  unfolding foo_def by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 9)

    def test_blank_between_assumptions(self):
        # `Aodv_Data:536` — a long `assumes ... and ...` list broken up for
        # readability.  The statement resumes; the proof is further down.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  assumes a1: "True"\n'
                           '\n'
                           '  and a2: "True"\n'
                           '  shows "True"\n'
                           '  proof -\n'
                           '    show "True" by simp\n'
                           '  qed\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 7)


class DoesNotOverrun(unittest.TestCase):
    """The scan runs FORWARD, so eagerness costs whole declarations."""

    def test_a_following_lemma_is_still_its_own_entry(self):
        sec = section_from('theory A imports Main begin\n'
                           'lemma one:\n'
                           '  "True"\n'
                           '\n'
                           'lemma two: "True" by simp\n'
                           'end\n')
        self.assertEqual(names(sec), ["one", "two"])

    def test_a_note_carrying_the_terms_closing_quote(self):
        r"""`Berlekamp_Hensel:64` — the closing `"` is on a `\<comment>` line.

        The scan used to count quotes by hand to know whether it was inside a
        term.  The comment-skip path did not count the ones it skipped past, so
        the term never closed, every later `lemma` was read as part of this
        statement, and 15 consecutive entries vanished — caught by a corpus
        diff of the entry set, not by any unit test.

        There is no parity to desynchronise now: the tokenizer closes the term,
        and the line is skipped only if it is WHOLLY prose.  Here it is not —
        the note is prose and the closing quote beside it is not — so the line
        is read as statement text and the term ends where Isabelle says it does.
        """
        sec = section_from('theory A imports Main begin\n'
                           'theorem one:\n'
                           '  shows "True"\n'
                           '    and "True\n'
                           '      \\<comment> \\<open>a note\\<close>"\n'
                           'proof -\n'
                           '  show "True" by simp\n'
                           '  show "True" by simp\n'
                           'qed\n'
                           '\n'
                           'lemma two: "True" by simp\n'
                           'lemma three: "True" by simp\n'
                           'end\n')
        self.assertEqual(names(sec), ["one", "two", "three"])
        self.assertEqual(entry(sec, "one").proof_line, 6)

    def test_a_proof_keyword_inside_a_term_is_not_the_proof(self):
        # `apply` is a perfectly good identifier in inner syntax.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "apply f x = using g y"\n'
                           '  by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 4)

    def test_statement_rendering_still_stops_at_the_blank(self):
        # `decl_end_line` deliberately does NOT follow the proof search past a
        # blank: `show --statement` must render what it always rendered.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "True"\n'
                           '\n'
                           'proof -\n'
                           '  show "True" by simp\n'
                           'qed\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").decl_end_line, 3)


class RoadmapBoundary(unittest.TestCase):
    r"""A note on the proof's own first line is a roadmap step.

    The rule was `proof_line < line`, which reads like an off-by-one and was
    not: a proof that IS one line has no line strictly inside it, so NO
    single-line proof could contribute a roadmap step, whatever its note said.
    """

    def contents(self, sec, name):
        return [c for _, c in entry(sec, name).roadmap]

    def test_note_on_a_one_line_proof(self):
        # `AVL2:140`, the case that made this concrete.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  "True"\n'
                           '  by (simp)'
                           r' \<comment> \<open>two simps is faster\<close>' '\n'
                           'end\n')
        self.assertEqual(self.contents(sec, "foo"), ["two simps is faster"])

    def test_note_deeper_in_the_proof_still_attaches(self):
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "True"\n'
                           'proof -\n'
                           '  show "True" by simp'
                           r' \<comment> \<open>the real work\<close>' '\n'
                           'qed\n'
                           'end\n')
        self.assertEqual(self.contents(sec, "foo"), ["the real work"])

    def test_note_on_the_statement_is_not_a_roadmap_step(self):
        # `Lifschitz_Consistency:102` — this annotates WHAT is proved, not how.
        # Deliberately out, pending a display that can tell the two apart.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  assumes a: "True"'
                           r' \<comment> \<open>for a sound system\<close>' '\n'
                           '  shows "True"\n'
                           '  by simp\n'
                           'end\n')
        self.assertEqual(self.contents(sec, "foo"), [])


class TermTracking(unittest.TestCase):
    r"""Where a term ends is the tokenizer's answer, not a quote count.

    Three separate hand-rolled trackers used to answer it — a `"` parity in the
    definition route, another in the goal route, and a strip-complete-pairs
    reconstruction for the one-liner scan.  Each had to be right about escapes,
    cartouches and notes, and none was.  All three now ask the scan, which
    tracked the same states all along.
    """

    def test_proof_on_the_line_the_term_closes(self):
        r"""`Berlekamp_Zassenhaus/Mahler_Measure:257` — `...)" proof -`.

        The old scan missed it and took line 262's NESTED `proof(induct ...)`
        as the proof of the outer lemma.
        """
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo:\n'
                           '  shows "True \\<and>\n'
                           '     True" proof -\n'
                           '  show "True \\<and> True"\n'
                           '  proof (induct x)\n'
                           '  qed auto\n'
                           'qed\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 4)

    def test_a_cartouche_body_with_a_blank_line(self):
        r"""`CVM_Distinct_Elements/CVM_Original_Algorithm:66` — a `fun` whose
        `\<open>...\<close>` body holds a `do { ... }` with a blank line in it.

        The declaration stopped at the blank, three lines before the cartouche
        actually closed.  A blank line is whitespace; it ends nothing.
        """
        sec = section_from('theory A imports Main begin\n'
                           'fun f :: \\<open>nat \\<Rightarrow> nat\\<close> where\n'
                           '  \\<open>f a =\n'
                           '    do {\n'
                           '      let x = a;\n'
                           '\n'
                           '      return x\n'
                           '    }\\<close>\n'
                           'end\n')
        self.assertEqual(entry(sec, "f").decl_end_line, 8)

    def test_a_rule_list_spaced_out_with_blank_lines(self):
        r"""`AWN/AWN_SOS:14` — an `inductive_set` whose rules are spaced into
        groups.  The declaration runs to line 34 and stopped at 26, so `show`
        rendered two thirds of it, `largest` under-measured it, and four rule
        names sat outside the extent `[declared-names]` scans.  `AODV/Aodv:264`
        is the worst: a `fun` running to 420 that ended at 300.

        A line beginning `|` cannot start a new command, so it can only
        continue this one.  Over 120 AFP entries this reaches 64 declarations
        and 1,306 lines; no extent shrinks and none passes its own span.
        """
        sec = section_from('theory A imports Main begin\n'
                           'inductive_set S :: "nat set"\n'
                           'where\n'
                           '    a: "0 \\<in> S"\n'
                           '\n'
                           '  | b: "1 \\<in> S"\n'
                           '\n'
                           '\n'
                           '  | c: "2 \\<in> S"\n'
                           '\n'
                           'lemma later: "True" by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "S").decl_end_line, 9)

    def test_a_blank_still_ends_a_declaration_no_bar_follows(self):
        r"""`ABY3_Protocols/Multiplication_Synthesization:22` — a `definition`
        followed by a blank and an `adhoc_overloading`, which is neither a
        recognised declaration nor a boundary command.  The blank is the ONLY
        thing that ends the declaration here, so the `|` lookahead must not
        weaken it: without the blank rule 706 of 55,838 entries run long.
        """
        sec = section_from('theory A imports Main begin\n'
                           'definition d :: "nat" where\n'
                           '  "d = 0"\n'
                           '\n'
                           'adhoc_overloading Monad_Syntax.bind \\<rightleftharpoons> d\n'
                           'end\n')
        self.assertEqual(entry(sec, "d").decl_end_line, 3)

    def test_a_comment_between_rules_is_stepped_over(self):
        r"""`AWN/OAWN_SOS:222` spaces its rules apart AND puts a `(* ... *)`
        note between two of them, so a blanks-only lookahead stops at the
        note and loses the last eight rules.

        The lookahead asks the OUTER view, which already draws the needed
        line: a blank and a comment blank to nothing, while `text` keeps its
        command word.  Formatting and notes are stepped over; structure is
        not.  This takes the corpus from 64 declarations reaching further to
        101.
        """
        sec = section_from('theory A imports Main begin\n'
                           'inductive_set S :: "nat set"\n'
                           'where a: "0 \\<in> S"\n'
                           '\n'
                           '  (* justified in the closed-system proof *)\n'
                           '  | b: "1 \\<in> S"\n'
                           'end\n')
        self.assertEqual(entry(sec, "S").decl_end_line, 6)

    def test_the_lookahead_stops_at_outer_syntax(self):
        r"""`ADS_Functor/ADS_Construction:290` — an `abbreviation` ending at
        291, then a comment banner and a `subsubsection`, and a `|` belonging
        to some later declaration.  The banner is skippable but the
        `subsubsection` is not, so the two are never linked.  A lookahead that
        scans past all non-blank lines to find a `|` joins them: 330 entries
        change, and this one gets `decl_end=295` against a span of `290..293`
        — an extent past its own end.
        """
        sec = section_from('theory A imports Main begin\n'
                           'abbreviation h :: "nat" where\n'
                           '  "h \\<equiv> 0"\n'
                           '\n'
                           '(**********)\n'
                           'subsubsection \\<open>Later\\<close>\n'
                           '\n'
                           'inductive_set S :: "nat set"\n'
                           'where a: "0 \\<in> S"\n'
                           '  | b: "1 \\<in> S"\n'
                           'end\n')
        e = entry(sec, "h")
        self.assertEqual(e.decl_end_line, 3)
        self.assertLessEqual(e.decl_end_line, e.thy_end)

    def test_a_blank_then_a_real_command_still_ends_it(self):
        # The `|` lookahead skips blanks ONLY; it must not reach past the
        # next declaration and swallow it.
        sec = section_from('theory A imports Main begin\n'
                           'inductive_set S :: "nat set"\n'
                           'where a: "0 \\<in> S"\n'
                           '\n'
                           'lemma later: "True" by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "S").decl_end_line, 3)

    def test_a_text_block_between_rules_still_ends_it(self):
        # A `text` block between two rules is prose, and ends the declaration
        # on its own terms; the lookahead must not step over it.
        sec = section_from('theory A imports Main begin\n'
                           'inductive_set S :: "nat set"\n'
                           'where a: "0 \\<in> S"\n'
                           '\n'
                           'text \\<open>an aside\\<close>\n'
                           '\n'
                           '  | b: "1 \\<in> S"\n'
                           'end\n')
        self.assertEqual(entry(sec, "S").decl_end_line, 3)

    def test_an_escaped_quote_does_not_open_a_term(self):
        # A `\"` inside a string is not a delimiter.  Counting quotes by regex
        # had to special-case that; the scan consumes it as one token.
        sec = section_from('theory A imports Main begin\n'
                           'lemma foo: "s = CHR 0x22 @ \\"x\\""\n'
                           '  by simp\n'
                           'end\n')
        self.assertEqual(entry(sec, "foo").proof_line, 3)


if __name__ == "__main__":
    unittest.main()
