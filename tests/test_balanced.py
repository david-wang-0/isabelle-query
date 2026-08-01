"""The shared balanced-delimiter scanner (`parsing._balanced_end`).

A depth counter is the one-symbol pushdown automaton that a *non-regular*
nesting construct needs and a regular expression cannot supply.  One engine now
serves every such site — the character paren `(`/`)`, the multi-byte symbol
cartouche `\\<open>`/`\\<close>`, and (quote-aware) an induction method's
argument list — so it is tested here directly rather than only through its
callers.  Every expected index is hand-computed against the string below it.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import parsing  # noqa: E402

_bal = parsing._balanced_end


class BalancedEnd(unittest.TestCase):
    def test_flat_paren_returns_index_past_close(self):
        # "(a b)" — ')' at index 4, one past is 5.
        self.assertEqual(_bal("(a b) tail", "(", ")"), 5)

    def test_nested_paren_matches_the_outermost(self):
        # "((a) b)" — the inner pair pushes to depth 2; only the final ')'
        # (index 6) returns depth to 0, one past is 7.
        self.assertEqual(_bal("((a) b)", "(", ")"), 7)

    def test_unbalanced_returns_minus_one(self):
        self.assertEqual(_bal("(a b", "(", ")"), -1)

    def test_start_offset_begins_at_the_opener(self):
        # scanning begins at index 2, the '(' — "xx(a)" closes at 4, past is 5.
        self.assertEqual(_bal("xx(a)", "(", ")", start=2), 5)

    def test_multichar_cartouche_token(self):
        # startswith(), not ==, lets a 7-byte `\<open>` be one delimiter.
        s = "\\<open>x\\<close>"
        self.assertEqual(_bal(s, "\\<open>", "\\<close>"), len(s))

    def test_nested_cartouche(self):
        s = "\\<open>a\\<open>b\\<close>c\\<close>"
        self.assertEqual(_bal(s, "\\<open>", "\\<close>"), len(s))

    def test_quote_unaware_closes_at_a_quoted_paren(self):
        # '("a)b")' — the ')' inside the quotes sits at index 3; without
        # quote-awareness it is read as the close, one past is 4.
        self.assertEqual(_bal('("a)b")', "(", ")"), 4)

    def test_quote_aware_skips_a_quoted_paren(self):
        # ... but quote-aware ignores everything inside "..." (a stray paren is
        # term data, not structure), so the real close is the last ')' at 6.
        self.assertEqual(_bal('("a)b")', "(", ")", quote_aware=True), 7)


class BalancedFacades(unittest.TestCase):
    """The two named helpers are thin, intention-revealing façades over the
    engine — one for the common paren case, one for the cartouche pair."""

    def test_paren_facade(self):
        self.assertEqual(parsing._balanced_paren_end("(a(b)c)"), 7)
        self.assertEqual(parsing._balanced_paren_end("(a"), -1)

    def test_cartouche_facade(self):
        s = "\\<open>a\\<open>b\\<close>c\\<close>"
        self.assertEqual(parsing._balanced_cartouche_end(s), len(s))


if __name__ == "__main__":
    unittest.main()
