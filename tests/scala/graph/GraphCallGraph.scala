package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphCallGraph {
  def run(): Unit = {
    test("test_call_graph.CallGraphEdges.test_bare_reference") {
      val sec = parse("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"foo = foo\" by simp\nend\n")
      val g = checkedGraph(List(sec))
      check(g.callers("foo").contains("bar"))
      check(g.callees("bar").contains("foo"))
    }
    test("test_call_graph.CallGraphEdges.test_comment_mention_is_not_a_call") {
      val sec = parse("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"True\"\n  \\<comment> \\<open>this step is by analogy with foo\\<close>\n  by auto\nend\n")
      val g = checkedGraph(List(sec))
      equal(g.callers("foo"), Set.empty[String])
    }
    test("test_call_graph.CallGraphEdges.test_cross_theory_reference") {
      val secs = Map("A" -> "theory A imports Main begin\ndefinition base :: \"nat\" where \"base = 0\"\nend\n", "B" -> "theory B imports A begin\nlemma uses_base: \"base = base\" by (simp add: base_def)\nend\n").toList.map { case (name, source) => parse(source, name, name + ".thy") }
      val g = checkedGraph(secs)
      check(g.callers("base").contains("uses_base"))
    }
    test("test_call_graph.CallGraphEdges.test_definition_site_excluded") {
      val sec = parse("theory T imports Main begin\nfun ff :: \"nat \\<Rightarrow> nat\" where\n\"ff 0 = 0\" |\n\"ff (Suc n) = ff n\"\nend\n")
      val g = checkedGraph(List(sec))
      check(!g.callers.getOrElse("ff",Set.empty[String]).contains("ff"))
    }
    test("test_call_graph.CallGraphEdges.test_prose_mention_is_not_a_call") {
      val sec = parse("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\ntext \\<open>Here we discuss foo at length in prose.\\<close>\nlemma bar: \"True\" by auto\nend\n")
      val g = checkedGraph(List(sec))
      equal(g.callers("foo"), Set.empty[String])
    }
    test("test_call_graph.CallGraphEdges.test_quoted_special_name_reference") {
      val sec = parse("theory T imports Main begin\nlemma \"rule-x:1\": \"True\" by auto\nlemma usesit: \"True\" using \"rule-x:1\" by auto\nend\n")
      val g = checkedGraph(List(sec))
      equal(g.callers("rule-x:1"), Set("usesit"))
    }
    test("test_call_graph.CallGraphEdges.test_special_name_substring_is_not_a_call") {
      val sec = parse("theory T imports Main begin\nlemma \"num:1\": \"True\" by auto\nlemma \"eq-num:1\": \"True\" by auto\nlemma usesit: \"True\" using \"eq-num:1\" by auto\nend\n")
      val g = checkedGraph(List(sec))
      equal(g.callers("eq-num:1"), Set("usesit"))
      equal(g.callers.getOrElse("num:1",Set.empty[String]), Set.empty[String])
    }
    test("test_call_graph.CallGraphEdges.test_substring_is_not_a_call") {
      val sec = parse("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\ndefinition foobar :: \"nat\" where \"foobar = 1\"\nlemma bar: \"foobar = foobar\" using foo_def by simp\nend\n")
      val g = checkedGraph(List(sec))
      check(!g.callers("foo").contains("bar"))
      check(g.callers("foobar").contains("bar"))
    }
    test("test_call_graph.CallGraphEdges.test_symbolic_name_not_glued_into_longer_symbol") {
      val sec = parse("theory T imports Main begin\ndefinition \\<gamma> :: \"nat\" where \"\\<gamma> = 0\"\ndefinition \\<gamma>\\<^sub>1 :: \"nat\" where \"\\<gamma>\\<^sub>1 = 1\"\nlemma usesit: \"\\<gamma>\\<^sub>1 = \\<gamma>\\<^sub>1\" by simp\nend\n")
      val g = checkedGraph(List(sec))
      check(g.callers("\\<gamma>\\<^sub>1").contains("usesit"))
      equal(g.callers.getOrElse("\\<gamma>",Set.empty[String]), Set.empty[String])
    }
    test("test_call_graph.CallGraphEdges.test_symbolic_name_reference") {
      val sec = parse("theory T imports Main begin\ndefinition \\<psi> :: \"nat\" where \"\\<psi> = 0\"\nlemma uses_psi: \"\\<psi> = \\<psi>\" by (simp add: \\<psi>_def)\nend\n")
      val g = checkedGraph(List(sec))
      check(g.callers("\\<psi>").contains("uses_psi"))
    }
    test("test_call_graph.DropShortNames.test_drop_upto_two_drops_two_char") {
      val sec = parse(GraphFixtures.text("test_call_graph.DropShortNames.SNIPPET"))
      val g = Usage_Graph.build_call_graph(List(sec), drop_upto = 2)
      check(!g.all_names.contains("f"))
      check(!g.all_names.contains("ff"))
    }
    test("test_call_graph.DropShortNames.test_drop_upto_zero_keeps_single_char") {
      val sec = parse(GraphFixtures.text("test_call_graph.DropShortNames.SNIPPET"))
      val g = Usage_Graph.build_call_graph(List(sec), drop_upto = 0)
      check(g.all_names.contains("f"))
      check(g.callers("f").contains("uses_both"))
    }
    test("test_call_graph.DropShortNames.test_oracle_parity_at_each_threshold") {
      val sec = parse(GraphFixtures.text("test_call_graph.DropShortNames.SNIPPET"))
      List(0, 1, 2).foreach { drop =>
      note(s"drop_upto=$drop")
      val fast = Usage_Graph.build_call_graph(List(sec), drop_upto = drop)
      val ref = reference(List(sec), drop_upto = drop)
      equal(fast.callers, ref.callers, s"drop_upto=${drop}")
      equal(fast.callees, ref.callees, s"drop_upto=${drop}")
      }
    }
    test("test_call_graph.DropShortNames.test_single_char_dropped_by_default") {
      val sec = parse(GraphFixtures.text("test_call_graph.DropShortNames.SNIPPET"))
      val g = Usage_Graph.build_call_graph(List(sec))
      check(!g.all_names.contains("f"))
      check(g.all_names.contains("ff"))
      check(g.callers("ff").contains("uses_both"))
    }
    test("test_call_graph.WordBoundary.test_plain_identifier_is_prime_and_substring_aware") {
      check(matches("foo","using foo by simp"))
      check(matches("foo","foo[OF x]"))
      check(!matches("foo","foobar"))
      check(!matches("foo","foo' = foo'"))
    }
    test("test_call_graph.WordBoundary.test_special_char_name_only_matches_quoted") {
      check(matches("num:1","\"num:1\"[THEN x]"))
      check(!matches("num:1","\"eq-num:1\""))
      check(!matches("denote=:4","\"denote=:4[3]\""))
    }
    test("test_call_graph.WordBoundary.test_symbolic_name_not_glued") {
      check(matches("\\<gamma>","rule \\<gamma> here"))
      check(!matches("\\<gamma>","\\<gamma>\\<^sub>1"))
      check(matches("foo","foo\\<gamma>"))
    }
  }
}
