"""ADD #3 induction discipline — LiFtEr's source-visible induction inputs.

The extractor reduces an `induct` / `induction` method call to four source-level
scalars: how many terms it inducts on, how many variables it generalizes
(`arbitrary:`), whether it supplies a `rule:`, and whether that rule is a
`*.induct` recursion rule.  Every fixture below is a real invocation shape drawn
from the AFP (scripts/probe_induction.py), hand-computed here first and matched
by the code — the correctness discipline for a source approximation.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402
from isabelle_query import shape  # noqa: E402


def _inductions(snippet, name):
    """The induction invocations of entry `name` in `snippet`."""
    sec = section_from(snippet, "Ind")
    entry = next(e for e in sec.entries if e.name == name)
    return shape.scan_inductions(sec, entry)


class SplitArgs(unittest.TestCase):
    """A `"..."` compound term stays one token; its inner commas / parens / spaces
    are part of the term, not separators."""

    def test_quoted_compound_term_is_one_token(self):
        self.assertEqual(
            shape._split_induct_args('"(p, t)" arbitrary: p t'),
            ['"(p, t)"', "arbitrary:", "p", "t"])

    def test_bare_terms_split_on_whitespace(self):
        self.assertEqual(shape._split_induct_args("xss yss zss"),
                         ["xss", "yss", "zss"])

    def test_subscript_symbol_stays_glued(self):
        # `ys\<^sub>1` is a single term token (no interior whitespace).
        self.assertEqual(shape._split_induct_args(r"ys\<^sub>1 arbitrary: xs"),
                         [r"ys\<^sub>1", "arbitrary:", "xs"])


class ParseInduction(unittest.TestCase):
    """`(n_terms, n_arbitrary, has_rule, recursion_rule)` — hand-computed against
    real AFP argument lists."""

    def test_single_term(self):
        self.assertEqual(shape._parse_induction("xs"), (1, 0, False, False))

    def test_arbitrary_only(self):
        # `(induction arbitrary: a)` — 0 terms, 1 generalized var.
        self.assertEqual(shape._parse_induction("arbitrary: a"),
                         (0, 1, False, False))

    def test_term_and_two_arbitrary(self):
        self.assertEqual(shape._parse_induction("x arbitrary: y z"),
                         (1, 2, False, False))

    def test_library_rule_is_not_recursion(self):
        # `list_induct2` is a hand-picked library rule — no qualifying `.induct`
        # dot, so not a per-function recursion rule.
        self.assertEqual(shape._parse_induction("xss yss rule: list_induct2"),
                         (2, 0, True, False))

    def test_qualified_rule_is_recursion(self):
        # `merge_R1.induct` is the auto-generated schema of a recursive function.
        self.assertEqual(shape._parse_induction("x y rule: merge_R1.induct"),
                         (2, 0, True, True))

    def test_rule_only_no_terms(self):
        self.assertEqual(shape._parse_induction("rule: nat.induct"),
                         (0, 0, True, True))

    def test_arbitrary_then_recursion_rule(self):
        self.assertEqual(
            shape._parse_induction("a arbitrary: b rule: rose_tree.induct"),
            (1, 1, True, True))

    def test_custom_non_dotted_rule_is_not_recursion(self):
        self.assertEqual(
            shape._parse_induction("x arbitrary: y rule: my_rule"),
            (1, 1, True, False))

    def test_quoted_compound_term_counts_once(self):
        # `(induction "(p, t)" arbitrary: p t rule: tree_of_zipper.induct)`.
        self.assertEqual(
            shape._parse_induction(
                '"(p, t)" arbitrary: p t rule: tree_of_zipper.induct'),
            (1, 2, True, True))

    def test_three_terms(self):
        self.assertEqual(
            shape._parse_induction(r"s \<pi>s s' rule: path_to.induct"),
            (3, 0, True, True))

    def test_glued_modifier_value(self):
        # defensive: `rule:foo.induct` with no space still scores.
        self.assertEqual(shape._parse_induction("x rule:foo.induct"),
                         (1, 0, True, True))

    def test_empty_args(self):
        self.assertEqual(shape._parse_induction(""), (0, 0, False, False))


class ArgTextLocation(unittest.TestCase):
    """Locating and quote-aware paren-balancing the argument list from source."""

    def test_single_line(self):
        lines = ["  by (induction xs arbitrary: ys)"]
        self.assertEqual(shape._induction_arg_text(lines, 0),
                         "xs arbitrary: ys")

    def test_quoted_paren_does_not_close_early(self):
        # the `)` inside "(p, t)" must not be read as the method's close paren.
        lines = ['  by (induction "(p, t)" arbitrary: p t rule: f.induct)']
        self.assertEqual(shape._induction_arg_text(lines, 0),
                         '"(p, t)" arbitrary: p t rule: f.induct')

    def test_proof_introducer(self):
        lines = ["  proof (induction n rule: nat.induct)"]
        self.assertEqual(shape._induction_arg_text(lines, 0),
                         "n rule: nat.induct")

    def test_multiline_arbitrary_wraps(self):
        lines = ["  by (induction xs arbitrary: a b",
                 "        c d rule: foo.induct)"]
        self.assertEqual(shape._induction_arg_text(lines, 0),
                         "xs arbitrary: a b c d rule: foo.induct")

    def test_bare_method_has_no_args(self):
        self.assertIsNone(shape._induction_arg_text(["  by induct"], 0))

    def test_trailing_combinator_method_excluded(self):
        # `by (induct x) auto` — only the induction parens are the args.
        self.assertEqual(shape._induction_arg_text(["  by (induct x) auto"], 0),
                         "x")


class ScanInductions(unittest.TestCase):
    """End-to-end over a proof region: `scan_inductions` finds every invocation,
    including the two forms that are not `_scan_steps` steps — the one-line `by
    (induction ...)` and the `proof (induction ...)` block-opener."""

    THY = r'''theory Ind imports Main begin
lemma a: "P xs" by (induction xs arbitrary: ys)
lemma b: "Q n"
  proof (induction n rule: nat.induct)
  qed
lemma c: "R x" apply (induct_tac x) done
lemma d: "S" by simp
lemma e: "T" by induct
lemma f: "U n m"
  proof (induction n)
    case 0 show ?case by (induction m arbitrary: n rule: foo.induct) qed
  next
    case (Suc k) show ?case by simp
  qed
end
'''

    def test_one_line_by_induction(self):
        # `by (induction ...)` on the lemma line is NOT a step, but is found.
        self.assertEqual(_inductions(self.THY, "a"),
                         [shape.Induction(1, 1, False, False)])

    def test_proof_introducer_with_recursion_rule(self):
        # `proof (induction ...)` block-opener, also not a step.
        self.assertEqual(_inductions(self.THY, "b"),
                         [shape.Induction(1, 0, True, True)])

    def test_induct_tac(self):
        self.assertEqual(_inductions(self.THY, "c"),
                         [shape.Induction(1, 0, False, False)])

    def test_non_induction_proof_empty(self):
        self.assertEqual(_inductions(self.THY, "d"), [])

    def test_bare_induct_zero_counts(self):
        self.assertEqual(_inductions(self.THY, "e"),
                         [shape.Induction(0, 0, False, False)])

    def test_multiple_inductions_in_one_proof(self):
        # the outer `proof (induction n)` and the inner `by (induction m ...)`;
        # the `by simp` case contributes nothing.
        self.assertEqual(
            _inductions(self.THY, "f"),
            [shape.Induction(1, 0, False, False),
             shape.Induction(1, 1, True, True)])

    def test_statement_mentioning_induct_is_not_counted(self):
        # a lemma *about* something named with `induct` — no proof introducer, so
        # the anchor keeps it at zero (the precision guarantee).
        snippet = r'''theory Ind imports Main begin
lemma g: "induct_scheme x = y" by auto
end
'''
        self.assertEqual(_inductions(snippet, "g"), [])


class CensusReduction(unittest.TestCase):
    """Per-proof reduction `summarize_inductions` and its census-record columns."""

    def test_summarize_empty(self):
        s = shape.summarize_inductions([])
        self.assertEqual(s, (0, 0, 0, 0, 0))

    def test_summarize_takes_maxima_and_sums(self):
        inds = [shape.Induction(1, 2, True, True),
                shape.Induction(3, 0, True, False),
                shape.Induction(1, 1, False, False)]
        s = shape.summarize_inductions(inds)
        # n=3; terms_max=3; arbitrary_max=2; n_rule=2; n_recursion=1.
        self.assertEqual(s, shape.InductionSummary(3, 3, 2, 2, 1))

    def test_summary_record_carries_induction_columns(self):
        thy = r'''theory Ind imports Main begin
lemma f: "U n m"
  proof (induction n)
    case 0 show ?case by (induction m arbitrary: a b rule: foo.induct) qed
  next
    case (Suc k) show ?case by simp
  qed
end
'''
        sec = section_from(thy, "Ind")
        entry = next(e for e in sec.entries if e.name == "f")
        rec = shape.summary_record(shape.summarize(
            shape.analyze_proof(sec, entry)))
        # outer `induction n` (1 term) + inner `induction m arbitrary: a b
        # rule: foo.induct` (1 term, 2 arbitrary, recursion rule).
        self.assertEqual(rec["n_induct"], 2)
        self.assertEqual(rec["induct_terms_max"], 1)
        self.assertEqual(rec["induct_arbitrary_max"], 2)
        self.assertEqual(rec["induct_rule"], 1)
        self.assertEqual(rec["induct_recursion"], 1)

    def test_non_induction_proof_has_zero_columns(self):
        thy = r'''theory Ind imports Main begin
lemma d: "S"
  proof -
    show ?thesis by simp
  qed
end
'''
        sec = section_from(thy, "Ind")
        entry = next(e for e in sec.entries if e.name == "d")
        pm = shape.analyze_proof(sec, entry)
        rec = shape.summary_record(shape.summarize(pm))
        self.assertEqual(
            [rec["n_induct"], rec["induct_terms_max"], rec["induct_arbitrary_max"],
             rec["induct_rule"], rec["induct_recursion"]], [0, 0, 0, 0, 0])


if __name__ == "__main__":
    unittest.main()
