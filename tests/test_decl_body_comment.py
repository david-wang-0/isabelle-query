r"""A formal comment does not end a declaration, and is not part of one.

`_scan_decl_body` broke out of the body scan at a line starting `\<comment>`,
with an exception for `record` (where breaking cost 11 of the AFP's 507 records
every field they declare).  The exception was deliberately left narrow, with
the general case recorded as unmeasured.

Measured: the break truncated **50 declarations over 11,514 theories**, all of
the keyword-comment-name shape, collapsing `body_end_line` onto the keyword
line.  `SchorrWaite:14`'s `rel` reported `body 14..14` for a declaration
running to 17.  That field is documented as the "safe relocation cut" and is
now `api` surface, so a consumer cutting there leaves the declaration behind.

Isabelle's lexer skips all four formal comments wherever a token may appear, so
one cannot end a declaration on ANY route.  Asking the tokenizer's `live` view
rather than testing the raw text also covers a comment that WRAPS (only its
first line carries the marker), the other three spellings, and `(* ... *)`.

Whole-AFP entry-set diff (`dump_entries.py --spans`), 390,397 records:

    1,796 -> 706 records changed, 0 gained, 0 lost
    401 grew   -- truncations repaired
    305 shrank -- pure-comment lines no longer counted as body,
                  all 305 verified blank in `live_source()`
    containment (body_end <= thy_end) unchanged at 82

The two directions are both corrections, which is why the shrink needed its own
proof rather than a sample: `scripts/probe_body_shrink_check.py`.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query.api import parse_theory  # noqa: E402

HEAD = "theory Probe\nimports Main\nbegin\n"
TAIL = "\nend\n"


def entry(body: str):
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "Probe.thy"
        p.write_text(HEAD + body + TAIL, encoding="utf-8")
        return parse_theory("Probe", p).entries[0]


class ACommentDoesNotEndADeclaration(unittest.TestCase):
    """The `SchorrWaite:14` shape, in each comment spelling."""

    # `definition` on line 4, comment on 5, name on 6, RHS on 7.
    def body_end_with(self, note: str) -> int:
        return entry(
            "definition\n"
            f"{note}\n"
            '  rel :: "nat set"\n'
            '  where "rel = {}"').body_end_line

    def test_marginal_note(self):
        self.assertEqual(
            self.body_end_with("  \\<comment> \\<open>Relations.\\<close>"), 7)

    def test_a_wrapping_note(self):
        # Only the FIRST line carries the marker, which is what the old raw
        # text test could not see.
        self.assertEqual(entry(
            "definition\n"
            "  \\<comment> \\<open>Relations induced\n"
            "      by a mapping.\\<close>\n"
            '  rel :: "nat set"\n'
            '  where "rel = {}"').body_end_line, 8)

    def test_a_document_marker(self):
        self.assertEqual(
            self.body_end_with("  \\<^marker>\\<open>tag important\\<close>"), 7)

    def test_an_ml_style_block_comment(self):
        self.assertEqual(self.body_end_with("  (* Relations. *)"), 7)

    def test_the_name_and_the_body_agree(self):
        e = entry(
            "definition\n"
            "  \\<comment> \\<open>Relations.\\<close>\n"
            '  rel :: "nat set"\n'
            '  where "rel = {}"')
        self.assertEqual(e.name, "rel")
        self.assertGreaterEqual(e.body_end_line, 6)   # reaches its own name


class ACommentIsNotPartOfADeclaration(unittest.TestCase):
    """The other direction: a trailing note must not extend the body.

    This is the 305 that SHRANK — `CoSMeDis/Prelim:62` counted a following
    `(* initially set to ... *)` line as declaration body, and
    `Prop_Compactness/k_coloring:101` counted a bare `(*>*)`.
    """

    def test_a_trailing_block_comment_is_not_body(self):
        e = entry(
            'definition emptyPost :: nat where\n'
            '"emptyPost = 0"\n'
            "(* initially set to the lowest value *)")
        self.assertEqual(e.body_end_line, 5)      # the RHS line, not the note

    def test_a_trailing_marginal_note_is_not_body(self):
        e = entry(
            'definition emptyPost :: nat where\n'
            '"emptyPost = 0"\n'
            "\\<comment> \\<open>a note\\<close>")
        self.assertEqual(e.body_end_line, 5)


class TheBodyStaysInsideItsOwnSpan(unittest.TestCase):
    """A body that grew must not have grown into the next declaration.

    The invariant a shrink-only check cannot see, and the one that rejected
    the wider version of this fix: relaxing the BLANK-line break as well took
    corpus-wide containment violations from 82 to 719.
    """

    def test_two_declarations_do_not_overlap(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "Probe.thy"
            p.write_text(HEAD + (
                "definition\n"
                "  \\<comment> \\<open>first\\<close>\n"
                '  a :: "nat" where "a = 0"\n'
                "definition\n"
                "  \\<comment> \\<open>second\\<close>\n"
                '  b :: "nat" where "b = 1"') + TAIL, encoding="utf-8")
            es = parse_theory("Probe", p).entries
        self.assertEqual([e.name for e in es], ["a", "b"])
        for e in es:
            self.assertLessEqual(e.body_end_line, e.thy_end)
        self.assertLess(es[0].thy_end, es[1].thy_line)


class TheBlankLineVariantIsStillOpen(unittest.TestCase):
    r"""`WFair:35` — a BLANK line before the note, which breaks first.

        definition
                                  <- blank; ends the body scan here
          \<comment> \<open>...\<close>
          transient :: ...

    Four residual records over the distribution plus one in the AFP.  The
    obvious fix — do not break on a blank while the body is still empty — was
    implemented, measured, and REJECTED: it repairs these but takes containment
    violations from 82 to 719, growing bodies past their own `thy_end` into the
    following declaration.  Pinned as expected-failure so improving it reports
    an unexpected success rather than going unnoticed.
    """

    @unittest.expectedFailure
    def test_a_blank_before_the_note(self):
        e = entry(
            "definition\n"
            "\n"
            "  \\<comment> \\<open>Specifies conditional fairness.\\<close>\n"
            '  transient :: "nat set"\n'
            '  where "transient = {}"')
        self.assertEqual(e.name, "transient")
        self.assertGreaterEqual(e.body_end_line, 7)


if __name__ == "__main__":
    unittest.main()
