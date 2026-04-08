#!/usr/bin/env python3
"""Extract definitions, lemmas, theorems from .thy files into a lemma registry.

Captures full multi-line statements (assumes/shows/fixes/where clauses)
up to the proof keyword, plus the source span (start..end line) of each
entry, the .thy file's section/subsection structure, and the file's total
line count.  Produces two files:

  registry/registry.md  — full multi-line entries, one section per theory
  registry/index.md     — compact index: theory table, registry locations,
                          per-theory outline (sections), name→location map

Usage: ./scripts/update-registry.py
"""

import re
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

ISA_DIR = Path(__file__).resolve().parent.parent
REGISTRY_DIR = ISA_DIR / "registry"
REGISTRY_PATH = REGISTRY_DIR / "registry.md"
INDEX_PATH = REGISTRY_DIR / "index.md"

# Also keep a copy in memory for backward compatibility
MEMORY_DIR = Path.home() / ".claude/projects/-Users-as456-projects-ndtht/memory"

# ---------------------------------------------------------------------------
# ROOT parsing
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# Entry extraction
# ---------------------------------------------------------------------------

@dataclass
class Entry:
    tag: str          # DEF, FUN, LEMMA, THEOREM, DATATYPE, TYPE, RECORD, AXIOM
    name: str         # identifier
    text: str         # full formatted text (may be multi-line)
    theory: str = ""  # set after extraction
    thy_line: int = 0 # 1-indexed start line in the .thy source file
    thy_end: int = 0  # 1-indexed end line in the .thy source file (heuristic)

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

NAME_RE = re.compile(r"^(\w+)")  # first word after tag


def _parse_name(text_after_tag: str) -> str:
    """Extract the identifier from formatted text (everything after the tag)."""
    m = NAME_RE.match(text_after_tag.strip())
    return m.group(1) if m else "?"


def extract_sections(lines: list[str]) -> list[tuple[str, str, int]]:
    """Return [(level, title, line)] for each section header in the .thy file.
    Section title may be partial if it spans multiple lines; we capture the
    portion on the header line, which is enough for outline display.
    """
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

        def push(e: Entry) -> None:
            e.thy_line = decl_line
            entries.append(e)

        # --- Simple one-concept declarations ---
        if keyword in ("datatype", "type_synonym", "record"):
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            push(Entry(tag, _parse_name(rest), f"{tag} {rest}"))
            i += 1
            continue

        if keyword == "axiomatization":
            push(Entry("AXIOM", "axiomatization", "AXIOMATIZATION"))
            i += 1
            while i < len(lines):
                ax_line = lines[i].strip()
                if re.match(r"[a-z_]+\s*:", ax_line):
                    name = ax_line.split(":")[0].strip()
                    ax_entry = Entry("AXIOM", name, f"  AXIOM {ax_line}")
                    ax_entry.thy_line = i + 1
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
                if keyword == "definition" and open_quotes == 0 and '"' in stripped:
                    break
            push(Entry(tag, name, "\n".join(buf)))
            continue

        # --- Lemmas/theorems/corollaries ---
        if keyword in ("lemma", "corollary", "theorem"):
            rest = line[len(keyword):].strip()
            name = _parse_name(rest)
            buf = [f"{tag} {rest}"]
            i += 1

            if re.search(r'".+"\s*$', rest) and "assumes" not in rest:
                push(Entry(tag, name, buf[0]))
                continue

            while i < len(lines):
                cline = lines[i]
                stripped = cline.strip()
                if BLANK_RE.match(cline):
                    break
                if PROOF_RE.match(cline):
                    break
                if DECL_RE.match(cline):
                    break
                if stripped.startswith("\\<comment>"):
                    i += 1
                    continue
                buf.append(f"  {stripped}")
                i += 1

            push(Entry(tag, name, "\n".join(buf)))
            continue

        i += 1

    return entries


def compute_spans(entries: list[Entry], section_lines: list[int],
                  total_lines: int) -> None:
    """Set thy_end on each entry to the line before the next entry-or-section.
    Last entry runs to end-of-file.  This is a structural span (chunk size),
    not a strict end-of-proof; good enough for size ranking and navigation.
    """
    structural = sorted({e.thy_line for e in entries} | set(section_lines))
    for e in entries:
        nxt = [s for s in structural if s > e.thy_line]
        e.thy_end = (nxt[0] - 1) if nxt else total_lines

# ---------------------------------------------------------------------------
# Output: registry
# ---------------------------------------------------------------------------

@dataclass
class TheorySection:
    theory: str
    entries: list[Entry]
    start_line: int = 0   # line in registry.md (1-based)
    end_line: int = 0
    thy_lines: int = 0    # total .thy source line count
    outline: list[tuple[str, str, int]] = field(default_factory=list)


def write_registry(sections: list[TheorySection], path: Path) -> None:
    """Write the full registry and record line numbers in each section."""
    lineno = 1
    with open(path, "w") as f:
        header = (
            f"# Lemma Registry — NDTHT Formalization\n\n"
            f"Auto-generated by `isa/scripts/update-registry.py` on {date.today()}.\n"
            f"Query with: `isa/scripts/query-registry.py <command> [args]`\n\n"
        )
        f.write(header)
        lineno += header.count("\n")

        for sec in sections:
            sec.start_line = lineno
            f.write(f"## {sec.theory}.thy\n```\n")
            lineno += 2
            for entry in sec.entries:
                f.write(entry.text + "\n")
                lineno += entry.text.count("\n") + 1
            f.write("```\n\n")
            lineno += 2
            sec.end_line = lineno - 1

# ---------------------------------------------------------------------------
# Output: index
# ---------------------------------------------------------------------------

def write_index(sections: list[TheorySection], path: Path, total: int) -> None:
    """Write the compact index file.

    Sections written:
      ## Theories       — summary table (theory | src lines | def/lem/thm | exports)
      ## Registry       — registry.md location of each theory section
      ## Outlines       — per-theory section structure (level, title, line)
      ## Names          — sorted name index with src spans
    """
    with open(path, "w") as f:
        f.write(f"# Lemma Index — NDTHT Formalization\n\n")
        f.write(f"Auto-generated on {date.today()}.  "
                f"{total} entries across {len(sections)} theories.\n")
        f.write(f"Full registry: `isa/registry/registry.md`\n")
        f.write(f"Query tool: `isa/scripts/query-registry.py`\n\n")

        # --- Theory summary table ---
        f.write("## Theories\n\n")
        f.write("Source-line counts (`.thy` file size), entry counts, and key exports.\n\n")
        f.write("| Theory | Src | D | L | T | Key Exports |\n")
        f.write("|--------|----:|--:|--:|--:|-------------|\n")

        for sec in sections:
            defs = [e for e in sec.entries if e.tag in ("DEF", "FUN", "DATATYPE", "RECORD", "TYPE")]
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

            f.write(f"| {sec.theory} | {sec.thy_lines} | "
                    f"{len(defs)} | {len(lemmas)} | {len(thms)} | "
                    f"{exports} |\n")

        # --- Registry locations (used by `query-registry.py theory <name>`) ---
        f.write("\n## Registry\n\n")
        f.write("`Theory: start-end` line range in `registry.md`.\n\n```\n")
        for sec in sections:
            f.write(f"{sec.theory}: {sec.start_line}-{sec.end_line}\n")
        f.write("```\n")

        # --- Outlines: per-theory section structure ---
        f.write("\n## Outlines\n\n")
        f.write("Section/subsection structure of each `.thy` file with source line numbers.\n\n")
        for sec in sections:
            if not sec.outline:
                continue
            f.write(f"### {sec.theory}\n```\n")
            for level, title, line in sec.outline:
                if level == "section":
                    indent, prefix = "", "section"
                elif level == "subsection":
                    indent, prefix = "  ", "subsection"
                else:
                    indent, prefix = "    ", "subsubsection"
                f.write(f"{indent}{prefix}: {title} ({line})\n")
            f.write("```\n\n")

        # --- Name index (sorted) ---
        f.write("## Names\n\n")
        f.write("Sorted index of all named entries.  "
                "Format: `name (TAG) — Theory [reg-line] (src start-end)`\n")
        f.write("`reg-line` is the line in `registry.md`; `src start-end` is the span "
                "in the `.thy` source file.\n\n```\n")

        all_entries: list[tuple[int, Entry, TheorySection]] = []
        for sec in sections:
            lineno = sec.start_line + 2  # skip "## Theory.thy\n```\n"
            for entry in sec.entries:
                if entry.name not in ("?", "axiomatization"):
                    all_entries.append((lineno, entry, sec))
                lineno += entry.text.count("\n") + 1

        all_entries.sort(key=lambda x: x[1].name.lower())

        for lineno, entry, sec in all_entries:
            span = f" (src {entry.thy_line}-{entry.thy_end})" if entry.thy_line else ""
            f.write(f"{entry.name} ({entry.tag}) — {sec.theory} [{lineno}]{span}\n")

        f.write("```\n")

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    root_path = ISA_DIR / "ROOT"
    if not root_path.exists():
        print(f"ERROR: no ROOT file in {ISA_DIR}", file=sys.stderr)
        sys.exit(1)

    # Collect theories from both sessions: base first, then main
    theory_dirs: list[tuple[str, Path]] = []  # (name, thy_path)

    base_root = ISA_DIR / "base" / "ROOT"
    if base_root.exists():
        for thy in parse_theories_from_root(base_root):
            thy_path = ISA_DIR / "base" / f"{thy}.thy"
            if thy_path.exists():
                theory_dirs.append((thy, thy_path))

    for thy in parse_theories_from_root(root_path):
        thy_path = ISA_DIR / f"{thy}.thy"
        if not thy_path.exists():
            thy_path = ISA_DIR / "base" / f"{thy}.thy"
        if thy_path.exists():
            theory_dirs.append((thy, thy_path))

    sections: list[TheorySection] = []
    total = 0
    for thy, thy_path in theory_dirs:
        lines = thy_path.read_text().splitlines()
        entries = extract_entries(lines)
        outline = extract_sections(lines)
        compute_spans(entries, [s[2] for s in outline], len(lines))

        for e in entries:
            e.theory = thy
        total += len(entries)

        sec = TheorySection(thy, entries, thy_lines=len(lines), outline=outline)
        sections.append(sec)

    REGISTRY_DIR.mkdir(parents=True, exist_ok=True)

    write_registry(sections, REGISTRY_PATH)
    write_index(sections, INDEX_PATH, total)

    # Symlink into memory for backward compatibility
    memory_link = MEMORY_DIR / "lemma-registry.md"
    if memory_link.is_symlink():
        memory_link.unlink()
    elif memory_link.exists():
        memory_link.unlink()
    memory_link.symlink_to(REGISTRY_PATH)

    memory_index_link = MEMORY_DIR / "lemma-index.md"
    if memory_index_link.is_symlink():
        memory_index_link.unlink()
    elif memory_index_link.exists():
        memory_index_link.unlink()
    memory_index_link.symlink_to(INDEX_PATH)

    print(f"Registry: {REGISTRY_PATH} ({total} entries)")
    print(f"Index:    {INDEX_PATH}")


if __name__ == "__main__":
    main()
