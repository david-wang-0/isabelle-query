/*  Title:      tests/scala/cli/Cli_ignore_case.scala

Hand-written port of tests/test_ignore_case.py [ignore-case]: `-i` /
`--ignore-case` on the search verbs.  On `grep` it compiles the pattern
case-insensitively, as a leading `(?i)` does, and composes with the `\|`
rewrite; on `find`, which always matches without regard to case, it is
accepted and changes nothing -- `-n`'s contract.  Counts are hand-read from
the fixture: `Hennie` once capitalised (line 5, a lemma name), once
lower-case (line 7, in `using`), and `foo` once (line 7).
*/

package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import CliSupport.*

private[regression] object Cli_ignore_case {
  private val CASE_FIX =
    "theory Case_Fix\n" +
    "  imports Main\n" +
    "begin\n" +
    "\n" +
    "lemma Hennie_bound: \"True\" by simp\n" +
    "\n" +
    "lemma other: \"True\" using hennie_bound foo by simp\n" +
    "\n" +
    "end\n"


  private def counting(body: (Seq[String] => Int) => Unit): Unit =
    withProject(Seq("Case_Fix.thy" -> CASE_FIX,
        "ROOT" -> "session Fix = HOL +\n  theories\n    Case_Fix\n")) { root =>
      body { argv =>
        val r = query(root, (List("grep") ++ argv ++ List("-c"))*)
        equal(r.exit, 0, s"grep ${argv.mkString(" ")} exit; stderr=${r.err}")
        Py.strip(r.out).toInt
      }
    }

  def run(): Unit = {
    test("test_ignore_case.GrepIgnoreCase.test_case_sensitive_by_default") {
      counting { count => equal(count(List("hennie")), 1) }
    }
    test("test_ignore_case.GrepIgnoreCase.test_both_spellings_ignore_case") {
      counting { count =>
        equal(count(List("hennie", "-i")), 2)
        equal(count(List("hennie", "--ignore-case")), 2)
      }
    }
    test("test_ignore_case.GrepIgnoreCase.test_it_is_the_inline_flag") {
      counting { count => equal(count(List("hennie", "-i")), count(List("(?i)hennie"))) }
    }
    test("test_ignore_case.GrepIgnoreCase.test_it_composes_with_the_alternation_rewrite") {
      counting { count => equal(count(List("HENNIE\\|FOO", "-i")), 2) }
    }
    test("test_ignore_case.FindAcceptsIt.test_find_parses_identically_to_its_absence") {
      val original = args("find", "x")
      for (flag <- List("-i", "--ignore-case")) subcase(s"flag=$flag") {
        val n = args("find", "x", flag)
        equal(n.flags, original.flags)
        equal(n.values, original.values)
        equal(n.positional, original.positional)
        equal(n.appended, original.appended)
      }
    }
  }
}
