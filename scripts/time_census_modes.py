#!/usr/bin/env python3
"""Time a census the two ways: a shell-style loop of `query` invocations vs one
`census --by-session` process (issue #6).

The loop's extra cost is not query runtime — it is interpreter + process startup
paid once per entry.  This measures both over the SAME entries so the difference
is attributable, and reports the per-entry startup it implies.

Both runs discard stdout: the point is wall-clock and record count, not the
records.  Record counts are compared because a faster run that measures fewer
proofs is not a faster run.

Usage:  time_census_modes.py [N_ENTRIES] [--afp DIR]
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

AFP_DEFAULT = Path.home() / "repos" / "afp" / "thys"


def _count(out: str) -> int:
    return sum(1 for ln in out.splitlines() if ln.strip())


def loop_mode(dirs) -> tuple[float, int, int]:
    """One `query` process per entry — what a shell loop does today."""
    t = time.perf_counter()
    recs = spawns = 0
    for d in dirs:
        r = subprocess.run(["query", "-R", str(d), "shape", "census"],
                           capture_output=True, text=True)
        spawns += 1
        recs += _count(r.stdout)
    return time.perf_counter() - t, recs, spawns


def batch_mode(root: Path, dirs) -> tuple[float, int, int]:
    """One process for the whole root.  A ROOTS index scoped to `dirs` keeps the
    comparison honest — same entries, one process."""
    t = time.perf_counter()
    r = subprocess.run(["query", "-R", str(root), "shape", "census",
                        "--by-session"], capture_output=True, text=True)
    dt = time.perf_counter() - t
    if r.returncode not in (0,):
        print(f"  (batch exited {r.returncode}: "
              f"{r.stderr.strip().splitlines()[:1]})", file=sys.stderr)
    return dt, _count(r.stdout), 1


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("entries", nargs="?", type=int, default=20)
    ap.add_argument("--afp", type=Path, default=AFP_DEFAULT)
    args = ap.parse_args()

    dirs = sorted(d for d in args.afp.iterdir() if d.is_dir())[:args.entries]
    # A scratch root whose ROOTS names exactly those entries, so `-R` sees the
    # same corpus the loop walks.  Symlinks, so nothing is copied.
    root = Path(".census-timing-root")
    if root.exists():
        for c in root.iterdir():
            if c.is_symlink() or c.is_file():
                c.unlink()
    else:
        root.mkdir()
    (root / "ROOTS").write_text("".join(f"{d.name}\n" for d in dirs))
    for d in dirs:
        (root / d.name).symlink_to(d)

    print(f"# {len(dirs)} entries from {args.afp}\n")
    b_dt, b_recs, _ = batch_mode(root, dirs)
    l_dt, l_recs, spawns = loop_mode(dirs)
    b2_dt, _b2, _ = batch_mode(root, dirs)      # interleaved: batch, loop, batch
    b_dt = min(b_dt, b2_dt)

    print(f"loop  ({spawns:>3} processes) {l_dt:7.1f}s   {l_recs:>8,} records")
    print(f"batch (  1 process )  {b_dt:7.1f}s   {b_recs:>8,} records")
    if l_recs != b_recs:
        print(f"\n!! record counts DIFFER by {abs(l_recs - b_recs):,} — a faster "
              f"run that measures fewer proofs is not a faster run")
    saved = l_dt - b_dt
    print(f"\nsaved {saved:6.1f}s  ({100 * saved / max(l_dt, 1e-9):4.1f}%)"
          f"   implied startup {1000 * saved / max(spawns - 1, 1):5.0f} ms/entry")


if __name__ == "__main__":
    main()
