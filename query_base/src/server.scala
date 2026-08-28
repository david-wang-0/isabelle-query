/*  Title:      query_base/src/server.scala

The warm index, and the four commands that expose it over `isabelle server`.

The tool's whole cost is the JVM: `isabelle query -V` takes ~850 ms to say a
version number it already knows, and a small query is dominated by start-up
rather than by work.  A resident JVM removes that toll, and the distribution
already ships one whose lifecycle, discovery registry and security model have
been reviewed — `isabelle server`.  Its command table is folded together from
every registered `Server.Commands` service on the classpath
(`Pure/Tools/server.scala`), and a component's jars are on that classpath the
moment the component is installed.  So the warm mode is one service class and
one `services =` line in `etc/build.props`: no second daemon, no second
registry, no second password scheme, no Isabelle patch.

Four decisions are load-bearing.

  * **One dispatch path.**  `query_run` takes an `argv` and hands it to
    `CLI.run_result` — the same argument grammar, the same commands, the same
    exit statuses as the command line.  A served answer differs from a typed
    one only in where the bytes go.  The alternative, a second command
    language over the socket, would be a second thing to keep in parity with
    the Python oracle, and the parity is the whole project.

  * **Synchronous replies, not `Server.Task`s.**  The distribution's own
    long-running commands (`session_build`, `use_theories`) return a task so
    the connection thread stays free for progress and cancellation.  A query
    has neither: it is one request, one answer, and the answer is the point.
    A task would cost a thread fork and two extra messages on a round trip
    whose entire budget is single-digit milliseconds.  Blocking is per-client
    by construction — the server forks a thread per accepted socket — so a
    whole-corpus query holds up only the client that asked for it.  The price
    is that a client which disconnects mid-query leaves the work running to
    completion; a `cancel`-able variant would need the task form back.

  * **The namespace is rebound before every request, under one lock.**
    `Namespace` is process-global mutable state (it decides whether `auto` is
    a proof method or a fact) and `shape census` binds the broad HOL union
    unconditionally, by design — a census must regenerate identically
    anywhere.  In a process that exits, that is harmless; in a resident one it
    would poison the next request, so every request starts by restoring the
    committed default and then lets `CLI.configure_namespace` step down for
    the project it is actually about.  Holding one lock across the whole run
    is what makes "the table is the one this request bound" true rather than
    hopeful.  It costs concurrency: two clients on two projects serialise.
    That is the honest price of a global, and the real fix — threading the
    table through as a value — is a change to every analysis signature in the
    engine, not to this file.

  * **A refusal is a protocol error, never an empty answer.**  Over the
    limit, or a root with no theories, replies `ERROR`.  The point of
    refusing rather than truncating is lost if the refusal arrives looking
    like "no results".

Staleness: a running server keeps classes loaded from the jar it started with,
so a rebuilt component leaves it answering with old code.  `isabelle_id`
stamps the distribution, not our jar, so we stamp it ourselves — mtime and
size of `$ISABELLE_QUERY_JAR`, read once per JVM.  A client that sees a
different stamp restarts the server; `query_run` also checks a `client_id` the
caller may send, so the check costs no extra round trip.
*/

package isabelle.query


import isabelle.*

import java.io.StringWriter
import java.nio.file.{Files, Path => JPath, Paths}
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable


object Query_Server {
  /* Bumped when the shape of a request or reply changes incompatibly.  The
     thin client refuses a server that does not match. */
  val protocol: Int = 1


  /* ------------------------------------------------------------------ */
  /* component identity                                                 */
  /* ------------------------------------------------------------------ */

  /* Read once, at first use: after that the JVM has the jar mapped and its
     mtime says nothing about the code actually running.  A server that has
     served nothing yet may stamp a jar newer than the classes it will load —
     the window is one request wide and closes on its own. */
  lazy val component_id: String = {
    val jar = Isabelle_System.getenv("ISABELLE_QUERY_JAR")
    val path = if (jar == null || jar.isEmpty) null else Paths.get(jar)
    if (path == null || !Files.isRegularFile(path)) "unknown"
    else
      try {
        val a = Files.readAttributes(path, classOf[BasicFileAttributes])
        a.lastModifiedTime.toMillis.toString + ":" + a.size.toString
      }
      catch { case _: Throwable => "unknown" }
  }

  def version_info: JSON.Object.T =
    JSON.Object(
      "protocol" -> protocol,
      "version" -> CLI.version,
      "component_id" -> component_id,
      "jar" -> Isabelle_System.getenv("ISABELLE_QUERY_JAR"),
      "indexes" -> registry_size)


  /* ------------------------------------------------------------------ */
  /* the size guard                                                     */
  /* ------------------------------------------------------------------ */

  /* A resident index is resident memory, and the guard is about the process
     living for days rather than about any single answer.  4000 clears the
     distribution's `src/HOL` (1451 theories) and every AFP entry with room to
     spare, and refuses a whole AFP checkout (10336) — which is a legitimate
     thing to ask for, so it is one flag away, not forbidden. */
  val LIMIT_DEFAULT: Int = 4000

  def default_limit: Int = {
    val env = Isabelle_System.getenv("ISABELLE_QUERY_SERVER_LIMIT")
    if (env == null || env.isEmpty) LIMIT_DEFAULT
    else try env.toInt catch { case _: NumberFormatException => LIMIT_DEFAULT }
  }

  def limit_message(root: JPath, candidates: Int, limit: Int): String =
    "project too large for a resident index: " + candidates + " theories under " +
      root + ", limit " + limit +
      " -- raise it with the request's \"limit\" (0 disables), or set " +
      "ISABELLE_QUERY_SERVER_LIMIT for the server"


  /* ------------------------------------------------------------------ */
  /* the file fingerprint                                               */
  /* ------------------------------------------------------------------ */

  /* What "the sources have not changed" means, and it is deliberately the
     expensive-but-honest reading: every theory the project could load, every
     `ROOT` and `ROOTS` that could change which those are, by modification
     time AND size.  Recomputing it is a directory walk plus one `stat` per
     file — no reads, no parsing — and it runs on EVERY request, because a
     warm answer that is one edit out of date is worse than a cold one.

     Size alongside mtime because a coarse filesystem clock can hide an edit
     that lands in the same millisecond as the last one; the pair is what the
     jEdit index already keys its per-file cache on. */
  final case class Fingerprint(files: Map[String, (Long, Long)]) {
    def size: Int = files.size
  }

  private def attrs_of(p: JPath): (Long, Long) =
    try {
      val a = Files.readAttributes(p, classOf[BasicFileAttributes])
      (a.lastModifiedTime.toMillis, a.size)
    }
    catch { case _: Throwable => (-1L, -1L) }

  private def is_root_file(p: JPath): Boolean = {
    val n = p.getFileName.toString
    n == "ROOT" || n == "ROOTS"
  }

  /* `extra` carries the theories discovery resolved OUTSIDE the root
     directory — a ROOT's `directories` clause may reach a sibling — which the
     walk below would otherwise never see. */
  def fingerprint(root: JPath, extra: Iterable[JPath]): Fingerprint = {
    val paths = mutable.LinkedHashSet.empty[JPath]
    for (p <- Discovery.walk(root, q => q.getFileName.toString.endsWith(".thy"))) paths += p
    for (p <- Discovery.walk(root, is_root_file)) paths += p
    for (p <- extra) paths += p
    Fingerprint(paths.iterator.map(p => p.toString -> attrs_of(p)).toMap)
  }


  /* ------------------------------------------------------------------ */
  /* one warm index                                                     */
  /* ------------------------------------------------------------------ */

  final class Index private[Query_Server] (val root: JPath) {
    val id: String = UUID.random_string()

    private val lock = new Object

    /* A SECOND monitor, and it has to be: the parse below runs on `Par_List`
       worker threads while the calling thread holds `lock`, so a worker
       reaching for `lock` would deadlock against its own caller. */
    private val cache_lock = new Object

    /* Per file, keyed exactly as the jEdit index keys it: the file's own
       mtime+size, plus the identity of the root-wide keyword union, because a
       `keywords` clause added anywhere reparses everything. */
    private val parsed = mutable.HashMap.empty[JPath, (String, Theory_Section)]

    private var print: Option[Fingerprint] = None
    private var sections: List[Theory_Section] = Nil
    private var table: Map[String, String] = Map.empty
    private var built_ms: Long = 0L
    private var checked_ms: Long = 0L
    private var reparsed: Int = 0
    private var _uses: Long = 0L

    /* Everything an index will say about itself, in one place: three separate
       accessors would be three separate lock acquisitions describing three
       different moments. */
    def stats: JSON.Object.T =
      lock.synchronized {
        JSON.Object(
          "index_id" -> id,
          "root" -> root.toString,
          "theories" -> sections.length,
          "entries" -> sections.foldLeft(0)(_ + _.entries.length),
          "files_checked" -> print.map(_.size).getOrElse(0),
          "build_ms" -> built_ms,
          "check_ms" -> checked_ms,
          "reparsed" -> reparsed,
          "uses" -> _uses)
      }

    /* The whole invalidation story, and it runs on every request.  Cheap path:
       the fingerprint is unchanged, so the sections stand as they are.  Costly
       path: something moved, so discovery and the header pass run again and
       every file whose own key still matches comes back out of the cache —
       one edited theory in a 1451-theory project reparses one theory. */
    def refresh(limit: Int): Unit =
      lock.synchronized {
        val t0 = System.currentTimeMillis()
        val known = sections.map(_.path)
        val print1 = fingerprint(root, known)
        checked_ms = System.currentTimeMillis() - t0

        /* The cap is checked on EVERY refresh, before the unchanged-sources
           short cut, and that ordering is the point: a cap that only applied
           when something had to be reparsed would let the FIRST request
           through and refuse the second, or — worse — silently serve a root
           the caller had just asked not to be served.  The limit belongs to
           the request, not to the index.

           The count comes from the walk above, which reads nothing.  It
           over-counts (an orphan theory is never loaded), and over-counting
           is the safe direction for an upper bound. */
        val candidates = print1.files.keysIterator.count(_.endsWith(".thy"))
        if (limit > 0 && candidates > limit) error(limit_message(root, candidates, limit))

        if (print.contains(print1) && sections.nonEmpty) { reparsed = 0; return }

        val t1 = System.currentTimeMillis()
        val plan = Theory.plan(root)
        if (plan.found.isEmpty) error(CLI.diagnose_empty_root(root))
        if (limit > 0 && plan.found.length > limit)
          error(limit_message(root, plan.found.length, limit))

        val union_key = "/" + plan.union.hashCode.toHexString
        val fresh = new java.util.concurrent.atomic.AtomicInteger(0)
        val secs =
          Par_List.map[(Discovery.Found, Map[String, String]), Option[Theory_Section]](
            { case (found, own) =>
                val key = {
                  val (m, s) = attrs_of(found.path)
                  "file:" + m + ":" + s + union_key
                }
                val hit = cache_lock.synchronized(parsed.get(found.path))
                hit match {
                  case Some((k, sec)) if k == key => Some(sec)
                  case _ =>
                    fresh.incrementAndGet()
                    val sec = Theory.parse(found, plan.table(own))
                    sec.foreach(s => cache_lock.synchronized { parsed(found.path) = (key, s) })
                    sec
                }
            },
            plan.found).flatten

        val live = plan.found.map(_._1.path).toSet
        cache_lock.synchronized { parsed.filterInPlace((p, _) => live(p)) }

        sections = secs
        table = plan.union
        print = Some(print1)
        reparsed = fresh.get()
        built_ms = System.currentTimeMillis() - t1
      }

    def provide(r: JPath): Option[(List[Theory_Section], Map[String, String])] =
      lock.synchronized {
        if (r == root && sections.nonEmpty) Some((sections, table)) else None
      }

    def used(): Unit = lock.synchronized { _uses += 1 }
  }


  /* ------------------------------------------------------------------ */
  /* the registry                                                       */
  /* ------------------------------------------------------------------ */

  private val registry_lock = new Object
  private val registry = mutable.LinkedHashMap.empty[JPath, Index]

  def registry_size: Int = registry_lock.synchronized(registry.size)

  def index_for(root: JPath): Index =
    registry_lock.synchronized {
      registry.getOrElseUpdate(Discovery.real(root), new Index(Discovery.real(root)))
    }

  def index_by_id(id: String): Option[Index] =
    registry_lock.synchronized { registry.values.find(_.id == id) }

  def close(id: String): Boolean =
    registry_lock.synchronized {
      registry.find(_._2.id == id) match {
        case Some((k, _)) => registry.remove(k); true
        case None => false
      }
    }

  def close_root(root: JPath): Boolean =
    registry_lock.synchronized { registry.remove(Discovery.real(root)).isDefined }

  def close_all(): Int =
    registry_lock.synchronized { val n = registry.size; registry.clear(); n }

  def open_indexes: List[JSON.Object.T] =
    registry_lock.synchronized(registry.values.toList).map(_.stats)


  /* ------------------------------------------------------------------ */
  /* running one request                                                */
  /* ------------------------------------------------------------------ */

  /* Every engine call in this JVM, one at a time.  See the header: the
     citation router's table is global, so "which table is bound" is only
     answerable if exactly one request is in flight. */
  private val engine_lock = new Object

  final case class Result(exit: Int, out: String, err: String, index: Option[Index],
    refresh_ms: Long)

  /* The warm index is provided LAZILY, for whatever root the run resolves.

     Eagerly warming a root guessed from the client's working directory was
     wrong in a way worth recording: `-R` lives in the `argv`, so the guess and
     the run disagreed whenever a caller passed one — and the guessed root
     could refuse (empty, or over the cap) for a query that never went near it.
     Only `CLI.Session.active_root` knows the answer, and it asks this function
     with it in hand.

     The cost is that a build happens under the engine lock, which the parse
     does not need.  Correctness first: the alternative is guessing again. */
  def run(argv: List[String], cwd: Option[String], env_root: Option[String],
    limit: Int
  ): Result = {
    val out = new StringWriter
    val err = new StringWriter
    var used: Option[Index] = None
    var refresh_ms = 0L
    val rc =
      engine_lock.synchronized {
        /* Back to the state a fresh process starts in, so nothing an earlier
           request bound — the corpus-wide shape view's unconditional broad
           union above all — can be read by this one. */
        Namespace.use_census_namespace()
        CLI.run_result(argv, new Out(out), new Out(err), s => {
          for (d <- cwd) s.ambient_root = () => CLI.default_root_from(Paths.get(d), env_root)
          s.index_provider = root =>
            if (!Files.isDirectory(root)) None
            else {
              val ix = index_for(root)
              val t0 = System.currentTimeMillis()
              ix.refresh(limit)
              refresh_ms += System.currentTimeMillis() - t0
              ix.used()
              used = Some(ix)
              ix.provide(root)
            }
        })
      }
    Result(rc, out.toString, err.toString, used, refresh_ms)
  }
}


/* ------------------------------------------------------------------ */
/* the commands                                                       */
/* ------------------------------------------------------------------ */

object Query_Server_Protocol {
  private def stale(client_id: String): Unit =
    if (client_id.nonEmpty && client_id != Query_Server.component_id)
      error("stale query server: component " + quote(Query_Server.component_id) +
        ", client expected " + quote(client_id) + " -- restart the server")

  private def root_of(json: JSON.T, cwd: Option[String], env_root: Option[String]): JPath = {
    JSON.string(json, "root").filter(_.nonEmpty) match {
      case Some(r) => Discovery.real(Paths.get(r).toAbsolutePath)
      case None =>
        Discovery.real(CLI.default_root_from(
          Paths.get(cwd.getOrElse(".")), env_root).toAbsolutePath)
    }
  }

  private def limit_of(json: JSON.T): Int =
    JSON.int(json, "limit").getOrElse(Query_Server.default_limit)

  private def cwd_of(json: JSON.T): Option[String] =
    JSON.string(json, "cwd").filter(_.nonEmpty)

  private def env_root_of(json: JSON.T): Option[String] =
    JSON.string(json, "env_root").filter(_.nonEmpty)


  /* `query_version` — who is answering, and is it the code the caller built.
     Cheap on purpose: a client may call it on every invocation. */
  object Version extends Server.Command("query_version") {
    override val command_body: Server.Command_Body = {
      case (_, ()) => Query_Server.version_info
      case (_, JSON.Object(_)) =>
        Query_Server.version_info ++ JSON.Object("open" -> Query_Server.open_indexes)
    }
  }

  /* `query_open` — build or re-validate a warm index for a root, and say what
     it cost.  Idempotent: opening an already-open root is the same stat sweep
     `query_run` does, not a reparse. */
  object Open extends Server.Command("query_open") {
    override val command_body: Server.Command_Body = {
      case (_, JSON.Object(json)) =>
        stale(JSON.string_default(json, "client_id").getOrElse(""))
        val root = root_of(json, cwd_of(json), env_root_of(json))
        if (!Files.isDirectory(root)) error("not a directory: " + root)
        val index = Query_Server.index_for(root)
        index.refresh(limit_of(json))
        index.stats
    }
  }

  /* `query_run` — one CLI invocation against a warm index.  `root` is
     optional: without it the CLIENT's working directory and `$ISABELLE_QUERY_ROOT`
     decide, by the same rule a typed command follows, which is what lets the
     thin client be a drop-in for the tool.

     The reply carries stdout, stderr and the exit status separately, and the
     status is DATA: exit 1 (unresolved subject) and exit 2 (bad root) are
     answers the CLI gives and the client re-emits.  Only a refusal the CLI
     cannot express — over the size limit, a stale component — is an `ERROR`. */
  object Run extends Server.Command("query_run") {
    override val command_body: Server.Command_Body = {
      case (_, JSON.Object(json)) =>
        stale(JSON.string_default(json, "client_id").getOrElse(""))
        val argv = JSON.strings(json, "argv").getOrElse(error("missing \"argv\""))
        val cwd = cwd_of(json)
        val env_root = env_root_of(json)

        /* An explicit `index_id` pins the root: the caller has opened one and
           wants THAT one, so `-R` elsewhere is a mistake worth naming rather
           than quietly honouring. */
        val pinned =
          JSON.string(json, "index_id").filter(_.nonEmpty).map(id =>
            Query_Server.index_by_id(id).getOrElse(error("no such index: " + id)))
        val argv1 =
          pinned match {
            case Some(ix) if !argv.exists(a => a == "-R" || a.startsWith("-R") ||
              a == "--root" || a.startsWith("--root=")) =>
              List("-R", ix.root.toString) ::: argv
            case _ => argv
          }

        val t0 = System.currentTimeMillis()
        val res = Query_Server.run(argv1, cwd, env_root, limit_of(json))
        val total = System.currentTimeMillis() - t0
        JSON.Object(
          "exit" -> res.exit,
          "output" -> res.out,
          "error" -> res.err,
          "index_id" -> res.index.map(_.id).getOrElse(""),
          /* What the answer cost inside the server, split at the seam that
             matters: how long the staleness recheck and any reparse took, and
             how long the rest of the query did.  A benchmark that cannot see
             this split cannot tell a slow project from a slow query. */
          "refresh_ms" -> res.refresh_ms,
          "run_ms" -> (total - res.refresh_ms),
          "component_id" -> Query_Server.component_id)
    }
  }

  /* `query_close` — drop a root's cache.  Explicit because the server has no
     idle timeout and nothing else bounds resident memory. */
  object Close extends Server.Command("query_close") {
    override val command_body: Server.Command_Body = {
      case (_, ()) => JSON.Object("closed" -> Query_Server.close_all())
      case (_, JSON.Object(json)) =>
        JSON.string(json, "index_id").filter(_.nonEmpty) match {
          case Some(id) =>
            if (!Query_Server.close(id)) error("no such index: " + id)
            JSON.Object("closed" -> 1)
          case None =>
            JSON.string(json, "root").filter(_.nonEmpty) match {
              case Some(r) =>
                /* By ROOT, without creating one on the way: `index_for` would
                   register an index just to close it. */
                val root = Discovery.real(Paths.get(r).toAbsolutePath)
                JSON.Object("closed" -> (if (Query_Server.close_root(root)) 1 else 0))
              case None => JSON.Object("closed" -> Query_Server.close_all())
            }
        }
    }
  }
}


class Query_Server_Commands extends Server.Commands(
  Query_Server_Protocol.Version,
  Query_Server_Protocol.Open,
  Query_Server_Protocol.Run,
  Query_Server_Protocol.Close)
