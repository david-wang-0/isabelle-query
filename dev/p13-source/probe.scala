package isabelle.query

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

object P13_Source {
  private def same(actual: Array[String], expected: Array[String]): Unit =
    assert(actual.sameElements(expected), "source lines differ")

  def main(args: Array[String]): Unit = {
    if (args.length == 2 && args(0) == "--retain") {
      // Standalone command ownership: Par_List starts non-daemon core workers.
      // Release them on success or failure after the diagnostic snapshot is read.
      try retain(java.nio.file.Path.of(args(1)))
      finally isabelle.Isabelle_Thread.pool.shutdown()
      return
    }
    if (args.contains("--fail")) sys.error("deliberate P13 source failure")
    val path = Files.createTempFile("p13-source", ".thy")
    try {
      val unicode = "α ∀ \\<alpha> 😀" + '\ud800' + "x" + '\udfff'
      val lines = Array("theory Snapshot imports Main begin", "", "text ‹source note›",
        "lemma sample:", "  assumes a: \"P α\"", "  shows \"P α\"",
        "  using a", "  by simp", "", "end", unicode, "")
      val sec = Theory.parse_source("Snapshot", path, lines)
      same(sec.lines, lines)
      assert(sec.text == lines.mkString("\n"))
      assert(sec.source_utf8_bytes == sec.text.getBytes(UTF_8).length.toLong)
      val e = sec.entries.find(_.name == "sample").get
      same(sec.slice(e.thy_line, e.body_end_line), lines.slice(3, 8))
      val declaration = e.text
      assert(declaration.contains("assumes a:"))
      assert(declaration.contains("shows"))
      // Inclusive extents, zero/default/larger context, reversed and clipped ends.
      for (context <- List(0, 2, 100); at <- List(1, 5, lines.length))
        same(sec.slice(at - context, at + context),
          lines.slice((at - context - 1) max 0, (at + context) min lines.length))
      same(sec.slice(8, 2), Array.empty[String])
      same(sec.slice(Int.MinValue, Int.MaxValue), lines)
      same(sec.live_source, Model.blank_all(lines, sec.regions.nonisar))
      same(sec.outer_source, Model.blank_all(lines, sec.regions.inner))
      val old = sec.text
      Files.writeString(path, "theory Changed begin end")
      lines(0) = "mutated caller array"
      assert(sec.text == old && e.text == declaration)
      val newer = Theory.parse_one("Changed", path, Files.readString(path))
      assert(newer.text != sec.text)
      Files.delete(path)
      assert(sec.text == old) // cache eviction/reload cannot silently substitute disk
      val got = sec.lines
      got(0) = "mutated returned array"
      assert(sec.text == old)

      // Cross-block surrogate pair, lone surrogates, long line and empty final line.
      val long = "x" * (Model.Source.block_chars - 1) + "😀" + unicode +
        "z" * (Model.Source.block_chars * 3)
      val bigLines = Array(long, "", unicode, "")
      val big = new Theory_Section("Big", path, Nil, bigLines, Regions.empty_result)
      assert(big.source_storage_bytes < big.text.length / 5)
      same(big.lines, bigLines)
      same(big.slice(2, 4), bigLines.slice(1, 4))
      assert(big.source_utf8_bytes == big.text.getBytes(UTF_8).length.toLong)
      // Concurrent readers never share mutable decode state.
      val threads = (1 to 4).map(_ => new Thread(() => {
        for (_ <- 1 to 20) { same(big.lines, bigLines); assert(big.line(0) == long) }
      }))
      val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
      threads.foreach(_.setUncaughtExceptionHandler((_, ex) => failure.set(ex)))
      threads.foreach(_.start()); threads.foreach(_.join())
      assert(failure.get() == null, String.valueOf(failure.get()))

      // A corrupt block must fail, never return a successful partial excerpt.
      val corrupt = Model.Source(Array("a" * 32767, "b" * 32768))
      val field = corrupt.getClass.getDeclaredFields.find(_.getType.isArray).get
      field.setAccessible(true)
      val blocks = field.get(corrupt).asInstanceOf[Array[Object]]
      val bytesField = blocks(1).getClass.getDeclaredFields.find(_.getType == classOf[Array[Byte]]).get
      bytesField.setAccessible(true)
      val bytes = bytesField.get(blocks(1)).asInstanceOf[Array[Byte]]
      bytes(0) = 0
      assert(corrupt.reader.slice(0, 10) == "a" * 10, "snippet decoded unrelated block")
      var rejected = false
      try corrupt.text catch { case scala.util.control.NonFatal(_) => rejected = true }
      assert(rejected, "corrupt source accepted")
      assert(big.line(0) == long)

      // Raw fallback covers arbitrary UTF-16, including malformed editor text.
      val random = new scala.util.Random(13013)
      val raw = new String(Array.fill[Char](70000)(random.nextInt(65536).toChar))
      val snapshot = Model.Source(Array(raw))
      assert(snapshot.text == raw)
      assert(snapshot.utf8_bytes == raw.getBytes(UTF_8).length.toLong)
      for (a <- List(0, 1, 32760, 65530); b <- List(a, (a + 30) min raw.length))
        assert(snapshot.reader.slice(a, b) == raw.substring(a, b))
      for (empty <- List(Array.empty[String], Array(""), Array("", ""))) {
        val section = new Theory_Section("Empty", path, Nil, empty, Regions.empty_result)
        same(section.lines, empty)
        same(section.slice(1, 100), empty)
        assert(section.text == empty.mkString("\n"))
      }
      // Broken/legacy and mixed prose retain the existing source-only region rules.
      val broken = Array("theory Broken begin", "(* unfinished", "lemma hidden: True")
      val brokenSec = Theory.parse_source("Broken", path, broken)
      same(brokenSec.lines, broken)
      assert(!brokenSec.entries.exists(_.name == "hidden"))
      val mixed = Array("by simp (* comment *)", "(* α *)", "\u00a0", "ML ‹foo", "bar›")
      val (split, starts) = Py.split_lines(mixed.mkString("\n"))
      val regions = Regions.scan(mixed.mkString("\n"), split, starts)
      assert(Regions.nonisar_ranges(split, regions.nonisar) == List((2, 2), (5, 5)))
      println("P13 SOURCE OK: exact snapshots, declarations, context, Unicode, concurrent reads")
    }
    finally Files.deleteIfExists(path)
    if (args.length == 2 && args(0) == "--profile") profile(java.nio.file.Path.of(args(1)))
  }
  // In-process diagnostic avoids cross-process PID/attach namespace assumptions.
  // The caller selects a heap; all sections stay reachable through the histogram.
  private def retain(root: java.nio.file.Path): Unit = {
    require(Files.isDirectory(root), "retained profile corpus directory required")
    val output = sys.env.getOrElse("P13_SOURCE_HISTOGRAM",
      sys.error("P13_SOURCE_HISTOGRAM output path required for --retain"))
    val sections = Theory.parse_root(root)
    require(sections.nonEmpty, "retained profile has no theories")
    val server = java.lang.management.ManagementFactory.getPlatformMBeanServer
    val histogram = server.invoke(
      new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
      "gcClassHistogram", Array[AnyRef](Array.empty[String]), Array("[Ljava.lang.String;"))
      .asInstanceOf[String]
    Files.writeString(java.nio.file.Path.of(output), histogram, UTF_8)
    println(s"P13 RETAINED theories=${sections.length} " +
      s"source_utf8_bytes=${sections.iterator.map(_.source_utf8_bytes).sum} " +
      s"source_storage_bytes=${sections.iterator.map(_.source_storage_bytes).sum} histogram=$output")
    java.lang.ref.Reference.reachabilityFence(sections)
  }

  // Opt-in source representation measurement; no parsing, JVM heap or RSS claim.
  private def profile(root: java.nio.file.Path): Unit = {
    require(Files.isDirectory(root), "profile corpus directory required")
    val paths = Files.walk(root)
    var files = 0L
    var before = 0L
    var after = 0L
    var offsets = 0L
    val begin = System.nanoTime()
    try {
      val it = paths.iterator()
      while (it.hasNext) {
        val path = it.next()
        if (Files.isRegularFile(path) && path.toString.endsWith(".thy")) {
          val (lines, _) = Py.split_lines(Theory.read(path))
          val source = Model.Source(lines)
          val text = lines.mkString("\n")
          before += text.length.toLong * (if (text.forall(_ <= 255)) 1 else 2)
          after += source.stored_bytes
          offsets += (lines.length.toLong + 1) * 4
          assert(source.text == text, "profile source mismatch: " + path)
          files += 1
        }
      }
    }
    finally paths.close()
    require(files > 0, "profile corpus has no theories")
    println(s"P13 SOURCE PAYLOAD files=$files raw_compact_bytes=$before stored_bytes=$after " +
      s"saved_bytes=${before - after} offset_bytes=$offsets elapsed_ms=${(System.nanoTime() - begin) / 1000000}")
  }
}
