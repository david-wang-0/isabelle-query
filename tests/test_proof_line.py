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
  * `_attach_roadmaps` cannot bound the proof body.

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

        When the comment-skip path did not count quotes on the way past, the
        term never closed, every later `lemma` was read as part of this
        statement, and 15 consecutive entries vanished — caught by a corpus
        diff of the entry set, not by any unit test.

        TWO mechanisms now prevent it, and each is independently sufficient:
        the comment path updates the parity, and the `_match_decl` break is not
        gated on parity at all (it is column-0 anchored, so it bounds any
        parity error to one entry).  Neither can be isolated by this test —
        remove either and it still passes.  Remove BOTH and `two` and `three`
        disappear, which is the failure this pins.
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


if __name__ == "__main__":
    unittest.main()
