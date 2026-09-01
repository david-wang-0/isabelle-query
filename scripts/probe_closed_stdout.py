#!/usr/bin/env python3
"""What `query ... | head` exits, by output size [closed-stdout].

`CONTRIBUTING.md` fixes the status at `128 + SIGPIPE` = 141 when a downstream
reader closes the pipe.  It is not that simple, and the first thing to settle is
what "correct" even is — so this measures standard Unix producers alongside
query rather than assuming the contract is right:

    seq 10        (fits the 64K pipe buffer)  -> 0
    seq 200000    (exceeds it)                -> killed by SIGPIPE
    python small                              -> 0
    python large  (no SIGPIPE handler)        -> 1, plus a shutdown traceback

So **0 is the correct answer for a small producer**: it wrote everything, and
the reader chose to stop.  Nothing was truncated on this side.  What a Python
program must not do is the third row — exit 1 or 120 with "Exception ignored
while flushing sys.stdout", which is neither the C behaviour nor a status any
caller can interpret.

    python scripts/probe_closed_stdout.py

Sweeps one command over a range of output sizes and reports the status and any
stderr noise at each, so the boundary is located rather than guessed at.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
QUERY = str(_ROOT / ".venv" / "bin" / "query")
AFP = Path.home() / "repos" / "afp" / "thys"


def piped(cmd: list[str], head_n: int = 3) -> tuple[int, str]:
    """(producer exit status, its stderr) with `head -n` closing the pipe."""
    p1 = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p2 = subprocess.Popen(["head", f"-{head_n}"], stdin=p1.stdout,
                          stdout=subprocess.DEVNULL)
    p1.stdout.close()
    p2.wait()
    err = p1.stderr.read().decode("utf-8", "replace")
    p1.stderr.close()
    return p1.wait(), err.strip()


def full_size(cmd: list[str]) -> int:
    r = subprocess.run(cmd, capture_output=True)
    return len(r.stdout)


def main() -> int:
    print("=== reference: what standard producers do")
    for label, cmd in [
        ("seq 10", ["seq", "10"]),
        ("seq 200000", ["seq", "200000"]),
        ("yes", ["yes"]),
        ("python (no handler, large)",
         [sys.executable, "-c",
          "print('\\n'.join(str(i) for i in range(200000)))"]),
    ]:
        st, err = piped(cmd)
        note = "  [stderr noise]" if err else ""
        print(f"  {label:28} -> {st}{note}")

    if not AFP.is_dir():
        print("\nAFP corpus absent; skipping the query sweep.")
        return 0

    print("\n=== query, by output size (64K is the pipe buffer)")
    entries = ["Abstract_Completeness", "Coinductive", "Flyspeck-Tame",
               "Jinja", "Ordinary_Differential_Equations"]
    rows = []
    for name in entries:
        d = AFP / name
        if not d.is_dir():
            continue
        cmd = [QUERY, "-R", str(d), "shape", "census"]
        size = full_size(cmd)
        st, err = piped(cmd)
        rows.append((name, size, st, err))
    rows.sort(key=lambda r: r[1])
    print(f"  {'entry':34} {'bytes':>9} {'exit':>5}  stderr")
    for name, size, st, err in rows:
        mark = " <-- under 64K" if size < 65536 else ""
        print(f"  {name:34} {size:9} {st:5}  "
              f"{err.splitlines()[0][:34] if err else ''}{mark}")
    bad = [r for r in rows if r[2] not in (0, 141) or r[3]]
    print(f"\n{len(bad)} run(s) neither 0 nor 141, or noisy on stderr")
    return len(bad)


if __name__ == "__main__":
    raise SystemExit(main())
