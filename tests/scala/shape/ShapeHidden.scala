package isabelle.query.regression

import isabelle.query._
import TestSupport._

object ShapeHidden {
  private def steps(name: String): List[Shape.Step] = {
    val s = parse(HiddenFixture.text, "Test")
    val ss = Shape.scan_steps(new Shape.Sec_Ctx(s), s.entries.find(_.name == name).get)
    Shape.annotate_fanin(ss, s.source)
    ss
  }
  private def goals(name: String): List[Shape.Step] = steps(name).filter(_.kind == "goal")
  private def goalShape(name: String): List[(String, String, Int)] =
    goals(name).map(s => (s.goal_cmd, s.stmt_text, s.fanin))
  private def firstGoal(text: String): Shape.Step = {
    val s = parse(text, "Test")
    Shape.scan_steps(new Shape.Sec_Ctx(s), s.entries.find(_.name == "l").get).filter(_.kind == "goal").head
  }
  def run(): Unit = {
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_the_goal_behind_the_cartouche_is_emitted") {
      equal(goals("a_cartouche_citation").length, 3)
    }
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_it_matches_the_same_proof_written_without_a_cartouche") {
      equal(goalShape("a_cartouche_citation"), goalShape("a_control_no_cartouche"))
    }
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_the_proposition_is_measured_not_the_cited_fact") {
      val goal = goals("a_cartouche_citation").find(s => s.goal_cmd == "have" && s.line == 7).get
      equal(goal.stmt_text, "True \\<and> True")
    }
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_the_citation_is_attributed_to_the_goal_that_makes_it") {
      val byLine = goals("a_cartouche_citation").map(s => s.line -> s.fanin).toMap
      equal(byLine(7), 1); equal(byLine(8), 0)
    }
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_a_nested_cartouche_does_not_strand_its_closer") {
      val (prefix, col) = Shape.split_command_prefix("from \\<open>a \\<open>b\\<close> c\\<close> have \"Q\" by simp")
      notContains(prefix, "\\<close>")
      equal(col, prefix.indexOf("have") + "have ".length)
    }
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_a_line_with_no_goal_keyword_is_unchanged") {
      equal(Shape.split_command_prefix("using \\<open>P\\<close> by simp")._2, -1)
    }
    test("test_shape_hidden_goals.ACartoucheFactReferenceIsNotTheProposition.test_a_keyword_inside_a_cited_term_is_not_a_command") {
      notContains(Shape.split_command_prefix("from \\<open>have x\\<close> by simp")._1, "have")
    }
    test("test_shape_hidden_goals.AWrappedStatementIsNotABareGoal.test_the_wrapped_proposition_is_found") {
      equal(goals("b_wrapped_statement").head.stmt_text, "True \\<and> True")
    }
    test("test_shape_hidden_goals.AWrappedStatementIsNotABareGoal.test_it_matches_the_same_proof_written_on_one_line") {
      equal(goalShape("b_wrapped_statement"), goalShape("b_control_same_line"))
    }
    test("test_shape_hidden_goals.AWrappedStatementIsNotABareGoal.test_the_span_points_at_the_line_the_statement_is_on") {
      val goal = goals("b_wrapped_statement").head
      equal(goal.line, 20); equal(goal.stmt_start, 21)
    }
    test("test_shape_hidden_goals.AWrappedStatementIsNotABareGoal.test_a_genuinely_bare_goal_stays_bare") {
      val bare = goals("b_wrapped_statement").find(_.goal_cmd == "show").get
      equal((bare.stmt_start, bare.stmt_text), (0, ""))
    }
    test("test_shape_hidden_goals.AWrappedStatementIsNotABareGoal.test_a_labelled_wrapped_goal_is_found") {
      val goal = firstGoal("theory T imports Main\nbegin\nlemma l: \"True\"\nproof -\n" +
        "  have key:\n    \"True\"\n    by simp\n  show ?thesis by simp\nqed\nend\n")
      equal((goal.label, goal.stmt_text), ("key", "True"))
    }
    test("test_shape_hidden_goals.AWrappedStatementIsNotABareGoal.test_a_command_followed_by_a_non_statement_stays_bare") {
      val goal = firstGoal("theory T imports Main\nbegin\nlemma l: \"True\"\nproof -\n" +
        "  show ?thesis\n    by simp\nqed\nend\n")
      equal(goal.stmt_text, "")
    }
  }
}
