#!/usr/bin/env python3
"""Profile the non-Isar scan over a corpus slice.

Reports cProfile's own ranking plus a hand split of the three phases —
the early-out precheck, the state machine, and the line-coverage/coalescing
pass — because the profile alone does not separate them (they are one call).

Usage:  probe_nonisar_profile.py [N_ENTRIES]
"""
import cProfile
import pstats
import sys
import time
from io import StringIO
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 40

docs = []
for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy in sorted(ent.rglob("*.thy")):
        try:
            docs.append(thy.read_text(errors="replace").splitlines())
        except OSError:
            pass
n_lines = sum(len(d) for d in docs)
print(f"{len(docs)} theories, {n_lines:,} lines\n")


def phase_precheck():
    for lines in docs:
        (any(parsing._ANY_REGION_RE.search(ln) for ln in lines)
         or any(parsing._leads_with_ml(ln) for ln in lines))


def phase_scan():
    for lines in docs:
        parsing._scan_nonisar_spans(lines)


def phase_all():
    for lines in docs:
        parsing.extract_nonisar_ranges(lines)


for name, fn in (("precheck", phase_precheck),
                 ("state machine", phase_scan),
                 ("full extract", phase_all)):
    t0 = time.perf_counter()
    fn()
    dt = time.perf_counter() - t0
    print(f"{name:<16} {dt:6.3f}s   ({n_lines / dt / 1e6:.2f}M lines/s)")

print()
buf = StringIO()
cProfile.run("phase_all()", sort="tottime")
pr = cProfile.Profile()
pr.enable()
phase_all()
pr.disable()
pstats.Stats(pr, stream=buf).sort_stats("tottime").print_stats(12)
print("\n".join(buf.getvalue().splitlines()[4:22]))
