/*  Title:      dev/p11reachprobe.scala

Direct checks and alternating-project benchmark for P11 S3's Reach closure
memo.  The identity checks use tiny valid theory headers, so they exercise the
real closure builder without requiring a project corpus.  Benchmark mode parses
two caller-supplied projects before timing only alternating closure lookups.
*/

package isabelle.query_dev


object P11_Reach_Probe {
  def main(args: Array[String]): Unit = {
    import isabelle.*
    import isabelle.query.{Reach, Theory, Theory_Section}

    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Path => JPath, Paths}
    import java.util.concurrent.{CountDownLatch, TimeUnit}

    Isabelle_System.init()

    if (args.headOption.contains("--bench")) benchmark(args.drop(1))
    else identity_checks()


    def benchmark(bench_args: Array[String]): Unit = {
      if (bench_args.length < 2 || bench_args.length > 3) {
        Console.err.println("usage: P11_Reach_Probe --bench ROOT_A ROOT_B [REPETITIONS]")
        sys.exit(2)
      }

      val root_a = Paths.get(bench_args(0))
      val root_b = Paths.get(bench_args(1))
      val repetitions = if (bench_args.length == 3) bench_args(2).toInt else 5
      if (!Files.isDirectory(root_a) || !Files.isDirectory(root_b) || repetitions <= 0) {
        Console.err.println("p11reachprobe: benchmark roots must be directories and repetitions positive")
        sys.exit(2)
      }

      val sections_a = Theory.parse_root(root_a)
      val sections_b = Theory.parse_root(root_b)
      if (sections_a.isEmpty || sections_b.isEmpty) {
        Console.err.println("p11reachprobe: benchmark roots must contain discovered theories")
        sys.exit(2)
      }

      /* Prime both projects before timing.  With the four-slot memo both are
         then hits; with the old one-slot memo every alternation rebuilds. */
      Reach.clear_cache()
      val closure_a = Reach.closure(sections_a)
      val closure_b = Reach.closure(sections_b)

      /* Validate the complete closures before timing.  `total_edges` walks
         every bitset row, so it must not contaminate lookup-only latency. */
      val edges_a = closure_a.total_edges
      val edges_b = closure_b.total_edges

      var checksum = 0L
      val started = System.nanoTime()
      var i = 0
      while (i < repetitions) {
        checksum += Reach.closure(sections_a).theories
        checksum += Reach.closure(sections_b).theories
        i += 1
      }
      val elapsed_ms = (System.nanoTime() - started) / 1000000.0

      println("P11REACHPROBE BENCH")
      println("  project A: " + root_a.toAbsolutePath.normalize +
        " (" + sections_a.length + " sections, " + edges_a + " closure edges)")
      println("  project B: " + root_b.toAbsolutePath.normalize +
        " (" + sections_b.length + " sections, " + edges_b + " closure edges)")
      println("  repetitions: " + repetitions + " (" + (2 * repetitions) + " alternating lookups)")
      println(f"  elapsed: $elapsed_ms%.3f ms")
      println("  lookup checksum: " + checksum)

      /* Theory.parse_root uses Par_List, whose worker threads keep this JVM
         alive after main returns.  Flush the complete measurement first, then
         terminate the successful benchmark explicitly. */
      System.out.flush()
      sys.exit(0)
    }


    def identity_checks(): Unit = {
      var failures = 0
      val faildemo = System.getenv("P11REACHPROBE_FAILDEMO") == "1"

      def check(name: String, ok: Boolean, detail: String = ""): Unit = {
        if (ok) println("  ok    " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
        else {
          failures += 1
          println("  FAIL  " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
        }
      }

      val scratch = Files.createTempDirectory("p11reachprobe-")
      val paths = new scala.collection.mutable.ListBuffer[JPath]

      def sections(name: String): List[Theory_Section] = {
        val path = scratch.resolve(name + ".thy")
        val text = "theory " + name + "\n  imports Main\nbegin\nend\n"
        Files.write(path, text.getBytes(StandardCharsets.UTF_8))
        paths += path
        List(Theory.parse_one(name, path, text))
      }

      try {
        val a = sections("P11_A")
        val b = sections("P11_B")
        val c = sections("P11_C")
        val d = sections("P11_D")
        val e = sections("P11_E")

        println("1. identity reuse -- A/B/A")
        Reach.clear_cache()
        val a1 = Reach.closure(a)
        Reach.closure(b)
        val a2 = Reach.closure(a)
        check("A survives an intervening B", (a1 eq a2) && !faildemo)

        println("2. identity, not structural equality")
        val equal_a = List(a.head)
        check("fixture lists are equal but distinct",
          a == equal_a && (a.asInstanceOf[AnyRef] ne equal_a.asInstanceOf[AnyRef]))
        Reach.clear_cache()
        val structural_1 = Reach.closure(a)
        val structural_2 = Reach.closure(equal_a)
        check("equal-but-distinct lists get distinct closures", structural_1 ne structural_2)
        check("each distinct identity is then reusable", Reach.closure(a) eq structural_1)

        println("3. four-entry least-recently-used eviction")
        Reach.clear_cache()
        val first_a = Reach.closure(a)
        val first_b = Reach.closure(b)
        Reach.closure(c)
        Reach.closure(d)
        check("four entries retain the oldest before overflow", Reach.closure(a) eq first_a)
        Reach.closure(e)
        check("recently touched A survives the fifth identity", Reach.closure(a) eq first_a)
        check("the least-recently-used B is evicted", Reach.closure(b) ne first_b)

        println("4. explicit clearing")
        Reach.clear_cache()
        val clear_keys = List(a, b, c, d)
        val before_clear = clear_keys.map(Reach.closure)
        Reach.clear_cache()
        val after_clear = clear_keys.map(Reach.closure)
        for (((before, after), j) <- before_clear.zip(after_clear).zipWithIndex)
          check("clear_cache drops cached closure " + (j + 1), after ne before)

        println("5. synchronized access")
        Reach.clear_cache()
        val workers = 8
        val gate = new CountDownLatch(1)
        val done = new CountDownLatch(workers)
        val answers = new Array[Reach.Closure](workers)
        for (j <- 0 until workers) {
          val thread = new Thread(() => {
            try {
              gate.await()
              answers(j) = Reach.closure(a)
            }
            finally done.countDown()
          })
          thread.setName("p11reachprobe-" + j)
          thread.start()
        }
        gate.countDown()
        val completed = done.await(10, TimeUnit.SECONDS)
        check("concurrent callers share one closure",
          completed && answers(0) != null && answers.forall(_ eq answers(0)),
          if (completed) "8 callers" else "timed out after 10 seconds")
      }
      finally {
        Reach.clear_cache()
        for (path <- paths) Files.deleteIfExists(path)
        Files.deleteIfExists(scratch)
      }

      println()
      if (failures == 0) println("P11REACHPROBE OK")
      else {
        Console.err.println("p11reachprobe: " + failures + " check(s) failed")
        sys.exit(1)
      }
    }
  }
}
