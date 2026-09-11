package isabelle.query.regression

import isabelle.query._
import TestSupport._

object ShapeProvenance {
  private def metrics(text: String, theory: String, name: String): Shape.Proof_Metrics = {
    val sec = parse(text, theory)
    Shape.analyze_proof(new Shape.Sec_Ctx(sec), sec.entries.find(_.name == name).get).get
  }
  private def kinds(name: String): Map[Int, String] =
    metrics(BareFixture.text, "Bare", name).goals.map(s => s.line -> s.bare).toMap
  private def summary(name: String): Shape.Proof_Summary = {
    val ps = Shape.summarize(metrics(BareFixture.text, "Bare", name))
    equal(ps.bare_kinds.map(_._2).sum, ps.n_bare, name)
    ps
  }
  def run(): Unit = {
    test("test_bare_provenance.BareByConstruction.test_a_bare_also_states_nothing_of_its_own") {
      equal(kinds("by_construction")(8), "construction")
    }
    test("test_bare_provenance.BareByConstruction.test_interpret_instantiates_rather_than_states") {
      equal(kinds("by_construction")(10), "construction")
    }
    test("test_bare_provenance.BareByConstruction.test_show_thesis_is_construction") {
      equal(kinds("by_construction")(11), "construction")
    }
    test("test_bare_provenance.BareByConstruction.test_a_stated_step_has_no_bare_kind") {
      val byLine = kinds("by_construction")
      equal(byLine(7), ""); equal(byLine(9), "")
    }
    test("test_bare_provenance.Undelimited.test_a_bare_term_is_undelimited_not_unfound") {
      equal(kinds("undelimited")(17), "undelimited")
    }
    test("test_bare_provenance.Undelimited.test_a_cited_cartouche_is_not_mistaken_for_the_statement") {
      equal(kinds("undelimited")(18), "undelimited")
    }
    test("test_bare_provenance.Unfound.test_obtain_with_the_statement_below_is_unfound") {
      equal(kinds("unfound")(24), "unfound")
    }
    test("test_bare_provenance.Unfound.test_a_wrapped_have_is_not_bare_at_all") {
      equal(kinds("unfound")(26), "")
    }
    test("test_bare_provenance.TheSumIsStillNBare.test_every_proof_sums") {
      List("by_construction", "undelimited", "unfound").foreach { name =>
        subcase(name) { summary(name); () }
      }
    }
    test("test_bare_provenance.TheSumIsStillNBare.test_every_bucket_key_is_present") {
      equal(summary("unfound").bare_kinds.map(_._1).sorted, Shape.BARE_KINDS.sorted)
    }
    test("test_bare_provenance.TheSumIsStillNBare.test_a_stated_proof_has_an_all_zero_histogram") {
      val text = "theory S\nimports Main\nbegin\n" +
        "lemma allstated: \"True\"\nproof -\n  have \"True\" by simp\n" +
        "  show \"True\" by simp\nqed\nend\n"
      val ps = Shape.summarize(metrics(text, "S", "allstated"))
      equal(ps.n_bare, 0)
      equal(ps.bare_kinds.map(_._2).toSet, Set(0))
    }
    test("test_bare_provenance.NonGoalStepsCarryNoKind.test_context_and_closing_steps") {
      val other = metrics(BareFixture.text, "Bare", "unfound").steps.filter(_.kind != "goal")
      check(other.nonEmpty)
      equal(other.map(_.bare).toSet, Set(""))
    }
  }
}
