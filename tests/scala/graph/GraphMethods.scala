package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphMethods {
  def run(): Unit = {
    test("test_methods.ScanMethods.test_combinator_tail_is_undercounted_not_miscounted") {
      val (counts, located) = scan("theory T imports Main begin\nlemma a: \"P\" by (induct x) auto\nend\n", None)
      equal(counts("induct"), 1)
      check(!counts.contains("auto"))
    }
    test("test_methods.ScanMethods.test_induction_method_is_recognised") {
      val (counts, located) = scan("theory T imports Main begin\nlemma a: \"P\" by (induction xs arbitrary: ys)\nlemma b: \"Q\" proof (induction n) qed\nend\n", None)
      equal(counts("induction"), 2)
    }
    test("test_methods.ScanMethods.test_introducer_forms_are_counted") {
      val (counts, located) = scan("theory T imports Main begin\nlemma a: \"P\" by simp\nlemma b: \"Q\" apply auto done\nlemma c: \"R\" proof (induct n) qed\nlemma d: \"S\" by (blast intro: foo)\nend\n", None)
      equal(counts("simp"), 1)
      equal(counts("auto"), 1)
      equal(counts("induct"), 1)
      equal(counts("blast"), 1)
    }
    test("test_methods.ScanMethods.test_located_form_reports_owning_entry") {
      val (counts, located) = scan("theory T imports Main begin\nlemma headline: \"P\" by simp\nlemma other: \"Q\" by auto\nend\n", Some("simp"))
      equal(counts("simp"), 1); equal(located.size, 1)
      val hit = located.head
      check(hit.path.toString.endsWith(".thy")); check(hit.owner.nonEmpty)
      equal(hit.owner.get.name, "headline"); contains(hit.text, "by simp")
    }
    test("test_methods.ScanMethods.test_method_name_as_a_variable_is_not_counted") {
      val (counts, located) = scan("theory T imports Main begin\nlemma foo: \"N = order\" by simp\nlemma bar: \"order N = N\" by auto\nend\n", None)
      equal(counts("simp"), 1)
      equal(counts("auto"), 1)
      check(!counts.contains("N"))
      check(!counts.contains("order"))
    }
    test("test_methods.ScanMethods.test_no_methods_yields_empty") {
      val (counts, located) = scan("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nend\n", Some("simp"))
      equal(counts.size, 0)
      equal(located, List())
    }
    test("test_methods.ScanMethods.test_prose_and_comments_are_skipped") {
      val (counts, located) = scan("theory T imports Main begin\ntext \\<open>We then apply simp and auto to finish.\\<close>\nlemma q: \"P\" by blast\nend\n", None)
      equal(counts("blast"), 1)
      check(!counts.contains("simp"))
      check(!counts.contains("auto"))
    }
  }
}
