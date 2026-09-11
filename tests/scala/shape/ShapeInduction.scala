package isabelle.query.regression

import isabelle.query._
import TestSupport._

object ShapeInduction {
  // These three pure helpers are private in Scala. Reflection retains the
  // original exact token/text assertions without widening production APIs.
  private def invoke[A](name: String, args: AnyRef*): A = {
    val method = Shape.getClass.getDeclaredMethods.find(m => m.getName == name && m.getParameterCount == args.length).get
    method.setAccessible(true)
    method.invoke(Shape, args*).asInstanceOf[A]
  }
  private def inductions(text: String, name: String): List[Shape.Induction] = {
    val sec = parse(text, "Ind")
    Shape.scan_inductions(new Shape.Sec_Ctx(sec), sec.entries.find(_.name == name).get)
  }
  private def record(text: String, name: String): Map[String, Jsonl.V] = {
    val sec = parse(text, "Ind")
    val pm = Shape.analyze_proof(new Shape.Sec_Ctx(sec), sec.entries.find(_.name == name).get).get
    Shape_Cmds.summary_record(Shape.summarize(pm)) match {
      case Jsonl.O(fields) => fields.toMap
      case other => throw new AssertionError("Expected summary object: " + other)
    }
  }
  private val THY = "theory Ind imports Main begin\nlemma a: \"P xs\" by (induction xs arbitrary: ys)\nlemma b: \"Q n\"\n  proof (induction n rule: nat.induct)\n  qed\nlemma c: \"R x\" apply (induct_tac x) done\nlemma d: \"S\" by simp\nlemma e: \"T\" by induct\nlemma f: \"U n m\"\n  proof (induction n)\n    case 0 show ?case by (induction m arbitrary: n rule: foo.induct) qed\n  next\n    case (Suc k) show ?case by simp\n  qed\nend\n"
  def run(): Unit = {
    test("test_induction.SplitArgs.test_quoted_compound_term_is_one_token") {
      equal(invoke[List[String]]("split_induct_args", "\"(p, t)\" arbitrary: p t"), List("\"(p, t)\"", "arbitrary:", "p", "t"))
    }
    test("test_induction.SplitArgs.test_bare_terms_split_on_whitespace") {
      equal(invoke[List[String]]("split_induct_args", "xss yss zss"), List("xss", "yss", "zss"))
    }
    test("test_induction.SplitArgs.test_subscript_symbol_stays_glued") {
      equal(invoke[List[String]]("split_induct_args", "ys\\<^sub>1 arbitrary: xs"), List("ys\\<^sub>1", "arbitrary:", "xs"))
    }
    test("test_induction.ParseInduction.test_single_term") {
      equal(invoke[Shape.Induction]("parse_induction", "xs"), Shape.Induction(1, 0, false, false))
    }
    test("test_induction.ParseInduction.test_arbitrary_only") {
      equal(invoke[Shape.Induction]("parse_induction", "arbitrary: a"), Shape.Induction(0, 1, false, false))
    }
    test("test_induction.ParseInduction.test_term_and_two_arbitrary") {
      equal(invoke[Shape.Induction]("parse_induction", "x arbitrary: y z"), Shape.Induction(1, 2, false, false))
    }
    test("test_induction.ParseInduction.test_library_rule_is_not_recursion") {
      equal(invoke[Shape.Induction]("parse_induction", "xss yss rule: list_induct2"), Shape.Induction(2, 0, true, false))
    }
    test("test_induction.ParseInduction.test_qualified_rule_is_recursion") {
      equal(invoke[Shape.Induction]("parse_induction", "x y rule: merge_R1.induct"), Shape.Induction(2, 0, true, true))
    }
    test("test_induction.ParseInduction.test_rule_only_no_terms") {
      equal(invoke[Shape.Induction]("parse_induction", "rule: nat.induct"), Shape.Induction(0, 0, true, true))
    }
    test("test_induction.ParseInduction.test_arbitrary_then_recursion_rule") {
      equal(invoke[Shape.Induction]("parse_induction", "a arbitrary: b rule: rose_tree.induct"), Shape.Induction(1, 1, true, true))
    }
    test("test_induction.ParseInduction.test_custom_non_dotted_rule_is_not_recursion") {
      equal(invoke[Shape.Induction]("parse_induction", "x arbitrary: y rule: my_rule"), Shape.Induction(1, 1, true, false))
    }
    test("test_induction.ParseInduction.test_quoted_compound_term_counts_once") {
      equal(invoke[Shape.Induction]("parse_induction", "\"(p, t)\" arbitrary: p t rule: tree_of_zipper.induct"), Shape.Induction(1, 2, true, true))
    }
    test("test_induction.ParseInduction.test_three_terms") {
      equal(invoke[Shape.Induction]("parse_induction", "s \\<pi>s s' rule: path_to.induct"), Shape.Induction(3, 0, true, true))
    }
    test("test_induction.ParseInduction.test_glued_modifier_value") {
      equal(invoke[Shape.Induction]("parse_induction", "x rule:foo.induct"), Shape.Induction(1, 0, true, true))
    }
    test("test_induction.ParseInduction.test_empty_args") {
      equal(invoke[Shape.Induction]("parse_induction", ""), Shape.Induction(0, 0, false, false))
    }
    test("test_induction.ArgTextLocation.test_single_line") {
      equal(invoke[String]("induction_arg_text", Array("  by (induction xs arbitrary: ys)"), Int.box(0)), "xs arbitrary: ys")
    }
    test("test_induction.ArgTextLocation.test_quoted_paren_does_not_close_early") {
      equal(invoke[String]("induction_arg_text", Array("  by (induction \"(p, t)\" arbitrary: p t rule: f.induct)"), Int.box(0)), "\"(p, t)\" arbitrary: p t rule: f.induct")
    }
    test("test_induction.ArgTextLocation.test_proof_introducer") {
      equal(invoke[String]("induction_arg_text", Array("  proof (induction n rule: nat.induct)"), Int.box(0)), "n rule: nat.induct")
    }
    test("test_induction.ArgTextLocation.test_multiline_arbitrary_wraps") {
      equal(invoke[String]("induction_arg_text", Array("  by (induction xs arbitrary: a b", "        c d rule: foo.induct)"), Int.box(0)), "xs arbitrary: a b c d rule: foo.induct")
    }
    test("test_induction.ArgTextLocation.test_bare_method_has_no_args") {
      equal(invoke[String]("induction_arg_text", Array("  by induct"), Int.box(0)), null)
    }
    test("test_induction.ArgTextLocation.test_trailing_combinator_method_excluded") {
      equal(invoke[String]("induction_arg_text", Array("  by (induct x) auto"), Int.box(0)), "x")
    }
    test("test_induction.ScanInductions.test_one_line_by_induction") {
      equal(inductions(THY, "a"), List(Shape.Induction(1, 1, false, false)))
    }
    test("test_induction.ScanInductions.test_proof_introducer_with_recursion_rule") {
      equal(inductions(THY, "b"), List(Shape.Induction(1, 0, true, true)))
    }
    test("test_induction.ScanInductions.test_induct_tac") {
      equal(inductions(THY, "c"), List(Shape.Induction(1, 0, false, false)))
    }
    test("test_induction.ScanInductions.test_non_induction_proof_empty") {
      equal(inductions(THY, "d"), Nil)
    }
    test("test_induction.ScanInductions.test_bare_induct_zero_counts") {
      equal(inductions(THY, "e"), List(Shape.Induction(0, 0, false, false)))
    }
    test("test_induction.ScanInductions.test_multiple_inductions_in_one_proof") {
      equal(inductions(THY, "f"), List(Shape.Induction(1, 0, false, false), Shape.Induction(1, 1, true, true)))
    }
    test("test_induction.ScanInductions.test_statement_mentioning_induct_is_not_counted") {
      equal(inductions("theory Ind imports Main begin\nlemma g: \"induct_scheme x = y\" by auto\nend\n", "g"), Nil)
    }
    test("test_induction.CensusReduction.test_summarize_empty") {
      equal(Shape.summarize_inductions(Nil), Shape.Induction_Summary(0, 0, 0, 0, 0))
    }
    test("test_induction.CensusReduction.test_summarize_takes_maxima_and_sums") {
      val inds = List(Shape.Induction(1, 2, true, true), Shape.Induction(3, 0, true, false),
        Shape.Induction(1, 1, false, false))
      equal(Shape.summarize_inductions(inds), Shape.Induction_Summary(3, 3, 2, 2, 1))
    }
    test("test_induction.CensusReduction.test_summary_record_carries_induction_columns") {
      val thy = "theory Ind imports Main begin\nlemma f: \"U n m\"\n" +
        "  proof (induction n)\n" +
        "    case 0 show ?case by (induction m arbitrary: a b rule: foo.induct) qed\n" +
        "  next\n    case (Suc k) show ?case by simp\n  qed\nend\n"
      val rec = record(thy, "f")
      equal(rec("n_induct"), Jsonl.I(2))
      equal(rec("induct_terms_max"), Jsonl.I(1))
      equal(rec("induct_arbitrary_max"), Jsonl.I(2))
      equal(rec("induct_rule"), Jsonl.I(1))
      equal(rec("induct_recursion"), Jsonl.I(1))
    }
    test("test_induction.CensusReduction.test_non_induction_proof_has_zero_columns") {
      val thy = "theory Ind imports Main begin\nlemma d: \"S\"\n" +
        "  proof -\n    show ?thesis by simp\n  qed\nend\n"
      val rec = record(thy, "d")
      equal(List("n_induct", "induct_terms_max", "induct_arbitrary_max", "induct_rule", "induct_recursion")
        .map(rec), List.fill(5)(Jsonl.I(0)))
    }
  }
}
