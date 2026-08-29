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
  val version = CLI.version


  /* --- development dumps --- */

  /* A theory's path relative to the root, which is the record's first field. */
  private def rel_path(base: JPath, path: JPath): String = {
    val p = Discovery.real(path)
    (if (p.startsWith(base)) base.relativize(p) else p).toString
  }

  private def theory_key(base: JPath, path: JPath): String = {
    val rel = rel_path(base, path)
    if (rel.endsWith(".thy")) rel.substring(0, rel.length - 4) else rel
  }

  /* Sorted by theory key, and parsed in BATCHES: over a whole corpus the
     sections are the memory high-water mark, and nothing after the print needs
     them.  Batching keeps the parallelism (a batch is one `Par_List.map`) while
     bounding what is live at once. */
  private val BATCH = 256

  def dump_entries(root: JPath, spans: Boolean, bindings: Boolean): Unit = {
    val base = Discovery.real(root)
    val plan = Theory.plan(root)
    val ordered = plan.found.sortBy(fk => theory_key(base, fk._1.path))
    val out = new PrintWriter(
      new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 16))
    try {
      for (batch <- ordered.grouped(BATCH)) {
        val parsed =
          Par_List.map((fk: (Discovery.Found, Map[String, String])) =>
            (theory_key(base, fk._1.path), Theory.parse(fk._1, plan.table(fk._2))), batch)
        for (case (key, Some(sec)) <- parsed; e <- sec.entries) {
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
    }
    finally out.flush()
  }

  /* The RAW `imports`-clause tokens per theory.  `deps` / `uses` /
     `graph imports` print such a token verbatim when it names no in-project
     theory, so the header parser's spelling is observable and has to be
     diffable against the reference implementation's regex. */
  def dump_imports(root: JPath): Unit = {
    val base = Discovery.real(root)
    val out = new PrintWriter(
      new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 16))
    try {
      val rows =
        for (f <- Discovery.theories(root))
          yield theory_key(base, f.path) + "\t" + Discovery.thy_imports(f.path).mkString(" ")
      for (r <- rows.sorted) out.println(r)
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

  /* The process entry point, and it runs the query.

     Until P8 it first asked the warm server whether it would rather answer —
     a second copy of the thin client's routing policy, in Scala, reached only
     after the client had already declined or been skipped.  That made a client
     fallback ask twice: the client would try the registry, fail, re-exec the
     tool, and the tool would try the registry and fail again, having been told
     nothing about the first attempt.  The policy now lives in exactly one
     place — `query_client.py`, chosen by `lib/Tools/query` — and a JVM that
     gets here is a JVM that has been told to do the work.

     So: strip the flag the shim routed on and run.  There is no branch. */
  def main_tool(args0: List[String]): Unit =
    local_tool(CLI.strip_no_server(args0))

  private def local_tool(args: List[String]): Unit =
    args match {
      case "dump-entries" :: rest =>
        val dirs = rest.filterNot(_.startsWith("-"))
        if (dirs.length != 1) error("Usage: isabelle query dump-entries ROOT_DIR [--spans]")
        dump_entries(Paths.get(dirs.head).toAbsolutePath,
          rest.contains("--spans"), rest.contains("--bindings"))
      case "dump-imports" :: rest =>
        val dirs = rest.filterNot(_.startsWith("-"))
        if (dirs.length != 1) error("Usage: isabelle query dump-imports ROOT_DIR")
        dump_imports(Paths.get(dirs.head).toAbsolutePath)
      case "dump-theories" :: rest =>
        val dirs = rest.filterNot(_.startsWith("-"))
        if (dirs.length != 1) error("Usage: isabelle query dump-theories ROOT_DIR")
        dump_theories(Paths.get(dirs.head).toAbsolutePath)
      case _ => CLI.run(args)
    }

  val isabelle_tool: Isabelle_Tool =
    Isabelle_Tool("query", "query an Isabelle/Isar project (syntax-aware, no build)",
      Scala_Project.here, main_tool)
}

class Query_Tools extends Isabelle_Scala_Tools(Query_Tool.isabelle_tool)

/* The JVM entry the `lib/Tools/query` shim execs, and the ONLY safe one.

   The shim shadows the tool name, and BOTH dispatchers — `bin/isabelle` and
   `Isabelle_Tool.main` — resolve external tools FIRST, so any name-based
   route back into the JVM (`isabelle java isabelle.Isabelle_Tool query …`)
   re-enters the shim as a CHILD process and loops, stacking one live JVM per
   ~1.3 s until the machine dies.  Measured, twice, the hard way.  This main
   is `find_internal`'s wrapper verbatim, minus the name lookup that loops.

   It is also, since P8, a terminal entry: it runs the query and returns.  It
   does not look for a server, so no route out of this process leads back to
   the shim, and the tripwire the shim still carries should never fire. */
object Query_Main {
  def main(args: Array[String]): Unit =
    Command_Line.tool { Query_Tool.isabelle_tool.body(args.toList) }
}
