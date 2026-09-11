package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphCitationReach {
  def run(): Unit = {
    test("test_citation_reach.AnUnknownClosureFiltersNothing.test_buffer_parsed_sections_keep_their_edges") {
      val sections = List(parse(GraphFixtures.text("test_citation_reach.A"), "A", "missing-A.thy"), parse(GraphFixtures.text("test_citation_reach.B"), "B", "missing-B.thy"))
      check(Usage_Graph.build_call_graph(sections).callers("base").contains("uses_base"))
    }
    test("test_citation_reach.AnUnknownClosureFiltersNothing.test_the_closure_is_reported_as_unknown") {
      val sections = List(parse(GraphFixtures.text("test_citation_reach.A"), "A", "missing-A.thy"), parse(GraphFixtures.text("test_citation_reach.B"), "B", "missing-B.thy"))
      // Public visibility must admit even an unknown target for an unreadable header.
      val closure = Reach.closure(sections)
      check(closure.visible("B", "A")); check(closure.visible("B", "not-in-project"))
    }
    test("test_citation_reach.FindCallersAgrees.test_a_name_the_project_never_declares_is_not_filtered") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(Reach.site_filter(sections, "nothing_declares_this")("C"))
      }
    }
    test("test_citation_reach.FindCallersAgrees.test_name_mode_restores_it") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(hits("name").contains("C"))
      }
    }
    test("test_citation_reach.FindCallersAgrees.test_the_importers_are_kept") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      equal(hits(), Set("B", "D"))
      }
    }
    test("test_citation_reach.FindCallersAgrees.test_the_invisible_theory_is_skipped") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(!hits().contains("C"))
      }
    }
    test("test_citation_reach.TheFlagIsOnEveryAttributingVerb.test_a_verb_that_attributes_nothing_does_not_carry_it") {
      List("deps" -> List("T"), "methods" -> Nil).foreach { case (verb, args) =>
      note(s"verb=$verb")
      equal(parsed(verb, args).str("reach"), None, verb)
      }
    }
    test("test_citation_reach.TheFlagIsOnEveryAttributingVerb.test_the_default_matches_the_library_default") {
      equal(Flags().reach, "closure"); equal(Reach.DEFAULT_MODE, Flags().reach)
    }
    test("test_citation_reach.TheFlagIsOnEveryAttributingVerb.test_the_flag_is_accepted") {
      List("callers" -> List("x"), "callees" -> List("x"), "refs" -> List("T"), "unused" -> Nil, "graph" -> Nil).foreach { case (verb, args) =>
      note(s"verb=$verb")
      equal(CLI.flags_of(parsed(verb, args)).reach, "closure", verb)
      equal(CLI.flags_of(parsed(verb, args ::: List("--reach", "name"))).reach, "name", verb)
      }
    }
    test("test_citation_reach.TheRuleOnlyDrops.test_closure_edges_are_a_subset") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(edges(g()).subsetOf(edges(g("name"))))
      }
    }
    test("test_citation_reach.TheRuleOnlyDrops.test_something_was_actually_dropped") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check((edges(g("name")) -- edges(g())).nonEmpty)
      }
    }
    test("test_citation_reach.UnusedMayHonestlyGrow.test_an_entry_only_cited_out_of_reach_becomes_unused") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(Usage.compute_unused(g("name", true), Set.empty).subsetOf(Usage.compute_unused(g("closure", true), Set.empty)))
      }
    }
    test("test_citation_reach.UnusedMayHonestlyGrow.test_base_is_live_because_b_really_cites_it") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(!Usage.compute_unused(g(derived = true), Set.empty).contains("base"))
      }
    }
    test("test_citation_reach.VisibilityDecidesAttribution.test_a_theory_cites_its_own_declaration") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(Reach.site_filter(sections, "base")("A"))
      }
    }
    test("test_citation_reach.VisibilityDecidesAttribution.test_a_theory_that_cannot_see_it_does_not") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(!callers().contains("looks_like"))
      }
    }
    test("test_citation_reach.VisibilityDecidesAttribution.test_a_transitive_importer_still_cites") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(callers().contains("via_b"))
      }
    }
    test("test_citation_reach.VisibilityDecidesAttribution.test_an_importer_still_cites") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(callers().contains("uses_base"))
      }
    }
    test("test_citation_reach.VisibilityDecidesAttribution.test_name_mode_restores_the_old_answer") {
      withProject(Seq("ROOT" -> "session Demo = HOL +\n theories A B C D\n") ++ List("A", "B", "C", "D").map(n => n + ".thy" -> GraphFixtures.text("test_citation_reach." + n))) { root =>
      val sections = load(root)
      def g(reach: String = "closure", derived: Boolean = false) = Usage_Graph.build_call_graph(sections, derived = derived, reach = reach)
      def callers(reach: String = "closure") = g(reach).callers.getOrElse("base", Set.empty)
      def hits(reach: String = "closure") = Usage.find_callers(sections, "base", reach = reach).map(_._1.theory).toSet
      check(callers("name").contains("looks_like"))
      }
    }
  }
}
