"""Call-graph construction — the linear tokenise-and-hash builder must agree
with the brute-force per-name oracle, and must honour the same exclusions
(def sites, prose text blocks, antiquotations).
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import (cli, section_from, sections_from,  # noqa: E402
                     brute_force_call_graph)


def build(*sections):
    return cli._build_call_graph(list(sections))


class CallGraphEdges(unittest.TestCase):
    def assertMatchesOracle(self, sections):
        fast = cli._build_call_graph(sections)
        ref = brute_force_call_graph(sections)
        self.assertEqual(fast.callers, ref.callers)
        self.assertEqual(fast.callees, ref.callees)
        return fast

    def test_bare_reference(self):
        sec = section_from(r'''theory T imports Main begin
definition foo :: "nat" where "foo = 0"
lemma bar: "foo = foo" by simp
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertIn("bar", g.callers["foo"])     # bar's statement cites foo
        self.assertIn("foo", g.callees["bar"])

    def test_quoted_special_name_reference(self):
        sec = section_from(r'''theory T imports Main begin
lemma "rule-x:1": "True" by auto
lemma usesit: "True" using "rule-x:1" by auto
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertEqual(g.callers["rule-x:1"], {"usesit"})

    def test_symbolic_name_reference(self):
        # references to a name containing \<...> symbols must be found:
        # the tokenizer has to keep `\<psi>` whole, not split it away
        sec = section_from(r'''theory T imports Main begin
definition \<psi> :: "nat" where "\<psi> = 0"
lemma uses_psi: "\<psi> = \<psi>" by (simp add: \<psi>_def)
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertIn("uses_psi", g.callers[r"\<psi>"])

    def test_definition_site_excluded(self):
        # a recursive fun references itself only inside its own span.
        # (Name is 2+ chars so it survives the default single-char drop and
        # the self-edge exclusion is what's actually under test.)
        sec = section_from(r'''theory T imports Main begin
fun ff :: "nat \<Rightarrow> nat" where
"ff 0 = 0" |
"ff (Suc n) = ff n"
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertNotIn("ff", g.callers.get("ff", set()))   # no self-edge

    def test_prose_mention_is_not_a_call(self):
        sec = section_from(r'''theory T imports Main begin
definition foo :: "nat" where "foo = 0"
text \<open>Here we discuss foo at length in prose.\<close>
lemma bar: "True" by auto
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertEqual(g.callers["foo"], set())          # text block skipped

    def test_comment_mention_is_not_a_call(self):
        # A name cited only inside a `\<comment> \<open>...\<close>` annotation is
        # documentation, not a proof-body call — the same rule as a `text`
        # block, and as grep/methods.  (Before this was fixed, the call graph
        # alone still counted it: the `\<comment>` line was not skipped.)
        sec = section_from(r'''theory T imports Main begin
definition foo :: "nat" where "foo = 0"
lemma bar: "True"
  \<comment> \<open>this step is by analogy with foo\<close>
  by auto
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertEqual(g.callers["foo"], set())          # \<comment> skipped

    def test_substring_is_not_a_call(self):
        # `foo` must not match inside `foobar` / `foo_def`
        sec = section_from(r'''theory T imports Main begin
definition foo :: "nat" where "foo = 0"
definition foobar :: "nat" where "foobar = 1"
lemma bar: "foobar = foobar" using foo_def by simp
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertNotIn("bar", g.callers["foo"])          # foobar/foo_def != foo
        self.assertIn("bar", g.callers["foobar"])

    def test_cross_theory_reference(self):
        secs = sections_from({
            "A": r'''theory A imports Main begin
definition base :: "nat" where "base = 0"
end
''',
            "B": r'''theory B imports A begin
lemma uses_base: "base = base" by (simp add: base_def)
end
''',
        })
        g = self.assertMatchesOracle(secs)
        self.assertIn("uses_base", g.callers["base"])

    def test_special_name_substring_is_not_a_call(self):
        # A quoted special-char name must not be matched inside a *longer*
        # quoted name it is a substring of: `num:1` ⊄ `eq-num:1`.  This is
        # the over-match the AOT keyword scanner surfaced at corpus scale.
        sec = section_from(r'''theory T imports Main begin
lemma "num:1": "True" by auto
lemma "eq-num:1": "True" by auto
lemma usesit: "True" using "eq-num:1" by auto
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertEqual(g.callers["eq-num:1"], {"usesit"})
        self.assertEqual(g.callers.get("num:1", set()), set())

    def test_symbolic_name_not_glued_into_longer_symbol(self):
        # `\<gamma>` must not be matched inside `\<gamma>\<^sub>1`.
        sec = section_from(r'''theory T imports Main begin
definition \<gamma> :: "nat" where "\<gamma> = 0"
definition \<gamma>\<^sub>1 :: "nat" where "\<gamma>\<^sub>1 = 1"
lemma usesit: "\<gamma>\<^sub>1 = \<gamma>\<^sub>1" by simp
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertIn("usesit", g.callers[r"\<gamma>\<^sub>1"])
        self.assertEqual(g.callers.get(r"\<gamma>", set()), set())


class DropShortNames(unittest.TestCase):
    """The `--drop-names-upto L` threshold: a length-1 name (`f`) is a term
    variable in nearly every proof, so by default it is not a citation node;
    a length-2 name (`ff`) is kept.  `drop_upto=0` disables the length filter.
    Both the fast builder and the oracle honour the same `drop_upto`."""

    SNIPPET = r'''theory T imports Main begin
definition f :: "nat" where "f = 0"
definition ff :: "nat" where "ff = 0"
lemma uses_both: "f = ff" by (simp add: f_def ff_def)
end
'''

    def test_single_char_dropped_by_default(self):
        sec = section_from(self.SNIPPET)
        g = cli._build_call_graph([sec])               # default drop_upto=1
        self.assertNotIn("f", g.all_names)             # length-1 excluded
        self.assertIn("ff", g.all_names)               # length-2 kept
        self.assertIn("uses_both", g.callers["ff"])

    def test_drop_upto_zero_keeps_single_char(self):
        sec = section_from(self.SNIPPET)
        g = cli._build_call_graph([sec], drop_upto=0)
        self.assertIn("f", g.all_names)
        self.assertIn("uses_both", g.callers["f"])

    def test_oracle_parity_at_each_threshold(self):
        sec = section_from(self.SNIPPET)
        for drop in (0, 1, 2):
            fast = cli._build_call_graph([sec], drop_upto=drop)
            ref = brute_force_call_graph([sec], drop_upto=drop)
            self.assertEqual(fast.callers, ref.callers, f"drop_upto={drop}")
            self.assertEqual(fast.callees, ref.callees, f"drop_upto={drop}")

    def test_drop_upto_two_drops_two_char(self):
        sec = section_from(self.SNIPPET)
        g = cli._build_call_graph([sec], drop_upto=2)
        self.assertNotIn("f", g.all_names)
        self.assertNotIn("ff", g.all_names)            # length-2 now dropped too


class WordBoundary(unittest.TestCase):
    """`_isa_word_pattern` matches a name only where a real citation can be —
    the precision that keeps the call graph from inventing edges."""

    def m(self, name, text):
        return bool(re.search(cli._isa_word_pattern(name), text))

    def test_plain_identifier_is_prime_and_substring_aware(self):
        self.assertTrue(self.m("foo", "using foo by simp"))
        self.assertTrue(self.m("foo", "foo[OF x]"))        # attribute reference
        self.assertFalse(self.m("foo", "foobar"))          # substring
        self.assertFalse(self.m("foo", "foo' = foo'"))     # prime extends the name

    def test_special_char_name_only_matches_quoted(self):
        self.assertTrue(self.m("num:1", '"num:1"[THEN x]'))
        self.assertFalse(self.m("num:1", '"eq-num:1"'))    # substring of longer name
        self.assertFalse(self.m("denote=:4", '"denote=:4[3]"'))

    def test_symbolic_name_not_glued(self):
        self.assertTrue(self.m(r"\<gamma>", r"rule \<gamma> here"))
        self.assertFalse(self.m(r"\<gamma>", r"\<gamma>\<^sub>1"))   # followed by a symbol
        self.assertTrue(self.m("foo", r"foo\<gamma>"))     # ASCII run abutting a symbol


if __name__ == "__main__":
    unittest.main()
