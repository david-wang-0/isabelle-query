r"""`show` must not print a line twice when the proof shares the statement line.

The default render is "the declaration, plus the first line of the proof, plus
a count of the rest" — which assumes the proof starts on a line of its own.  For
`lemma a: "P" by simp` it does not, so the declaration slice and the
"first proof line" slice were the same line and it printed twice.

The second half of issue #5; the first half is the shape scanner
(`test_shape_inline.py`).  Both spellings of one proof should read the same, and
`show` is where a user notices.

Every case here is checked by counting occurrences, not by an `assertIn` — the
bug printed the right text, just twice, so a containment assertion passes on
broken output.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

THY = """theory Show imports Main begin

lemma one_liner: "P a" by simp

lemma next_line: "P b"
  by simp

lemma shows_form:
  assumes "A g"
  shows "P g" by simp

lemma term_line:
  "P c" by auto

lemma structured: "P i"
proof -
  have "R i" by simp
  show "P i" by simp
qed

end
"""


class NoDuplicatedLines(unittest.TestCase):
    def setUp(self):
        self.sec = section_from(THY, "Show")

    def _render(self, name):
        entry = next(e for e in self.sec.entries if e.name == name)
        return cli.render_entry(self.sec, entry)

    def _body(self, name):
        """Rendered lines below the `--- header ---`."""
        return self._render(name).splitlines()[1:]

    def test_one_liner_prints_its_line_once(self):
        body = self._body("one_liner")
        self.assertEqual(body.count('lemma one_liner: "P a" by simp'), 1)
        self.assertEqual(len(body), 1)

    def test_declaration_ending_in_its_proof_prints_once(self):
        # The proof is on the LAST line of a multi-line declaration, so the
        # duplicate is the `shows` line rather than the `lemma` line — a fix
        # keyed on `proof_line == thy_line` would leave this one broken.
        body = self._body("shows_form")
        self.assertEqual(body.count('  shows "P g" by simp'), 1)
        self.assertEqual(len(body), 3)

    def test_proof_on_its_own_line_still_shows_it(self):
        # The complement: suppressing the first proof line unconditionally
        # would silently drop the proof from every ordinary render.
        body = self._body("next_line")
        self.assertEqual(body.count("  by simp"), 1)
        self.assertIn('lemma next_line: "P b"', body)

    def test_a_bare_statement_term_line_still_shows_its_proof(self):
        # `lemma term_line:` / `"P c" by auto` — the proof line is past
        # `decl_end_line`, so it is a genuine extra line and must be printed.
        body = self._body("term_line")
        self.assertEqual(body.count('  "P c" by auto'), 1)
        self.assertEqual(len(body), 2)

    def test_structured_proof_is_unchanged(self):
        body = self._body("structured")
        self.assertEqual(body.count('lemma structured: "P i"'), 1)
        self.assertEqual(body.count("proof -"), 1)
        self.assertIn("  [+3 more proof lines]", body)

    def test_every_rendered_line_is_distinct_across_the_fixture(self):
        """A blunt catch-all: no entry in the fixture repeats a source line."""
        for name in ("one_liner", "next_line", "shows_form", "term_line",
                     "structured"):
            body = [ln for ln in self._body(name) if ln.strip()]
            self.assertEqual(len(body), len(set(body)), f"{name} repeats a line")


if __name__ == "__main__":
    unittest.main()
