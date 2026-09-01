r"""A `\<...>` token's body is the symbol's name, never a fact's.

The citation scan takes `[\w']+` runs off each line so that a bare name abutting
a symbol — `iso_transaction` in `iso_transaction\<^sub>h` — is still found.  It
reached straight into the symbol itself:

    \<lambda>  ->  lambda        \<le>       ->  le
    \<close>   ->  close         \<subseteq> ->  subseteq
    \<^sub>    ->  sub           \<and>      ->  and

and the AFP declares entries with exactly those names — **7 named `lambda`, 37
`le`, 27 `sub`, 35 `set`, 9 `close`** — so every `\<lambda>` written anywhere in
the corpus was recorded as a citation of all seven.

Whole AFP, `scripts/probe_citation_reach.py`, name-scoped so the effect is not
confounded with `[citation-reach]`:

    edges          3,020,075 -> 2,404,456   (-615,619, -20.4%)
    callers lambda   123,954 ->     1,184
    callers le       128,930 ->     2,561
    callers sub      331,343 ->     3,324

Closure-scoped the delta is only 14,150 edges (1.24%), because a symbol appears
everywhere and most of its spurious edges already crossed a closure boundary.
That is why visibility scoping masked this and could not fix it: the spurious
edges INSIDE a closure survived, and those are the ones a single-session user
sees.

Fixed at both attribution points, as `[citation-reach]` had to be.  The graph
blanks symbol tokens before its word pass; `_isa_word_pattern` (the single-name
scan behind `callers`) grows two lookbehinds, because a plain run sits between
non-`[\w']` characters when it is the inside of a symbol too.

`TheGuardsThatWordScanningExistsFor` is the class that matters: the word pass
was added for a reason, and narrowing it must not undo that reason.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402
from isabelle_query import graph  # noqa: E402
from isabelle_query.parsing import _isa_word_pattern  # noqa: E402

# Entries named after symbol bodies, cited nowhere — every `\<lambda>` below is
# a symbol, not a reference.
SYMBOLS = r'''theory Sym
imports Main
begin

definition lambda :: "nat" where "lambda = 0"
definition le :: "nat" where "le = 1"
definition sub :: "nat" where "sub = 2"
definition close :: "nat" where "close = 3"

lemma writes_symbols: "\<forall>x\<^sub>1. (\<lambda>y. y) x\<^sub>1 \<le> x\<^sub>1"
  using \<open>True\<close> by simp

end
'''

# The two things the word pass was added for.
GUARDS = r'''theory Guard
imports Main
begin

definition iso_transaction :: "nat" where "iso_transaction = 0"
definition "merge_rt_F\<^sub>m" :: "nat" where "merge_rt_F\<^sub>m = 1"
definition inside :: "nat" where "inside = 2"

lemma abuts: "iso_transaction\<^sub>h = iso_transaction\<^sub>h" by simp
lemma symbolic: "merge_rt_F\<^sub>m = merge_rt_F\<^sub>m" by simp
lemma in_cartouche: \<open>inside = inside\<close> by simp

end
'''


def callers_in(src, theory):
    g = graph._build_call_graph([section_from(src, theory)])
    return g.callers


class ASymbolBodyIsNotACitation(unittest.TestCase):

    def setUp(self):
        self.callers = callers_in(SYMBOLS, "Sym")

    def test_lambda_is_not_cited_by_a_lambda_symbol(self):
        self.assertEqual(self.callers["lambda"], set())

    def test_le_is_not_cited_by_a_le_symbol(self):
        self.assertEqual(self.callers["le"], set())

    def test_sub_is_not_cited_by_a_subscript_control(self):
        self.assertEqual(self.callers["sub"], set())

    def test_close_is_not_cited_by_a_cartouche_delimiter(self):
        # `\<open>` / `\<close>` are the commonest symbols in modern Isar, and
        # `close` is a perfectly ordinary name for a lemma about closure.
        self.assertEqual(self.callers["close"], set())


class TheGuardsThatWordScanningExistsFor(unittest.TestCase):
    """Narrowing the word pass must not undo what it was added for."""

    def setUp(self):
        self.callers = callers_in(GUARDS, "Guard")

    def test_a_bare_name_abutting_a_symbol_is_still_found(self):
        # The reason `word_re` exists beside `sym_re`: a plain run glued to a
        # symbol would otherwise be swallowed into the symbolic token.
        self.assertIn("abuts", self.callers["iso_transaction"])

    def test_a_symbolic_name_is_still_one_token(self):
        # The reason `sym_re` exists beside `word_re`.
        self.assertIn("symbolic", self.callers["merge_rt_F\\<^sub>m"])

    def test_a_name_inside_a_cartouche_is_still_cited(self):
        # Blanking the DELIMITERS must not blank what they contain: a
        # cartouche holds inner syntax, and a name there is a real use.
        self.assertIn("in_cartouche", self.callers["inside"])


class TheSingleNameScanAgrees(unittest.TestCase):
    r"""`_isa_word_pattern` is the `callers` path; it had the same flaw.

    A plain identifier sits between non-`[\w']` characters when it is the
    inside of a `\<...>` token too, so the prime-aware boundary matched there.
    """

    def matches(self, name, line):
        return bool(re.search(_isa_word_pattern(name), line))

    def test_a_symbol_body_does_not_match(self):
        self.assertFalse(self.matches("lambda", r'have "\<lambda>x. x"'))
        self.assertFalse(self.matches("le", r'have "a \<le> b"'))
        self.assertFalse(self.matches("sub", r'have "x\<^sub>1 = y"'))
        self.assertFalse(self.matches("open", r'using \<open>foo\<close>'))

    def test_the_real_name_still_matches(self):
        self.assertTrue(self.matches("lambda", "using lambda by simp"))
        self.assertTrue(self.matches("le", "by (rule le)"))

    def test_a_name_abutting_a_symbol_still_matches(self):
        self.assertTrue(
            self.matches("iso_transaction", r'have "iso_transaction\<^sub>h"'))

    def test_a_name_inside_a_cartouche_still_matches(self):
        self.assertTrue(self.matches("foo", r'using \<open>foo\<close>'))

    def test_a_symbolic_name_is_unaffected(self):
        # A name that STARTS with `\<` takes the symbolic branch, which already
        # guards against gluing; the new lookbehinds must not reach it.
        self.assertTrue(self.matches("\\<gamma>", r'using \<gamma> by simp'))
        self.assertFalse(
            self.matches("\\<gamma>", r'using \<gamma>\<^sub>1 by simp'))


if __name__ == "__main__":
    unittest.main()
