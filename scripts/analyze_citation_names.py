#!/usr/bin/env python3
r"""Characterise the citation-graph name universe by name length and tag.

WHY THIS EXISTS
    After the token router (proof methods / keywords / numerals are not fact
    citations), the residual call-graph noise is *short* names: single- and
    double-letter tokens (`x`, `a`, `f`, `xs`) that are term variables in most
    proofs but collide with an entry that happens to be named that.  Some
    short names are genuine short *lemma* names we want to keep; most short
    DEF names are LHS-head mis-mints from `definition "x + y = ..."`.

    `query` filters short citation names by a length threshold (overridable).
    This script is the evidence behind the default: it builds the call graph
    for a corpus once and buckets in-degree by name length AND tag, so we can
    see how much in-edge noise sits at each length and whether it is LEMMA
    (real, keep) or DEF (mostly mis-mint, drop).  Re-run it after a parser or
    router change to re-audit, or against a new corpus to pick a threshold.

USAGE
    python3 scripts/analyze_citation_names.py [--root DIR] [--top N]

    Defaults: --root from $ISABELLE_QUERY_ROOT, else ~/repos/afp/thys.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from isabelle_query import cli  # noqa: E402


def _first_tags(sections) -> dict[str, str]:
    """name -> tag, first declaration wins (mirrors the call-graph universe)."""
    tag_of: dict[str, str] = {}
    for s in sections:
        for e in s.entries:
            if e.name != "?" and e.name not in tag_of:
                tag_of[e.name] = e.tag
    return tag_of


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.environ.get(
        "ISABELLE_QUERY_ROOT",
        str(Path.home() / "repos" / "afp" / "thys")),
        help="Isabelle session dir to analyse (the call-graph corpus).")
    ap.add_argument("--top", type=int, default=25,
                    help="how many hubs to list per section (default 25)")
    ns = ap.parse_args()

    cli._ROOT_OVERRIDE = Path(ns.root).expanduser().resolve()
    sections = cli.load_index()
    tag_of = _first_tags(sections)

    g = cli._build_call_graph(sections)
    indeg = {n: len(g.callers.get(n, ())) for n in g.all_names}
    total_names = len(g.all_names)
    total_edges = sum(indeg.values()) or 1
    print(f"corpus: {ns.root}")
    print(f"citation universe: {total_names} names, {total_edges} in-edges\n")

    # In-edge mass by name length (capped at 5 = "5 or more").
    buckets: dict[int, dict] = {}
    for n, d in indeg.items():
        b = buckets.setdefault(min(len(n), 5),
                               {"names": 0, "edges": 0, "top": ("", 0)})
        b["names"] += 1
        b["edges"] += d
        if d > b["top"][1]:
            b["top"] = (n, d)
    print(f"{'len':>4} {'#names':>8} {'in-edges':>10} {'edges%':>7}   top hub")
    for L in sorted(buckets):
        b = buckets[L]
        lab = "5+" if L >= 5 else str(L)
        print(f"{lab:>4} {b['names']:>8} {b['edges']:>10} "
              f"{100.0 * b['edges'] / total_edges:6.1f}%   "
              f"{b['top'][0]!r} ({b['top'][1]}, {tag_of.get(b['top'][0], '?')})")

    print(f"\n--- top {ns.top} hubs overall (in-degree, name, tag) ---")
    for n, d in sorted(indeg.items(), key=lambda x: -x[1])[:ns.top]:
        print(f"  {d:>7}  {n:<22} {tag_of.get(n, '?')}")

    for L in (1, 2):
        rows = sorted(((n, d) for n, d in indeg.items() if len(n) == L),
                      key=lambda x: -x[1])
        by_tag: dict[str, list[int]] = {}
        for n, d in rows:
            v = by_tag.setdefault(tag_of.get(n, "?"), [0, 0])
            v[0] += 1
            v[1] += d
        print(f"\n--- length-{L}: {len(rows)} names, "
              f"{sum(d for _, d in rows)} in-edges ---")
        print("    by tag (#names, in-edges): "
              + ", ".join(f"{t}=({c},{e})" for t, (c, e) in
                          sorted(by_tag.items(), key=lambda x: -x[1][1])))
        for n, d in rows[:ns.top]:
            print(f"      {d:>7}  {n:<14} {tag_of.get(n, '?')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
