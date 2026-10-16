package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Path as JPath}
import TestSupport.*
import CliSupport.*

/* `tests/test_subject_loop.py`: the subject loop, `CMD A B C` in one call.
   One call graph per request [graph-once]; a batch with an unknown subject is
   all or nothing [subject-batch]; `callers -f tsv` names its subject on every
   row [callers-tsv]. */
private[regression] object Cli_subject_loop {
  private val THY = """theory A imports Main begin
lemma base: "True" by simp
lemma mid: "True" using base by simp
lemma top: "True" using mid by simp
lemma other: "True" using base by simp
end
"""

  private def project[A](body: JPath => A): A =
    withProject(Seq("A.thy" -> THY, "ROOT" -> "session S = HOL +\n  theories\n    A\n"))(body)

  def run(): Unit = {
    test("test_subject_loop.AnUnknownSubjectFailsTheBatch.test_stdout_is_untouched_and_the_status_is_1") {
      project { root =>
        val r = query(root, "callers", "-r", "-c", "base", "bogus", "top")
        equal((r.exit, r.out), (1, ""))
        contains(r.err, "'bogus'")
      }
    }
    test("test_subject_loop.AnUnknownSubjectFailsTheBatch.test_every_unknown_subject_is_reported") {
      project { root =>
        val r = query(root, "callees", "-c", "nope1", "base", "nope2")
        equal((r.exit, r.out), (1, ""))
        contains(r.err, "'nope1'")
        contains(r.err, "'nope2'")
      }
    }
    test("test_subject_loop.AnUnknownSubjectFailsTheBatch.test_a_clean_batch_matches_the_single_calls") {
      project { root =>
        // The blank-line layout a positional reader splits on is unchanged.
        val singles = List("base", "mid", "top").map(n => query(root, "callers", "-r", n).out)
        val r = query(root, "callers", "-r", "base", "mid", "top")
        equal(r.exit, 0)
        equal(r.out, singles.mkString("\n"))
      }
    }
    test("test_subject_loop.AnUnknownSubjectFailsTheBatch.test_a_scan_with_no_hits_is_not_unknown") {
      project { root =>
        // Plain `callers` scans text: zero mentions of an undeclared name is an
        // honest 0, not a failure (CONTRIBUTING's two empties).
        val r = query(root, "callers", "-c", "base", "bogus")
        equal(r.exit, 0)
        equal(r.out.split("\\s+").toList.filter(_.nonEmpty), List("2", "0"))
      }
    }
  }
}
