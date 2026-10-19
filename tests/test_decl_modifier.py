r"""`private` / `qualified` declarations are entries [decl-modifier].

Issue #19: Pure.thy declares both words as `before_command` keywords, so
`private lemma helper` IS a lemma.  The grammar did not read past the
modifier, so it was no entry — missing from `outline`, `show`, `callees` and
`unused` — and, a declaration ending at the next one recognised, its lines
went to the entry above: `sorry` charged `open_goal`'s open goal to
`public_user`, which has a complete proof.

The fixture is the issue's, line for line.
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
from isabelle_query.parsing import extract_entries  # noqa: E402

PRIV = """\
theory Priv
  imports Main
begin

definition foo :: "nat \\<Rightarrow> nat" where "foo n = n + 1"

context
begin

private lemma helper: "foo n > n"
  by (simp add: foo_def)

lemma public_user: "foo n \\<noteq> n"
  using helper by (metis less_irrefl)

private lemma open_goal: "foo n = n"
  sorry

end

qualified lemma qual_lemma: "foo 0 = 1"
  by (simp add: foo_def)

lemma plain: "foo 1 = 2"
  by (simp add: foo_def)

end
"""


class Fixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        d = Path(self._tmp.name)
        (d / "Priv.thy").write_text(PRIV, encoding="utf-8")
        (d / "ROOT").write_text("session Priv = HOL +\n  theories Priv\n",
                                encoding="utf-8")
        cli._ROOT_OVERRIDE = d
        self.sections = cli.load_index()
        self.by_name = {e.name: e for e in self.sections[0].entries}

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def out(self, fn, *args):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn(self.sections, *args)
        return buf.getvalue()


class TheModifiedDeclarationIsAnEntry(Fixture):

    def test_every_declaration_is_indexed(self):
        self.assertEqual(list(self.by_name), [
            "foo", "helper", "public_user", "open_goal", "qual_lemma",
            "plain"])

    def test_visibility(self):
        self.assertEqual(
            {n: e.visibility for n, e in self.by_name.items()},
            {"foo": "", "helper": "private", "public_user": "",
             "open_goal": "private", "qual_lemma": "qualified",
             "plain": ""})

    def test_spans_no_longer_absorb(self):
        # `public_user` is lines 13..14 and its trailing blank, and no more.
        e = self.by_name["public_user"]
        self.assertEqual((e.thy_line, e.body_end_line), (13, 14))
        self.assertEqual(self.by_name["open_goal"].thy_line, 16)

    def test_sorry_names_its_own_entry(self):
        out = self.out(commands.cmd_sorry, False)
        self.assertIn("Priv.thy:17  open_goal (LEMMA)", out)
        self.assertNotIn("public_user", out)

    def test_callees_sees_the_private_lemma(self):
        out = self.out(commands.cmd_callees, "public_user", CmdFlags())
        self.assertIn("helper (LEMMA) — Priv [L10]", out)

    def test_cited_private_lemma_is_not_unused(self):
        # A private lemma is the kind that goes dead, so `unused` must judge
        # it: `helper` is cited by `public_user`, `open_goal` by nothing.
        out = self.out(commands.cmd_unused, CmdFlags())
        self.assertNotIn("helper", out)
        self.assertIn("open_goal", out)

    def test_show_and_outline_print_the_modifier(self):
        show = self.out(commands.cmd_show, "helper", CmdFlags())
        self.assertIn("--- helper (LEMMA, private) — Priv.thy", show)
        outline = self.out(commands.cmd_outline, "Priv", CmdFlags())
        self.assertIn("LEMMA    private helper  (10..12", outline)
        self.assertIn("LEMMA    qualified qual_lemma", outline)


class EveryEntryOfTheCommand(unittest.TestCase):
    """The modifier belongs to the COMMAND, so every entry one command mints
    carries it, including one that starts on a later line."""

    def test_lemmas_and_group(self):
        es = extract_entries([
            "private lemmas a = TrueI", "  and b = refl",
            "lemma c: True by simp"])
        self.assertEqual([(e.name, e.visibility) for e in es],
                         [("a", "private"), ("b", "private"), ("c", "")])

    def test_definition_and_indented(self):
        es = extract_entries([
            "  qualified definition f :: nat where \"f = 0\"",
            "definition g :: nat where \"g = 0\""])
        self.assertEqual([(e.name, e.tag, e.visibility) for e in es],
                         [("f", "DEF", "qualified"), ("g", "DEF", "")])

    def test_modifier_alone_is_not_a_declaration(self):
        # `private` in prose position, or before a non-declaring command,
        # mints nothing.
        self.assertEqual(extract_entries(["private notation foo (\"F\")"]), [])


if __name__ == "__main__":
    unittest.main()
