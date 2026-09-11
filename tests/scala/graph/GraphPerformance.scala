package isabelle.query.regression

import isabelle.query.*
import java.nio.file.Files
import TestSupport.*

object GraphPerformance {
  def source(scale: Int): String = {
    val defs = 150 * scale
    val lemmas = 2000 * scale
    val out = scala.collection.mutable.ListBuffer("theory Perf imports Main begin")
    for (i <- 0 until defs) out += s"definition d$i :: \"nat\" where \"d$i = 0\""
    for (j <- 0 until lemmas) {
      val a = j % defs; val b = (j + 1) % defs
      if (j % 12 == 0) out += s"text \\<open>prose discussing d$a here.\\<close>"
      out += s"lemma l$j: \"d$a = d$b \\<Longrightarrow> d$a = d$b\" by (simp add: d${a}_def d${b}_def)"
    }
    out += "end"
    out.mkString("\n")
  }
  lazy val corpora = (List(parse(source(1), "Perf")), List(parse(source(4), "Perf")))
  private def best(body: => Any): Double =
    (0 until 5).map { _ => val start = System.nanoTime(); body; (System.nanoTime() - start) / 1e9 }.min
  def buildTime(sections: List[Theory_Section]): Double = best(Usage_Graph.build_call_graph(sections))
  def parseTime(text: String): Double = withProject(Seq("Perf.thy" -> text)) { root =>
    val path = root.resolve("Perf.thy")
    best(Theory.parse_one("Perf", path, Files.readString(path)))
  }
}
