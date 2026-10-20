r"""A keep file records which uncited entries stay, and why [keep-file].

#22: a lemma proved "for independent interest" carries no evidence `unused`
can see, so the decision has to be written down once.  `.query-keep` in the
root (or `--keep-file PATH`) lists `THEORY NAME [NOTE]` per line, and each
name acts as `--keep`: not reported, and the `-r` cascade stops at it.

THEORY is checked: a line naming a declaration the theory does not have is
stale, and is reported on stderr rather than silently keeping nothing.

Hand-computed: `variant` is kept, so `helper` (cited only by it) does not
cascade; `scaffold` is not, so it is the one row left.
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import cli  # noqa: E402

KEEP = """\
theory Keep
  imports Main
begin

lemma helper: "True \\<or> False"
  by simp

lemma variant: "True \\<or> False"
  by (rule helper)

lemma scaffold: "False \\<longrightarrow> True"
  by simp

end
"""

KEEP_FILE = """\
# kept for independent interest
Keep\tvariant\tthe form with weaker hypotheses

sub/Keep.thy   variant
Keep           no_such_lemma   a stale line
Nowhere        variant
Keep
"""


class KeepFile(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.d = Path(self._tmp.name)
        (self.d / "sub").mkdir()
        (self.d / "sub" / "Keep.thy").write_text(KEEP, encoding="utf-8")
        (self.d / "ROOT").write_text(
            'session K in sub = HOL +\n  theories Keep\n', encoding="utf-8")
        cli._ROOT_OVERRIDE = self.d

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def run_unused(self, *argv):
        ns = cli._build_parser().parse_args(["unused", *argv])
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            ns.func(ns)
        rows = [ln.split()[1] for ln in out.getvalue().splitlines()
                if ".." in ln]
        return rows, err.getvalue()

    def test_without_one(self):
        rows, err = self.run_unused("-r")
        self.assertEqual(rows, ["helper", "variant", "scaffold"])
        self.assertEqual(err, "")

    def test_the_root_one_is_read(self):
        (self.d / ".query-keep").write_text(KEEP_FILE, encoding="utf-8")
        rows, err = self.run_unused("-r")
        self.assertEqual(rows, ["scaffold"])
        # Lines 4 and 2 are good; the other three are reported, by line.
        warned = sorted(ln.split(".query-keep:")[1].split(":")[0]
                        for ln in err.splitlines())
        self.assertEqual(warned, ["5", "6", "7"])
        self.assertIn("Keep declares no 'no_such_lemma'", err)
        self.assertIn("no theory 'Nowhere'", err)
        self.assertIn("expected THEORY NAME", err)

    def test_a_path_is_relative_to_the_file(self):
        elsewhere = self.d / "audit"
        elsewhere.mkdir()
        (elsewhere / "kept.tsv").write_text("../sub/Keep.thy variant\n",
                                            encoding="utf-8")
        rows, err = self.run_unused("--keep-file",
                                    str(elsewhere / "kept.tsv"))
        self.assertEqual(rows, ["scaffold"])
        self.assertEqual(err, "")

    def test_a_missing_one_is_a_usage_error(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_unused("--keep-file", str(self.d / "absent"))
        self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
