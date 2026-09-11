/*  Title:      query_base/src/query_host.scala

Optional query-only endpoint inside an existing JVM. Reuses stock authenticated
loopback framing and private servers.db, without stock session/lifecycle commands
or probing/pruning foreign registry rows. Service construction stays inert.
*/

package isabelle.query

import isabelle.*
import scala.util.control.NonFatal

object Query_Host {
  private val lifecycle_lock = new Object
  private final case class Owned(info: Server.Info, server: Endpoint)
  private var owned: Option[Owned] = None

  private def warning(exn: Throwable): Unit =
    System.err.println("Isabelle query host: " + Exn.message(exn))

  /* Handler supplies the stock socket/password/Connection framing. Track
     connections before password input too, so stop also closes clients that
     never authenticate. No session registry exists in this endpoint. */
  private final class Endpoint(allow_shutdown: Boolean = false) extends Server.Handler(0) {
    var before_shutdown: () => Unit = () => ()
    private val connections_lock = new Object
    private var connections = Set.empty[Server.Connection]
    @volatile private var stopped = false

    private lazy val accept_thread: Thread = Isabelle_Thread.fork(name = "query_host") {
      while (!stopped) {
        try {
          val connection = Server.Connection(socket.accept())
          val accepted = connections_lock.synchronized {
            if (stopped) false
            else { connections += connection; true }
          }
          if (!accepted) connection.close()
          else {
            try {
              Isabelle_Thread.fork(name = "query_client") {
                try {
                  if (connection.read_password(password)) handle(connection)
                }
                catch { case NonFatal(_) => () } // disconnect, including stop
                finally {
                  connections_lock.synchronized { connections -= connection }
                  connection.close()
                }
              }
            }
            catch {
              case NonFatal(exn) =>
                connections_lock.synchronized { connections -= connection }
                connection.close()
                throw exn
            }
          }
        }
        catch {
          case NonFatal(exn) =>
            if (!stopped) warning(exn)
            stopped = true
        }
      }
    }

    override def start(): Unit = { accept_thread; () }
    override def join(): Unit = accept_thread.join()
    override def stop(): Unit = {
      stopped = true
      socket.close()
      val pending = connections_lock.synchronized {
        val all = connections
        connections = Set.empty
        all
      }
      pending.foreach(_.close())
      join()
    }

    override def handle(connection: Server.Connection): Unit = {
      connection.reply_ok(JSON.Object(
        "isabelle_id" -> Isabelle_System.isabelle_id(),
        "isabelle_name" -> Isabelle_System.isabelle_name()))
      var finished = false
      while (!stopped && !finished) {
        connection.read_line_message() match {
          case None => finished = true
          case Some(message) =>
            try {
              val (name, argument) = Server.Argument.split(message)
              val arg = Server.Argument.unapply(argument)
                .getOrElse(error("Malformed argument for command " + quote(name)))
              if (name == "shutdown" && allow_shutdown) {
                before_shutdown()
                connection.reply_ok(JSON.Object())
                stop()
              }
              else connection.reply_ok(
                Query_Server_Protocol.dispatch(name, arg, allow_stdin = false))
            }
            catch {
              case NonFatal(exn) =>
                val known = Server.json_error(exn)
                connection.reply_error(
                  if (known.nonEmpty) known else Server.Reply.error_message(Exn.message(exn)))
            }
        }
      }
    }
  }

  private def register_row(info: Server.Info): Unit = {
    val data = Server.private_data
    using(SQLite.open_database(data.database, restrict = true)) { db =>
      data.transaction_lock(db, create = true) {
        // Unique primary key: never replace a collision or inspect liveness.
        db.execute_statement(data.Base.table.insert(), body = { stmt =>
          stmt.string(1) = info.name
          stmt.int(2) = info.port
          stmt.string(3) = info.password
        })
      }
    }
  }

  /* Only remove the exact row published by this listener under a transaction.
     No Server.exit, active probe, or foreign-row cleanup belongs here. */
  private def remove_row(info: Server.Info): Unit = {
    val data = Server.private_data
    using(SQLite.open_database(data.database, restrict = true)) { db =>
      data.transaction_lock(db) {
        if (data.list(db).exists(i =>
          i.name == info.name && i.port == info.port && i.password == info.password)) {
          db.execute_statement(data.Base.table.delete(sql = data.Base.name.where_equal(info.name)))
        }
      }
    }
  }

  def start(kind: String): Unit = lifecycle_lock.synchronized {
    if (owned.isEmpty) {
      var pending: Option[Owned] = None
      try {
        if (Isabelle_System.getenv("ISABELLE_QUERY_HOST") != "0") {
          if (kind != "jedit" && kind != "pide") error("bad query host kind: " + kind)
          // Freeze identity before either listener startup or registry publication.
          val component_id = Query_Server.component_id
          val name = "isabelle_query_host_" + kind + "_" +
            ProcessHandle.current().pid() + "_" + UUID.random_string().replace("-", "")
          Query_Server.host_identity = Query_Server.Host_Identity(kind, name)
          val local = new Endpoint
          val info = Server.Info(name, local.port, local.password)
          pending = Some(Owned(info, local))
          local.start()
          register_row(info)
          owned = pending
        }
      }
      catch {
        case NonFatal(exn) =>
          pending.foreach { local =>
            try local.server.stop() catch { case NonFatal(e) => warning(e) }
            try remove_row(local.info) catch { case NonFatal(_) => () }
          }
          Query_Server.host_identity = Query_Server.Host_Identity("server", "")
          warning(exn)
      }
    }
  }

  def stop(): Unit = lifecycle_lock.synchronized {
    for (local <- owned) {
      try { local.server.stop() }
      catch { case NonFatal(exn) => warning(exn) }
      finally {
        try { remove_row(local.info) }
        catch { case NonFatal(exn) => warning(exn) }
        owned = None
        Query_Server.host_identity = Query_Server.Host_Identity("server", "")
      }
    }
  }

  def with_host[A](kind: String)(body: => A): A = {
    start(kind)
    try body finally stop()
  }

  def with_server[A](name: String, shutdown_started: () => Unit = () => ())
    (body: (Server.Info, Server.Handler) => A): A = {
    val component_id = Query_Server.component_id
    Query_Server.host_identity = Query_Server.Host_Identity("server", name)
    val local = new Endpoint(allow_shutdown = true)
    val info = Server.Info(name, local.port, local.password)
    local.before_shutdown = () => { shutdown_started(); remove_row(info) }
    try {
      local.start()
      register_row(info)
      body(info, local)
    }
    finally {
      shutdown_started()
      try local.stop()
      finally remove_row(info)
    }
  }
}
