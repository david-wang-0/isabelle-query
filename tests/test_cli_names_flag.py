"""The terse listing flag is `--names` only — `-n` never means it.

`-n` collides with the universal grep/rg convention where `-n` = line
numbers.  This tool always prints `theory:line` locations, so a grep-style
`-n` has nothing to toggle; squatting on it for `--names` would silently
flip a grep user into names-only mode.  So `-n` is not the terse flag
anywhere; `--names` is the spelling.

What `-n` *does* is now split by verb, and the split is the contract:

  * on the **search** verbs (`grep`, `sorry`) it parses and is ignored — a
    usage-dump error there sends a grep-reflex caller back to raw `grep`,
    and the always-on locus already carries the line number they wanted;
  * **everywhere else** it stays an unknown flag, because those verbs are
    where `-n` used to mean `--names` and silence would be worse than an
    error: it would return a different result set than the one the old
    spelling asked for.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

# Every subcommand that exposes the terse listing flag (via `_add_names_flag`
# directly or the `_add_mode_flags` bundle).
NAMES_FLAG = ["theory", "defs", "find", "show", "callers", "callees",
              "grep", "methods"]

# The verbs that swallow `-n` (`_add_line_number_noop_flag`).
NOOP_N = ["grep", "sorry"]


class NamesFlag(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_long_names_flag_still_parses(self):
        ns = self.parser.parse_args(["callers", "foo", "--names"])
        self.assertTrue(ns.names)

    def test_short_n_flag_is_rejected_outside_the_search_verbs(self):
        # argparse exits (2) on the unknown short flag.  `methods` takes an
        # optional positional, so it needs no subject argument.
        for cmd in NAMES_FLAG:
            if cmd in NOOP_N:
                continue
            with self.subTest(cmd=cmd):
                argv = [cmd, "-n"] if cmd == "methods" else [cmd, "x", "-n"]
                with self.assertRaises(SystemExit):
                    self.parser.parse_args(argv)

    def test_short_n_never_sets_names(self):
        # The point of the split: where `-n` parses, it must not have become a
        # back door to the terse view it once meant.
        ns = self.parser.parse_args(["grep", "x", "-n"])
        self.assertFalse(ns.names)

    def test_noop_n_parses_identically_to_its_absence(self):
        # The whole contract in one assertion: `-n` must leave the namespace
        # byte-identical, so it cannot reach a handler at all.  `dest` and
        # `default` are both SUPPRESS, so it adds no attribute either.
        for cmd in NOOP_N:
            with self.subTest(cmd=cmd):
                argv = [cmd, "x"] if cmd == "grep" else [cmd]
                self.assertEqual(self.parser.parse_args(argv),
                                 self.parser.parse_args(argv + ["-n"]))
                self.assertEqual(self.parser.parse_args(argv),
                                 self.parser.parse_args(
                                     argv + ["--line-number"]))


if __name__ == "__main__":
    unittest.main()
