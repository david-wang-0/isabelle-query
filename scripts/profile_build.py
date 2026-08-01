#!/usr/bin/env python3
r"""Profile the two cost centres of a `query` build: parse and graph.

WHY THIS EXISTS
    The end-to-end cost of any call-graph command is two phases:

      load_index()        -- walk ROOTs, read every .thy, tokenise each
                             command into Entry spans (the "parse" phase);
      _build_call_graph() -- one linear pass over all source lines,
                             tokenising each and intersecting with the
                             name universe (the "graph" phase).

    Knowing which phase dominates -- and which functions inside it -- is the
    prerequisite for any optimisation that isn't a guess.  This script times
    each phase on its own wall clock and, with --cprofile, prints the top
    cumulative-time functions per phase so the hot lines are named, not
    speculated.  Re-run after any change to confirm a speedup is real and
    didn't regress the other phase.

USAGE
    python3 scripts/profile_build.py [--root DIR] [--cprofile] [--top N]
    python3 scripts/profile_build.py --repeat 3        # average 3 graph builds
    python3 scripts/profile_build.py --by-theory 15    # per-theory cost ranking

    --by-theory N ranks theories by *entry count* and times parse+build for
    each of the top N.  This is the view that exposed parse cost tracking
    entry count, not line count (a 44k-line file of long proofs is cheap; a
    file of thousands of short declarations is not) — the `entries`/`lines`
    columns sit side by side so the driver is visible.  Complements
    an afp-metrics.py script, which ranks by lines and does not parse.

    Defaults: --root from $ISABELLE_QUERY_ROOT, else ~/repos/afp/thys.
"""
from __future__ import annotations

import argparse
import cProfile
import os
import pstats
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from isabelle_query import cli  # noqa: E402


def _time(label, fn):
    t0 = time.perf_counter()
    result = fn()
    dt = time.perf_counter() - t0
    print(f"  {label:<22} {dt:7.3f}s")
    return result, dt


def _elapsed(fn) -> float:
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


def _by_theory(sections, n: int) -> None:
    """Rank theories by entry count and time each phase per theory — the view
    that exposes parse cost tracking entries, not lines.  Re-parses each theory
    from its path; builds the call graph on that one section (so cross-theory
    edges are absent, but the per-theory cost is what we want)."""
    ranked = sorted(sections, key=lambda s: len(s.entries), reverse=True)[:n]
    print(f"\ntop {n} theories by entry count (parse/build min of 3):")
    print(f"  {'theory':<34} {'entries':>7} {'lines':>7} {'ent/kloc':>8} "
          f"{'parse':>8} {'build':>8}")
    for s in ranked:
        lines = len(s.source())
        density = 1000.0 * len(s.entries) / lines if lines else 0.0
        tp = min(_elapsed(lambda s=s: cli._parse_one(s.path.stem, s.path))
                 for _ in range(3))
        tb = min(_elapsed(lambda s=s: cli._build_call_graph([s]))
                 for _ in range(3))
        print(f"  {s.theory[:34]:<34} {len(s.entries):>7} {lines:>7} "
              f"{density:>8.1f} {tp*1e3:>6.0f}ms {tb*1e3:>6.0f}ms")


def _cprofile(label, fn, top):
    pr = cProfile.Profile()
    pr.enable()
    result = fn()
    pr.disable()
    print(f"\n=== cProfile: {label} (top {top} by cumulative time) ===")
    st = pstats.Stats(pr)
    st.sort_stats("cumulative").print_stats(top)
    return result


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.environ.get(
        "ISABELLE_QUERY_ROOT",
        str(Path.home() / "repos" / "afp" / "thys")))
    ap.add_argument("--cprofile", action="store_true",
                    help="also print per-phase cProfile hot functions")
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--repeat", type=int, default=1,
                    help="times to repeat the graph build (parse is cached)")
    ap.add_argument("--by-theory", type=int, default=0, metavar="N",
                    help="rank theories by entry count and time the top N "
                         "per-theory (entries-vs-lines cost view)")
    ns = ap.parse_args()

    cli._ROOT_OVERRIDE = Path(ns.root).expanduser().resolve()
    print(f"corpus: {ns.root}")

    print("\nwall clock:")
    sections, _ = _time("load_index (parse)", cli.load_index)
    n_thy = len(sections)
    n_lines = sum(len(s.source()) for s in sections)
    n_entries = sum(len(s.entries) for s in sections)
    print(f"  ({n_thy} theories, {n_lines} source lines, {n_entries} entries)")

    build_times = []
    g = None
    for _ in range(ns.repeat):
        g, dt = _time("_build_call_graph", lambda: cli._build_call_graph(sections))
        build_times.append(dt)
    if ns.repeat > 1:
        print(f"  graph build avg over {ns.repeat}: "
              f"{sum(build_times) / len(build_times):7.3f}s")
    print(f"  ({len(g.all_names)} names, "
          f"{sum(len(v) for v in g.callers.values())} in-edges)")

    if ns.by_theory:
        _by_theory(sections, ns.by_theory)

    if ns.cprofile:
        _cprofile("load_index", cli.load_index, ns.top)
        _cprofile("_build_call_graph",
                  lambda: cli._build_call_graph(sections), ns.top)
    return 0


if __name__ == "__main__":
    sys.exit(main())
