#!/usr/bin/env python3
r"""Which of query's tests of *moved* code have no counterpart upstream?

`[common-shim]` proposes retiring four test files — `test_thy_header.py`,
`test_session_theories.py`, `test_base_logic.py`, `test_discover_roots.py` —
on the grounds that they test the parser that moved to `isabelle-layout`
through query's shim, and that layout "already carries its own versions of all
four".

Same *filename* is not the same *coverage*, and the file-level test counts
already disagree, so retiring on the strength of the filename would delete
cases silently.  This compares the two sides case by case and prints what
query would lose.

Matching is by normalised method name (`test_` stripped, underscores dropped,
lowercased), then by a containment fallback in both directions, because the
two projects name the same case differently — layout tests a function, query
tests a behaviour it depends on.  A NEAR match is reported with the upstream
name so it can be eyeballed; only UNMATCHED is a real loss.

    python scripts/probe_duplicated_tests.py [--layout DIR]

Exit status is 0 always; this reports, it does not gate.
"""

from __future__ import annotations

import argparse
import ast
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LAYOUT = _ROOT.parent / "isabelle-layout"

# query file -> upstream file it is claimed to duplicate
PAIRS = {
    "test_thy_header.py": "test_thy_header.py",
    "test_session_theories.py": "test_session_theories.py",
    "test_base_logic.py": "test_base_logic.py",
    "test_discover_roots.py": "test_discover_roots.py",
}


def _tests(path: Path) -> dict[str, str]:
    """`{normalised name: original name}` for every test method in `path`."""
    tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    found: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if node.name.startswith("test"):
                key = node.name[len("test"):].strip("_").replace("_", "")
                found[key.lower()] = node.name
    return found


def _match(key: str, upstream: dict[str, str]) -> tuple[str, str] | None:
    """Exact, then containment either way.  Returns (kind, upstream name)."""
    if key in upstream:
        return ("exact", upstream[key])
    for up_key, up_name in upstream.items():
        if key in up_key or up_key in key:
            return ("near", up_name)
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--layout", type=Path, default=DEFAULT_LAYOUT,
                    help=f"isabelle-layout checkout (default {DEFAULT_LAYOUT})")
    args = ap.parse_args()

    up_dir = args.layout / "tests"
    if not up_dir.is_dir():
        print(f"no layout tests at {up_dir}", file=sys.stderr)
        return 0

    total_lost = 0
    for ours_name, theirs_name in PAIRS.items():
        ours_path = _ROOT / "tests" / ours_name
        theirs_path = up_dir / theirs_name
        if not ours_path.exists():
            print(f"{ours_name}: already retired")
            continue
        if not theirs_path.exists():
            print(f"{ours_name}: NO upstream counterpart — keep")
            continue

        ours, theirs = _tests(ours_path), _tests(theirs_path)
        unmatched, near = [], []
        for key, name in sorted(ours.items()):
            hit = _match(key, theirs)
            if hit is None:
                unmatched.append(name)
            elif hit[0] == "near":
                near.append((name, hit[1]))

        total_lost += len(unmatched)
        print(f"\n{ours_name}: {len(ours)} here, {len(theirs)} upstream")
        if unmatched:
            print(f"  UNMATCHED ({len(unmatched)}) — lost if this file goes:")
            for name in unmatched:
                print(f"      {name}")
        if near:
            print(f"  near ({len(near)}) — check the upstream case really "
                  f"covers ours:")
            for name, up in near:
                print(f"      {name}\n        ~ {up}")
        if not unmatched and not near:
            print("  every case has an exact counterpart upstream")

    print(f"\n{total_lost} case(s) with no upstream counterpart at all")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
