r"""`shape census --by-session`: one process, one session at a time (issue #6).

A whole-AFP census driven by a shell loop spawns `query` once per entry and pays
interpreter + process startup ~1,000 times.  Batch mode pays it once.  Measured
in one process over 992 AFP sessions: 292,343 records in 414s, worst single
session 29 MB traced, process peak 93 MB RSS.

Three properties make it a replacement for the loop rather than a faster
approximation of it, and each is pinned here:

* **Bounded memory** — sections are built by a *thunk* inside the loop and
  dropped at the end of it, so a corpus run holds one session at a time.  Loading
  the whole tree first is a different program (29 MB traced for 12 entries
  extrapolates to gigabytes over ~9,600 theories).
* **Isolation** — a session that cannot be loaded or analysed is reported on
  stderr and skipped.  One bad session must not cost the other 991.
* **Global dedup** — the dedup set is shared across sessions, so a theory
  claimed by two sessions is emitted once.  Real exposure: within the
  `AutoCorres2` entry, the `CParser` session's 47 theories are all also claimed
  by `AutoCorres2`.

The exit-code contract is #7's, re-derived for a batch run where "empty" is now
per-session; `ExitContract` below states each case and why.
"""

import io
import json
import os
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402
from isabelle_query import shape_cmds  # noqa: E402

THY = """theory {name} imports Main begin

lemma {name}_a: "P a"
proof -
  have "Q a" by simp
  show "P a" by simp
qed

end
"""


def _sec(name, session=None):
    sec = section_from(THY.format(name=name), name)
    sec.session = session
    return sec


def _run(groups, **kw):
    """Run the batch census, returning (records, stderr, outcome)."""
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        outcome = shape_cmds.cmd_shape_census_by_session(groups, **kw)
    recs = [json.loads(ln) for ln in out.getvalue().splitlines() if ln.strip()]
    return recs, err.getvalue(), outcome


def _boom(exc=RuntimeError("unparseable")):
    def load():
        raise exc
    return load


class Provenance(unittest.TestCase):
    def test_records_carry_their_session(self):
        groups = [("S1", lambda: [_sec("Alpha", "S1")]),
                  ("S2", lambda: [_sec("Beta", "S2")])]
        recs, err, out = _run(groups)
        self.assertEqual([r["session"] for r in recs], ["S1", "S2"])
        self.assertEqual([r["theory"] for r in recs], ["Alpha", "Beta"])
        self.assertEqual(err, "")
        self.assertEqual(out, (2, 2, 0, 2))

    def test_session_is_null_without_one(self):
        """A bare `.thy` load has no session; the key is still present, so a
        consumer never has to distinguish "absent" from "unknown"."""
        recs, _err, _out = _run([("x", lambda: [_sec("Alpha")])])
        self.assertIsNone(recs[0]["session"])
        self.assertIn("session", recs[0])


class Isolation(unittest.TestCase):
    def test_a_failing_session_is_skipped_not_fatal(self):
        groups = [("Good1", lambda: [_sec("Alpha", "Good1")]),
                  ("Bad", _boom()),
                  ("Good2", lambda: [_sec("Beta", "Good2")])]
        recs, err, out = _run(groups)
        self.assertEqual([r["session"] for r in recs], ["Good1", "Good2"])
        self.assertEqual(out, (3, 2, 1, 2))
        self.assertIn("Bad", err)
        self.assertIn("RuntimeError", err)

    def test_a_load_failure_is_isolated_too(self):
        """The thunk is called INSIDE the try.  If the caller passed ready-made
        sections instead, a theory that cannot be read would kill the run before
        the census ever started."""
        _recs, err, out = _run([("Bad", _boom(OSError("no such file")))])
        self.assertEqual(out.skipped, 1)
        self.assertIn("OSError", err)

    def test_warnings_never_touch_stdout(self):
        """stdout is the JSONL stream; a diagnostic there corrupts the corpus
        file a caller is redirecting."""
        out_buf, err_buf = io.StringIO(), io.StringIO()
        with redirect_stdout(out_buf), redirect_stderr(err_buf):
            shape_cmds.cmd_shape_census_by_session([("Bad", _boom())])
        self.assertEqual(out_buf.getvalue(), "")
        self.assertNotEqual(err_buf.getvalue(), "")

    def test_broken_pipe_is_not_a_session_failure(self):
        """`census --by-session | head` closes the pipe.  Swallowing that would
        report every remaining session as skipped and exit 2 for a run that
        worked, so it must propagate."""
        def load():
            raise BrokenPipeError(32, "Broken pipe")
        with self.assertRaises(BrokenPipeError):
            shape_cmds.cmd_shape_census_by_session([("S", load)])


class Laziness(unittest.TestCase):
    def test_sessions_load_one_at_a_time(self):
        """The memory bound is only real if a later session has not been built
        by the time an earlier one is being emitted."""
        order = []

        def loader(name):
            def load():
                order.append(f"load:{name}")
                return [_sec(name, name)]
            return load

        groups = ((n, loader(n)) for n in ("Aaa", "Bbb", "Ccc"))
        out, _err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(_err):
            shape_cmds.cmd_shape_census_by_session(groups)
        # Interleaved, not all loads first.
        self.assertEqual(order, ["load:Aaa", "load:Bbb", "load:Ccc"])
        self.assertEqual(len(out.getvalue().splitlines()), 3)

    def test_a_generator_of_groups_is_consumed_lazily(self):
        built = []

        def gen():
            for n in ("Aaa", "Bbb"):
                built.append(n)
                yield n, (lambda n=n: [_sec(n, n)])

        recs, _err, _out = _run(gen())
        self.assertEqual(built, ["Aaa", "Bbb"])
        self.assertEqual(len(recs), 2)


class Dedup(unittest.TestCase):
    def test_a_theory_claimed_twice_is_emitted_once(self):
        """`sections_for_session` shares one dedup set across sessions, matching
        a whole-root load's "first session to reference a theory owns it".  With
        a per-session set the shared theory's proofs appear twice."""
        import tempfile
        from pathlib import Path
        from isabelle_query import parsing
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "shared").mkdir()
            (root / "shared" / "Shared.thy").write_text(
                THY.format(name="Shared"), encoding="utf-8")
            (root / "ROOT").write_text(
                'session One = HOL +\n  theories\n    "shared/Shared"\n\n'
                'session Two = HOL +\n  theories\n    "shared/Shared"\n',
                encoding="utf-8")
            from isabelle_query.common import iter_sessions
            sessions = iter_sessions(root)
            self.assertEqual([s.name for s in sessions], ["One", "Two"])
            seen: set = set()
            groups = [(s.name, (lambda s=s: parsing.sections_for_session(s, seen)))
                      for s in sessions]
            recs, _err, out = _run(groups)
        self.assertEqual(len(recs), 1, "shared theory emitted more than once")
        self.assertEqual(recs[0]["session"], "One")  # first claimant wins
        self.assertEqual(out.loaded, 2)              # both sessions still ran

    def test_the_cli_shares_one_dedup_set_across_sessions(self):
        """The primitive honouring a shared set is not enough — `cli` is the
        only place that decides whether to share one, so the whole path is
        driven here.  Without this, replacing the shared set with a fresh
        `set()` per session passes every other test in this file."""
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "shared").mkdir()
            (root / "shared" / "Shared.thy").write_text(
                THY.format(name="Shared"), encoding="utf-8")
            (root / "ROOT").write_text(
                'session One = HOL +\n  theories\n    "shared/Shared"\n\n'
                'session Two = HOL +\n  theories\n    "shared/Shared"\n',
                encoding="utf-8")
            err, code, out = ExitContract()._run_cli(root)
        self.assertIsNone(code)
        recs = [json.loads(ln) for ln in out.splitlines() if ln.strip()]
        self.assertEqual(len(recs), 1, "shared theory emitted more than once")
        self.assertEqual(recs[0]["session"], "One")
        self.assertEqual(err, "")


class Resume(unittest.TestCase):
    def test_resume_skips_already_present_records(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl",
                                         delete=False) as fh:
            fh.write(json.dumps({"theory": "Alpha", "lemma": "Alpha_a"}) + "\n")
            path = fh.name
        try:
            groups = [("S1", lambda: [_sec("Alpha", "S1")]),
                      ("S2", lambda: [_sec("Beta", "S2")])]
            recs, _err, _out = _run(groups, resume=path)
        finally:
            os.unlink(path)
        self.assertEqual([r["theory"] for r in recs], ["Beta"])


class ExitContract(unittest.TestCase):
    """#7's rule re-derived for a batch run, where "empty" is per-session.

    Driven through `_run_shape_census_by_session` rather than asserted on a
    hand-built outcome tuple: the mapping from outcome to exit code IS the
    contract, and a test that constructs the tuple itself checks nothing.
    """

    def _run_cli(self, root, loader=None):
        """Run the batch census over `root`, optionally with the per-session
        loader replaced.  Returns (stderr, SystemExit code or None)."""
        import argparse
        from pathlib import Path
        ns = argparse.Namespace(resume=None, by_session=True)
        old_root, old_load = cli._ROOT_OVERRIDE, cli.sections_for_session
        cli._ROOT_OVERRIDE = Path(root)
        if loader is not None:
            cli.sections_for_session = loader
        out, err = io.StringIO(), io.StringIO()
        code = None
        try:
            with redirect_stdout(out), redirect_stderr(err):
                cli._run_shape_census(ns)
        except SystemExit as e:
            code = e.code
        finally:
            cli._ROOT_OVERRIDE = old_root
            cli.sections_for_session = old_load
        return err.getvalue(), code, out.getvalue()

    def _root(self, d, body):
        from pathlib import Path
        root = Path(d)
        (root / "T.thy").write_text(body, encoding="utf-8")
        (root / "ROOT").write_text(
            'session S = HOL +\n  theories\n    T\n', encoding="utf-8")
        return root

    def test_no_sessions_at_all_is_bad_root(self):
        """Nothing to iterate is exactly #7's case, so it reuses #7's
        diagnosis rather than inventing a batch-specific message."""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            (__import__("pathlib").Path(d) / "notes.txt").write_text("hi")
            err, code, _out = self._run_cli(d)
        self.assertEqual(code, cli._EXIT_BAD_ROOT)
        self.assertIn("no ROOT or ROOTS file", err)

    def test_all_sessions_skipped_is_an_error(self):
        """Every attempt raised, so nothing was measured — the corpus-scale
        form of the silent zero #7 fixed.  Exit 0 here would hand a wrapper an
        empty file that looks like a valid census."""
        import tempfile

        def boom(session, seen):
            raise RuntimeError("unparseable")

        with tempfile.TemporaryDirectory() as d:
            self._root(d, THY.format(name="T"))
            err, code, out = self._run_cli(d, loader=boom)
        self.assertEqual(code, cli._EXIT_BAD_ROOT)
        self.assertIn("failed to load", err)
        self.assertEqual(out, "")

    def test_partial_failure_succeeds_but_says_so(self):
        """The question WAS asked and mostly answered, so exit 0 — but the loss
        is named on stderr, or a wrapper is quietly given a short corpus."""
        import tempfile
        calls = []

        def flaky(session, seen):
            calls.append(session.name)
            if len(calls) == 1:
                raise RuntimeError("unparseable")
            return [_sec("Beta", session.name)]

        with tempfile.TemporaryDirectory() as d:
            root = self._root(d, THY.format(name="T"))
            (root / "ROOT").write_text(
                'session S1 = HOL +\n  theories\n    T\n\n'
                'session S2 = HOL +\n  theories\n    T\n', encoding="utf-8")
            err, code, out = self._run_cli(d, loader=flaky)
        self.assertIsNone(code)                      # exit 0
        self.assertIn("1 of 2 session(s) skipped", err)
        self.assertEqual(len(out.splitlines()), 1)   # the good session emitted

    def test_loaded_but_zero_records_is_a_silent_honest_zero(self):
        """A corpus of definitions has no proofs to measure.  `loaded` counts
        sessions that PARSED, not sessions that produced output — that is what
        separates this from the all-skipped case."""
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self._root(d, "theory T imports Main begin\n"
                          'definition d :: nat where "d = 0"\nend\n')
            err, code, out = self._run_cli(d)
        self.assertIsNone(code)
        self.assertEqual(out, "")
        self.assertEqual(err, "")

    def test_a_rootless_directory_of_theories_still_works(self):
        """No ROOT is not the same as nothing to read: `_sections_from_dir`
        falls back to a recursive `*.thy` glob, and plain `census` has always
        handled such a directory.  `--by-session` must be a cheaper command,
        not a narrower one — before this it reported #7's "no ROOT or ROOTS
        file" diagnosis for a corpus it could perfectly well read."""
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "Bare.thy").write_text(THY.format(name="Bare"),
                                              encoding="utf-8")
            err, code, out = self._run_cli(d)
        self.assertIsNone(code)
        recs = [json.loads(ln) for ln in out.splitlines() if ln.strip()]
        self.assertEqual([r["theory"] for r in recs], ["Bare"])
        self.assertIsNone(recs[0]["session"])   # no ROOT, so no session name
        self.assertEqual(err, "")

    def test_exit_codes_are_distinct(self):
        self.assertNotEqual(cli._EXIT_BAD_ROOT, cli._EXIT_SIGPIPE)
        self.assertEqual(cli._EXIT_BAD_ROOT, 2)
        self.assertEqual(cli._EXIT_SIGPIPE, 141)  # 128 + SIGPIPE, as a shell reports


if __name__ == "__main__":
    unittest.main()
