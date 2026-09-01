#!/usr/bin/env python3
r"""What closure-scoped citation attribution costs and buys [citation-reach].

`callers` / `callees` / `unused` / `graph citation` used to resolve a cited
token by NAME alone: find `mono` on a line, look up every entry called `mono`,
report the line as a caller of all of them.  Within one session that is right —
everything there sees everything the session declares.  Over a corpus it is
not, and `--reach closure` drops an attribution whose citing theory cannot see
the declaration (itself, or its transitive in-project `imports`).

The rule is a NECESSARY condition on visibility, so it can only ever DROP an
edge.  `unused` may therefore honestly GROW, which is the point: an entry kept
alive only by an unreachable same-name citation is dead.

    python scripts/probe_citation_reach.py [ROOT]

Prints both graphs side by side.  A corpus-scale delta that cannot be turned
off cannot be measured against the numbers it replaces, which is why `--reach`
exists at all and why this reads both modes rather than asserting one.
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph  # noqa: E402


def edges(g) -> set[tuple[str, str]]:
    return {(caller, name) for name, cs in g.callers.items() for caller in cs}


def main() -> int:
    root = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    print(f"{len(sections)} theories under {root}")

    by_mode = {}
    for mode in graph.REACH_MODES:
        g = graph._build_call_graph(sections, derived=True, reach=mode)
        by_mode[mode] = g
        dead = {n for n in g.all_names if not g.callers.get(n)}
        print(f"{mode:9}  {len(edges(g)):9} edges  "
              f"{len(g.all_names):8} names  {len(dead):8} with no caller")

    lost = edges(by_mode["name"]) - edges(by_mode["closure"])
    gained = edges(by_mode["closure"]) - edges(by_mode["name"])
    print(f"\ndropped {len(lost)} edges, gained {len(gained)} "
          f"(gained must be 0: the rule can only drop)")

    # Which names lose the most — the same-name collisions the rule is for.
    per_name: Counter[str] = Counter(name for _caller, name in lost)
    print("\nnames losing the most attributions:")
    for name, n in per_name.most_common(15):
        print(f"  {name:32} -{n}")

    dead_name = {n for n in by_mode["name"].all_names
                 if not by_mode["name"].callers.get(n)}
    dead_closure = {n for n in by_mode["closure"].all_names
                    if not by_mode["closure"].callers.get(n)}
    newly = dead_closure - dead_name
    print(f"\nnewly caller-free: {len(newly)} entries "
          f"(kept alive only by a citation their citer could not see)")
    for n in sorted(newly)[:10]:
        print(f"  {n}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
