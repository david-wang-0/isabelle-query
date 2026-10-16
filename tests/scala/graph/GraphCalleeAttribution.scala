package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

/** Upstream `tests/test_callee_attribution.py` (0.9.8): a callee row names a
  * declaration the citing theory can SEE [callee-attribution].
  *
  * The call graph is keyed by NAME and `--reach closure` keeps an edge when
  * SOME declaration is visible; the row then named one first-wins, by load
  * order.  Here `Arms` loads first and declares `tape`; `Guess` declares it
  * too.  Only `Guess` is in `Sim`'s closure, so first-wins named the wrong
  * one.  Real files, because visibility reads `imports` clauses off disk. */
object GraphCalleeAttribution {
  private val THYS: List[(String, String)] = List(
    "Arms" -> ("theory Arms\nimports Main\nbegin\n" +
      "definition tape :: \"nat\" where \"tape = 0\"\nend\n"),
    "Guess" -> ("theory Guess\nimports Main\nbegin\n" +
      "definition tape :: \"nat\" where \"tape = 1\"\n" +
      "lemma local_use: \"tape = 1\" by (simp add: tape_def)\nend\n"),
    // Loads before `Top`, and cannot see `pass_lemma`: its `wrap` is not a
    // caller of it, whatever the name says.
    "Lone" -> ("theory Lone\nimports Main\nbegin\n" +
      "lemma wrap: \"True\" by simp\nend\n"),
    "Sim" -> ("theory Sim\nimports Guess\nbegin\n" +
      "lemma pass_lemma: \"tape = 1\" by (simp add: tape_def)\nend\n"),
    "Top" -> ("theory Top\nimports Sim\nbegin\n" +
      "lemma top_lemma: \"True\" using pass_lemma by simp\n" +
      "lemma wrap: \"True\" using pass_lemma by simp\nend\n"),
    // Both visible: `Guess` directly, `Arms` through `Mid`.
    "Mid" -> "theory Mid\nimports Arms\nbegin\nend\n",
    "Near" -> ("theory Near\nimports Mid Guess\nbegin\n" +
      "lemma near_lemma: \"tape = tape\" by simp\nend\n"))

  private val ARMS = "tape (DEF) — Arms [L4]"
  private val GUESS = "tape (DEF) — Guess [L4]"

  private def fixture[T](body: java.nio.file.Path => T): T =
    withProject(THYS.map { case (n, text) => s"$n.thy" -> text } :+
      ("ROOT" -> ("session Demo = HOL +\n  theories\n" + THYS.map(t => s"    ${t._1}\n").mkString)))(body)

  /* `cmd_callees`' rows for `tape`, stripped. */
  private def rows(root: java.nio.file.Path, args: String*): List[String] =
    cli("callees" :: args.toList, root).out.linesIterator.map(_.trim)
      .filter(_.startsWith("tape")).toList

  /* `callers -r pass_lemma`'s lines that mention `wrap`, stripped. */
  private def wraps(root: java.nio.file.Path, args: String*): List[String] =
    cli(List("callers", "-r", "pass_lemma") ++ args, root).out.linesIterator
      .filter(_.contains("wrap")).map(_.trim).toList

  /* `callers NAME`: a row reduced to its locus; headers kept whole. */
  private def grouped(root: java.nio.file.Path, name: String): List[String] =
    cli(List("callers", name), root).out.linesIterator.filter(_.nonEmpty)
      .map(ln => if (ln.startsWith("  ")) ln.trim.split("\\s+")(0) else ln).toList

  def run(): Unit = {
    test("test_callee_attribution.TheVisibleDeclaration.test_first_wins_would_name_the_invisible_one") {
      // The premise of the fixture: load order puts `Arms` first.
      fixture { root => equal(load(root).head.theory, "Arms") }
    }
    test("test_callee_attribution.TheVisibleDeclaration.test_direct") {
      fixture { root => equal(rows(root, "pass_lemma"), List(GUESS)) }
    }
    test("test_callee_attribution.TheVisibleDeclaration.test_recursive") {
      // Whatever `Sim` sees, `Top` sees: the seed's closure scopes every depth.
      fixture { root => equal(rows(root, "-r", "top_lemma"), List(GUESS)) }
    }
    test("test_callee_attribution.TheVisibleDeclaration.test_several_visible_nearest_first") {
      // `Guess` is a direct import, `Arms` two hops away: depth beats load order.
      fixture { root => equal(rows(root, "near_lemma"), List(GUESS, ARMS)) }
    }
    test("test_callee_attribution.TheVisibleDeclaration.test_name_mode_lists_every_declaration") {
      fixture { root => equal(rows(root, "--reach", "name", "pass_lemma"), List(ARMS, GUESS)) }
    }
    test("test_callee_attribution.TheVisibleDeclaration.test_external_asks_the_visible_declaration") {
      // `local_use` cites its own theory's `tape`; first-wins called it
      // cross-theory because `Arms` is not `Guess`.
      fixture { root => equal(rows(root, "--external", "local_use"), Nil) }
    }
    test("test_callee_attribution.TheCallerThatCanReachTheSeed.test_block") {
      fixture { root => equal(wraps(root), List("wrap (LEMMA) — Top [L5]")) }
    }
    test("test_callee_attribution.TheCallerThatCanReachTheSeed.test_names") {
      fixture { root => equal(wraps(root, "--names"), List("wrap (LEMMA) — Top")) }
    }
    test("test_callee_attribution.TheCallerThatCanReachTheSeed.test_tsv") {
      fixture { root =>
        equal(wraps(root, "-f", "tsv"), List("pass_lemma\twrap\tLEMMA\tTop\tDemo\t1\tTop:5"))
      }
    }
    test("test_callee_attribution.TheCallerThatCanReachTheSeed.test_name_mode_lists_both") {
      fixture { root =>
        equal(wraps(root, "--reach", "name"),
          List("wrap (LEMMA) — Lone [L4]", "wrap (LEMMA) — Top [L5]"))
      }
    }
    test("test_callee_attribution.CallersOfACollidingName.test_grouped") {
      // `Guess` and `Sim` see Guess's `tape` alone; `Near` imports both.
      fixture { root =>
        equal(grouped(root, "tape"), List(
          "3 caller(s) of tape, which has 2 declarations:",
          "tape (DEF) — Guess [L4]: 2 caller(s)",
          "Guess:5", "Sim:4",
          "tape, any of 2: (DEF) — Arms [L4]; (DEF) — Guess [L4]: 1 caller(s)",
          "Near:4"))
      }
    }
    test("test_callee_attribution.CallersOfACollidingName.test_one_declaration_is_unchanged") {
      fixture { root => equal(grouped(root, "pass_lemma").head, "2 caller(s) of pass_lemma:") }
    }
  }
}
