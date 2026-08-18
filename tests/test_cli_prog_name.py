"""Messages name the command the user typed [cli-alias].

The distribution is called `isabelle-query`, so someone who
`pip install isabelle-query` and then types `isabelle-query` used to get
*command not found* — the one name they already knew was the one that did not
work.  Both names are now console scripts pointing at the same entry point.

That makes a hardcoded `"query"` in user-facing text wrong for one caller or
the other, and wrong in the least helpful direction: `usage: query ...` in
answer to `isabelle-query -h` names a command the reader may not have on PATH,
and every example in that help output becomes uncopyable.  So the name is
reflected from the invocation via a single accessor, `cli._prog_name`, which
five sites and two help examples now share.

These tests set `sys.argv[0]` — the only honest way to check a *reflection*.
`test_cli_version.py` ties `--version` to the same accessor, but under pytest
argv[0] is pytest's own, so agreement there is incidental; the real contract is
here.
"""

import contextlib
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

try:
    import tomllib
except ModuleNotFoundError:  # Python < 3.11
    tomllib = None

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


@contextlib.contextmanager
def invoked_as(argv0, *rest):
    """Run the body as though the shell had spelled the command `argv0`."""
    old = sys.argv
    sys.argv = [argv0, *rest]
    try:
        yield
    finally:
        sys.argv = old


class ProgNameReflectsTheInvocation(unittest.TestCase):

    def test_each_console_script_name_is_reported_as_itself(self):
        for spelling in ("query", "isabelle-query"):
            with self.subTest(spelling=spelling):
                # A real invocation gives an absolute path, not a bare word.
                with invoked_as(f"/usr/local/bin/{spelling}"):
                    self.assertEqual(cli._prog_name(), spelling)

    def test_a_renamed_wrapper_is_reported_as_itself(self):
        # Nothing here is a whitelist of the two shipped names: a shim, an alias
        # script or a distro-renamed binary reports whatever the user typed,
        # which is the whole point of reflecting rather than canonicalising.
        with invoked_as("/opt/bin/iq"):
            self.assertEqual(cli._prog_name(), "iq")

    def test_usage_line_names_the_invoked_command(self):
        with invoked_as("/usr/local/bin/isabelle-query"):
            usage = cli._build_parser().format_usage()
        self.assertTrue(usage.startswith("usage: isabelle-query"), usage)

    def test_subcommand_usage_inherits_the_invoked_command(self):
        # argparse derives every subparser's prog from the parent's, so one
        # `prog=` covers `isabelle-query shape census --help` too.
        with invoked_as("/usr/local/bin/isabelle-query"):
            parser = cli._build_parser()
            with self.assertRaises(SystemExit):
                with contextlib.redirect_stdout(io.StringIO()) as buf:
                    parser.parse_args(["grep", "--help"])
        self.assertTrue(buf.getvalue().startswith("usage: isabelle-query grep"),
                        buf.getvalue()[:120])

    def test_help_examples_name_the_invoked_command(self):
        # The copy-pasteability half: the `-`/stdin examples are embedded in
        # help *prose*, where a stale name is least likely to be noticed.
        with invoked_as("/usr/local/bin/isabelle-query"):
            parser = cli._build_parser()
            with self.assertRaises(SystemExit):
                with contextlib.redirect_stdout(io.StringIO()) as buf:
                    parser.parse_args(["grep", "--help"])
        text = " ".join(buf.getvalue().split())
        self.assertIn("| isabelle-query grep PAT -", text)
        self.assertNotIn("| query grep PAT -", text)

    def test_version_line_names_the_invoked_command(self):
        with invoked_as("/usr/local/bin/isabelle-query"):
            parser = cli._build_parser()
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                with self.assertRaises(SystemExit):
                    parser.parse_args(["--version"])
            expected = f"isabelle-query {cli._resolve_version()}"
        self.assertEqual(buf.getvalue().strip(), expected)

    def test_stderr_diagnostic_names_the_invoked_command(self):
        # `query: /path: why` is the shape a Unix caller greps for, so it has
        # to move with the rest rather than stay a fixed literal.
        with invoked_as("/usr/local/bin/isabelle-query",
                        "-R", "/no/such/place/at/all", "summary"):
            with contextlib.redirect_stderr(io.StringIO()) as buf:
                with self.assertRaises(SystemExit) as cm:
                    cli.main()
        self.assertEqual(cm.exception.code, 2)
        self.assertTrue(buf.getvalue().startswith("isabelle-query: "),
                        buf.getvalue())


    def test_the_census_skip_notice_names_the_invoked_command(self):
        # `shape_cmds` sits *below* `cli` in the module DAG, so it cannot reach
        # a helper defined there — which is why the accessor is the leaf module
        # `_prog`.  This is the test that would have caught the sixth site: the
        # `[cli-alias]` item listed five, all in `cli.py`.
        from isabelle_query import shape_cmds

        def explode():
            raise RuntimeError("boom")

        with invoked_as("/usr/local/bin/isabelle-query"):
            with contextlib.redirect_stdout(io.StringIO()):
                with contextlib.redirect_stderr(io.StringIO()) as err:
                    out = shape_cmds.cmd_shape_census([("Demo", explode)])
        self.assertEqual(out.skipped, 1)
        self.assertTrue(err.getvalue().startswith("isabelle-query: session"),
                        err.getvalue())


class NoHardcodedProgramName(unittest.TestCase):
    """The contract of CONTRIBUTING's "the program name is not a literal".

    A grep, because the failure mode is *addition*: the five sites the
    `[cli-alias]` item listed were all in `cli.py`, and the sixth — the census
    skip notice — was found only by installing the alias and running it.  A
    reviewer will not re-run that by hand on every new message.
    """

    # `"query: ` / `f"query ` — the two shapes a user-facing literal takes.
    # A trailing quote (`.joinpath("query")`) is a path, not a message, and a
    # backticked `query` in prose is not preceded by a quote character.
    LITERAL = re.compile(r"""["']query[ :]""")

    def test_no_message_hardcodes_the_canonical_name(self):
        src = os.path.join(REPO, "src", "isabelle_query")
        offenders = []
        for fname in sorted(os.listdir(src)):
            if not fname.endswith(".py") or fname == "_prog.py":
                continue  # `_prog` *defines* the fallback; that is its job
            with open(os.path.join(src, fname), encoding="utf-8") as fh:
                for n, line in enumerate(fh, 1):
                    if line.lstrip().startswith("#"):
                        continue
                    if self.LITERAL.search(line):
                        offenders.append(f"{fname}:{n}: {line.strip()}")
        self.assertEqual(offenders, [], "use `prog_name()` instead:\n" +
                         "\n".join(offenders))


class ProgNameFallsBackToCanonical(unittest.TestCase):
    """Where the invocation cannot name a command anyone could retype."""

    def test_module_path_falls_back(self):
        # `python -m isabelle_query.cli` and `python src/.../cli.py` both put a
        # source file in argv[0]; `usage: cli.py` would be worse than useless.
        for argv0 in ("/x/src/isabelle_query/cli.py", "cli.py"):
            with self.subTest(argv0=argv0):
                with invoked_as(argv0):
                    self.assertEqual(cli._prog_name(), "query")

    def test_empty_argv0_falls_back(self):
        # Embedded interpreters and `python -c` leave argv[0] empty.
        with invoked_as(""):
            self.assertEqual(cli._prog_name(), "query")

    def test_absent_argv_falls_back(self):
        old = sys.argv
        sys.argv = []
        try:
            self.assertEqual(cli._prog_name(), "query")
        finally:
            sys.argv = old


@unittest.skipIf(tomllib is None, "tomllib needs Python 3.11+")
class BothNamesAreInstalled(unittest.TestCase):
    """The reflection is pointless if only one script exists to reflect."""

    def setUp(self):
        with open(os.path.join(REPO, "pyproject.toml"), "rb") as fh:
            self.scripts = tomllib.load(fh)["project"]["scripts"]

    def test_both_names_are_declared(self):
        self.assertEqual(set(self.scripts), {"query", "isabelle-query"})

    def test_both_names_are_the_same_entry_point(self):
        # Additive by construction: one implementation, two spellings, so the
        # alias cannot drift into a second CLI.
        self.assertEqual(self.scripts["query"], self.scripts["isabelle-query"])

    def test_the_alias_matches_the_distribution_name(self):
        with open(os.path.join(REPO, "pyproject.toml"), "rb") as fh:
            name = tomllib.load(fh)["project"]["name"]
        self.assertIn(name, self.scripts)


if __name__ == "__main__":
    unittest.main()
