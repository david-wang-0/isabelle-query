"""Custom outer-syntax command recognition via the header keyword scanner.

An AFP entry may define its own theory commands (AOT's ``AOT_theorem``,
``AOT_define``, ...).  The parser learns these *faithfully* — not by guessing
from the name — by reading the ``keywords "name" :: kind`` clause in a theory
header, which is exactly Isabelle's own keyword table (Pure/Thy/thy_header.ML).

These tests pin three things:
  * the scanner parses the real header grammar (multi-name groups, ``and``
    separators, kindless minor keywords, ``% tag`` specs, glued ``::kind``);
  * only theory-command kinds (thy_goal*/thy_defn/thy_decl*) become entries —
    proof/diagnostic kinds must not;
  * recognising the commands collapses the inflated spans they used to cause
    (the ``largest`` bug: an unbounded built-in entry swallowing a run of
    unrecognised custom commands).
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

scan = cli.scan_keywords


def names_of(snippet):
    return [e.name for e in section_from(snippet).entries]


def entries_of(snippet):
    return [(e.tag, e.name) for e in section_from(snippet).entries]


class ScanKeywordBlock(unittest.TestCase):
    """The header grammar, Pure/Thy/thy_header.ML:154-164."""

    def _scan(self, block):
        # wrap a bare `keywords ...` block in a minimal header
        hdr = ("theory T imports Main\n  " + block + "\nbegin").splitlines()
        return scan(hdr)

    def test_kind_to_family(self):
        # thy_goal* -> THEOREM (proof-bearing), thy_defn/thy_decl* -> DEF
        self.assertEqual(self._scan('keywords "g" :: thy_goal'), {"g": "THEOREM"})
        self.assertEqual(self._scan('keywords "s" :: thy_goal_stmt'), {"s": "THEOREM"})
        self.assertEqual(self._scan('keywords "d" :: thy_defn'), {"d": "DEF"})
        self.assertEqual(self._scan('keywords "c" :: thy_decl'), {"c": "DEF"})
        self.assertEqual(self._scan('keywords "b" :: thy_decl_block'), {"b": "DEF"})

    def test_non_theory_kinds_are_skipped(self):
        # proof / diagnostic / document / quasi_command introduce no fact
        for kind in ("prf_decl", "prf_goal", "diag", "document_body",
                     "quasi_command", "thy_load", "qed_block"):
            self.assertEqual(self._scan(f'keywords "x" :: {kind}'), {},
                             f"{kind} should create no command")

    def test_multiple_names_share_one_kind(self):
        self.assertEqual(self._scan('keywords "a" "b" "c" :: thy_decl'),
                         {"a": "DEF", "b": "DEF", "c": "DEF"})

    def test_and_separated_decls(self):
        got = self._scan('keywords "named_simpset" :: thy_decl '
                         'and "print_named_simpset" :: diag')
        self.assertEqual(got, {"named_simpset": "DEF"})  # diag dropped

    def test_kindless_minor_keywords_skipped(self):
        # `ensures`/`returns`/`variant` are syntax of program_spec, not commands
        got = self._scan('keywords "ensures"\n     and "returns" "variant"\n'
                         '     and "program_spec" :: thy_goal')
        self.assertEqual(got, {"program_spec": "THEOREM"})

    def test_tag_value_is_not_a_name(self):
        # the `% "proof"` tag value must not be captured as a command name
        self.assertEqual(self._scan('keywords "@qed" :: prf_block % "proof"'), {})
        self.assertEqual(self._scan('keywords "g" :: thy_goal % "proof"'),
                         {"g": "THEOREM"})

    def test_glued_colon_kind(self):
        self.assertEqual(self._scan('keywords "named_rules"::thy_decl'),
                         {"named_rules": "DEF"})

    def test_no_keywords_clause(self):
        self.assertEqual(scan("theory T imports Main begin".splitlines()), {})


class CustomCommandEntries(unittest.TestCase):
    """A theory that declares *and* uses its own commands (single-file)."""

    def test_goal_command_quoted_label(self):
        snippet = r'''theory T imports Main
  keywords "AOT_theorem" :: thy_goal
begin
AOT_theorem "foo:1": \<open>p \<rightarrow> p\<close>
  by simp
end
'''
        self.assertEqual(entries_of(snippet), [("THEOREM", "foo:1")])

    def test_decl_command_bare_name(self):
        snippet = r'''theory T imports Main
  keywords "AOT_define" :: thy_decl
begin
AOT_define Bar :: \<open>nat\<close> (\<open>B\<close>)
end
'''
        self.assertEqual(entries_of(snippet), [("DEF", "Bar")])

    def test_proof_command_makes_no_entry(self):
        # a prf_* command used at top level must not be indexed
        snippet = r'''theory T imports Main
  keywords "AOT_theorem" :: thy_goal and "AOT_show" :: prf_asm_goal % "proof"
begin
AOT_theorem "t:1": \<open>p\<close>
AOT_show \<open>p\<close> by simp
end
'''
        self.assertEqual(names_of(snippet), ["t:1"])


class CrossTheoryUnion(unittest.TestCase):
    """A theory that *uses* a command another theory *declares*: the table is
    threaded in explicitly (as load_index's header pre-scan would supply it)."""

    def test_use_without_local_declaration(self):
        # no `keywords` clause here — the command is known only via `custom`
        snippet = r'''theory T imports Main begin
AOT_theorem "bar:2": \<open>q\<close> by simp
end
'''
        ents = cli.extract_entries(snippet.splitlines(),
                                   custom={"AOT_theorem": "THEOREM"})
        self.assertEqual([(e.tag, e.name) for e in ents], [("THEOREM", "bar:2")])

    def test_unknown_command_stays_unrecognised(self):
        snippet = r'''theory T imports Main begin
AOT_theorem "bar:2": \<open>q\<close> by simp
end
'''
        # without the table, the custom command is invisible (no entry)
        self.assertEqual(cli.extract_entries(snippet.splitlines()), [])


class SpanCollapse(unittest.TestCase):
    """The user-visible payoff: recognising the custom commands bounds the
    spans they used to inflate (the `largest`/`show` over-reporting)."""

    SNIPPET = r'''theory T imports Main
  keywords "AOT_theorem" :: thy_goal
begin
theorem first: "True" by simp

AOT_theorem "a:1": \<open>X\<close> by simp

AOT_theorem "a:2": \<open>Y\<close> by simp

AOT_theorem "a:3": \<open>Z\<close> by simp
end
'''

    def test_builtin_span_is_bounded_by_next_custom_command(self):
        sec = section_from(self.SNIPPET)
        by_name = {e.name: e for e in sec.entries}
        self.assertEqual(set(by_name), {"first", "a:1", "a:2", "a:3"})
        first = by_name["first"]
        # first must end before the first AOT_theorem, not swallow the run
        self.assertLess(first.thy_end, by_name["a:1"].thy_line)
        self.assertLessEqual(first.thy_end - first.thy_line, 3)


if __name__ == "__main__":
    unittest.main()
