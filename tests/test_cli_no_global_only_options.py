"""Guard against the "global but not local" help inconsistency, generally.

An option attached to a parser that *owns* subcommands is parsed from the tokens
before the subcommand name, but argparse does not show it in — or accept it on —
the subcommands themselves.  That is exactly the trap `-R/--root` fell into
(`query methods -h` never mentioned it).  Rather than re-audit by hand whenever a
verb is added, this walks every parser in the tree that owns subcommands and
asserts each of its options is repeated on every child — except a small whitelist
that is legitimately parent-only.

Whitelist rationale:
  * ``-h``/``--help`` — argparse adds its own to every parser anyway;
  * ``--version`` — a top-level affordance (`query --version`); `query methods
    --version` is not meaningful, so it is deliberately not propagated.

If a new global-ish option is added to the top parser (or the `shape` group), the
fix is to route it through `_add_root_flag`-style propagation, not to widen this
whitelist.
"""
import argparse
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

_PARENT_ONLY_OK = {"-h", "--help", "--version"}


def _subparser_action(parser):
    return next((a for a in parser._actions
                 if isinstance(a, argparse._SubParsersAction)), None)


def _option_strings(parser):
    out = set()
    for a in parser._actions:
        out |= set(a.option_strings)
    return out


class NoGlobalOnlyOptions(unittest.TestCase):
    def test_every_parent_option_is_on_every_child(self):
        parser = cli._build_parser()
        stack = [("query", parser)]
        seen = set()
        checked_parents = 0
        while stack:
            name, p = stack.pop()
            spa = _subparser_action(p)
            if spa is None:
                continue
            checked_parents += 1
            # Options on this parent that ought to appear on each child.
            parent_opts = [a for a in p._actions
                           if a.option_strings
                           and not (set(a.option_strings) & _PARENT_ONLY_OK)]
            for cname, child in spa.choices.items():
                child_opts = _option_strings(child)
                for a in parent_opts:
                    self.assertTrue(
                        set(a.option_strings) & child_opts,
                        f"{a.option_strings[-1]} is on `{name}` but missing "
                        f"from subcommand `{cname}` — global-but-not-local; "
                        f"propagate it to the subparsers, do not leave it "
                        f"parent-only")
                if id(child) not in seen:          # dedupe alias-shared parsers
                    seen.add(id(child))
                    stack.append((f"{name} {cname}", child))
        # Sanity: we actually traversed both owners (top + the shape group),
        # so a future refactor that flattens the tree can't vacuously pass.
        self.assertGreaterEqual(checked_parents, 2)


if __name__ == "__main__":
    unittest.main()
