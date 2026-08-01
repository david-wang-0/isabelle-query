#!/usr/bin/env python3
"""Show what the non-Isar scan marks in one theory, and why.

Usage:  probe_nonisar_show.py PATH.thy [MAX_RANGES]

Prints each marked range with its first line, so a suspicious share of a
theory can be attributed to a real region (an ML block) or to a runaway (the
state machine stuck after an unbalanced token).
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import parsing  # noqa: E402

path = Path(sys.argv[1])
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 25
lines = path.read_text(errors="replace").splitlines()
rs = parsing.extract_nonisar_ranges(lines)
marked = sum(hi - lo + 1 for lo, hi in rs)

print(f"{path.name}: {len(lines)} lines, {len(rs)} ranges, "
      f"{marked} marked ({100 * marked / max(len(lines), 1):.1f}%)")
print()
for lo, hi in sorted(rs, key=lambda r: r[1] - r[0], reverse=True)[:limit]:
    head = lines[lo - 1].strip()[:88]
    print(f"  {lo:5d}..{hi:<5d} ({hi - lo + 1:4d} lines)  {head}")
