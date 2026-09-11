#!/usr/bin/env python3
"""Bounded, isolated real host/client checks; invoked by p12integration.sh."""
import importlib.util
import json
import os
from pathlib import Path
import selectors
import signal
import socket
import sqlite3
import subprocess
import tempfile
import time

OUT = Path(os.environ["P12_INTEGRATION_OUT"])
DB = Path(os.environ["P12_HOME_USER"]) / "servers.db"
FIX = OUT / "fixture"
spec = importlib.util.spec_from_file_location("query_client", os.environ["P12_CLIENT"])
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)
checks = 0
host_pids = set()


def check(name, condition):
    global checks
    if not condition:
        raise AssertionError(name)
    checks += 1
    print("  ok  " + name, flush=True)


def rows():
    if not DB.exists():
        return []
    try:
        with sqlite3.connect(DB.as_uri() + "?mode=ro", uri=True, timeout=0.2) as db:
            return db.execute("SELECT name, port, password FROM isabelle_servers").fetchall()
    except sqlite3.OperationalError:
        return []


def metadata(row):
    conn = client.Connection(int(row[1]), row[2], 5)
    try:
        head, value = conn.command("query_version", {"open": True})
        assert head == "OK", "metadata request failed"
        return value
    finally:
        conn.close()


def terminal_result(*args, server=None):
    env = os.environ.copy()
    if server is not None:
        env["ISABELLE_QUERY_CLIENT_SERVER"] = server
    result = subprocess.run(["isabelle", "query", *args], text=True,
                            capture_output=True, timeout=20, env=env)
    with (OUT / "terminal.log").open("a") as log:
        log.write(json.dumps({"args": args, "server": server, "exit": result.returncode,
                              "stdout": result.stdout, "stderr": result.stderr}) + "\n")
    return result


def terminal(*args, server=None):
    result = terminal_result(*args, server=server)
    assert result.returncode == 0, "terminal invocation failed; see terminal.log"
    return result.stdout


def endpoint_closed(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.2):
            return False
    except ConnectionRefusedError:
        return True


def process_running(pid):
    try:
        return Path("/proc/" + str(pid) + "/stat").read_text().split(")", 1)[1].split()[0] != "Z"
    except FileNotFoundError:
        return False


def await_condition(condition, seconds=10):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.05)
    return False


def await_host(proc, kind):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        assert proc.poll() is None, "host exited before advertisement"
        for row in rows():
            if row[0].startswith("isabelle_query_host_" + kind + "_"):
                try:
                    value = metadata(row)
                except (OSError, client.Fallback):
                    continue
                return row, value
        time.sleep(0.05)
    raise AssertionError("host advertisement timed out")


def mcp(proc, request):
    proc.stdin.write((json.dumps(request) + "\n").encode())
    proc.stdin.flush()
    line = b""
    deadline = time.monotonic() + 10
    with selectors.DefaultSelector() as selector:
        selector.register(proc.stdout, selectors.EVENT_READ)
        while not line.endswith(b"\n"):
            remaining = deadline - time.monotonic()
            assert remaining > 0 and selector.select(remaining), "MCP response timed out"
            block = os.read(proc.stdout.fileno(), 65536)
            assert block, "MCP stdout closed before response"
            line += block
    with (OUT / "mcp-responses.log").open("ab") as log:
        log.write(line)
    value = json.loads(line)
    assert value.get("id") == request["id"], "MCP stdout contamination or wrong response"
    assert "error" not in value, "MCP request failed"
    return value["result"]


def exercise(kind, command):
    stdout_log = OUT / (kind + "-stdout.log")
    with (OUT / (kind + "-stderr.log")).open("wb") as stderr:
        proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=stderr, start_new_session=True)
        try:
            row, first = await_host(proc, kind)
            pid = first["host_pid"]
            host_pids.add(pid)
            check(kind + " advertises its own JVM", first["host_kind"] == kind and
                  first["host_name"] == row[0] and os.getpgid(pid) == proc.pid)
            print("  host " + kind + " pid=" + str(pid), flush=True)
            if kind == "pide":
                result = mcp(proc, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                  "params": {"protocolVersion": "2025-03-26",
                                             "capabilities": {},
                                             "clientInfo": {"name": "p12-probe", "version": "1"}}})
                check("MCP initialize stdout is clean JSON", "serverInfo" in result)
                listing = mcp(proc, {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                    "params": {"name": "list_sessions", "arguments": {}}})
                check("MCP serves requests without a prover", not listing.get("isError", False)
                      and listing["structuredContent"] ==
                      {"starting": [], "running": [], "stopping": []})

            status = terminal("--client-status")
            check(kind + " terminal discovers the advertised PID", str(pid) in status and row[0] in status)
            query = ("-R", str(FIX), "find", "alpha", "-c")
            check(kind + " terminal query succeeds", terminal(*query) == "1\n")
            one = metadata(row)
            check(kind + " terminal populated this host's index", len(one["open"]) == 1)
            check(kind + " repeated terminal query succeeds", terminal(*query) == "1\n")
            two = metadata(row)
            check(kind + " repeat retains index and advances use count",
                  two["host_pid"] == pid and one["open"][0]["index_id"] == two["open"][0]["index_id"]
                  and two["open"][0]["uses"] > one["open"][0]["uses"]
                  and two["open"][0]["reparsed"] == 0)

            if kind == "pide":
                reply = mcp(proc, {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                   "params": {"name": "query", "arguments": {"argv": list(query)}}})
                check("existing multi-session adapter works in the hosted process",
                      reply["structuredContent"] == {"exit": 0, "stdout": "1\n", "stderr": ""})
                shared = metadata(row)
                check("MCP adapter and terminal share the index",
                      shared["open"][0]["index_id"] == two["open"][0]["index_id"] and
                      shared["open"][0]["uses"] > two["open"][0]["uses"])

            terminal("--client-cache", "off")
            disabled = metadata(row)
            check(kind + " off clears and disables retention", not disabled["retain_indexes"]
                  and not disabled["open"])
            check(kind + " query still works while retention is off", terminal(*query) == "1\n")
            check(kind + " off leaves no retained index", not metadata(row)["open"])
            terminal("--client-cache", "on")
            check(kind + " on restores retention", metadata(row)["retain_indexes"])
            terminal(*query)
            check(kind + " on retains the next index", len(metadata(row)["open"]) == 1)
            terminal("--client-cache", "clear")
            cleared = metadata(row)
            check(kind + " clear drops indexes and preserves policy",
                  cleared["retain_indexes"] and not cleared["open"] and cleared["host_pid"] == pid)
            check(kind + " never started a dedicated fallback", [r[0] for r in rows()] == [row[0]])

            for action in ("--client-stop", "--client-restart"):
                action_args = (action,) if action == "--client-stop" else (action, *query)
                refused = terminal_result(*action_args, server=row[0])
                check(kind + " explicitly targeted " + action + " is refused",
                      refused.returncode == 2 and "refusing" in refused.stderr)
                check(kind + " remains usable after " + action + " refusal",
                      proc.poll() is None and metadata(row)["host_pid"] == pid and
                      terminal(*query, server=row[0]) == "1\n")

            proc.stdin.close()
            proc.stdin = None
            rc = proc.wait(timeout=15)
            tail = proc.stdout.read()
            stdout_log.write_bytes(tail)
            check(kind + " EOF exits successfully", rc == 0)
            check(kind + " no unexpected stdout on shutdown", not tail)
            check(kind + " removes its registry row", row[0] not in [r[0] for r in rows()])
            try:
                with socket.create_connection(("127.0.0.1", row[1]), timeout=1):
                    closed = False
            except OSError:
                closed = True
            check(kind + " closes its listener", closed)
            check(kind + " JVM is gone", not Path("/proc/" + str(pid)).exists())
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=5)
            # Any unexpected dedicated fallback belongs only to this scratch home.
            for row in rows():
                if not row[0].startswith("isabelle_query_host_"):
                    subprocess.run(["isabelle", "server", "-x", "-n", row[0]],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)


# Test-only scheduling hook loaded by Python's site mechanism in the real CLI.
# It pauses before query_run, never replaces transport, responses, or cleanup.
# Every attempt gets an observable host identity, including a reconnect attempt.
PROFILE_GATE = r'''import json, os, sys, time
from pathlib import Path
attempt = 0

def gate(frame, event, arg):
    global attempt
    if (event != "call" or frame.f_code.co_name != "command" or
            frame.f_code.co_filename != os.environ["P12_CLIENT"] or
            frame.f_locals.get("name") != "query_run"):
        return
    attempt += 1
    base = Path(os.environ["P12_GATE"])
    conn = frame.f_locals["self"]
    value = {"client_pid": os.getpid(), "host_pid": conn.version["host_pid"],
             "host_kind": conn.version["host_kind"], "name": conn.name}
    tmp = base / ("ready.%d.tmp" % attempt)
    tmp.write_text(json.dumps(value))
    tmp.rename(base / ("ready.%d" % attempt))
    deadline = time.monotonic() + 60
    while not (base / ("release.%d" % attempt)).exists():
        if time.monotonic() >= deadline:
            raise RuntimeError("integration gate timed out")
        time.sleep(0.02)

if os.environ.get("P12_GATE"):
    sys.setprofile(gate)
'''


def process_identity(pid):
    try:
        fields = Path("/proc/" + str(pid) + "/stat").read_text().rsplit(")", 1)[1].split()
        return None if fields[0] == "Z" else fields[19]
    except FileNotFoundError:
        return None


def gone(row, pid, identity, label):
    check(label + " closes listener", await_condition(lambda: endpoint_closed(row[1])))
    check(label + " removes own row", await_condition(lambda: row not in rows()))
    check(label + " terminates observed JVM", await_condition(
        lambda: process_identity(pid) != identity))


class GatedCLI:
    def __init__(self, label):
        self.base = Path(tempfile.mkdtemp(prefix=label + ".", dir=OUT))
        env = os.environ.copy()
        env["P12_GATE"] = str(self.base)
        env["PYTHONPATH"] = str(OUT / "gate-hook")
        self.proc = subprocess.Popen(
            ["isabelle", "query", "-R", str(FIX), "find", "alpha", "-c"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, start_new_session=True)
        self.pidfd = None

    def ready(self, attempt=1):
        path = self.base / ("ready." + str(attempt))
        check(self.base.name + " reaches real query attempt " + str(attempt),
              await_condition(lambda: path.exists() or self.proc.poll() is not None, 35)
              and path.exists())
        value = json.loads(path.read_text())
        if self.pidfd is None:
            # Pin the actual Python client, not its shell wrapper or a reusable PID.
            self.pidfd = os.pidfd_open(value["client_pid"])
            assert os.getpgid(value["client_pid"]) == self.proc.pid
        row = next(r for r in rows() if r[0] == value["name"])
        assert metadata(row)["host_pid"] == value["host_pid"]
        identity = process_identity(value["host_pid"])
        assert identity is not None
        print("  gated " + self.base.name + " " + json.dumps(value), flush=True)
        return row, value["host_pid"], identity

    def release(self, attempt=1):
        (self.base / ("release." + str(attempt))).touch()

    def finish(self):
        output, error = self.proc.communicate(timeout=25)
        (self.base / "stderr.log").write_bytes(error)
        check(self.base.name + " produces exactly one CLI answer",
              self.proc.returncode == 0 and output == b"1\n")

    def terminate_client(self, sig):
        signal.pidfd_send_signal(self.pidfd, sig)
        output, error = self.proc.communicate(timeout=15)
        (self.base / "stderr.log").write_bytes(error)
        check(self.base.name + " client exits on " + signal.Signals(sig).name,
              self.proc.returncode != 0 and not output)

    def close(self):
        if self.proc.poll() is None:
            if self.pidfd is not None:
                try:
                    signal.pidfd_send_signal(self.pidfd, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            else:
                self.proc.terminate()
            output, error = self.proc.communicate(timeout=15)
            (self.base / "stderr.log").write_bytes(error)
            (self.base / "stdout.log").write_bytes(output)
        if self.pidfd is not None:
            os.close(self.pidfd)


def owned_fallback():
    check("fallback begins after all hosted JVMs exited", not rows() and
          all(not process_running(pid) for pid in host_pids))
    hook = OUT / "gate-hook"
    hook.mkdir(exist_ok=True)
    (hook / "sitecustomize.py").write_text(PROFILE_GATE)
    for label, sig in (("normal-owner", None), ("term-owner", signal.SIGTERM),
                       ("kill-owner", signal.SIGKILL)):
        owner = GatedCLI(label)
        try:
            row, pid, identity = owner.ready()
            check(label + " owns a dedicated JVM", metadata(row)["host_kind"] == "server"
                  and pid not in host_pids and len(rows()) == 1)
            check(label + " uses a unique automatic fallback name",
                  row[0].startswith("isabelle_query_fallback_"))
            if sig is None:
                owner.release()
                owner.finish()
            else:
                owner.terminate_client(sig)
            gone(row, pid, identity, label)
        finally:
            owner.close()

    owner = GatedCLI("borrowed-owner")
    borrower = None
    try:
        row, pid, identity = owner.ready()
        for action in ("--client-stop", "--client-restart"):
            args = (action,) if action == "--client-stop" else (
                action, "-R", str(FIX), "find", "alpha", "-c")
            result = terminal_result(*args)
            check("unnamed " + action + " preserves another client's fallback",
                  result.returncode == 0 and
                  (action == "--client-stop" or result.stdout == "1\n") and
                  process_identity(pid) == identity and
                  metadata(row)["host_pid"] == pid and rows() == [row])
        # Ordinary borrower completion must not acquire ownership of this instance.
        check("borrower CLI succeeds on owner's server", terminal(
            "-R", str(FIX), "find", "alpha", "-c") == "1\n")
        check("borrower exit preserves owner JVM and listener",
              process_identity(pid) == identity and metadata(row)["host_pid"] == pid)
        borrower = GatedCLI("recovering-borrower")
        attached, attached_pid, _ = borrower.ready()
        check("borrower attaches to owner's existing instance", attached == row and attached_pid == pid)
        owner.release()
        owner.finish()
        gone(row, pid, identity, "owner exit with attached borrower")
        borrower.release()
        replacement, new_pid, new_identity = borrower.ready(2)
        check("borrower reconnects to a new dedicated JVM", new_pid != pid and
              metadata(replacement)["host_kind"] == "server")
        borrower.release(2)
        borrower.finish()
        gone(replacement, new_pid, new_identity, "recovered borrower exit")
        check("borrower recovery leaves private registry empty", not rows())
    finally:
        if borrower is not None:
            borrower.close()
        owner.close()


def manual_dedicated():
    query = ("-R", str(FIX), "find", "alpha", "-c")
    manual = None
    unrelated = None
    dedicated = None
    stderr = (OUT / "unrelated-stderr.log").open("wb")
    stdout = (OUT / "unrelated-stdout.log").open("wb")
    try:
        # The harness owns this pipe; attaching CLI clients must never close it.
        manual = subprocess.Popen(["isabelle", "query_server", "-n", "isabelle_query"],
                                  stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                  start_new_session=True)
        check("manual dedicated server advertises", await_condition(
            lambda: any(r[0] == "isabelle_query" for r in rows()), seconds=30))
        candidates = rows()
        check("manual server owns exactly the default dedicated row",
              len(candidates) == 1 and candidates[0][0] == "isabelle_query")
        dedicated = candidates[0]
        one = metadata(dedicated)
        pid = one["host_pid"]
        check("manual dedicated identity is a different server JVM", one["host_kind"] == "server"
              and pid not in host_pids and process_running(pid))
        print("  dedicated pid=" + str(pid), flush=True)
        check("terminal status selects the dedicated PID", str(pid) in terminal("--client-status"))
        check("CLI reuses manually started dedicated server", terminal(*query) == "1\n")
        one = metadata(dedicated)
        check("manual dedicated survives CLI exit with populated index",
              manual.poll() is None and one["host_pid"] == pid and len(one["open"]) == 1)
        check("dedicated repeat query succeeds", terminal(*query) == "1\n")
        two = metadata(dedicated)
        check("dedicated repeat reuses the PID and index", two["host_pid"] == pid and
              one["open"][0]["index_id"] == two["open"][0]["index_id"] and
              two["open"][0]["uses"] > one["open"][0]["uses"] and
              two["open"][0]["reparsed"] == 0)

        # A separate, disposable shared host proves --client-stop is endpoint-local.
        unrelated = subprocess.Popen(["isabelle", "pide_mcp"], stdin=subprocess.PIPE,
                                     stdout=stdout, stderr=stderr, start_new_session=True)
        unrelated_row, unrelated_info = await_host(unrelated, "pide")
        unrelated_pid = unrelated_info["host_pid"]
        check("unrelated shared host is separately alive", unrelated_pid != pid and
              os.getpgid(unrelated_pid) == unrelated.pid)
        terminal("--client-stop", server=dedicated[0])
        check("explicit client-stop closes the dedicated endpoint",
              await_condition(lambda: endpoint_closed(dedicated[1])))
        check("client-stop terminates the dedicated JVM",
              await_condition(lambda: not process_running(pid)))
        check("client-stop preserves the unrelated host identity and listener",
              unrelated.poll() is None and metadata(unrelated_row)["host_pid"] == unrelated_pid)
        check("unrelated host still serves terminal queries after dedicated stop",
              terminal(*query, server=unrelated_row[0]) == "1\n" and
              metadata(unrelated_row)["host_pid"] == unrelated_pid)
        unrelated.stdin.close()
        unrelated.stdin = None
        check("unrelated host exits normally on its own EOF", unrelated.wait(timeout=15) == 0)
        check("unrelated host removes only its own endpoint",
              unrelated_row[0] not in [r[0] for r in rows()] and endpoint_closed(unrelated_row[1]))
    finally:
        if unrelated is not None and unrelated.poll() is None:
            os.killpg(unrelated.pid, signal.SIGTERM)
            try:
                unrelated.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(unrelated.pid, signal.SIGKILL)
                unrelated.wait(timeout=5)
        if dedicated is not None and not endpoint_closed(dedicated[1]):
            # Exact explicit name in this private registry, never a discovered host.
            terminal("--client-stop", server=dedicated[0])

        if manual is not None:
            manual.stdin.close()
            manual.stdin = None
            try:
                manual.wait(timeout=10)
            except subprocess.TimeoutExpired:
                manual.terminate()
                manual.wait(timeout=5)
        stdout.close()
        stderr.close()


def main():
    # Never inherit routing switches from the caller into this isolated check.
    for name in ("ISABELLE_QUERY_CLIENT_SERVER", "ISABELLE_QUERY_CLIENT_COLD",
                 "ISABELLE_QUERY_NO_CLIENT", "ISABELLE_QUERY_NO_SERVER", "ISABELLE_QUERY_HOST"):
        os.environ.pop(name, None)
    exercise("pide", ["isabelle", "pide_mcp", "-L", str(OUT / "pide log.txt"), "-w"])
    check("upstream log option preserves a path containing spaces", (OUT / "pide log.txt").is_file())
    exercise("jedit", ["isabelle", "java", "-Djava.awt.headless=true",
                       "isabelle.query.integration.P12_JEdit_Probe"])
    for args in (("-?",), ("--p12-invalid-option",)):
        result = subprocess.run(["isabelle", "pide_mcp", *args], input=b"",
                                capture_output=True, timeout=20)
        check("upstream usage/error status is preserved: " + args[0], result.returncode != 0
              and b"Usage: isabelle pide_mcp" in result.stdout)
        check("usage/error leaves no host registry row: " + args[0], not rows())
    owned_fallback()
    manual_dedicated()
    print("P12 INTEGRATION OK: " + str(checks) + " checks", flush=True)


if __name__ == "__main__":
    main()
