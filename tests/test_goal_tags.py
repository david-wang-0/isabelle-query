r"""A goal entry is tagged with the keyword its author wrote [goal-tags].

Issue #22: `corollary` and `proposition` were folded into `LEMMA`, so a
dead-code audit could not tell a headline corollary from scaffolding.  To
Isabelle the four goal keywords are one command (Pure.thy:54, 574-577); the
tag records the author's choice, which is all that tells them apart.

`summary`'s T column counts the three that announce a result; L is `lemma`.
Every goal tag still reads as a goal: it has a statement, it is citable.

A goal command a theory header declares (`AOT_theorem :: thy_goal`) is
`GOAL`: its kind says it has a proof, not which of the two it is, so
`summary` counts it in neither column and says so in a footnote.
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from, tags_by_name  # noqa: E402
from isabelle_query import commands  # noqa: E402
from isabelle_query.model import CmdFlags  # noqa: E402
from isabelle_query.premises import statement_of  # noqa: E402

GOALS = """\
theory Goals
  imports Main
begin

definition d :: nat where "d = 0"

lemma base_l: "d = 0"
  by (simp add: d_def)

theorem main_t: "d = 0"
  by (rule base_l)

corollary cor_c:
  assumes p: "x > (0::nat)"
  shows "x \\<noteq> 0"
  using p by simp

proposition prop_p: "True"
  by simp

end
"""


class GoalTags(unittest.TestCase):

    def setUp(self):
        self.sec = section_from(GOALS, theory="Goals")

    def out(self, fn, *args, **kw):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn(*args, **kw)
        return buf.getvalue()

    def test_each_keyword_keeps_its_tag(self):
        self.assertEqual(tags_by_name(self.sec), {
            "d": "DEF", "base_l": "LEMMA", "main_t": "THEOREM",
            "cor_c": "COROLLARY", "prop_p": "PROPOSITION"})

    def test_a_corollary_has_a_statement(self):
        by_name = {e.name: e for e in self.sec.entries}
        st = statement_of(self.sec, by_name["cor_c"])
        self.assertIsNotNone(st)
        self.assertEqual([p.label for p in st.premises], ["p"])

    def test_summary_counts_results_under_T(self):
        row = [ln for ln in self.out(commands.cmd_summary, [self.sec])
               .splitlines() if ln.startswith("| Goals")]
        self.assertEqual(row, ["| Goals | 21 | 1 | 1 | 3 | d, main_t, cor_c, prop_p |"])

    def test_unused_names_the_keyword(self):
        rows = [ln.split()[:2] for ln in
                self.out(commands.cmd_unused, [self.sec], CmdFlags())
                .splitlines() if ln[:1].isupper() and ".." in ln]
        self.assertEqual(rows, [["THEOREM", "main_t"], ["COROLLARY", "cor_c"],
                                ["PROPOSITION", "prop_p"]])


CUSTOM = """\
theory Cust
  imports Main
  keywords "my_thm" :: thy_goal
begin

lemma base_l: "True"
  by simp

my_thm cust_g: "True"
  by (rule base_l)

end
"""


class HeaderDeclaredGoal(unittest.TestCase):

    def setUp(self):
        self.sec = section_from(CUSTOM, theory="Cust")

    def test_it_is_a_goal_not_a_theorem(self):
        self.assertEqual(tags_by_name(self.sec),
                         {"base_l": "LEMMA", "cust_g": "GOAL"})

    def test_summary_counts_it_in_a_footnote(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_summary([self.sec])
        out = buf.getvalue().splitlines()
        self.assertIn("| Cust | 12 | 0 | 1 | 0 | base_l |", out)
        self.assertEqual(out[-1], "1 entry is a goal of a header-declared "
                                  "command (tag GOAL), counted in neither L "
                                  "nor T.")

    def test_no_footnote_without_one(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_summary([section_from(GOALS, theory="Goals")])
        self.assertNotIn("GOAL", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
