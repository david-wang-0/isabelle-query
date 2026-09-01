#!/usr/bin/env python3
"""Dump the citation graph as sorted `caller<TAB>callee` lines, for diffing.

Written for [name-is-not-identity], where the question is whether re-keying
the per-section indexes by path changes which entry a citation is attributed
to.  Counts cannot answer that — an edge invented and an edge lost cancel — so
this dumps the edge SET and lets `comm`/`diff` do the work.

    python scripts/probe_edge_dump.py [ROOT] [--reach name|closure]

Run it once on a pre-fix worktree and once here:

    PYTHONPATH=~/repos/query-pre/src python ~/repos/query-pre/scripts/probe_edge_dump.py
"""

from __future__ import annotations

import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph  # noqa: E402


def main() -> int:
    argv = [a for a in sys.argv[1:]]
    reach = "closure"
    if "--reach" in argv:
        i = argv.index("--reach")
        reach = argv[i + 1]
        del argv[i:i + 2]
    root = Path(argv[0]).expanduser() if argv else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    g = graph._build_call_graph(sections, derived=True, reach=reach)
    print(f"# {len(sections)} theories, {len(g.all_names)} names, reach={reach}",
          file=sys.stderr)
    out = sys.stdout
    for callee in sorted(g.callers):
        for caller in sorted(g.callers[callee]):
            out.write(f"{caller}\t{callee}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
