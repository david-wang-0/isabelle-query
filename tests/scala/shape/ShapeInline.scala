package isabelle.query.regression

import isabelle.query._
import TestSupport._

object ShapeInline {
  private def sec(body: String): Theory_Section = parse(s"theory T imports Main begin\n$body\nend\n")
  private def entry(s: Theory_Section, name: String): Entry = s.entries.find(_.name == name).get
  private def steps(s: Theory_Section, name: String): List[Shape.Step] =
    Shape.scan_steps(new Shape.Sec_Ctx(s), entry(s, name))
  private def skeleton(ss: List[Shape.Step]): List[(String, String)] = ss.map(s => (s.kw, s.kind))
  def run(): Unit = {
    test("test_shape_inline.InlineProofIsScanned.test_on_the_declaration_line") {
      val s = sec("lemma a: \"P a\" by simp\nlemma b: \"P b\"\n  by simp")
      equal(skeleton(steps(s, "a")), List(("by", "closing")))
      equal(skeleton(steps(s, "a")), skeleton(steps(s, "b")))
    }
    test("test_shape_inline.InlineProofIsScanned.test_on_a_later_line_of_the_declaration") {
      val s = sec("lemma g:\n  assumes \"A g\"\n  shows \"P g\" by simp")
      val e = entry(s, "g")
      check(e.proof_line > e.thy_line)
      check(e.proof_line <= e.decl_end_line)
      equal(skeleton(steps(s, "g")), List(("by", "closing")))
    }
    test("test_shape_inline.InlineProofIsScanned.test_after_a_bare_term_past_the_declaration_end") {
      val s = sec("lemma c:\n  \"P c\" by auto")
      val e = entry(s, "c")
      check(e.proof_line > e.decl_end_line)
      equal(skeleton(steps(s, "c")), List(("by", "closing")))
    }
    test("test_shape_inline.InlineProofIsScanned.test_a_one_liner_reaches_the_census") {
      val s = sec("lemma a: \"P a\" by simp")
      check(Shape.analyze_proof(new Shape.Sec_Ctx(s), entry(s, "a")).nonEmpty)
    }
    test("test_shape_inline.InlineProofIsScanned.test_inline_plumbing_and_block_openers") {
      val s = sec("lemma d: \"P d\" using refl by simp\nlemma e: \"P e\"\n  using refl by simp")
      equal(skeleton(steps(s, "d")), List(("using", "plumbing")))
      equal(skeleton(steps(s, "d")), skeleton(steps(s, "e")))
    }
    test("test_shape_inline.OnlyStatementTextIsBlanked.test_a_structured_proof_is_unchanged") {
      val s = sec("lemma i: \"P i\"\nproof -\n  from a have b: \"R i\" by simp\n" +
        "  show \"P i\" by (simp add: b)\nqed")
      equal(Shape.inline_proof_col(new Shape.Sec_Ctx(s), entry(s, "i")), 0)
      equal(skeleton(steps(s, "i")), List(("from", "goal"), ("show", "goal"), ("qed", "closing")))
    }
    test("test_shape_inline.OnlyStatementTextIsBlanked.test_an_ordinary_indented_proof_line_needs_no_column") {
      val s = sec("lemma b: \"P b\"\n  by simp")
      equal(Shape.inline_proof_col(new Shape.Sec_Ctx(s), entry(s, "b")), 0)
    }
    test("test_shape_inline.OnlyStatementTextIsBlanked.test_the_column_lands_on_the_proof_keyword") {
      val s = sec("lemma a: \"P a\" by simp")
      val e = entry(s, "a")
      val col = Shape.inline_proof_col(new Shape.Sec_Ctx(s), e)
      equal(s.source(e.proof_line - 1).substring(col).trim, "by simp")
    }
    test("test_shape_inline.StatementSpansAndLineNumbers.test_an_inline_goal_extracts_its_own_proposition") {
      val s = sec("lemma e: \"P e\" proof - show \"Q e\" by simp qed")
      equal(steps(s, "e").map(_.stmt_text), List("Q e"))
    }
    test("test_shape_inline.StatementSpansAndLineNumbers.test_step_lines_are_real_source_lines") {
      val s = sec("lemma a: \"P a\" by simp")
      val ss = steps(s, "a")
      equal(ss.length, 1)
      equal(ss.head.line, entry(s, "a").thy_line)
      contains(s.source(ss.head.line - 1), "by simp")
    }
  }
}
