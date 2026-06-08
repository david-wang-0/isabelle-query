"""Pin the CLI's two-family argument contract and its shared help wording.

The surface splits into two families with deliberately different shapes,
each matching an external convention:

  * **lookup** verbs (`show`/`callers`/`callees`, `deps`/`uses`) take a
    one-or-more *subject* positional (git/brew style) and carry **no**
    trailing PATH positionals — "who calls X" is corpus-global, scoped by
    the global `-R`, not by a file subset;
  * **search** verbs (`grep`/`largest`/`sorry`) take trailing PATH
    positionals (grep/rg style).

The regression these guard: `callers` must be variadic and must NOT
regrow a `files` positional — the two-greedy-positionals clash that forced
the family split (a single subparser can't carry both `name nargs='+'`
and `files nargs='*'`).  They also pin that every subject-list positional
is rendered from the one shared template, so the help wording cannot drift
command-to-command.
"""

import argparse
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

# The invariant slices every `_add_subject_list_arg` help string shares.
_SHARED = "pass multiple to "
_SHARED_TAIL = "in turn (blank-line separated), so "

LOOKUP_LIST = ["show", "callees", "callers", "deps", "uses"]
SEARCH_PATHS = ["grep", "largest", "sorry"]


def _sub(parser, cmd):
    action = next(a for a in parser._actions
                  if isinstance(a, argparse._SubParsersAction))
    return action.choices[cmd]


def _positional(sp):
    return next(a for a in sp._actions if not a.option_strings)


class FamilyContract(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_callers_is_variadic(self):
        ns = self.parser.parse_args(["callers", "A", "B", "C"])
        self.assertEqual(ns.name, ["A", "B", "C"])
        self.assertIs(ns.func, cli._run_callers)

    def test_callers_has_no_path_positional(self):
        # The whole reason for the family split: callers dropped its trailing
        # PATH positionals so `name` could become nargs='+' (two greedy
        # positionals can't coexist).  A regrown `files` would break that.
        ns = self.parser.parse_args(["callers", "foo"])
        self.assertFalse(hasattr(ns, "files"))

    def test_lookup_subjects_are_variadic_without_paths(self):
        for cmd in LOOKUP_LIST:
            with self.subTest(cmd=cmd):
                sp = _sub(self.parser, cmd)
                self.assertEqual(_positional(sp).nargs, "+")
                self.assertFalse(any(a.dest == "files" for a in sp._actions))

    def test_search_verbs_carry_trailing_paths(self):
        for cmd in SEARCH_PATHS:
            with self.subTest(cmd=cmd):
                sp = _sub(self.parser, cmd)
                files = next(a for a in sp._actions if a.dest == "files")
                self.assertEqual(files.nargs, "*")
                self.assertEqual(files.metavar, "PATH")


class SharedHelpWording(unittest.TestCase):
    """Every subject-list positional flows through `_add_subject_list_arg`,
    so the common sentence is byte-identical across commands."""

    def setUp(self):
        self.parser = cli._build_parser()

    def test_subject_list_help_is_templated(self):
        for cmd in LOOKUP_LIST + ["find"]:
            with self.subTest(cmd=cmd):
                pos = _positional(_sub(self.parser, cmd))
                self.assertIn(_SHARED, pos.help)
                self.assertIn(_SHARED_TAIL, pos.help)
                # The gate-loop motif names the command itself.
                self.assertIn(f"`{cmd} A B C`", pos.help)
                self.assertIn(f"do {cmd} $n", pos.help)

    def test_template_varies_only_by_extra(self):
        # Replace each command's own name with a placeholder; the residual
        # spine must then be shared.  callees has no `extra`, so its spine is
        # the bare template; show's spine equals it plus the appended extra.
        def spine(cmd):
            return _positional(_sub(self.parser, cmd)).help.replace(cmd, "CMD")
        self.assertTrue(spine("show").startswith(spine("callees")))


if __name__ == "__main__":
    unittest.main()
