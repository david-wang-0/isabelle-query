"""P12 Python transport units: private registry/fakes, never the oracle matrix."""
import contextlib
import importlib.util
import io
import os
import select
import signal
import sys
import threading
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "p12_client", ROOT / "query_base/lib/scripts/query_client.py")
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
JEDIT = c.HOST_PREFIX + "jedit_10_a"
PIDE = c.HOST_PREFIX + "pide_20_a"


def version(name=c.DEFAULT_SERVER, stamp="stamp", kind=None):
    return dict(protocol=1, component_id=stamp, host_name=name,
                host_kind=kind or ("jedit" if "jedit" in name else
                                   "pide" if "pide" in name else "server"),
                host_pid=123, retain_indexes=True)


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.dict(os.environ, {}, clear=True))
        self.stack.enter_context(patch.object(c, "jar_stamp", return_value="stamp"))
        self.rows = []
        self.registry = self.stack.enter_context(patch.object(
            c, "registry_entries", side_effect=lambda i, cache, name=None:
            [r for r in self.rows if name is None or r[0] == name]))
        self.start = self.stack.enter_context(patch.object(c, "start_server",
                                                         return_value=(999, "secret")))
        self.stop = Mock()
        self.connections = []
        self.replies = {}
        self.factory = self.stack.enter_context(patch.object(c, "Connection", side_effect=self.connection))

    def connection(self, port, password, timeout, deadline=None):
        reply = self.replies.get(port, version())
        if isinstance(reply, Exception):
            raise reply
        conn = Mock(timeout=timeout, deadline=deadline)
        def command(command_name, argument=None):
            if command_name == "shutdown":
                self.stop(port)
                return "OK", {}
            return "OK", reply
        conn.command.side_effect = command
        self.connections.append(conn)
        return conn

    def add(self, name, reply=None):
        port = len(self.rows) + 1
        self.rows.append((name, port, "secret"))
        self.replies[port] = version(name) if reply is None else reply

    def connect(self, restart=False, timeout=19):
        return c.connect("isabelle", {}, os.environ.get("ISABELLE_QUERY_CLIENT_SERVER",
                         c.DEFAULT_SERVER), restart, timeout, False)

    def opts(self):
        return dict(cached={}, name=c.DEFAULT_SERVER, restart=False,
                    timeout=19, verbose=False, limit=None)

    def main(self, args):
        with patch.object(c, "read_cache", return_value={}), patch.object(
                c, "find_isabelle", return_value="isabelle"), contextlib.redirect_stdout(
                    io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
            rc = c.main(args)
        return rc, out.getvalue(), err.getvalue()

    def test_prefer_jedit_then_deterministic_name(self):
        self.add(PIDE)
        self.add(JEDIT + "z")
        self.add(c.DEFAULT_SERVER)
        self.add(JEDIT)
        conn = self.connect()
        self.assertEqual(conn.name, JEDIT)
        conn.command.assert_called_once_with("query_version")
        self.assertEqual(conn.timeout, 19)
        self.assertIsNone(conn.deadline)
        self.start.assert_not_called()

    def test_skip_incompatible_dead_malformed_and_wrong_auth(self):
        self.add(JEDIT + "1", version(JEDIT + "1", stamp="stale"))
        self.add(JEDIT + "2", c.Fallback("bad password"))
        self.add(JEDIT + "3", ConnectionRefusedError())
        self.add(JEDIT + "4", [])
        self.add(JEDIT + "5", dict(version(JEDIT + "5"), protocol=2))
        self.add(PIDE)
        conn = self.connect()
        self.assertEqual(conn.name, PIDE)
        for failed in self.connections[:-1]:
            failed.close.assert_called_once()
        self.stop.assert_not_called()
        self.start.assert_not_called()

    def test_private_other_compatible_server_is_ignored(self):
        self.add("another")
        self.add(c.DEFAULT_SERVER)
        self.assertEqual(self.connect().name, c.DEFAULT_SERVER)
        self.rows = self.rows[:1]
        self.assertTrue(self.connect().name.startswith(c.FALLBACK_PREFIX))
        self.start.assert_called_once()
        self.assertNotIn(1, [call.args[0] for call in self.factory.call_args_list])

    def test_explicit_selection_does_not_probe_other_hosts(self):
        self.add(JEDIT)
        self.add("private")
        os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = "private"
        self.assertEqual(self.connect().name, "private")
        self.registry.assert_called_once_with("isabelle", {}, "private")
        self.assertEqual(self.factory.call_count, 1)

    def test_absent_explicit_starts_only_selected(self):
        self.add(JEDIT)
        os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = "private"
        self.connect()
        self.start.assert_called_once_with("isabelle", "private")

    def test_shared_stop_restart_refused_by_name_and_metadata(self):
        for name in (JEDIT, c.DEFAULT_SERVER):
            with self.subTest(name=name):
                self.rows = []
                self.add(name, version(name, stamp="stale", kind="jedit"))
                os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = name
                for action in ("--client-stop", "--client-restart"):
                    rc, out, err = self.main([action, "summary"])
                    self.assertEqual(rc, 2)
                    self.assertEqual(out, "")
                    self.assertIn("refusing", err)
        self.stop.assert_not_called()
        self.start.assert_not_called()

    def test_busy_default_gets_managed_fallback_without_restart(self):
        self.add(c.DEFAULT_SERVER, c.socket.timeout())
        conn = self.connect()
        self.assertTrue(conn.name.startswith(c.FALLBACK_PREFIX))
        self.stop.assert_not_called()
        self.start.assert_called_once()

    def test_busy_explicit_selection_remains_exclusive(self):
        os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = c.DEFAULT_SERVER
        self.add(c.DEFAULT_SERVER, c.socket.timeout())
        self.add(c.FALLBACK_PREFIX + "existing")
        with self.assertRaises(c.Fallback):
            self.connect()
        self.stop.assert_not_called()
        self.start.assert_not_called()

    def test_managed_fallback_is_reused_when_default_unverifiable(self):
        self.add(c.DEFAULT_SERVER, c.Fallback("wrong password"))
        self.add(c.FALLBACK_PREFIX + "existing")
        self.add("private")
        self.assertEqual(self.connect().name, c.FALLBACK_PREFIX + "existing")
        self.start.assert_not_called()
        self.stop.assert_not_called()

    def test_dead_default_stop_and_restart(self):
        self.add(c.DEFAULT_SERVER, ConnectionRefusedError())
        self.assertEqual(self.main(["--client-stop"])[0:2], (0, ""))
        conn = self.connect(restart=True)
        self.assertEqual(conn.name, c.DEFAULT_SERVER)
        self.assertTrue(conn.restarted)
        self.start.assert_called_once_with("isabelle", c.DEFAULT_SERVER)
        self.stop.assert_not_called()

    def test_unnamed_controls_exclude_borrowed_fallback_and_embedded(self):
        self.add(JEDIT)
        self.add(c.FALLBACK_PREFIX + "existing")
        self.assertEqual(self.main(["--client-stop"])[0], 0)
        self.factory.assert_not_called()
        conn = self.connect(restart=True)
        self.assertEqual(conn.name, c.DEFAULT_SERVER)
        self.stop.assert_not_called()
        self.start.assert_called_once_with("isabelle", c.DEFAULT_SERVER)

    def test_unnamed_controls_select_manual_default_only(self):
        self.add(JEDIT)
        self.add(c.DEFAULT_SERVER)
        self.add(c.FALLBACK_PREFIX + "existing")
        self.assertEqual(self.main(["--client-stop"])[0], 0)
        self.stop.assert_called_once_with(2)
        self.stop.reset_mock()
        conn = self.connect(restart=True)
        self.assertEqual(conn.name, c.DEFAULT_SERVER)
        self.stop.assert_called_once_with(2)
        self.start.assert_called_once_with("isabelle", c.DEFAULT_SERVER)

    def test_explicit_controls_can_select_borrowed_dedicated(self):
        name = c.FALLBACK_PREFIX + "existing"
        self.add(name)
        os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = name
        self.assertEqual(self.main(["--client-stop"])[0], 0)
        self.stop.assert_called_once_with(1)
        self.stop.reset_mock()
        self.assertEqual(self.connect(restart=True).name, name)
        self.stop.assert_called_once_with(1)
        self.start.assert_called_once_with("isabelle", name)

    def test_unverifiable_unnamed_restart_never_stops_borrowed_fallback(self):
        self.add(c.DEFAULT_SERVER, c.socket.timeout())
        self.add(c.FALLBACK_PREFIX + "existing")
        self.assertEqual(self.main(["--client-restart", "summary"])[0], 2)
        self.stop.assert_not_called()
        self.start.assert_not_called()

    def test_incompatible_borrowed_fallback_is_skipped_without_shutdown(self):
        self.add(c.FALLBACK_PREFIX + "existing", version(stamp="old"))
        conn = self.connect()
        self.assertNotEqual(conn.name, self.rows[0][0])
        self.stop.assert_not_called()

    def test_explicit_restart_failure_is_diagnostic_not_cold(self):
        os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = c.DEFAULT_SERVER
        self.add(c.DEFAULT_SERVER, c.socket.timeout())
        rc, out, err = self.main(["--client-restart", "summary"])
        self.assertEqual((rc, out), (2, ""))
        self.assertTrue(err)
        self.start.assert_not_called()

    def test_stale_dedicated_survives_automatic_fallback(self):
        self.add(c.DEFAULT_SERVER, version(stamp="old"))
        conn = self.connect()
        self.assertTrue(conn.name.startswith(c.FALLBACK_PREFIX))
        self.stop.assert_not_called()
        self.start.assert_called_once()
        os.environ["ISABELLE_QUERY_CLIENT_SERVER"] = c.DEFAULT_SERVER
        with self.assertRaises(c.Fallback):
            self.connect()
        self.stop.assert_not_called()

    def test_discovery_budget_reserves_dedicated_probe(self):
        for i in range(30):
            self.add(JEDIT + str(i))
        self.add(c.DEFAULT_SERVER)
        now = [0.0]
        attempts = []
        def probe(name, info, deadline):
            attempts.append(name)
            now[0] = deadline
            if name != c.DEFAULT_SERVER:
                raise c.socket.timeout()
            return Mock(name=name, version=version())
        with patch.object(c.time, "monotonic", side_effect=lambda: now[0]), patch.object(
                c, "probe", side_effect=probe):
            conn = self.connect(timeout=0.001)
        self.assertLessEqual(now[0], c.DISCOVERY_TIMEOUT)
        self.assertLessEqual(len(attempts), 8)
        self.assertEqual(attempts[-1], c.DEFAULT_SERVER)
        self.assertEqual(conn.timeout, 0.001)
        self.start.assert_not_called()

    def test_cache_controls_and_actual_status(self):
        self.add(JEDIT)
        for mode in ("on", "off", "clear"):
            rc, out, _ = self.main(["--client-cache", mode])
            self.assertEqual(rc, 0)
            self.assertIn(JEDIT, out)
            self.assertIn("retain_indexes", out)
            self.connections[-1].command.assert_called_with(
                "query_cache", {"mode": mode, "client_id": "stamp"})
        rc, out, _ = self.main(["--client-status"])
        self.assertEqual(rc, 0)
        self.assertIn(JEDIT, out)
        self.assertIn("123", out)
        self.connections[-1].command.assert_called_with("query_version", {"open": True})

    def test_invalid_cache_before_network(self):
        for args in (["--client-cache"], ["--client-cache", "bad"]):
            self.assertEqual(self.main(args)[0], 2)
        self.registry.assert_not_called()
        self.start.assert_not_called()

    def test_output_validated_before_any_stdout_and_timeout_not_replayed(self):
        conn = Mock(name=c.DEFAULT_SERVER, version=version(), restarted=True)
        conn.name = c.DEFAULT_SERVER
        for reply in (("OK", dict(output="once", error="", exit="bad")),
                      ("OK", dict(output="once", error=3, exit=0)),
                      ("OK", dict(output="once", error="", exit=97))):
            conn.command.return_value = reply
            with patch.object(c, "connect", return_value=conn):
                rc, out, _ = self.main(["summary"])
            self.assertEqual(rc, 97)
            self.assertEqual(out, "")
        conn.command.side_effect = c.socket.timeout()
        with patch.object(c, "connect", return_value=conn) as connect:
            rc, out, _ = self.main(["summary"])
        self.assertEqual(rc, 2)
        self.assertEqual(out, "")
        connect.assert_called_once()

    def test_refusal_and_shared_stale_reply_no_restart(self):
        conn = Mock(version=version(JEDIT), restarted=False)
        conn.name = JEDIT
        for message, expected in (("too large for a resident index", 2),
                                  ("stale query server", 2)):
            conn.command.return_value = ("ERROR", {"message": message})
            with patch.object(c, "connect", return_value=conn) as connect:
                rc, out, _ = self.main(["summary"])
            self.assertEqual((rc, out), (expected, ""))
            connect.assert_called_once()
        self.stop.assert_not_called()

    def test_request_preserves_cwd_env_and_roots(self):
        os.environ["ISABELLE_QUERY_ROOT"] = "relative"
        body = c.request(["-R", "project", "find", "x"], 42, False)
        self.assertEqual(body["argv"], ["-R", os.path.abspath("project"), "find", "x"])
        self.assertEqual(body["cwd"], os.getcwd())
        self.assertEqual(body["env_root"], "relative")
        self.assertEqual(body["env"], {"ISABELLE_QUERY_ROOT": "relative"})
        self.assertEqual(body["limit"], 42)

    def test_missing_local_stamp_does_not_query_or_mutate(self):
        self.add(JEDIT)
        with patch.object(c, "jar_stamp", return_value=""), self.assertRaises(c.Fallback):
            self.connect()
        self.factory.assert_not_called()
        self.start.assert_not_called()
        self.stop.assert_not_called()

    def test_stop_uses_checked_socket_without_second_registry_lookup(self):
        self.add(c.DEFAULT_SERVER)
        self.assertEqual(c.stop_server("isabelle", c.DEFAULT_SERVER, {}), 0)
        self.registry.assert_called_once()
        self.assertEqual(self.connections[0].command.call_args_list[-1].args, ("shutdown",))
        self.connections[0].close.assert_called_once()
        self.start.assert_not_called()

    def test_cold_only_does_not_discover(self):
        for args in (["--client-cold", "summary"], ["dump-entries"], ["summary", "-"]):
            self.assertEqual(self.main(args)[0], 97)
        self.registry.assert_not_called()

    def test_success_bytes_and_output_failure_never_replay(self):
        conn = Mock(version=version(), restarted=True)
        conn.name = c.DEFAULT_SERVER
        conn.command.return_value = ("OK", dict(output="once\n", error="diagnostic\n", exit=1))
        with patch.object(c, "connect", return_value=conn):
            self.assertEqual(self.main(["summary"]), (1, "once\n", "diagnostic\n"))
            stream = Mock()
            stream.write.side_effect = OSError("partial write")
            with patch.object(c.sys, "stdout", stream):
                self.assertEqual(c.warm("isabelle", ["summary"], self.opts()), 2)
            stream.write.side_effect = BrokenPipeError()
            with patch.object(c.sys, "stdout", stream):
                self.assertEqual(c.warm("isabelle", ["summary"], self.opts()), 141)

    def test_disconnect_rediscovers_once_with_fresh_root_request(self):
        first, second = Mock(), Mock()
        first.command.side_effect = ConnectionResetError("owner exited")
        second.command.return_value = ("OK", dict(output="once\n", exit=0))
        with patch.object(c, "connect", side_effect=[first, second]) as connect:
            rc, out, err = self.main(["-R", "project", "summary"])
        self.assertEqual((rc, out, err), (0, "once\n", ""))
        self.assertEqual(connect.call_count, 2)
        first.close.assert_called_once()
        second.close.assert_called_once()
        self.assertEqual(first.command.call_args, second.command.call_args)
        self.assertEqual(second.command.call_args.args[1]["argv"],
                         ["-R", os.path.abspath("project"), "summary"])
        self.stop.assert_not_called()

    def test_second_disconnect_declines_once(self):
        conns = [Mock(), Mock()]
        for conn in conns:
            conn.command.side_effect = BrokenPipeError("gone")
        with patch.object(c, "connect", side_effect=conns) as connect:
            self.assertEqual(self.main(["summary"])[:2], (97, ""))
        self.assertEqual(connect.call_count, 2)
        for conn in conns:
            conn.close.assert_called_once()

    def test_logical_errors_never_reconnect_or_run_cold(self):
        conn = Mock()
        for message in ("stale query server", "refused", "unknown command"):
            conn.command.return_value = ("ERROR", {"message": message})
            with patch.object(c, "connect", return_value=conn) as connect:
                self.assertEqual(self.main(["summary"])[:2], (2, ""))
            connect.assert_called_once()
        self.stop.assert_not_called()

    def test_partial_output_never_reconnects(self):
        conn = Mock()
        conn.command.return_value = ("OK", dict(output="once", exit=0))
        stream = Mock()
        stream.write.side_effect = BrokenPipeError("closed")
        with patch.object(c, "connect", return_value=conn) as connect, patch.object(
                c.sys, "stdout", stream):
            self.assertEqual(c.warm("isabelle", ["summary"], self.opts()), 141)
        connect.assert_called_once()

    def test_logical_error_output_failure_never_replays(self):
        conn = Mock()
        conn.command.return_value = ("ERROR", {"message": "refused"})
        stream = Mock()
        stream.write.side_effect = OSError("partial diagnostic")
        with patch.object(c, "connect", return_value=conn) as connect, patch.object(
                c.sys, "stderr", stream):
            self.assertEqual(c.warm("isabelle", ["summary"], self.opts()), 2)
        connect.assert_called_once()

    def test_main_cleans_only_its_launches_on_success_and_error(self):
        previous = Mock()
        c._owned_launches.append(previous)
        try:
            for failure in (None, ValueError("bad"), SystemExit(143)):
                owned = Mock()
                def run(args):
                    c._owned_launches.append(owned)
                    if failure is not None:
                        raise failure
                    return 1
                with patch.object(c, "_main", side_effect=run):
                    if failure is None:
                        self.assertEqual(c.main([]), 1)
                    else:
                        with self.assertRaises(type(failure)):
                            c.main([])
                owned.stdin.close.assert_called_once()
                owned.wait.assert_called_once()
            previous.stdin.close.assert_not_called()
        finally:
            c._owned_launches.remove(previous)

    def test_post_start_identity_failure_cleans_owned_child(self):
        for failure in (c.Fallback("wrong identity"), ConnectionResetError("gone")):
            owned = Mock()
            def start(*args):
                c._owned_launches.append(owned)
                return 999, "secret"
            with patch.object(c, "start_server", side_effect=start), patch.object(
                    c, "probe", side_effect=failure), self.assertRaises(type(failure)):
                c.start_checked("isabelle", "private", 1, False, False)
            owned.stdin.close.assert_called_once()
            owned.wait.assert_called_once()



class WireAndRegistryTests(unittest.TestCase):
    def test_startup_read_deadline_and_owned_child_cleanup(self):
        read_fd, write_fd = os.pipe()
        proc = Mock(stdout=os.fdopen(read_fd, "rb"))
        proc.poll.return_value = None
        try:
            with patch("subprocess.Popen", return_value=proc), patch.object(
                    c.os, "killpg") as killpg, patch.object(c, "START_TIMEOUT", 0.02):
                start = c.time.monotonic()
                with self.assertRaisesRegex(c.Fallback, "timed out"):
                    c.start_server("private-launcher", "private")
                self.assertLess(c.time.monotonic() - start, 0.5)
            killpg.assert_called_once_with(proc.pid, signal.SIGKILL)
            proc.wait.assert_called_once_with(timeout=1)
            self.assertTrue(proc.stdout.closed)
        finally:
            os.close(write_fd)

    @unittest.skipUnless(hasattr(os, "pidfd_open"), "requires Linux pidfds")
    def test_failed_startup_kills_launcher_and_term_resistant_child(self):
        # A real private process tree, with no Isabelle settings or registry.
        child_code = (
            "import os, signal, sys\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            "with open(sys.argv[1], 'w') as f: f.write(str(os.getpid()))\n"
            "os.write(1, b'invalid greeting\\n')\n"
            "signal.pause()\n"
        )
        real_popen = subprocess.Popen
        launched = []
        def launch(*args, **kwargs):
            proc = real_popen(*args, **kwargs)
            launched.append(proc)
            return proc
        with tempfile.TemporaryDirectory() as home:
            launcher = Path(home) / "launcher"
            pid_file = Path(home) / "child.pid"
            launcher.write_text(
                "#!" + sys.executable + "\nimport subprocess, sys\n"
                "subprocess.Popen([sys.executable, '-c', " + repr(child_code) +
                ", sys.argv[-1]]).wait()\n")
            launcher.chmod(0o700)
            try:
                with patch("subprocess.Popen", side_effect=launch), patch.object(c, "START_TIMEOUT", 2):
                    started = c.time.monotonic()
                    with self.assertRaisesRegex(c.Fallback, "unparsable"):
                        c.start_server(str(launcher), str(pid_file))
                    self.assertLess(c.time.monotonic() - started, 3)
                self.assertEqual(launched[0].returncode, -signal.SIGKILL)
                self.assertTrue(launched[0].stdout.closed)
                child_pid = int(pid_file.read_text())
                try:
                    child_fd = os.pidfd_open(child_pid)
                except ProcessLookupError:
                    pass  # Already reaped by its new parent.
                else:
                    try:
                        self.assertTrue(select.select([child_fd], [], [], 1)[0],
                                        "launcher child survived failed startup cleanup")
                    finally:
                        os.close(child_fd)
            finally:
                # Also make a failing regression leave no live fixture child.
                for proc in launched:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    proc.wait(timeout=1)

    def test_startup_greeting_retains_owned_pipe_until_cleanup(self):
        read_fd, write_fd = os.pipe()
        proc = Mock(stdout=os.fdopen(read_fd, "rb"))
        os.write(write_fd, b'server "private" = 127.0.0.1:12345 (password "secret")\n')
        os.close(write_fd)
        with patch("subprocess.Popen", return_value=proc) as popen:
            self.assertEqual(c.start_server("private-launcher", "private"), (12345, "secret"))
        self.assertEqual(popen.call_args.kwargs["cwd"], "/")
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        proc.terminate.assert_not_called()
        self.assertTrue(proc.stdout.closed)
        self.assertEqual(popen.call_args.args[0][1], "query_server")
        self.assertEqual(popen.call_args.kwargs["stdin"], subprocess.PIPE)
        proc.stdin.close.assert_not_called()
        c.close_owned_since(0)
        proc.stdin.close.assert_called_once()
        proc.wait.assert_called_once_with(timeout=c.SHUTDOWN_TIMEOUT)

    def test_owned_cleanup_kills_group_before_reaping_after_timeout(self):
        proc = Mock()
        proc.wait.side_effect = [subprocess.TimeoutExpired("launcher", 1), 0]
        c._owned_launches.append(proc)
        with patch.object(c.os, "killpg") as killpg:
            c.close_owned_since(0)
        killpg.assert_called_once_with(proc.pid, signal.SIGKILL)
        self.assertEqual(proc.wait.call_count, 2)
        proc.stdin.close.assert_called_once()

    def test_trickling_probe_uses_absolute_deadline(self):
        sock = Mock()
        now = [0.0]
        def recv(n):
            now[0] += 0.1
            return b"O"
        sock.recv.side_effect = recv
        with patch.object(c.socket, "create_connection", return_value=sock), patch.object(
                c.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaises(c.socket.timeout):
                c.Connection(1, "secret", 20, deadline=0.25)
        self.assertLessEqual(sock.recv.call_count, 3)
        sock.close.assert_called_once()

    def test_private_socket_wrong_listener_fallback_reused(self):
        # Only two private loopback listeners, and an injected registry.
        listeners = []
        accepted = []
        workers = []
        stop = threading.Event()
        rows = []
        commands = []
        def listener(handler):
            sock = c.socket.socket()
            sock.bind(("127.0.0.1", 0))
            sock.listen()
            sock.settimeout(0.05)
            listeners.append(sock)
            def serve():
                while not stop.is_set():
                    try:
                        conn, _ = sock.accept()
                    except c.socket.timeout:
                        continue
                    except OSError:
                        break
                    accepted.append(conn)
                    handler(conn)
            worker = threading.Thread(target=serve, daemon=True)
            workers.append(worker)
            worker.start()
            return sock.getsockname()[1]
        def wrong(conn):
            # Accept authentication bytes, but never greet.
            pass
        def good(conn):
            conn.settimeout(1)
            try:
                with conn.makefile("rb") as stream:
                    self.assertEqual(stream.readline(), b"secret\n")
                    conn.sendall(b"OK {}\n")
                    command = stream.readline().decode().strip()
                    commands.append(command)
                    conn.sendall(("OK " + c.json.dumps(version()) + "\n").encode())
            except OSError:
                pass
        try:
            wrong_port = listener(wrong)
            good_port = listener(good)
            rows.append((c.DEFAULT_SERVER, wrong_port, "wrong"))
            def registry(i, cache, name=None):
                return [r for r in rows if name is None or r[0] == name]
            def start(i, name):
                rows.append((name, good_port, "secret"))
                return good_port, "secret"
            with patch.dict(os.environ, {}, clear=True), patch.object(c, "jar_stamp", return_value="stamp"), patch.object(
                    c, "registry_entries", side_effect=registry), patch.object(c, "start_server", side_effect=start) as launch:
                started = c.time.monotonic()
                first = c.connect("unused", {}, c.DEFAULT_SERVER, False, 7, False)
                name = first.name
                first.close()
                second = c.connect("unused", {}, c.DEFAULT_SERVER, False, 7, False)
                self.assertEqual(second.name, name)
                self.assertTrue(name.startswith(c.FALLBACK_PREFIX))
                self.assertEqual(second.timeout, 7)
                second.close()
                self.assertLess(c.time.monotonic() - started, 2)
                launch.assert_called_once()
                self.assertEqual(commands, ["query_version", "query_version"])
        finally:
            stop.set()
            for sock in listeners + accepted:
                sock.close()
            for worker in workers:
                worker.join(timeout=1)
                self.assertFalse(worker.is_alive())

    def test_private_socket_refused_stop_restart(self):
        # Bound but non-listening: deterministic ECONNREFUSED without port reuse.
        with c.socket.socket() as dead:
            dead.bind(("127.0.0.1", 0))
            row = (c.DEFAULT_SERVER, dead.getsockname()[1], "secret")
            with patch.dict(os.environ, {}, clear=True), patch.object(c, "registry_entries", return_value=[row]), patch.object(
                    c, "jar_stamp", return_value="stamp"), patch.object(c, "start_checked") as start:
                self.assertEqual(c.stop_server("unused", c.DEFAULT_SERVER), 0)
                c.connect("unused", {}, c.DEFAULT_SERVER, True, 7, False)
                start.assert_called_once_with("unused", c.DEFAULT_SERVER, 7, True, False)

    def test_failed_greeting_closes_socket(self):
        sock = Mock()
        sock.recv.return_value = b"ERROR bad password\n"
        with patch.object(c.socket, "create_connection", return_value=sock):
            with self.assertRaises(c.Fallback):
                c.Connection(1, "secret", 4)
        sock.close.assert_called_once()

    def test_metadata_probe_is_bare_command_and_frame_is_preserved(self):
        sock = Mock()
        sock.recv.side_effect = [b"OK {}\n", b'OK {"protocol":1}\n']
        with patch.object(c.socket, "create_connection", return_value=sock):
            conn = c.Connection(1, "secret", 4)
            self.assertEqual(conn.command("query_version"), ("OK", {"protocol": 1}))
        self.assertEqual(sock.sendall.call_args.args, (b"query_version\n",))
        conn.close()

    def test_private_registry_read_only_and_explicit_filter(self):
        with tempfile.TemporaryDirectory() as home:
            db = Path(home) / "servers.db"
            with contextlib.closing(sqlite3.connect(db)) as con:
                con.execute("CREATE TABLE isabelle_servers(name, port, password)")
                con.executemany("INSERT INTO isabelle_servers VALUES (?, ?, ?)",
                                [(JEDIT, 100, "secret"), ("private", 101, "other"),
                                 ("bad", "oops", "bad")])
                con.commit()
            before = db.read_bytes()
            with patch.object(c, "home_user", return_value=home):
                self.assertEqual(c.registry_entries("unused", {}, "private"),
                                 [("private", 101, "other")])
                self.assertEqual(len(c.registry_entries("unused", {})), 2)
            self.assertEqual(db.read_bytes(), before)
        with tempfile.TemporaryDirectory() as home, patch.object(c, "home_user", return_value=home):
            self.assertEqual(c.registry_entries("unused", {}), [])
            self.assertFalse((Path(home) / "servers.db").exists())

    def test_refused_pruning_exact_row_replacement_and_foreign_survival(self):
        dead = c.FALLBACK_PREFIX + "dead"
        replaced = c.FALLBACK_PREFIX + "replaced"
        foreign = [(JEDIT, 10, "editor"), ("private", 11, "manual"),
                   (c.DEFAULT_SERVER, 12, "default")]
        with tempfile.TemporaryDirectory() as home:
            db = Path(home) / "servers.db"
            with contextlib.closing(sqlite3.connect(db)) as con:
                con.execute("CREATE TABLE isabelle_servers(name PRIMARY KEY, port, password)")
                con.executemany("INSERT INTO isabelle_servers VALUES (?, ?, ?)",
                                foreign + [(dead, 20, "old"), (replaced, 21, "new")])
                con.commit()
            cached = {"_registry_db": str(db)}
            c.prune_refused_server(cached, dead, 20, "old")
            c.prune_refused_server(cached, replaced, 21, "old")
            c.prune_refused_server(cached, replaced, 20, "new")
            for row in foreign:
                c.prune_refused_server(cached, *row)
            with patch.object(c, "home_user", return_value=home):
                self.assertEqual(c.registry_entries("unused", {}),
                                 foreign + [(replaced, 21, "new")])

    def test_selected_name_pruning_still_protects_embedded_and_unrelated(self):
        rows = [("selected", 20, "secret"), ("unrelated", 21, "other"), (JEDIT, 22, "editor")]
        with tempfile.TemporaryDirectory() as home:
            db = Path(home) / "servers.db"
            with contextlib.closing(sqlite3.connect(db)) as con:
                con.execute("CREATE TABLE isabelle_servers(name PRIMARY KEY, port, password)")
                con.executemany("INSERT INTO isabelle_servers VALUES (?, ?, ?)", rows)
                con.commit()
            cached = {"_registry_db": str(db)}
            for row in rows:
                c.prune_refused_server(cached, *row, selected_name="selected")
            c.prune_refused_server(cached, *rows[2], selected_name=JEDIT)
            with patch.object(c, "home_user", return_value=home):
                self.assertEqual(c.registry_entries("unused", {}), rows[1:])

    def test_pruning_locked_or_missing_database_is_nonblocking(self):
        name = c.FALLBACK_PREFIX + "dead"
        with tempfile.TemporaryDirectory() as home:
            db = Path(home) / "servers.db"
            cached = {"_registry_db": str(db)}
            c.prune_refused_server(cached, name, 20, "old")
            self.assertFalse(db.exists())
            with contextlib.closing(sqlite3.connect(db)) as con:
                con.execute("CREATE TABLE isabelle_servers(name PRIMARY KEY, port, password)")
                con.execute("INSERT INTO isabelle_servers VALUES (?, ?, ?)", (name, 20, "old"))
                con.commit()
                con.execute("BEGIN IMMEDIATE")
                start = c.time.monotonic()
                c.prune_refused_server(cached, name, 20, "old")
                self.assertLess(c.time.monotonic() - start, 0.2)
                self.assertEqual(con.execute("SELECT count(*) FROM isabelle_servers").fetchone(), (1,))

    def test_only_connection_refusal_prunes_discovered_fallback(self):
        names = [c.FALLBACK_PREFIX + suffix for suffix in
                 ("dead", "busy", "wrong", "stale", "live")]
        rows = [(name, i + 1, "secret") for i, name in enumerate(names)]
        def probe(name, info, deadline):
            error = {names[0]: ConnectionRefusedError(), names[1]: c.socket.timeout(),
                     names[2]: c.Fallback("wrong auth")}.get(name)
            if error is not None:
                raise error
            conn = Mock(version=version(name, stamp="old" if name == names[3] else "stamp"))
            conn.name = name
            return conn
        with patch.dict(os.environ, {}, clear=True), patch.object(c, "registry_entries", return_value=rows), \
                patch.object(c, "probe", side_effect=probe), patch.object(c, "jar_stamp", return_value="stamp"), \
                patch.object(c, "prune_refused_server") as prune:
            # The only live row sorts after the failed candidates.
            names[-1] = c.FALLBACK_PREFIX + "zzlive"
            rows[-1] = (names[-1], 5, "secret")
            conn = c.connect("unused", {}, c.DEFAULT_SERVER, False, 1, False)
            conn.close()
        prune.assert_called_once_with({}, names[0], 1, "secret", selected_name=None)

    def test_shim_cache_validation_and_cold_action_refusal(self):
        shim = ROOT / "query_base/lib/Tools/query"
        env = dict(os.environ, ISABELLE_QUERY_BASE_HOME=str(ROOT / "query_base"),
                   ISABELLE_QUERY_NO_SERVER="1")
        for args in (["--client-cache"], ["--client-cache", "bad"],
                     ["--client-cache", "off", "--no-server", "summary"]):
            proc = subprocess.run(["bash", str(shim), *args], env=env,
                                  capture_output=True, text=True, timeout=5)
            self.assertEqual(proc.returncode, 2)
            self.assertEqual(proc.stdout, "")
            self.assertIn("client-cache", proc.stderr)


if __name__ == "__main__":
    unittest.main()
