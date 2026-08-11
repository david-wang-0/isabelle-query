r"""How a user-typed search pattern is read — `_user_pattern`.

`find` and `grep` take a regex, and an Isabelle name is not regex-safe.  The
failure this fixes was not an error but a SILENCE: `split\<^sub>i` compiles
cleanly and can never match, because `\<` is a literal `<` and the `^` after it
is a start-of-string anchor sitting mid-pattern.  The user is told "No entries
matching" and has no reason to disbelieve it.

Two facts make that worth a rewrite rather than a `--literal` flag:

* `show 'split\<^sub>i_tree'` and `callers 'split\<^sub>i_tree'` already work,
  because they take an exact name.  `find` was the one verb that would not
  accept what the tool itself printed, against CLAUDE.md's "the tool's output
  is valid input".
* over 120 AFP entries, 1,326 of the 1,691 markup-carrying names — 78.4% —
  could not be found by their own printed spelling
  (`scripts/probe_symbol_names.py`).

Only the `\<...>` spans are escaped, so a pattern stays a regex everywhere else.
"""

import io
import os
import sys
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

from isabelle_query.commands import _user_pattern  # noqa: E402

THY = (
    'theory Sym imports Main begin\n'
    'locale split\\<^sub>i_tree =\n'
    '  fixes x :: nat\n'
    'begin\n'
    'end\n'
    'locale split\\<^sub>i_list =\n'
    '  fixes y :: nat\n'
    'begin\n'
    'end\n'
    'lemma split\\<^sub>i_tree_smeq: "True" by simp\n'
    'lemma plain_name: "True" by simp\n'
    'lemma alpha\\<alpha>: "True" by simp\n'
    'end\n'
)


class UserPattern(unittest.TestCase):
    """The rewrite itself, away from any command."""

    def test_markup_is_escaped_so_the_caret_is_not_an_anchor(self):
        out = _user_pattern(r"split\<^sub>i")
        self.assertNotIn("^", out.replace(r"\^", ""))   # no bare anchor left
        import re
        self.assertTrue(re.search(out, r"split\<^sub>i_tree"))

    def test_a_pattern_without_markup_is_untouched(self):
        for p in (r"foo.*bar", r"^anchored", r"a|b", r"[A-Z]\w+"):
            with self.subTest(pattern=p):
                self.assertEqual(_user_pattern(p), p)

    def test_regex_syntax_around_the_markup_still_works(self):
        import re
        pat = re.compile(_user_pattern(r"split\<^sub>i.*_smeq"))
        self.assertTrue(pat.search(r"split\<^sub>i_tree_smeq"))
        self.assertFalse(pat.search(r"split\<^sub>i_tree"))

    def test_grep_style_alternation_is_still_rewritten(self):
        # The rewrite this one was added beside, kept working.
        self.assertEqual(_user_pattern(r"a\|b"), "a|b")

    def test_both_rewrites_compose(self):
        import re
        pat = re.compile(_user_pattern(r"split\<^sub>i_tree\|plain"))
        self.assertTrue(pat.search(r"split\<^sub>i_tree"))
        self.assertTrue(pat.search("plain_name"))


def _find(secs, pattern, mode="names"):
    f = cli.CmdFlags()
    f.mode = mode
    buf = io.StringIO()
    with redirect_stdout(buf):
        cli.cmd_find(secs, pattern, f)
    return buf.getvalue()


class FindBySymbolName(unittest.TestCase):

    def setUp(self):
        self.secs = [section_from(THY, "Sym")]

    def test_the_printed_name_finds_the_entry(self):
        out = _find(self.secs, r"split\<^sub>i_tree")
        self.assertIn(r"split\<^sub>i_tree", out)

    def test_a_markup_prefix_finds_every_entry_sharing_it(self):
        out = _find(self.secs, r"split\<^sub>i")
        for name in (r"split\<^sub>i_tree", r"split\<^sub>i_list",
                     r"split\<^sub>i_tree_smeq"):
            with self.subTest(name=name):
                self.assertIn(name, out)

    def test_regex_still_applies_around_the_markup(self):
        out = _find(self.secs, r"split\<^sub>i.*_smeq")
        self.assertIn(r"split\<^sub>i_tree_smeq", out)
        self.assertNotIn(r"split\<^sub>i_list (", out)

    def test_a_non_caret_symbol_still_works(self):
        # `\<alpha>` carries no metacharacter and matched before the fix; it
        # must not be broken BY the fix, which is the regression risk.
        self.assertIn(r"alpha\<alpha>", _find(self.secs, r"alpha\<alpha>"))

    def test_plain_patterns_are_unaffected(self):
        out = _find(self.secs, "plain")
        self.assertIn("plain_name", out)
        self.assertNotIn(r"split\<^sub>i_tree (", out)


class InvalidPattern(unittest.TestCase):
    """`find` reported a bad regex as a traceback; `grep` always exited 2."""

    def test_find_reports_a_bad_regex_and_exits_2(self):
        f = cli.CmdFlags()
        f.mode = "names"
        with self.assertRaises(SystemExit) as cm:
            with redirect_stdout(io.StringIO()):
                cli.cmd_find([section_from(THY, "Sym")], "(", f)
        self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
