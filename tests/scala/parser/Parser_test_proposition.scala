/*  Title:      tests/scala/parser/Parser_test_proposition.scala

Hand-written port of tests/test_proposition.py [decl-commands]: `proposition`
declares a fact exactly as `lemma` does, tag LEMMA.  Both halves on one
fixture -- the entry itself, and the knock-on bound of the entry above it,
whose proof scan must no longer swallow the proposition's steps.  The central
assertion needs no hand-computed step taxonomy: `above` and `below` are
IDENTICAL one-line proofs, so they must scan to the same steps.
*/

package isabelle.query.regression

import isabelle.query.*
import isabelle.query.regression.TestSupport.*


private[regression] object Parser_test_proposition {
  //  1 theory Prop / 5 lemma above / 8 proposition mid / 14 lemma below
  private val PROP =
    "theory Prop\n" +
    "imports Main\n" +
    "begin\n" +
    "\n" +
    "lemma above: \"True\"\n" +
    "  by simp\n" +
    "\n" +
    "proposition mid: \"True\"\n" +
    "proof -\n" +
    "  have a: \"True\" by simp\n" +
    "  show \"True\" by simp\n" +
    "qed\n" +
    "\n" +
    "lemma below: \"True\"\n" +
    "  by simp\n" +
    "\n" +
    "end\n"

  private def sec(): Theory_Section = parse(PROP, "Prop", "Prop.thy")
  private def by_name(s: Theory_Section): Map[String, Entry] =
    s.entries.map(e => e.name -> e).toMap
  private def steps(name: String): List[Shape.Step] = {
    val s = sec()
    Shape.scan_steps(new Shape.Sec_Ctx(s), by_name(s)(name))
  }

  def run(): Unit = {
    test("test_proposition.PropositionIsADeclaration.test_it_is_indexed_in_source_order") {
      equal(sec().entries.map(_.name), List("above", "mid", "below"))
    }
    test("test_proposition.PropositionIsADeclaration.test_it_tags_as_a_lemma") {
      equal(by_name(sec())("mid").tag, "LEMMA")
    }
    test("test_proposition.PropositionIsADeclaration.test_it_starts_where_it_is_written") {
      equal(by_name(sec())("mid").thy_line, 8)
    }
    test("test_proposition.PropositionIsADeclaration.test_it_bounds_the_declaration_above_it") {
      val end = by_name(sec())("above").thy_end
      check(end < 8, s"above.thy_end $end < 8")
    }
    test("test_proposition.ItsProofIsScannedAsItsOwn.test_identical_proofs_scan_identically") {
      equal(steps("above").map(_.kind), steps("below").map(_.kind))
    }
    test("test_proposition.ItsProofIsScannedAsItsOwn.test_the_propositions_own_proof_has_more_than_one_step") {
      val n = steps("mid").size
      check(n > 1, s"mid scans to $n steps")
    }
    test("test_proposition.ItsProofIsScannedAsItsOwn.test_the_lemma_above_does_not_swallow_them") {
      val (a, m) = (steps("above").size, steps("mid").size)
      check(a < m, s"above $a < mid $m")
    }
  }
}
