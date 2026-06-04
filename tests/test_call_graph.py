"""Call-graph construction — the linear tokenise-and-hash builder must agree
with the brute-force per-name oracle, and must honour the same exclusions
(def sites, prose text blocks, antiquotations).
"""

import os
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
        # a recursive fun references itself only inside its own span
        sec = section_from(r'''theory T imports Main begin
fun f :: "nat \<Rightarrow> nat" where
"f 0 = 0" |
"f (Suc n) = f n"
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertNotIn("f", g.callers.get("f", set()))   # no self-edge

    def test_prose_mention_is_not_a_call(self):
        sec = section_from(r'''theory T imports Main begin
definition foo :: "nat" where "foo = 0"
text \<open>Here we discuss foo at length in prose.\<close>
lemma bar: "True" by auto
end
''')
        g = self.assertMatchesOracle([sec])
        self.assertEqual(g.callers["foo"], set())          # text block skipped

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


if __name__ == "__main__":
    unittest.main()
