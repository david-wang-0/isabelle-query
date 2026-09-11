/*  Title:      dev/p11serverprobe.scala

Deterministic equivalence and timing probe for P11's fused server fingerprint
walk.  dev/p11serverprobe.sh supplies a synthetic nested fixture, an external
theory and a real corpus.  The reference function below is deliberately the
pre-P11 two-walk algorithm; comparing complete maps pins paths, deduplication
and mtime/size attributes together rather than comparing only counts.
*/

package isabelle.query_dev


object P11_Server_Probe {
  import isabelle.query.{Discovery, Query_Server}

  import java.nio.file.{Files, Path => JPath, Paths}
  import java.nio.file.attribute.BasicFileAttributes

  import scala.collection.mutable


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

  /* Exact pre-P11 implementation, kept in probe code only. */
  private def legacy_fingerprint(root: JPath,
    extra: Iterable[JPath]
  ): Query_Server.Fingerprint = {
    val paths = mutable.LinkedHashSet.empty[JPath]
    for (p <- Discovery.walk(root, q => q.getFileName.toString.endsWith(".thy"))) paths += p
    for (p <- Discovery.walk(root, is_root_file)) paths += p
    for (p <- extra) paths += p
    Query_Server.Fingerprint(paths.iterator.map(p => p.toString -> attrs_of(p)).toMap)
  }

  private def median(xs: List[Long]): Long = {
    val sorted = xs.sorted
    sorted(sorted.length / 2)
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 4) {
      Console.err.println(
        "usage: P11_Server_Probe FIXTURE_ROOT EXTERNAL_THEORY CORPUS REPETITIONS")
      sys.exit(2)
    }

    val fixture = Paths.get(args(0))
    val external = Paths.get(args(1))
    val corpus = Paths.get(args(2))
    val repetitions = args(3).toInt
    if (repetitions < 1) {
      Console.err.println("repetitions must be positive")
      sys.exit(2)
    }

    var checks = 0
    var failures = 0
    def check(name: String, ok: Boolean, detail: String = ""): Unit = {
      checks += 1
      if (ok) println("  ok    " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
      else {
        failures += 1
        println("  FAIL  " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
      }
    }

    println("1. nested and external fixture")
    val nested = fixture.resolve("nested/Nested.thy")
    val extra = List(external, nested, external)
    val legacy_fixture = legacy_fingerprint(fixture, extra)
    val fused_fixture = Query_Server.fingerprint(fixture, extra)
    val expected =
      Set(
        fixture.resolve("Top.thy"),
        fixture.resolve("ROOT"),
        fixture.resolve("ROOTS"),
        fixture.resolve("nested/Nested.thy"),
        fixture.resolve("nested/ROOT"),
        fixture.resolve("nested/ROOTS"),
        external).map(_.toString)

    check("complete fingerprint equals the two-walk algorithm",
      fused_fixture == legacy_fixture,
      fused_fixture.size.toString + " files")
    check("the expected nested ROOT, ROOTS and theories are present",
      fused_fixture.files.keySet == expected,
      fused_fixture.files.keySet.toList.sorted.mkString(", "))
    check("external and duplicate paths occur once",
      fused_fixture.files.keySet(external.toString) && fused_fixture.size == expected.size,
      fused_fixture.size.toString)
    check("directory symlinks are not followed",
      !fused_fixture.files.contains(fixture.resolve("linked/Hidden.thy").toString))
    check("unrelated files stay outside the fingerprint",
      !fused_fixture.files.contains(fixture.resolve("ignored.txt").toString))

    println()
    println("2. real-corpus equality and warm timing")
    /* Warm the classes and filesystem before measuring.  Alternating which
       algorithm goes first keeps page-cache order from always favouring the
       fused implementation. */
    legacy_fingerprint(corpus, Nil)
    Query_Server.fingerprint(corpus, Nil)
    val legacy_ns = new mutable.ListBuffer[Long]
    val fused_ns = new mutable.ListBuffer[Long]
    var corpus_equal = true
    for (i <- 0 until repetitions) {
      var old_print: Query_Server.Fingerprint = null
      var new_print: Query_Server.Fingerprint = null
      def old_once(): Unit = {
        val t0 = System.nanoTime()
        old_print = legacy_fingerprint(corpus, Nil)
        legacy_ns += System.nanoTime() - t0
      }
      def new_once(): Unit = {
        val t0 = System.nanoTime()
        new_print = Query_Server.fingerprint(corpus, Nil)
        fused_ns += System.nanoTime() - t0
      }
      if (i % 2 == 0) { old_once(); new_once() }
      else { new_once(); old_once() }
      corpus_equal &&= old_print == new_print
    }

    if (sys.env.get("P11SERVERPROBE_FAILDEMO").contains("1")) corpus_equal = false
    check("complete real-corpus fingerprints agree", corpus_equal)
    val old_ms = median(legacy_ns.toList) / 1000000.0
    val new_ms = median(fused_ns.toList) / 1000000.0
    check("both timing samples completed", legacy_ns.length == repetitions &&
      fused_ns.length == repetitions)
    println(f"P11SERVER TIMING median-of-$repetitions%d legacy=$old_ms%.3f ms fused=$new_ms%.3f ms")

    println()
    if (failures == 0) println("P11SERVERPROBE OK (" + checks + " checks)")
    else {
      println("P11SERVERPROBE FAIL (" + failures + " of " + checks + " checks)")
      sys.exit(1)
    }
  }
}
