r"""`unused` and the facts a tool uses without a citation  [implicit-use].

Issue #21: `lemma own_simp [simp]:` is never cited — the simplifier picks it
up — so `unused` listed it as dead although deleting it can break proofs.
`declare foo [simp]` and a `lemmas` right-hand side already counted as
citations; the entry's OWN header attribute did not.

An implicitly used entry is live for the cascade, as a `--keep` name is:
`base` is cited only by `own_simp`, and must not cascade.  `--implicit mark`
(the default) still lists the uncited ones, tagged; `hide` drops them.  A
transforming attribute (`[rule_format]`) hands the fact to no tool.
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import cli, commands  # noqa: E402
from isabelle_query.model import CmdFlags  # noqa: E402


IMPL = """\
theory Impl
  imports Main
begin

definition f :: "nat \\<Rightarrow> nat" where "f n = n"

lemma base: "f 0 = 0"
  by (simp add: f_def)

lemma own_simp [simp]: "f 0 = 0 \\<and> True"
  using base by simp

lemma later_simp: "f 1 = 1"
  by (simp add: f_def)

declare later_simp [simp]

lemma in_set: "f 2 = 2"
  by (simp add: f_def)

lemmas f_simps [simp] = in_set

lemma fmt [rule_format]: "\\<forall>n. f n = n"
  by (simp add: f_def)

theorem endpoint: "f 4 = 4"
  by (simp add: f_def)

end
"""


class Fixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        d = Path(self._tmp.name)
        (d / "Impl.thy").write_text(IMPL, encoding="utf-8")
        (d / "ROOT").write_text("session Impl = HOL +\n  theories Impl\n",
                                encoding="utf-8")
        cli._ROOT_OVERRIDE = d
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def rows(self, **kw):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_unused(self.sections, CmdFlags(**kw))
        lines = buf.getvalue().splitlines()
        # Below the count line, a blank, and the two header lines.
        return lines[0], [" ".join(ln.split()) for ln in lines[4:]
                          if ln.strip()]


class UnusedMarksTheImplicitOnes(Fixture):

    def test_mark(self):
        head, rows = self.rows()
        self.assertEqual(
            head, "4 unused entries (zero callers, 2 of them implicitly used):")
        self.assertEqual([r.split()[1] for r in rows],
                         ["own_simp", "f_simps", "fmt", "endpoint"])
        self.assertTrue(rows[0].endswith("[implicit: simp]"))
        self.assertNotIn("implicit", rows[2])      # `fmt`: rule_format only

    def test_hide(self):
        _head, rows = self.rows(implicit="hide")
        self.assertEqual([r.split()[1] for r in rows], ["fmt", "endpoint"])

    def test_cascade_stops_at_an_implicit_entry(self):
        for mode in ("mark", "hide"):
            _head, rows = self.rows(recursive=True, implicit=mode)
            names = [r.split()[1] for r in rows]
            self.assertNotIn("base", names)
            self.assertNotIn("in_set", names)

    def test_roots_carry_the_tag_and_the_mark(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_unused(self.sections, CmdFlags(roots=True))
        out = [" ".join(ln.split()) for ln in buf.getvalue().splitlines()]
        self.assertEqual(out[0], "4 unused roots, 2 of them implicitly used:")
        self.assertEqual(out[2].split()[:2], ["Tag", "Root"])
        rows = {r.split()[1]: r for r in out[4:] if r}
        self.assertTrue(rows["own_simp"].startswith("LEMMA own_simp 2 "))
        self.assertTrue(rows["own_simp"].endswith("Impl [implicit: simp]"))
        self.assertTrue(rows["endpoint"].startswith("THEOREM endpoint"))

    def test_roots_hide(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_unused(self.sections,
                                CmdFlags(roots=True, implicit="hide"))
        self.assertTrue(buf.getvalue().startswith("2 unused roots:"))


if __name__ == "__main__":
    unittest.main()
