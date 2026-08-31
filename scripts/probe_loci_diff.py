#!/usr/bin/env python3
"""Confirm that qualifying a locus changed the locus and NOTHING else.

`[disambig-loci]` widens the location column, so a plain `diff` of a verb's
before/after output is 100% changed and says nothing.  This compares the rows
structurally instead: same count, same order, same matched TEXT, same line
numbers, and every locus either unchanged or the old one with a directory
prefix.  The owner column is reported separately, because that is the column
the wrong-file defect actually moved.

    python scripts/probe_loci_diff.py BEFORE.txt AFTER.txt

Both files are the output of the same verb run over the same corpus, one from
a pre-fix worktree (`git worktree add ~/repos/query-pre <sha>`, run with
`PYTHONPATH=<worktree>/src python -m isabelle_query.cli ...`).
"""

from __future__ import annotations

import sys
from pathlib import Path


def rows(text: str) -> list[tuple[str, str]]:
    """`(locus, rest)` for each indented row that opens with a `NAME:LINE`."""
    out = []
    for line in text.splitlines():
        if not line.startswith("  "):
            continue
        parts = line.split(None, 1)
        if len(parts) != 2 or ":" not in parts[0]:
            continue
        if not parts[0].rsplit(":", 1)[-1].rstrip("-").isdigit():
            continue
        out.append((parts[0], parts[1].strip()))
    return out


def main() -> int:
    before = rows(Path(sys.argv[1]).read_text())
    after = rows(Path(sys.argv[2]).read_text())
    print(f"{len(before)} rows before, {len(after)} after")
    if len(before) != len(after):
        print("ROW COUNT MOVED — not a pure locus change")
        return 1

    same_loc = grew = moved_line = bad = 0
    owner_moved = []
    for (lb, rb), (la, ra) in zip(before, after):
        nb, _, lnb = lb.rpartition(":")
        na, _, lna = la.rpartition(":")
        if lnb != lna:
            moved_line += 1
        if na == nb:
            same_loc += 1
        elif na.endswith("/" + nb):
            grew += 1
        else:
            bad += 1
            if bad <= 5:
                print(f"  UNEXPLAINED  {lb!r} -> {la!r}")
        # The owner column is everything up to the two-space gap before the
        # source text; compare what is left after the source text is removed.
        if rb != ra:
            owner_moved.append((la, rb, ra))

    print(f"{same_loc:8}  loci unchanged")
    print(f"{grew:8}  loci gained a directory prefix")
    print(f"{bad:8}  loci changed some other way (must be 0)")
    print(f"{moved_line:8}  rows whose LINE NUMBER moved (must be 0)")
    print(f"{len(owner_moved):8}  rows whose owner/text column moved")
    for la, rb, ra in owner_moved[:8]:
        print(f"    {la}\n      before: {rb[:96]}\n      after : {ra[:96]}")
    return 1 if (bad or moved_line) else 0


if __name__ == "__main__":
    raise SystemExit(main())
