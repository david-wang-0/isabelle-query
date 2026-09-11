package isabelle.query

import isabelle._

object Query_Server_Main {
  def main(args: Array[String]): Unit = Command_Line.tool {
    val name = args.toList match {
      case List("-n", name) => name
      case _ => error("Usage: isabelle query_server -n NAME")
    }
    val closing = new java.util.concurrent.atomic.AtomicBoolean(false)
    val endpoint = new java.util.concurrent.atomic.AtomicReference[Server.Handler]()
    val timer = new java.util.Timer("query_shutdown", true)
    def begin_shutdown(): Unit = {
      if (closing.compareAndSet(false, true)) {
        // Bound cleanup even if a registry lock or abandoned handler is stuck.
        timer.schedule(new java.util.TimerTask {
          override def run(): Unit = Runtime.getRuntime.halt(0)
        }, 3000L)
      }
    }
    Isabelle_Thread.fork(name = "query_owner", daemon = true) {
      try { while (System.in.read() != -1) {} }
      finally {
        begin_shutdown()
        Option(endpoint.get()).foreach(_.stop())
      }
    }
    Query_Host.with_server(name, () => begin_shutdown()) { (info, owned) =>
      endpoint.set(owned)
      if (closing.get()) owned.stop()
      else Output.writeln(info.toString, stdout = true)
      // Command_Line.tool calls sys.exit after cleanup, including blocked handlers.
      owned.join()
    }
  }
}
