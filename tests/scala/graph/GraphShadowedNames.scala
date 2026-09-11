package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphShadowedNames {
  def run(): Unit = {
    test("test_shadowed_names.MethodInvocationIsNotACitation.test_attribute_argument_is_not_an_edge") {
      check(!Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_shadowed_names.MethodInvocationIsNotACitation.SNIPPET")))).callers("simp").contains("attribute"))
    }
    test("test_shadowed_names.MethodInvocationIsNotACitation.test_bare_method_is_not_an_edge") {
      check(!Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_shadowed_names.MethodInvocationIsNotACitation.SNIPPET")))).callers("simp").contains("plain"))
    }
    test("test_shadowed_names.MethodInvocationIsNotACitation.test_matches_the_oracle") {
      val sec = parse(GraphFixtures.text("test_shadowed_names.MethodInvocationIsNotACitation.SNIPPET"))
      equal(Usage_Graph.build_call_graph(List(sec)).callers, reference(List(sec)).callers)
    }
    test("test_shadowed_names.MethodInvocationIsNotACitation.test_method_modifier_is_not_an_edge") {
      check(!Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_shadowed_names.MethodInvocationIsNotACitation.SNIPPET")))).callers("simp").contains("modifier"))
    }
    test("test_shadowed_names.MethodInvocationIsNotACitation.test_term_position_is_an_edge") {
      check(Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_shadowed_names.MethodInvocationIsNotACitation.SNIPPET")))).callers("simp").contains("term_use"))
    }
    test("test_shadowed_names.PositionRule.test_attribute_block") {
      check(!use("  declare foo [simp]"))
    }
    test("test_shadowed_names.PositionRule.test_bare_method") {
      check(!use("  by simp"))
    }
    test("test_shadowed_names.PositionRule.test_cartouche_term_is_a_use") {
      check(use("lemma t: \\<open>simp = 0\\<close>"))
    }
    test("test_shadowed_names.PositionRule.test_derived_spelling_counts_only_when_asked") {
      check(use("  unfolding simp_def by auto",derived = true))
      check(!use("  unfolding simp_def by auto",derived = false))
    }
    test("test_shadowed_names.PositionRule.test_method_modifier") {
      check(!use("  apply (auto simp: refl)"))
    }
    test("test_shadowed_names.PositionRule.test_method_with_fact_list") {
      check(!use("  by (simp add: refl)"))
    }
    test("test_shadowed_names.PositionRule.test_quoted_term_is_a_use") {
      check(use("lemma t: \"simp = 0\""))
    }
    test("test_shadowed_names.PositionRule.test_rule_argument_is_a_citation") {
      check(use("  by (rule simp)"))
    }
    test("test_shadowed_names.PositionRule.test_using_is_a_citation") {
      check(use("  using simp by auto"))
    }
    test("test_shadowed_names.ShadowedNameIsStillAFact.test_citation_is_an_edge") {
      val sec = parse(GraphFixtures.text("test_shadowed_names.ShadowedNameIsStillAFact.SNIPPET"))
      equal(Usage_Graph.build_call_graph(List(sec)).callers("foo"), Set("cites_it"))
    }
    test("test_shadowed_names.ShadowedNameIsStillAFact.test_matches_the_oracle") {
      val sec = parse(GraphFixtures.text("test_shadowed_names.ShadowedNameIsStillAFact.SNIPPET"))
      equal(Usage_Graph.build_call_graph(List(sec)).callers, reference(List(sec)).callers)
    }
    test("test_shadowed_names.ShadowedNameIsStillAFact.test_name_is_a_graph_node") {
      val sec = parse(GraphFixtures.text("test_shadowed_names.ShadowedNameIsStillAFact.SNIPPET"))
      check(Usage_Graph.build_call_graph(List(sec)).all_names.contains("foo"))
    }
    test("test_shadowed_names.ShadowedNameIsStillAFact.test_unused_can_see_it") {
      val sec = parse(GraphFixtures.text("test_shadowed_names.ShadowedNameIsStillAFact.SNIPPET"))
      val g = Usage_Graph.build_call_graph(List(sec), derived = true)
      equal(Usage.compute_unused(g, Set.empty), Set("cites_it", "dead"))
    }
    test("test_shadowed_names.UnshadowedNamesAreUnaffected.test_numerals_and_short_names_still_excluded") {
      val sec = parse("theory T imports Main begin\nlemma x: \"True\" by simp\nlemma uses_x: \"True\" using x by simp\nend\n")
      check(!Usage_Graph.build_call_graph(List(sec)).all_names.contains("x"))
    }
    test("test_shadowed_names.UnshadowedNamesAreUnaffected.test_plain_name_keeps_position_blind_edges") {
      val sec = parse("theory T imports Main begin\ndefinition helper :: \"nat\" where \"helper = 0\"\nlemma base: \"helper = 0\" by (simp add: helper_def)\nlemma later: \"True\" using base by simp\nend\n")
      val g = Usage_Graph.build_call_graph(List(sec))
      equal(g.callers("helper"), Set("base"))
      equal(g.callers("base"), Set("later"))
      equal(g.callers, reference(List(sec)).callers)
    }
  }
}
