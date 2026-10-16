package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Path as JPath}
import TestSupport.*

/* `tests/test_toplevel_callers.py`: the synthetic `<toplevel>` caller.  Two
   theories named `A` (sessions S1 in `alpha/`, S2 in `beta/`), each citing
   `base` on line 2 and `mid` on line 3 from `declare`, which no entry owns.

   Ported so far: the plain-mode TSV row [callers-tsv] (upstream fcdc879),
   whose `<toplevel>` caller is minted from the locus label by the TSV emitter
   itself.  The closure-side IDs need the graph's node to be named by that
   label too, which is [toplevel-label] (P15 S4): `OneNodePerTheory` (3 IDs)
   and `TsvRowsAreFilled.{test_closure_rows,
   test_the_locus_is_the_edge_into_the_closure,
   test_a_deeper_member_is_located_by_its_own_edge}` join this file there. */
object GraphToplevelCallers {
  private val ROOT = """session S0 = HOL +
  theories
    Base

session S1 in alpha = S0 +
  theories
    A

session S2 in beta = S0 +
  theories
    A
"""

  private val BASE = """theory Base imports Main begin
lemma base: "True" by simp
lemma mid: "True" using base by simp
end
"""

  // Top-level citations of `base` on line 2 and `mid` on line 3, in both.
  private val A = """theory A imports "S0.Base" begin
declare base [simp]
declare mid [simp]
end
"""

  private def twoTheoriesNamedA[T](body: JPath => T): T =
    withProject(Seq("ROOT" -> ROOT, "Base.thy" -> BASE, "alpha/A.thy" -> A,
      "beta/A.thy" -> A))(body)

  private def rows(out: String): List[List[String]] =
    out.linesIterator.map(_.split("\t", -1).toList).toList

  def run(): Unit = {
    test("test_toplevel_callers.TsvRowsAreFilled.test_located_rows_name_the_caller_alike") {
      twoTheoriesNamedA { root =>
        val r = cli(List("callers", "-f", "tsv", "base"), root)
        val top = rows(r.out).filter(_(2) == "TOPLEVEL")
        equal(top, List(
          List("base", "alpha/A:<toplevel>", "TOPLEVEL", "alpha/A", "S1", "1", "alpha/A:2"),
          List("base", "beta/A:<toplevel>", "TOPLEVEL", "beta/A", "S2", "1", "beta/A:2")))
      }
    }
  }
}
