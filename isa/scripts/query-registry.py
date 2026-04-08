#!/usr/bin/env python3
"""Query the lemma registry.

Commands:
  summary                       Theory summary table (sizes, defs/lemmas/thms)
  theory <name>                 Show all entries for a theory (raw chunk)
  defs <theory>                 List definitions in a theory
  deps <theory>                 List upstream theories (from ROOT order)
  outline <theory>              Section structure interleaved with entries
  largest [N] [theory]          Top N largest entries by source span (default 20)
  find <pattern> [flags]        Find entries by name (regex, case-insensitive)
  show <name> [flags]           Show a specific entry (exact name, falls back to substring)

Verbosity flags (apply to find/show):
  default       First match only.  Hint at the count of additional matches.
  -a, --all     Show all matches.
  -c, --count   Just print the match count, no bodies.
  -n, --names   Just print names + tags + theory + src span (no bodies).

Examples:
  query-registry.py summary
  query-registry.py theory MultiTapeNTM
  query-registry.py outline SimFaithfulness
  query-registry.py largest 10 SimFaithfulness
  query-registry.py find threshold              # first match + count hint
  query-registry.py find threshold --all        # all matches with bodies
  query-registry.py find threshold --count      # just the count
  query-registry.py find threshold --names      # names only
  query-registry.py show ntmk_time_mono

Future / TODO features (to keep us from re-reading large theories):
  - uses <name> / users <name>: lemma-level forward/reverse usage graph,
    extracted from `using X`, `[OF X]`, `unfolding X_def`, `rule X` mentions
    in proof bodies.  Largest single-feature win for "if I move X, who breaks?"
    questions; cost is moderate (regex over .thy files at registry-update time).
  - rdeps-lemma <name>: same as `users`, but with the closure (everything
    reachable backward).  Falls out of `users` once it exists.
  - unused [theory]: lemmas with zero callers anywhere in the project.
    Catches dead helpers.  Falls out of `users`.
  - signatures <theory>: like `theory <name>` but only the first line of
    each entry (signature/header) — saves output budget when scanning a
    big theory.
  - sections <theory>: just the outline header tree (no entries) — already
    available as a subset of `outline`.
"""

import re
import sys
from pathlib import Path

ISA_DIR = Path(__file__).resolve().parent.parent
REGISTRY_PATH = ISA_DIR / "registry" / "registry.md"
INDEX_PATH = ISA_DIR / "registry" / "index.md"

# (name, tag, theory, reg_line, src_start, src_end)
NameRow = tuple[str, str, str, int, int, int]


def _ensure_index() -> None:
    if not INDEX_PATH.exists():
        print("ERROR: index not found.  Run `make registry` in isa/.",
              file=sys.stderr)
        sys.exit(1)


def load_index_names() -> list[NameRow]:
    """Load the name index.  Returns [(name, tag, theory, reg_line, src_start, src_end), ...]."""
    _ensure_index()
    entries: list[NameRow] = []
    in_names = False
    pat = re.compile(
        r"(\w+) \((\w+)\) — (\w+) \[(\d+)\](?:\s*\(src (\d+)-(\d+)\))?"
    )
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
        m = pat.match(line)
        if m:
            src_s = int(m.group(5)) if m.group(5) else 0
            src_e = int(m.group(6)) if m.group(6) else 0
            entries.append(
                (m.group(1), m.group(2), m.group(3), int(m.group(4)), src_s, src_e)
            )
    return entries


def load_theory_ranges() -> dict[str, tuple[int, int]]:
    """Load theory name → (registry-md start, end) line range."""
    _ensure_index()
    ranges: dict[str, tuple[int, int]] = {}
    in_registry = False
    for line in INDEX_PATH.read_text().splitlines():
        if line.strip() == "## Registry":
            in_registry = True
            continue
        if not in_registry:
            continue
        if line.startswith("## ") and line.strip() != "## Registry":
            break
        m = re.match(r"(\w+):\s*(\d+)-(\d+)", line)
        if m:
            ranges[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    return ranges


def load_outlines() -> dict[str, list[tuple[str, str, int]]]:
    """Load per-theory outline (level, title, line) lists from ## Outlines."""
    _ensure_index()
    out: dict[str, list[tuple[str, str, int]]] = {}
    in_outlines = False
    cur_theory: str | None = None
    in_block = False
    for line in INDEX_PATH.read_text().splitlines():
        if line.strip() == "## Outlines":
            in_outlines = True
            continue
        if not in_outlines:
            continue
        if line.startswith("## ") and line.strip() != "## Outlines":
            break
        if line.startswith("### "):
            cur_theory = line[4:].strip()
            out[cur_theory] = []
            in_block = False
            continue
        if line.startswith("```"):
            in_block = not in_block
            continue
        if in_block and cur_theory is not None:
            m = re.match(r"^(\s*)(section|subsection|subsubsection):\s*(.*)\s*\((\d+)\)$",
                         line)
            if m:
                level = m.group(2)
                title = m.group(3).strip()
                ln = int(m.group(4))
                out[cur_theory].append((level, title, ln))
    return out


def read_registry_lines(start: int, end: int) -> str:
    lines = REGISTRY_PATH.read_text().splitlines()
    s = max(0, start - 1)
    e = min(len(lines), end)
    return "\n".join(lines[s:e])


def read_entry_at(line: int) -> str:
    """Read a single entry starting at the given line (1-based) in registry.md."""
    lines = REGISTRY_PATH.read_text().splitlines()
    idx = line - 1
    if idx < 0 or idx >= len(lines):
        return "(line out of range)"
    result = [lines[idx]]
    for j in range(idx + 1, len(lines)):
        if lines[j].startswith("  "):
            result.append(lines[j])
        else:
            break
    return "\n".join(result)


def _format_name_line(row: NameRow) -> str:
    name, tag, theory, _ln, src_s, src_e = row
    span = f" [src {src_s}-{src_e}, {src_e - src_s + 1} lines]" if src_s else ""
    return f"{name} ({tag}) — {theory}{span}"


def _print_entry_full(row: NameRow) -> None:
    name, tag, theory, ln, src_s, src_e = row
    span = f" src {src_s}-{src_e}" if src_s else ""
    print(f"--- {name} ({tag}) — {theory}.thy [reg {ln}{span}] ---")
    print(read_entry_at(ln))


# ---------------------------------------------------------------------------
# Verbosity-mode dispatch (shared between find/show)
# ---------------------------------------------------------------------------

def _emit_matches(matches: list[NameRow], pattern: str, mode: str) -> None:
    if not matches:
        print(f"No entries matching '{pattern}'.")
        return

    if mode == "count":
        print(f"{len(matches)} match(es) for '{pattern}'.")
        return

    if mode == "names":
        for row in matches:
            print(_format_name_line(row))
        return

    if mode == "all":
        for row in matches:
            _print_entry_full(row)
            print()
        return

    # mode == "first"
    _print_entry_full(matches[0])
    if len(matches) > 1:
        print()
        print(f"[+{len(matches) - 1} more match(es).  Use --all to show, "
              f"--names for a list, --count for just the count.]")


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_theory(name: str) -> None:
    ranges = load_theory_ranges()
    if name not in ranges:
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


def cmd_find(pattern: str, mode: str) -> None:
    entries = load_index_names()
    pat = re.compile(pattern, re.IGNORECASE)
    matches = [row for row in entries if pat.search(row[0])]
    _emit_matches(matches, pattern, mode)


def cmd_show(name: str, mode: str) -> None:
    entries = load_index_names()
    matches = [row for row in entries if row[0] == name]
    if not matches:
        matches = [row for row in entries if row[0].lower() == name.lower()]
    if not matches:
        # Substring fallback so old "show partial" usage still works.
        matches = [row for row in entries if name.lower() in row[0].lower()]
    _emit_matches(matches, name, mode)


def cmd_defs(theory: str) -> None:
    entries = load_index_names()
    matches = [row for row in entries
               if row[2].lower() == theory.lower()
               and row[1] in ("DEF", "FUN", "DATATYPE", "RECORD", "TYPE")]
    if not matches:
        print(f"No definitions found in '{theory}'.")
        return
    for row in matches:
        _print_entry_full(row)
        print()


def cmd_deps(theory: str) -> None:
    """Show theories that come before the given theory in ROOT order."""
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
        print(f"  {t} (registry lines {start}-{end})")


def cmd_summary() -> None:
    _ensure_index()
    in_table = False
    for line in INDEX_PATH.read_text().splitlines():
        if line.startswith("## Theories"):
            in_table = True
        elif line.startswith("## ") and not line.startswith("## Theories"):
            if in_table:
                break
        if in_table:
            print(line)


def cmd_outline(theory: str) -> None:
    """Show interleaved sections + entries for one theory."""
    outlines = load_outlines()
    if theory not in outlines:
        for k in outlines:
            if k.lower() == theory.lower():
                theory = k
                break
        else:
            print(f"Theory '{theory}' not found.")
            return

    entries = [row for row in load_index_names()
               if row[2].lower() == theory.lower() and row[4] > 0]

    # Items: (line, kind, payload)
    items: list[tuple[int, str, object]] = []
    for level, title, ln in outlines[theory]:
        items.append((ln, "section", (level, title)))
    for row in entries:
        items.append((row[4], "entry", row))
    items.sort(key=lambda x: x[0])

    if not items:
        print(f"No outline data for '{theory}'.")
        return

    print(f"Outline of {theory}.thy:\n")
    for ln, kind, payload in items:
        if kind == "section":
            level, title = payload  # type: ignore[misc]
            indent = {"section": "", "subsection": "  ",
                      "subsubsection": "    "}[level]
            print(f"{indent}{level:>14}: {title}  (line {ln})")
        else:
            row: NameRow = payload  # type: ignore[assignment]
            name, tag, _theory, _reg, src_s, src_e = row
            size = src_e - src_s + 1
            print(f"        {tag:<8} {name}  ({src_s}-{src_e}, {size} lines)")


def cmd_largest(args: list[str]) -> None:
    """Show top-N largest entries by .thy source span."""
    n = 20
    theory_filter: str | None = None
    for a in args:
        if a.isdigit():
            n = int(a)
        else:
            theory_filter = a

    rows = load_index_names()
    if theory_filter:
        rows = [r for r in rows if r[2].lower() == theory_filter.lower()]

    sized = [(r[5] - r[4] + 1, r) for r in rows if r[4] > 0]
    sized.sort(key=lambda x: -x[0])

    if not sized:
        print("No entries with span data.  Re-run update-registry.py.")
        return

    where = f" in {theory_filter}" if theory_filter else ""
    print(f"Top {min(n, len(sized))} largest entries{where}:\n")
    print(f"{'Lines':>6}  {'Tag':<8}  {'Name':<42}  Theory  (span)")
    print(f"{'-' * 6:>6}  {'-' * 8:<8}  {'-' * 42:<42}  ------")
    for size, row in sized[:n]:
        name, tag, theory, _ln, src_s, src_e = row
        print(f"{size:>6}  {tag:<8}  {name:<42}  {theory}  ({src_s}-{src_e})")


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

def _parse_flags(args: list[str]) -> tuple[list[str], str]:
    """Strip verbosity flags from args, return (positional, mode)."""
    positional: list[str] = []
    mode = "first"
    for arg in args:
        if arg in ("-a", "--all"):
            mode = "all"
        elif arg in ("-c", "--count"):
            mode = "count"
        elif arg in ("-n", "--names"):
            mode = "names"
        else:
            positional.append(arg)
    return positional, mode


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]
    rest = sys.argv[2:]

    if cmd == "summary":
        cmd_summary()
    elif cmd == "theory" and len(rest) >= 1:
        cmd_theory(rest[0])
    elif cmd == "find" and len(rest) >= 1:
        positional, mode = _parse_flags(rest)
        if not positional:
            print("Usage: query-registry.py find <pattern> [-a|-c|-n]", file=sys.stderr)
            sys.exit(1)
        cmd_find(positional[0], mode)
    elif cmd == "show" and len(rest) >= 1:
        positional, mode = _parse_flags(rest)
        if not positional:
            print("Usage: query-registry.py show <name> [-a|-c|-n]", file=sys.stderr)
            sys.exit(1)
        cmd_show(positional[0], mode)
    elif cmd == "defs" and len(rest) >= 1:
        cmd_defs(rest[0])
    elif cmd == "deps" and len(rest) >= 1:
        cmd_deps(rest[0])
    elif cmd == "outline" and len(rest) >= 1:
        cmd_outline(rest[0])
    elif cmd == "largest":
        cmd_largest(rest)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
