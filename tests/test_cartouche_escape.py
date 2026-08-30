r"""A cartouche body is raw: `\\` in one is not a string escape.

`_SCAN_RE` matches every state-changing token in one alternation, and Python's
alternation is ordered choice.  `\\` (the string escape) was listed before
`\<open>`, so at

    fun resid  (infix \<open>\\<close> 70)

the escape branch consumed the two backslashes — the body backslash and the
first character of `\<close>` — the close was never tokenised, and the scanner
stayed in cartouche state to the END OF THE FILE.  Every declaration below read
as "inside a term": the declaration swallowed the rest of the theory and
nothing after it was indexed at all.

That is a layering mistake rather than a missing case.  Isabelle reads source
in two passes: a SYMBOL layer, where `\<close>` is one atom, and a token layer,
where `\\` is a string escape.  Scanning both at once lets an escape eat half a
symbol.  `(?!<)` restores symbol-precedence — decline the escape exactly when
the second backslash begins a markup token — which is Isabelle's own rule
written as a lookahead.

The residuation and hiding operators are spelled this way, so the cost was
1,867 records over five AFP entries (ResiduatedTransitionSystem 1,052,
ResiduatedTransitionSystem2 742, HOL-CSP 49, Circus 13, Isabelle_Meta_Model
11).  Found by the differential harness in David Wang's Scala port, recorded
there as D1.

`EscapesStillWork` is the guard, and it is the half that matters most: the
escape branch exists so an escaped quote inside a `"..."` term is consumed
rather than read as the closing delimiter.  Narrowing it must not reopen that.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

# The shape of ResiduatedTransitionSystem/LambdaCalculus:566 and HOL-CSP/Hiding:171
BACKSLASH_CARTOUCHE = r"""theory Esc
imports Main
begin

fun resid  (infix \<open>\\<close> 70)
  where \<open>resid x = x\<close>

lemma below_operator: \<open>True\<close> by simp

definition marker :: \<open>bool\<close> where \<open>marker = True\<close>

end
"""

# The case the escape branch was added for: a quote and a backslash escaped
# inside a term.  Get this wrong and the string never closes, which is the same
# whole-file swallow from the other direction.
ESCAPES = r"""theory Guard
imports Main
begin

lemma escaped_quote: "a \" b = c" by simp

lemma after_escaped_quote: "True" by simp

lemma escaped_backslash: "a \\" by simp

lemma after_escaped_backslash: "True" by simp

end
"""


class BackslashCartouche(unittest.TestCase):

    def setUp(self):
        self.sec = section_from(BACKSLASH_CARTOUCHE, "Esc")
        self.names = [e.name for e in self.sec.entries]

    def test_declarations_below_the_operator_are_indexed(self):
        self.assertIn("below_operator", self.names)
        self.assertIn("marker", self.names)

    def test_the_operator_declaration_does_not_swallow_the_file(self):
        resid = next(e for e in self.sec.entries if e.name == "resid")
        # The `where` clause ends on line 6; the lemma below opens on line 8.
        self.assertLess(resid.decl_end_line, 8,
                        "declaration ran past its own `where` clause")

    def test_every_declaration_is_found(self):
        self.assertEqual(self.names, ["resid", "below_operator", "marker"])


class EscapesStillWork(unittest.TestCase):
    """Narrowing the escape must not reopen the case it was added for."""

    def setUp(self):
        self.sec = section_from(ESCAPES, "Guard")
        self.names = [e.name for e in self.sec.entries]

    def test_an_escaped_quote_does_not_close_its_term(self):
        # If `\"` were read as a closing quote the trailing `"` would open a
        # string that runs to EOF, and this lemma would vanish.
        self.assertIn("after_escaped_quote", self.names)

    def test_an_escaped_backslash_is_still_consumed(self):
        self.assertIn("after_escaped_backslash", self.names)

    def test_every_declaration_is_found(self):
        self.assertEqual(self.names,
                         ["escaped_quote", "after_escaped_quote",
                          "escaped_backslash", "after_escaped_backslash"])


if __name__ == "__main__":
    unittest.main()
