"""The global ``-R/--root`` option must be discoverable *and* usable on every
subcommand, not only on the top-level ``query`` parser.

Regression guarded: ``-R`` used to live solely on the top parser, so
``query methods -h`` never mentioned it (a user reading that help couldn't tell
the option existed) and ``query methods -R DIR`` errored with "unrecognized
arguments".  It is now attached to each (sub)parser off the subparser registry,
with ``default=SUPPRESS`` on the copies so a subparser never clobbers a value the
top-level parser already resolved.  These tests pin both halves: the option shows
in each subcommand's help, and it parses in either position — including the nested
``shape`` verbs — with the top-position value surviving (the SUPPRESS invariant).
"""
import argparse
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402


def _sub(parser, cmd):
    action = next(a for a in parser._actions
                  if isinstance(a, argparse._SubParsersAction))
    return action.choices[cmd]


# A verb from each shape family + the nested-group parent and a nested verb.
_DOCUMENTED_ON = ["methods", "summary", "deps", "show", "grep", "shape"]


class RootFlagDiscoverable(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_listed_in_each_subcommand_help(self):
        for cmd in _DOCUMENTED_ON:
            help_text = _sub(self.parser, cmd).format_help()
            self.assertIn("--root", help_text, f"{cmd} -h omits --root")
            self.assertIn("-R", help_text, f"{cmd} -h omits -R")

    def test_listed_in_nested_shape_verb_help(self):
        shape = _sub(self.parser, "shape")
        census = _sub(shape, "census")
        self.assertIn("--root", census.format_help())

    def test_help_wording_is_position_agnostic(self):
        # The wording must no longer claim it "Must precede the subcommand".
        top = self.parser.format_help()
        self.assertNotIn("precede the subcommand", top)


class RootFlagPositions(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_before_and_after_agree(self):
        before = self.parser.parse_args(["-R", "D", "methods"])
        after = self.parser.parse_args(["methods", "-R", "D"])
        self.assertEqual(before.root, "D")
        self.assertEqual(after.root, "D")

    def test_absent_is_none(self):
        self.assertIsNone(self.parser.parse_args(["methods"]).root)

    def test_nested_shape_both_positions(self):
        outer = self.parser.parse_args(["-R", "D", "shape", "census"])
        inner = self.parser.parse_args(["shape", "census", "-R", "D"])
        self.assertEqual(outer.root, "D")
        self.assertEqual(inner.root, "D")

    def test_top_position_survives_suppress_default(self):
        # The SUPPRESS invariant: the subparser copy, absent here, must not
        # overwrite the value the top parser already set to None-default.
        self.assertEqual(
            self.parser.parse_args(["-R", "D", "shape", "census"]).root, "D")


if __name__ == "__main__":
    unittest.main()
