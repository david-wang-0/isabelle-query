#!/usr/bin/env python3
r"""Corpus probe: what would block tracking COST?

The block model (`begin`/`end` at outer-syntax position) is only worth having
if it is close to free — `query` reparses the whole tree on every invocation,
and that is the property the tool is built around.

Measured here as an UPPER BOUND, deliberately: the outer-syntax pass runs as a
SECOND scan over the source, on top of the parse.  A real implementation folds
it into the tokenizer's existing single pass — `scan_regions` already walks
every line with a state machine that knows `string` and `term` state, and the
only new work is recording spans it currently discards.  So the true cost is
below what this prints, and the printed number is the one to argue against.

Usage:  probe_block_cost.py [N_ENTRIES]
"""
import sys
import time
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))
sys.path.insert(0, str(_ROOT / "scripts"))

from isabelle_query import cli  # noqa: E402
from probe_block_structure import _BLOCK_RE, outer_only  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 60

paths = []
for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    paths.extend(sorted(ent.rglob("*.thy")))

# Warm the page cache and collect sources once, so the timings compare CPU on
# the scan rather than disk.
srcs = []
for p in paths:
    try:
        srcs.append((p.stem, p, p.read_text().splitlines()))
    except Exception:  # noqa: BLE001
        pass
n_lines = sum(len(s) for _, _, s in srcs)

t0 = time.perf_counter()
secs = []
for thy, p, lines in srcs:
    try:
        secs.append(cli._parse_one(thy, p, lines=list(lines)))
    except Exception:  # noqa: BLE001
        pass
t_parse = time.perf_counter() - t0

# Split the block pass in two.  The tokenizing half is what a real
# implementation does NOT pay — `scan_regions` already runs that state machine
# and merely discards the spans.  The counting half is the true marginal cost.
t0 = time.perf_counter()
outers = []
for sec in secs:
    try:
        outers.append(outer_only(sec.live_source()))
    except Exception:  # noqa: BLE001
        pass
t_tokenize = time.perf_counter() - t0

t0 = time.perf_counter()
depth_total = 0
for live in outers:
    depth = 0
    for line in live:
        for m in _BLOCK_RE.finditer(line):
            depth += 1 if m.group(1) == "begin" else -1
    depth_total += depth
t_count = time.perf_counter() - t0
t_block = t_tokenize + t_count

print(f"theories={len(secs):,}  lines={n_lines:,}")
print(f"  parse today                {t_parse:6.2f}s  "
      f"({n_lines / max(t_parse, 1e-9) / 1e6:.2f}M lines/s)")
print(f"  + block pass (2nd scan)    {t_block:6.2f}s  "
      f"({100.0 * t_block / max(t_parse, 1e-9):.1f}% on top)  <- upper bound")
print(f"      of which re-tokenizing {t_tokenize:6.2f}s  "
      f"(NOT paid: folds into scan_regions)")
print(f"      of which counting      {t_count:6.2f}s  "
      f"({100.0 * t_count / max(t_parse, 1e-9):.1f}% on top)  <- true marginal")
print(f"\n(sanity: summed closing depth across all theories = {depth_total})")
