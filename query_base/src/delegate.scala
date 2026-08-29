/*  Title:      query_base/src/delegate.scala

The cold CLI, delegating to the warm server.

`isabelle query` pays about a second of JVM before it reads a theory.  P7 gave
that cost two answers: a `Server.Commands` service on the stock `isabelle
server` (`server.scala`) and a non-JVM thin client that talks to it
(`lib/scripts/query_client.py`).  This is the third — the tool itself, using
the same server when there is one, so a user who types `isabelle query` and has
never heard of a server still gets the warm index.

It is a PORT OF THE CLIENT'S POLICY, not a second policy: the same server name,
the same registry lookup, the same jar-stamp staleness rule, the same three
forwarded variables, the same "never a wrong answer, then never a hang, then
fast" ordering.  Where the two deliberately differ, the reason is that the user
did not ASK for a server here (see `Refusals` below).

Five things are load-bearing.

  * **One dispatch path, still.**  The delegated request goes to `query_run`,
    which hands the argv to `CLI.run_result` — the same function this process
    would have called locally.  Nothing about the answer is computed twice, in
    two places, or in two grammars.  What this file does is choose *which JVM*
    runs it.

  * **Nothing is printed until the whole answer is in hand.**  The reply is one
    message; it is decoded, and only then written out.  So a fallback — at any
    point, for any reason — cannot duplicate or truncate output, because
    nothing has been written yet.

  * **Failure means the cold path, silently.**  No server, a dead registry row,
    a refused connection, a protocol the server does not know, a socket that
    dies mid-request: every one of them runs the query locally instead.  The
    fallback writes nothing to stderr unless `$ISABELLE_QUERY_SERVER_VERBOSE`
    is set, because a note there would change the bytes a caller compares
    against a cold run, and byte identity is the property this whole mode is
    judged on.

  * **stderr is written BEFORE stdout.**  The two streams arrive separately and
    have to be replayed in some order.  A diagnostic must survive a closed
    stdout (`query find . -a | head -3` must still say what went wrong), and
    the one diagnostic the tool emits before it computes anything — the
    Pure-floor namespace note — comes first in a cold run too.  The thin client
    writes stdout first; that difference is recorded rather than hidden, and it
    is why the harnesses capture the two streams into separate files.

  * **A refusal the SERVER made about ITSELF is not the user's answer.**  Over
    the resident-index size cap, the thin client reports and exits 2, because
    its user chose the warm path.  Here the user typed `isabelle query`, which
    has always answered that question cold, so the cap falls back instead of
    refusing.  The cap protects the server's memory; it must not shrink the
    tool.

The bypass list — what never delegates — is `bypass` below, and it is the one
place it is written down (README mirrors it in prose).
*/

package isabelle.query


import isabelle.*

import java.io.{File => JFile, IOException}
import java.net.{InetSocketAddress, Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import scala.collection.mutable


object Query_Delegate {
  /* ------------------------------------------------------------------ */
  /* the knobs                                                          */
  /* ------------------------------------------------------------------ */

  /* The opt-out, as a flag and as a variable.  The flag wins: it is on the
     command line the user is looking at, and a variable exported three shells
     ago is not. */
  val OFF_FLAG = "--no-server"
  val OFF_ENV = "ISABELLE_QUERY_NO_SERVER"

  /* Diagnostics on stderr.  An env var rather than a flag, for the same reason
     the thin client's `--client-*` options carry a prefix: it configures THIS
     PROCESS and has nothing to do with the query. */
  val VERBOSE_ENV = "ISABELLE_QUERY_SERVER_VERBOSE"

  /* Shared with the thin client, deliberately: both front ends must reach the
     SAME server, or a developer pointing one at a scratch server would silently
     be served by the other from the real registry. */
  val SERVER_ENV = "ISABELLE_QUERY_CLIENT_SERVER"
  val TIMEOUT_ENV = "ISABELLE_QUERY_CLIENT_TIMEOUT"

  /* The process's own environment, read the way `CLI.process_env` reads it —
     these variables configure this invocation, and none of them is forwarded. */
  private def env(k: String): Option[String] = Option(System.getenv(k)).filter(_.nonEmpty)

  def server_name: String = env(SERVER_ENV).getOrElse(Query_Server.default_server_name)

  def verbose: Boolean = env(VERBOSE_ENV).contains("1")

  /* Short where a wrong answer costs a spurious cold run, long where it costs
     repeated work.  A connect that does not answer at once is a dead registry
     row, not a busy server: the accept loop is one thread doing nothing else.
     The request timeout is necessarily generous — a whole-corpus query is
     legitimately minutes — and is the thin client's, under the same name. */
  private val CONNECT_TIMEOUT_MS = 2000
  private val GREETING_TIMEOUT_MS = 10000
  private val START_TIMEOUT_MS = 60000
  private val DEFAULT_TIMEOUT_MS = 600000

  def request_timeout_ms: Int =
    env(TIMEOUT_ENV).flatMap(s =>
      try { val v = (s.toDouble * 1000).toInt; if (v > 0) Some(v) else None }
      catch { case _: NumberFormatException => None }
    ).getOrElse(DEFAULT_TIMEOUT_MS)

  private lazy val diagnostics: Out = Out.stderr

  private def note(msg: String): Unit =
    if (verbose) diagnostics.println("query-delegate: " + msg)

  /* Time one stage and say what it cost, but only when asked.  The split is
     not decoration: the warm round trip itself is ~1 ms, and everything else
     on this path — opening the registry's SQLite database above all — is
     overhead that a single total would hide (dev/P7B-STATUS.md records the
     measurement this exists for). */
  private def timed[A](label: String)(body: => A): A = {
    if (!verbose) body
    else {
      val t0 = System.currentTimeMillis()
      val res = body
      note(label + " " + (System.currentTimeMillis() - t0) + " ms")
      res
    }
  }

  /* Anything that means: do not trust the warm path for this invocation.
     Mirrors the client's `Fallback`. */
  private final class Fallback(val reason: String)
    extends RuntimeException(null, null, false, false)


  /* ------------------------------------------------------------------ */
  /* the opt-out flag                                                   */
  /* ------------------------------------------------------------------ */

  /* Recognised anywhere before a bare `--`, and removed before the argv is
     read by anyone: the CLI's own grammar does not know this option, and a
     served run must not carry it either.  Only the exact spelling — an
     abbreviation gets the grammar's usual "unrecognized argument", which is a
     better answer than silently delegating against the user's stated wish. */
  def strip_flag(args: List[String]): (List[String], Boolean) = {
    val out = new mutable.ListBuffer[String]
    var seen = false
    var only_pos = false
    for (tok <- args) {
      if (only_pos) out += tok
      else if (tok == "--") { only_pos = true; out += tok }
      else if (tok == OFF_FLAG) seen = true
      else out += tok
    }
    (out.toList, seen)
  }


  /* ------------------------------------------------------------------ */
  /* the bypass list                                                    */
  /* ------------------------------------------------------------------ */

  /* THE ONE PLACE the list lives.  README's "server" section says the same
     thing in prose; a new verb of any of these shapes belongs here. */

  /* These write straight to the process's own stdout inside the JVM (they are
     corpus dumps, sized for a pipe, not for a socket).  `COLD_ONLY_COMMANDS`
     in `query_client.py` is the same list on the other front end, and
     `Query_Tool.main_tool` is where they are dispatched. */
  val COLD_ONLY_COMMANDS: Set[String] = Set("dump-entries", "dump-imports", "dump-theories")

  /* `-` is `CLI.STDIN_SENTINEL`, the only route by which any command reads
     stdin (`CLI.load_sections` and the `lines` verb, both gated on it).  The
     server cannot see this process's stdin, so such a run stays here. */
  val STDIN_TOKEN: String = CLI.STDIN_SENTINEL

  /* The exact spellings only; an abbreviation delegates and gets the same
     bytes back, because a stamp-matched server is running this very jar. */
  private val HELP_TOKENS = Set("-h", "--help", "-V", "--version")

  /* The positional tokens, in order — the command name, then a group's view
     name.  Options are skipped by the same rule the CLI's own top-level loop
     uses (`-R`/`--root` take a following argument; everything else beginning
     with `-` is a flag or carries its value inline). */
  private def positionals(args: List[String]): List[String] = {
    val out = new mutable.ListBuffer[String]
    var rest = args
    var only_pos = false
    while (rest.nonEmpty) {
      val tok = rest.head
      rest = rest.tail
      if (only_pos) out += tok
      else if (tok == "--") only_pos = true
      else if (tok == "-R" || tok == "--root") { if (rest.nonEmpty) rest = rest.tail }
      else if (tok.length > 1 && tok.startsWith("-")) ()
      else out += tok
    }
    out.toList
  }

  /* `Some(reason)` — run locally.  Each case, and why it is not a matter of
     taste:

       stdin        the server has no access to this process's stdin
       dumps        they write past any capture, and are corpus-sized
       shape census a 256 MB reply through a synchronous single-message
                    protocol is measurably SLOWER warm than cold
                    (dev/BENCH.md); it also gets no benefit from a warm index,
                    because it iterates sessions itself rather than going
                    through `load_index`
       help/version pure text, no project, no reason to need a server up
       nothing      a bare `isabelle query` prints its usage
       a live path  it may be a path or a pattern, and only the grammar knows
                    (see `ambiguous`) */
  def bypass(args: List[String]): Option[String] = {
    if (args.isEmpty) Some("no arguments")
    else if (args.contains(STDIN_TOKEN)) Some("reads stdin")
    else {
      val pos = positionals(args)
      if (pos.headOption.exists(COLD_ONLY_COMMANDS)) Some("development dump")
      else if (pos.take(2) == List("shape", "census")) Some("shape census")
      else if (args.takeWhile(_ != "--").exists(HELP_TOKENS)) Some("help or version")
      else ambiguous(args).map(t => "relative to this directory: " + quote(t))
    }
  }


  /* ------------------------------------------------------------------ */
  /* finding, and starting, the server                                  */
  /* ------------------------------------------------------------------ */

  /* The stock registry, read through the stock accessor.  `Server.private_data.list`
     rather than `.find`, which is `.list` plus an `active` probe: that probe is
     a whole connect-and-round-trip, and we are about to make exactly that
     connection anyway.  Doing it twice costs a measurable slice of a budget
     whose whole point is to be small, and buys nothing — a row that has gone
     stale between the read and the connect has to be handled regardless (it
     is, in `open` below), so the connect IS the liveness probe.

     No write lock is taken: a reader must never be able to block the server's
     own `init()`.  A missing database is checked for rather than opened, since
     opening would create one. */
  private def registry_lookup(name: String): Option[Server.Info] = {
    val db = Server.private_data.database
    if (!db.is_file) None
    else
      try using(SQLite.open_database(db))(Server.private_data.list(_).find(_.name == name))
      catch { case _: Throwable => None }
  }

  private def isabelle_tool: String = {
    val exe = Isabelle_System.getenv("ISABELLE_TOOL")
    if (exe != null && exe.nonEmpty) exe else env("ISABELLE_TOOL").getOrElse("isabelle")
  }

  /* A server must outlive the invocation that started it, so it is detached
     from this process's session where the platform offers a way: otherwise a
     Ctrl-C at the terminal, which signals the whole foreground process group,
     would take the new server with it.  Absent `setsid` the spawn still works;
     it is just less durable, which is a slower next query and not a wrong
     answer. */
  private def setsid: Option[String] =
    Option(System.getenv("PATH")).toList.flatMap(_.split(JFile.pathSeparatorChar)).
      map(d => new JFile(d, "setsid")).find(f => f.isFile && f.canExecute).map(_.getPath)

  /* `isabelle server -n NAME` prints `server "NAME" = HOST:PORT (password
     "...")` and then blocks, so the address arrives on its stdout and there is
     no need to race the registry for the row it just wrote.  `Server.Info.parse`
     is the distribution's own reader for that line.

     CONCURRENT STARTS need no lock of ours: the spawned process runs
     `Server.init`, which does its find-or-insert under the registry's
     transaction lock.  A loser prints the WINNER's line and exits at once, so
     both callers end up with the same, single server. */
  private def start_server(name: String): Server.Info = {
    val cmd = new java.util.ArrayList[String]
    for (s <- setsid) cmd.add(s)
    cmd.add(isabelle_tool)
    cmd.add("server")
    cmd.add("-n")
    cmd.add(name)

    val builder = new ProcessBuilder(cmd)
    /* A shared server must not inherit one caller's working directory; every
       request carries its own `cwd` instead. */
    val fs_root = new JFile(JFile.separator)
    if (fs_root.isDirectory) builder.directory(fs_root)
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    /* The environment is INHERITED, `USER_HOME` above all, which is what keeps
       a scratch-home development run talking to a scratch-home server.  It is
       safe to inherit precisely because the server never reads its own
       environment for a request (`server.scala`, and dev/P6C-STATUS.md §4). */

    val proc =
      try builder.start()
      catch { case exn: IOException => throw new Fallback("cannot start a server: " + exn) }
    try proc.getOutputStream.close() catch { case _: IOException => () }

    val reader =
      new java.io.BufferedReader(
        new java.io.InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8))
    val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
    var line: String = null
    try {
      while (line == null && System.currentTimeMillis() < deadline) {
        line = reader.readLine()
        if (line == null) throw new Fallback("the server exited during start-up")
      }
    }
    catch {
      case exn: IOException => throw new Fallback("cannot read the server greeting: " + exn)
    }
    finally try reader.close() catch { case _: IOException => () }

    if (line == null) throw new Fallback("no server greeting within " + START_TIMEOUT_MS + " ms")
    Server.Info.parse(line).getOrElse(
      throw new Fallback("unreadable server greeting: " + quote(line.take(120))))
  }

  /* Stopped through the protocol, never a signal: `Server.exit` sends
     `shutdown` and waits until the socket stops answering, and the row it
     leaves behind is deleted by the very next `init()` — which is the spawn on
     the line after this one.  A `kill -9` would leave a row pointing at a dead
     port for the next caller to trip over. */
  private def stop_server(name: String): Unit =
    try { if (Server.private_data.database.is_file) Server.exit(name) }
    catch { case _: Throwable => () }


  /* ------------------------------------------------------------------ */
  /* one authenticated round trip                                       */
  /* ------------------------------------------------------------------ */

  /* `Server.Info.connection()` would do, except that it neither bounds the
     connect nor disables Nagle.  Both matter: an unbounded connect to a
     firewalled port is the hang this mode must not have, and the algorithm has
     nothing to coalesce in a request/response protocol — it only ever costs
     (measured at ~40 ms a round trip, dev/P7-STATUS.md). */
  private def connect(info: Server.Info): Server.Connection = {
    val socket = new Socket
    socket.connect(new InetSocketAddress(Server.localhost, info.port), CONNECT_TIMEOUT_MS)
    socket.setTcpNoDelay(true)
    val connection = Server.Connection(socket)
    connection.set_timeout(Time.ms(GREETING_TIMEOUT_MS))
    connection.write_line_message(info.password)
    connection.read_line_message() match {
      case Some(msg) if msg.startsWith("OK") => connection
      case other =>
        connection.close()
        throw new Fallback("the server did not greet: " + quote(other.getOrElse("").take(80)))
    }
  }

  private def command(connection: Server.Connection, name: String, arg: JSON.T
  ): (Server.Reply, JSON.T) = {
    connection.set_timeout(Time.ms(request_timeout_ms))
    connection.write_line_message(name + " " + JSON.Format(arg))
    connection.read_line_message() match {
      case None => throw new Fallback("the connection closed before an answer arrived")
      case Some(msg) =>
        msg match {
          case Server.Reply(reply, body: JSON.T) => (reply, body)
          case _ => throw new Fallback("unreadable reply: " + quote(msg.take(120)))
        }
    }
  }


  /* ------------------------------------------------------------------ */
  /* the request                                                        */
  /* ------------------------------------------------------------------ */

  /* A served run happens in the SERVER's working directory, so a relative path
     in the argument list would resolve somewhere else.  Exactly ONE argument is
     rewritten here: `-R`/`--root`'s, in all four spellings.  It is rewritten
     whether or not it exists, because an unreadable root is a diagnostic the
     tool must give about the path the user meant, and it is safe to rewrite
     because that option's argument is a directory in every invocation there
     is — no grammar has to be consulted to know it.  `~` is expanded here
     because inside a server `user.home` is the home of whoever started it.

     Every OTHER relative path is handled by refusing to delegate at all; see
     `ambiguous` below for why guessing is not available. */
  def absolutize(args: List[String]): List[String] = {
    val out = new mutable.ListBuffer[String]
    var rest = args
    while (rest.nonEmpty) {
      val tok = rest.head
      rest = rest.tail
      if (tok == "-R" || tok == "--root") {
        out += tok
        if (rest.nonEmpty) { out += resolve(rest.head); rest = rest.tail }
      }
      else if (tok.startsWith("--root=")) out += "--root=" + resolve(tok.substring(7))
      else if (tok.startsWith("-R") && tok.length > 2) out += "-R" + resolve(tok.substring(2))
      else out += tok
    }
    out.toList
  }

  private def resolve(p: String): String =
    CLI.expanduser(p).toAbsolutePath.normalize.toString

  private def exists(token: String): Boolean =
    try java.nio.file.Files.exists(CLI.expanduser(token))
    catch { case _: Throwable => false }

  /* THE ARGUMENT THIS LAYER MUST NOT DECIDE.  Whether a positional is a path
     or a pattern is a fact about the COMMAND: `find .` searches for the regex
     `.`, `grep pat .` searches the directory `.`, and the two tokens are
     spelled identically.  A transport that rewrote every token naming an
     existing file would turn the first into a search for the caller's absolute
     working directory — the answer was `No entries matching '/home/...'`, and
     it looked like a correct empty result.  A transport that rewrote none of
     them would send the second to the server's own `/`.

     So neither guess is available, and the third option is the right one: an
     invocation carrying a token that could be either runs HERE, where relative
     means what the user meant.  It costs the warm path for `grep pat .` and
     buys never being wrong about it.

     Two kinds of token are NOT ambiguous, and excluding them is what keeps the
     rule from swallowing the warm path whole:

       * one that names nothing — the server resolves it exactly as this
         process would, and gives the same "not a path or known theory" when it
         resolves to nothing;
       * one that is ABSOLUTE — it means the same thing in any working
         directory, so it needs no rewriting and none is done: whether the
         grammar reads it as a path or as a pattern, the server reads it the
         same way this process would have.

     A `~`-prefixed token IS ambiguous even though it looks absolute: expanding
     it could corrupt a pattern, and not expanding it would send it to a server
     whose `user.home` is somebody else's.

     `-R`'s argument is skipped: it is a directory by construction and
     `absolutize` has already dealt with it. */
  def ambiguous(args: List[String]): Option[String] = {
    var rest = args
    var found: Option[String] = None
    while (rest.nonEmpty && found.isEmpty) {
      val tok = rest.head
      rest = rest.tail
      if (tok == "-R" || tok == "--root") { if (rest.nonEmpty) rest = rest.tail }
      else if (tok.startsWith("-") && tok.length > 1) ()
      else if (tok.startsWith("~")) { if (exists(tok)) found = Some(tok) }
      else if (!tok.startsWith("/") && exists(tok)) found = Some(tok)
    }
    found
  }

  /* `CLI.request_env` is the contract — every variable the ENGINE reads, and
     no others.  Read from THIS process, bound for THIS request inside the
     server, so a variable means the same thing delegated as it does cold. */
  private def request(args: List[String]): JSON.Object.T = {
    val forwarded =
      (for (k <- CLI.request_env; v <- CLI.process_env(k)) yield k -> (v: JSON.T)).toMap
    val env_root =
      CLI.request_env.filter(_ != CLI.NAMESPACE_ENV).iterator.
        flatMap(CLI.process_env(_)).nextOption().getOrElse("")
    JSON.Object(
      "argv" -> absolutize(args),
      "cwd" -> Paths.get("").toAbsolutePath.toString,
      "env_root" -> env_root,
      "env" -> forwarded,
      /* Our own jar's stamp.  The server compares it against the one it
         started with and refuses on a mismatch, so the staleness check costs
         no extra round trip — `Query_Server.component_id` reads the jar this
         very process was loaded from. */
      "client_id" -> Query_Server.component_id)
  }


  /* ------------------------------------------------------------------ */
  /* the run                                                            */
  /* ------------------------------------------------------------------ */

  /* `Some(exit)` — answered by the server, output already written.  `None` —
     run it here.  Every path out of the warm attempt that is not a complete OK
     reply is a `None`, and nothing has been written on any of them. */
  def delegate(args: List[String], no_server_flag: Boolean): Option[Int] = {
    if (no_server_flag) { note("--no-server"); None }
    else if (env(OFF_ENV).contains("1")) { note("$" + OFF_ENV + "=1"); None }
    else bypass(args) match {
      case Some(why) => note("local: " + why); None
      case None =>
        val t0 = System.currentTimeMillis()
        try {
          val rc = warm(args)
          note("delegated, " + (System.currentTimeMillis() - t0) + " ms")
          Some(rc)
        }
        catch {
          case exn: Fallback => note("falling back: " + exn.reason); None
          case _: SocketTimeoutException =>
            note("falling back: no answer within " + request_timeout_ms + " ms"); None
          case exn: IOException => note("falling back: " + exn); None
          case exn: java.sql.SQLException => note("falling back: registry: " + exn); None
        }
    }
  }

  private def warm(args: List[String]): Int = {
    val name = server_name
    var restarted = false

    def open(): Server.Connection =
      timed("registry")(registry_lookup(name)) match {
        case Some(info) =>
          try timed("connect")(connect(info))
          catch {
            /* A row can go stale between the probe and the connect.  Starting a
               server is both the retry and the cleanup: `init` prunes rows
               whose server no longer answers. */
            case _: IOException =>
              note("stale registry row; starting a server")
              connect(start_server(name))
          }
        case None =>
          note("no server; starting one")
          connect(start_server(name))
      }

    var connection = open()
    try {
      var (reply, body) = timed("query_run")(command(connection, "query_run", request(args)))

      /* The component was rebuilt under a running server, so its loaded
         classes are not this jar's.  Stop it through the protocol and start
         one from the jar we are actually running.  Exactly once: a stale
         server is a fact about the component, so a second retry would fail the
         same way. */
      if (reply == Server.Reply.ERROR && message_of(body).contains("stale query server")) {
        note("component rebuilt under the server; restarting")
        connection.close()
        stop_server(name)
        restarted = true
        connection = connect(start_server(name))
        val r = command(connection, "query_run", request(args))
        reply = r._1
        body = r._2
      }

      if (reply != Server.Reply.OK) {
        val msg = message_of(body)
        /* The size cap is the SERVER's bound on ITS OWN memory.  The thin
           client reports it, because its user asked for the warm path; here
           the user typed `isabelle query`, which has always answered this
           question cold, so the cap must not shrink the tool. */
        if (msg.contains("too large for a resident index")) throw new Fallback("over the cap")
        throw new Fallback("server error: " + msg.take(160))
      }

      val out = JSON.string(body, "output").getOrElse("")
      val err = JSON.string(body, "error").getOrElse("")
      val rc = JSON.int(body, "exit").getOrElse(0)
      if (restarted) note("restarted")
      emit(out, err, rc)
    }
    finally connection.close()
  }

  private def message_of(body: JSON.T): String =
    JSON.string(body, "message").getOrElse(body.toString)

  /* The answer, replayed onto this process's own file descriptors.  stderr
     first (see the header), and a closed stdout is 141 rather than an
     exception, because that is what the cold tool does and the contract says
     the two must be indistinguishable. */
  private def emit(out: String, err: String, rc: Int): Int = {
    if (err.nonEmpty) {
      try { val e = Out.stderr; e.print(err); e.flush() }
      catch { case _: Broken_Pipe => () }
    }
    try {
      val o = Out.stdout
      o.print(out)
      o.flush()
      note("wrote " + out.length + " chars, exit " + rc)
      rc
    }
    catch { case _: Broken_Pipe => note("stdout closed"); CLI.EXIT_SIGPIPE }
  }
}
