#!/usr/bin/env python3
r"""Corpus probe: what would recognising INDENTED declarations yield and cost?

`probe_indented_decls.py` says 5,245 declarations are invisible because
`DECL_RE` is anchored at column 0.  This asks the other half: if the anchor
went, what would the parser actually produce?

Method: re-parse each theory from an `lstrip`ped copy of its source.  That is
not the fix — it conflates several changes at once (indented `proof` / `by`
lines move to column 0 too) — but it is a faithful UPPER BOUND on what
de-anchoring reaches, and it costs nothing to run, because `_parse_one` already
accepts in-memory lines for the stdin path.  Line numbers are preserved, so
gained entries can be read back against the real source.

What to look for:
  * `gained` — declarations that become visible.  Sampled with their real
    source line, so a false positive is visible as prose or inner syntax.
  * `lost` — entries that STOP being recognised.  Any of these is a warning:
    the anchor is also what bounds a runaway statement scan to one entry
    (`tests/test_proof_line.py::DoesNotOverrun`), so a loss here is the shape
    of that failure.

Usage:  probe_deanchor.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

gained_tags: Counter = Counter()
lost_tags: Counter = Counter()
gained_samples: list[str] = []
lost_samples: list[str] = []
zero_fixed: list[str] = []
n_thy = n_base = n_new = n_gained = n_lost = 0

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            base = cli._parse_one(thy_path.stem, thy_path)
            raw = base.source()
            flat = cli._parse_one(thy_path.stem, thy_path,
                                  lines=[ln.lstrip() for ln in raw])
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        n_base += len(base.entries)
        n_new += len(flat.entries)

        b = {(e.thy_line, e.name) for e in base.entries}
        f = {(e.thy_line, e.name) for e in flat.entries}
        by_key = {(e.thy_line, e.name): e for e in flat.entries}
        base_by_key = {(e.thy_line, e.name): e for e in base.entries}

        first_here = True
        for key in sorted(f - b):
            n_gained += 1
            gained_tags[by_key[key].tag] += 1
            # One sample per theory: a run of samples from a single file says
            # nothing about whether the gain generalises.
            if first_here and len(gained_samples) < 24:
                first_here = False
                ln = key[0]
                src = raw[ln - 1].strip()[:92] if 0 < ln <= len(raw) else "?"
                gained_samples.append(
                    f"  {thy_path.stem}:{ln}  {by_key[key].tag} {key[1]}\n"
                    f"      {src}")
        for key in sorted(b - f):
            n_lost += 1
            lost_tags[base_by_key[key].tag] += 1
            if len(lost_samples) < 14:
                ln = key[0]
                src = raw[ln - 1].strip()[:92] if 0 < ln <= len(raw) else "?"
                lost_samples.append(
                    f"  {thy_path.stem}:{ln}  {base_by_key[key].tag} {key[1]}\n"
                    f"      {src}")
        if not base.entries and flat.entries:
            zero_fixed.append(f"  {thy_path.stem:<40} 0 -> {len(flat.entries):>4}"
                              f"  ({len(raw):,} lines)")

print(f"theories={n_thy:,}")
print(f"  entries today          {n_base:,}")
print(f"  entries de-anchored    {n_new:,}   "
      f"({100.0 * (n_new - n_base) / max(n_base, 1):+.1f}%)")
print(f"  gained {n_gained:,}   lost {n_lost:,}")

print("\ngained by tag:")
for tag, c in gained_tags.most_common():
    print(f"  {tag:<12} {c:>6}")
if lost_tags:
    print("\nLOST by tag  (any loss is a warning — see the module docstring):")
    for tag, c in lost_tags.most_common():
        print(f"  {tag:<12} {c:>6}")

print(f"\ntheories that went from ZERO entries to some: {len(zero_fixed)}")
for z in zero_fixed[:14]:
    print(z)

print("\ngained samples (read for false positives):")
for s in gained_samples:
    print(s)
if lost_samples:
    print("\nLOST samples:")
    for s in lost_samples:
        print(s)
