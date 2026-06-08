"""The terse listing flag is `--names` only — the `-n` short flag is gone.

`-n` collides with the universal grep/rg convention where `-n` = line
numbers.  This tool always prints `theory:line` locations, so a grep-style
`-n` has nothing to toggle; squatting on it for `--names` would silently
flip a grep user into names-only mode.  So `-n` is dropped everywhere and
left free for its conventional meaning; `--names` is the spelling.
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


class NamesFlag(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_long_names_flag_still_parses(self):
        ns = self.parser.parse_args(["callers", "foo", "--names"])
        self.assertTrue(ns.names)

    def test_short_n_flag_is_rejected_everywhere(self):
        # argparse exits (2) on the now-unknown short flag.  `methods` takes
        # an optional positional, so it needs no subject argument.
        for cmd in NAMES_FLAG:
            with self.subTest(cmd=cmd):
                argv = [cmd, "-n"] if cmd == "methods" else [cmd, "x", "-n"]
                with self.assertRaises(SystemExit):
                    self.parser.parse_args(argv)


if __name__ == "__main__":
    unittest.main()
