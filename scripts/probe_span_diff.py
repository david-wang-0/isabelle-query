#!/usr/bin/env python3
"""Classify a `dump_entries.py --spans` before/after diff, field by field.

A span change is invisible to a plain `diff` count: 706 changed lines says
nothing about whether extents GREW (a truncation repaired) or SHRANK (a
declaration newly cut short), which is the only question that matters.  This
pairs the two dumps by record key and reports the direction of every field.

    python scripts/probe_span_diff.py .before.txt .after.txt
"""

from __future__ import annotations

import sys
from collections import Counter


def load(path: str) -> dict[str, dict[str, str]]:
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        parts = line.split(":")
        # `theory:line:tag:name:src=..:decl_end=..:proof=..:body_end=..`
        fields = {}
        for p in parts:
            if "=" in p:
                k, v = p.split("=", 1)
                fields[k] = v
        key = ":".join(parts[:3])          # theory:line:tag  (name may change)
        fields["name"] = parts[3] if len(parts) > 3 else ""
        out.setdefault(key, fields)
    return out


def main() -> int:
    before, after = load(sys.argv[1]), load(sys.argv[2])
    keys = set(before) | set(after)
    print(f"records: {len(before)} before, {len(after)} after, "
          f"{len(keys)} keys")
    print(f"only in before: {len(set(before) - set(after))}")
    print(f"only in after : {len(set(after) - set(before))}")

    moves: Counter = Counter()
    deltas: Counter = Counter()
    samples: dict[str, list] = {}
    for k in set(before) & set(after):
        b, a = before[k], after[k]
        for f in ("src", "decl_end", "proof", "body_end", "name"):
            if b.get(f) != a.get(f):
                moves[f] += 1
                if f in ("decl_end", "body_end"):
                    try:
                        d = int(a[f]) - int(b[f])
                    except (ValueError, KeyError):
                        d = 0
                    deltas[f"{f} {'grew' if d > 0 else 'shrank'}"] += 1
                    samples.setdefault(f"{f}{'+' if d > 0 else '-'}",
                                       []).append((k, b[f], a[f]))

    print("\nfields that moved:")
    for f, n in moves.most_common():
        print(f"  {f:<10} {n}")
    print("\ndirection:")
    for d, n in deltas.most_common():
        print(f"  {d:<20} {n}")
    for tag, rows in sorted(samples.items()):
        print(f"\n  sample {tag}:")
        for k, b, a in rows[:6]:
            print(f"    {k:<60} {b} -> {a}")

    # Containment: an entry's body must not run past its OWN span.  `thy_end`
    # is set by `compute_spans` from the next entry's start, so this is the
    # check that a body that GREW did not grow into the next declaration --
    # the failure a shrink-only invariant cannot see.
    print("\ncontainment (body_end <= thy_end, decl_end <= thy_end):")
    for label, dump in (("before", before), ("after", dump_after := after)):
        bad_body = bad_decl = 0
        worst = None
        for k, f in dump.items():
            try:
                end = int(f["src"].split("-")[1])
                be, de = int(f["body_end"]), int(f["decl_end"])
            except (KeyError, ValueError, IndexError):
                continue
            if be > end:
                bad_body += 1
                worst = worst or (k, be, end)
            if de > end:
                bad_decl += 1
        print(f"  {label:<7} body_end past thy_end: {bad_body:<6} "
              f"decl_end past thy_end: {bad_decl}")
        if worst:
            print(f"          e.g. {worst[0]}: body_end {worst[1]} "
                  f"> thy_end {worst[2]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
