r"""The attributes on a declaration's own header  [implicit-use].

`lemma foo [simp, intro!]:` gives `Entry.attrs == ("simp", "intro")`: the
head word of each attribute, so `unused` can tell a fact handed to a tool
(issue #21) from one that is merely transformed (`[rule_format]`).
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query.parsing import attr_heads, extract_entries  # noqa: E402


class TheHeaderAttributes(unittest.TestCase):

    def attrs(self, *lines):
        return [e.attrs for e in extract_entries(list(lines))]

    def test_forms(self):
        self.assertEqual(self.attrs(
            'lemma a [simp, intro!]: "P"', 'lemma (in loc) b[iff]: "P"',
            'lemma c: "P [x]"', 'theorem d [case_names A B, consumes 1]:',
            '  "P"'),
            [("simp", "intro"), ("iff",), (), ("case_names", "consumes")])

    def test_lemmas_binding_on_a_later_line(self):
        self.assertEqual(self.attrs(
            "lemmas a [simp] = TrueI", "  and b [intro] = refl"),
            [("simp",), ("intro",)])

    def test_nested_commas(self):
        self.assertEqual(attr_heads('where x = "f (a, b)", THEN foo, simp'),
                         ("where", "THEN", "simp"))


if __name__ == "__main__":
    unittest.main()
