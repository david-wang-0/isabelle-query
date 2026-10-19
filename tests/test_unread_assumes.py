r"""`unused --locals` reports named premises the proof never reads
[unread-assumes].

Issue #20: a lemma's named `assumes` is proof-local exactly as a `have
NAME:` is, and the commonest dead name after a refactor.  It is read when
the proof cites it (`a`, `a(1)`), or cites the whole block (`assms`, the
legacy `prems`; `that` for an `if` premise).  A label with a declaring
attribute (`[simp]`) is used by the simplifier.  An unfinished proof reads
nothing by construction, so its premises are not reported.

The final `by` / `qed` method is where a premise is most often cited
(`by (auto simp: a)`), and the walk used to stop before reading it — local
`have`s never needed it.
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

ASM = """\
theory Asm
  imports Main
begin

lemma clamp:
  fixes n :: nat
  assumes lang: "n > 0"
      and sig: "n < 10"
      and i1: "1 \\<le> n"
  shows "n \\<noteq> 0"
  using lang by simp

lemma whole:
  assumes a: "n > (0::nat)" and b: "n < 5"
  shows "n \\<noteq> 0"
  using assms by simp

lemma indexed:
  assumes ab: "n > (0::nat)" "n < 5"
      and c: "True"
  shows "n \\<noteq> 0"
  using ab(1) by simp

lemma declared:
  assumes s [simp]: "f = (0::nat)"
  shows "f = 0"
  by simp

lemma unfinished:
  assumes x: "n > (0::nat)"
  shows "n \\<noteq> 0"
  sorry

lemma short: "n \\<noteq> 0" if p: "n > (0::nat)" and q: "True" for n :: nat
  using p by simp

lemma by_method:
  assumes m: "n > (0::nat)"
  shows "n \\<noteq> 0"
  by (simp add: m)

lemma by_qed:
  assumes k: "n > (0::nat)"
  shows "n \\<noteq> 0"
proof -
  show ?thesis
    by simp
qed (use k in simp)

end
"""


class UnreadPremises(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        d = Path(self._tmp.name)
        (d / "Asm.thy").write_text(ASM, encoding="utf-8")
        (d / "ROOT").write_text("session Asm = HOL +\n  theories Asm\n",
                                encoding="utf-8")
        cli._ROOT_OVERRIDE = d
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def test_report(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_unused_locals(self.sections, CmdFlags())
        rows = [ln.split() for ln in buf.getvalue().splitlines()]
        self.assertEqual(rows, [
            ["Asm:8", "sig", "assumes", "clamp"],
            ["Asm:9", "i1", "assumes", "clamp"],
            ["Asm:20", "c", "assumes", "indexed"],
            ["Asm:34", "q", "if", "short"],
        ])


if __name__ == "__main__":
    unittest.main()
