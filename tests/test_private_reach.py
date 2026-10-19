r"""Who may cite a `private` name  [decl-modifier].

Issue #19: once a private lemma is an entry, name resolution has to follow
Isabelle's.  A private name is hidden outside the block that declares it,
under any spelling, so another theory's `helper` cannot be meant by a
citation here.  Two theories each with a private `helper` must not cite each
other's, and a theory importing one of them must not cite it at all.

`A` and `B` each declare a private `helper` and cite it.  `C` imports `A`
and cites a `helper` that it cannot reach: it declares none of its own and
A's is private.  Real files, because visibility reads `imports` off disk.
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


def _thy(name, imports, body):
    return f"theory {name}\nimports {imports}\nbegin\n{body}end\n"


THYS = {
    "A": _thy("A", "Main",
              'context\nbegin\n'
              'private lemma helper: "True" by simp\n'
              'lemma a_user: "True" using helper by simp\n'
              'end\n'),
    "B": _thy("B", "Main",
              'context\nbegin\n'
              'private lemma helper: "True" by simp\n'
              'lemma b_user: "True" using helper by simp\n'
              'end\n'),
    # Imports `A`, yet A's `helper` is out of reach: this `helper` names
    # something else (a local fact, a library lemma).
    "C": _thy("C", "A",
              'lemma c_user: "True" using helper by simp\n'),
}


class Fixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        d = Path(self._tmp.name)
        for name, text in THYS.items():
            (d / f"{name}.thy").write_text(text, encoding="utf-8")
        (d / "ROOT").write_text(
            "session Demo = HOL +\n  theories\n"
            + "".join(f"    {n}\n" for n in THYS), encoding="utf-8")
        cli._ROOT_OVERRIDE = d
        self.sections = cli.load_index()

    def tearDown(self):
        cli._ROOT_OVERRIDE = None
        self._tmp.cleanup()

    def out(self, fn, name, **kw):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn(self.sections, name, CmdFlags(context=0, **kw))
        return buf.getvalue()


class APrivateNameStaysInItsTheory(Fixture):

    def test_callees_name_the_own_theorys_helper(self):
        self.assertIn("helper (LEMMA) — A [L6]",
                      self.out(commands.cmd_callees, "a_user"))
        out = self.out(commands.cmd_callees, "b_user")
        self.assertIn("helper (LEMMA) — B [L6]", out)
        self.assertNotIn("— A", out)

    def test_an_importer_cannot_cite_it(self):
        self.assertNotIn("helper", self.out(commands.cmd_callees, "c_user"))

    def test_callers_group_by_the_own_declaration(self):
        out = self.out(commands.cmd_callers, "helper")
        self.assertIn("2 caller(s) of helper, which has 2 declarations:", out)
        self.assertIn("helper (LEMMA) — A [L6]: 1 caller(s)", out)
        self.assertIn("helper (LEMMA) — B [L6]: 1 caller(s)", out)
        self.assertNotIn("C:", out)

    def test_name_mode_is_unscoped(self):
        out = self.out(commands.cmd_callees, "b_user", reach="name")
        self.assertIn("— A [L6]", out)
        self.assertIn("— B [L6]", out)


if __name__ == "__main__":
    unittest.main()
