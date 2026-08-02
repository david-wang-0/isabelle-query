#!/usr/bin/env python3
"""Audit an entry-set diff: show each changed entry against its real source.

`dump_entries.py` says WHICH entries a parser change added or lost; this says
whether they should have been.  A count alone cannot tell a recovered
declaration from a phantom minted inside a proof — only the source line can.

Samples one per theory by default, because a run of hits from a single file
says nothing about whether the change generalises.

    comm -13 <(sort .before.txt) <(sort .after.txt) > .gained.txt
    python3 scripts/audit_entry_diff.py .gained.txt [N_SAMPLES] [--all]
                                                   [--suspicious]

`--suspicious` reports only records whose preceding non-blank line reads as
proof text.  That is the false positive that matters: a declaration keyword
recognised INSIDE a proof body is a phantom entry, and it is the one failure
the column-0 anchor used to prevent for free.

Reads `afp_entry/theory:line:tag:name` records and prints the source line each names,
plus the line above it, since a false positive usually gives itself away by
what precedes it (a `proof`, an `apply`, an open term).
"""
import sys
from collections import Counter
from pathlib import Path

AFP = Path.home() / "repos" / "afp" / "thys"
records = Path(sys.argv[1]).read_text().splitlines()
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 25
per_theory = "--all" not in sys.argv and "--suspicious" not in sys.argv
suspicious_only = "--suspicious" in sys.argv
# Tokens that can only open a proof STEP, never a declaration.  `qed` / `next`
# are included: a declaration may legitimately follow them, but a gained entry
# right after one is worth a look either way.
PROOF_TOKENS = ("apply", "by ", "proof", "show", "have", "then", "next",
                "case", "using", "unfolding", "obtain", "fix", "assume",
                "moreover", "ultimately", "hence", "thus", "qed")

index: dict[str, Path] = {}
for p in AFP.rglob("*.thy"):
    index.setdefault(p.stem, p)

by_tag: Counter = Counter()
seen_theories: set[str] = set()
shown = 0
for rec in records:
    parts = rec.split(":", 3)
    if len(parts) < 4:
        continue
    # Records are `afp_entry/theory:line:tag:name`.
    thy, line_no, tag, name = (parts[0].split("/")[-1], int(parts[1]),
                               parts[2], parts[3])
    by_tag[tag] += 1
    if shown >= limit or (per_theory and thy in seen_theories):
        continue
    seen_theories.add(thy)
    path = index.get(thy)
    if path is None:
        continue
    try:
        src = path.read_text().splitlines()
    except Exception:  # noqa: BLE001
        continue
    k = line_no - 2
    while k >= 0 and not src[k].strip():
        k -= 1
    above = src[k].rstrip()[:96] if k >= 0 else ""
    if suspicious_only and not above.strip().startswith(PROOF_TOKENS):
        shown -= 0
        continue
    here = src[line_no - 1].rstrip()[:96] if line_no <= len(src) else "?"
    shown += 1
    print(f"  {thy}:{line_no}  {tag} {name}")
    if above.strip():
        print(f"      above| {above}")
    print(f"      HERE | {here}")

print(f"\n{len(records):,} records, {shown} shown, by tag:")
for tag, c in by_tag.most_common():
    print(f"  {tag:<12} {c:>6}")
