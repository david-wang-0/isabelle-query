package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Path as JPath}
import TestSupport.*

/* `tests/test_toplevel_callers.py`: the synthetic `<toplevel>` caller.  Two
   theories named `A` (sessions S1 in `alpha/`, S2 in `beta/`), each citing
   `base` on line 2 and `mid` on line 3 from `declare`, which no entry owns.
   The node is named by the theory's locus label, so the two are two nodes
   [toplevel-label]; the TSV rows locate each at the edge into the closure
   [callers-tsv] (upstream fcdc879). */
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
    test("test_toplevel_callers.OneNodePerTheory.test_each_theory_gets_its_own_node") {
      twoTheoriesNamedA { root =>
        val g = Usage_Graph.build_call_graph(GraphSupport.load(root))
        equal(g.callers("base"), Set("mid", "alpha/A:<toplevel>", "beta/A:<toplevel>"))
      }
    }
    test("test_toplevel_callers.OneNodePerTheory.test_the_closure_counts_both") {
      twoTheoriesNamedA { root =>
        val r = cli(List("callers", "-r", "-c", "base"), root)
        equal((r.exit, r.out.trim), (0, "3"))
      }
    }
    test("test_toplevel_callers.OneNodePerTheory.test_the_oracle_agrees") {
      // `checkedGraph` compares callers and callees with the reference builder.
      twoTheoriesNamedA { root => GraphSupport.checkedGraph(GraphSupport.load(root)) }
    }
    test("test_toplevel_callers.TsvRowsAreFilled.test_closure_rows") {
      twoTheoriesNamedA { root =>
        val r = cli(List("callers", "-r", "-f", "tsv", "base"), root)
        equal(r.exit, 0)
        equal(rows(r.out), List(
          List("base", "alpha/A:<toplevel>", "TOPLEVEL", "alpha/A", "S1", "1", "alpha/A:2"),
          List("base", "beta/A:<toplevel>", "TOPLEVEL", "beta/A", "S2", "1", "beta/A:2"),
          List("base", "mid", "LEMMA", "Base", "S0", "1", "Base:3")))
      }
    }
    test("test_toplevel_callers.TsvRowsAreFilled.test_the_locus_is_the_edge_into_the_closure") {
      // For `mid`, the node's first citation (line 2, of `base`) is not why it
      // is a caller; line 3 is.
      twoTheoriesNamedA { root =>
        val r = cli(List("callers", "-r", "-f", "tsv", "mid"), root)
        equal(rows(r.out).map(_(6)), List("alpha/A:3", "beta/A:3"))
      }
    }
    test("test_toplevel_callers.TsvRowsAreFilled.test_a_deeper_member_is_located_by_its_own_edge") {
      // `base` <- `mid` <- alpha/A, which now cites only `mid`.
      twoTheoriesNamedA { root =>
        java.nio.file.Files.writeString(root.resolve("alpha/A.thy"),
          "theory A imports \"S0.Base\" begin\ndeclare mid [simp]\nend\n")
        val r = cli(List("callers", "-r", "-f", "tsv", "base"), root)
        equal(rows(r.out).filter(_(3) == "alpha/A"), List(
          List("base", "alpha/A:<toplevel>", "TOPLEVEL", "alpha/A", "S1", "2", "alpha/A:2")))
      }
    }
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
