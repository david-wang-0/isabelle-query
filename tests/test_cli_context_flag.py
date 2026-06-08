"""`--context` uses one short flag — `-U` — on every command that has it.

`callers` used to declare its own `-C/--context` inline; it now routes
through the shared `_add_context_flag` helper like its lookup-family
siblings (theory/outline/find/show), so the short flag is uniformly `-U`.
(`callers` is a lookup verb — it carries no PATH positionals — so it should
match its family; and rg's `-C` means context on *both* sides whereas
`callers` shows only trailing lines, rg's `-A`, so `-C` was a mis-aligned
borrowing anyway.)  Only the *default* differs by command: a preview wants
2 lines, a caller listing wants 0.
"""

import argparse
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

# Every command exposing `--context`, with its per-command default.
CONTEXT_DEFAULTS = {"theory": 2, "outline": 2, "find": 2, "show": 2,
                    "callers": 0}


def _sub(parser, cmd):
    action = next(a for a in parser._actions
                  if isinstance(a, argparse._SubParsersAction))
    return action.choices[cmd]


def _context_opt(sp):
    return next(a for a in sp._actions if a.dest == "context")


class ContextFlag(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_short_flag_is_U_everywhere(self):
        for cmd in CONTEXT_DEFAULTS:
            with self.subTest(cmd=cmd):
                opt = _context_opt(_sub(self.parser, cmd))
                self.assertEqual(opt.option_strings, ["-U", "--context"])

    def test_callers_C_flag_is_gone(self):
        with self.assertRaises(SystemExit):
            self.parser.parse_args(["callers", "foo", "-C", "3"])

    def test_callers_accepts_U(self):
        ns = self.parser.parse_args(["callers", "foo", "-U", "3"])
        self.assertEqual(ns.context, 3)

    def test_per_command_defaults_preserved(self):
        for cmd, default in CONTEXT_DEFAULTS.items():
            with self.subTest(cmd=cmd):
                opt = _context_opt(_sub(self.parser, cmd))
                self.assertEqual(opt.default, default)


if __name__ == "__main__":
    unittest.main()
