#!/usr/bin/env python3
"""Probe: what is `isabelle_query.common` actually for, now that the parser left?

`common.py` re-exports `isabelle-layout`.  Two questions decide its future, and
both are answerable from the tree rather than from opinion:

  1. Of the names it offers, how many does anything IN THIS REPO use?  The rest
     exist only for the deprecation window — callers outside this repository
     that still import `isabelle_query.common`.
  2. Of the names in-repo code wants, how many could come straight from
     `isabelle_layout` instead?  A name that could is a redirect; a name that
     could not is the actual reason the module exists.

This matters beyond tidiness.  `[layout-privates]` — query reaching past
layout's public API, which is what a version cap was standing in for — turns
out to be mostly a property of the deprecation window rather than of query: the
privates are re-exported for downstream callers, not consumed here.  Closing
the window would shrink the exposure without changing a line of query's logic.

Reads imports statically, so it also catches a name that is re-exported and
then used by nobody at all.

Usage:  probe_common_surface.py
"""
from __future__ import annotations

import ast
import sys
from collections import defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_ROOT / "src"))

import isabelle_layout  # noqa: E402

COMMON = _ROOT / "src/isabelle_query/common.py"


def offered() -> tuple[set[str], set[str]]:
    """(every name `common` exposes, those it re-exports privately)."""
    tree = ast.parse(COMMON.read_text())
    names, private = set(), set()
    for n in tree.body:
        if isinstance(n, ast.ImportFrom) and (n.module or "").startswith(
                "isabelle_layout"):
            for a in n.names:
                names.add(a.asname or a.name)
                if a.name.startswith("_"):
                    private.add(a.asname or a.name)
        elif isinstance(n, ast.FunctionDef):
            names.add(n.name)
    return names, private


def wanted() -> dict[str, set[str]]:
    """Which in-repo file wants which name from `common`."""
    users: dict[str, set[str]] = defaultdict(set)
    for d in ("src", "tests", "scripts"):
        for p in sorted((_ROOT / d).rglob("*.py")):
            try:
                tree = ast.parse(p.read_text())
            except SyntaxError:
                continue
            for n in ast.walk(tree):
                if isinstance(n, ast.ImportFrom) and (
                        n.module or "").endswith("common"):
                    users[str(p.relative_to(_ROOT))] |= {
                        a.name for a in n.names}
                elif (isinstance(n, ast.Attribute)
                      and isinstance(n.value, ast.Name)
                      and n.value.id == "common"):
                    users[str(p.relative_to(_ROOT))].add(n.attr)
    return users


def main() -> None:
    offers, private = offered()
    users = wanted()
    used = set().union(*users.values()) if users else set()
    public_layout = set(getattr(isabelle_layout, "__all__", []))

    print(f"`common` offers {len(offers)} names "
          f"({len(private)} of them private upstream)")
    print(f"{len(users)} file(s) in this repo import from it, "
          f"wanting {len(used)} distinct names\n")

    unused = sorted(offers - used)
    print(f"OFFERED BUT UNUSED HERE — the deprecation window, {len(unused)}:")
    for n in unused:
        print(f"    {'priv ' if n in private else '     '}{n}")
    print(f"  ({len(private & set(unused))} of the {len(private)} private "
          f"re-exports are in this group — exposure query does not itself need)")

    redirect = sorted(n for n in used if n in public_layout)
    own = sorted(n for n in used if n not in public_layout)
    print(f"\nWANTED HERE, {len(used)}:")
    print(f"  {len(redirect)} available as PUBLIC isabelle_layout — a redirect:")
    print(f"      {', '.join(redirect)}")
    print(f"  {len(own)} NOT public top-level layout — why the module exists:")
    for n in own:
        who = sorted(f for f, ns in users.items() if n in ns)
        print(f"      {n:<24} {', '.join(who)}")


if __name__ == "__main__":
    main()
