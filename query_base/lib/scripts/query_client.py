#!/usr/bin/env python3
"""Thin client for the warm `isabelle query` server.

`isabelle query` spends about 850 ms starting a JVM before it looks at a single
theory, which is most of the cost of a small query.  The engine's answer to
that is a `Server.Commands` service folded into the stock `isabelle server`
(see `query_base/src/server.scala`); this is the other half — a client that
speaks the server's documented line protocol directly, so nothing on the fast
path is a JVM.

Deliberately stdlib-only and deliberately small.  It is a TRANSPORT, not a
second front end: it does not know the tool's options, does not validate them,
and does not format results.  `argv` goes over the wire verbatim and the
server hands it to the same `CLI.run_result` a typed command reaches, so a
served answer and a typed one differ only in who wrote the bytes.

The safety rule, in order of priority: never a wrong answer, then never a
hang, then fast.  Every failure mode — no server, a refused connection, a
protocol the client does not know, a component rebuilt under a running server
— falls back to running `isabelle query` cold.  A slower right answer is
always available; a wrong one must not be.

Usage:
    query_client.py [--client-OPTION ...] ARG ...

Client options are recognised only BEFORE the first tool argument, and all
carry the `--client-` prefix so they cannot collide with the tool's own:

    --client-cold          skip the server entirely (the cold path)
    --client-status        report on the server and its open indexes, then exit
    --client-stop          shut the server down, then exit
    --client-restart       restart the server before running
    --client-limit N       index size cap for this request (0 disables)
    --client-timeout S     seconds to wait for the answer (default 600)
    --client-verbose       report timings and the path taken, on stderr

Environment:
    ISABELLE_QUERY_CLIENT_SERVER    server name (default: isabelle_query)
    ISABELLE_QUERY_CLIENT_COLD      set to 1 to force the cold path
    ISABELLE_QUERY_CLIENT_TIMEOUT   default for --client-timeout
    ISABELLE_QUERY_CLIENT_CACHE     where the resolved settings are cached
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
DEFAULT_SERVER = "isabelle_query"
DEFAULT_TIMEOUT = 600.0

# A connect that does not answer at once is a dead registry row, not a busy
# server: the accept loop is one thread doing nothing else.
CONNECT_TIMEOUT = 2.0
GREETING_TIMEOUT = 10.0
# JVM boot plus the component's class loading.  Generous because the cost of
# being wrong here is a spurious fall back to the cold path, not an error.
START_TIMEOUT = 60.0

# These write straight to the process's own stdout inside the JVM (they are
# corpus dumps, sized for a pipe, not for a socket), and `-` reads the
# client's stdin, which the server has no access to.  Both are cold-only.
COLD_ONLY_COMMANDS = {"dump-entries", "dump-imports", "dump-theories"}


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
    """The `isabelle` wrapper: the cold path, and how a server gets started."""
    exe = os.environ.get("ISABELLE_TOOL") or cached.get("isabelle", "")
    if exe and os.access(exe, os.X_OK):
        return exe
    from shutil import which

    exe = which("isabelle")
    if exe:
        return exe
    sys.stderr.write("query: no `isabelle` on PATH, and no $ISABELLE_TOOL\n")
    sys.exit(2)


def home_user(isabelle, cached):
    """`$ISABELLE_HOME_USER`, which is where the server registry lives.

    Resolving it means one `isabelle getenv` — a full JVM boot, the very cost
    this client exists to avoid — so the answer is cached and keyed on
    everything that could change it."""
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


def registry_lookup(isabelle, cached, name):
    """`$ISABELLE_HOME_USER/servers.db`, read-only.  The schema is
    `isabelle_servers(name, port, password)` — see `Server.private_data`.

    Opened in read-only URI mode so a client can never take the write lock the
    server's own `init()` needs."""
    db = os.path.join(home_user(isabelle, cached), "servers.db")
    if not os.path.isfile(db):
        return None
    try:
        con = sqlite3.connect("file:%s?mode=ro" % db, uri=True, timeout=2.0)
    except sqlite3.Error:
        return None
    try:
        row = con.execute(
            "SELECT port, password FROM isabelle_servers WHERE name = ?", (name,)
        ).fetchone()
    except sqlite3.Error:
        return None
    finally:
        con.close()
    if not row:
        return None
    return int(row[0]), str(row[1])


def start_server(isabelle, name):
    """`isabelle server` prints `server "NAME" = HOST:PORT (password "...")`
    and then blocks in the foreground, so the port and password arrive on its
    stdout — no need to race the registry for the row it just wrote.

    `start_new_session` detaches it: the client exits in milliseconds and the
    server must outlive it.  Its environment is inherited, `USER_HOME`
    included, which is what keeps a development client talking to a
    development server."""
    import subprocess

    try:
        proc = subprocess.Popen(
            [isabelle, "server", "-n", name],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
            cwd="/",  # a shared server must not inherit one client's cwd
            text=True,
        )
    except OSError as exn:
        raise Fallback("cannot start the server: %s" % exn)

    deadline = time.monotonic() + START_TIMEOUT
    line = ""
    while time.monotonic() < deadline:
        line = proc.stdout.readline()
        if line:
            break
        if proc.poll() is not None:
            raise Fallback("server exited during start-up")
    proc.stdout.close()
    if "= " not in line or "(password " not in line:
        raise Fallback("unreadable server greeting: %r" % line.strip())
    try:
        address = line.split("= ", 1)[1].split(" ", 1)[0]
        port = int(address.rsplit(":", 1)[1])
        password = line.split('(password "', 1)[1].split('"', 1)[0]
    except (IndexError, ValueError):
        raise Fallback("unparsable server greeting: %r" % line.strip())
    return port, password


def stop_server(isabelle, name):
    import subprocess

    try:
        proc = subprocess.run(
            [isabelle, "server", "-x", "-n", name],
            capture_output=True,
            text=True,
            timeout=START_TIMEOUT,
        )
    except (OSError, subprocess.SubprocessError) as exn:
        sys.stderr.write("query: cannot stop the server: %s\n" % exn)
        return 2
    sys.stdout.write(proc.stdout)
    sys.stderr.write(proc.stderr)
    return proc.returncode


# --------------------------------------------------------------------------
# the line protocol
# --------------------------------------------------------------------------


class Connection:
    """One authenticated socket, speaking the framing documented at the head of
    `Pure/Tools/server.scala`: a short message is one LF-terminated line; a
    long one is a line of decimal digits (the byte length, newline included)
    followed by exactly that many bytes."""

    def __init__(self, port, password, timeout):
        self.sock = socket.create_connection(
            ("127.0.0.1", port), timeout=CONNECT_TIMEOUT
        )
        # Without this a long message costs a delayed ACK: the framing writes
        # a length header and then a payload, Nagle holds the second segment
        # until the first is acknowledged, and the round trip jumps from about
        # 1 ms to about 41 ms — measured, and the single biggest thing between
        # this client and the floor.  A request/response protocol has nothing
        # to coalesce, so the algorithm only ever costs here.
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(GREETING_TIMEOUT)
        self.buf = b""
        self.sock.sendall(password.encode("utf-8") + b"\n")
        greeting = self.read_message()
        if greeting is None or not greeting.startswith("OK"):
            raise Fallback("server did not greet (bad password or dead socket)")
        self.timeout = timeout

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def _read_line(self):
        while b"\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                return None
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return line[:-1] if line.endswith(b"\r") else line

    def _read_exactly(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(65536, n - len(self.buf)))
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
            block = self._read_exactly(int(line))
            if block is None:
                return None
            return block.rstrip(b"\n").decode("utf-8", "replace")
        return line.decode("utf-8", "replace")

    def command(self, name, argument):
        payload = ("%s %s" % (name, json.dumps(argument))).encode("utf-8")
        # The server's own rule for when a header is needed, mirrored: over
        # 100 bytes, or containing a newline.  Header and payload go out in ONE
        # write, so the wire sees one segment even where Nagle is in force.
        header = (
            b"%d\n" % (len(payload) + 1)
            if len(payload) > 100 or b"\n" in payload
            else b""
        )
        self.sock.sendall(header + payload + b"\n")
        self.sock.settimeout(self.timeout if self.timeout > 0 else None)
        reply = self.read_message()
        if reply is None:
            raise Fallback("connection closed before an answer arrived")
        head, _, rest = reply.partition(" ")
        try:
            body = json.loads(rest) if rest.strip() else {}
        except ValueError:
            raise Fallback("unparsable reply: %r" % reply[:120])
        return head, body


# --------------------------------------------------------------------------
# the run
# --------------------------------------------------------------------------


def cold(isabelle, args):
    """The cold path, and the last word on every failure.  `execv` rather than
    a subprocess so the tool inherits this process entirely: its exit status is
    the status, and a SIGPIPE kills the right process."""
    sys.stdout.flush()
    sys.stderr.flush()
    os.execv(isabelle, [isabelle, "query"] + args)


def connect(isabelle, cached, name, restart, timeout, verbose):
    """A connection to the warm server, starting one if there is none."""
    if restart:
        stop_server(isabelle, name)
        info = None
    else:
        info = registry_lookup(isabelle, cached, name)
    if info is None:
        note(verbose, "starting the server")
        info = start_server(isabelle, name)
    port, password = info
    try:
        return Connection(port, password, timeout)
    except (OSError, socket.timeout) as exn:
        # A registry row outlives the process it names; the server prunes such
        # rows on the NEXT init, so starting one is both the retry and the
        # cleanup.
        note(verbose, "stale registry row (%s), starting a server" % exn)
        port, password = start_server(isabelle, name)
        try:
            return Connection(port, password, timeout)
        except (OSError, socket.timeout) as exn2:
            raise Fallback("cannot reach the server: %s" % exn2)


def note(verbose, msg):
    if verbose:
        sys.stderr.write("query-client: %s\n" % msg)


def absolutize(args):
    """A served run happens in the server's working directory, not the user's,
    so a relative path in the argument list would resolve somewhere else.

    Only tokens that NAME AN EXISTING FILE OR DIRECTORY here are rewritten,
    which is exactly the set the tool would have resolved as paths; a theory
    name, a pattern or a locus stays untouched.  `-R`'s argument is rewritten
    whether or not it exists, because an unreadable root is a diagnostic the
    tool must give about the path the user meant."""
    out = []
    i = 0
    while i < len(args):
        tok = args[i]
        if tok in ("-R", "--root"):
            out.append(tok)
            if i + 1 < len(args):
                out.append(os.path.abspath(args[i + 1]))
                i += 1
        elif tok.startswith("--root="):
            out.append("--root=" + os.path.abspath(tok[len("--root=") :]))
        elif tok.startswith("-R") and len(tok) > 2:
            out.append("-R" + os.path.abspath(tok[2:]))
        elif not tok.startswith("-") and os.path.exists(tok):
            out.append(os.path.abspath(tok))
        else:
            out.append(tok)
        i += 1
    return out


def request(args, limit, verbose):
    env_root = ""
    for var in ("ISABELLE_LAYOUT_ROOT", "ISABELLE_QUERY_ROOT"):
        if os.environ.get(var):
            env_root = os.environ[var]
            break
    body = {
        "argv": absolutize(args),
        "cwd": os.getcwd(),
        "env_root": env_root,
        "client_id": jar_stamp(),
    }
    if limit is not None:
        body["limit"] = limit
    return body


def warm(isabelle, args, opts):
    """One warm invocation, with exactly one restart allowed: a stale server
    is a fact about the component, so retrying against the same one would
    fail the same way, and retrying forever would be a hang with extra steps."""
    conn = connect(isabelle, opts["cached"], opts["name"], opts["restart"],
                   opts["timeout"], opts["verbose"])
    try:
        head, body = conn.command("query_run", request(args, opts["limit"],
                                                       opts["verbose"]))
        if head == "ERROR" and "stale query server" in str(body.get("message", "")):
            note(opts["verbose"], "component rebuilt under the server; restarting")
            conn.close()
            conn = connect(isabelle, opts["cached"], opts["name"], True,
                           opts["timeout"], opts["verbose"])
            head, body = conn.command("query_run", request(args, opts["limit"],
                                                           opts["verbose"]))
        if head != "OK":
            message = str(body.get("message", body))
            # A REFUSAL is the server's considered answer and must not be
            # silently replaced by a cold run that would happily do the work
            # the refusal exists to prevent.
            if "too large for a resident index" in message:
                sys.stderr.write("isabelle query: %s\n" % message)
                return 2
            raise Fallback("server error: %s" % message)
        sys.stdout.write(body.get("output", ""))
        sys.stderr.write(body.get("error", ""))
        sys.stdout.flush()
        return int(body.get("exit", 0))
    finally:
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
    print("server        %s" % opts["name"])
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


def main(argv):
    cached = read_cache()
    isabelle = find_isabelle(cached)
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
    force_cold = os.environ.get("ISABELLE_QUERY_CLIENT_COLD") == "1"
    action = "run"

    while argv and argv[0].startswith("--client-"):
        opt = argv.pop(0)
        if opt == "--client-cold":
            force_cold = True
        elif opt == "--client-status":
            action = "status"
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

    if action == "stop":
        return stop_server(isabelle, opts["name"])
    if action == "status":
        try:
            return status(isabelle, opts)
        except Fallback as exn:
            sys.stderr.write("query: %s\n" % exn)
            return 2

    first = next((a for a in argv if not a.startswith("-")), None)
    if force_cold or first in COLD_ONLY_COMMANDS or "-" in argv:
        note(opts["verbose"], "cold path")
        cold(isabelle, argv)

    start = time.monotonic()
    try:
        rc = warm(isabelle, argv, opts)
        note(opts["verbose"], "warm, %.1f ms" % ((time.monotonic() - start) * 1000))
        return rc
    except Fallback as exn:
        note(opts["verbose"], "falling back: %s" % exn)
        cold(isabelle, argv)
    except socket.timeout:
        # Falling back would repeat work that has already run longer than the
        # caller allowed; say so instead of doubling the wait.
        sys.stderr.write(
            "query: no answer within %ss -- raise --client-timeout, "
            "or use --client-cold\n" % opts["timeout"]
        )
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except BrokenPipeError:
        # Match the tool: a closed stdout is the shell's 141, not an error.
        os._exit(141)
