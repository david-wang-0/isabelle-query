/*  Title:      jedit_query/src/query_index.scala

The warm per-project index, and the one background thread that owns it.

Three things are load-bearing here, and each is a decision:

  * ONE index per PROJECT, not per jEdit process.  The project a buffer belongs
    to is discovered from the buffer's own path, so a window holding an AFP
    entry and a distribution session keeps two indexes and answers each from
    its own.

  * ONE worker thread for every engine call.  The engine's method/attribute
    table (`isabelle.query.Namespace`) is process-global mutable state: it
    decides whether `auto` is a proof method or a fact, and it is bound per
    PROJECT (a ZF session steps down to the Pure floor, a HOL one does not).
    Two projects querying concurrently would silently share whichever table was
    bound last.  Serialising all engine work through a single thread makes that
    impossible by construction, and `with_namespace` binds the table
    immediately before each call — using the CLI's own `configure_namespace`,
    so there is exactly one definition of the policy.  It also keeps the EDT
    free, which is the other half of the requirement.

  * The section list is CACHED PER FILE, keyed by mtime+size (or, for a dirty
    buffer, by the buffer text itself).  Parsing is the expensive part; the
    ROOT walk and the header pass are not, and re-running them is what lets a
    file added or removed on disk show up without a manual refresh.

Dirty buffers are overlaid from live buffer text, encoded back to file form
(`Symbol.encode`) so the engine sees what a save would have written.  Line
NUMBERS are invariant under that encoding, which is what makes a hit found in
an unsaved buffer still point at the right line.

What is NOT incremental — the P3 note that still stands — is the custom-command
keyword union: it is built root-wide from every theory HEADER before any body
is parsed, so a `keywords` clause typed into an unsaved buffer is only picked up
after a save.  The union's identity is part of the cache key, so when it does
change every section is reparsed rather than half of them being wrong.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.query.{CLI, Discovery, Entry, Namespace, Out, Theory, Theory_Section,
  Usage_Graph}

import java.io.Writer
import java.nio.file.{Files, Path => JPath}
import java.util.concurrent.{Executors, ExecutorService, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable


object Query_Index {
  /* ------------------------------------------------------------------ */
  /* status                                                             */
  /* ------------------------------------------------------------------ */

  sealed abstract class Status { def message: String }

  case object Idle extends Status {
    def message: String = "no index yet"
  }

  final case class Indexing(done: Int, total: Int) extends Status {
    def message: String =
      if (total <= 0) "indexing ..." else "indexing ... " + done + "/" + total
  }

  final case class Ready(theories: Int, entries: Int, millis: Long, note: String)
  extends Status {
    def message: String = {
      val base =
        theories.toString + " theor" + (if (theories == 1) "y" else "ies") + ", " +
          entries.toString + " entries (" + millis.toString + " ms)"
      if (note.isEmpty) base else base + " -- " + note
    }
  }

  final case class Failed(message: String) extends Status


  /* ------------------------------------------------------------------ */
  /* the parsed project                                                 */
  /* ------------------------------------------------------------------ */

  /* Everything a command needs, plus the two lookups the IDE features are
     built on.  The maps are lazy because a "find usages" run touches neither:
     `Usage.find_callers` walks the section list directly. */
  final class Snapshot(val root: JPath, val sections: List[Theory_Section]) {
    lazy val entry_by_name: Map[String, (String, Entry)] =
      Usage_Graph.entry_by_name(sections)

    lazy val by_theory: Map[String, Theory_Section] =
      Usage_Graph.sections_by_theory(sections)

    def theories: Int = sections.length
    def entries: Int = sections.foldLeft(0)(_ + _.entries.length)

    /* --- the direct lookups (find-definition, quick-open, peek) --- */

    def definition(name: String): Option[(String, Entry)] = entry_by_name.get(name)

    def section(theory: String): Option[Theory_Section] = by_theory.get(theory)

    def path_of(theory: String): Option[JPath] = by_theory.get(theory).map(_.path)

    def theory_names: List[String] = sections.map(_.theory)

    /* Declaration names in load order, which is the order a picker should
       offer them in before any ranking. */
    def entry_names: List[String] =
      for (sec <- sections; e <- sec.entries if e.name.nonEmpty) yield e.name
  }


  /* ------------------------------------------------------------------ */
  /* which project a file belongs to                                    */
  /* ------------------------------------------------------------------ */

  private val marker_names = List(".isabelle-query", ".isabelle-layout")

  private def read_marker(marker: JPath): Option[JPath] =
    try {
      val text = new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8)
      text.split("\n", -1).map(_.trim).find(s => s.nonEmpty && !s.startsWith("#")).map { s =>
        val p = java.nio.file.Paths.get(s)
        Discovery.real(if (p.isAbsolute) p else marker.getParent.resolve(p))
      }
    }
    catch { case _: Throwable => None }

  /* The CLI's rule (`CLI.default_root`) — nearest project marker at or above,
     else the nearest directory holding a ROOT file — but rooted at the FILE
     rather than at the process's working directory, and deliberately ignoring
     `$ISABELLE_QUERY_ROOT`.  One jEdit process holds buffers from several
     projects; a single environment variable would bind all of them to one. */
  def root_of(file: JPath): Option[JPath] = {
    val chain = {
      val out = new mutable.ListBuffer[JPath]
      var d = Discovery.real(file.toAbsolutePath).getParent
      while (d != null) { out += d; d = d.getParent }
      out.toList
    }
    val marker = chain.flatMap(d => marker_names.map(d.resolve)).find(Files.isRegularFile(_))
    marker match {
      case Some(m) => Some(read_marker(m).getOrElse(m.getParent))
      case None => chain.find(d => Files.isRegularFile(d.resolve("ROOT")))
    }
  }


  /* ------------------------------------------------------------------ */
  /* the registry                                                       */
  /* ------------------------------------------------------------------ */

  private val registry_lock = new Object
  private val registry = mutable.LinkedHashMap.empty[JPath, Query_Index]

  def apply(root: JPath): Query_Index =
    registry_lock.synchronized {
      registry.getOrElseUpdate(Discovery.real(root), new Query_Index(Discovery.real(root)))
    }

  def for_file(file: JPath): Option[Query_Index] = root_of(file).map(apply)

  def known: List[Query_Index] = registry_lock.synchronized { registry.values.toList }

  def forget_all(): Unit = registry_lock.synchronized { registry.clear() }


  /* ------------------------------------------------------------------ */
  /* the worker                                                         */
  /* ------------------------------------------------------------------ */

  private val worker: ExecutorService =
    Executors.newSingleThreadExecutor(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "isabelle-query-index")
        t.setDaemon(true)
        t.setPriority(Thread.NORM_PRIORITY - 1)
        t
      }
    })

  /* The only way engine code is entered.  Never call this from the EDT's
     caller side expecting a result — hand the result back with
     `GUI_Thread.later`. */
  /* The thread is a daemon and outlives a plugin reload deliberately: shutting
     the executor down would leave a reloaded plugin with a dead one, and there
     is nothing to release — `forget_all` drops the parsed state. */
  def background(body: => Unit): Unit =
    try worker.execute(() => body)
    catch { case _: java.util.concurrent.RejectedExecutionException => () }


  /* A `Writer` that keeps what the engine wrote to it: `configure_namespace`
     reports "base logic is not HOL, using the minimal Pure table" on stderr,
     and in a GUI that belongs in the status line, not in a log nobody reads. */
  private class Capture extends Writer {
    private val buf = new StringBuilder
    def write(cbuf: Array[Char], off: Int, len: Int): Unit = buf.appendAll(cbuf, off, len)
    def flush(): Unit = ()
    def close(): Unit = ()
    def text: String = buf.toString.trim
  }
}


final class Query_Index private[jedit_query] (val root: JPath) {
  private val cache_lock = new Object
  private val cache = mutable.HashMap.empty[JPath, (String, Theory_Section)]

  @volatile private var _status: Query_Index.Status = Query_Index.Idle
  @volatile private var _snapshot: Option[Query_Index.Snapshot] = None
  @volatile private var _note: String = ""

  def status: Query_Index.Status = _status
  def snapshot: Option[Query_Index.Snapshot] = _snapshot
  def note: String = _note

  def name: String = root.getFileName match { case null => root.toString; case n => n.toString }

  /* Drop everything parsed, so the next refresh starts from the files. */
  def invalidate(): Unit = cache_lock.synchronized { cache.clear() }


  /* ------------------------------------------------------------------ */
  /* the namespace seam                                                 */
  /* ------------------------------------------------------------------ */

  /* Bind the citation router's table for THIS project, then run the query.
     The policy is not restated here: `CLI.configure_namespace` is the one
     definition of it (including the `$ISABELLE_QUERY_NAMESPACE=committed`
     pin), and it only ever steps DOWN from the committed default — so the
     default has to be restored first for the binding to be idempotent across
     projects.

     `synchronized` is belt to `Query_Index.background`'s braces: today every
     engine call runs on the one worker thread, and this keeps the invariant
     true if a later feature ever adds a second. */
  def with_namespace[A](body: => A): A =
    Query_Index.synchronized {
      val capture = new Query_Index.Capture
      try {
        Namespace.use_census_namespace()
        val session = new CLI.Session(new Out(capture), new Out(capture))
        session.root_override = Some(root)
        CLI.configure_namespace(session, "callers")
      }
      catch { case _: Throwable => () }
      _note = capture.text
      body
    }


  /* ------------------------------------------------------------------ */
  /* building                                                           */
  /* ------------------------------------------------------------------ */

  private def file_key(path: JPath): String =
    try {
      val attrs = Files.readAttributes(path, classOf[java.nio.file.attribute.BasicFileAttributes])
      "file:" + attrs.lastModifiedTime.toMillis + ":" + attrs.size
    }
    catch { case _: Throwable => "file:missing" }

  private def cached(path: JPath): Option[(String, Theory_Section)] =
    cache_lock.synchronized { cache.get(path) }

  private def store(path: JPath, key: String, sec: Theory_Section): Unit =
    cache_lock.synchronized { cache(path) = (key, sec) }

  /* Runs ON the worker thread.  `progress` is called from the parallel parse,
     so it must be cheap and thread-safe (the dockable hops to the EDT). */
  def refreshed(
    overlay: Map[JPath, String],
    progress: Query_Index.Status => Unit = _ => ()
  ): Query_Index.Snapshot = {
    val start = System.currentTimeMillis()
    set(Query_Index.Indexing(0, 0), progress)

    val plan = Theory.plan(root)
    val total = plan.found.length
    if (total == 0) {
      val why = CLI.diagnose_empty_root(root)
      set(Query_Index.Failed(why), progress)
      error(why)
    }

    /* The keyword union is root-wide, so a change to it invalidates every
       parsed section, not just the header that moved. */
    val union_key = "/" + plan.union.hashCode.toHexString
    val done = new AtomicInteger(0)

    val sections =
      Par_List.map[(Discovery.Found, Map[String, String]), Option[Theory_Section]](
        { case (found, own) =>
            val sec = section_for(found, plan.table(own), union_key, overlay)
            val n = done.incrementAndGet()
            if (n == total || n % 16 == 0) set(Query_Index.Indexing(n, total), progress)
            sec
        },
        plan.found).flatten

    /* Forget files the project no longer contains. */
    val live = plan.found.map(_._1.path).toSet
    cache_lock.synchronized { cache.filterInPlace((p, _) => live(p)) }

    val snapshot = new Query_Index.Snapshot(root, sections)
    _snapshot = Some(snapshot)
    set(Query_Index.Ready(snapshot.theories, snapshot.entries,
      System.currentTimeMillis() - start, _note), progress)
    snapshot
  }

  private def set(st: Query_Index.Status, progress: Query_Index.Status => Unit): Unit = {
    _status = st
    progress(st)
  }

  private def section_for(
    found: Discovery.Found,
    table: Map[String, String],
    union_key: String,
    overlay: Map[JPath, String]
  ): Option[Theory_Section] = {
    val path = found.path
    /* Discovery resolves a theory against a REAL session directory but does
       not re-resolve the file itself, while the overlay is keyed by real path
       (that is the form jEdit knows a buffer by).  A symlinked `.thy` is the
       one case where the two spellings differ, so try both. */
    val text = overlay.get(path).orElse(overlay.get(Discovery.real(path)))
    val key =
      (text match {
        case Some(t) => "buffer:" + t.length + ":" + t.hashCode.toHexString
        case None => file_key(path)
      }) + union_key

    cached(path) match {
      case Some((k, sec)) if k == key => Some(sec)
      case _ =>
        val parsed =
          try Some(Theory.parse_one(found.name, path,
            text.getOrElse(Theory.read(path)), table, found.session))
          catch { case _: Throwable => None }
        parsed.foreach(store(path, key, _))
        parsed
    }
  }
}
