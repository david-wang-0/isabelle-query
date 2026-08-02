r"""`TheorySection.live_source` — the redacted, position-preserving view.

The tokenizer (`parsing._scan_nonisar_spans`) reports non-Isar regions at
character granularity.  `live_source` applies them, replacing each such
character with a space.  The point of spaces rather than deletion is that a
scanner can switch views and change nothing else: line numbers, column indices,
1-indexed span arithmetic and line masks all still address the same characters.

Two properties carry the whole design, and both are asserted directly below
before any behaviour is:

  * every line keeps its length, and the file keeps its line count;
  * only the regions the tokenizer names are blanked — a ``"..."`` term and a
    bare cartouche hold inner syntax and stay live, because the `mono` in
    ``lemma "mono f"`` is a real citation.

`source()` is untouched throughout: it is what a caller must print, or `grep`
would show the user blanks where their comment is.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402
from isabelle_query import model  # noqa: E402


def live(snippet, theory="A"):
    return section_from(snippet, theory).live_source()


class Shape(unittest.TestCase):
    """The invariants every consumer relies on, asserted as invariants."""

    SNIPPET = r'''theory A
imports Main
begin

lemma foo: "True" by simp   (* trailing prose *)

lemma bar: "True"
  (* a comment
     running over
     three lines *)
  by simp

ML \<open>
  val x = 1;
\<close>

end
'''

    def test_line_count_is_preserved(self):
        sec = section_from(self.SNIPPET, "A")
        self.assertEqual(len(sec.live_source()), len(sec.source()))

    def test_every_line_keeps_its_length(self):
        sec = section_from(self.SNIPPET, "A")
        self.assertEqual([len(ln) for ln in sec.live_source()],
                         [len(ln) for ln in sec.source()])

    def test_redaction_only_ever_writes_spaces(self):
        # A character either survives unchanged or becomes a space; nothing is
        # rewritten to anything else, so a surviving token is byte-identical.
        sec = section_from(self.SNIPPET, "A")
        for raw, red in zip(sec.source(), sec.live_source()):
            for a, b in zip(raw, red):
                self.assertIn(b, (a, " "))

    def test_source_is_not_mutated(self):
        sec = section_from(self.SNIPPET, "A")
        before = list(sec.source())
        sec.live_source()
        self.assertEqual(sec.source(), before)
        self.assertIn("trailing prose", "\n".join(sec.source()))

    def test_result_is_cached(self):
        sec = section_from(self.SNIPPET, "A")
        self.assertIs(sec.live_source(), sec.live_source())

    def test_clean_theory_shares_the_source_list(self):
        # No region anywhere: the redacted view IS the source, with no copy.
        sec = section_from('theory A\nimports Main\nbegin\n\n'
                           'lemma foo: "True" by simp\n\nend\n', "A")
        self.assertEqual(sec.nonisar_spans, {})
        self.assertIs(sec.live_source(), sec.source())


class WhatIsBlanked(unittest.TestCase):

    def test_trailing_comment_goes_live_text_stays(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "True" by (simp add: foo) (* not baz *)\n\n'
                   'end\n')
        self.assertIn("by (simp add: foo)", out[4])
        self.assertNotIn("baz", out[4])

    def test_multi_line_comment_body_is_blank(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "True"\n'
                   '  (* first\n'
                   '     second *)\n'
                   '  by simp\n\nend\n')
        self.assertEqual(out[5].strip(), "")
        self.assertEqual(out[6].strip(), "")
        self.assertEqual(out[7].strip(), "by simp")

    def test_nested_comment_is_blank_throughout(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "True"\n'
                   '  (* outer (* inner *) still outer *)\n'
                   '  by simp\n\nend\n')
        self.assertEqual(out[5].strip(), "")

    def test_cancel_region_goes(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "True"\n'
                   r'  using helper \<^cancel>\<open>and other\<close> by simp'
                   '\n\nend\n')
        self.assertIn("using helper", out[5])
        self.assertNotIn("other", out[5])
        self.assertIn("by simp", out[5])

    def test_ml_body_goes_but_the_command_stays(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'ML \\<open>\n  val secret = 1;\n\\<close>\n\nend\n')
        self.assertTrue(out[4].startswith("ML"))   # the span boundary survives
        self.assertEqual(out[5].strip(), "")
        self.assertEqual(out[6].strip(), "")

    def test_legacy_verbatim_goes(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "True" by simp {* legacy foo *}\n\nend\n')
        self.assertIn("by simp", out[4])
        self.assertNotIn("legacy", out[4])


class WhatIsKeptLive(unittest.TestCase):
    """Redacting any of these would delete true citations — see issue #3."""

    def test_quoted_term_is_untouched(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "mono f \\<and> True" by simp\n\nend\n')
        self.assertIn('"mono f \\<and> True"', out[4])

    def test_comment_opener_inside_a_string_is_untouched(self):
        # `(*` here is HOL's multiplication section written in a term, not a
        # comment opener; blanking from it would swallow the rest of the file.
        out = live('theory A\nimports Main\nbegin\n\n'
                   'lemma bar: "fold (*) [1, 2] x = y" by simp\n\n'
                   'lemma baz: "True" by simp\n\nend\n')
        self.assertIn("fold (*) [1, 2] x = y", out[4])
        self.assertIn("baz", out[6])

    def test_bare_cartouche_is_untouched(self):
        out = live('theory A\nimports Main\nbegin\n\n'
                   r'lemma bar: \<open>fold (*) xs = y\<close> by simp'
                   '\n\nend\n')
        self.assertIn(r'\<open>fold (*) xs = y\<close>', out[4])

    def test_text_block_prose_is_untouched(self):
        # `text` prose is line-level noise, masked through `_noise_spans`, not
        # redacted here — so the two mechanisms stay separable.
        out = live('theory A\nimports Main\nbegin\n\n'
                   'text \\<open>Prose mentioning foo.\\<close>\n\n'
                   'lemma bar: "True" by simp\n\nend\n')
        self.assertIn("Prose mentioning foo.", out[4])


class BlankSpans(unittest.TestCase):
    """The primitive, in isolation: it must never change a line's length."""

    def check(self, line, spans, expected):
        got = model._blank_spans(line, spans)
        self.assertEqual(got, expected)
        self.assertEqual(len(got), len(line))

    def test_single_span(self):
        self.check("abcdef", [(2, 4)], "ab  ef")

    def test_two_spans(self):
        self.check("abcdef", [(0, 1), (4, 6)], " bcd  ")

    def test_span_to_end_of_line(self):
        self.check("by simp (*x*)", [(8, 13)], "by simp" + " " * 6)

    def test_span_past_the_end_is_clamped(self):
        self.check("abc", [(1, 99)], "a  ")

    def test_no_spans_is_identity(self):
        self.check("abc", [], "abc")

    def test_overlapping_spans_do_not_shift_the_line(self):
        # The tokenizer emits disjoint spans, so this is defensive: the length
        # invariant must hold even if a caller supplies overlap.
        self.check("abcdef", [(1, 4), (2, 5)], "a    f")


class Parity(unittest.TestCase):
    """The line ranges must stay derivable from the columns."""

    def test_fully_blank_lines_are_exactly_the_reported_ranges(self):
        sec = section_from(Shape.SNIPPET, "A")
        from_cols = {i for i, ln in enumerate(sec.live_source(), 1)
                     if sec.source()[i - 1].strip() and not ln.strip()}
        from_ranges = {i for lo, hi in sec.nonisar_ranges
                       for i in range(lo, hi + 1)
                       if sec.source()[i - 1].strip()}
        self.assertEqual(from_cols, from_ranges)


if __name__ == "__main__":
    unittest.main()
