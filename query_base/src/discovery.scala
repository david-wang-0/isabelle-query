/*  Title:      query_base/src/discovery.scala

What counts as the project: the theories `isabelle build` would compile.

Each session's ROOT-declared theories, PLUS the transitive closure of their
in-entry `imports`.  An AFP entry that declares a few leaf theories and pulls
the rest in that way — AODV declares one and builds seventy-odd — is otherwise
silently truncated.  Imports of OTHER entries and of the base library (`HOL-*`,
`Pure`, the object logics) are not followed, and an orphan `.thy` that no
declared root reaches is excluded: exactly the build's own set, and not a
recursive glob.

ROOT files are parsed by `Sessions.parse_root_entries` and ROOTS files by
`Sessions.parse_roots` — Isabelle's own parsers, so a `theories [document =
false] Foo (global)` clause needs no hand-rolled tokeniser to survive.
Everything else here (which directories are searched, how an import name is
classified, the order the closure is walked in) reproduces the semantics of the
`isabelle-layout` package the Python implementation depends on.
*/

package isabelle.query


import isabelle.*

import java.nio.file.{Files, Path => JPath, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*


object Discovery {
  /* --- filesystem helpers --- */

  def real(p: JPath): JPath =
    try p.toRealPath()
    catch { case _: Exception => p.toAbsolutePath.normalize }

  /* Python's `sorted()` over Path objects compares the COMPONENT TUPLE, not
     the whole string: `/a/ab` sorts before `/a/b`, which sorts before `/a-x`.
     A plain string order would interleave them differently. */
  object Path_Order extends Ordering[JPath] {
    def compare(a: JPath, b: JPath): Int = {
      val xs = a.toString.split("/", -1)
      val ys = b.toString.split("/", -1)
      var i = 0
      while (i < xs.length && i < ys.length) {
        val c = xs(i).compareTo(ys(i))
        if (c != 0) return c
        i += 1
      }
      xs.length - ys.length
    }
  }

  private def is_dir(p: JPath): Boolean = Files.isDirectory(p)
  private def is_file(p: JPath): Boolean = Files.isRegularFile(p)

  /* Recursive walk, files of a directory before its subdirectories and not
     following directory symlinks — the shape of the `rglob` this reproduces,
     which matters where the first hit for a name wins. */
  def walk(dir: JPath, accept: JPath => Boolean): List[JPath] = {
    val out = new mutable.ListBuffer[JPath]
    def rec(d: JPath): Unit = {
      val items =
        try {
          val s = Files.newDirectoryStream(d)
          try s.iterator().asScala.toList.sortBy(_.getFileName.toString)
          finally s.close()
        }
        catch { case _: Exception => Nil }
      for (p <- items if !Files.isDirectory(p) && accept(p)) out += p
      for (p <- items if Files.isDirectory(p) && !Files.isSymbolicLink(p)) rec(p)
    }
    rec(dir)
    out.toList
  }

  def theory_stem(p: JPath): String = {
    val n = p.getFileName.toString
    if (n.endsWith(".thy")) n.substring(0, n.length - 4) else n
  }


  /* --- ROOT / ROOTS discovery --- */

  /* Two disjoint modes.  With no `ROOTS` index the tree is walked for every
     `ROOT` file (skipping hidden directories); with one, only the directories
     the index names are visited, recursively, which is what keeps a corpus
     root from dragging in whatever happens to sit beside it. */
  def discover_roots(root_dir: JPath): List[JPath] = {
    if (!Files.exists(root_dir)) Nil
    else {
      val out = new mutable.ListBuffer[JPath]
      val seen = mutable.Set.empty[JPath]
      def add(root: JPath): Unit = {
        val rp = real(root)
        if (!seen(rp)) { seen += rp; out += rp }
      }
      if (!is_file(root_dir.resolve("ROOTS"))) {
        for (p <- walk(root_dir, q => q.getFileName.toString == "ROOT")) {
          val rel = root_dir.relativize(p)
          val hidden = rel.iterator().asScala.exists(_.toString.startsWith("."))
          if (!hidden && is_file(p)) add(p)
        }
      }
      else {
        val visited = mutable.Set.empty[JPath]
        def visit(d: JPath): Unit = {
          val rd = real(d)
          if (!visited(rd)) {
            visited += rd
            if (is_file(d.resolve("ROOT"))) add(d.resolve("ROOT"))
            val index = d.resolve("ROOTS")
            if (is_file(index)) {
              for (entry <- Sessions.parse_roots(Path.explode(index.toString))) {
                val sub = d.resolve(entry.trim)
                if (is_dir(sub)) visit(sub)
              }
            }
          }
        }
        visit(root_dir)
      }
      out.toList.sorted(Path_Order)
    }
  }


  /* --- sessions --- */

  final case class Session_Info(
    name: String,
    root_path: JPath,               // resolved, absolute
    in_subdir: Option[String],
    directories: List[String],
    theories: List[String],
    /* The declared parent session (`session Foo = HOL +`).  Not used by
       discovery — a session's theories are found the same way whatever it sits
       on — but it is the chain the namespace router follows to a project's base
       logic, which decides which committed method table applies. */
    parent: Option[String] = None
  ) {
    def session_dir: JPath = in_subdir match {
      case Some(d) => root_path.getParent.resolve(d)
      case None => root_path.getParent
    }
    def entry_root: JPath = root_path.getParent
  }

  def parse_root_sessions(root_path: JPath): List[Session_Info] = {
    val entries =
      try Sessions.parse_root_entries(Path.explode(root_path.toString))
      catch { case ERROR(_) => Nil; case _: Exception => Nil }
    val abs = real(root_path)
    for (e <- entries) yield
      Session_Info(
        name = e.name,
        root_path = abs,
        in_subdir = if (e.path == "." || e.path.isEmpty) None else Some(e.path),
        directories = e.directories,
        theories = e.theories.flatMap(_._2.map(_._1._1)),
        parent = e.parent)
  }

  def iter_sessions(root_dir: JPath): List[Session_Info] =
    discover_roots(root_dir).flatMap(parse_root_sessions)


  /* --- theory resolution --- */

  /* A declared theory, resolved against the session directory and its
     `directories` clause, first hit winning; failing that, a UNIQUE match by
     leaf name anywhere below the session. */
  def resolve_session_theory(session: Session_Info, name: String): Option[JPath] = {
    val base = session.session_dir
    val candidates = (base :: session.directories.map(base.resolve)).map(_.resolve(name + ".thy"))
    candidates.find(Files.exists(_)) match {
      case some @ Some(_) => some
      case None =>
        val leaf = name.split("/").last + ".thy"
        val matches = walk(base, p => p.getFileName.toString == leaf)
        if (matches.length == 1) Some(matches.head) else None
    }
  }

  /* Where a bare theory name may be found: the session directory, plus any
     `directories` entry that lies OUTSIDE it (one inside is covered by the
     recursive scan anyway). */
  def theory_search_roots(session: Session_Info): List[JPath] = {
    val base = session.session_dir
    val resolved_base = real(base)
    val extra =
      for {
        d <- session.directories
        target = real(base.resolve(d))
        if !(target == resolved_base || target.startsWith(resolved_base))
      } yield target
    base :: extra
  }

  /* The base library and the object logics.  Only ever reached for a QUALIFIED
     import: a bare `Main` becomes infrastructure by failing to resolve, and a
     bare name that DOES resolve to a file the session owns is that file even
     if it happens to be called `CCL`. */
  val infra_roots: Set[String] = Set(
    "Pure", "Main", "Complex_Main", "Tools", "Doc",
    "FOL", "FOLP", "ZF", "CTT", "LCF", "CCL", "Cube", "Sequents")

  private def import_session(raw: String): Option[String] = {
    val i = raw.lastIndexOf('.')
    if (i < 0) None else Some(raw.substring(0, i))
  }

  private def strip_quotes(s: String): String = {
    var a = 0
    var b = s.length
    while (a < b && s.charAt(a) == '"') a += 1
    while (b > a && s.charAt(b - 1) == '"') b -= 1
    s.substring(a, b)
  }

  /* `Some(path)` for an import this session owns and the walk must follow;
     `None` for the base library, another entry, or anything unresolvable. */
  def classify_import(name: String, session: Session_Info,
    stem_index: Map[String, JPath], importer: JPath
  ): Option[JPath] = {
    val raw = strip_quotes(Py.strip(name))
    if (raw.contains("/")) {
      val base_dir = if (importer != null) importer.getParent else session.session_dir
      val cand0 = base_dir.resolve(raw + ".thy")
      val cand = real(cand0)
      if (Files.exists(cand) && cand.startsWith(session.entry_root) && cand != session.entry_root)
        Some(cand)
      else stem_index.get(raw.split("/").last)
    }
    else import_session(raw) match {
      case None => stem_index.get(raw)
      case Some(sess) if sess == session.name =>
        stem_index.get(raw.substring(raw.lastIndexOf('.') + 1))
      case Some(sess) if infra_roots(sess) || sess.startsWith("HOL") => None
      case Some(_) => None       // another entry's session: not followed
    }
  }

  /* The imports a theory header declares, read by Isabelle's own header
     parser.  A file it cannot parse contributes nothing, exactly as an
     unmatched header regex does in the reference implementation. */
  def thy_imports(path: JPath): List[String] =
    try {
      val node = Document.Node.Name(path.toString, theory = theory_stem(path))
      Thy_Header.read(node, Scan.char_reader(File.read(Path.explode(path.toString))))
        .imports.map(_._1)
    }
    catch { case _: Throwable => Nil }

  /* The build's theory set for one session: declared theories in ROOT order,
     then the closure of their in-entry imports.  The frontier is a STACK, so
     the last declared root is expanded first and, within a file, the last
     import first — the order the reference implementation emits. */
  def session_theories(session: Session_Info): List[(String, JPath)] = {
    val out = new mutable.ListBuffer[(String, JPath)]
    val seen = mutable.Set.empty[JPath]
    val frontier = new mutable.ArrayBuffer[JPath]

    val stem_index = {
      val index = mutable.LinkedHashMap.empty[String, JPath]
      for (scan_root <- theory_search_roots(session) if is_dir(scan_root)) {
        for (p <- walk(scan_root, q => q.getFileName.toString.endsWith(".thy"))) {
          val stem = theory_stem(p)
          if (!index.contains(stem)) index += (stem -> p)
        }
      }
      index.toMap
    }

    def offer(name: String, p: JPath): Unit = {
      val rp = real(p)
      if (!seen(rp)) { seen += rp; out += ((name, p)); frontier += p }
    }

    for (name <- session.theories) resolve_session_theory(session, name).foreach(offer(name, _))

    while (frontier.nonEmpty) {
      val p = frontier.remove(frontier.length - 1)
      for (imp <- thy_imports(p)) {
        classify_import(imp, session, stem_index, p).foreach(q => offer(theory_stem(q), q))
      }
    }
    out.toList
  }


  /* --- the whole root --- */

  final case class Found(name: String, path: JPath, session: Option[String])

  /* Every theory under `root_dir`, deduplicated by real path (a theory two
     sessions both reference is parsed once, and attributed to the first that
     reached it).  With no ROOT anywhere, fall back to a recursive glob — the
     legacy behaviour for a directory that is not an Isabelle session. */
  def theories(root_dir: JPath): List[Found] = {
    val roots = discover_roots(root_dir)
    val pairs = new mutable.ListBuffer[(String, JPath, Option[String])]
    if (roots.nonEmpty) {
      for {
        root_path <- roots
        session <- parse_root_sessions(root_path)
        (name, path) <- session_theories(session)
      } pairs += ((name, path, Some(session.name)))
    }
    else {
      for (p <- walk(root_dir, q => q.getFileName.toString.endsWith(".thy")).sorted(Path_Order))
        pairs += ((theory_stem(p), p, None))
    }
    val seen = mutable.Set.empty[JPath]
    val out = new mutable.ListBuffer[Found]
    for ((name, path, session) <- pairs if Files.exists(path)) {
      val rp = real(path)
      if (!seen(rp)) { seen += rp; out += Found(name, path, session) }
    }
    out.toList
  }
}
