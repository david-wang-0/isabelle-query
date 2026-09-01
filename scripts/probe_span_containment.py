#!/usr/bin/env python3
"""Entries whose recorded body runs past their own span.

`compute_spans` sets `thy_end` from the NEXT entry's `src_start`, so
`body_end_line > thy_end` means a declaration's body overlaps its neighbour.
That is the invariant a shrink-only check cannot see, and the one that
rejected the wider `[decl-body-comment]` fix (82 violations -> 719).

Takes a `dump_entries.py --spans` file, or runs the dump itself.

    python scripts/dump_entries.py 2000 --spans > .dump.txt
    python scripts/probe_span_containment.py .dump.txt
"""

from __future__ import annotations

import sys


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "-"
    stream = sys.stdin if path == "-" else open(path, encoding="utf-8")
    total = 0
    bad_body: list[tuple[str, int, int]] = []
    bad_decl = 0
    for line in stream:
        parts = line.rstrip("\n").split(":")
        fields = {}
        for p in parts:
            if "=" in p:
                k, v = p.split("=", 1)
                fields[k] = v
        try:
            end = int(fields["src"].split("-")[1])
            be, de = int(fields["body_end"]), int(fields["decl_end"])
        except (KeyError, ValueError, IndexError):
            continue
        total += 1
        if be > end:
            bad_body.append((":".join(parts[:4]), be, end))
        if de > end:
            bad_decl += 1

    print(f"{total} records")
    print(f"body_end > thy_end: {len(bad_body)}")
    print(f"decl_end > thy_end: {bad_decl}")
    for key, be, end in bad_body[:12]:
        print(f"  {key}  body_end={be} > thy_end={end}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
