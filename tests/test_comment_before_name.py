r"""A formal comment between a keyword and its name is skipped, not read.

When a declaration keyword stands alone, `_lookahead_name` reads the name from
the first *content* line below it.  It decided what "content" meant by testing
the raw text for a leading ``\<comment>``, and that is wrong twice over:

* it recognises only the comment's FIRST line, so a comment that wraps left its
  continuation looking like content — `HOL/UNITY/WFair.thy:35` was indexed as
  ``is``, out of "the rest **is** generic to all forms of fairness", and
  ``transient``, the constant the file exists to define, was never an entry;
* it recognises only one of Isabelle's four formal comments, so a
  ``\<^marker>`` on its own line (which `HOL/Analysis` writes on hundreds of
  declarations) and an ML-style ``(* ... *)`` both yielded ``?``.

The tokenizer already knows all of these: every such line is blank in the
`live` view.  Asking that view instead of re-testing the text is the whole
fix.

Corpus effect, entry-set diff over the AFP with `scripts/dump_entries.py
--spans`: **11 records**, every one ``?`` -> a real name, with `src`,
`decl_end`, `proof` and `body_end` byte-identical on both sides.  No entry
gained, lost or moved.  Distribution: `WFair`'s ``is`` and `Bali/TypeRel`'s
``i`` phantoms are gone and both real declarations are indexed.

`scripts/probe_comment_shapes.py` is the shape catalogue this file pins.
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

DECL = '  transient :: "nat set" where "transient = {}"'


def parse(body: str):
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "Probe.thy"
        p.write_text(HEAD + body + TAIL, encoding="utf-8")
        return parse_theory("Probe", p)


def names(body: str) -> list[str]:
    return [e.name for e in parse(body).entries]


class AKeywordAloneStillFindsItsName(unittest.TestCase):
    """The six shapes, and the name must be the same in all of them."""

    def test_no_comment_at_all(self):
        self.assertIn("transient", names(f"definition\n{DECL}"))

    def test_comment_on_the_keyword_line(self):
        # Already worked: `_strip_decl_prefix` skips a leading cartouche.
        self.assertIn("transient", names(
            "definition \\<comment> \\<open>Generic to all forms.\\<close>\n"
            f"{DECL}"))

    def test_comment_alone_on_one_line(self):
        self.assertIn("transient", names(
            "definition\n"
            "  \\<comment> \\<open>Generic to all forms.\\<close>\n"
            f"{DECL}"))

    def test_comment_wrapping_over_two_lines(self):
        # The WFair shape.  Before the fix this indexed `is`, from the prose.
        self.assertIn("transient", names(
            "definition\n"
            "  \\<comment> \\<open>This specifies conditional fairness.  The rest\n"
            "      is generic to all forms of fairness.\\<close>\n"
            f"{DECL}"))

    def test_a_marker_is_a_formal_comment_too(self):
        self.assertIn("transient", names(
            "definition\n"
            "  \\<^marker>\\<open>tag important\\<close>\n"
            f"{DECL}"))

    def test_a_wrapping_marker(self):
        self.assertIn("transient", names(
            "definition\n"
            "  \\<^marker>\\<open>tag important\n"
            "      and more\\<close>\n"
            f"{DECL}"))

    def test_an_ml_style_block_comment(self):
        # `GenClock:52` — Isabelle's lexer skips these in the same position,
        # and the live view blanks them, so the same fix covers them.
        self.assertIn("transient", names(
            "definition\n"
            "  (* Adjustment to a clock *)\n"
            f"{DECL}"))


class ThePhantomIsGone(unittest.TestCase):
    """The prose word must not become an entry — the half that loses data."""

    def test_the_prose_word_is_not_an_entry(self):
        got = names(
            "definition\n"
            "  \\<comment> \\<open>This specifies conditional fairness.  The rest\n"
            "      is generic to all forms of fairness.\\<close>\n"
            f"{DECL}")
        self.assertNotIn("is", got)
        self.assertEqual(got, ["transient"])


class TheEntrySpanCoversItsOwnName(unittest.TestCase):
    """Only the NAME changes here — the corpus diff moved no span at all.

    Pinned as an invariant rather than as arithmetic on `decl_end_line`,
    because that field does NOT behave the way inserting two lines suggests: a
    comment between the keyword and the name collapses `decl_end_line` (and
    `body_end_line`) onto the keyword line.  `WFair`'s `transient` reports
    `src 14..43, body 35..35` for a declaration that runs to 43.  That is
    pre-existing and independent of this fix — see `[decl-body-comment]` in
    `todo.md` — so it is not asserted either way here.
    """

    def test_the_span_contains_the_line_the_name_is_written_on(self):
        body = ("definition\n"
                "  \\<comment> \\<open>One\n"
                "      two.\\<close>\n"
                f"{DECL}")
        e = parse(body).entries[0]
        name_line = 4 + 3          # HEAD is 3 lines; DECL is the 4th of body
        self.assertEqual(e.name, "transient")
        self.assertLessEqual(e.src_start, name_line)
        self.assertGreaterEqual(e.thy_end, name_line)

    def test_the_name_is_the_same_with_and_without_the_comment(self):
        plain = parse(f"definition\n{DECL}").entries[0]
        commented = parse(
            "definition\n"
            "  \\<comment> \\<open>One\n"
            "      two.\\<close>\n"
            f"{DECL}").entries[0]
        self.assertEqual(plain.name, commented.name)
        self.assertEqual(commented.thy_line, plain.thy_line)


class TheGuardStillGuards(unittest.TestCase):
    r"""A redacted line is skipped free, but the scan is still bounded.

    `_NAME_LOOKAHEAD_LINES` exists so "a truncated/malformed file cannot run on
    looking for a name that is not there".  Formal comments no longer spend it
    — they are one lexer token however far they wrap — so `_NAME_SCAN_LINES`
    has to be what stops an unterminated one.
    """

    def test_an_unterminated_comment_does_not_invent_a_name(self):
        body = ("definition\n"
                "  \\<comment> \\<open>this cartouche never closes\n"
                + "\n".join(f"  filler line {k}" for k in range(60)))
        # No crash, no name invented out of the filler prose.
        got = names(body)
        self.assertNotIn("filler", got)
        self.assertNotIn("line", got)

    def test_blank_lines_still_spend_the_budget(self):
        # Four blank lines is past `_NAME_LOOKAHEAD_LINES`, so the name is not
        # claimed from that distance -- unchanged behaviour, pinned so the
        # "skipped without charge" rule cannot silently widen to blanks.
        self.assertNotIn("transient", names(
            "definition\n" + "\n" * 5 + DECL))


if __name__ == "__main__":
    unittest.main()
