/*  Title:      jedit_query/src/query_index.scala

The warm per-project index, and the one background thread that owns it.

Three things are load-bearing here, and each is a decision:

  * ONE index per PROJECT, not per jEdit process.  The project a buffer belongs
    to is discovered from the buffer's own path, so a window holding an AFP
    entry and a distribution session keeps two indexes and answers each from
    its own.

  * ONE worker thread for every engine call.  No longer for the namespace's
    sake: since [p10-namespace-value] the method/attribute table is a VALUE
    (`isabelle.query.Namespace.Table`), resolved per project by `with_table`
    and handed to the call, so two indexes may hold two different tables at
    once and nothing can inherit the other's.  What the single thread still
    buys is the rest of the requirement: the EDT stays free, one index's parse
    and per-file cache are never re-entered by a second query, and results
    arrive in the order the user asked for them.

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

The size guard is the fourth decision.  `root_of` walks up to the NEAREST ROOT,
which is the right answer to "which project is this buffer in" but makes the
eagerness unbounded: an AFP checkout carries a `thys/ROOT` of its own, so a
buffer that resolves to it means ten thousand theories, and discovery alone
reads every one of their headers — serially, in the import closure — before a
single body is parsed.  So the guard runs BEFORE discovery, on the only measure
available that costs no reads: how many `.thy` files lie under the root.  It
over-counts (an orphan theory is never loaded), which is the safe direction for
an upper bound.  Over the limit the index REFUSES rather than truncating: a
partial index answers "no usages" for a name that is used, and a panel that
silently under-reports is worse than one that says it will not answer.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.query.{CLI, Discovery, Entry, Namespace, Out, Render, Theory,
  Theory_Section, Usage_Graph}

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
  /* the size guard                                                     */
  /* ------------------------------------------------------------------ */

  /* Chosen against the two corpora that matter: the distribution's `src/HOL`
     is 1451 theories and indexes in about four seconds on this machine, and
     must keep working; an AFP checkout's own `thys/ROOT` is 10336 and must
     not.  Zero or less means no limit, for someone who knows what they are
     asking for. */
  val LIMIT_DEFAULT: Int = 2000

  def limit: Int = Query_Options.integer("index-limit", LIMIT_DEFAULT)

  def over_limit(candidates: Int, limit: Int): Boolean = limit > 0 && candidates > limit

  /* Both ways out, in the message, because the caption is the only place the
     user will look. */
  def limit_message(name: String, candidates: Int, limit: Int): String =
    "project too large: " + candidates.toString + " theories under " + name +
      ", limit " + limit.toString +
      " -- raise " + Query_Options.property_name("index-limit") +
      ", or mark the directory you mean with a .isabelle-query file"


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

    /* By PATH, for every lookup that has one: a theory NAME is unique in a
       session and not in a corpus, so `by_theory` above (last wins) opens
       another file's section whenever two theories share a name — and it
       cannot find a path-spelled theory at all [name-is-not-identity]. */
    lazy val by_path: Map[JPath, Theory_Section] =
      sections.iterator.map(sec => sec.path -> sec).toMap

    def section_of(path: JPath): Option[Theory_Section] = by_path.get(path)

    /* The qualified theory label the CLI prints, for the panel and the peek
       popup: one map per snapshot, not one per row [disambig-loci]. */
    lazy val labels: Map[JPath, String] = Render.locus_labels(sections)

    def label_of(path: JPath): String =
      labels.getOrElse(path, Discovery.theory_stem(path))

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


  /* A `Writer` that keeps what the engine wrote to it: `resolve_namespace`
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

  /* THIS project's citation table, handed TO the query rather than bound
     around it.  The policy is not restated here: `CLI.resolve_namespace` is
     the one definition of it (including the `$ISABELLE_QUERY_NAMESPACE=
     committed` pin and the step DOWN to the Pure floor for a positively
     non-HOL base), and since [p10-namespace-value] it returns a value — so
     there is nothing to restore, no monitor to hold, and two indexes may be
     queried with two different tables at the same time.

     Resolved per call rather than cached, because it reads the project's ROOT
     files and a `session … = ZF +` line may be edited under us; it costs about
     ten milliseconds, which is why nothing on the keystroke path enters here
     (`Query_Quick_Open`).

     The stderr note the resolver writes for a non-HOL project is captured into
     `_note`, which the panel shows, and is set BEFORE `body` runs so a result
     may carry it. */
  def with_table[A](body: Namespace.Table => A): A = {
    val capture = new Query_Index.Capture
    val table =
      try {
        val session = new CLI.Session(new Out(capture), new Out(capture))
        session.root_override = Some(root)
        CLI.resolve_namespace(session, "callers")
      }
      catch { case _: Throwable => Namespace.census }
    _note = capture.text
    body(table)
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

    /* Before discovery, on a directory walk that reads nothing. */
    val cap = Query_Index.limit
    if (cap > 0) {
      val on_disk =
        Discovery.walk(root, p => p.getFileName.toString.endsWith(".thy")).length
      if (Query_Index.over_limit(on_disk, cap)) {
        val why = Query_Index.limit_message(name, on_disk, cap)
        set(Query_Index.Failed(why), progress)
        error(why)
      }
    }

    val plan = Theory.plan(root)
    val total = plan.found.length
    if (total == 0) {
      val why = CLI.diagnose_empty_root(root)
      set(Query_Index.Failed(why), progress)
      error(why)
    }
    /* Again on the discovered set: a ROOT may reach theories that do not live
       under the root directory the walk covered. */
    if (Query_Index.over_limit(total, cap)) {
      val why = Query_Index.limit_message(name, total, cap)
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
