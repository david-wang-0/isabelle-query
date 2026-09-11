/* Dedicated P13 host cache tests. No sockets, prover or live jEdit required. */
package isabelle.query_dev

import isabelle.*
import isabelle.query.*
import isabelle.jedit_query.Query_Index
import java.nio.file.{Files, Path => JPath}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.FileTime
import scala.collection.mutable

object P13_Cache {
  private var checks = 0
  private def check(name: String)(ok: => Boolean): Unit = {
    if (!ok) throw new AssertionError(name)
    checks += 1
    println("OK " + name)
  }
  private def write(p: JPath, text: String): Unit = {
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(UTF_8))
    // Distinguish same-size writes even on coarse filesystem clocks.
    Files.setLastModifiedTime(p, FileTime.fromMillis(
      Files.getLastModifiedTime(p).toMillis + 2000L))
  }
  private def thy(name: String, body: String): String =
    "theory " + name + " imports Pure begin\n" + body + "\nend\n"
  private def manifest(root: JPath, session: String, names: String): Unit =
    write(root.resolve("ROOT"), "session " + session + " = Pure + theories " + names + "\n")
  private def fail(body: => Any): Throwable = {
    val exn = try { body; None } catch { case e: Throwable => Some(e) }
    exn.getOrElse(throw new AssertionError("expected failure"))
  }
  private def num(obj: JSON.Object.T, key: String): Long = obj(key) match {
    case x: Int => x.toLong
    case x: Long => x
    case x: Double => x.toLong
    case x => throw new AssertionError("not a number: " + x)
  }

  private def jedit(root: JPath): Unit = {
    val a = root.resolve("A.thy")
    val b = root.resolve("B.thy")
    write(a, thy("A", "lemma disk_a: True sorry"))
    write(b, thy("B", "lemma disk_b: True sorry"))
    val extras = (3 to 20).map(i => "Extra" + i)
    extras.foreach(n => write(root.resolve(n + ".thy"), thy(n, "lemma extra: True sorry")))
    manifest(root, "Cache", (Seq("A", "B") ++ extras).mkString(" "))
    val index = Query_Index(root)
    val events = new mutable.ListBuffer[Query_Index.Status]
    val first = index.refreshed(Map.empty, s => events.synchronized { events += s; () })
    check("public UI progress reaches Ready") {
      events.head == Query_Index.Indexing(0, 0) &&
        events.exists { case Query_Index.Indexing(20, 20) => true; case _ => false } &&
        events.last.isInstanceOf[Query_Index.Ready]
    }
    val warm = index.refreshed(Map.empty)
    check("warm hit reuses sections in discovery order") {
      first.sections.map(_.path) == warm.sections.map(_.path) &&
        first.sections.zip(warm.sections).forall { case (x, y) => x eq y }
    }

    val overlay1 = Map(a -> thy("A", "lemma Aa: True sorry"),
      b -> thy("B", "lemma overlay_b: True sorry"))
    val dirty = index.refreshed(overlay1)
    check("dirty overlay wins over disk and stays immutable") {
      dirty.section_of(a).get.text.contains("lemma Aa:") &&
        first.section_of(a).get.text.contains("disk_a")
    }
    val overlay2 = overlay1.updated(a, thy("A", "lemma BB: True sorry"))
    check("fixture exercises equal-length Java hash collision") {
      overlay1(a).length == overlay2(a).length && overlay1(a).hashCode == overlay2(a).hashCode
    }
    val changed = index.refreshed(overlay2)
    check("equal-hash edit invalidates only edited buffer") {
      changed.section_of(a).get.text.contains("lemma BB:") &&
        (changed.section_of(b).get eq dirty.section_of(b).get)
    }

    // Fail after several bounded batches: this catches worker writes to the
    // retained cache even when the old public snapshot was left intact.
    for (injected <- List[Throwable](new OutOfMemoryError("p13 injected"),
      new InterruptedException("p13 injected"), new IllegalStateException("p13 injected"))) {
      val before_failure = index.snapshot.get
      val doomed = overlay2.updated(b, thy("B", "lemma never_published: True sorry"))
      val exn = fail(index.refreshed(doomed, {
        case Query_Index.Indexing(16, _) => throw injected
        case _ => ()
      }))
      check("refresh propagates " + injected.getClass.getSimpleName) {
        (exn eq injected) || (Exn.cause(exn) eq injected) ||
          (injected.isInstanceOf[InterruptedException] && Exn.is_interrupt(exn))
      }
      check("failed dirty refresh preserves published snapshot") {
        index.snapshot.contains(before_failure) && index.status.isInstanceOf[Query_Index.Failed]
      }
      val recovered = index.refreshed(overlay2)
      check("failed dirty refresh preserves old cache objects") {
        recovered.sections.zip(changed.sections).forall { case (x, y) => x eq y }
      }
      check("successful recovery has exact prior source") {
        recovered.section_of(b).get.text == changed.section_of(b).get.text
      }
    }

    val before_ready_failure = index.snapshot.get
    val ready_failure = new IllegalStateException("Ready observer failed")
    val ready_exn = fail(index.refreshed(overlay1, {
      case _: Query_Index.Ready => throw ready_failure
      case _ => ()
    }))
    check("Ready-only callback failure aborts before publication") {
      (ready_exn eq ready_failure) && index.snapshot.contains(before_ready_failure) &&
        index.status.isInstanceOf[Query_Index.Failed]
    }
    val after_ready_failure = index.refreshed(overlay2)
    check("Ready-only callback failure retains exact prior cache") {
      after_ready_failure.sections.zip(before_ready_failure.sections)
        .forall { case (x, y) => x eq y }
    }

    val before_invalidate = index.snapshot.get
    val refresh_starts = new java.util.concurrent.atomic.AtomicInteger(0)
    val completed = index.refreshed(overlay1, {
      case Query_Index.Indexing(0, 0) => refresh_starts.incrementAndGet(); ()
      case Query_Index.Indexing(n, total) if total > 0 && n == total =>
        index.invalidate()
        index.invalidate()
      case _ => ()
    })
    check("in-flight invalidation publishes coherent completed snapshot") {
      index.snapshot.contains(completed) && index.status.isInstanceOf[Query_Index.Ready] &&
        completed.section_of(a).get.text.contains("lemma Aa:") &&
        before_invalidate.section_of(a).get.text.contains("lemma BB:")
    }
    check("in-flight invalidation does not retry parsing") { refresh_starts.get() == 1 }
    val invalidated = index.refreshed(overlay1)
    check("in-flight invalidation leaves next refresh uncached") {
      invalidated.sections.zip(completed.sections).forall { case (x, y) => !(x eq y) }
    }

    // A reentrant successful refresh simulates an unsupported competing writer
    // deterministically, without timing or adding another parsing thread pool.
    var winner: Option[Query_Index.Snapshot] = None
    val competing = fail(index.refreshed(overlay1, {
      case _: Query_Index.Ready => winner = Some(index.refreshed(overlay2))
      case _ => ()
    }))
    check("competing successful publication is not overwritten") {
      Exn.message(competing).contains("index replaced during refresh") &&
        index.snapshot == winner && winner.get.section_of(a).get.text.contains("lemma BB:")
    }
    check("competing successful publication retains its cache") {
      index.refreshed(overlay2).sections.zip(winner.get.sections)
        .forall { case (x, y) => x eq y }
    }
    val before_invalidated_failure = index.snapshot.get
    fail(index.refreshed(overlay1, {
      case _: Query_Index.Ready => index.invalidate(); throw ready_failure
      case _ => ()
    }))
    check("Ready failure with invalidation preserves previous snapshot") {
      index.snapshot.contains(before_invalidated_failure) &&
        index.status.isInstanceOf[Query_Index.Failed]
    }
    check("Ready failure does not undo explicit invalidation") {
      index.refreshed(overlay2).sections.zip(before_invalidated_failure.sections)
        .forall { case (x, y) => !(x eq y) }
    }
    val disk = index.refreshed(Map.empty)
    check("closing dirty overlay reloads matching disk version") {
      disk.section_of(a).get.text.contains("disk_a") &&
        changed.section_of(a).get.text.contains("lemma BB:")
    }
    manifest(root, "Renamed", "A B")
    val renamed = index.refreshed(Map.empty)
    check("ROOT ownership change invalidates session identity") {
      renamed.sections.forall(_.session.contains("Renamed"))
    }
    write(b, "theory B imports Pure keywords \"p13_decl\" :: thy_decl begin\nend\n")
    val keywords = index.refreshed(Map.empty)
    check("keyword union invalidates unchanged sibling") {
      !(keywords.section_of(a).get eq renamed.section_of(a).get)
    }
    Files.delete(b)
    manifest(root, "Renamed", "A")
    val removed = index.refreshed(Map.empty)
    check("removed file leaves snapshot and cache") { removed.section_of(b).isEmpty }
    write(b, thy("B", "lemma reloaded: True sorry"))
    manifest(root, "Renamed", "A B")
    check("removed file can be reloaded") {
      index.refreshed(Map.empty).section_of(b).get.text.contains("reloaded")
    }
    val broken = index.refreshed(Map(a -> "theory A imports Pure begin\nlemma unfinished:\n"))
    check("partially edited source remains queryable without proof processing") {
      broken.section_of(a).get.text.contains("lemma unfinished:")
    }
    val link_root = root.resolveSibling("symlink")
    Files.createDirectories(link_root)
    val link = link_root.resolve("A.thy")
    Files.createSymbolicLink(link, a)
    manifest(link_root, "Links", "A")
    check("real-path dirty overlay wins through a symlink") {
      Query_Index(link_root).refreshed(overlay2).section_of(link).get.text.contains("lemma BB:")
    }
    Query_Index.forget_all()
  }

  private def server(root: JPath): Unit = {
    val a = root.resolve("A.thy")
    write(a, thy("A", "text ‹Unicode α 😀›\nlemma old: True sorry"))
    manifest(root, "Server", "A")
    Query_Server.cache_control("on")
    Query_Server.close_all()
    val initial = Query_Server.open(root, 0)
    val expected = Theory.parse_root(root).map(_.text.getBytes(UTF_8).length.toLong).sum
    check("server normalized UTF-8 budget matches encoded source") {
      num(initial, "source_bytes") == expected && Query_Server.registry_source_bytes == expected
    }
    check("server warm cache avoids reparsing") {
      num(Query_Server.open(root, 0), "reparsed") == 0
    }
    manifest(root, "ChangedOwner", "A")
    check("server ROOT ownership invalidates retained section") {
      num(Query_Server.open(root, 0), "reparsed") == 1
    }
    write(a, thy("A", "lemma changed_longer: True sorry"))
    Thread.currentThread().interrupt()
    val interrupted = try fail(Query_Server.open(root, 0)) finally Thread.interrupted()
    check("server cancellation is propagated") { Exn.is_interrupt(interrupted) }
    check("failed server refresh detaches retained accounting") {
      Query_Server.registry_size == 0 && Query_Server.registry_source_bytes == 0
    }
    val recovered = Query_Server.open(root, 0)
    check("server recovers with fresh complete handle") {
      recovered("index_id") != initial("index_id") && num(recovered, "theories") == 1
    }
    val current = Theory.parse_root(root).map(_.text.getBytes(UTF_8).length.toLong).sum
    check("replacement is charged once; old bytes are released") {
      num(recovered, "source_bytes") == current && Query_Server.registry_source_bytes == current
    }
    Query_Server.close_all()
    check("close releases retained accounting") { Query_Server.registry_source_bytes == 0 }
  }

  def main(args: Array[String]): Unit = {
    Isabelle_System.init()
    val base = java.nio.file.Paths.get(args(0))
    if (Isabelle_System.getenv("P13CACHE_FAILDEMO") == "1")
      check("deliberate failability marker")(false)
    try {
      jedit(base.resolve("jedit"))
      server(base.resolve("server"))
      println("P13CACHE OK " + checks)
      Console.out.flush()
      // Isabelle's parallel worker pool keeps standalone JVMs alive.
      sys.exit(0)
    }
    catch {
      case exn: Throwable =>
        exn.printStackTrace(Console.err)
        Console.err.flush()
        sys.exit(1)
    }
  }
}
