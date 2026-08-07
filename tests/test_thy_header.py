r"""`parse_thy_imports` reads the whole theory header, and only the header.

Regression for `[thy-header]`.  The clause was found by regex-searching the
first 50 lines for `imports ... begin`, which failed three ways:

* **the window** — `section`/`text` blocks may legally precede the `theory`
  command, and an AFP title-and-history block routinely pushes the header past
  any constant.  62 of the AFP's 9,604 theories lost their imports entirely
  (worst: `Cook_Levin/Basics.thy`, line 199), which is a wrong `deps` answer
  *and* a silent hole in the discovery closure `session_theories` builds.
* **the terminator** — `keywords` and `abbrevs` are header clauses that follow
  `imports`, so scanning to `begin` swallowed them.  105 AFP theories declare
  a `keywords` block; `AutoCorres` "imported" `keywords`, `autocorres`, `::`
  and `thy_decl`.
* **nested comments** — Isabelle's `(* *)` nest, and a non-greedy regex stops
  at the first `*)`.  `Universal_Turing_Machine.GeneratedCode` reported an
  import literally named `*)`.

Verified against Isabelle's own `theory/parents` export over 11 built
sessions: 563/563 theories agree (`scripts/probe_parents_oracle.py`).
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

parse_thy_imports = cli.parse_thy_imports


class HeaderParsing(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def _imports(self, text):
        p = self.dir / "T.thy"
        p.write_text(text, encoding="utf-8")
        return parse_thy_imports(p)

    # --- the window ------------------------------------------------------
    def test_header_past_a_long_preamble_is_found(self):
        # 80 lines of title block, as `Cook_Levin/Basics.thy` carries.
        preamble = "(*\n" + "\n".join(f"   line {i}" for i in range(80)) + "\n*)\n"
        self.assertEqual(
            self._imports(preamble + 'theory T\n  imports Bar\nbegin\nend\n'),
            ["Bar"])

    def test_section_and_text_may_precede_the_theory_command(self):
        pre = ('section \\<open>Title\\<close>\n'
               'text \\<open>' + "prose\n" * 60 + '\\<close>\n')
        self.assertEqual(
            self._imports(pre + 'theory T\n  imports Bar\nbegin\nend\n'),
            ["Bar"])

    def test_prose_before_the_header_cannot_supply_a_phantom_clause(self):
        # The words are inside a cartouche, so they are not syntax.
        pre = 'text \\<open>we import Baz here: imports Baz begin\\<close>\n'
        self.assertEqual(
            self._imports(pre + 'theory T\n  imports Bar\nbegin\nend\n'),
            ["Bar"])

    # --- the terminator --------------------------------------------------
    def test_keywords_block_is_not_imported(self):
        self.assertEqual(self._imports(
            'theory T\n  imports Main\n'
            '  keywords "autocorres" :: thy_decl and "declare_prototype"'
            ' :: thy_goal_stmt\nbegin\nend\n'),
            ["Main"])

    def test_abbrevs_block_is_not_imported(self):
        self.assertEqual(self._imports(
            'theory T\n  imports Main Other\n'
            '  abbrevs "===" = "\\<equiv>"\nbegin\nend\n'),
            ["Main", "Other"])

    # --- comments --------------------------------------------------------
    def test_nested_comment_in_the_clause(self):
        self.assertEqual(self._imports(
            'theory T\n'
            '  imports A\n'
            '(*        "HOL-Library.Skip" (* see codegen.pdf *) *)\n'
            '          "HOL-Library.Code_Binary_Nat" (* keep *)\n'
            'begin\nend\n'),
            ["A", "HOL-Library.Code_Binary_Nat"])

    def test_comment_between_tokens_still_separates_them(self):
        self.assertEqual(
            self._imports('theory T imports A(* c *)B begin\nend\n'),
            ["A", "B"])

    # --- shapes that must survive ----------------------------------------
    def test_quoted_qualified_name(self):
        self.assertEqual(
            self._imports('theory T\n  imports "HOL-Library.FuncSet"\n'
                          'begin\nend\n'),
            ["HOL-Library.FuncSet"])

    def test_relative_path_import(self):
        # FSM_Tests writes `imports "../FSM"`; the token must survive whole.
        self.assertEqual(
            self._imports('theory T\nimports "../FSM"\nbegin\nend\n'),
            ["../FSM"])

    def test_order_is_preserved(self):
        self.assertEqual(
            self._imports('theory T\n  imports C A B\nbegin\nend\n'),
            ["C", "A", "B"])

    def test_one_line_header(self):
        self.assertEqual(self._imports('theory T imports Main begin\nend\n'),
                         ["Main"])

    # --- no clause at all ------------------------------------------------
    def test_theory_without_imports_yields_nothing(self):
        # `theory Pure begin`.  A bare `imports` further down (here inside a
        # locale) must not pair with some later `begin`.
        self.assertEqual(self._imports(
            'theory T begin\n'
            'locale L begin\n'
            'text \\<open>imports\\<close>\n'
            'end\nend\n'),
            [])

    def test_missing_file_yields_nothing(self):
        self.assertEqual(parse_thy_imports(self.dir / "absent.thy"), [])


class StripBlockComments(unittest.TestCase):
    """The nesting-aware stripper, in isolation."""

    def setUp(self):
        from isabelle_query import common
        self.strip = common._strip_block_comments

    def test_nested_pair_is_removed_whole(self):
        self.assertEqual(self.strip("a (* x (* y *) z *) b").split(), ["a", "b"])

    def test_stray_closer_is_left_alone(self):
        self.assertEqual(self.strip("a *) b"), "a *) b")

    def test_unterminated_comment_swallows_the_rest(self):
        self.assertEqual(self.strip("a (* b").strip(), "a")

    def test_text_without_comments_is_returned_unchanged(self):
        self.assertIs(self.strip("plain text"), "plain text")

    def test_two_separate_comments(self):
        self.assertEqual(self.strip("a (* x *) b (* y *) c").split(),
                         ["a", "b", "c"])


class StripCartouches(unittest.TestCase):
    """The token-driven rewrite must match the old per-character walk."""

    def setUp(self):
        from isabelle_query import common
        self.strip = common._strip_cartouches

    def _reference(self, text):
        out, i, depth = [], 0, 0
        while i < len(text):
            if text.startswith(r"\<open>", i):
                depth += 1
                i += len(r"\<open>")
            elif text.startswith(r"\<close>", i):
                depth = max(0, depth - 1)
                i += len(r"\<close>")
            else:
                if depth == 0:
                    out.append(text[i])
                i += 1
        return "".join(out)

    def test_matches_the_reference_walk(self):
        cases = [
            r"a \<open>b\<close> c",
            r"a \<open>b \<open>n\<close> d\<close> e",
            r"a \<close> b",                      # stray closer
            r"a \<open>b",                        # unterminated
            "no cartouches here",
            r"\<open>\<close>",
        ]
        for c in cases:
            with self.subTest(c=c):
                self.assertEqual(self.strip(c), self._reference(c))


if __name__ == "__main__":
    unittest.main()
