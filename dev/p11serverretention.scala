/*  Title:      dev/p11serverretention.scala

Deterministic direct probe for P11 server retention and the two CLI host seams.
The runner starts a fresh JVM per cache-setting mode, so process-owned registry
state and environment parsing have a known boundary.  No server socket, heap or
component registration is involved.
*/

package isabelle.query_dev


object P11_Server_Retention {
  import isabelle.*
  import isabelle.query.{CLI, Discovery, Query_Server, Theory}

  import java.io.StringWriter
  import java.nio.charset.StandardCharsets
  import java.nio.file.{Files, Path => JPath, Paths}
  import java.util.concurrent.atomic.AtomicReference
  import java.util.concurrent.{CountDownLatch, TimeUnit}


  private var checks = 0
  private var failures = 0

  private def check(name: String, ok: Boolean, detail: String = ""): Unit = {
    checks += 1
    if (ok) println("  ok    " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
    else {
      failures += 1
      println("  FAIL  " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
    }
  }

  private def number(obj: JSON.Object.T, key: String): Long =
    obj.get(key) match {
      case Some(n: Int) => n.toLong
      case Some(n: Long) => n
      case Some(n: Double) => n.toLong
      case Some(n: BigDecimal) => n.toLong
      case x => error("missing numeric " + key + ": " + x)
    }

  private def string(obj: JSON.Object.T, key: String): String =
    obj.get(key).collect { case s: String => s }
      .getOrElse(error("missing string " + key))

  private def write(path: JPath, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }

  private def theory(name: String, body: String): String =
    "theory " + name + " imports Pure begin\n" + body + "\nend\n"

  private def root(base: JPath, name: String, body: String): (JPath, JPath) = {
    val dir = base.resolve(name)
    val thy = dir.resolve(name + ".thy")
    write(thy, theory(name, body))
    write(dir.resolve("ROOT"), "session " + name + " = Pure + theories " + name + "\n")
    (dir, thy)
  }

  private def expected_weight_once(root: JPath): (Long, Int) = {
    val plan = Theory.plan(root)
    val sections = plan.found.flatMap { case (found, own) => Theory.parse(found, plan.table(own)) }
    (sections.map(_.text.getBytes(StandardCharsets.UTF_8).length.toLong).sum,
      sections.map(_.text.length).sum)
  }

  private def server_run(args: List[String], id: Option[String] = None,
    ambient: Option[() => JPath] = None, allow_stdin: Boolean = true, limit: Int = 0
  ): Query_Server.Result =
    Query_Server.run(args, None, None, Map.empty, limit, index_id = id,
      host_ambient_root = ambient, allow_stdin = allow_stdin)

  private def message(body: => Any): String =
    try { body; "<no error>" }
    catch { case exn: Throwable => Option(exn.getMessage).getOrElse(exn.toString) }

  private def live(id: String): Boolean =
    try { server_run(List("--help"), Some(id)); true }
    catch { case _: Throwable => false }

  private def stats(id: String): JSON.Object.T =
    Query_Server.open_indexes.find(s => string(s, "index_id") == id)
      .getOrElse(error("index is not retained: " + id))

  private def aggregate_ok: Boolean = {
    val sum = Query_Server.open_indexes.map(number(_, "source_bytes")).sum
    Query_Server.registry_source_bytes == sum && sum <= Query_Server.cache_limit_bytes
  }

  private def config(expected: Long): Unit =
    check("cache setting resolves to expected bytes",
      Query_Server.cache_limit_bytes == expected,
      Query_Server.cache_limit_bytes.toString)

  private def core(base: JPath): Unit = {
    Query_Server.close_all()
    val (small, small_file) = root(base, "P11_Small", "text \\<open>λ café needle\\<close>")
    val (expected0, chars0) = expected_weight_once(small)
    val opened0 = Query_Server.open(small, 0)
    val id = string(opened0, "index_id")
    check("open charges retained normalized UTF-8 bytes",
      number(opened0, "source_bytes") == expected0,
      number(opened0, "source_bytes") + " bytes")
    check("non-ASCII fixture distinguishes bytes from characters", expected0 > chars0,
      expected0 + " bytes / " + chars0 + " chars")

    check("pinned help remains root-independent", server_run(List("--help"), Some(id)).exit == 0)
    check("pinned version remains root-independent",
      server_run(List("--version"), Some(id)).exit == 0)
    check("ordinary invalid argv remains CLI exit 2",
      server_run(List("--definitely-invalid"), Some(id)).exit == 2)
    val same_file =
      server_run(List("-R", small.toString, "grep", "needle", small_file.toString), Some(id))
    check("same-root file-only request validates without consuming the index",
      same_file.exit == 0 && same_file.out.contains("needle"))

    val (other, _) = root(base, "P11_Other", "text \\<open>other\\<close>")
    val conflict = message {
      server_run(List("-R", other.toString, "grep", "needle", small_file.toString), Some(id))
    }
    check("conflicting file-only root is rejected before dispatch",
      conflict.contains("pinned to") && conflict.contains("explicit root"), conflict)
    val abbreviated = message {
      server_run(List("--roo", other.toString, "grep", "needle", small_file.toString), Some(id))
    }
    check("abbreviated conflicting root reaches the same validator",
      abbreviated.contains("pinned to") && abbreviated.contains("explicit root"), abbreviated)

    val hosted = server_run(List("summary"), ambient = Some(() => small), allow_stdin = false)
    check("lazy host ambient root supplies an ordinary root-addressed run",
      hosted.exit == 0 && hosted.index.nonEmpty)
    val grep_stdin =
      server_run(List("grep", "needle", "-"), ambient = Some(() => small), allow_stdin = false)
    check("search stdin is rejected through Session.stdin_source",
      grep_stdin.exit == 2 && grep_stdin.err.contains("stdin input is unavailable"))
    val lines_stdin =
      server_run(List("lines", "-", "1"), ambient = Some(() => small), allow_stdin = false)
    check("separate-file lines stdin uses the same rejection",
      lines_stdin.exit == 2 && lines_stdin.err.contains("stdin input is unavailable"))
    val locus_stdin =
      server_run(List("lines", "--", "-:1..2"), ambient = Some(() => small), allow_stdin = false)
    check("colon-locus lines stdin uses the same rejection",
      locus_stdin.exit == 2 && locus_stdin.err.contains("stdin input is unavailable"))
    val inert =
      server_run(List("grep", "-", "--help"), ambient = Some(() => small), allow_stdin = false)
    check("inert sentinel in help does not call stdin", inert.exit == 0)

    def serialized(name: String, contender: => Unit): Unit = {
      val held_entered = new CountDownLatch(1)
      val release_held = new CountDownLatch(1)
      val held_done = new CountDownLatch(1)
      val contender_started = new CountDownLatch(1)
      val contender_done = new CountDownLatch(1)
      val held_error = new AtomicReference[Throwable](null)
      val contender_error = new AtomicReference[Throwable](null)
      val held_result = new AtomicReference[Query_Server.Result](null)
      val held = new Thread(() => {
        try {
          held_result.set(server_run(List("summary"), ambient = Some(() => {
            held_entered.countDown()
            if (!release_held.await(10, TimeUnit.SECONDS))
              error("timed out waiting to release held run")
            small
          })))
        }
        catch { case exn: Throwable => held_error.set(exn) }
        finally held_done.countDown()
      })
      val competing = new Thread(() => {
        contender_started.countDown()
        try contender
        catch { case exn: Throwable => contender_error.set(exn) }
        finally contender_done.countDown()
      })
      held.setDaemon(true)
      competing.setDaemon(true)
      held.start()
      val entered = held_entered.await(10, TimeUnit.SECONDS)
      if (entered) competing.start()
      val started = entered && contender_started.await(10, TimeUnit.SECONDS)
      val blocked = started && !contender_done.await(250, TimeUnit.MILLISECONDS)
      release_held.countDown()
      val completed = held_done.await(10, TimeUnit.SECONDS) &&
        contender_done.await(10, TimeUnit.SECONDS)
      check(name, entered && started && blocked && completed &&
        held_error.get() == null && contender_error.get() == null &&
        held_result.get() != null && held_result.get().exit == 0,
        "blocked=" + blocked + ", completed=" + completed)
    }

    serialized("same-root query_open waits for a running query lifecycle",
      { Query_Server.open(small, 0); () })
    serialized("query_open waits for a running query lifecycle",
      { Query_Server.open(other, 0); () })
    serialized("a second query_run waits for a running query lifecycle",
      { server_run(List("summary"), ambient = Some(() => other)); () })
    val (close_root, _) = root(base, "P11_Close", "text \\<open>close\\<close>")
    val close_id = string(Query_Server.open(close_root, 0), "index_id")
    val status_before_close = Query_Server.open_indexes.exists(s =>
      string(s, "index_id") == close_id)
    serialized("query_close waits for a running query lifecycle",
      { Query_Server.close(close_id); () })
    val status_after_close = Query_Server.open_indexes.exists(s =>
      string(s, "index_id") == close_id)
    check("status snapshot sees the close only after lifecycle release",
      status_before_close && !status_after_close && !live(close_id))

    write(small_file, theory("P11_Small", "text \\<open>λ café needle and growth\\<close>"))
    val grown_expected = expected_weight_once(small)._1
    server_run(List("summary"), Some(id))
    check("source growth republishes cached retained-text weight",
      number(stats(id), "source_bytes") == grown_expected)
    write(small_file, theory("P11_Small", "text \\<open>λ\\<close>"))
    val shrunk_expected = expected_weight_once(small)._1
    server_run(List("summary"), Some(id))
    check("source shrink republishes cached retained-text weight",
      number(stats(id), "source_bytes") == shrunk_expected && shrunk_expected < grown_expected)

    val (race, race_file) = root(base, "P11_Race", "text \\<open>retained race text\\<close>")
    val race_open = Query_Server.open(race, 0)
    val race_id = string(race_open, "index_id")
    val race_bytes = number(race_open, "source_bytes")
    Files.delete(race_file)
    check("deleting backing file does not retroactively undercharge retained text",
      number(stats(race_id), "source_bytes") == race_bytes)

    val external_base = base.resolve("external-case")
    val external_src = external_base.resolve("src/External.thy")
    write(external_src, theory("External", "text \\<open>outside root\\<close>"))
    val external_root = external_base.resolve("project")
    write(external_root.resolve("ROOT"),
      "session P11_External = Pure +\n  directories \"../src\"\n  theories External\n")
    val external_open = Query_Server.open(external_root, 0)
    check("external theory is charged exactly once",
      number(external_open, "source_bytes") == expected_weight_once(external_root)._1)

    val empty = base.resolve("empty-first-open")
    Files.createDirectories(empty)
    val before = Query_Server.registry_size
    val failed = message { Query_Server.open(empty, 0) }
    check("failed first open leaves no registry entry",
      failed != "<no error>" && Query_Server.registry_size == before, failed)

    val multi = base.resolve("P11_Multi")
    write(multi.resolve("M1.thy"), theory("M1", "text \\<open>one\\<close>"))
    write(multi.resolve("M2.thy"), theory("M2", "text \\<open>two\\<close>"))
    write(multi.resolve("ROOT"), "session P11_Multi = Pure + theories M1 M2\n")
    val multi_id = string(Query_Server.open(multi, 0), "index_id")
    val before_failure = Query_Server.registry_source_bytes
    val refused_refresh = message {
      server_run(List("summary"), Some(multi_id), limit = 1)
    }
    check("real refresh refusal expires the old ID and removes its charge",
      refused_refresh.contains("project too large") && !live(multi_id) &&
        Query_Server.registry_source_bytes < before_failure && live(id), refused_refresh)
    check("aggregate remains exact and bounded after refresh failure", aggregate_ok)
    val recreated = server_run(List("-R", multi.toString, "summary"), limit = 0)
    val recreated_id = recreated.index.map(_.id).getOrElse("")
    check("repaired root-addressed request recreates an evicted index",
      recreated.exit == 0 && recreated_id.nonEmpty && recreated_id != multi_id &&
        live(recreated_id))
    check("aggregate remains exact and bounded after recreation", aggregate_ok)
  }

  private def lru(base: JPath): Unit = {
    Query_Server.close_all()
    val pad = "x" * 400000
    val (a, _) = root(base, "P11_A", "text \\<open>" + pad + "\\<close>")
    val (b, _) = root(base, "P11_B", "text \\<open>" + pad + "\\<close>")
    val (d, d_file) = root(base, "P11_D", "text \\<open>" + pad + "\\<close>")
    val (huge, _) = root(base, "P11_Huge", "text \\<open>" + ("z" * 1100000) + "\\<close>")
    val id_a = string(Query_Server.open(a, 0), "index_id")
    val id_b = string(Query_Server.open(b, 0), "index_id")
    server_run(List("summary"), Some(id_a))
    val id_d = string(Query_Server.open(d, 0), "index_id")
    check("ordinary aggregate pressure evicts the least-recent admissible root",
      live(id_a) && !live(id_b) && live(id_d))
    check("aggregate charge equals retained stats and is within budget", aggregate_ok)

    val huge_run = server_run(List("-R", huge.toString, "summary"))
    check("oversized root-addressed run is transient",
      huge_run.exit == 0 && huge_run.index.isEmpty)
    check("oversized transient run preserves unrelated retained IDs",
      live(id_a) && live(id_d))
    check("oversized transient cleanup leaves exact bounded accounting", aggregate_ok)
    val refused = message { Query_Server.open(huge, 0) }
    check("oversized query_open refuses", refused.contains("too large for server retention"), refused)
    check("refused oversized open preserves unrelated retained IDs",
      live(id_a) && live(id_d))
    check("refused oversized open leaves exact bounded accounting", aggregate_ok)

    write(d_file, theory("P11_D", "text \\<open>" + ("q" * 1100000) + "\\<close>"))
    val grown = server_run(List("summary"), Some(id_d))
    check("a retained root that grows oversized becomes transient",
      grown.exit == 0 && grown.index.isEmpty && !live(id_d))
    check("grown oversized root detaches only itself", live(id_a))
    check("grown oversized detach leaves exact bounded accounting", aggregate_ok)

    val recreated_b = server_run(List("-R", b.toString, "summary"))
    val recreated_b_id = recreated_b.index.map(_.id).getOrElse("")
    check("root-addressed run recreates an evicted root with a new ID",
      recreated_b.exit == 0 && recreated_b_id.nonEmpty && recreated_b_id != id_b &&
        live(recreated_b_id))
    check("explicit close expires only the selected recreated handle",
      Query_Server.close(recreated_b_id) && !live(recreated_b_id) && live(id_a))
    check("aggregate remains exact after explicit close", aggregate_ok)

    val (f, f_file) = root(base, "P11_F", "text \\<open>" + pad + "\\<close>")
    val opened_f = Query_Server.open(f, 0)
    val id_f = string(opened_f, "index_id")
    val before_f = number(opened_f, "source_bytes")
    server_run(List("summary"), Some(id_a))
    write(f_file, theory("P11_F", "text \\<open>" + ("r" * 700000) + "\\<close>"))
    val unresolved = server_run(List("callees", "p11_missing_subject"), Some(id_f))
    val after_f = expected_weight_once(f)._1
    check("successful refresh followed by unresolved lookup keeps CLI exit 1",
      unresolved.exit == 1)
    check("nonzero result publishes grown weight without promoting failed use",
      after_f > before_f && live(id_a) && !live(id_f),
      before_f + " -> " + after_f + " bytes")
    check("nonzero cleanup leaves exact bounded accounting", aggregate_ok)
  }

  private def zero(base: JPath): Unit = {
    Query_Server.close_all()
    val dir = base.resolve("zero")
    val file = dir.resolve("Zero.thy")
    write(file, "")
    val run = server_run(List("-R", dir.toString, "summary"))
    check("zero-byte root can answer transiently at budget zero", run.exit == 0)
    check("zero-byte root is not retained at budget zero",
      run.index.isEmpty && Query_Server.registry_size == 0)
    val refused = message { Query_Server.open(dir, 0) }
    check("query_open refuses unconditionally at budget zero",
      refused.contains("retention is disabled") && Query_Server.registry_size == 0, refused)
  }

  private def metadata(base: JPath): Unit = {
    Query_Server.close_all()
    var first = ""
    var last = ""
    for (i <- 0 to Query_Server.REGISTRY_ENTRIES_MAX) {
      val dir = base.resolve("meta-" + i)
      write(dir.resolve("M" + i + ".thy"), "")
      val id = string(Query_Server.open(dir, 0), "index_id")
      if (i == 0) first = id
      last = id
    }
    check("metadata cap retains exactly 256 zero-weight indexes",
      Query_Server.registry_size == Query_Server.REGISTRY_ENTRIES_MAX)
    check("metadata cap evicts the least-recent ID", !live(first) && live(last))
  }

  def main(args: Array[String]): Unit = {
    Isabelle_System.init()
    try {
      args.toList match {
        case List("--config", expected) => config(expected.toLong)
        case List("--core", base) => core(Paths.get(base))
        case List("--lru", base) => lru(Paths.get(base))
        case List("--zero", base) => zero(Paths.get(base))
        case List("--metadata", base) => metadata(Paths.get(base))
        case _ =>
          Console.err.println(
            "usage: P11_Server_Retention --config BYTES | --core/--lru/--zero/--metadata DIR")
          failures += 1
      }
      if (sys.env.get("P11SERVERRETENTION_FAILDEMO").contains("1"))
        check("deliberate failability marker", false)
    }
    catch {
      case exn: Throwable =>
        failures += 1
        Console.err.println("p11serverretention: unexpected failure: " +
          Option(exn.getMessage).getOrElse(exn.toString))
        exn.printStackTrace(Console.err)
    }
    finally { Query_Server.close_all() }

    println()
    if (failures == 0) println("P11SERVERRETENTION OK (" + checks + " checks)")
    else Console.err.println("p11serverretention: " + failures + " check(s) failed")
    /* Theory.plan/refresh starts Par_List workers which keep a standalone JVM
       alive after main returns.  Flush the complete verdict, then terminate. */
    Console.out.flush()
    Console.err.flush()
    sys.exit(if (failures == 0) 0 else 1)
  }
}
