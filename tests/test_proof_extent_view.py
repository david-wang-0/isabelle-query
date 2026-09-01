r"""A boundary written inside a comment is not a boundary [proof-extent-view].

`_proof_extent` walks forward from a proof's first line and stops at a `text`
block, a heading, or the next declaration.  All three tests read the RAW line,
so a commented-out one ended a proof that had not ended — and authors supersede
a lemma and leave the old one in a `(* ... *)` all the time:

    lemma AR_Times: ...
      using assms by (simp add: ...)

    (* Unused and requires ordered_euclidean_space
    subsection\<^marker>\<open>tag unimportant\<close>\<open>Retracts ...\<close>

    lemma ANR_interval [iff]: ...

The proof above stopped at the `(*`.  **287 of the AFP's 295,775 proofs**, all
of them under-long; `body_end_line` grew on 287 entries and shrank on none, and
no other field moved at all.  It feeds `shape`'s `proof_lines` /
`proof_tokens` and `show --proof`.

The test is `nonisar_ranges` — the WHOLE-LINE noise mask — and not the outer
view every other scanner asks.  The outer view blanks a `text` block's own
cartouche, so `startswith("text ")` would stop matching and the boundary would
be lost in the other direction; and a *partially* commented line
(`lemma foo: "P" (* note *)`) is live Isar that must still end the proof.
`TheRealBoundariesStillStop` pins both.

Only the boundary tests are masked.  Whether a trailing comment block belongs
to the proof is a separate question, and answering it here would be a second
change hiding inside this one.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402

# The shape of HOL/Analysis/Retracts:1262 and
# ABY3_Protocols/Multiplication_Synthesization:41.
COMMENTED = r'''theory Commented
imports Main
begin

lemma first: "True"
  using TrueI
  by simp

(* superseded, kept for reference
lemma old_version: "True"
  by auto
*)

lemma second: "True" by simp

end
'''

COMMENTED_HEADING = r'''theory Head
imports Main
begin

lemma first: "True"
  by simp

(* not ready
subsection \<open>Retracts and intervals\<close>
*)

lemma second: "True" by simp

end
'''

COMMENTED_TEXT = r'''theory Txt
imports Main
begin

lemma first: "True"
  by simp

(* draft
text \<open>Some prose that is not live.\<close>
*)

lemma second: "True" by simp

end
'''

# The guards: real boundaries, and a live line that merely ends in a comment.
LIVE = r'''theory Live
imports Main
begin

lemma stops_at_decl: "True"
  by simp
lemma next_decl: "True" by simp

lemma stops_at_text: "True"
  by simp
text \<open>Real prose.\<close>

lemma stops_at_heading: "True"
  by simp
subsection \<open>Real heading\<close>

lemma trailing_comment: "True"
  by simp
lemma after_it: "P" (* a note on a live declaration *)
  by simp

end
'''


def body_end(src, theory, name):
    sec = section_from(src, theory)
    return next(e for e in sec.entries if e.name == name).body_end_line


class ACommentedBoundaryDoesNotStopTheProof(unittest.TestCase):

    def test_a_commented_out_declaration(self):
        # `first`'s proof is 6..7; the commented block is 9..12.  Before, the
        # walk stopped at line 10's `lemma old_version` and gave 9.
        self.assertEqual(body_end(COMMENTED, "Commented", "first"), 12)

    def test_a_commented_out_heading(self):
        self.assertEqual(body_end(COMMENTED_HEADING, "Head", "first"), 10)

    def test_a_commented_out_text_block(self):
        self.assertEqual(body_end(COMMENTED_TEXT, "Txt", "first"), 10)

    def test_the_commented_lemma_declares_nothing(self):
        # Belt and braces: the entry scan already excludes it, and if it ever
        # stopped doing so this test would be measuring the wrong thing.
        sec = section_from(COMMENTED, "Commented")
        self.assertEqual([e.name for e in sec.entries], ["first", "second"])


class TheRealBoundariesStillStop(unittest.TestCase):
    """Guard: passes before and after.  Masking noise must not mask Isar."""

    def test_a_real_declaration_stops_it(self):
        self.assertEqual(body_end(LIVE, "Live", "stops_at_decl"), 6)

    def test_a_real_text_block_stops_it(self):
        self.assertEqual(body_end(LIVE, "Live", "stops_at_text"), 10)

    def test_a_real_heading_stops_it(self):
        self.assertEqual(body_end(LIVE, "Live", "stops_at_heading"), 14)

    def test_a_live_line_that_merely_ends_in_a_comment_still_stops_it(self):
        # `lemma after_it: "P" (* a note *)` is a declaration.  Whole-line
        # masking is what gets this right: the line is not wholly noise, so it
        # is still tested, and `DECL_RE` still matches it.
        self.assertEqual(body_end(LIVE, "Live", "trailing_comment"), 18)


if __name__ == "__main__":
    unittest.main()
