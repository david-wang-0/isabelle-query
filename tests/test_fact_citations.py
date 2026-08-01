"""Positional fact-citation extraction (`graph._cited_facts_on_line`).

Unlike the position-blind call graph ("which entries mention X"), this pulls
the facts a proof line *cites* in citation positions: the arguments of
`from`/`using`/`with`/`unfolding` and of the closing method (`rule`, `metis`,
`simp add:`, `[OF ...]`).  It is the shared primitive the width fan-in metric
(M5a) aggregates per step; a proposition is stripped first so a term that
merely contains a keyword is ignored, and an unclassifiable method shape trips
the `covered` flag for the coverage statistic.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402,F401  (puts src/ on the path)
from isabelle_query import graph  # noqa: E402


def facts(line):
    return graph._cited_facts_on_line(line)


class FromUsingWith(unittest.TestCase):
    def test_from_prefix(self):
        # single-char `a` (an assumes label) IS a fact in a citation position
        self.assertEqual(facts('from a have p1: "P" by blast'), ({"a"}, True))

    def test_using_suffix(self):
        self.assertEqual(facts('moreover have "P" using a by simp'),
                         ({"a"}, True))

    def test_unfolding_list(self):
        self.assertEqual(facts("unfolding foo_def bar_def by auto"),
                         ({"foo_def", "bar_def"}, True))

    def test_label_is_introduced_not_cited(self):
        # `have outer:` introduces the fact `outer`; it is not a citation.
        self.assertEqual(facts('have outer: "x = x"'), (set(), True))

    def test_keyword_inside_proposition_is_ignored(self):
        # `using` appears only inside the quoted term, not as a real citation.
        self.assertEqual(facts('have "P using Q" by simp'), (set(), True))


class MethodArguments(unittest.TestCase):
    def test_rule_bare_arg(self):
        self.assertEqual(facts('show "P \\<and> P" by (rule conjI)'),
                         ({"conjI"}, True))

    def test_rule_local_label(self):
        self.assertEqual(facts('show "x = x" by (rule outer)'),
                         ({"outer"}, True))

    def test_metis_multiple(self):
        self.assertEqual(facts("by (metis a b c)"), ({"a", "b", "c"}, True))

    def test_simp_add_marker(self):
        self.assertEqual(facts("by (simp add: f_def g_def)"),
                         ({"f_def", "g_def"}, True))

    def test_auto_simp_marker(self):
        self.assertEqual(facts("by (auto simp: h)"), ({"h"}, True))

    def test_of_attribute(self):
        self.assertEqual(facts("by (rule r[OF h])"), ({"r", "h"}, True))

    def test_bare_method_no_facts(self):
        self.assertEqual(facts("by simp"), (set(), True))
        self.assertEqual(facts("by blast"), (set(), True))

    def test_induction_variable_is_not_a_fact(self):
        # `induct n` — n is a term variable, not a fact; still fully covered.
        self.assertEqual(facts("by (induct n)"), (set(), True))


class ChainedCiteKeywords(unittest.TestCase):
    """A cite keyword (`unfolding`, `using`, ...) chained after another must end
    the prior fact list and start its own — never be swallowed as a fact."""

    def test_using_then_unfolding(self):
        # the AFP's single most common shape (`using X unfolding Y .`): the old
        # walk swallowed `unfolding` as a fact of the `using` list.
        self.assertEqual(facts("using lnull_lappend unfolding lnull_def ."),
                         ({"lnull_lappend", "lnull_def"}, True))

    def test_unfolding_then_using(self):
        self.assertEqual(facts("unfolding a_def using assms by auto"),
                         ({"a_def", "assms"}, True))

    def test_from_then_with(self):
        self.assertEqual(facts('from a with b have "P"'), ({"a", "b"}, True))


class AttributeBrackets(unittest.TestCase):
    """`name[attr ...]` — only the *fact-composing* attributes (`OF`, `THEN`,
    `unfolded`, `folded`, `simplified`) contribute cited facts; the term- and
    flag-valued ones (`of`, `where`, `symmetric`, declarations) contribute
    nothing.  The bracket contents used to leak as bare rule arguments."""

    def test_of_terms_do_not_leak(self):
        self.assertEqual(facts("by (rule foo[of a b])"), ({"foo"}, True))

    def test_where_terms_do_not_leak(self):
        self.assertEqual(facts("by (rule foo[where x=y])"), ({"foo"}, True))

    def test_and_in_where_does_not_leak(self):
        self.assertEqual(facts("by (rule foo[where f=g and bound=h])"),
                         ({"foo"}, True))

    def test_lone_greek_term_does_not_leak(self):
        # `[of \<phi> \<psi>]` — term instantiation, the top leak-#2 source.
        self.assertEqual(facts("by (rule foo[of \\<phi> \\<psi>])"),
                         ({"foo"}, True))

    def test_symmetric_flag_does_not_leak(self):
        self.assertEqual(facts("by (simp add: foo[symmetric])"),
                         ({"foo"}, True))

    def test_of_still_keeps_the_head_fact(self):
        self.assertEqual(facts("unfolding foo_def[symmetric] by simp"),
                         ({"foo_def"}, True))

    def test_OF_args_are_facts(self):
        self.assertEqual(facts("by (rule r[OF h])"), ({"r", "h"}, True))

    def test_OF_then_THEN_multi_attr(self):
        self.assertEqual(facts("by (rule r[OF a, THEN b])"),
                         ({"r", "a", "b"}, True))

    def test_unfolded_arg_is_a_fact(self):
        self.assertEqual(facts("by (simp add: foo[unfolded bar_def])"),
                         ({"foo", "bar_def"}, True))

    def test_simplified_rule_arg_is_a_fact(self):
        self.assertEqual(facts("by (rule foo[simplified bar])"),
                         ({"foo", "bar"}, True))

    def test_nested_OF_brackets(self):
        self.assertEqual(facts("by (rule r[OF s[OF t]])"),
                         ({"r", "s", "t"}, True))

    def test_wildcard_placeholder_in_OF_is_not_a_fact(self):
        # `[OF _ h]` — the `_` is a term placeholder, only `h` is a fact.
        self.assertEqual(facts("by (rule inj_on_subset[OF _ subset_UNIV])"),
                         ({"inj_on_subset", "subset_UNIV"}, True))


class NonIdentifierTokens(unittest.TestCase):
    """Digit-led and prime-led tokens are never Isabelle fact names — numerals /
    numeric labels / statement-text artifacts and type variables — so they never
    count, even in a citation position (idf would weight a df-1 artifact maximally)."""

    def test_numeric_labels_are_not_facts(self):
        # numeric back-references (`from 1 2 3`) are not the named premises M5a
        # counts, and digit-led tokens are never valid fact names anyway.
        self.assertEqual(facts('from 31 32 33 have c: "P" by force'),
                         (set(), True))

    def test_type_variable_is_not_a_fact(self):
        self.assertEqual(facts("using foo 'a bar by simp"), ({"foo", "bar"}, True))

    def test_dotted_numeral_artifacts_are_not_facts(self):
        self.assertEqual(facts("using lem 0.. 1. by auto"), ({"lem"}, True))

    def test_cite_list_head_with_attr_keeps_following_facts(self):
        # `using assms[OF x] that` — the old walk stopped dead at `[`, losing
        # both the OF premise `x` and the trailing `that`.
        self.assertEqual(facts("using assms[OF x] that by blast"),
                         ({"assms", "x", "that"}, True))

    def test_marker_list_with_attr(self):
        self.assertEqual(facts("by (auto simp: foo[of x] bar)"),
                         ({"foo", "bar"}, True))


class SymbolNamedFactsKept(unittest.TestCase):
    """Facts whose names carry Isabelle symbol tokens (`\\<^sub>`, Greek
    letters) are genuine citations — the extractor must keep them (they are NOT
    parse junk, despite failing a plain ``[A-Za-z_]``-lead filter)."""

    def test_subscripted_fact(self):
        self.assertEqual(facts("by (simp add: blindable\\<^sub>h.map_id)"),
                         ({"blindable\\<^sub>h.map_id"}, True))

    def test_greek_led_def(self):
        self.assertEqual(facts("unfolding \\<phi>_def by simp"),
                         ({"\\<phi>_def"}, True))


class RealAfpComposite(unittest.TestCase):
    def test_where_and_dest_of_line(self):
        # ADS_Functor/Coinductive-style line: a `[where ... and ...]` term block
        # on the rule, then `split:`/`simp add:`/`dest:` fact markers with an
        # `[OF ...]` inside the last.  Only the genuine facts survive.
        line = ('by(rule monotone_if_bot[where f="\\<lambda>xs. g xs" '
                'and bound=LNil])(auto split: llist.split '
                'simp add: not_lnull LCons_conv dest: monotoneD[OF mono])')
        self.assertEqual(facts(line), (
            {"monotone_if_bot", "llist.split", "not_lnull", "LCons_conv",
             "monotoneD", "mono"}, True))


class Coverage(unittest.TestCase):
    def test_unknown_method_with_bare_args_is_uncovered(self):
        # A method that is neither a known rule-method nor a known term-arg
        # method, with bare args, can't be classified — flag it.
        found, covered = facts("by (my_custom_tactic foo)")
        self.assertFalse(covered)

    def test_known_shapes_stay_covered(self):
        self.assertTrue(facts("by (simp add: f_def)")[1])
        self.assertTrue(facts("using a by (rule r)")[1])


if __name__ == "__main__":
    unittest.main()
