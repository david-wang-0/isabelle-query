"""Proof-method scanner (`query methods` / `query method NAME`).

The scanner is the complement of the citation router: the `PROOF_METHODS`
tokens the router declines to treat as call-graph edges are exactly the
method uses tallied here.  Precision comes from anchoring on the three pure
proof keywords (`by` / `apply` / `proof`) — a method-namespace token that is
really a term variable (`N`, `order`) is only counted when it sits in
introducer position, where it is unambiguously the method.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402


def scan(snippet, only=None):
    return cli._scan_methods([section_from(snippet)], only=only)


class ScanMethods(unittest.TestCase):
    def test_introducer_forms_are_counted(self):
        # by / apply / proof, bare and parenthesised
        snippet = r'''theory T imports Main begin
lemma a: "P" by simp
lemma b: "Q" apply auto done
lemma c: "R" proof (induct n) qed
lemma d: "S" by (blast intro: foo)
end
'''
        counts, _ = scan(snippet)
        self.assertEqual(counts["simp"], 1)
        self.assertEqual(counts["auto"], 1)
        self.assertEqual(counts["induct"], 1)
        self.assertEqual(counts["blast"], 1)

    def test_method_name_as_a_variable_is_not_counted(self):
        # `N` and `order` are in the method namespace but here they are term
        # variables/constants in the *statement*, never after by/apply/proof.
        # A naive token tally would over-count them; the introducer anchor
        # keeps them at zero — the whole point of the precision design.
        snippet = r'''theory T imports Main begin
lemma foo: "N = order" by simp
lemma bar: "order N = N" by auto
end
'''
        counts, _ = scan(snippet)
        self.assertEqual(counts["simp"], 1)
        self.assertEqual(counts["auto"], 1)
        self.assertNotIn("N", counts)
        self.assertNotIn("order", counts)

    def test_combinator_tail_is_undercounted_not_miscounted(self):
        # documented trade-off: only the *initial* method of an introducer is
        # counted, so the trailing `auto` of `by (induct x) auto` is missed.
        # Never over-counted, which is what keeps the ranking trustworthy.
        snippet = r'''theory T imports Main begin
lemma a: "P" by (induct x) auto
end
'''
        counts, _ = scan(snippet)
        self.assertEqual(counts["induct"], 1)
        self.assertNotIn("auto", counts)

    def test_prose_and_comments_are_skipped(self):
        # an `apply simp` mentioned in a text block / preamble is prose, not a
        # method use — the live-source filter excludes it.
        snippet = r'''theory T imports Main begin
text \<open>We then apply simp and auto to finish.\<close>
lemma q: "P" by blast
end
'''
        counts, _ = scan(snippet)
        self.assertEqual(counts["blast"], 1)
        self.assertNotIn("simp", counts)
        self.assertNotIn("auto", counts)

    def test_located_form_reports_owning_entry(self):
        snippet = r'''theory T imports Main begin
lemma headline: "P" by simp
lemma other: "Q" by auto
end
'''
        counts, located = scan(snippet, only="simp")
        self.assertEqual(counts["simp"], 1)
        self.assertEqual(len(located), 1)
        theory, line_no, owner, text = located[0]
        self.assertEqual(theory, "Test")
        self.assertIsNotNone(owner)
        self.assertEqual(owner.name, "headline")
        self.assertIn("by simp", text)

    def test_no_methods_yields_empty(self):
        snippet = r'''theory T imports Main begin
definition foo :: "nat" where "foo = 0"
end
'''
        counts, located = scan(snippet, only="simp")
        self.assertEqual(len(counts), 0)
        self.assertEqual(located, [])


if __name__ == "__main__":
    unittest.main()
