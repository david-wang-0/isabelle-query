/*  Title:      dev/p11graph.scala

P11 S2 equivalence and allocation probe for Usage_Graph.build_call_graph.

The shell runner compiles this exact source against the archived baseline and
the revised component.  Each process parses its inputs once, emits stable graph
digests for four modes, checks hand-computed edge expectations, then measures
only repeated warm graph construction.  ThreadMXBean allocation is appropriate
here because build_call_graph is single-threaded; parsing is outside the timed
and allocated region.
*/

package isabelle.query_dev


object P11_Graph_Probe {
  def main(args: Array[String]): Unit = {
    import isabelle.*
    import isabelle.query.{Namespace, Theory, Theory_Section, Usage_Graph}

    import java.lang.management.ManagementFactory
    import java.nio.charset.StandardCharsets
    import java.nio.file.Paths
    import java.security.MessageDigest

    Isabelle_System.init()

    if (args.length < 2 || args.length > 4) {
      Console.err.println("usage: P11_Graph_Probe CORPUS FIXTURE [WARMUPS [REPS]]")
      sys.exit(2)
    }

    val corpus_root = Paths.get(args(0))
    val fixture_root = Paths.get(args(1))
    val warmups = if (args.length >= 3) args(2).toInt else 2
    val reps = if (args.length >= 4) args(3).toInt else 5
    if (warmups < 0 || reps < 3) {
      Console.err.println("warmups must be non-negative and reps must be at least 3")
      sys.exit(2)
    }

    val fixture = Theory.parse_root(fixture_root)
    val corpus = Theory.parse_root(corpus_root)
    if (fixture.isEmpty || corpus.isEmpty) {
      Console.err.println("probe input parsed to zero theory sections")
      sys.exit(2)
    }

    def graph(sections: List[Theory_Section], derived: Boolean, reach: String):
        Usage_Graph.Call_Graph =
      Usage_Graph.build_call_graph(sections, derived = derived, reach = reach,
        namespace = Namespace.census)

    def digest(g: Usage_Graph.Call_Graph): String = {
      val md = MessageDigest.getInstance("SHA-256")
      def add(s: String): Unit = {
        md.update(s.getBytes(StandardCharsets.UTF_8))
        md.update('\n'.toByte)
      }
      for (n <- g.all_names.toList.sorted) add("N\t" + n)
      for ((callee, callers) <- g.callers.toList.sortBy(_._1);
           caller <- callers.toList.sorted) add("R\t" + callee + "\t" + caller)
      for ((caller, callees) <- g.callees.toList.sortBy(_._1);
           callee <- callees.toList.sorted) add("E\t" + caller + "\t" + callee)
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    }

    val fixture_off = graph(fixture, derived = false, reach = "name")
    val fixture_on = graph(fixture, derived = true, reach = "name")

    def fixture_errors(off: Usage_Graph.Call_Graph,
      on: Usage_Graph.Call_Graph): List[String] = {
      val errors = scala.collection.mutable.ListBuffer.empty[String]
      def edge(g: Usage_Graph.Call_Graph, caller: String, callee: String): Boolean =
        g.callees.getOrElse(caller, Set.empty)(callee)
      def expect(label: String, actual: Boolean): Unit =
        if (!actual) errors += label

      expect("direct repeated token is one edge",
        off.callees.getOrElse("use_direct", Set.empty).count(_ == "repeat_target") == 1 &&
          edge(off, "use_direct", "repeat_target"))
      expect("derived spelling is absent with derived off", !edge(off, "use_derived", "made"))
      expect("derived spelling maps to base with derived on", edge(on, "use_derived", "made"))
      expect("real derived-looking name keeps direct identity",
        edge(on, "use_collision", "base_def") && !edge(on, "use_collision", "base"))
      expect("symbolic name survives the symbol pass",
        edge(off, "use_symbolic", "sym\\<^sub>x"))
      expect("method-position shadow is excluded", !edge(off, "shadow_method", "simp"))
      expect("fact-position shadow is retained", edge(off, "shadow_fact", "simp"))
      errors.toList
    }

    val errors = fixture_errors(fixture_off, fixture_on)
    if (errors.nonEmpty) {
      errors.foreach(e => println("FIXTURE_FAIL " + e))
      sys.exit(1)
    }
    println("FIXTURE_OK derived-off/on direct-collision repeated symbolic shadowed")

    /* Demonstrate that the hand-computed checker rejects a missing required
       edge, without changing source or relying on a second build. */
    val damaged = fixture_on.copy(
      callees = fixture_on.callees.updated("use_derived",
        fixture_on.callees.getOrElse("use_derived", Set.empty) - "made"))
    if (fixture_errors(fixture_off, damaged).isEmpty) {
      println("FAILABILITY_FAIL fixture checker accepted a removed derived edge")
      sys.exit(1)
    }
    println("FAILABILITY_OK removed derived edge is rejected")

    for (derived <- List(false, true); reach <- List("name", "closure")) {
      val g = graph(corpus, derived, reach)
      val edges = g.callees.valuesIterator.map(_.size).sum
      println(s"EQUIV derived=$derived reach=$reach names=${g.all_names.size} " +
        s"edges=$edges sha256=${digest(g)}")
    }

    val bean0 = ManagementFactory.getThreadMXBean
    val bean = bean0 match {
      case b: com.sun.management.ThreadMXBean if b.isThreadAllocatedMemorySupported =>
        if (!b.isThreadAllocatedMemoryEnabled) b.setThreadAllocatedMemoryEnabled(true)
        Some(b)
      case _ => None
    }
    if (bean.isEmpty) {
      Console.err.println("ThreadMXBean allocated-byte measurement is unavailable")
      sys.exit(2)
    }
    val thread_id = Thread.currentThread().getId
    def median(xs: Array[Long]): Long = xs.sorted.apply(xs.length / 2)
    val source_chars = corpus.iterator.map(_.text.length.toLong).sum
    for (derived <- List(false, true)) {
      var sink: Usage_Graph.Call_Graph = null
      var i = 0
      while (i < warmups) {
        sink = graph(corpus, derived = derived, reach = "name")
        i += 1
      }
      val times = new Array[Long](reps)
      val allocs = new Array[Long](reps)
      i = 0
      while (i < reps) {
        val before_alloc = bean.get.getThreadAllocatedBytes(thread_id)
        val before_time = System.nanoTime()
        sink = graph(corpus, derived = derived, reach = "name")
        times(i) = System.nanoTime() - before_time
        allocs(i) = bean.get.getThreadAllocatedBytes(thread_id) - before_alloc
        i += 1
      }
      if (sink == null || sink.all_names.isEmpty) sys.error("benchmark result was not consumed")
      val ns = median(times)
      val allocated = median(allocs)
      val mchars_s = source_chars.toDouble / (ns.toDouble / 1.0e9) / 1.0e6
      println(f"BENCH derived=$derived sections=${corpus.length}%d source_chars=$source_chars%d " +
        f"warmups=$warmups%d reps=$reps%d median_ms=${ns / 1.0e6}%.3f " +
        f"median_alloc_bytes=$allocated%d mchars_per_s=$mchars_s%.3f")
    }

    System.out.flush()
    sys.exit(0)
  }
}
