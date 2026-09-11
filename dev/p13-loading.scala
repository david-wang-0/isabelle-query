package isabelle.query

import isabelle.*
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.file.{Files, Path => JPath}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

object P13_Loading {
  private def check(ok: Boolean, name: String): Unit = {
    if (!ok) throw new AssertionError(name)
    println("OK " + name)
  }

  private def raised(body: => Any): Throwable =
    try { body; throw new AssertionError("expected exception") }
    catch { case e: Throwable => e }

  // Inject at the actual read boundary, without mutable production test hooks.
  private def failing_path(path: JPath, error: Throwable): JPath =
    Proxy.newProxyInstance(classOf[JPath].getClassLoader, Array(classOf[JPath]),
      new InvocationHandler {
        def invoke(proxy: Object, method: Method, args: Array[Object]): Object =
          if (method.getName == "toString") throw error
          else method.invoke(path, (if (args == null) Array.empty[Object] else args)*)
      }).asInstanceOf[JPath]

  def main(args: Array[String]): Unit = {
    try { run(args); System.exit(0) }
    catch { case e: Throwable => e.printStackTrace(); System.exit(1) }
  }

  private def run(args: Array[String]): Unit = {
    if (args.contains("--fail")) check(false, "deliberate failability marker")
    check(CLI.version == "0.8.1-scala.0.2", "CLI release version")
    val version_out = new java.io.StringWriter
    val version_err = new java.io.StringWriter
    check(CLI.run_result(List("--version"), new Out(version_out), new Out(version_err)) == 0 &&
      version_out.toString.trim == "query 0.8.1-scala.0.2" &&
      version_err.toString.isEmpty, "CLI --version output")
    val root = java.nio.file.Paths.get(args(0))
    Files.createDirectories(root)
    val path = root.resolve("A.thy")
    val cancelled_with_cause = new java.util.concurrent.CancellationException("cancelled")
    cancelled_with_cause.initCause(new java.io.IOException("underlying operation"))
    val intermediate_fatal = new OutOfMemoryError("intermediate fatal")
    intermediate_fatal.initCause(new java.io.IOException("ordinary deepest cause"))
    val intermediate_interrupt = new InterruptedException("intermediate interrupt")
    intermediate_interrupt.initCause(new java.io.IOException("ordinary deepest cause"))
    val errors = List[Throwable](new OutOfMemoryError("synthetic"),
      new StackOverflowError("synthetic"), new LinkageError("synthetic"),
      new InterruptedException("synthetic"), Exn.Interrupt(),
      new java.util.concurrent.CancellationException("synthetic"),
      new RuntimeException(new OutOfMemoryError("wrapped")),
      new RuntimeException(new InterruptedException("wrapped")),
      cancelled_with_cause, new RuntimeException(cancelled_with_cause),
      new RuntimeException(intermediate_fatal), new RuntimeException(intermediate_interrupt))
    for ((error, i) <- errors.zipWithIndex) {
      val p = failing_path(path, error)
      val found = Discovery.Found("A", p, None)
      check(Exn.cause(raised(Theory.parse(found, Map.empty))) eq Exn.cause(error),
        s"parse propagates fault $i")
      check(Exn.cause(raised(Theory.header_keywords(p))) eq Exn.cause(error),
        s"header propagates fault $i")
      check(Exn.cause(raised(Discovery.thy_imports(p))) eq Exn.cause(error),
        s"discovery import read propagates fault $i")
      var continued = false
      val census_err = new java.io.StringWriter
      val groups = Iterator[(String, () => List[Theory_Section])](
        "failed" -> (() => throw error), "later" -> (() => { continued = true; Nil }))
      check(raised(Shape_Cmds.cmd_shape_census(new Out(new java.io.StringWriter),
        new Out(census_err), groups, None)) eq error, s"census propagates fault $i")
      check(!continued && census_err.toString.isEmpty, s"census aborts without skip $i")
      val got = raised(Theory.map_bounded((n: Int) => {
        if (n == 0) throw error else n
      }, (0 until 12).toList))
      check((Exn.cause(got) eq Exn.cause(error)) ||
        (Exn.is_interrupt(error) && Exn.is_interrupt(got)), s"parallel propagates fault $i")
      Thread.interrupted()
    }

    // All nodes are ordinary; a repeated identity terminates inspection. Do
    // not call Isabelle Exn.cause here: that independent helper assumes acyclic
    // chains and would hang the test even when our fallback policy is correct.
    val cycle_a = new RuntimeException("cycle A")
    val cycle_b = new java.io.IOException("cycle B")
    cycle_a.initCause(cycle_b)
    cycle_b.initCause(cycle_a)
    val cyclic_path = failing_path(path, cycle_a)
    check(Theory.parse(Discovery.Found("A", cyclic_path, None), Map.empty).isEmpty,
      "cyclic ordinary parse failure terminates and remains recoverable")
    check(Theory.header_keywords(cyclic_path).isEmpty,
      "cyclic ordinary header failure terminates and remains recoverable")
    check(Discovery.thy_imports(cyclic_path).isEmpty,
      "cyclic ordinary discovery failure terminates and remains recoverable")
    val cyclic_err = new java.io.StringWriter
    val cyclic_groups = Iterator[(String, () => List[Theory_Section])](
      "cycle" -> (() => throw cycle_a), "later" -> (() => Nil))
    val cyclic = Shape_Cmds.cmd_shape_census(new Out(new java.io.StringWriter),
      new Out(cyclic_err), cyclic_groups, None)
    check(cyclic.skipped == 1 && cyclic.loaded == 1 &&
      cyclic_err.toString.contains("session 'cycle' skipped"),
      "cyclic ordinary census failure terminates and preserves recovery")
    val missing = Discovery.Found("Missing", root.resolve("Missing.thy"), None)
    check(Theory.parse(missing, Map.empty).isEmpty, "unreadable source remains skipped")
    check(Theory.header_keywords(missing.path).isEmpty, "unreadable header remains empty")
    check(Discovery.thy_imports(missing.path).isEmpty, "unreadable imports remain empty")
    val ordinary_err = new java.io.StringWriter
    val ordinary_groups = Iterator[(String, () => List[Theory_Section])](
      "failed" -> (() => throw new java.io.IOException("synthetic")), "later" -> (() => Nil))
    val ordinary = Shape_Cmds.cmd_shape_census(new Out(new java.io.StringWriter),
      new Out(ordinary_err), ordinary_groups, None)
    check(ordinary.skipped == 1 && ordinary.loaded == 1 &&
      ordinary_err.toString.contains("session 'failed' skipped"), "ordinary census recovery")

    val cap = Theory.parallelism
    val started = new AtomicInteger
    val active = new AtomicInteger
    val peak = new AtomicInteger
    val entered = new CountDownLatch(cap)
    val completed = new CountDownLatch(cap - 1)
    val release = new CountDownLatch(1)
    val next_job = new CountDownLatch(1)
    val answer = new AtomicReference[List[Int]](Nil)
    val failure = new AtomicReference[Throwable]
    val worker = new Thread(() => {
      try answer.set(Theory.map_bounded((n: Int) => {
        started.incrementAndGet()
        if (n >= cap) next_job.countDown()
        val running = active.incrementAndGet()
        peak.accumulateAndGet(running, (a: Int, b: Int) => math.max(a, b))
        try {
          if (n < cap) {
            entered.countDown()
            if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("workers did not start")
          }
          if (n == 0) {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timed out")
          }
          else if (n < cap) completed.countDown()
          n
        }
        finally active.decrementAndGet()
      }, (0 until cap * 4 + 1).toList))
      catch { case e: Throwable => failure.set(e) }
    }, "p13-loading-probe")
    worker.start()
    try {
      check(entered.await(10, TimeUnit.SECONDS), "initial workers start")
      check(completed.await(10, TimeUnit.SECONDS), "later items complete before first")
      if (cap > 1) {
        check(next_job.await(10, TimeUnit.SECONDS), "next job starts while earlier slow job is blocked")
        check(started.get > cap && release.getCount == 1,
          "heterogeneous work progresses without a batch barrier")
      }
      else {
        check(!next_job.await(200, TimeUnit.MILLISECONDS), "one worker waits for current job")
        check(started.get == 1, "single worker limit")
      }
      check(answer.get.isEmpty, "ordered results not published before slow job succeeds")
    }
    finally { release.countDown(); worker.join(15000) }
    check(!worker.isAlive && failure.get == null, "bounded map completes")
    check(peak.get <= cap, "active parses bounded")
    check(answer.get == (0 until cap * 4 + 1).toList, "out-of-order completions preserve order")

    // Every worker holds an initial job before job zero fails. Peers return
    // normally after Isabelle's cancellation wakes them: the scheduler's stop
    // flag, not a fabricated interrupt from our task, must prevent another job.
    val peers_entered = new CountDownLatch(cap - 1)
    val hold_peers = new CountDownLatch(1)
    val failure_started = new AtomicInteger
    val original_fatal = new OutOfMemoryError("fail fast")
    var failed_result = List(-1)
    val failure_seen = try raised {
      failed_result = Theory.map_bounded((n: Int) => {
        failure_started.incrementAndGet()
        if (n == 0) {
          if (!peers_entered.await(10, TimeUnit.SECONDS))
            throw new AssertionError("peer workers did not start")
          throw original_fatal
        }
        else if (n < cap) {
          peers_entered.countDown()
          try {
            if (!hold_peers.await(10, TimeUnit.SECONDS))
              throw new AssertionError("failed worker did not cancel peers")
          }
          catch { case _: InterruptedException => () }
        }
        n
      }, (0 until cap * 5).toList)
    }
    finally hold_peers.countDown()
    check(failure_seen eq original_fatal, "fail fast preserves original fatal exception")
    check(failure_started.get == cap, "failure stops workers before claiming further jobs")
    check(failed_result == List(-1), "failed worker pool publishes no partial result")
    Thread.interrupted()
    check(Theory.map_bounded((n: Int) => n, Nil).isEmpty, "empty map")
    Thread.currentThread.interrupt()
    check(Exn.is_interrupt(raised(Theory.map_bounded((n: Int) => n, List(1)))),
      "pending caller interruption propagates")
    Thread.interrupted()

    Files.writeString(path, "theory A imports Main begin\nlemma unfinished: True\n")
    Files.writeString(root.resolve("Z.thy"),
      "theory Z imports Main keywords \"custom_fact\" :: thy_goal begin\nend\n")
    Files.writeString(root.resolve("B.thy"),
      "theory B imports Main begin\ncustom_fact sample: True by simp\nend\n")
    val plan = Theory.plan(root)
    val sequential = plan.found.flatMap { case (f, k) => Theory.parse(f, plan.table(k)) }
    val bounded = Theory.parse_root(root)
    def projection(xs: List[Theory_Section]) = xs.map(s =>
      (s.path, s.theory, s.text, s.entries.map(e => (e.name, e.thy_line, e.text))))
    check(projection(bounded) == projection(sequential), "root matches sequential projection")
    check(bounded.exists(_.entries.exists(_.name == "sample")), "root-wide custom keywords")
    check(bounded.exists(_.entries.exists(_.name == "unfinished")), "partially edited source accepted")

    val session = new CLI.Session(new Out(new java.io.StringWriter),
      new Out(new java.io.StringWriter))
    val fatal = new OutOfMemoryError("namespace")
    session.ambient_root = () => throw fatal
    session.env = _ => None
    check(raised(CLI.resolve_namespace(session, "callers")) eq fatal,
      "namespace fallback propagates fatal error")
    var published = List(-1)
    val later = new OutOfMemoryError("later batch")
    check(raised { published = Theory.map_bounded((n: Int) =>
      if (n == cap) throw later else n, (0 until cap * 3).toList) } eq later,
      "later job fatal propagates")
    check(published == List(-1), "failed map cannot publish partial success")
    println("P13 LOADING OK")
  }
}
