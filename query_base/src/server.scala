/*  Title:      query_base/src/server.scala

The warm index, and the commands that expose it over `isabelle server`.

A cold `isabelle query` costs about 870 ms before it does anything, and this
comment used to attribute all of it to "the JVM".  It is worth having the real
split written down, because the wrong one argues for the wrong design:
`scala_build` ~405 ms (a second JVM, only to check whether this component is
stale), the `bin/isabelle` settings shell ~180 ms (paid again by `isabelle
java`), Isabelle/Scala class loading ~250 ms — and the JVM itself ~30 ms.

A resident process removes those, and that is the SMALLER half of what this
file buys.  The larger half is the parse: 421 ms for a 28-theory AFP entry,
2755 ms for `src/HOL`, ~19 s for the whole AFP, against a 12 ms restat to prove
the sources have not moved.  A warm INDEX is the point; a warm JVM is how it is
held.  (dev/BENCH.md carries the measurements.)

The distribution already ships a resident process whose lifecycle, discovery
registry and security model have been reviewed — `isabelle server`.  Its command table is folded together from
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

  * **The namespace table is a VALUE, so nothing has to be put back.**
    Which table is in force — whether `auto` is a proof method or a fact —
    is resolved per request by `CLI.resolve_namespace`, stored on that
    request's `CLI.Session` and carried down by parameter, so `shape census`'s
    unconditional broad union (a census must regenerate identically anywhere)
    cannot reach the next request.  Until [p10-namespace-value] it was
    process-global state, and this file restored the committed default before
    every request to survive it; that restore is gone.  The lock below stays,
    but it now guards the warm INDEX rather than a table — see
    `engine_lock`.

  * **The environment is per REQUEST, never the server's own.**  The same
    argument as the namespace, one level up: a resident JVM's environment is
    whatever the client that happened to start it exported, it is invisible
    in the argv, and it outlives that client.  A server started under
    `$ISABELLE_QUERY_NAMESPACE=committed` answered every later client's ZF
    `callers induct` with 1 instead of 250.  So `query_run` binds
    `CLI.Session.env` from the request's `env` object — the variables
    `CLI.request_env` lists, and no others — and the server's own environment
    is unreachable from a request rather than merely overridden by one.

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

  /* ONE warm server, reached by every front end.  The name is a client-side
     fact — `isabelle server -n NAME` is how a process is found again — so it
     lives here, beside the protocol it names, and both the auto-delegating CLI
     (`delegate.scala`) and the thin client (`lib/scripts/query_client.py`,
     `DEFAULT_SERVER`) read it rather than each choosing one.  Two names would
     mean two resident JVMs holding two copies of the same index, and a
     developer who redirected one front end at a scratch server would be served
     by the other from the real registry. */
  val default_server_name: String = "isabelle_query"


  /* ------------------------------------------------------------------ */
  /* component identity                                                 */
  /* ------------------------------------------------------------------ */

  /* Read once: embedded hosts force this at startup, before publishing their
     listener. Dedicated servers capture it on first query use. After that a
     changed jar on disk says nothing about the classes actually running. */
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

  /* Identity is a single immutable volatile snapshot. Reading version_info
     never enters Query_Host's lifecycle lock (which may wait for a listener).
     host_name is the embedded servers.db name, empty for a dedicated server;
     host_pid always identifies this JVM. No version/service call starts a host. */
  private[query] final case class Host_Identity(kind: String, name: String)
  @volatile private[query] var host_identity: Host_Identity = Host_Identity("server", "")
  private val host_pid: Long = ProcessHandle.current().pid()

  def version_info: JSON.Object.T = {
    val host = host_identity
    registry_lock.synchronized { JSON.Object(
      "protocol" -> protocol,
      "version" -> CLI.version,
      "component_id" -> component_id,
      "jar" -> Isabelle_System.getenv("ISABELLE_QUERY_JAR"),
      "host_kind" -> host.kind,
      "host_name" -> host.name,
      "host_pid" -> host_pid,
      "retain_indexes" -> retain_indexes_locked,
      "indexes" -> registry.size,
      "cache_source_bytes" -> retained_bytes_locked,
      "cache_limit_bytes" -> cache_limit_bytes,
      "cache_entry_limit" -> REGISTRY_ENTRIES_MAX) }
  }


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

  /* Aggregate retention is weighted by the UTF-8 byte length of the
     NORMALIZED source strings the parsed sections actually retain.  This is a
     cache metric, not an RSS or exact heap bound: decoded source, declarations,
     graphs and simultaneous previous/replacement snapshots are not charged.
     The source snapshot computes this charge without encoding a second copy. */
  val CACHE_MB_DEFAULT: Long = 128L
  val REGISTRY_ENTRIES_MAX: Int = 256
  private val MIB: Long = 1024L * 1024L

  def cache_limit_bytes: Long = {
    val env = Isabelle_System.getenv("ISABELLE_QUERY_SERVER_CACHE_MB")
    if (env == null || env.isEmpty) CACHE_MB_DEFAULT * MIB
    else
      try {
        val mb = env.toLong
        if (mb < 0) CACHE_MB_DEFAULT * MIB else Math.multiplyExact(mb, MIB)
      }
      catch {
        case _: NumberFormatException | _: ArithmeticException => CACHE_MB_DEFAULT * MIB
      }
  }

  private def saturated_sum(xs: Iterable[Long]): Long = {
    val it = xs.iterator
    var total = 0L
    var saturated = false
    while (it.hasNext && !saturated) {
      val x = it.next()
      if (x >= Long.MaxValue - total) saturated = true
      else total += x
    }
    if (saturated) Long.MaxValue else total
  }


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
    catch { case exn: Throwable if Theory.recoverable(exn) => (-1L, -1L) }

  private def is_fingerprint_file(p: JPath): Boolean = {
    val n = p.getFileName.toString
    n.endsWith(".thy") || n == "ROOT" || n == "ROOTS"
  }

  /* `extra` carries the theories discovery resolved OUTSIDE the root
     directory — a ROOT's `directories` clause may reach a sibling — which the
     walk below would otherwise never see. */
  def fingerprint(root: JPath, extra: Iterable[JPath]): Fingerprint = {
    val paths = mutable.LinkedHashSet.empty[JPath]
    for (p <- Discovery.walk(root, is_fingerprint_file)) paths += p
    for (p <- extra) paths += p
    Fingerprint(paths.iterator.map(p => p.toString -> attrs_of(p)).toMap)
  }


  /* ------------------------------------------------------------------ */
  /* one warm index                                                     */
  /* ------------------------------------------------------------------ */

  final class Index private[Query_Server] (val root: JPath) {
    val id: String = UUID.random_string()

    private final case class Cache_Key(stamp: (Long, Long), name: String,
      session: Option[String], table: Map[String, String])
    private final case class Cached(key: Cache_Key, section: Theory_Section, source_bytes: Long)

    private val lock = new Object

    /* A SECOND monitor, and it has to be: the parse below runs on `Par_List`
       worker threads while the calling thread holds `lock`, so a worker
       reaching for `lock` would deadlock against its own caller. */
    private val cache_lock = new Object

    /* Per file, keyed exactly as the jEdit index keys it: the file's own
       mtime+size, plus the identity of the root-wide keyword union, because a
       `keywords` clause added anywhere reparses everything.  The byte charge
       belongs to the retained normalized text, so it is computed once on a
       fresh parse and travels with a cache hit. */
    private var parsed = Map.empty[JPath, Cached]

    private var print: Option[Fingerprint] = None
    private var sections: List[Theory_Section] = Nil
    private var table: Map[String, String] = Map.empty
    private var built_ms: Long = 0L
    private var checked_ms: Long = 0L
    private var reparsed: Int = 0
    private var _uses: Long = 0L
    private var _source_bytes: Long = 0L

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
          "uses" -> _uses,
          "source_bytes" -> _source_bytes)
      }

    def source_bytes: Long = lock.synchronized(_source_bytes)

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

        val fresh = new java.util.concurrent.atomic.AtomicInteger(0)
        /* Workers read one immutable snapshot and return refresh-local values.
           The shared cache is replaced only after every worker succeeds, so a
           failed parallel parse cannot leave partially refreshed objects in
           the retained index. */
        val previous = cache_lock.synchronized(parsed)
        val built =
          Theory.map_bounded[(Discovery.Found, Map[String, String]), Option[(JPath, Cached)]](
            { case (found, own) =>
                val keywords = plan.table(own)
                val key = Cache_Key(attrs_of(found.path), found.name, found.session, keywords)
                previous.get(found.path) match {
                  case Some(cached) if cached.key == key => Some(found.path -> cached)
                  case _ =>
                    fresh.incrementAndGet()
                    Theory.parse(found, keywords).map { section =>
                      val bytes = section.source_utf8_bytes
                      found.path -> Cached(key, section, bytes)
                    }
                }
            },
            plan.found).flatten

        val committed = built.toMap
        val secs = built.map(_._2.section)
        val bytes = saturated_sum(committed.values.map(_.source_bytes))
        val next_print = Some(print1)
        cache_lock.synchronized { parsed = committed }

        sections = secs
        table = plan.union
        print = next_print
        reparsed = fresh.get()
        built_ms = System.currentTimeMillis() - t1
        _source_bytes = bytes
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

  /* Every open, run and close lifecycle in this JVM, one at a time.  In
     particular an index cannot be refreshed or detached between a run's
     refresh and the end of the CLI call that consumes its sections. */
  private val engine_lock = new Object
  /* Runtime transitions hold engine_lock, together with open/run/publish,
     and registry_lock for an atomic version snapshot. Volatile reads allow
     discovery to avoid engine_lock while a long query is executing.
     retain_indexes in version_info is EFFECTIVE retention: runtime on AND
     a positive environment budget. cache_limit_bytes remains the configured
     budget even while off; indexes/source bytes describe retained entries. */
  @volatile private var retention_enabled = true
  private def retain_indexes_locked: Boolean = retention_enabled && cache_limit_bytes > 0L
  private val registry_lock = new Object
  private final class Registry_Entry(val index: Index, var source_bytes: Long,
    var last_used: Long)
  private val registry = mutable.LinkedHashMap.empty[JPath, Registry_Entry]
  private var use_clock = 0L

  private def next_use_locked(): Long = {
    if (use_clock == Long.MaxValue) {
      var n = 0L
      for ((_, entry) <- registry.toList.sortBy(_._2.last_used)) {
        n += 1
        entry.last_used = n
      }
      use_clock = n
    }
    use_clock += 1
    use_clock
  }

  private def entry_for(root: JPath): (JPath, Registry_Entry) = {
    val key = Discovery.real(root)
    val entry =
      registry_lock.synchronized {
        registry.getOrElseUpdate(key,
          new Registry_Entry(new Index(key), 0L, next_use_locked()))
      }
    (key, entry)
  }

  private def entry_by_id(id: String): Option[(JPath, Registry_Entry)] =
    registry_lock.synchronized { registry.find(_._2.index.id == id) }

  private def detach(key: JPath, entry: Registry_Entry): Unit =
    registry_lock.synchronized {
      if (registry.get(key).contains(entry)) registry.remove(key)
    }

  private def retained_bytes_locked: Long =
    saturated_sum(registry.values.map(_.source_bytes))

  private def evict_locked(): Unit = {
    val limit = cache_limit_bytes
    while (registry.nonEmpty &&
      (registry.size > REGISTRY_ENTRIES_MAX || retained_bytes_locked > limit)) {
      val (key, _) = registry.minBy { case (root, entry) => (entry.last_used, root.toString) }
      registry.remove(key)
    }
  }

  /* Publish only after Index.refresh has atomically committed its snapshot.
     Registry and index monitors are never held together. */
  private def publish(key: JPath, entry: Registry_Entry, promote: Boolean): Boolean = {
    val bytes = entry.index.source_bytes
    registry_lock.synchronized {
      if (registry.get(key).contains(entry)) {
        val limit = cache_limit_bytes
        /* Admission precedes aggregate eviction.  A transient oversized (or
           zero-budget) entry must detach itself without flushing unrelated
           healthy roots merely because it is newest. */
        if (!retain_indexes_locked || bytes > limit) registry.remove(key)
        else {
          entry.source_bytes = bytes
          if (promote) entry.last_used = next_use_locked()
          evict_locked()
        }
      }
      registry.get(key).contains(entry)
    }
  }

  def registry_size: Int = registry_lock.synchronized(registry.size)

  def registry_source_bytes: Long = registry_lock.synchronized(retained_bytes_locked)

  def close(id: String): Boolean =
    engine_lock.synchronized {
      registry_lock.synchronized {
        registry.find(_._2.index.id == id) match {
          case Some((k, _)) => registry.remove(k); true
          case None => false
        }
      }
    }

  def close_root(root: JPath): Boolean =
    engine_lock.synchronized {
      registry_lock.synchronized { registry.remove(Discovery.real(root)).isDefined }
    }

  def close_all(): Int =
    engine_lock.synchronized {
      registry_lock.synchronized { val n = registry.size; registry.clear(); n }
    }

  /* One atomic transition and reply snapshot, ordered engine -> registry.
     Never take engine_lock while already holding registry_lock. off clears
     idle handles; in-flight runs finish before it can disable publication. */
  def cache_control(mode: String): JSON.Object.T = engine_lock.synchronized {
    registry_lock.synchronized {
      def clear(): Int = { val n = registry.size; registry.clear(); n }
      val closed = mode match {
        case "on" => retention_enabled = true; 0
        case "off" => retention_enabled = false; clear()
        case "clear" => clear()
        case _ => error("bad query cache mode: expected on, off, or clear")
      }
      version_info ++ JSON.Object("closed" -> closed)
    }
  }

  def open_indexes: List[JSON.Object.T] =
    registry_lock.synchronized(registry.valuesIterator.map(_.index).toList).map(_.stats)

  private def retention_message(bytes: Long): String =
    if (cache_limit_bytes == 0L)
      "server index retention is disabled by ISABELLE_QUERY_SERVER_CACHE_MB=0"
    else if (!retention_enabled)
      "server index retention is disabled by query_cache off"
    else
      "index too large for server retention: " + bytes +
        " normalized UTF-8 source bytes, budget " + cache_limit_bytes +
        " -- raise ISABELLE_QUERY_SERVER_CACHE_MB for the server"

  def open(root: JPath, limit: Int): JSON.Object.T =
    engine_lock.synchronized {
      if (!retain_indexes_locked) error(retention_message(0L))
      val (key, entry) = entry_for(root)
      try {
        entry.index.refresh(limit)
        if (!publish(key, entry, promote = true))
          error(retention_message(entry.index.source_bytes))
        entry.index.stats
      }
      catch {
        case exn: Throwable => detach(key, entry); throw exn
      }
    }


  /* ------------------------------------------------------------------ */
  /* running one request                                                */
  /* ------------------------------------------------------------------ */

  /* Every engine call in this JVM, one at a time — and since
     [p10-namespace-value] that is about the warm INDEX, not about a table.

     What it guards: `refresh` then `provide` is ONE logical step.  `Index` has
     its own monitor for its own fields, but a second request between the two
     could reparse the root and hand this run sections whose fingerprint was
     never the one this run checked — and `refresh_ms` / `used` are written
     across the pair.  Holding it for the whole run also bounds peak memory at
     one whole-corpus analysis rather than one per connected client, which for
     a process meant to live for days is the difference between a cache and a
     leak.  Two clients on two projects still serialise; nothing in the ENGINE
     requires that any more. */
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
    env: Map[String, String], limit: Int, index_id: Option[String] = None,
    host_ambient_root: Option[() => JPath] = None, allow_stdin: Boolean = true
  ): Result = {
    val out = new StringWriter
    val err = new StringWriter
    var refresh_ms = 0L
    engine_lock.synchronized {
      val pinned =
        index_id.filter(_.nonEmpty).map(id =>
          entry_by_id(id).getOrElse(
            error("no such index: " + id +
              " (expired or unknown; call query_open again)")))
      var refreshed: Option[(JPath, Registry_Entry)] = None
      var retained: Option[Index] = None
      var rc = 0
      var completed = false
      try {
        rc = CLI.run_result(argv, new Out(out), new Out(err), s => {
          /* THE REQUEST'S environment, and only it.  The server's own is an
             accident of whoever started it — a client that happened to export
             `$ISABELLE_QUERY_NAMESPACE=committed` used to pin the table for
             every later client of that JVM, with no way to tell from the
             argv.  Binding an explicit (possibly empty) map here is what makes
             the process environment unreachable rather than merely
             overridden. */
          s.env = k => env.get(k).filter(_.nonEmpty)
          pinned match {
            case Some((root, _)) => s.ambient_root = () => root
            case None =>
              host_ambient_root match {
                case Some(root) =>
                  s.ambient_root = () => Discovery.real(root().toAbsolutePath)
                case None =>
                  for (d <- cwd)
                    s.ambient_root = () => CLI.default_root_from(Paths.get(d), env_root)
              }
          }
          for ((pinned_root, _) <- pinned) {
            s.validate_explicit_root = root =>
              if (root != pinned_root)
                error("index " + index_id.getOrElse("") + " is pinned to " + pinned_root +
                  ", but the request resolved explicit root " + root)
          }
          if (!allow_stdin)
            s.stdin_source = () =>
              throw new CLI.Usage_Error("stdin input is unavailable in this host")
          s.index_provider = root =>
            if (pinned.isEmpty && !Files.isDirectory(root)) None
            else {
              val resolved = Discovery.real(root)
              val (key, entry) =
                pinned match {
                  case Some((pinned_root, pinned_entry)) =>
                    if (resolved != pinned_root)
                      error("index " + index_id.getOrElse("") + " is pinned to " +
                        pinned_root + ", but the request resolved root " + resolved)
                    (pinned_root, pinned_entry)
                  case None => entry_for(resolved)
                }
              val t0 = System.currentTimeMillis()
              try {
                entry.index.refresh(limit)
                refreshed = Some(key -> entry)
              }
              catch {
                case exn: Throwable => detach(key, entry); throw exn
              }
              finally { refresh_ms += System.currentTimeMillis() - t0 }
              entry.index.used()
              entry.index.provide(resolved)
            }
        })
        completed = true
      }
      finally {
        /* A successful refresh is accounted even when later CLI work returns
           nonzero or throws.  Only a completed exit 0 promotes recency. */
        for ((key, entry) <- refreshed) {
          if (publish(key, entry, promote = completed && rc == 0))
            retained = Some(entry.index)
        }
      }
      Result(rc, out.toString, err.toString, retained, refresh_ms)
    }
  }
}


/* ------------------------------------------------------------------ */
/* the commands                                                       */
/* ------------------------------------------------------------------ */

object Query_Server_Protocol {
  /* The stock service wrapper and embedded endpoint share these context-free
     bodies. Embedded hosts never manufacture a Server.Context or expose the
     unrelated stock session/lifecycle commands. */
  abstract class Query_Command(name: String) extends Server.Command(name) {
    def query_body: PartialFunction[Any, Any]
    final override def command_body: Server.Command_Body = {
      case (_, arg) if query_body.isDefinedAt(arg) => query_body(arg)
    }
  }

  def dispatch(name: String, arg: Any, allow_stdin: Boolean = true): Any = {
    val command = List(Version, Cache, Open, Run, Close).find(_.command_name == name)
      .getOrElse(error("Bad command " + quote(name)))
    val body = if (command eq Run) Run.body(allow_stdin) else command.query_body
    if (!body.isDefinedAt(arg)) error("Bad argument for command " + quote(name))
    body(arg)
  }

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

  /* The request's environment: exactly the variables `CLI.request_env` names,
     taken from the request and from nowhere else.  A client that sends none
     gets an EMPTY environment rather than the server's, which is the fix: an
     inherited variable is invisible in the argv and therefore undebuggable
     from the caller's side.  `env_root` stays as its own field because
     `query_open` — which has no argv — needs it too. */
  private def env_of(json: JSON.Object.T): Map[String, String] = {
    val obj =
      json.get("env") match {
        case Some(JSON.Object(m)) => m
        case _ => Map.empty[String, JSON.T]
      }
    (for {
      k <- CLI.request_env
      v <- obj.get(k).collect { case s: String if s.nonEmpty => s }
    } yield k -> v).toMap
  }


  /* `query_version` — who is answering, and is it the code the caller built.
     Cheap on purpose: a client may call it on every invocation. */
  object Version extends Query_Command("query_version") {
    override val query_body: PartialFunction[Any, Any] = {
      case () => Query_Server.version_info
      case JSON.Object(_) =>
        Query_Server.version_info ++ JSON.Object("open" -> Query_Server.open_indexes)
    }
  }

  /* Additive protocol-1 command; stale checks precede all policy changes. */
  object Cache extends Query_Command("query_cache") {
    override val query_body: PartialFunction[Any, Any] = {
      case JSON.Object(json) =>
        stale(JSON.string_default(json, "client_id").getOrElse(""))
        Query_Server.cache_control(
          JSON.string(json, "mode").getOrElse(error("missing \"mode\"")))
    }
  }

  /* `query_open` — build or re-validate a warm index for a root, and say what
     it cost.  Idempotent: opening an already-open root is the same stat sweep
     `query_run` does, not a reparse. */
  object Open extends Query_Command("query_open") {
    override val query_body: PartialFunction[Any, Any] = {
      case JSON.Object(json) =>
        stale(JSON.string_default(json, "client_id").getOrElse(""))
        val root = root_of(json, cwd_of(json), env_root_of(json))
        if (!Files.isDirectory(root)) error("not a directory: " + root)
        Query_Server.open(root, limit_of(json))
    }
  }

  /* `query_run` — one CLI invocation against a warm index.  `root` is
     optional: without it the CLIENT's working directory and `$ISABELLE_QUERY_ROOT`
     decide, by the same rule a typed command follows, which is what lets the
     thin client be a drop-in for the tool.

     `env` is optional and carries the request's own environment (see the
     header): a request that sends none is run with an EMPTY environment, not
     with the server's.

     The reply carries stdout, stderr and the exit status separately, and the
     status is DATA: exit 1 (unresolved subject) and exit 2 (bad root) are
     answers the CLI gives and the client re-emits.  Only a refusal the CLI
     cannot express — over the size limit, a stale component — is an `ERROR`. */
  object Run extends Query_Command("query_run") {
    override val query_body: PartialFunction[Any, Any] = body(allow_stdin = true)

    // Embedded hosts own their input stream (notably PIDE's MCP stdio).
    // Refuse consumption at Session.stdin_source, never by inspecting argv.
    def body(allow_stdin: Boolean): PartialFunction[Any, Any] = {
      case JSON.Object(json) =>
        stale(JSON.string_default(json, "client_id").getOrElse(""))
        val argv = JSON.strings(json, "argv").getOrElse(error("missing \"argv\""))
        val cwd = cwd_of(json)
        val env_root = env_root_of(json)

        val t0 = System.currentTimeMillis()
        /* ID lookup happens inside Query_Server.run's lifecycle lock.  The
           pinned root becomes the CLI session's ambient root; a normally
           parsed explicit root is validated at the resolved-root hook. */
        val res =
          Query_Server.run(argv, cwd, env_root, env_of(json), limit_of(json),
            index_id = JSON.string(json, "index_id").filter(_.nonEmpty),
            allow_stdin = allow_stdin)
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

  /* `query_close` — explicitly drop one or all automatically bounded indexes. */
  object Close extends Query_Command("query_close") {
    override val query_body: PartialFunction[Any, Any] = {
      case () => JSON.Object("closed" -> Query_Server.close_all())
      case JSON.Object(json) =>
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
  Query_Server_Protocol.Cache,
  Query_Server_Protocol.Open,
  Query_Server_Protocol.Run,
  Query_Server_Protocol.Close)
