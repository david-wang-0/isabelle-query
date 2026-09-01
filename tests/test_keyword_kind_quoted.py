r"""A `keywords` kind may be quoted, and Isabelle takes it either way.

The header grammar (`Pure/Thy/thy_header.ML`) reads the kind as a *name*, and a
name is quoted or unquoted at the author's discretion:

    keywords "alphabet" "statespace" :: "thy_defn"          Optics/Lens_Instances:5
    keywords "expr_constructor" ... :: "thy_decl_block"     Shallow_Expressions/Expressions:5

`_parse_keyword_block` took the kind only from an `op`-flagged token — an
unquoted run — because quoting is what distinguishes a command NAME from the
`% tag` values around it.  That reasoning holds before the `::` and not after
it: past the colon the first token IS the kind whichever way it is spelled.  A
quoted kind therefore yielded no kind at all, `_KIND_FAMILY.get("")` was None,
the commands never entered the table, and every `alphabet ...` /
`expr_constructor ...` declaration in those sessions was invisible.

Found by the differential harness in David Wang's Scala port, recorded there as
D3; the Scala side reads the header with `Thy_Header`, Isabelle's own parser.
37 records over Optics and Shallow_Expressions.

The negative cases below are what keep the widening honest.  Accepting a
quoted token after `::` must not start accepting one BEFORE it (that is the
command name), nor a `% tag` value (which follows a kind that is already set).
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402
from isabelle_query import parsing  # noqa: E402


def table(clause):
    """The custom-command table a header with this `keywords` clause yields."""
    return parsing.scan_keywords(
        ["theory T", "  imports Main", f"  {clause}", "begin"])


class QuotedKind(unittest.TestCase):

    def test_quoted_kind_registers_the_commands(self):
        self.assertEqual(table('keywords "alphabet" "statespace" :: "thy_defn"'),
                         {"alphabet": "DEF", "statespace": "DEF"})

    def test_unquoted_kind_still_registers(self):
        self.assertEqual(table('keywords "alphabet" "statespace" :: thy_defn'),
                         {"alphabet": "DEF", "statespace": "DEF"})

    def test_the_two_spellings_agree(self):
        self.assertEqual(table('keywords "expr_constructor" :: "thy_decl_block"'),
                         table('keywords "expr_constructor" :: thy_decl_block'))

    def test_glued_quoted_kind(self):
        self.assertEqual(table('keywords "alphabet" ::"thy_defn"'),
                         {"alphabet": "DEF"})


class TheWideningStaysNarrow(unittest.TestCase):
    """A quoted token is the kind only in the slot right after `::`."""

    def test_a_group_with_no_kind_is_a_minor_keyword(self):
        # `and "over"` declares syntax, not a command: no `::`, no entry.
        self.assertEqual(
            table('keywords "edefinition" :: "thy_decl_block" and "over"'),
            {"edefinition": "DEF"})

    def test_a_quoted_tag_value_is_not_read_as_the_kind(self):
        # The kind is already bound when `% "proof"` arrives.
        self.assertEqual(table('keywords "alphabet" :: thy_defn % "proof"'),
                         {"alphabet": "DEF"})

    def test_an_unknown_kind_registers_nothing(self):
        self.assertEqual(table('keywords "alphabet" :: "quasi_command"'), {})


class ThePipelineSeesIt(unittest.TestCase):
    """The declarations the table unlocks are actually indexed."""

    SOURCE = r"""theory Lens
imports Main
  keywords "alphabet" :: "thy_defn"
begin

alphabet mystate =
  x :: nat
  y :: nat

lemma after: \<open>True\<close> by simp

end
"""

    def test_the_custom_command_declares_an_entry(self):
        sec = section_from(self.SOURCE, "Lens")
        self.assertIn("mystate", [e.name for e in sec.entries])


if __name__ == "__main__":
    unittest.main()
