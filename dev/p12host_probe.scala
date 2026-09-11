/*  Title:      dev/p12host_probe.scala

Focused P12 core/host checks. Compile against an isolated query_base copy and
run with a private USER_HOME under a timeout. Modes: core, zero, optout, fail,
stamp. Never run against a normal servers.db; main enforces a temporary home.
The existing P11 retention probe supplies cap/LRU/oversized regression coverage.
*/
package isabelle.query_dev

import isabelle.*
import isabelle.query.{Query_Host, Query_Server, Query_Server_Commands}
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.FileTime
import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

object P12_Host_Probe {
  private var checks = 0
  private def check(name: String)(ok: => Boolean): Unit = {
    assert(ok, name)
    checks += 1
    println("  ok  " + name)
  }
  private def str(o: JSON.Object.T, k: String): String = JSON.string(o, k).get
  private def num(o: JSON.Object.T, k: String): Long = JSON.long(o, k).get
  private def retains(o: JSON.Object.T): Boolean = JSON.bool(o, "retain_indexes").get
  private def fails(body: => Any): Boolean =
    try { body; false } catch { case _: RuntimeException => true }
  private def rows: List[Server.Info] =
    using(SQLite.open_database(Server.private_data.database))(Server.private_data.list)
  private def advertised: Server.Info = {
    val name = str(Query_Server.version_info, "host_name")
    rows.find(_.name == name).getOrElse(error("host not advertised"))
  }
  private def request(info: Server.Info, cmd: String, arg: JSON.Object.T = JSON.Object.empty,
    expected: Server.Reply = Server.Reply.OK): JSON.Object.T = {
    using(info.connection()) { c =>
      c.set_timeout(Time.seconds(5))
      assert(c.read_line_message().exists(_.startsWith("OK ")))
      c.write_line_message(cmd + " " + JSON.Format(arg))
      c.read_line_message().getOrElse(error("no reply")) match {
        case Server.Reply(reply, JSON.Object(obj)) if reply == expected => obj
        case other => error("unexpected reply: " + other)
      }
    }
  }
  private def silent(body: => Unit): Unit = {
    val bytes = new ByteArrayOutputStream
    val previous = System.out
    val capture = new PrintStream(bytes)
    System.setOut(capture)
    try body finally { System.setOut(previous); capture.close() }
    check("host lifecycle does not write stdout")(bytes.size() == 0)
  }
  private def fixture(): java.nio.file.Path = {
    val root = Files.createTempDirectory("p12host-fixture-")
    Files.writeString(root.resolve("ROOT"), "session P12 = Pure + theories P12\n")
    Files.writeString(root.resolve("P12.thy"),
      "theory P12 imports Pure begin\ntext \\<open>needle\\<close>\nend\n")
    root
  }
  private def run(root: java.nio.file.Path): Query_Server.Result =
    Query_Server.run(List("-R", root.toString, "summary"), None, None, Map.empty, 0)

  private def core(): Unit = {
    val root = fixture()
    check("service constructors do not advertise") {
      new Query_Server_Commands
      !Server.private_data.database.file.exists()
    }
    val initial = Query_Server.version_info
    check("dedicated identity and protocol remain default") {
      str(initial, "host_kind") == "server" && str(initial, "host_name").isEmpty &&
        num(initial, "protocol") == 1 && num(initial, "host_pid") == ProcessHandle.current().pid()
    }
    check("query_version does not advertise")(!Server.private_data.database.file.exists())
    // Listening but never greeting: stock Server.init would wait two seconds
    // on this row, then delete it. Embedded registration must not even connect.
    val foreign = new java.net.ServerSocket(0, 50, Server.localhost)
    foreign.setSoTimeout(200)
    val foreign_name = "p12host_foreign_unresponsive"
    val data0 = Server.private_data
    using(SQLite.open_database(data0.database, restrict = true)) { db =>
      data0.transaction_lock(db, create = true) {
        db.execute_statement(data0.Base.table.insert(), body = { s =>
          s.string(1) = foreign_name; s.int(2) = foreign.getLocalPort; s.string(3) = "foreign"
        })
      }
    }
    val starting = System.nanoTime()
    silent { Query_Host.start("jedit") }
    val startup_ms = (System.nanoTime() - starting) / 1000000L
    val info = advertised
    try {
      val unprobed = try { foreign.accept().close(); false }
        catch { case _: java.net.SocketTimeoutException => true }
      check("registration does not probe/prune foreign rows and returns promptly") {
        startup_ms < 1500 && unprobed && rows.exists(i =>
          i.name == foreign_name && i.port == foreign.getLocalPort && i.password == "foreign")
      }
      val version = request(info, "query_version")
      check("real authenticated socket reports embedded identity") {
        str(version, "host_kind") == "jedit" && str(version, "host_name") == info.name &&
          info.name.startsWith("isabelle_query_host_jedit_" + ProcessHandle.current().pid() + "_") &&
          num(version, "host_pid") == ProcessHandle.current().pid() && retains(version)
      }
      Query_Host.start("pide")
      check("start is idempotent across kinds")(advertised.name == info.name && rows.size == 2)
      val open = request(info, "query_open", JSON.Object("root" -> root.toString))
      val id = str(open, "index_id")
      val result = request(info, "query_run",
        JSON.Object("argv" -> List("summary"), "index_id" -> id))
      check("socket run reuses warm index")(str(result, "index_id") == id && num(result, "exit") == 0)
      val stdin = request(info, "query_run", JSON.Object(
        "argv" -> List("grep", "needle", "-"), "index_id" -> id))
      check("raw socket stdin consumption is refused at the input hook") {
        num(stdin, "exit") == 2 && str(stdin, "error").contains("stdin input is unavailable")
      }
      val inert_dash = request(info, "query_run", JSON.Object(
        "argv" -> List("grep", "-", "--help"), "index_id" -> id))
      check("inert dash remains valid on embedded endpoint")(num(inert_dash, "exit") == 0)
      val stale = request(info, "query_cache",
        JSON.Object("mode" -> "off", "client_id" -> "definitely-stale"), Server.Reply.ERROR)
      check("stale control refuses without mutation") {
        str(stale, "message").contains("stale") && retains(Query_Server.version_info) &&
          Query_Server.registry_size == 1
      }
      request(info, "query_cache", JSON.Object("mode" -> "bad"), Server.Reply.ERROR)
      request(info, "query_cache", JSON.Object.empty, Server.Reply.ERROR)
      check("bad mode/missing mode preserve cache")(Query_Server.registry_size == 1)
      val cleared = request(info, "query_cache", JSON.Object("mode" -> "clear"))
      check("clear reports count and preserves policy")(num(cleared, "closed") == 1 && retains(cleared))
      check("clear invalidates old handles")(fails {
        Query_Server.run(List("--help"), None, None, Map.empty, 0, index_id = Some(id))
      })
      val reopened = run(root)
      check("clear recreates a fresh retained handle")(reopened.index.exists(_.id != id))
      val off = request(info, "query_cache", JSON.Object("mode" -> "off"))
      check("off atomically clears and disables") {
        num(off, "closed") == 1 && !retains(off) && num(off, "indexes") == 0 &&
          num(off, "cache_source_bytes") == 0 && num(off, "cache_limit_bytes") > 0
      }
      check("off refuses open")(fails { Query_Server.open(root, 0) })
      val transient = run(root)
      check("off allows transient root run") {
        transient.exit == 0 && transient.index.isEmpty && Query_Server.registry_size == 0
      }
      check("clear preserves off policy")(!retains(Query_Server.cache_control("clear")))
      check("on reenables configured budget")(retains(Query_Server.cache_control("on")))
      check("on permits retained open")(Query_Server.open(root, 0).contains("index_id"))

      // Hold engine_lock inside a real run; off and version must finish after release.
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val attempted = new CountDownLatch(1)
      val done = new CountDownLatch(1)
      val failure = new AtomicReference[Throwable](null)
      def worker(body: => Unit): Thread = {
        val t = new Thread(() => try body catch { case exn: Throwable => failure.set(exn) })
        t.setDaemon(true); t.start(); t
      }
      val held = worker {
        Query_Server.run(List("summary"), None, None, Map.empty, 0,
          host_ambient_root = Some(() => {
            entered.countDown()
            assert(release.await(10, TimeUnit.SECONDS))
            root
          }))
      }
      assert(entered.await(10, TimeUnit.SECONDS))
      val control = worker {
        attempted.countDown()
        try { Query_Server.cache_control("off"); () } finally done.countDown()
      }
      assert(attempted.await(10, TimeUnit.SECONDS))
      val blocked = !done.await(200, TimeUnit.MILLISECONDS)
      val discovered = new CountDownLatch(1)
      val reader = worker {
        // Unit-argument query_version is the cheap discovery path; the object
        // form intentionally includes index stats, which may wait for refresh.
        using(info.connection()) { c =>
          c.set_timeout(Time.seconds(3))
          assert(c.read_line_message().exists(_.startsWith("OK ")))
          c.write_line_message("query_version")
          assert(c.read_line_message().exists(_.startsWith("OK ")))
        }
        discovered.countDown()
      }
      val responsive = discovered.await(4, TimeUnit.SECONDS)
      release.countDown()
      held.join(10000); control.join(10000); reader.join(10000)
      check("off waits for run, clears final publication, and version has no lock inversion") {
        blocked && responsive && !held.isAlive && !control.isAlive && !reader.isAlive && failure.get() == null &&
          Query_Server.registry_size == 0 && !retains(Query_Server.version_info)
      }
      Query_Server.cache_control("on")

      val (other, other_server) = Server.init(name = "p12host_unrelated_" + UUID.random_string())
      try {
        silent { Query_Host.stop() }
        check("stop removes own row/listener only") {
          !info.active && !rows.exists(_.name == info.name) && other.active && rows.exists(_.name == other.name)
        }
        Query_Host.stop()
        var normal: Server.Info = null
        val value = Query_Host.with_host("pide") {
          normal = advertised
          check("bracket advertises PIDE kind")(str(request(normal, "query_version"), "host_kind") == "pide")
          42
        }
        check("normal bracket stops and preserves body result")(value == 42 && !normal.active)
        var exceptional: Server.Info = null
        val marker = new IllegalArgumentException("body marker")
        val propagated = try {
          Query_Host.with_host("pide") { exceptional = advertised; throw marker }; false
        } catch { case exn: IllegalArgumentException => exn eq marker }
        check("exceptional bracket stops and preserves body exception")(propagated && !exceptional.active)

        Query_Host.start("jedit")
        val replaced = advertised
        // Simulate another owner's replacement row. stop must compare credentials.
        val data = Server.private_data
        using(SQLite.open_database(data.database)) { db => data.transaction_lock(db) {
          db.execute_statement(data.Base.table.delete(sql = data.Base.name.where_equal(replaced.name)))
          db.execute_statement(data.Base.table.insert(), body = { s =>
            s.string(1) = replaced.name; s.int(2) = other.port; s.string(3) = other.password
          })
        }}
        Query_Host.stop()
        check("stop preserves a replacement registry row and listener") {
          !replaced.active && other.active && rows.exists(i => i.name == replaced.name && i.port == other.port)
        }
        // Narrow endpoint rejects every stock lifecycle/prover command.
        Query_Host.start("pide")
        val last = advertised
        using(last.connection()) { c =>
          c.set_timeout(Time.seconds(3))
          assert(c.read_line_message().exists(_.startsWith("OK ")))
          for (command <- List("shutdown", "session_start {}", "help", "echo", "session_build {}")) {
            c.write_line_message(command)
            assert(c.read_line_message().exists {
              case Server.Reply(Server.Reply.ERROR, JSON.Object(obj)) =>
                str(obj, "message").contains("Bad command")
              case _ => false
            })
          }
          c.write_line_message("query_cache {broken")
          assert(c.read_line_message().exists {
            case Server.Reply(Server.Reply.ERROR, JSON.Object(obj)) =>
              str(obj, "message").contains("Malformed")
            case _ => false
          })
        }
        check("stock commands and malformed input are rejected without stopping host") {
          other.active && last.active && request(last, "query_version").nonEmpty
        }
        check("dedicated stock wrapper shares query dispatcher") {
          retains(request(other, "query_cache", JSON.Object("mode" -> "on"))) &&
            request(other, "query_version").nonEmpty
        }
        val unauthenticated = new java.net.Socket(Server.localhost, last.port)
        unauthenticated.setSoTimeout(3000)
        val authenticated = last.connection()
        authenticated.set_timeout(Time.seconds(3))
        assert(authenticated.read_line_message().nonEmpty)
        Query_Host.stop()
        try {
          check("stop closes authenticated and unauthenticated accepted clients") {
            authenticated.read_line_message().isEmpty && unauthenticated.getInputStream.read() == -1 && other.active
          }
        } finally { authenticated.close(); unauthenticated.close() }
      }
      finally other_server.foreach(_.stop())
    }
    finally { Query_Host.stop(); Query_Server.close_all(); foreign.close() }
  }

  def main(args: Array[String]): Unit = {
    val user = Paths.get(Isabelle_System.getenv("USER_HOME")).toRealPath()
    require(user.startsWith(Paths.get(System.getProperty("java.io.tmpdir")).toRealPath()),
      "probe requires private temporary USER_HOME")
    args.toList match {
      case List("core") => core()
      case List("zero") =>
        val root = fixture()
        check("budget zero overrides default and on") {
          !retains(Query_Server.version_info) && !retains(Query_Server.cache_control("on"))
        }
        check("budget zero refuses open")(fails { Query_Server.open(root, 0) })
        val result = run(root)
        check("budget zero remains transient")(result.exit == 0 && result.index.isEmpty)
      case List("optout") =>
        silent { Query_Host.start("pide"); Query_Host.stop() }
        check("opt-out does not create registry")(!Server.private_data.database.file.exists())
        var ran = false
        Query_Host.with_host("pide") { ran = true }
        check("opt-out still runs bracket body")(ran)
      case List("fail") =>
        Files.createDirectories(Server.private_data.database.java_path)
        var ran = false
        silent { Query_Host.with_host("pide") { ran = true } }
        check("registry failure does not break host body")(ran)
        check("failed start restores dedicated identity")(str(Query_Server.version_info, "host_kind") == "server")
      case List("stamp") =>
        val jar = Paths.get(Isabelle_System.getenv("ISABELLE_QUERY_JAR"))
        val old = Files.getLastModifiedTime(jar)
        val expected = old.toMillis.toString + ":" + Files.size(jar)
        Query_Host.start("pide")
        try {
          Files.setLastModifiedTime(jar, FileTime.fromMillis(old.toMillis + 3000))
          check("host start captures component stamp before first version request") {
            str(Query_Server.version_info, "component_id") == expected
          }
        } finally { Files.setLastModifiedTime(jar, old); Query_Host.stop() }
      case _ => error("expected core, zero, optout, fail, or stamp")
    }
    println("P12HOST OK: " + checks + " checks")
  }
}
