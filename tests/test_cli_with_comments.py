"""The search family's prose toggle is `--with-comments` on both verbs.

`find` and `grep` both widen a search into `text` blocks and \\<comment>
annotations with the *same* flag — `--with-comments` — routed through the
shared `_add_with_comments_flag` helper.  grep's old `-a/--all` spelling is
gone: on `find`, `-a` already means "show all matches" (the lookup-family
mode from `_add_mode_flags`), so a grep-only `-a`-for-prose forked `-a`'s
meaning across the two search verbs — the same trap as the dropped `-n`.
One concept, one word.
"""

import argparse
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

PROSE_VERBS = ["find", "grep"]


def _sub(parser, cmd):
    action = next(a for a in parser._actions
                  if isinstance(a, argparse._SubParsersAction))
    return action.choices[cmd]


def _opt(sp, long):
    return next(a for a in sp._actions if long in a.option_strings)


class WithCommentsToggle(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_both_search_verbs_accept_with_comments(self):
        for cmd in PROSE_VERBS:
            with self.subTest(cmd=cmd):
                ns = self.parser.parse_args([cmd, "foo", "--with-comments"])
                self.assertTrue(ns.with_comments)

    def test_grep_a_flag_is_gone(self):
        # `-a` used to mean "include prose" on grep; it is removed so it no
        # longer collides with `find -a` ("show all matches").
        with self.assertRaises(SystemExit):
            self.parser.parse_args(["grep", "foo", "-a"])

    def test_find_a_flag_still_means_show_all(self):
        # `-a` survives on find as the lookup-family show-all mode — it must
        # NOT have become the prose toggle.
        ns = self.parser.parse_args(["find", "foo", "-a"])
        self.assertTrue(ns.all)              # show-all mode
        self.assertFalse(ns.with_comments)   # NOT prose search

    def test_prose_help_is_shared(self):
        # Both verbs render the flag from one helper, so the help is
        # byte-identical and cannot drift command-to-command.
        helps = {cmd: _opt(_sub(self.parser, cmd), "--with-comments").help
                 for cmd in PROSE_VERBS}
        self.assertEqual(helps["find"], helps["grep"])


if __name__ == "__main__":
    unittest.main()
