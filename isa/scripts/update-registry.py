#!/usr/bin/env python3
"""Extract definitions, lemmas, theorems from .thy files into a lemma registry.

Captures full multi-line statements (assumes/shows/fixes/where clauses)
up to the proof keyword.  Produces two files:

  registry/registry.md  — full multi-line entries, one section per theory
  registry/index.md     — compact index: theory table + name→location map

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

NAME_RE = re.compile(r"^(\w+)")  # first word after tag


def _parse_name(text_after_tag: str) -> str:
    """Extract the identifier from formatted text (everything after the tag)."""
    m = NAME_RE.match(text_after_tag.strip())
    return m.group(1) if m else "?"


def extract_entries(thy_path: Path) -> list[Entry]:
    lines = thy_path.read_text().splitlines()
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

        # --- Simple one-concept declarations ---
        if keyword in ("datatype", "type_synonym", "record"):
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            entries.append(Entry(tag, _parse_name(rest), f"{tag} {rest}"))
            i += 1
            continue

        if keyword == "axiomatization":
            entries.append(Entry("AXIOM", "axiomatization", "AXIOMATIZATION"))
            i += 1
            while i < len(lines):
                ax_line = lines[i].strip()
                if re.match(r"[a-z_]+\s*:", ax_line):
                    name = ax_line.split(":")[0].strip()
                    entries.append(Entry("AXIOM", name, f"  AXIOM {ax_line}"))
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
            entries.append(Entry(tag, name, "\n".join(buf)))
            continue

        # --- Lemmas/theorems/corollaries ---
        if keyword in ("lemma", "corollary", "theorem"):
            rest = line[len(keyword):].strip()
            name = _parse_name(rest)
            buf = [f"{tag} {rest}"]
            i += 1

            if re.search(r'".+"\s*$', rest) and "assumes" not in rest:
                entries.append(Entry(tag, name, buf[0]))
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

            entries.append(Entry(tag, name, "\n".join(buf)))
            continue

        i += 1

    return entries

# ---------------------------------------------------------------------------
# Output: registry
# ---------------------------------------------------------------------------

@dataclass
class TheorySection:
    theory: str
    entries: list[Entry]
    start_line: int = 0   # line in registry.md (1-based)
    end_line: int = 0


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
    """Write the compact index file."""
    with open(path, "w") as f:
        f.write(f"# Lemma Index — NDTHT Formalization\n\n")
        f.write(f"Auto-generated on {date.today()}.  "
                f"{total} entries across {len(sections)} theories.\n")
        f.write(f"Full registry: `isa/registry/registry.md`\n")
        f.write(f"Query tool: `isa/scripts/query-registry.py`\n\n")

        # --- Theory summary table ---
        f.write("## Theories\n\n")
        f.write("| Theory | Lines | D | L | T | Key Exports |\n")
        f.write("|--------|------:|--:|--:|--:|-------------|\n")

        for sec in sections:
            defs = [e for e in sec.entries if e.tag in ("DEF", "FUN", "DATATYPE", "RECORD", "TYPE")]
            lemmas = [e for e in sec.entries if e.tag == "LEMMA"]
            thms = [e for e in sec.entries if e.tag == "THEOREM"]

            # Key exports: definitions first, then theorems, then first few lemmas
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

            lines_str = f"{sec.start_line}-{sec.end_line}"
            f.write(f"| {sec.theory} | {lines_str} | "
                    f"{len(defs)} | {len(lemmas)} | {len(thms)} | "
                    f"{exports} |\n")

        # --- Name index (sorted) ---
        f.write("\n## Names\n\n")
        f.write("Sorted index of all named entries.  "
                "Format: `name (TAG) — Theory [line]`\n\n")
        f.write("```\n")

        all_entries: list[tuple[str, Entry, TheorySection]] = []
        for sec in sections:
            # Compute per-entry line numbers
            lineno = sec.start_line + 2  # skip "## Theory.thy\n```\n"
            for entry in sec.entries:
                if entry.name != "?" and entry.name != "axiomatization":
                    all_entries.append((entry.name.lower(), entry, sec))
                lineno += entry.text.count("\n") + 1

        # Re-compute with actual line tracking
        all_entries = []
        for sec in sections:
            lineno = sec.start_line + 2
            for entry in sec.entries:
                if entry.name != "?" and entry.name != "axiomatization":
                    all_entries.append((lineno, entry, sec))
                lineno += entry.text.count("\n") + 1

        all_entries.sort(key=lambda x: x[1].name.lower())

        for lineno, entry, sec in all_entries:
            f.write(f"{entry.name} ({entry.tag}) — {sec.theory} [{lineno}]\n")

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
        entries = extract_entries(thy_path)
        for e in entries:
            e.theory = thy
        total += len(entries)
        sections.append(TheorySection(thy, entries))

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
