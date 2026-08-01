r"""Non-Isar lexical regions: comments, `\<^cancel>`, verbatim, and ML bodies.

Isabelle source contains regions that are not Isar proof text.  A name in such
a region is not a fact citation, and a command word in one is not a command.
The scanner used to read all of them as live source, which produced call-graph
edges the source does not support (`callers` invents a caller, `unused` hides a
dead lemma) and truncated spans (a commented-out `end` cut the declaration
above it, so `show`/`outline`/`enclosing`/`largest` all reported the wrong
extent).

The reproductions below are the four in issue #2, verbatim, with the correct
results stated there — plus the fixtures that issue asks for around them: an
ML body with no entry above it (which passed before the fix, for the wrong
reason), an ML body between two entries, a comment nested two levels deep, and
the guard against over-correcting.

That guard is the important one.  A `"..."` region holds an inner-syntax term,
so `foo` in `lemma bar: "foo = 0"` is a REAL citation.  Redacting strings — or
redacting every cartouche rather than only an ML command's body — would delete
true edges, a failure this suite would otherwise not notice, because every
other test here asserts the ABSENCE of an edge.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import (cli, section_from,  # noqa: E402
                     brute_force_call_graph)
from isabelle_query import parsing  # noqa: E402


def ranges(snippet):
    """Non-Isar line ranges of a snippet (1-indexed inclusive)."""
    return parsing.extract_nonisar_ranges(snippet.split("\n"))


def graph_of(sec):
    return cli._build_call_graph([sec])


def entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


def enclosing(sec, line_no):
    """The entry whose span contains `line_no`, or None — what `enclosing` asks."""
    idx = cli._build_line_index([sec]).get(sec.theory, [])
    return cli._entry_at_line(idx, line_no)


# --- the four reproductions of issue #2, verbatim ---------------------------

R1 = r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  (* could have used foo here, but did not *)
  by simp

end
'''

R2 = r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True" by simp

ML \<open>
  val msg = "foo is not a fact citation here";
\<close>

end
'''

R3 = r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  \<^cancel>\<open>using foo\<close>
  by simp

end
'''

R4 = r'''theory A
imports Main
begin

lemma bar: "True \<and> True"
proof
(* superseded:
end
  show True by simp
*)
  show True by simp
  show True by simp
qed

end
'''


class Reproductions(unittest.TestCase):
    """The four defects of issue #2, each asserting the correct result."""

    def test_r1_comment_is_not_a_citation(self):
        sec = section_from(R1, "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_r1_comment_does_not_hide_a_dead_lemma(self):
        # The user-visible consequence: `foo` is dead code, and the comment
        # (which says the author did NOT use it) concealed that.
        sec = section_from(R1, "A")
        g = cli._build_call_graph([sec], derived=True)
        self.assertEqual(cli._compute_unused(g), {"foo", "bar"})

    def test_r2_ml_body_is_not_a_citation(self):
        # ML source has its own namespace: the ML identifier `foo` is not the
        # Isabelle fact `foo`.
        sec = section_from(R2, "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_r2_ml_body_ends_the_span_above_it(self):
        # `bar` is one line; before the fix its span ran on to 11, absorbing
        # the whole ML block.
        sec = section_from(R2, "A")
        bar = entry(sec, "bar")
        self.assertEqual((bar.src_start, bar.thy_end), (7, 7))

    def test_r3_cancel_region_is_not_a_citation(self):
        # Isabelle deletes the text in a \<^cancel> region, so a citation there
        # is evidence the proof does NOT use the fact.
        sec = section_from(R3, "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_r4_commented_end_does_not_cut_the_span(self):
        sec = section_from(R4, "A")
        bar = entry(sec, "bar")
        self.assertEqual((bar.src_start, bar.thy_end), (5, 13))

    def test_r4_live_proof_line_has_an_enclosing_entry(self):
        # Line 12 is an ordinary `show` in a live proof.
        sec = section_from(R4, "A")
        e = enclosing(sec, 12)
        self.assertIsNotNone(e)
        self.assertEqual(e.name, "bar")


class MLBodyFamily(unittest.TestCase):
    """Issue #2 asks for these separately: a partial correction passes some."""

    def test_ml_body_with_no_entry_above_it(self):
        # This case passed BEFORE the fix, for the wrong reason: the edge was
        # attributed to the entry enclosing the line, and no entry enclosed it.
        sec = section_from(r'''theory A
imports Main
begin

ML \<open>
  val msg = "foo is not a fact citation here";
\<close>

lemma foo: "True" by simp

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_ml_body_below_the_only_entry(self):
        # Issue #2's own construction: R2 with `lemma bar` removed.  This one
        # passed BEFORE the fix too — the ML body fell inside `foo`'s own
        # (over-long) span, so the mention was discarded as a self-citation.
        # Kept because a correction that re-attributes it would be a regression
        # that no other test here would catch.
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

ML \<open>
  val msg = "foo is not a fact citation here";
\<close>

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_ml_body_between_two_entries(self):
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True" by simp

ML \<open>
  val msg = "foo";
\<close>

lemma baz: "True" by simp

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["foo"], set())
        self.assertEqual((entry(sec, "bar").src_start,
                          entry(sec, "bar").thy_end), (7, 7))
        self.assertIn("baz", {e.name for e in sec.entries})

    def test_ml_file_ends_the_span_above_it(self):
        # `ML_file` takes a path, not a cartouche — no body to skip, but it is
        # still a command, so it must not be absorbed into the lemma above.
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

ML_file "helper.ML"

end
''', "A")
        self.assertEqual(entry(sec, "foo").thy_end, 5)


class NestedAndInline(unittest.TestCase):
    def test_nested_comment_two_levels_deep(self):
        # A non-greedy match to the FIRST `*)` would end the comment on the
        # inner one and read the line after it as live proof text.
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  (* outer
     (* inner *)
     foo is still inside the comment
  *)
  by simp

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())
        self.assertEqual(ranges(r'''(* outer
   (* inner *)
   still comment
*)
live'''), [(1, 4)])

    def test_comment_alone_on_one_line_inside_a_proof(self):
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  proof -
  (* foo would do here *)
    show "True" by simp
  qed

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_legacy_verbatim_is_not_live(self):
        self.assertEqual(ranges('{* legacy foo *}'), [(1, 1)])


class KeptLive(unittest.TestCase):
    r"""The over-correction guard: these edges must SURVIVE.

    A correction that redacts `"..."` regions, or every cartouche rather than
    only an ML command's body, passes every other test in this file while
    quietly deleting most of the call graph.
    """

    def test_fact_named_in_a_term_is_still_a_citation(self):
        sec = section_from(r'''theory A
imports Main
begin

definition foo :: "nat" where "foo = 0"

lemma bar: "foo = foo" by simp

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["foo"], {"bar"})
        self.assertEqual(g.callers, brute_force_call_graph([sec]).callers)

    def test_operator_section_in_a_term_opens_no_comment(self):
        # `(*)` is HOL's multiplication section.  Reading its `(*` as a comment
        # opener would redact the rest of the theory — deleting `foo`'s edge and
        # every entry below.
        sec = section_from(r'''theory A
imports Main
begin

definition foo :: "nat" where "foo = 0"

lemma bar: "fold (*) [1, 2] foo = foo" by simp

lemma baz: "True" by simp

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["foo"], {"bar"})
        self.assertEqual({e.name for e in sec.entries}, {"foo", "bar", "baz"})
        self.assertEqual(ranges('lemma bar: "fold (*) [1, 2] x = y" by simp'), [])

    def test_operator_section_in_a_cartouche_opens_no_comment(self):
        # Isabelle scans a cartouche as one token, so `(*` inside it is term
        # text, not a comment opener.
        self.assertEqual(
            ranges(r'have \<open>fold (*) [1, 2] x = y\<close> by simp'), [])

    def test_comment_open_inside_a_string_is_not_a_comment(self):
        self.assertEqual(ranges(r'lemma bar: "x = (*) 1 2" by simp'), [])

    def test_trailing_comment_keeps_its_line_live(self):
        # Deliberately conservative: `_noise_spans` is line granular, so
        # reporting this line would blank its live half and drop the real
        # citation of `foo`.  The phantom citation from the comment survives
        # (see `test_known_failures`); losing a true edge would be worse.
        self.assertEqual(ranges('  by (simp add: foo) (* not bar *)'), [])


class Ranges(unittest.TestCase):
    """Unit-level: the line ranges the scanner reports."""

    def test_full_line_comment(self):
        self.assertEqual(ranges('lemma a\n  (* prose *)\n  by simp'), [(2, 2)])

    def test_multi_line_comment_is_one_range(self):
        self.assertEqual(ranges('a\n(* one\ntwo\nthree *)\nb'), [(2, 4)])

    def test_cancel_region(self):
        self.assertEqual(ranges(r'a' '\n' r'  \<^cancel>\<open>using foo\<close>'
                                '\n' r'b'), [(2, 2)])

    def test_ml_body(self):
        self.assertEqual(ranges('ML \\<open>\n  val x = 1;\n\\<close>\nlemma a'),
                         [(2, 3)])

    def test_ml_command_line_itself_stays_live(self):
        # The `ML` keyword must stay visible: it is the span boundary.
        self.assertNotIn(1, [lo for lo, _ in
                             ranges('ML \\<open>\n  val x = 1;\n\\<close>')])

    def test_blank_source_has_no_ranges(self):
        self.assertEqual(ranges('lemma a: "True" by simp'), [])


if __name__ == "__main__":
    unittest.main()
