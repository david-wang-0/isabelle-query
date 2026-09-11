package isabelle.query.regression

import isabelle.JSON
import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

object Runner {
  private def obj(value: Any): Map[String, Any] = value match {
    case JSON.Object(fields) => fields
    case _ => throw new IllegalArgumentException("expected JSON object")
  }
  private def rows(value: Any): List[Map[String, Any]] = value match {
    case xs: List[?] => xs.map(obj)
    case _ => throw new IllegalArgumentException("expected JSON array")
  }
  private def read(path: String): Map[String, Any] = obj(JSON.parse(Files.readString(Paths.get(path))))
  def main(args: Array[String]): Unit = {
    import TestSupport.*
    require(args.length >= 2, "Runner INVENTORY_ROOT SUITE...")
    val root = args(0)
    val suites = args.drop(1).toList
    val inventory = rows(read(s"$root/upstream-tests.json")("cases"))
    val selected = inventory.filter(r => suites.contains(r("suite").toString))
    val expected = selected.map(_("id").toString).toSet
    val expectedFailureWhitelist = selected.filter { row =>
      try {
        row.get("expected_failure_marker") match {
          case None =>
            require(!row.get("expected_failure").contains(true), "missing decorator metadata")
            false
          case Some(value) =>
            val marker = obj(value)
            val line = marker.getOrElse("line", 0).toString.toDouble
            val sourcePresent = marker.get("source").exists {
              case source: String => source.startsWith("tests/") && source.endsWith(".py")
              case _ => false
            }
            require(row.get("expected_failure").contains(true) && sourcePresent &&
              line >= 1 && line.isWhole &&
              marker.get("decorator").contains("unittest.expectedFailure") &&
              marker.get("source") == row.get("source"), "invalid upstream decorator metadata")
            true
        }
      } catch { case NonFatal(e) =>
        failures += s"${row("id")}: expected-failure inventory: $e"
        false
      }
    }.map(_("id").toString).toSet
    if (inventory.map(_("id")).distinct.size != inventory.size)
      failures += "duplicate inventory IDs"
    try {
      val matrix = read(s"$root/coverage/matrix.json")
      val cases = rows(matrix("cases"))
      require(cases.size == 307 && cases.map(_("id")).distinct.size == 307,
        "matrix must contain 307 unique invocation IDs")
      cases.foreach { row =>
        require(Set("corpus", "process")(row("status").toString), "unknown matrix tier")
        require(row("reason").toString.trim.nonEmpty, "empty matrix reason")
        require(row("destination") == "dev/difftest.sh", "unknown matrix destination")
      }
    } catch { case NonFatal(e) => failures += s"matrix manifest: $e" }
    var manifests = List.empty[Map[String, Any]]
    suites.foreach { suite =>
      try {
        val manifest = read(s"$root/coverage/$suite.json")
        require(manifest("suite") == suite, s"wrong manifest suite: $suite")
        val entries = rows(manifest("cases"))
        val own = selected.filter(_("suite") == suite).map(_("id")).toSet
        require(entries.map(_("id")).toSet == own, s"non-exhaustive manifest: $suite")
        entries.foreach { row =>
          require(Set("ported", "python-only", "optional", "process", "known-difference", "expected-failure")(
            row("status").toString), s"unknown manifest status: ${row("id")}")
          val upstreamExpected = expectedFailureWhitelist(row("id").toString)
          require((row("status") == "expected-failure") == upstreamExpected,
            s"upstream expected-failure marker/status disagreement: ${row("id")}")
          val dest = row("destination").toString
          require(dest.startsWith(s"tests/scala/$suite/") && !dest.split('/').contains(".."),
            s"invalid destination: $dest")
          require(Files.isRegularFile(Paths.get(root).getParent.getParent.resolve(dest)),
            s"missing destination: $dest")
        }
        manifests :::= entries
      } catch { case NonFatal(e) => failures += s"$suite manifest: $e" }
      try {
        val name = s"${suite.head.toUpper}${suite.tail}Suite"
        val cls = Class.forName(s"isabelle.query.regression.$name$$")
        cls.getMethod("run").invoke(cls.getField("MODULE$").get(null))
      } catch { case NonFatal(e) => failures += s"$suite suite aborted: $e" }
    }
    if (manifests.map(_("id")).distinct.size != manifests.size)
      failures += "duplicate manifest IDs"
    val observed = tests.keySet.toSet ++ dispositions.keySet
    (expected -- observed).toList.sorted.foreach(id => failures += s"missing runtime ID: $id")
    (observed -- expected).toList.sorted.foreach(id => failures += s"unknown runtime ID: $id")
    manifests.foreach { row =>
      val id = row("id").toString
      val status = row("status").toString
      if (status == "ported") {
        if (!tests.contains(id) || dispositions.contains(id)) failures += s"ported/runtime disagreement: $id"
      } else {
        val reason = row.getOrElse("reason", "").toString
        if (reason.trim.isEmpty || dispositions.get(id) != Some(status -> reason))
          failures += s"disposition/runtime disagreement: $id"
        if (status == "known-difference") {
          if (!tests.getOrElse(id, false) || assertions.getOrElse(id, 0) == 0)
            failures += s"known-difference lacks passing assertions: $id"
        } else if (status == "expected-failure") {
          if (!expectedFailures.getOrElse(id, false) || assertions.getOrElse(id, 0) == 0 || tests.contains(id))
            failures += s"expected-failure lacks a verified shared assertion failure: $id"
        } else if (status != "optional" && tests.contains(id))
          failures += s"nonported ID counted as test: $id"
      }
    }
    dispositions.foreach { case (id, (kind, reason)) => println(s"$kind $id: $reason") }
    notes.foreach { case (id, note) => println(s"NOTE $id: $note") }
    failures.foreach(f => System.err.println(s"FAIL $f"))
    val passed = tests.count { case (id, ok) => ok && expected(id) && !dispositions.contains(id) }
    println(s"Scala regression: $passed passed, ${dispositions.size} explicit nonpass dispositions, " +
      s"${notes.size} subcase notes, ${failures.size} failures; ${observed.size}/${expected.size} IDs")
    // Isabelle services may leave non-daemon threads; this is a standalone runner.
    sys.exit(if (failures.nonEmpty) 1 else 0)
  }
}
