package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphFactCitations {
  def run(): Unit = {
    test("test_fact_citations.AttributeBrackets.test_OF_args_are_facts") {
      equal(Usage_Graph.cited_facts_on_line("by (rule r[OF h])"), (Set("r", "h"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_OF_then_THEN_multi_attr") {
      equal(Usage_Graph.cited_facts_on_line("by (rule r[OF a, THEN b])"), (Set("r", "a", "b"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_and_in_where_does_not_leak") {
      equal(Usage_Graph.cited_facts_on_line("by (rule foo[where f=g and bound=h])"), (Set("foo"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_lone_greek_term_does_not_leak") {
      equal(Usage_Graph.cited_facts_on_line("by (rule foo[of \\<phi> \\<psi>])"), (Set("foo"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_nested_OF_brackets") {
      equal(Usage_Graph.cited_facts_on_line("by (rule r[OF s[OF t]])"), (Set("r", "s", "t"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_of_still_keeps_the_head_fact") {
      equal(Usage_Graph.cited_facts_on_line("unfolding foo_def[symmetric] by simp"), (Set("foo_def"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_of_terms_do_not_leak") {
      equal(Usage_Graph.cited_facts_on_line("by (rule foo[of a b])"), (Set("foo"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_simplified_rule_arg_is_a_fact") {
      equal(Usage_Graph.cited_facts_on_line("by (rule foo[simplified bar])"), (Set("foo", "bar"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_symmetric_flag_does_not_leak") {
      equal(Usage_Graph.cited_facts_on_line("by (simp add: foo[symmetric])"), (Set("foo"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_unfolded_arg_is_a_fact") {
      equal(Usage_Graph.cited_facts_on_line("by (simp add: foo[unfolded bar_def])"), (Set("foo", "bar_def"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_where_terms_do_not_leak") {
      equal(Usage_Graph.cited_facts_on_line("by (rule foo[where x=y])"), (Set("foo"), true))
    }
    test("test_fact_citations.AttributeBrackets.test_wildcard_placeholder_in_OF_is_not_a_fact") {
      equal(Usage_Graph.cited_facts_on_line("by (rule inj_on_subset[OF _ subset_UNIV])"), (Set("inj_on_subset", "subset_UNIV"), true))
    }
    test("test_fact_citations.ChainedCiteKeywords.test_from_then_with") {
      equal(Usage_Graph.cited_facts_on_line("from a with b have \"P\""), (Set("a", "b"), true))
    }
    test("test_fact_citations.ChainedCiteKeywords.test_unfolding_then_using") {
      equal(Usage_Graph.cited_facts_on_line("unfolding a_def using assms by auto"), (Set("a_def", "assms"), true))
    }
    test("test_fact_citations.ChainedCiteKeywords.test_using_then_unfolding") {
      equal(Usage_Graph.cited_facts_on_line("using lnull_lappend unfolding lnull_def ."), (Set("lnull_lappend", "lnull_def"), true))
    }
    test("test_fact_citations.Coverage.test_known_shapes_stay_covered") {
      check(Usage_Graph.cited_facts_on_line("by (simp add: f_def)")._2)
      check(Usage_Graph.cited_facts_on_line("using a by (rule r)")._2)
    }
    test("test_fact_citations.Coverage.test_unknown_method_with_bare_args_is_uncovered") {
      val (found, covered) = Usage_Graph.cited_facts_on_line("by (my_custom_tactic foo)")
      check(!covered)
    }
    test("test_fact_citations.FromUsingWith.test_from_prefix") {
      equal(Usage_Graph.cited_facts_on_line("from a have p1: \"P\" by blast"), (Set("a"), true))
    }
    test("test_fact_citations.FromUsingWith.test_keyword_inside_proposition_is_ignored") {
      equal(Usage_Graph.cited_facts_on_line("have \"P using Q\" by simp"), (Set.empty[String], true))
    }
    test("test_fact_citations.FromUsingWith.test_label_is_introduced_not_cited") {
      equal(Usage_Graph.cited_facts_on_line("have outer: \"x = x\""), (Set.empty[String], true))
    }
    test("test_fact_citations.FromUsingWith.test_unfolding_list") {
      equal(Usage_Graph.cited_facts_on_line("unfolding foo_def bar_def by auto"), (Set("foo_def", "bar_def"), true))
    }
    test("test_fact_citations.FromUsingWith.test_using_suffix") {
      equal(Usage_Graph.cited_facts_on_line("moreover have \"P\" using a by simp"), (Set("a"), true))
    }
    test("test_fact_citations.MethodArguments.test_auto_simp_marker") {
      equal(Usage_Graph.cited_facts_on_line("by (auto simp: h)"), (Set("h"), true))
    }
    test("test_fact_citations.MethodArguments.test_bare_method_no_facts") {
      equal(Usage_Graph.cited_facts_on_line("by simp"), (Set.empty[String], true))
      equal(Usage_Graph.cited_facts_on_line("by blast"), (Set.empty[String], true))
    }
    test("test_fact_citations.MethodArguments.test_induction_variable_is_not_a_fact") {
      equal(Usage_Graph.cited_facts_on_line("by (induct n)"), (Set.empty[String], true))
    }
    test("test_fact_citations.MethodArguments.test_metis_multiple") {
      equal(Usage_Graph.cited_facts_on_line("by (metis a b c)"), (Set("a", "b", "c"), true))
    }
    test("test_fact_citations.MethodArguments.test_of_attribute") {
      equal(Usage_Graph.cited_facts_on_line("by (rule r[OF h])"), (Set("r", "h"), true))
    }
    test("test_fact_citations.MethodArguments.test_rule_bare_arg") {
      equal(Usage_Graph.cited_facts_on_line("show \"P \\<and> P\" by (rule conjI)"), (Set("conjI"), true))
    }
    test("test_fact_citations.MethodArguments.test_rule_local_label") {
      equal(Usage_Graph.cited_facts_on_line("show \"x = x\" by (rule outer)"), (Set("outer"), true))
    }
    test("test_fact_citations.MethodArguments.test_simp_add_marker") {
      equal(Usage_Graph.cited_facts_on_line("by (simp add: f_def g_def)"), (Set("f_def", "g_def"), true))
    }
    test("test_fact_citations.NonIdentifierTokens.test_cite_list_head_with_attr_keeps_following_facts") {
      equal(Usage_Graph.cited_facts_on_line("using assms[OF x] that by blast"), (Set("assms", "x", "that"), true))
    }
    test("test_fact_citations.NonIdentifierTokens.test_dotted_numeral_artifacts_are_not_facts") {
      equal(Usage_Graph.cited_facts_on_line("using lem 0.. 1. by auto"), (Set("lem"), true))
    }
    test("test_fact_citations.NonIdentifierTokens.test_marker_list_with_attr") {
      equal(Usage_Graph.cited_facts_on_line("by (auto simp: foo[of x] bar)"), (Set("foo", "bar"), true))
    }
    test("test_fact_citations.NonIdentifierTokens.test_numeric_labels_are_not_facts") {
      equal(Usage_Graph.cited_facts_on_line("from 31 32 33 have c: \"P\" by force"), (Set.empty[String], true))
    }
    test("test_fact_citations.NonIdentifierTokens.test_type_variable_is_not_a_fact") {
      equal(Usage_Graph.cited_facts_on_line("using foo 'a bar by simp"), (Set("foo", "bar"), true))
    }
    test("test_fact_citations.RealAfpComposite.test_where_and_dest_of_line") {
      val line = "by(rule monotone_if_bot[where f=\"\\<lambda>xs. g xs\" and bound=LNil])(auto split: llist.split simp add: not_lnull LCons_conv dest: monotoneD[OF mono])"
      equal(Usage_Graph.cited_facts_on_line(line), (Set("monotone_if_bot", "llist.split", "not_lnull", "LCons_conv", "monotoneD", "mono"), true))
    }
    test("test_fact_citations.SymbolNamedFactsKept.test_greek_led_def") {
      equal(Usage_Graph.cited_facts_on_line("unfolding \\<phi>_def by simp"), (Set("\\<phi>_def"), true))
    }
    test("test_fact_citations.SymbolNamedFactsKept.test_subscripted_fact") {
      equal(Usage_Graph.cited_facts_on_line("by (simp add: blindable\\<^sub>h.map_id)"), (Set("blindable\\<^sub>h.map_id"), true))
    }
  }
}
