r"""A root that cannot be read is an error, not an empty answer.

`query -R /typo/path shape census` used to print nothing and exit 0, which is
byte-identical to a legitimate "this corpus has no proofs".  A shell path
expansion bug therefore produced a whole run of zero-record censuses that every
wrapper script accepted as valid (issue #7).

The fix has two halves, and they are checked separately because they fire at
different times:

* an explicit ``-R`` is an assertion by the caller, so a missing or
  non-directory path fails in `main` before any command runs;
* any other way of arriving at an empty index — including cwd discovery —
  fails in `load_index`, which is also where the diagnosis is produced, so it
  costs nothing on a run that finds theories.

Exit status is 2, deliberately not 1: the whole complaint was that a broken run
and an empty-but-honest run were indistinguishable, so they must not share a
code.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402


def write_tree(base: Path, files: dict) -> None:
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


class Diagnosis(unittest.TestCase):
    """`_diagnose_empty_root` names the narrowest cause that applies."""

    def test_missing_directory(self):
        self.assertEqual(
            cli._diagnose_empty_root(Path("/no/such/place/at/all")),
            "no such directory")

    def test_not_a_directory(self):
        with tempfile.NamedTemporaryFile(suffix=".txt") as fh:
            self.assertEqual(
                cli._diagnose_empty_root(Path(fh.name)), "not a directory")

    def test_directory_with_neither_root_nor_theories(self):
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {"notes.txt": "nothing to see\n"})
            msg = cli._diagnose_empty_root(Path(d))
            self.assertIn("no ROOT or ROOTS file", msg)
            self.assertIn("no .thy files", msg)

    def test_root_file_declaring_no_session(self):
        # AFP's own `thys/ROOT` is exactly this: a chapter-definition file.
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {"ROOT": "chapter Demo\n"})
            msg = cli._diagnose_empty_root(Path(d))
            self.assertIn("none declares a session", msg)

    def test_session_declared_but_no_theory_resolves(self):
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {
                "ROOT": "session Demo = HOL +\n  theories\n    Missing\n"})
            msg = cli._diagnose_empty_root(Path(d))
            self.assertIn("session(s) declared", msg)
            self.assertIn("Demo", msg)


class ExplicitRootFailsEarly(unittest.TestCase):
    """`-R` is checked in `main`, before the subcommand runs."""

    def _run(self, argv):
        old = sys.argv
        sys.argv = argv
        try:
            with self.assertRaises(SystemExit) as cm:
                cli.main()
            return cm.exception.code
        finally:
            sys.argv = old

    def test_missing_root_exits_2(self):
        self.assertEqual(
            self._run(["query", "-R", "/no/such/place/at/all", "summary"]), 2)

    def test_file_as_root_exits_2(self):
        with tempfile.NamedTemporaryFile(suffix=".txt") as fh:
            self.assertEqual(self._run(["query", "-R", fh.name, "summary"]), 2)

    def test_bad_root_reported_even_when_files_are_given(self):
        # The reason the early check is not redundant with `load_index`'s: the
        # search family (`grep PAT FILE`) resolves its own paths and returns
        # before `load_index` is ever called, so the load-time guard cannot
        # reach it.  Without this check a bad -R is silently ignored there.
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {
                "A.thy": 'theory A begin\nlemma a: "True" by simp\nend\n'})
            self.assertEqual(
                self._run(["query", "-R", "/no/such/place/at/all",
                           "grep", "lemma", str(Path(d) / "A.thy")]), 2)

    def test_exit_code_is_not_1(self):
        # 1 is "ran and found nothing".  Sharing it would recreate the bug.
        self.assertNotEqual(
            self._run(["query", "-R", "/no/such/place/at/all", "summary"]), 1)


class EmptyIndexFails(unittest.TestCase):
    """An empty index is an error wherever the root came from."""

    def test_a_rootless_directory_of_theories_still_loads(self):
        # The guard must not fire here: `_sections_from_dir` globs `*.thy`
        # when there is no ROOT, and that fallback is deliberate.  Getting
        # this wrong would turn a supported ad-hoc layout into an error.
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {
                "Orphan.thy": 'theory Orphan begin\n'
                              'lemma o1: "True" by simp\n'
                              'end\n'})
            old = cli._ROOT_OVERRIDE
            cli._ROOT_OVERRIDE = Path(d)
            try:
                self.assertEqual([s.theory for s in cli.load_index()],
                                 ["Orphan"])
            finally:
                cli._ROOT_OVERRIDE = old

    def test_load_index_on_an_empty_directory_exits_2(self):
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {"notes.txt": "nothing to see\n"})
            old = cli._ROOT_OVERRIDE
            cli._ROOT_OVERRIDE = Path(d)
            try:
                with self.assertRaises(SystemExit) as cm:
                    cli.load_index()
                self.assertEqual(cm.exception.code, 2)
            finally:
                cli._ROOT_OVERRIDE = old

    def test_a_real_session_still_loads(self):
        # The guard must not fire on the happy path.
        with tempfile.TemporaryDirectory() as d:
            write_tree(Path(d), {
                "ROOT": "session Demo = HOL +\n  theories\n    Top\n",
                "Top.thy": 'theory Top imports Main begin\n'
                           'lemma t: "True" by simp\n'
                           'end\n'})
            old = cli._ROOT_OVERRIDE
            cli._ROOT_OVERRIDE = Path(d)
            try:
                sections = cli.load_index()
                self.assertEqual([s.theory for s in sections], ["Top"])
            finally:
                cli._ROOT_OVERRIDE = old


if __name__ == "__main__":
    unittest.main()
