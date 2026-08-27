/* Isabelle Query — fast syntax-aware queries over an Isabelle/Isar project.

Scala rewrite of the Python isabelle-query tool (see PLAN.md).
CLI entry point: `isabelle query …`.

`dump-entries` and `dump-theories` are DEVELOPMENT commands, deliberately
absent from the usage text: they exist so the engine's entry set and theory set
can be diffed byte-for-byte against the Python oracle over whole corpora (see
`dev/entrydiff.sh`).  Their record format is fixed by that harness and is not a
user-facing interface.
*/

package isabelle.query


import isabelle.*

import java.io.{BufferedWriter, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => JPath, Paths}


object Query_Tool {
  val version = "0.8.0-scala"

  private def usage(): Unit =
    Output.writeln(
      """Usage: isabelle query [OPTIONS] COMMAND [ARGS]
        |
        |  -V, --version    print version and exit
        |
        |Query an Isabelle/Isar project: syntax-aware, no build required.
        |Commands arrive per PLAN.md.""".stripMargin)


  /* --- development dumps --- */

  private def sorted_sections(root: JPath): (JPath, List[(String, Theory_Section)]) = {
    val base = Discovery.real(root)
    val sections = Theory.parse_root(root)
    val keyed =
      for (sec <- sections) yield {
        val p = Discovery.real(Paths.get(sec.path.implode))
        val rel = (if (p.startsWith(base)) base.relativize(p) else p).toString
        ((if (rel.endsWith(".thy")) rel.substring(0, rel.length - 4) else rel), sec)
      }
    (base, keyed.sortBy(_._1))
  }

  def dump_entries(root: JPath, spans: Boolean, bindings: Boolean): Unit = {
    val out = new PrintWriter(
      new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 16))
    try {
      for ((key, sec) <- sorted_sections(root)._2; e <- sec.entries) {
        val buf = new StringBuilder
        buf ++= key; buf += ':'; buf ++= e.thy_line.toString
        buf += ':'; buf ++= e.tag; buf += ':'; buf ++= e.name
        if (spans) {
          buf ++= ":src=" + e.src_start + "-" + e.thy_end
          buf ++= ":decl_end=" + e.decl_end_line
          buf ++= ":proof=" + e.proof_line
          buf ++= ":body_end=" + e.body_end_line
        }
        if (bindings) {
          buf ++= ":bind=" + e.bindings.map(b => b._1 + "/" + b._2).mkString(",")
          buf ++= ":target=" + e.target
        }
        out.println(buf.toString)
      }
    }
    finally out.flush()
  }

  def dump_theories(root: JPath): Unit = {
    val base = Discovery.real(root)
    val out = new PrintWriter(
      new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 16))
    try {
      val rels =
        for (f <- Discovery.theories(root)) yield {
          val p = Discovery.real(f.path)
          (if (p.startsWith(base)) base.relativize(p) else p).toString
        }
      for (r <- rels.sorted) out.println(r)
    }
    finally out.flush()
  }


  /* --- tool --- */

  def main_tool(args: List[String]): Unit =
    args match {
      case ("-V" | "--version") :: _ => Output.writeln("query " + version)
      case "dump-entries" :: rest =>
        val dirs = rest.filterNot(_.startsWith("-"))
        if (dirs.length != 1) error("Usage: isabelle query dump-entries ROOT_DIR [--spans]")
        dump_entries(Paths.get(dirs.head).toAbsolutePath,
          rest.contains("--spans"), rest.contains("--bindings"))
      case "dump-theories" :: rest =>
        val dirs = rest.filterNot(_.startsWith("-"))
        if (dirs.length != 1) error("Usage: isabelle query dump-theories ROOT_DIR")
        dump_theories(Paths.get(dirs.head).toAbsolutePath)
      case _ => usage()
    }

  val isabelle_tool: Isabelle_Tool =
    Isabelle_Tool("query", "query an Isabelle/Isar project (syntax-aware, no build)",
      Scala_Project.here, main_tool)
}

class Query_Tools extends Isabelle_Scala_Tools(Query_Tool.isabelle_tool)
