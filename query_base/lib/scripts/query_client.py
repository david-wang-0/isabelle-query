#!/usr/bin/env python3
"""Thin client for the warm `isabelle query` server.

A cold `isabelle query` costs about 870 ms before it prints anything, and the
warm mode exists to remove two quite different parts of that.  Naming them
correctly matters, because the tool's own documentation had this wrong through
P7 and the wrong version argues for the wrong design:

  ~405 ms  `scala_build` -- a whole SECOND JVM, whose only job is to check
           whether this component needs recompiling
  ~180 ms  the `bin/isabelle` settings shell, sourced again by `isabelle java`
   ~30 ms  the JVM itself.  That is all it is.
  ~250 ms  Isabelle/Scala class loading over a 53-jar class path
     THEN  the actual parse -- 421 ms for a 28-theory AFP entry, 2755 ms for
           `src/HOL`, ~19 s for the whole AFP

Skipping the first four is what a resident process buys, and it is the smaller
half.  The larger half is the last line: the server holds the PARSED corpus, so
a repeat question re-stats the files (12 ms over `src/HOL`'s 1468) instead of
re-reading them.  No amount of compiling makes a cold process do that, which is
why the answer here is a warm index rather than a faster start.

The engine's half of it is a `Server.Commands` service folded into the stock
`isabelle server` (see `query_base/src/server.scala`); this is the other half —
a client that speaks the server's documented line protocol directly, so nothing
on the fast path is a JVM.

Deliberately stdlib-only and deliberately small.  It is a TRANSPORT, not a
second front end: it does not know the tool's options, does not validate them,
and does not format results.  `argv` goes over the wire verbatim and the
server hands it to the same `CLI.run_result` a typed command reaches, so a
served answer and a typed one differ only in who wrote the bytes.

The safety rule, in order of priority: never a wrong answer, then never a
hang, then fast.  Every failure mode — no server, a refused connection, a
protocol the client does not know, a component rebuilt under a running server
— declines, and the cold path answers instead.  A slower right answer is
always available; a wrong one must not be.

DECLINING IS AN EXIT STATUS, NOT AN EXEC.  This script exits EXIT_RUN_COLD (97)
having written nothing, and `lib/Tools/query` — which called it, and which is
the only router — runs the JVM.  Before P8 it re-exec'd `isabelle query`
itself, which since P7d meant re-entering this very script through the shim: a
hop that needed an environment mark to keep it from looping, and that landed in
a JVM carrying a second copy of the routing policy (`delegate.scala`), which
then re-tried the registry this client had just failed on.  One direction, one
policy, no mark.

Usage:
    query_client.py [--client-OPTION ...] ARG ...

Client options are recognised only BEFORE the first tool argument, and all
carry the `--client-` prefix so they cannot collide with the tool's own:

    --client-cold          skip the server entirely (the cold path)
    --client-cache MODE    set retention on/off, or clear the selected host
    --client-status        report on the server and its open indexes, then exit
    --client-stop          shut the server down, then exit
    --client-restart       restart the server before running
    --client-limit N       index size cap for this request (0 disables)
    --client-timeout S     seconds to wait for the answer (default 600)
    --client-verbose       report timings and the path taken, on stderr

Since P7d this script needs no path to reach: the component's
`lib/Tools/query` shim makes it what a plain `isabelle query` runs, routing to
the JVM front end only where a JVM is needed (`--no-server`, help/version,
$ISABELLE_QUERY_NO_CLIENT=1, or no python3).  The shim also splits the argv,
keeping the `--client-*` options for this script and the rest for the cold
path, so a decline can be run without them.

Environment:
    ISABELLE_QUERY_CLIENT_SERVER    server name (default: isabelle_query)
    ISABELLE_QUERY_CLIENT_COLD      set to 1 to force the cold path
    ISABELLE_QUERY_CLIENT_TIMEOUT   default for --client-timeout
    ISABELLE_QUERY_CLIENT_CACHE     where the resolved settings are cached
    ISABELLE_QUERY_NO_CLIENT        read by the SHIM, not by this script: it
                                    means "do not run the client at all", so
                                    nothing here ever observes it

The variables above configure THIS PROCESS and are never sent.  The variables
the tool itself reads are a different set (`FORWARDED_ENV` below) and ARE sent,
per request: a resident server must not read its own environment, because that
environment is whatever the client which happened to start it exported, and it
would silently outlive them.  A variable set here therefore means the same
thing warm as it does cold — which is the only property that lets the client
be a drop-in for `isabelle query`.
"""

# The import list is part of the budget.  A bare `python3 -c pass` costs about
# 15 ms on the reference machine and the whole point is to stay far under the
# Python tool's ~76 ms cold floor, so anything not needed on the fast path is
# imported where it is used: `subprocess` (+8 ms) and `shutil` (+9 ms) are
# reached only when a server has to be started or a setting resolved, and
# `pathlib` (+10 ms) is not used at all — `os.path` says the same things.
# `sqlite3` (+5 ms) stays, deliberately: the alternative is caching the
# server's password in a file of our own, which puts a secret somewhere the
# Isabelle server's own restricted-permission registry did not put it.
import json
import os
import socket
import sqlite3
import sys
import time

PROTOCOL = 1
# ONE warm server, shared with the auto-delegating CLI: this must equal
# `Query_Server.default_server_name` in `query_base/src/server.scala`, and
# $ISABELLE_QUERY_CLIENT_SERVER overrides it for BOTH front ends.  Two names
# would mean two resident JVMs holding two copies of the same index.
DEFAULT_SERVER = "isabelle_query"
DEFAULT_TIMEOUT = 600.0
HOST_PREFIX = "isabelle_query_host_"
FALLBACK_PREFIX = "isabelle_query_fallback_"
DISCOVERY_TIMEOUT = 2.0
PROBE_TIMEOUT = 0.25

# A connect that does not answer at once is a dead registry row, not a busy
# server: the accept loop is one thread doing nothing else.
CONNECT_TIMEOUT = 2.0
GREETING_TIMEOUT = 10.0
# JVM boot plus the component's class loading.  Generous because the cost of
# being wrong here is a spurious fall back to the cold path, not an error.
START_TIMEOUT = 60.0

# The status this script exits with to say: I have written NOTHING, and this
# invocation wants the cold path.  `lib/Tools/query` reads it and runs the JVM
# itself -- which is why nothing here ever re-enters `isabelle query`.  Chosen
# outside the CLI's exit contract (0 ran, 1 unresolved subject, 2 usage, 141
# closed stdout), outside sysexits.h (64-78), and below the shell's signal
# range (129+), so a real answer can never be mistaken for it.
EXIT_RUN_COLD = 97

# These write straight to the process's own stdout inside the JVM (they are
# corpus dumps, sized for a pipe, not for a socket), and `-` reads the
# client's stdin, which the server has no access to.  Both are cold-only, as is
# any invocation carrying a token that names something in the current directory
# (see `ambiguous`).  SINCE P8 THIS IS THE ONLY HOME OF THAT LIST: the Scala
# side used to carry a second copy (`Query_Delegate.bypass`), and a verb of any
# of these shapes belongs here and nowhere else.  README's "warm server"
# section mirrors it in prose.
COLD_ONLY_COMMANDS = {"dump-entries", "dump-imports", "dump-theories"}

# Every environment variable the ENGINE reads, mirroring `CLI.request_env` on
# the other side.  Forwarded per request and bound for that request only.
#   ISABELLE_LAYOUT_ROOT / ISABELLE_QUERY_ROOT  which project, with no -R
#   ISABELLE_QUERY_NAMESPACE                    pin the committed method table
# Import-visibility filtering used to be a fourth entry here; it is `--reach
# {closure,name}` now, and a flag needs no forwarding at all -- argv already
# goes over the socket verbatim.
# Deliberately NOT forwarded: ISABELLE_QUERY_JAR and
# ISABELLE_QUERY_SERVER_LIMIT, which a server reads about ITSELF (its own jar,
# its own memory bound) and which a client must not be able to redefine.
FORWARDED_ENV = (
    "ISABELLE_LAYOUT_ROOT",
    "ISABELLE_QUERY_ROOT",
    "ISABELLE_QUERY_NAMESPACE",
)


class Fallback(Exception):
    """Anything that means: do not trust the warm path for this invocation."""


# --------------------------------------------------------------------------
# locating things
# --------------------------------------------------------------------------


def component_jar():
    """This script lives in the component, so the jar is a relative step away
    — no Isabelle settings needed to find the thing whose identity we check."""
    lib = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
    return os.path.join(lib, "classes", "isabelle_query.jar")


def jar_stamp():
    """Must agree byte-for-byte with `Query_Server.component_id`: Java's
    `FileTime.toMillis()` truncates nanoseconds, so integer-divide here."""
    try:
        st = os.stat(component_jar())
    except OSError:
        return ""
    return "%d:%d" % (st.st_mtime_ns // 1_000_000, st.st_size)


def cache_path():
    override = os.environ.get("ISABELLE_QUERY_CLIENT_CACHE")
    if override:
        return override
    base = os.environ.get("XDG_CACHE_HOME") or os.path.join(
        os.path.expanduser("~"), ".cache"
    )
    return os.path.join(base, "isabelle-query", "client.json")


def settings_key():
    """What a cached resolution is valid for.  `USER_HOME` is in the key
    because the development loop moves the whole Isabelle user home into a
    scratch tree, and a cache that ignored it would send a scratch client at
    the real registry.  `PATH` is in it because that is what decides WHICH
    `isabelle` we are talking about."""
    return "\0".join(
        [
            os.environ.get("PATH", ""),
            os.environ.get("ISABELLE_TOOL", ""),
            os.environ.get("USER_HOME", ""),
            os.environ.get("HOME", ""),
            os.environ.get("ISABELLE_IDENTIFIER", ""),
        ]
    )


def read_cache():
    try:
        with open(cache_path(), "r", encoding="utf-8") as f:
            cached = json.load(f)
    except (OSError, ValueError):
        return {}
    return cached if cached.get("key") == settings_key() else {}


def write_cache(entries):
    path = cache_path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = "%s.tmp%d" % (path, os.getpid())
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dict(entries, key=settings_key()), f)
        os.replace(tmp, path)
    except OSError:
        pass  # a cache that cannot be written is slow, not wrong


def find_isabelle(cached):
    """The `isabelle` wrapper: how `$ISABELLE_HOME_USER` is resolved and how a
    server gets started.  `None` when there is none to be found, which is not
    an error here -- the caller returns EXIT_RUN_COLD and the shim, which has
    its own settings environment, runs the query."""
    exe = os.environ.get("ISABELLE_TOOL") or cached.get("isabelle", "")
    if exe and os.access(exe, os.X_OK):
        return exe
    from shutil import which

    return which("isabelle")


def home_user(isabelle, cached):
    """`$ISABELLE_HOME_USER`, which is where the server registry lives.

    Resolving it means one `isabelle getenv`, which is pure bash and starts no
    JVM — but it does source the whole settings environment, ~185 ms, which is
    six times this script's own budget.  So the answer is cached, keyed on
    everything that could change it.  (Through P7 this comment claimed `getenv`
    was "a full JVM boot"; it never was.  The cache is worth having anyway, for
    the real reason.)"""
    home = cached.get("home_user", "")
    if home and os.path.isdir(home):
        return home

    import subprocess

    try:
        raw = subprocess.run(
            [isabelle, "getenv", "-b", "ISABELLE_HOME_USER"],
            capture_output=True,
            text=True,
            timeout=START_TIMEOUT,
        )
    except (OSError, subprocess.SubprocessError) as exn:
        raise Fallback("cannot run `isabelle getenv`: %s" % exn)
    if raw.returncode != 0:
        raise Fallback("`isabelle getenv` failed: %s" % raw.stderr.strip())
    home = raw.stdout.strip()
    if not home:
        raise Fallback("empty ISABELLE_HOME_USER")
    write_cache({"isabelle": isabelle, "home_user": home})
    return home


# --------------------------------------------------------------------------
# the registry
# --------------------------------------------------------------------------


def registry_entries(isabelle, cached, name=None):
    """`$ISABELLE_HOME_USER/servers.db`, read-only.  The schema is
    `isabelle_servers(name, port, password)` — see `Server.private_data`.

    Opened in read-only URI mode so a client can never take the write lock the
    server's own `init()` needs."""
    db = os.path.join(home_user(isabelle, cached), "servers.db")
    cached["_registry_db"] = db
    if not os.path.isfile(db):
        return []
    try:
        from urllib.parse import quote
        con = sqlite3.connect("file:%s?mode=ro" % quote(db, safe="/"), uri=True, timeout=0.05)
    except sqlite3.Error:
        return []
    try:
        sql = "SELECT name, port, password FROM isabelle_servers"
        rows = con.execute(sql if name is None else sql + " WHERE name = ?",
                           () if name is None else (name,)).fetchall()
        return [(n, p, secret) for n, p, secret in rows
                if isinstance(n, str) and isinstance(p, int) and 0 < p < 65536
                and isinstance(secret, str) and secret and "\n" not in secret]
    except sqlite3.Error:
        return []
    finally:
        con.close()


def registry_lookup(isabelle, cached, name):
    rows = registry_entries(isabelle, cached, name)
    return rows[0][1:] if rows else None


def prune_refused_server(cached, name, port, password, selected_name=None):
    """Called only after connection refusal; never wait for a registry writer."""
    db = cached.get("_registry_db")
    if (name.startswith(HOST_PREFIX) or not db or
            not (name.startswith(FALLBACK_PREFIX) or name == selected_name)):
        return
    from urllib.parse import quote

    try:
        con = sqlite3.connect("file:%s?mode=rw" % quote(db, safe="/"), uri=True, timeout=0)
    except sqlite3.Error:
        return
    try:
        with con:
            con.execute("BEGIN IMMEDIATE")
            con.execute("DELETE FROM isabelle_servers WHERE name=? AND port=? AND password=?",
                        (name, port, password))
    except sqlite3.Error:
        pass
    finally:
        con.close()


_owned_launches = []
SHUTDOWN_TIMEOUT = 4.0  # Allow the launcher's 3-second watchdog to finish first.


def close_owned_since(mark):
    import signal
    import subprocess

    owned = _owned_launches[mark:]
    del _owned_launches[mark:]
    for proc in reversed(owned):
        try:
            proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=SHUTDOWN_TIMEOUT)
            continue
        except (OSError, subprocess.TimeoutExpired):
            pass
        # Kill the whole group before reaping its leader; TERM can lose the
        # wrapper first and leave a resistant JVM orphaned.
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except OSError:
            pass
        try:
            proc.wait(timeout=1)
        except (OSError, subprocess.TimeoutExpired):
            pass


def start_server(isabelle, name):
    """Keep the owned launcher alive through a private stdin pipe."""
    import selectors
    import subprocess

    try:
        proc = subprocess.Popen(
            [isabelle, "query_server", "-n", name],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            stdin=subprocess.PIPE, start_new_session=True, cwd="/",
        )
    except OSError as exn:
        raise Fallback("cannot start the server: %s" % exn)

    try:
        deadline = time.monotonic() + START_TIMEOUT
        data = b""
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout, selectors.EVENT_READ)
            while b"\n" not in data:
                left = deadline - time.monotonic()
                if left <= 0 or not selector.select(left):
                    raise Fallback("server startup timed out")
                chunk = os.read(proc.stdout.fileno(), 4096)
                if not chunk:
                    raise Fallback("server exited during start-up")
                data += chunk
                if len(data) > 65536:
                    raise Fallback("oversized server greeting")
        line = data.split(b"\n", 1)[0].decode("utf-8")
        try:
            address = line.split("= ", 1)[1].split(" ", 1)[0]
            port = int(address.rsplit(":", 1)[1])
            password = line.split('(password "', 1)[1].split('"', 1)[0]
            if not 0 < port < 65536 or not password:
                raise ValueError()
        except (IndexError, ValueError):
            # A greeting contains credentials: never include it in diagnostics.
            raise Fallback("unparsable server greeting")
        _owned_launches.append(proc)
        return port, password
    except BaseException:
        # The Java tool wrapper can retain a shell above the JVM. The new
        # session's process group belongs entirely to this failed launch, so
        # kill that group before reaping its leader (and allowing PID reuse).
        # Killing only the shell can orphan a JVM holding our stdout pipe.
        # No registry lookup or existing server is involved in this cleanup.
        import signal

        try:
            proc.stdin.close()
        except OSError:
            pass
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except OSError:
            pass
        try:
            proc.wait(timeout=1)
        except (OSError, subprocess.TimeoutExpired):
            pass  # Preserve the startup failure, with bounded cleanup.
        raise
    finally:
        proc.stdout.close()


def shutdown_connection(conn):
    if shared(conn):
        raise Fallback("refusing to stop/restart an embedded query host")
    if type(conn.version.get("protocol")) is not int or conn.version["protocol"] != PROTOCOL:
        raise Fallback("refusing to stop/restart an incompatible query protocol")
    conn.deadline, conn.timeout = None, GREETING_TIMEOUT
    head, body = conn.command("shutdown")
    if head != "OK":
        raise Fallback("cannot stop the selected server: %s" % body.get("message", body))


def candidate_rows(isabelle, cached, name, dedicated_only=False):
    explicit = "ISABELLE_QUERY_CLIENT_SERVER" in os.environ
    rows = registry_entries(isabelle, cached, name if explicit else None)
    if explicit:
        return rows
    return [r for r in rows if r[0] == name or
            (not dedicated_only and r[0].startswith((FALLBACK_PREFIX, HOST_PREFIX)))]


def candidate_rank(name, candidate):
    return (0 if candidate.startswith(HOST_PREFIX + "jedit_") else
            1 if candidate.startswith(HOST_PREFIX + "pide_") else
            2 if candidate == name else 3, candidate)


def stop_server(isabelle, name, cached=None, quiet=False):
    if name.startswith(HOST_PREFIX):
        raise Fallback("refusing to stop/restart an embedded query host")
    cached = cached if cached is not None else {}
    rows = candidate_rows(isabelle, cached, name, dedicated_only=True)
    deadline = time.monotonic() + DISCOVERY_TIMEOUT
    failure = None
    for candidate, port, password in sorted(rows, key=lambda r: candidate_rank(name, r[0])):
        if time.monotonic() >= deadline:
            break
        conn = None
        try:
            conn = probe(candidate, (port, password), min(deadline, time.monotonic() + PROBE_TIMEOUT))
            if shared(conn):
                raise Fallback("refusing to stop/restart an embedded query host")
        except ConnectionRefusedError:
            prune_refused_server(cached, candidate, port, password,
                                 selected_name=os.environ.get("ISABELLE_QUERY_CLIENT_SERVER",
                                     DEFAULT_SERVER if name == DEFAULT_SERVER else None))
            continue  # A dead listener has nothing left to stop.
        except (Fallback, OSError, ValueError, TypeError) as exn:
            failure = exn
            if conn is not None:
                conn.close()
            continue
        try:
            shutdown_connection(conn)
            return 0
        finally:
            conn.close()
    if failure is not None:
        raise Fallback("cannot verify a dedicated server to stop: %s" % failure)
    return 0


# --------------------------------------------------------------------------
# the line protocol
# --------------------------------------------------------------------------


class Connection:
    """One authenticated socket, speaking the framing documented at the head of
    `Pure/Tools/server.scala`: a short message is one LF-terminated line; a
    long one is a line of decimal digits (the byte length, newline included)
    followed by exactly that many bytes."""

    def __init__(self, port, password, timeout, deadline=None):
        self.deadline = deadline
        self.timeout = timeout
        self.sock = socket.create_connection(
            ("127.0.0.1", port), timeout=self.remaining(CONNECT_TIMEOUT))
        try:
            # Without this a long message costs a delayed ACK: the framing writes
            # a length header and then a payload, Nagle holds the second segment
            # until the first is acknowledged, and the round trip jumps from about
            # 1 ms to about 41 ms — measured, and the single biggest thing between
            # this client and the floor.  A request/response protocol has nothing
            # to coalesce, so the algorithm only ever costs here.
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.sock.settimeout(self.remaining(GREETING_TIMEOUT))
            self.buf = b""
            self.sock.sendall(password.encode("utf-8") + b"\n")
            greeting = self.read_message()
            if greeting is None or greeting.split(" ", 1)[0] != "OK":
                raise Fallback("server did not greet (bad password or dead socket)")
        except BaseException:
            self.close()
            raise

    def remaining(self, default):
        if self.deadline is None:
            return default
        left = self.deadline - time.monotonic()
        if left <= 0:
            raise socket.timeout("discovery deadline expired")
        return min(default, left) if default is not None else left

    def recv(self, n):
        if self.deadline is not None:
            self.sock.settimeout(self.remaining(PROBE_TIMEOUT))
        return self.sock.recv(n)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def _read_line(self):
        while b"\n" not in self.buf:
            chunk = self.recv(65536)
            if not chunk:
                return None
            self.buf += chunk
            if self.deadline is not None and len(self.buf) > 1048576:
                raise Fallback("oversized discovery reply")
        line, self.buf = self.buf.split(b"\n", 1)
        return line[:-1] if line.endswith(b"\r") else line

    def _read_exactly(self, n):
        while len(self.buf) < n:
            chunk = self.recv(max(65536, n - len(self.buf)))
            if not chunk:
                return None
            self.buf += chunk
        block, self.buf = self.buf[:n], self.buf[n:]
        return block

    def read_message(self):
        line = self._read_line()
        if line is None:
            return None
        if line.isdigit():
            if self.deadline is not None and int(line) > 1048576:
                raise Fallback("oversized discovery reply")
            block = self._read_exactly(int(line))
            if block is None:
                return None
            return block.rstrip(b"\n").decode("utf-8", "replace")
        return line.decode("utf-8", "replace")

    def command(self, name, argument=None):
        payload = (name if argument is None else
                   "%s %s" % (name, json.dumps(argument))).encode("utf-8")
        # The server's own rule for when a header is needed, mirrored: over
        # 100 bytes, or containing a newline.  Header and payload go out in ONE
        # write, so the wire sees one segment even where Nagle is in force.
        header = (
            b"%d\n" % (len(payload) + 1)
            if len(payload) > 100 or b"\n" in payload
            else b""
        )
        self.sock.settimeout(self.remaining(self.timeout if self.timeout > 0 else None))
        self.sock.sendall(header + payload + b"\n")
        reply = self.read_message()
        if reply is None:
            raise ConnectionResetError("connection closed before an answer arrived")
        head, _, rest = reply.partition(" ")
        try:
            body = json.loads(rest) if rest.strip() else {}
        except ValueError:
            raise Fallback("unparsable reply: %r" % reply[:120])
        if not isinstance(body, dict):
            raise Fallback("reply is not an object")
        return head, body


# --------------------------------------------------------------------------
# the run
# --------------------------------------------------------------------------


def cold(verbose, why):
    """Decline, and say why if asked.  The last word on every failure.

    Until P8 this ran the cold path itself, by `execv`-ing `isabelle query` —
    which, since the shim took over that tool name, re-entered the very script
    it was leaving.  That hop needed an environment mark to stop it looping,
    and landed in a JVM that then re-tried the registry this client had just
    failed on.  Now the decision is simply reported upward: the shim called us
    and the shim is what falls back.

    THE INVARIANT THAT MAKES IT SAFE is the one this client already had —
    nothing reaches stdout until a complete OK reply is in hand — so a decline
    at any point, for any reason, can neither duplicate nor truncate output.
    Every `return EXIT_RUN_COLD` below is downstream of that guarantee."""
    note(verbose, why)
    sys.stdout.flush()
    sys.stderr.flush()
    return EXIT_RUN_COLD


def shared(conn):
    return (conn.name.startswith(HOST_PREFIX) or
            conn.version.get("host_kind", "server") != "server")


def probe(name, info, deadline):
    conn = Connection(*info, PROBE_TIMEOUT, deadline=deadline)
    try:
        head, version = conn.command("query_version")
        if head != "OK" or not isinstance(version, dict):
            raise Fallback("host has no query_version")
        kind = version.get("host_kind", "server")
        if kind not in ("server", "jedit", "pide"):
            raise Fallback("unknown query host kind")
        if kind != "server" and version.get("host_name") != name:
            raise Fallback("query host name mismatch")
        if ("host_pid" in version and
                (type(version["host_pid"]) is not int or version["host_pid"] <= 0)):
            raise Fallback("invalid query host PID")
        if "retain_indexes" in version and type(version["retain_indexes"]) is not bool:
            raise Fallback("invalid query retention policy")
        conn.name, conn.version = name, version
        return conn
    except BaseException:
        conn.close()
        raise


def compatible(conn, stamp):
    return (bool(stamp) and type(conn.version.get("protocol")) is int and
            conn.version.get("protocol") == PROTOCOL and
            conn.version.get("component_id") == stamp)


def ready_connection(conn, timeout, restarted, verbose):
    conn.deadline, conn.timeout = None, timeout
    conn.restarted = restarted
    note(verbose, "host %s (%s), retention %s" %
         (conn.name, conn.version.get("host_kind", "server"),
          conn.version.get("retain_indexes", "unknown")))
    return conn


def start_checked(isabelle, name, timeout, restarted, verbose):
    note(verbose, "starting dedicated server %s" % name)
    mark = len(_owned_launches)
    conn = None
    try:
        conn = probe(name, start_server(isabelle, name), time.monotonic() + GREETING_TIMEOUT)
        if shared(conn) or not compatible(conn, jar_stamp()):
            raise Fallback("started server is incompatible")
        return ready_connection(conn, timeout, restarted, verbose)
    except BaseException:
        if conn is not None:
            conn.close()
        close_owned_since(mark)
        raise


def connect(isabelle, cached, name, restart, timeout, verbose):
    """Discover reserved hosts only; unverifiable defaults get a managed fallback."""
    explicit = "ISABELLE_QUERY_CLIENT_SERVER" in os.environ
    if restart and name.startswith(HOST_PREFIX):
        raise Fallback("refusing to stop/restart an embedded query host")
    stamp = jar_stamp()
    if not stamp:
        raise Fallback("local query component identity is unavailable")
    rows = candidate_rows(isabelle, cached, name, dedicated_only=restart)
    deadline = time.monotonic() + DISCOVERY_TIMEOUT
    refused = set()
    failure = "selected server is unavailable or incompatible"
    stale = None
    selected = None
    try:
        for candidate, port, password in sorted(rows, key=lambda r: candidate_rank(name, r[0])):
            if time.monotonic() >= deadline:
                break
            if candidate != name and time.monotonic() >= deadline - PROBE_TIMEOUT:
                continue
            conn = None
            try:
                conn = probe(candidate, (port, password),
                             min(deadline if candidate == name else deadline - PROBE_TIMEOUT,
                                 time.monotonic() + PROBE_TIMEOUT))
                if restart and shared(conn):
                    raise Fallback("refusing to stop/restart an embedded query host")
                if compatible(conn, stamp):
                    selected, conn = conn, None
                    break
                # Retain a verified dedicated candidate for explicit restart only.
                if (not shared(conn) and type(conn.version.get("protocol")) is int
                        and conn.version["protocol"] == PROTOCOL and stale is None):
                    stale, conn = conn, None
            except ConnectionRefusedError:
                refused.add(candidate)
                prune_refused_server(cached, candidate, port, password,
                                     selected_name=os.environ.get("ISABELLE_QUERY_CLIENT_SERVER",
                                         DEFAULT_SERVER if restart and name == DEFAULT_SERVER else None))
            except (Fallback, OSError, ValueError, TypeError) as exn:
                failure = str(exn) or "selected listener did not answer"
            if conn is not None:
                conn.close()
        if selected is not None:
            if not restart:
                return ready_connection(selected, timeout, False, verbose)
            try:
                shutdown_connection(selected)
                return start_checked(isabelle, selected.name, timeout, True, verbose)
            finally:
                selected.close()
        if stale is not None and restart:
            shutdown_connection(stale)
            return start_checked(isabelle, stale.name, timeout, True, verbose)
        if name.startswith(HOST_PREFIX):
            raise Fallback("selected embedded query host is unavailable or incompatible")
        selected_exists = any(r[0] == name for r in rows)
        if selected_exists and name not in refused and (explicit or restart):
            raise Fallback(failure)
        if not explicit and not restart:
            import uuid
            name = FALLBACK_PREFIX + uuid.uuid4().hex
        return start_checked(isabelle, name, timeout, restart, verbose)
    finally:
        if stale is not None:
            stale.close()


def note(verbose, msg):
    if verbose:
        sys.stderr.write("query-client: %s\n" % msg)


def resolve(p):
    return os.path.abspath(os.path.expanduser(p))


def absolutize(args):
    """A served run happens in the server's working directory, not the user's,
    so a relative path in the argument list would resolve somewhere else.

    Exactly ONE argument is rewritten: `-R`/`--root`'s, in all four spellings.
    It is rewritten whether or not it exists, because an unreadable root is a
    diagnostic the tool must give about the path the user meant, and it is safe
    to rewrite because that option's argument is a directory in every
    invocation there is -- no grammar has to be consulted to know it.

    `~` is expanded HERE for the same reason the path is made absolute: the
    tool expands it against `user.home`, which inside a server is the home of
    whoever started it.  The shell normally does this first; a quoted `'~/p'`
    is the case that reaches us.

    Every OTHER relative path is handled by not serving the request at all;
    see `ambiguous` below."""
    out = []
    i = 0
    while i < len(args):
        tok = args[i]
        if tok in ("-R", "--root"):
            out.append(tok)
            if i + 1 < len(args):
                out.append(resolve(args[i + 1]))
                i += 1
        elif tok.startswith("--root="):
            out.append("--root=" + resolve(tok[len("--root=") :]))
        elif tok.startswith("-R") and len(tok) > 2:
            out.append("-R" + resolve(tok[2:]))
        else:
            out.append(tok)
        i += 1
    return out


def ambiguous(args):
    """THE ARGUMENT THIS TRANSPORT MUST NOT DECIDE.

    Whether a positional is a path or a pattern is a fact about the COMMAND:
    `find .` searches for the regex `.`, `grep pat .` searches the directory
    `.`, and the two tokens are spelled identically.  This client used to
    rewrite every token that named an existing file, which turned the first
    into a search for the caller's absolute working directory -- and the wrong
    answer arrived looking like a correct empty result, `No entries matching
    '<cwd>'`.  Rewriting none of them instead would send the second to the
    server's own `/`.

    Neither guess is available, so the invocation runs COLD, where relative
    means what the user meant.  Two kinds of token are not ambiguous, and
    excluding them is what keeps the rule from swallowing the warm path whole:
    one that names nothing (the server resolves it exactly as a local run
    would), and one that is ABSOLUTE (it means the same thing in any working
    directory, so no rewriting is needed and none is done).  A `~`-prefixed
    token IS ambiguous despite looking absolute: expanding it could corrupt a
    pattern, and not expanding it would send it to a server whose home is
    somebody else's.  `-R`'s argument is skipped -- `absolutize` has already
    dealt with it."""
    i = 0
    while i < len(args):
        tok = args[i]
        if tok in ("-R", "--root"):
            i += 1
        elif len(tok) > 1 and tok.startswith("-"):
            pass
        elif tok.startswith("~"):
            if os.path.exists(os.path.expanduser(tok)):
                return tok
        elif not tok.startswith("/") and os.path.exists(tok):
            return tok
        i += 1
    return None


def positionals(args, n=2):
    """The first N positional tokens, by the same rule the CLI's own top-level
    loop uses: `-R`/`--root` consume a following argument, anything else
    starting with `-` is an option, and a bare `--` ends option processing."""
    out = []
    only_pos = False
    i = 0
    while i < len(args) and len(out) < n:
        tok = args[i]
        if only_pos:
            out.append(tok)
        elif tok == "--":
            only_pos = True
        elif tok in ("-R", "--root"):
            i += 1
        elif len(tok) > 1 and tok.startswith("-"):
            pass
        else:
            out.append(tok)
        i += 1
    return out


def request(args, limit, verbose):
    env = {var: os.environ[var] for var in FORWARDED_ENV if os.environ.get(var)}
    env_root = ""
    for var in ("ISABELLE_LAYOUT_ROOT", "ISABELLE_QUERY_ROOT"):
        if env.get(var):
            env_root = env[var]
            break
    body = {
        "argv": absolutize(args),
        "cwd": os.getcwd(),
        # `env_root` is the resolved root variable, which `query_open` also
        # takes; `env` is the raw set, bound for this request inside the CLI.
        "env_root": env_root,
        "env": env,
        "client_id": jar_stamp(),
    }
    if limit is not None:
        body["limit"] = limit
    return body


def warm(isabelle, args, opts):
    """Rediscover once after transport loss, before emitting any answer."""
    conn = None
    payload = request(args, opts["limit"], opts["verbose"])
    try:
        for attempt in range(2):
            try:
                conn = connect(isabelle, opts["cached"], opts["name"],
                               opts["restart"] if attempt == 0 else False,
                               opts["timeout"], opts["verbose"])
                head, body = conn.command("query_run", payload)
                break
            except ConnectionError:
                if attempt:
                    raise
                if conn is not None:
                    conn.close()
                    conn = None
        if head != "OK":
            try:
                sys.stderr.write("isabelle query: %s\n" % body.get("message", body))
            except BrokenPipeError:
                return 141
            except (OSError, UnicodeError):
                return 2
            return 2
        output, error, rc = body.get("output", ""), body.get("error", ""), int(body.get("exit", 0))
        if not isinstance(output, str) or not isinstance(error, str) or rc == EXIT_RUN_COLD:
            raise Fallback("malformed query result")
        try:
            sys.stdout.write(output)
            sys.stderr.write(error)
            sys.stdout.flush()
        except BrokenPipeError:
            return 141
        except (OSError, UnicodeError):
            # Bytes may already have escaped. Never signal cold replay here.
            return 2
        return rc
    finally:
        if conn is not None:
            conn.close()


def status(isabelle, opts):
    conn = connect(isabelle, opts["cached"], opts["name"], opts["restart"],
                   opts["timeout"], opts["verbose"])
    try:
        head, body = conn.command("query_version", {"open": True})
    finally:
        conn.close()
    if head != "OK":
        sys.stderr.write("query: %s\n" % body.get("message", body))
        return 2
    print("server        %s" % conn.name)
    print("host_kind     %s" % body.get("host_kind", "server"))
    print("host_pid      %s" % body.get("host_pid", "unknown"))
    print("retain_indexes %s" % body.get("retain_indexes", "unknown"))
    print("protocol      %s (client %d)" % (body.get("protocol"), PROTOCOL))
    print("version       %s" % body.get("version"))
    print("component_id  %s%s" % (body.get("component_id"),
                                  "" if body.get("component_id") == jar_stamp()
                                  else "  [STALE: jar is %s]" % jar_stamp()))
    for ix in body.get("open", []):
        print(
            "index         %s  %s theories, %s entries, "
            "%s ms build / %s ms recheck, %s uses"
            % (ix.get("root"), ix.get("theories"), ix.get("entries"),
               ix.get("build_ms"), ix.get("check_ms"), ix.get("uses"))
        )
    return 0


def cache_control(isabelle, opts):
    conn = connect(isabelle, opts["cached"], opts["name"], opts["restart"],
                   opts["timeout"], opts["verbose"])
    try:
        head, body = conn.command("query_cache", {"mode": opts["cache"],
                                                  "client_id": jar_stamp()})
        if head != "OK":
            raise Fallback(str(body.get("message", body)))
        print("server        %s" % conn.name)
        print("host_kind     %s" % conn.version.get("host_kind", "server"))
        print("retain_indexes %s" % body.get("retain_indexes", "unknown"))
        return 0
    finally:
        conn.close()


def main(argv):
    mark = len(_owned_launches)
    try:
        return _main(argv)
    finally:
        close_owned_since(mark)


def _main(argv):
    cached = read_cache()
    opts = {
        "cached": cached,
        "name": os.environ.get("ISABELLE_QUERY_CLIENT_SERVER", DEFAULT_SERVER),
        "timeout": float(
            os.environ.get("ISABELLE_QUERY_CLIENT_TIMEOUT", DEFAULT_TIMEOUT)
        ),
        "limit": None,
        "restart": False,
        "verbose": False,
    }
    force_cold = (os.environ.get("ISABELLE_QUERY_CLIENT_COLD") == "1" or
                  os.environ.get("ISABELLE_QUERY_NO_SERVER") == "1")
    action = "run"

    while argv and argv[0].startswith("--client-"):
        opt = argv.pop(0)
        if opt == "--client-cold":
            force_cold = True
        elif opt == "--client-status":
            action = "status"
        elif opt == "--client-cache":
            if not argv or argv[0] not in ("on", "off", "clear"):
                sys.stderr.write("query: --client-cache: expected on, off, or clear\n")
                return 2
            opts["cache"] = argv.pop(0)
            action = "cache"
        elif opt == "--client-stop":
            action = "stop"
        elif opt == "--client-restart":
            opts["restart"] = True
        elif opt == "--client-verbose":
            opts["verbose"] = True
        elif opt in ("--client-limit", "--client-timeout"):
            if not argv:
                sys.stderr.write("query: %s: expected one argument\n" % opt)
                return 2
            value = argv.pop(0)
            try:
                opts["limit" if opt.endswith("limit") else "timeout"] = (
                    int(value) if opt.endswith("limit") else float(value)
                )
            except ValueError:
                sys.stderr.write("query: %s: not a number: %s\n" % (opt, value))
                return 2
        else:
            sys.stderr.write("query: unknown client option: %s\n" % opt)
            return 2

    if force_cold and (action != "run" or opts["restart"]):
        sys.stderr.write("query: client action requires the warm path\n")
        return 2

    # Resolved AFTER the options, so a decline can be reported at the verbosity
    # the caller asked for.  No `isabelle` means no registry and no way to
    # start a server: decline, and let the shim -- which reached this script
    # through a settings environment of its own -- run the query.  For
    # `--client-status` / `--client-stop` there is nothing to decline TO, so
    # those say what is wrong instead.
    isabelle = find_isabelle(cached)
    if isabelle is None:
        if action == "run" and not opts["restart"]:
            return cold(opts["verbose"], "no `isabelle` on PATH, and no $ISABELLE_TOOL")
        sys.stderr.write("query: no `isabelle` on PATH, and no $ISABELLE_TOOL\n")
        return 2

    if action != "run":
        try:
            if action == "stop":
                return stop_server(isabelle, opts["name"], cached)
            return status(isabelle, opts) if action == "status" else cache_control(isabelle, opts)
        except (Fallback, OSError, ValueError, TypeError) as exn:
            sys.stderr.write("query: %s\n" % exn)
            return 2

    # `shape census` is on the bypass list for two structural reasons: a 256 MB
    # reply through a synchronous single-message protocol is SLOWER warm than
    # cold, and a census gets no benefit from a warm index anyway, because it
    # iterates sessions itself rather than going through `load_index`.
    pos = positionals(argv)
    first = pos[0] if pos else None
    census = pos[:2] == ["shape", "census"]
    live = ambiguous(argv)
    if force_cold or first in COLD_ONLY_COMMANDS or census or "-" in argv or live:
        if opts["restart"]:
            sys.stderr.write("query: --client-restart requires a warm-compatible invocation\n")
            return 2
        return cold(opts["verbose"],
                    "cold path" if not live
                    else "cold path: relative to this directory: %r" % live)

    start = time.monotonic()
    try:
        rc = warm(isabelle, argv, opts)
        try:
            note(opts["verbose"], "warm, %.1f ms" % ((time.monotonic() - start) * 1000))
        except OSError:
            pass  # The answer has been emitted; never replay for a timing log.
        return rc
    except Fallback as exn:
        if opts["restart"]:
            sys.stderr.write("query: %s\n" % exn)
            return 2
        return cold(opts["verbose"], "falling back: %s" % exn)
    except socket.timeout:
        # Falling back would repeat work that has already run longer than the
        # caller allowed; say so instead of doubling the wait.  (Checked before
        # OSError below, of which it is a subclass.)
        sys.stderr.write(
            "query: no answer within %ss -- raise --client-timeout, "
            "or use --client-cold\n" % opts["timeout"]
        )
        return 2
    except OSError as exn:
        if opts["restart"]:
            sys.stderr.write("query: %s\n" % exn)
            return 2
        # A socket that dies mid-request -- the server killed, the connection
        # reset.  Nothing has been written to stdout yet (that happens only
        # after a complete OK reply), so running cold cannot duplicate output,
        # and a traceback here would be a worse answer than a slow one.
        return cold(opts["verbose"], "falling back: %s" % exn)
    except (ValueError, KeyError, TypeError) as exn:
        if opts["restart"]:
            sys.stderr.write("query: malformed reply: %s\n" % exn)
            return 2
        # A reply this client cannot make sense of is a protocol mismatch, and
        # a protocol mismatch is exactly what the cold path is for.
        return cold(opts["verbose"], "falling back: malformed reply: %s" % exn)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    import signal

    def interrupted(signum, frame):
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        sys.exit(main(sys.argv[1:]))
    except BrokenPipeError:
        # Match the tool: a closed stdout is the shell's 141, not an error.
        os._exit(141)
