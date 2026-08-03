#!/usr/bin/env python3
"""Time the shape step scan against a given `src` tree, parse phase separated.

Built for the comparison `CONTRIBUTING`'s verification section asks for:
interleaved, never across runs.  This machine drifts several percent between
consecutive runs of the SAME binary, so a before/after pair run back to back is
the only honest reading — take a `src` tree per invocation and alternate:

    git archive HEAD | tar -x -C .headtree
    for i in 1 2; do
      time_shape_scan.py /abs/path/.headtree/src 40
      time_shape_scan.py src 40
    done

Parsing is timed separately and reported as a share, because the scan is the
only thing a `shape` change moves and a scan regression looks very different
against the whole census than against itself.  (The inline-proof fix measured
+5% on the scan, ~35% of which is parse — inside the drift band end to end.)

Usage:  time_shape_scan.py SRC_DIR N_ENTRIES
"""
import sys
import time
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from isabelle_query import cli, shape

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[2])

secs = []
tp = time.perf_counter()
for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            secs.append(cli._parse_one(thy_path.stem, thy_path))
        except Exception:
            continue
parse = time.perf_counter() - tp

t0 = time.perf_counter()
n = 0
for sec in secs:
    for e in sec.entries:
        n += len(shape._scan_steps(sec, e))
scan = time.perf_counter() - t0
print(f"{sys.argv[1]:<40} parse {parse:6.2f}s  scan {scan:6.3f}s "
      f"({100 * scan / (parse + scan):4.1f}% of the two)  steps={n:,}")
