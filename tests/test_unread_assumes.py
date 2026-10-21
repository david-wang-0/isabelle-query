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


CASC = """\
theory Casc
  imports Main
begin

lemma clamp:
  assumes lang: "n > (0::nat)"
      and sig: "n < 10"
      and tge: "True"
  shows "n \\<noteq> 0"
  using lang tge by simp

theorem diag:
  assumes lang: "n > (0::nat)"
      and sig: "n < 10"
      and tge: "True"
  shows "n \\<noteq> 0"
  by (rule clamp [OF lang sig tge])

theorem diag2:
  assumes s2: "n < (10::nat)" and l2: "n > 0"
  shows "n \\<noteq> 0"
  using s2 by (rule clamp [OF l2 _ TrueI])

theorem top:
  assumes s3: "n < (10::nat)" and l3: "n > 0"
  shows "n \\<noteq> 0"
  using diag [OF l3 s3 TrueI] .

theorem keeps:
  assumes s4: "n < (10::nat)" and l4: "n > 0"
  shows "n \\<noteq> 0 \\<and> n < 10"
  using clamp [OF l4 s4 TrueI] s4 by simp

locale L =
  fixes x :: nat
  assumes xpos: "x > 0"
begin

lemma inner:
  assumes u: "x < 5" and w: "True"
  shows "x \\<noteq> 0"
  using xpos w by simp

lemma inner_user:
  assumes u2: "x < 5"
  shows "x \\<noteq> 0"
  using inner [OF u2 TrueI] .

end

theorem outer_user:
  assumes p: "0 < (1::nat)" and u3: "(1::nat) < 5"
  shows "(1::nat) \\<noteq> 0"
  using L.inner [OF p u3 TrueI] by simp

end
"""


class ThePremiseCascade(unittest.TestCase):
    """`-r`: a caller's label read only to discharge a dead premise by
    `[OF …]` position is dead too, to a fixed point.  `diag2` puts `_` in
    that position, and `keeps` reads its label again, so neither is.  Inside
    locale `L` positions are the statement's; outside it they are not."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        d = Path(self._tmp.name)
        (d / "Casc.thy").write_text(CASC, encoding="utf-8")
        (d / "ROOT").write_text("session Casc = HOL +\n  theories Casc\n",
                                encoding="utf-8")
        cli._ROOT_OVERRIDE = d
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def rows(self, **kw):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            commands.cmd_unused_locals(self.sections, CmdFlags(**kw))
        return [" ".join(ln.split()) for ln in buf.getvalue().splitlines()]

    def test_direct_only(self):
        self.assertEqual(self.rows(), ["Casc:7 sig assumes clamp",
                                       "Casc:40 u assumes inner"])

    def test_cascade(self):
        self.assertEqual(self.rows(recursive=True), [
            "Casc:7 sig assumes clamp",
            "Casc:40 u assumes inner",
            "Casc:14 sig assumes diag fed only clamp.sig",
            "Casc:45 u2 assumes inner_user fed only inner.u",
            "Casc:25 s3 assumes top fed only diag.sig",
        ])

    def test_no_cascade_across_a_locale_boundary(self):
        # From outside `L`, `L.inner`'s rule starts with `xpos`: position 1
        # is not `u`, so `outer_user`'s `p` must not read as feeding it.
        self.assertFalse(any("outer_user" in r
                             for r in self.rows(recursive=True)))

    def test_keep_stops_it(self):
        self.assertEqual(
            self.rows(recursive=True, keep=frozenset({"sig", "u"})),
            ["No unread local names found."])


if __name__ == "__main__":
    unittest.main()
