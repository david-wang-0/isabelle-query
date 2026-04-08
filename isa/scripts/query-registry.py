#!/usr/bin/env python3
"""Query the lemma index — computed live from .thy files on every invocation.

No serialized registry; all commands re-parse the theory tree (~60ms for the
full ~90 theories).  This means results are always in sync with the current
.thy source — no `make registry` step.

Commands:
  summary                       Theory summary table (sizes, defs/lemmas/thms,
                                key exports)
  theory <name> [flags]         Show all entries for a theory
  defs <theory>                 List definitions in a theory
  deps <theory>                 List upstream theories (from ROOT order)
  outline <theory>              Section structure interleaved with entries
  largest [N] [theory]          Top N largest entries by source span (default 20)
  find <pattern> [flags]        Find entries by name (regex, case-insensitive)
  show <name> [flags]           Show a specific entry (exact name, falls back
                                to substring)

Verbosity flags (apply to find/show; verbatim also applies to theory):
  default       First match: header + statement verbatim + first proof line
                + count of remaining proof lines.
  -a, --all     Show all matches.
  -c, --count   Just print the match count, no bodies.
  -n, --names   Just print names + tags + theory + src span (no bodies).
  -V, --verbatim
                Print the full source slice (statement + entire proof body)
                from the .thy file.

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
  query-registry.py show ntmk_time_mono -V      # full source incl. proof

Future / TODO features (to keep us from re-reading large theories):
  - uses <name> / users <name>: lemma-level forward/reverse usage graph,
    extracted from `using X`, `[OF X]`, `unfolding X_def`, `rule X` mentions
    in proof bodies.  Largest single-feature win for "if I move X, who breaks?"
    questions; cost is moderate (one regex pass over .thy files at query
    time, additive on the existing ~60ms parse).
  - rdeps-lemma <name>: same as `users`, but with the closure (everything
    reachable backward).  Falls out of `users` once it exists.
  - unused [theory]: lemmas with zero callers anywhere in the project.
    Catches dead helpers.  Falls out of `users`.
  - signatures <theory>: like `theory <name>` but only the first line of
    each entry (signature/header) — saves output budget when scanning a
    big theory.  Could be a flag to `theory` rather than its own command.
  - sections <theory>: just the outline header tree (no entries) — already
    available as a subset of `outline`; could be a flag to `outline`.
"""

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ISA_DIR = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# Parsing — walks ROOT files and extracts entries from .thy sources
# ---------------------------------------------------------------------------

@dataclass
class Entry:
    tag: str            # DEF, FUN, LEMMA, THEOREM, DATATYPE, TYPE, RECORD, AXIOM
    name: str           # identifier
    text: str           # legacy pre-formatted text (for `theory` dump)
    theory: str = ""
    thy_line: int = 0       # 1-indexed start line in the .thy source file
    decl_end_line: int = 0  # 1-indexed last line of the declaration
                            # (last header line before proof / blank / next decl)
    proof_line: int = 0     # 1-indexed first line of the proof (0 if no proof)
    thy_end: int = 0        # 1-indexed structural end (heuristic — to next entry)


@dataclass
class TheorySection:
    theory: str
    path: Path
    entries: list[Entry]
    thy_lines: int = 0
    outline: list[tuple[str, str, int]] = field(default_factory=list)
    _source_cache: list[str] | None = None

    def source(self) -> list[str]:
        if self._source_cache is None:
            self._source_cache = self.path.read_text().splitlines()
        return self._source_cache

    def slice(self, start: int, end: int) -> list[str]:
        """Return 1-indexed inclusive line range from the .thy source."""
        lines = self.source()
        s = max(0, start - 1)
        e = min(len(lines), end)
        return lines[s:e]


DECL_RE = re.compile(
    r"^(definition|fun|primrec|lemma|corollary|theorem|axiomatization|datatype|type_synonym|record)\s"
)

TAG_MAP = {
    "definition": "DEF", "fun": "FUN", "primrec": "FUN",
    "lemma": "LEMMA", "corollary": "LEMMA",
    "theorem": "THEOREM",
    "axiomatization": "AXIOM",
    "datatype": "DATATYPE", "type_synonym": "TYPE", "record": "RECORD",
}

PROOF_RE = re.compile(
    r"^\s*(proof\b|by\b|sorry\b|oops\b|using\b"
    r"|unfolding\b|apply\b|\.\.\s*$)"
)
BLANK_RE = re.compile(r"^\s*$")
TOPLEVEL_RE = re.compile(r"^[a-z]")
SECTION_RE = re.compile(r"^(section|subsection|subsubsection)\s+\\<open>(.*)")
NAME_RE = re.compile(r"^(\w+)")


def _parse_name(text_after_tag: str) -> str:
    m = NAME_RE.match(text_after_tag.strip())
    return m.group(1) if m else "?"


def parse_theories_from_root(root_path: Path) -> list[str]:
    theories = []
    in_theories = False
    for line in root_path.read_text().splitlines():
        stripped = line.strip()
        if re.match(r"theories\b", stripped):
            in_theories = True
            continue
        if re.match(r"(document_files|document_theories|sessions|options|description)\b", stripped):
            in_theories = False
            continue
        if in_theories and re.match(r"[A-Za-z_][A-Za-z0-9_]*$", stripped):
            theories.append(stripped)
    return theories


def extract_sections(lines: list[str]) -> list[tuple[str, str, int]]:
    out: list[tuple[str, str, int]] = []
    for i, line in enumerate(lines, 1):
        m = SECTION_RE.match(line)
        if not m:
            continue
        level = m.group(1)
        rest = m.group(2)
        close_idx = rest.find("\\<close>")
        title = rest[:close_idx] if close_idx >= 0 else rest
        out.append((level, title.strip(), i))
    return out


def extract_entries(lines: list[str]) -> list[Entry]:
    entries: list[Entry] = []
    i = 0

    while i < len(lines):
        line = lines[i]
        m = DECL_RE.match(line)
        if not m:
            i += 1
            continue

        keyword = m.group(1)
        tag = TAG_MAP[keyword]
        decl_line = i + 1  # 1-indexed source line

        # --- Simple one-concept declarations ---
        if keyword in ("datatype", "type_synonym", "record"):
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            e = Entry(tag, _parse_name(rest), f"{tag} {rest}",
                      thy_line=decl_line, decl_end_line=decl_line)
            entries.append(e)
            i += 1
            continue

        if keyword == "axiomatization":
            entries.append(Entry("AXIOM", "axiomatization", "AXIOMATIZATION",
                                 thy_line=decl_line, decl_end_line=decl_line))
            i += 1
            while i < len(lines):
                ax_line = lines[i].strip()
                if re.match(r"[a-z_]+\s*:", ax_line):
                    name = ax_line.split(":")[0].strip()
                    ax_entry = Entry("AXIOM", name, f"  AXIOM {ax_line}",
                                     thy_line=i + 1, decl_end_line=i + 1)
                    entries.append(ax_entry)
                    i += 1
                elif ax_line.startswith("and "):
                    i += 1
                elif ax_line == "" or TOPLEVEL_RE.match(lines[i]):
                    break
                else:
                    i += 1
            continue

        # --- Definitions ---
        if keyword in ("definition", "fun", "primrec"):
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            name = _parse_name(rest)
            buf = [f"{tag} {rest}"]
            decl_end_line = decl_line
            i += 1
            open_quotes = rest.count('"') % 2
            while i < len(lines):
                cline = lines[i]
                if BLANK_RE.match(cline):
                    break
                if DECL_RE.match(cline):
                    break
                stripped = cline.strip()
                if stripped.startswith("\\<comment>") or stripped.startswith("text "):
                    break
                buf.append(f"  {stripped}")
                open_quotes = (open_quotes + stripped.count('"')) % 2
                i += 1
                decl_end_line = i  # 1-indexed line just appended
                if keyword == "definition" and open_quotes == 0 and '"' in stripped:
                    break
            entries.append(Entry(tag, name, "\n".join(buf),
                                 thy_line=decl_line,
                                 decl_end_line=decl_end_line))
            continue

        # --- Lemmas / theorems / corollaries ---
        if keyword in ("lemma", "corollary", "theorem"):
            rest = line[len(keyword):].strip()
            name = _parse_name(rest)
            buf = [f"{tag} {rest}"]
            decl_end_line = decl_line
            proof_line = 0
            i += 1

            while i < len(lines):
                cline = lines[i]
                stripped = cline.strip()
                if BLANK_RE.match(cline):
                    break
                if PROOF_RE.match(cline):
                    proof_line = i + 1
                    break
                if DECL_RE.match(cline):
                    break
                if stripped.startswith("\\<comment>"):
                    i += 1
                    continue
                buf.append(f"  {stripped}")
                i += 1
                decl_end_line = i

            entries.append(Entry(tag, name, "\n".join(buf),
                                 thy_line=decl_line,
                                 decl_end_line=decl_end_line,
                                 proof_line=proof_line))
            continue

        i += 1

    return entries


def compute_spans(entries: list[Entry], section_lines: list[int],
                  total_lines: int) -> None:
    """Set thy_end on each entry to the line before the next entry-or-section."""
    structural = sorted({e.thy_line for e in entries} | set(section_lines))
    for e in entries:
        nxt = [s for s in structural if s > e.thy_line]
        e.thy_end = (nxt[0] - 1) if nxt else total_lines


def _parse_one(thy: str, thy_path: Path) -> TheorySection:
    lines = thy_path.read_text().splitlines()
    entries = extract_entries(lines)
    outline = extract_sections(lines)
    compute_spans(entries, [s[2] for s in outline], len(lines))
    for e in entries:
        e.theory = thy
    return TheorySection(thy, thy_path, entries, thy_lines=len(lines), outline=outline)


def load_registry() -> list[TheorySection]:
    """Walk both ROOT files (base + main), parse all .thy files."""
    sections: list[TheorySection] = []
    seen: set[str] = set()

    def add(thy: str, thy_path: Path) -> None:
        if thy in seen or not thy_path.exists():
            return
        seen.add(thy)
        sections.append(_parse_one(thy, thy_path))

    base_root = ISA_DIR / "base" / "ROOT"
    if base_root.exists():
        for thy in parse_theories_from_root(base_root):
            add(thy, ISA_DIR / "base" / f"{thy}.thy")

    root_path = ISA_DIR / "ROOT"
    if root_path.exists():
        for thy in parse_theories_from_root(root_path):
            thy_path = ISA_DIR / f"{thy}.thy"
            if not thy_path.exists():
                thy_path = ISA_DIR / "base" / f"{thy}.thy"
            add(thy, thy_path)

    return sections


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _format_name_line(sec: TheorySection, entry: Entry) -> str:
    span_size = entry.thy_end - entry.thy_line + 1 if entry.thy_line else 0
    span = f" [src {entry.thy_line}-{entry.thy_end}, {span_size} lines]" if entry.thy_line else ""
    return f"{entry.name} ({entry.tag}) — {sec.theory}{span}"


def _proof_extent(sec: TheorySection, proof_line: int, thy_end: int) -> int:
    """Walk forward from proof_line, return last line that belongs to the proof.
    Stops at `text \\<open>...` blocks, section headers, next declarations, or
    end of file.  Returns proof_line itself for one-line proofs.
    """
    lines = sec.source()
    last = proof_line
    for line_no in range(proof_line + 1, thy_end + 1):
        if line_no > len(lines):
            break
        cline = lines[line_no - 1]
        stripped = cline.strip()
        # Stop at top-level documentation blocks (text \<open>...\<close>) but
        # NOT at in-proof Isar annotations (\<comment> \<open>...\<close>), which
        # are routine inside proof bodies.
        if stripped.startswith("text ") or stripped.startswith("text\\<open>"):
            break
        if SECTION_RE.match(cline):
            break
        if DECL_RE.match(cline):
            break
        if stripped:
            last = line_no
    return last


def render_entry(sec: TheorySection, entry: Entry, verbatim: bool = False) -> str:
    """Render a single entry.

    default mode:   header + verbatim statement + first proof line + remaining count
    verbatim mode:  header + entire source slice [thy_line..thy_end]
    """
    span_size = entry.thy_end - entry.thy_line + 1 if entry.thy_line else 0
    header = (f"--- {entry.name} ({entry.tag}) — {sec.theory}.thy "
              f"[src {entry.thy_line}-{entry.thy_end}, {span_size} lines] ---")

    # No source location (e.g. AXIOM placeholder) → fall back to entry.text
    if not entry.thy_line:
        return f"{header}\n{entry.text}"

    if verbatim:
        body_lines = sec.slice(entry.thy_line, entry.thy_end)
        return header + "\n" + "\n".join(body_lines)

    # Default: statement (verbatim) + first proof line + remaining count
    if entry.proof_line and entry.proof_line >= entry.decl_end_line:
        statement = sec.slice(entry.thy_line, entry.decl_end_line)
        first_proof = sec.slice(entry.proof_line, entry.proof_line)
        proof_end = _proof_extent(sec, entry.proof_line, entry.thy_end)
        remaining = max(0, proof_end - entry.proof_line)
        body = "\n".join(statement + first_proof)
        if remaining > 0:
            body += f"\n  [+{remaining} more proof line{'s' if remaining != 1 else ''}]"
        return header + "\n" + body

    # No proof captured (e.g. one-line lemma, definition, datatype) → just the
    # declaration as recorded by the parser.
    body_lines = sec.slice(entry.thy_line, entry.decl_end_line)
    return header + "\n" + "\n".join(body_lines)


# ---------------------------------------------------------------------------
# Verbosity-mode dispatch
# ---------------------------------------------------------------------------

def _emit_matches(sections_by_theory: dict[str, TheorySection],
                  matches: list[Entry], pattern: str, mode: str,
                  verbatim: bool) -> None:
    if not matches:
        print(f"No entries matching '{pattern}'.")
        return

    if mode == "count":
        print(f"{len(matches)} match(es) for '{pattern}'.")
        return

    if mode == "names":
        for e in matches:
            print(_format_name_line(sections_by_theory[e.theory], e))
        return

    if mode == "all":
        for e in matches:
            print(render_entry(sections_by_theory[e.theory], e, verbatim=verbatim))
            print()
        return

    # mode == "first"
    e0 = matches[0]
    print(render_entry(sections_by_theory[e0.theory], e0, verbatim=verbatim))
    if len(matches) > 1:
        print()
        print(f"[+{len(matches) - 1} more match(es).  Use --all to show, "
              f"--names for a list, --count for just the count.]")


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_summary(sections: list[TheorySection]) -> None:
    total = sum(len(s.entries) for s in sections)
    print(f"# Lemma Index — NDTHT Formalization\n")
    print(f"{total} entries across {len(sections)} theories  "
          f"(parsed live from .thy files)\n")
    print("## Theories\n")
    print("Source-line counts (`.thy` file size), entry counts, and key exports.\n")
    print("| Theory | Src | D | L | T | Key Exports |")
    print("|--------|----:|--:|--:|--:|-------------|")
    for sec in sections:
        defs = [e for e in sec.entries
                if e.tag in ("DEF", "FUN", "DATATYPE", "RECORD", "TYPE")]
        lemmas = [e for e in sec.entries if e.tag == "LEMMA"]
        thms = [e for e in sec.entries if e.tag == "THEOREM"]

        key_names: list[str] = []
        for e in defs:
            if e.name != "?" and e.name not in key_names:
                key_names.append(e.name)
        for e in thms:
            if e.name != "?" and e.name not in key_names:
                key_names.append(e.name)
        if not key_names:
            for e in lemmas[:3]:
                if e.name != "?" and e.name not in key_names:
                    key_names.append(e.name)

        exports = ", ".join(key_names[:6])
        if len(key_names) > 6:
            exports += ", ..."

        print(f"| {sec.theory} | {sec.thy_lines} | "
              f"{len(defs)} | {len(lemmas)} | {len(thms)} | {exports} |")


def _resolve_theory(sections: list[TheorySection], name: str) -> TheorySection | None:
    for s in sections:
        if s.theory == name:
            return s
    for s in sections:
        if s.theory.lower() == name.lower():
            return s
    return None


def cmd_theory(sections: list[TheorySection], name: str, verbatim: bool) -> None:
    sec = _resolve_theory(sections, name)
    if sec is None:
        print(f"Theory '{name}' not found.  Known theories:")
        for s in sorted(sections, key=lambda x: x.theory):
            print(f"  {s.theory}")
        return

    print(f"## {sec.theory}.thy  ({sec.thy_lines} src lines, {len(sec.entries)} entries)")
    if verbatim:
        for e in sec.entries:
            print()
            print(render_entry(sec, e, verbatim=True))
        return

    print("```")
    for e in sec.entries:
        print(e.text)
    print("```")


def cmd_find(sections: list[TheorySection], pattern: str, mode: str,
             verbatim: bool) -> None:
    pat = re.compile(pattern, re.IGNORECASE)
    by_theory = {s.theory: s for s in sections}
    matches: list[Entry] = []
    for s in sections:
        for e in s.entries:
            if pat.search(e.name):
                matches.append(e)
    _emit_matches(by_theory, matches, pattern, mode, verbatim)


def cmd_show(sections: list[TheorySection], name: str, mode: str,
             verbatim: bool) -> None:
    by_theory = {s.theory: s for s in sections}
    matches: list[Entry] = []
    for s in sections:
        for e in s.entries:
            if e.name == name:
                matches.append(e)
    if not matches:
        for s in sections:
            for e in s.entries:
                if e.name.lower() == name.lower():
                    matches.append(e)
    if not matches:
        # Substring fallback
        for s in sections:
            for e in s.entries:
                if name.lower() in e.name.lower():
                    matches.append(e)
    _emit_matches(by_theory, matches, name, mode, verbatim)


def cmd_defs(sections: list[TheorySection], theory: str) -> None:
    sec = _resolve_theory(sections, theory)
    if sec is None:
        print(f"Theory '{theory}' not found.")
        return
    matches = [e for e in sec.entries
               if e.tag in ("DEF", "FUN", "DATATYPE", "RECORD", "TYPE")]
    if not matches:
        print(f"No definitions found in '{sec.theory}'.")
        return
    for e in matches:
        print(render_entry(sec, e))
        print()


def cmd_deps(sections: list[TheorySection], theory: str) -> None:
    """Show theories that come before the given theory in ROOT order."""
    target = _resolve_theory(sections, theory)
    if target is None:
        print(f"Theory '{theory}' not found.")
        return
    idx = sections.index(target)
    if idx == 0:
        print(f"{target.theory} has no upstream dependencies (it's first in ROOT).")
        return
    print(f"Theories before {target.theory} in ROOT order:")
    for s in sections[:idx]:
        print(f"  {s.theory}  ({s.thy_lines} src lines, {len(s.entries)} entries)")


def cmd_outline(sections: list[TheorySection], theory: str) -> None:
    sec = _resolve_theory(sections, theory)
    if sec is None:
        print(f"Theory '{theory}' not found.")
        return

    items: list[tuple[int, str, object]] = []
    for level, title, ln in sec.outline:
        items.append((ln, "section", (level, title)))
    for e in sec.entries:
        if e.thy_line > 0:
            items.append((e.thy_line, "entry", e))
    items.sort(key=lambda x: x[0])

    if not items:
        print(f"No outline data for '{sec.theory}'.")
        return

    print(f"Outline of {sec.theory}.thy:\n")
    for ln, kind, payload in items:
        if kind == "section":
            level, title = payload  # type: ignore[misc]
            indent = {"section": "", "subsection": "  ",
                      "subsubsection": "    "}[level]
            print(f"{indent}{level:>14}: {title}  (line {ln})")
        else:
            e: Entry = payload  # type: ignore[assignment]
            size = e.thy_end - e.thy_line + 1
            print(f"        {e.tag:<8} {e.name}  ({e.thy_line}-{e.thy_end}, {size} lines)")


def cmd_largest(sections: list[TheorySection], args: list[str]) -> None:
    n = 20
    theory_filter: str | None = None
    for a in args:
        if a.isdigit():
            n = int(a)
        else:
            theory_filter = a

    rows: list[tuple[int, Entry, TheorySection]] = []
    for s in sections:
        if theory_filter and s.theory.lower() != theory_filter.lower():
            continue
        for e in s.entries:
            if e.thy_line > 0:
                rows.append((e.thy_end - e.thy_line + 1, e, s))

    rows.sort(key=lambda x: -x[0])

    if not rows:
        print("No entries found.")
        return

    where = f" in {theory_filter}" if theory_filter else ""
    print(f"Top {min(n, len(rows))} largest entries{where}:\n")
    print(f"{'Lines':>6}  {'Tag':<8}  {'Name':<42}  Theory  (span)")
    print(f"{'-' * 6:>6}  {'-' * 8:<8}  {'-' * 42:<42}  ------")
    for size, e, s in rows[:n]:
        print(f"{size:>6}  {e.tag:<8}  {e.name:<42}  {s.theory}  ({e.thy_line}-{e.thy_end})")


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

def _parse_flags(args: list[str]) -> tuple[list[str], str, bool]:
    """Strip flags from args, return (positional, mode, verbatim)."""
    positional: list[str] = []
    mode = "first"
    verbatim = False
    for arg in args:
        if arg in ("-a", "--all"):
            mode = "all"
        elif arg in ("-c", "--count"):
            mode = "count"
        elif arg in ("-n", "--names"):
            mode = "names"
        elif arg in ("-V", "--verbatim"):
            verbatim = True
        else:
            positional.append(arg)
    return positional, mode, verbatim


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]
    rest = sys.argv[2:]

    sections = load_registry()

    if cmd == "summary":
        cmd_summary(sections)
    elif cmd == "theory" and len(rest) >= 1:
        positional, _mode, verbatim = _parse_flags(rest)
        if not positional:
            print("Usage: query-registry.py theory <name> [-V]", file=sys.stderr)
            sys.exit(1)
        cmd_theory(sections, positional[0], verbatim)
    elif cmd == "find" and len(rest) >= 1:
        positional, mode, verbatim = _parse_flags(rest)
        if not positional:
            print("Usage: query-registry.py find <pattern> [-a|-c|-n|-V]", file=sys.stderr)
            sys.exit(1)
        cmd_find(sections, positional[0], mode, verbatim)
    elif cmd == "show" and len(rest) >= 1:
        positional, mode, verbatim = _parse_flags(rest)
        if not positional:
            print("Usage: query-registry.py show <name> [-a|-c|-n|-V]", file=sys.stderr)
            sys.exit(1)
        cmd_show(sections, positional[0], mode, verbatim)
    elif cmd == "defs" and len(rest) >= 1:
        cmd_defs(sections, rest[0])
    elif cmd == "deps" and len(rest) >= 1:
        cmd_deps(sections, rest[0])
    elif cmd == "outline" and len(rest) >= 1:
        cmd_outline(sections, rest[0])
    elif cmd == "largest":
        cmd_largest(sections, rest)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
