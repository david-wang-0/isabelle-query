r"""Entries whose name is also a proof method or attribute.

Isabelle's method/attribute namespace overlaps ordinary fact names: `insert`,
`trans`, `symmetric`, `cong`, `mono`, `cases` are all real Isabelle attributes
AND real AFP lemma names, and the table the router binds is a union over
sessions — `HOL-Eisbach` exports the methods of its own `Tests` theory, so
plain `foo` is in it too.

Such a name used to be dropped from the citation graph outright, which stopped
`by simp` minting an edge to a `definition simp` but also erased every genuine
citation of the entry: `lemma foo` was invisible to `callers` and never
appeared in `unused`.  A name cannot distinguish the two cases — `foo` and
`match` are the same collision with opposite right answers — so position does
it instead:

  * an explicit fact citation (`using foo`, `by (rule foo)`, `simp add: foo`)
    is a use;
  * a mention inside a quoted proposition or cartouche (`lemma "simp x = y"`)
    is a use — the token is a constant or statement text;
  * anything else (`by simp`, `apply (auto simp: h)`, `[symmetric]`) is the
    method or attribute of that name doing its job, and mints no edge.

Both directions are asserted here.  Testing only that `foo` is recovered would
pass for a change that simply deleted the filter, reintroducing the spurious
in-edge from every `by simp` in the corpus that the filter exists to prevent.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import (cli, section_from,  # noqa: E402
                     brute_force_call_graph)
from isabelle_query import graph  # noqa: E402


def graph_of(sec, derived=False):
    return cli._build_call_graph([sec], derived=derived)


class ShadowedNameIsStillAFact(unittest.TestCase):
    """The recovered half: a declared entry keeps its identity."""

    SNIPPET = r'''theory T imports Main begin
lemma foo: "True" by simp
lemma cites_it: "True" using foo by simp
lemma dead: "True" by simp
end
'''

    def test_name_is_a_graph_node(self):
        # `foo` is a method in the census union (HOL-Eisbach's Tests theory).
        sec = section_from(self.SNIPPET)
        self.assertIn("foo", graph_of(sec).all_names)

    def test_citation_is_an_edge(self):
        sec = section_from(self.SNIPPET)
        self.assertEqual(graph_of(sec).callers["foo"], {"cites_it"})

    def test_unused_can_see_it(self):
        sec = section_from(self.SNIPPET)
        g = cli._build_call_graph([sec], derived=True)
        # `foo` is cited, so only the two uncited lemmas are dead.
        self.assertEqual(cli._compute_unused(g), {"cites_it", "dead"})

    def test_matches_the_oracle(self):
        sec = section_from(self.SNIPPET)
        self.assertEqual(graph_of(sec).callers,
                         brute_force_call_graph([sec]).callers)


class MethodInvocationIsNotACitation(unittest.TestCase):
    """The guard: this is why the filter existed, and it must still hold."""

    SNIPPET = r'''theory T imports Main begin
definition simp :: "nat" where "simp = 0"
lemma plain: "True" by simp
lemma modifier: "True" by (auto simp: refl)
lemma attribute: "True" by (simp add: refl)
lemma term_use: "simp = 0" unfolding simp_def by simp
end
'''

    def test_bare_method_is_not_an_edge(self):
        # Without this, every `by simp` in the corpus cites this definition.
        self.assertNotIn("plain", graph_of(section_from(self.SNIPPET)).callers["simp"])

    def test_method_modifier_is_not_an_edge(self):
        self.assertNotIn("modifier",
                         graph_of(section_from(self.SNIPPET)).callers["simp"])

    def test_attribute_argument_is_not_an_edge(self):
        self.assertNotIn("attribute",
                         graph_of(section_from(self.SNIPPET)).callers["simp"])

    def test_term_position_is_an_edge(self):
        # The constant `simp` appearing in a statement IS a use of it.
        self.assertIn("term_use", graph_of(section_from(self.SNIPPET)).callers["simp"])

    def test_matches_the_oracle(self):
        sec = section_from(self.SNIPPET)
        self.assertEqual(graph_of(sec).callers,
                         brute_force_call_graph([sec]).callers)


class PositionRule(unittest.TestCase):
    """`_shadowed_uses_on_line` line by line — the whole decision in one place."""

    def use(self, line, name="simp", derived=False):
        return bool(graph._shadowed_uses_on_line(line, {name}, derived))

    def test_bare_method(self):
        self.assertFalse(self.use("  by simp"))

    def test_method_modifier(self):
        self.assertFalse(self.use("  apply (auto simp: refl)"))

    def test_method_with_fact_list(self):
        self.assertFalse(self.use("  by (simp add: refl)"))

    def test_attribute_block(self):
        self.assertFalse(self.use("  declare foo [simp]"))

    def test_using_is_a_citation(self):
        self.assertTrue(self.use("  using simp by auto"))

    def test_rule_argument_is_a_citation(self):
        self.assertTrue(self.use("  by (rule simp)"))

    def test_quoted_term_is_a_use(self):
        self.assertTrue(self.use('lemma t: "simp = 0"'))

    def test_cartouche_term_is_a_use(self):
        self.assertTrue(self.use(r'lemma t: \<open>simp = 0\<close>'))

    def test_derived_spelling_counts_only_when_asked(self):
        # `unused` asks the DECLARATION question, so `simp_def` keeps `simp`
        # alive there; the fact-level graph keeps them distinct.
        self.assertTrue(self.use("  unfolding simp_def by auto", derived=True))
        self.assertFalse(self.use("  unfolding simp_def by auto", derived=False))


class UnshadowedNamesAreUnaffected(unittest.TestCase):
    """The ordinary path must not have moved: no positional test applies."""

    def test_plain_name_keeps_position_blind_edges(self):
        sec = section_from(r'''theory T imports Main begin
definition helper :: "nat" where "helper = 0"
lemma base: "helper = 0" by (simp add: helper_def)
lemma later: "True" using base by simp
end
''')
        g = graph_of(sec)
        self.assertEqual(g.callers["helper"], {"base"})
        self.assertEqual(g.callers["base"], {"later"})
        self.assertEqual(g.callers, brute_force_call_graph([sec]).callers)

    def test_numerals_and_short_names_still_excluded(self):
        sec = section_from(r'''theory T imports Main begin
lemma x: "True" by simp
lemma uses_x: "True" using x by simp
end
''')
        # length-1 `x` is a term variable in nearly every proof: still dropped.
        self.assertNotIn("x", graph_of(sec).all_names)


if __name__ == "__main__":
    unittest.main()
