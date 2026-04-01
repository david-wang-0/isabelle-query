#!/usr/bin/env python3
"""Query the lemma registry.

Usage:
  query-registry.py theory <name>          Show all entries for a theory
  query-registry.py find <pattern>         Find entries by name (Python regex, case-insensitive)
  query-registry.py show <name>            Show a specific entry (exact name match)
  query-registry.py defs <theory>          List definitions in a theory
  query-registry.py deps <theory>          List upstream theories (from ROOT order)
  query-registry.py summary                Print the theory summary table

Examples:
  query-registry.py theory MultiTapeNTM
  query-registry.py find threshold
  query-registry.py show ntmk_time_mono
  query-registry.py defs DiagonalLang
  query-registry.py summary
"""

import re
import sys
from pathlib import Path

ISA_DIR = Path(__file__).resolve().parent.parent
REGISTRY_PATH = ISA_DIR / "registry" / "registry.md"
INDEX_PATH = ISA_DIR / "registry" / "index.md"


def load_index_names() -> list[tuple[str, str, str, int]]:
    """Load the name index.  Returns [(name, tag, theory, line), ...]."""
    if not INDEX_PATH.exists():
        print("ERROR: index not found.  Run isa/scripts/update-registry.sh", file=sys.stderr)
        sys.exit(1)

    entries = []
    in_names = False
    for line in INDEX_PATH.read_text().splitlines():
        if line.strip() == "## Names":
            in_names = True
            continue
        if not in_names:
            continue
        if line.startswith("```"):
            if entries:  # closing fence
                break
            continue
        # Format: name (TAG) — Theory [line]
        m = re.match(r"(\w+) \((\w+)\) — (\w+) \[(\d+)\]", line)
        if m:
            entries.append((m.group(1), m.group(2), m.group(3), int(m.group(4))))
    return entries


def load_theory_ranges() -> dict[str, tuple[int, int]]:
    """Load theory name → (start_line, end_line) from the index table."""
    if not INDEX_PATH.exists():
        return {}
    ranges = {}
    for line in INDEX_PATH.read_text().splitlines():
        # | TheoryName | 123-456 | ...
        m = re.match(r"\|\s*(\w+)\s*\|\s*(\d+)-(\d+)\s*\|", line)
        if m:
            ranges[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    return ranges


def read_registry_lines(start: int, end: int) -> str:
    """Read lines [start, end] (1-based) from the registry."""
    lines = REGISTRY_PATH.read_text().splitlines()
    # Clamp to file bounds
    s = max(0, start - 1)
    e = min(len(lines), end)
    return "\n".join(lines[s:e])


def read_entry_at(line: int) -> str:
    """Read a single entry starting at the given line (1-based)."""
    lines = REGISTRY_PATH.read_text().splitlines()
    idx = line - 1
    if idx < 0 or idx >= len(lines):
        return "(line out of range)"
    result = [lines[idx]]
    # Collect continuation lines (indented)
    for j in range(idx + 1, len(lines)):
        if lines[j].startswith("  "):
            result.append(lines[j])
        else:
            break
    return "\n".join(result)


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_theory(name: str) -> None:
    ranges = load_theory_ranges()
    if name not in ranges:
        # Try case-insensitive
        for k in ranges:
            if k.lower() == name.lower():
                name = k
                break
        else:
            print(f"Theory '{name}' not found.  Known theories:")
            for k in sorted(ranges):
                print(f"  {k}")
            return
    start, end = ranges[name]
    print(read_registry_lines(start, end))


def cmd_find(pattern: str) -> None:
    entries = load_index_names()
    pat = re.compile(pattern, re.IGNORECASE)
    matches = [(n, t, th, ln) for n, t, th, ln in entries if pat.search(n)]
    if not matches:
        print(f"No entries matching '{pattern}'.")
        return
    # Print matches with their full text
    for name, tag, theory, line in matches:
        print(f"--- {name} ({tag}) — {theory}.thy [{line}] ---")
        print(read_entry_at(line))
        print()


def cmd_show(name: str) -> None:
    entries = load_index_names()
    matches = [(n, t, th, ln) for n, t, th, ln in entries if n == name]
    if not matches:
        # Try case-insensitive
        matches = [(n, t, th, ln) for n, t, th, ln in entries
                   if n.lower() == name.lower()]
    if not matches:
        print(f"Entry '{name}' not found.")
        # Suggest similar
        close = [(n, t, th, ln) for n, t, th, ln in entries if name.lower() in n.lower()]
        if close:
            print("Similar entries:")
            for n, t, th, ln in close[:10]:
                print(f"  {n} ({t}) — {th}")
        return
    for n, t, th, ln in matches:
        print(f"--- {n} ({t}) — {th}.thy [{ln}] ---")
        print(read_entry_at(ln))
        print()


def cmd_defs(theory: str) -> None:
    entries = load_index_names()
    matches = [(n, t, th, ln) for n, t, th, ln in entries
               if th.lower() == theory.lower() and t in ("DEF", "FUN", "DATATYPE", "RECORD", "TYPE")]
    if not matches:
        print(f"No definitions found in '{theory}'.")
        return
    for name, tag, th, line in matches:
        print(f"--- {name} ({tag}) [{line}] ---")
        print(read_entry_at(line))
        print()


def cmd_deps(theory: str) -> None:
    """Show theories that come before the given theory in ROOT order (upstream deps)."""
    ranges = load_theory_ranges()
    theories = list(ranges.keys())
    target = None
    for t in theories:
        if t.lower() == theory.lower():
            target = t
            break
    if target is None:
        print(f"Theory '{theory}' not found.")
        return
    idx = theories.index(target)
    if idx == 0:
        print(f"{target} has no upstream dependencies (it's first in ROOT).")
        return
    print(f"Theories before {target} in ROOT order:")
    for t in theories[:idx]:
        start, end = ranges[t]
        print(f"  {t} (lines {start}-{end})")


def cmd_summary() -> None:
    if not INDEX_PATH.exists():
        print("ERROR: index not found.  Run isa/scripts/update-registry.sh", file=sys.stderr)
        sys.exit(1)
    # Print the theory table section
    lines = INDEX_PATH.read_text().splitlines()
    in_table = False
    for line in lines:
        if line.startswith("## Theories"):
            in_table = True
        elif line.startswith("## Names"):
            break
        if in_table:
            print(line)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]

    if cmd == "summary":
        cmd_summary()
    elif cmd == "theory" and len(sys.argv) >= 3:
        cmd_theory(sys.argv[2])
    elif cmd == "find" and len(sys.argv) >= 3:
        cmd_find(sys.argv[2])
    elif cmd == "show" and len(sys.argv) >= 3:
        cmd_show(sys.argv[2])
    elif cmd == "defs" and len(sys.argv) >= 3:
        cmd_defs(sys.argv[2])
    elif cmd == "deps" and len(sys.argv) >= 3:
        cmd_deps(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
