"""Opt-in JVM lifetime checks using a copied component and private registry.

Set QUERY_OWNED_TEST_COMPONENT to a compiled query_base snapshot.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import select
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


@unittest.skipUnless(os.environ.get("QUERY_OWNED_TEST_COMPONENT"), "needs compiled private component")
class OwnedLauncherTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.home = tempfile.TemporaryDirectory(prefix="query-owned-test-")
        cls.addClassCleanup(cls.home.cleanup)
        work = Path(cls.home.name)
        cls.component = work / "query_base"
        shutil.copytree(os.environ["QUERY_OWNED_TEST_COMPONENT"], cls.component)
        cls.env = dict(os.environ, USER_HOME=str(work / "home"),
                       ISABELLE_QUERY_CLIENT_CACHE=str(work / "client.json"))
        for key in ("CLASSPATH", "ISABELLE_COMPONENTS", "ISABELLE_QUERY_JAR",
                    "ISABELLE_QUERY_BASE_HOME", "ISABELLE_QUERY_CLIENT_SERVER"):
            cls.env.pop(key, None)
        (work / "home").mkdir()
        cls.isabelle = shutil.which("isabelle")
        subprocess.run([cls.isabelle, "components", "-u", str(cls.component)],
                       env=cls.env, check=True, capture_output=True, timeout=20)
        cls.script = cls.component / "lib/scripts/query_client.py"
        spec = importlib.util.spec_from_file_location("owned_client", cls.script)
        cls.c = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.c)

    def setUp(self):
        self.env_patch = patch.dict(os.environ, self.env, clear=True)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)
        self.addCleanup(self.c.close_owned_since, 0)

    def start(self, name="private"):
        info = self.c.start_server(self.isabelle, name)
        proc = self.c._owned_launches[-1]
        return info, proc

    def version(self, info):
        conn = self.c.Connection(*info, 2)
        try:
            head, body = conn.command("query_version")
            self.assertEqual(head, "OK")
            return body
        finally:
            conn.close()

    def test_socket_eof_survives_owner_eof_removes_exact_row(self):
        info, proc = self.start()
        self.assertEqual(self.version(info)["host_name"], "private")
        self.assertEqual(self.version(info)["host_kind"], "server")
        self.assertIsNone(proc.poll())
        self.c.close_owned_since(0)
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(self.c.registry_entries(self.isabelle, {}), [])

    def test_collision_preserves_existing_endpoint(self):
        info, proc = self.start()
        with self.assertRaises(self.c.Fallback):
            self.c.start_server(self.isabelle, "private")
        self.assertIsNone(proc.poll())
        self.assertEqual(self.version(info)["host_name"], "private")

    def test_shutdown_allows_immediate_same_name_restart(self):
        info, proc = self.start()
        conn = self.c.Connection(*info, 2)
        try:
            self.assertEqual(conn.command("shutdown"), ("OK", {}))
            replacement, child = self.start()
            self.assertEqual(self.version(replacement)["host_name"], "private")
            self.assertIsNone(child.poll())
            self.assertEqual(proc.wait(timeout=3), 0)
        finally:
            conn.close()

    def test_eof_with_blocked_request_and_unauthenticated_connection(self):
        info, proc = self.start()
        conn = self.c.Connection(*info, 2)
        unauthenticated = self.c.socket.create_connection(("127.0.0.1", info[0]), 2)
        try:
            conn.sock.sendall(b'1000\nquery_run {')
            proc.stdin.close()
            self.assertEqual(proc.wait(timeout=3), 0)
        finally:
            conn.close()
            unauthenticated.close()

    def test_eof_exits_even_when_registry_cleanup_is_locked(self):
        info, proc = self.start("locked")
        db = Path(self.c.home_user(self.isabelle, {})) / "servers.db"
        lock = sqlite3.connect(db)
        try:
            lock.execute("BEGIN IMMEDIATE")
            proc.stdin.close()
            # SQLite may refuse the lock immediately; either path must exit.
            self.assertIn(proc.wait(timeout=5), (0, 2))
        finally:
            lock.rollback()
            lock.execute("DELETE FROM isabelle_servers WHERE name=? AND port=? AND password=?",
                         ("locked", *info))
            lock.commit()
            lock.close()

    def test_start_and_stop_preserve_foreign_registry_row(self):
        info, proc = self.start()
        db = Path(self.c.home_user(self.isabelle, {})) / "servers.db"
        with contextlib.closing(sqlite3.connect(db)) as con, con:
            con.execute("INSERT INTO isabelle_servers VALUES (?, ?, ?)",
                        ("foreign", 9, "not-a-live-listener"))
        try:
            self.start("second")
            self.c.close_owned_since(0)
            self.assertEqual(self.c.registry_entries(self.isabelle, {}),
                             [("foreign", 9, "not-a-live-listener")])
        finally:
            with contextlib.closing(sqlite3.connect(db)) as con, con:
                con.execute("DELETE FROM isabelle_servers WHERE name=?", ("foreign",))

    def test_attached_client_exit_preserves_launcher(self):
        info, proc = self.start()
        with patch.dict(os.environ, {"ISABELLE_QUERY_CLIENT_SERVER": "private"}), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.c.main(["--client-status"]), 0)
        self.assertIsNone(proc.poll())
        self.assertEqual(self.version(info)["host_name"], "private")

    def test_owner_disappears_then_query_rebuilds_on_replacement(self):
        info, proc = self.start(self.c.FALLBACK_PREFIX + "first")
        connect = self.c.connect
        attempts = []
        def connect_then_close_owner(*args, **kwargs):
            conn = connect(*args, **kwargs)
            attempts.append(conn)
            if len(attempts) == 1:
                proc.stdin.close()
                self.assertEqual(proc.wait(timeout=3), 0)
            return conn
        with tempfile.TemporaryDirectory(prefix="query-corpus-") as root:
            Path(root, "A.thy").write_text("theory A imports Main begin\nlemma a: True by simp\nend\n")
            with patch.object(self.c, "connect", side_effect=connect_then_close_owner), \
                    contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(self.c.main(["-R", root, "summary"]), 0)
            self.assertTrue(out.getvalue())
        self.assertEqual(len(attempts), 2)
        self.assertNotEqual(attempts[0].name, attempts[1].name)
        self.assertEqual(self.c.registry_entries(self.isabelle, {}), [])

    def test_forced_exit_residual_is_pruned_by_next_auto_client(self):
        name = self.c.FALLBACK_PREFIX + "forced"
        info, proc = self.start(name)
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait(timeout=3)
        self.c.close_owned_since(0)
        self.assertIn((name, *info), self.c.registry_entries(self.isabelle, {}))
        db = Path(self.c.home_user(self.isabelle, {})) / "servers.db"
        foreign = ("manual-survivor", 9, "foreign")
        with contextlib.closing(sqlite3.connect(db)) as con, con:
            con.execute("INSERT INTO isabelle_servers VALUES (?, ?, ?)", foreign)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(self.c.main(["--client-status"]), 0)
            self.assertEqual(self.c.registry_entries(self.isabelle, {}), [foreign])
        finally:
            with contextlib.closing(sqlite3.connect(db)) as con, con:
                con.execute("DELETE FROM isabelle_servers WHERE name=? AND port=? AND password=?", foreign)

    def test_stale_selected_explicit_and_default_restart_recover(self):
        for name, explicit in (("private", True), (self.c.DEFAULT_SERVER, False)):
            with self.subTest(name=name):
                info, proc = self.start(name)
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=3)
                self.c.close_owned_since(0)
                self.assertIn((name, *info), self.c.registry_entries(self.isabelle, {}))
                env = {"ISABELLE_QUERY_CLIENT_SERVER": name} if explicit else {}
                args = ["--client-status"] if explicit else ["--client-restart", "--client-status"]
                with patch.dict(os.environ, env), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(self.c.main(args), 0)
                self.assertEqual(self.c.registry_entries(self.isabelle, {}), [])

    def test_unnamed_controls_preserve_other_owners_fallback(self):
        name = self.c.FALLBACK_PREFIX + "borrowed"
        info, proc = self.start(name)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.c.main(["--client-stop"]), 0)
            self.assertEqual(self.c.main(["--client-restart", "--client-status"]), 0)
        self.assertIsNone(proc.poll())
        self.assertEqual(self.version(info)["host_name"], name)
        self.assertEqual(self.c.registry_entries(self.isabelle, {}), [(name, *info)])

    def test_main_status_cleans_its_spawn(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(self.c.main(["--client-status"]), 0)
        self.assertIn("server", out.getvalue())
        self.assertEqual(self.c._owned_launches, [])
        self.assertEqual(self.c.registry_entries(self.isabelle, {}), [])

    @unittest.skipUnless(hasattr(os, "pidfd_open"), "requires Linux pidfds")
    def test_sigkill_owner_delivers_eof_to_real_launcher(self):
        code = (
            "import importlib.util,json,signal,sys\n"
            "s=importlib.util.spec_from_file_location('c',sys.argv[1])\n"
            "c=importlib.util.module_from_spec(s); s.loader.exec_module(c)\n"
            "info=c.start_server(sys.argv[2],'private')\n"
            "print(json.dumps([c._owned_launches[-1].pid,*info]),flush=True)\n"
            "signal.pause()\n"
        )
        owner = subprocess.Popen([sys.executable, "-c", code, str(self.script), self.isabelle],
                                 env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        child_pid = None
        try:
            self.assertTrue(select.select([owner.stdout], [], [], 20)[0])
            child_pid, port, password = json.loads(owner.stdout.readline())
            child_fd = os.pidfd_open(child_pid)
            try:
                self.assertEqual(self.version((port, password))["host_name"], "private")
                owner.kill()
                self.assertEqual(owner.wait(timeout=2), -signal.SIGKILL)
                self.assertTrue(select.select([child_fd], [], [], 5)[0], "launcher survived owner SIGKILL")
            finally:
                os.close(child_fd)
            self.assertEqual(self.c.registry_entries(self.isabelle, {}), [])
        finally:
            if owner.poll() is None:
                owner.kill()
            owner.communicate(timeout=2)
            if child_pid is not None:
                try:
                    os.killpg(child_pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass


if __name__ == "__main__":
    unittest.main()
