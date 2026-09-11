package isabelle.query.regression

import isabelle.query.*
import java.io.StringWriter
import java.nio.file.{Files, Path as JPath, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final case class CliResult(exit: Int, out: String, err: String)

object TestSupport {
  final class CheckFailure private[TestSupport](clue: String) extends AssertionError(clue)
  private[regression] val expectedFailures = mutable.LinkedHashMap.empty[String, Boolean]
  private var inExpectedFailure = false
  private[regression] val tests = mutable.LinkedHashMap.empty[String, Boolean]
  private[regression] val dispositions = mutable.LinkedHashMap.empty[String, (String, String)]
  private[regression] val failures = mutable.ArrayBuffer.empty[String]
  private var current = ""
  private[regression] val assertions = mutable.Map.empty[String, Int]
  private[regression] val notes = mutable.ArrayBuffer.empty[(String, String)]

  def test(id: String)(body: => Unit): Unit = {
    val duplicate = tests.contains(id) || expectedFailures.contains(id)
    if (duplicate) failures += s"duplicate test ID: $id"
    val failureCount = failures.size
    val previous = current
    current = id
    assertions(id) = 0
    try { body; tests(id) = !duplicate && failures.size == failureCount }
    catch { case NonFatal(e) =>
      tests(id) = false
      failures += s"$id: ${e.toString}"
    }
    finally { current = previous }
  }
  /** Only shared assertion failures qualify. The two upstream cases have direct
    * bodies: subcase collection is explicitly unsupported in this bracket. */
  def expectedFailure(id: String, reason: String)(body: => Unit): Unit = {
    val duplicate = tests.contains(id) || expectedFailures.contains(id)
    if (duplicate) failures += s"duplicate expected-failure ID: $id"
    disposition(id, "expected-failure", reason)
    val failureCount = failures.size
    expectedFailures(id) = false
    val previous = current
    val previousExpected = inExpectedFailure
    current = id
    inExpectedFailure = true
    assertions(id) = 0
    try {
      body
      failures += s"$id: XPASS (upstream expected assertion failure did not occur)"
    } catch {
      case e: CheckFailure =>
        if (!duplicate && assertions.getOrElse(id, 0) > 0 && failures.size == failureCount) {
          expectedFailures(id) = true
          note(s"expected assertion failure: ${e.getMessage}")
        } else failures += s"$id: invalid expected-failure assertion context"
      case NonFatal(e) => failures += s"$id: unexpected exception in expected-failure: $e"
    } finally {
      current = previous
      inExpectedFailure = previousExpected
    }
  }
  def note(clue: String): Unit = notes += current -> clue
  def subcase(clue: String)(body: => Unit): Unit = {
    if (inExpectedFailure)
      throw new IllegalStateException("expectedFailure supports direct bodies, not subcase collection")
    note(clue)
    try body catch { case NonFatal(e) =>
      failures += s"$current subcase $clue: ${e.toString}"
    }
  }
  def check(condition: Boolean, clue: => String = ""): Unit = {
    assertions(current) = assertions.getOrElse(current, 0) + 1
    if (!condition) throw new CheckFailure(clue)
  }
  def equal[A](actual: A, expected: A, clue: String = ""): Unit =
    check(actual == expected, s"$clue expected <$expected>, got <$actual>")
  def contains(text: String, part: String): Unit =
    check(text.contains(part), s"expected <$text> to contain <$part>")
  def notContains(text: String, part: String): Unit =
    check(!text.contains(part), s"expected <$text> not to contain <$part>")
  def raises(body: => Any): Throwable = {
    assertions(current) = assertions.getOrElse(current, 0) + 1
    val result = try { body; None } catch { case NonFatal(e) => Some(e) }
    result.getOrElse(throw new CheckFailure("expected an exception"))
  }
  def disposition(id: String, kind: String, reason: String): Unit = {
    if (dispositions.contains(id)) failures += s"duplicate disposition ID: $id"
    if (!Set("python-only", "optional", "process", "known-difference", "expected-failure")(kind))
      failures += s"unknown disposition $kind: $id"
    if (reason.trim.isEmpty) failures += s"empty disposition reason: $id"
    dispositions(id) = kind -> reason
  }
  def withProject[A](files: Seq[(String, String)])(body: JPath => A): A = {
    val root = Files.createTempDirectory("query-regression-")
    try {
      files.foreach { case (name, text) =>
        val path = root.resolve(name).normalize
        require(path.startsWith(root), s"fixture escapes project: $name")
        Files.createDirectories(path.getParent)
        Files.writeString(path, text)
      }
      body(root)
    } finally {
      val stream = Files.walk(root)
      try stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
  def parse(text: String, theory: String = "T", file: String = "T.thy",
    keywords: Map[String, String] = Map.empty, session: Option[String] = None
  ): Theory_Section = Theory.parse_one(theory, Paths.get(file), text, keywords, session)

  def cli(args: List[String], root: JPath,
    env: Map[String, String] = Map("ISABELLE_QUERY_NAMESPACE" -> "committed"),
    stdin: Array[String] = Array.empty
  ): CliResult = {
    val stdout = new StringWriter
    val stderr = new StringWriter
    val exit = CLI.run_result(args, new Out(stdout), new Out(stderr), s => {
      s.env = env.get
      s.ambient_root = () => CLI.default_root_from(root, s.env_root)
      s.stdin_source = () => stdin.clone()
    })
    CliResult(exit, stdout.toString, stderr.toString)
  }
}
