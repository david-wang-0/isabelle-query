#!/usr/bin/env python3
r"""Query the lemma index — computed live from .thy files on every invocation.

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

Verbosity / search-mode flags (apply to find and show):
  default       First match: header + statement + first proof line
                + roadmap or count of remaining proof lines.
  -a, --all     Show all matches.
  -c, --count   Just print the match count, no bodies.
  -n, --names   Just print names + tags + theory + src span (no bodies).
  -V, --verbatim
                Print the full source slice (statement + entire proof body)
                from the .thy file.

Comment-context flags (apply to show / outline / theory; --with-comments
applies to find):
  (default)         Include preamble (truncated) above the entry header
                    and roadmap (\<comment> annotations) inside the proof
                    preview, when present.
  --no-comments     Suppress preamble and roadmap (formal-content view).
  --comments-only   Show ONLY preamble + roadmap, skip statement and proof.
  --with-comments   (find) Also search inside text blocks and \<comment>
                    annotations; matches return a context window.

Context-size knob (analogous to `diff -U`):
  -U N, --context N
                    Lines of preview / context to show.  Default 2.
                    For find --with-comments: lines either side of a match.
                    For show / outline / theory: max lines per truncated
                    text block or roadmap entry.
                    Use a large N (or -V) to effectively disable truncation.

Source selection:
  --file PATH       Parse a single arbitrary .thy file instead of walking
                    the project ROOTs.  Useful for inspecting external
                    theories (e.g. --file ../Universal_Turing_Machine/UTM.thy).

Examples:
  query-registry.py summary
  query-registry.py theory MultiTapeNTM
  query-registry.py outline SimFaithfulness
  query-registry.py largest 10 SimFaithfulness
  query-registry.py find threshold                       # name search
  query-registry.py find threshold --with-comments       # also search prose
  query-registry.py find threshold --with-comments -U 4  # wider context
  query-registry.py show sim_faithful_one_step           # default with roadmap
  query-registry.py show sim_faithful_one_step --comments-only
  query-registry.py show sim_faithful_one_step --no-comments
  query-registry.py show sim_faithful_one_step -V        # full source
  query-registry.py --file ../Universal_Turing_Machine/UTM.thy outline UTM

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
    # Comment context attached during _parse_one:
    preamble: tuple[int, int] | None = None
        # (start, end) of the `text \<open>...\<close>` block immediately
        # preceding this entry, if one exists within ~3 blank lines.
    roadmap: list[tuple[int, str]] = field(default_factory=list)
        # (line_no, content) for `\<comment> \<open>...\<close>` annotations
        # found inside this entry's proof body.


@dataclass
class TheorySection:
    theory: str
    path: Path
    entries: list[Entry]
    thy_lines: int = 0
    outline: list[tuple[str, str, int]] = field(default_factory=list)
    text_blocks: list[tuple[int, int]] = field(default_factory=list)
        # All top-level text blocks in the theory, used for `outline` rendering
        # (per-entry preambles are stored on Entry.preamble).
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
SECTION_RE = re.compile(r"^(chapter|section|subsection|subsubsection)\s+\\<open>(.*)")
TEXT_OPEN_RE = re.compile(r"^\s*(text|text_raw)\s*\\<open>")
COMMENT_LINE_RE = re.compile(r"\\<comment>\s*\\<open>(.*)$")
LATEX_LINE_RE = re.compile(
    r"\\(begin|end|caption|node|draw|newlength|newcommand|settowidth|settoheight|scalebox|label)\b"
)
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


def _find_balanced_close(lines: list[str], start: int) -> int:
    """Given a 0-indexed start line that opens a `\\<open>` block, return the
    0-indexed line of the matching `\\<close>` (counts open/close balance).
    Returns start if no balance found (malformed).
    """
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("\\<open>")
        depth -= lines[i].count("\\<close>")
        if depth <= 0 and i >= start:
            return i
    return start


def extract_text_blocks(lines: list[str]) -> list[tuple[int, int]]:
    """Return [(start_line, end_line)] (1-indexed inclusive) for top-level
    `text \\<open>...\\<close>` and `text_raw` blocks.  Body is not stored —
    callers slice from sec.source() when needed.
    """
    out = []
    i = 0
    while i < len(lines):
        if TEXT_OPEN_RE.match(lines[i]):
            end = _find_balanced_close(lines, i)
            out.append((i + 1, end + 1))
            i = end + 1
        else:
            i += 1
    return out


def extract_comment_lines(lines: list[str]) -> list[tuple[int, str]]:
    """Return [(line_no, content)] for in-proof `\\<comment> \\<open>...\\<close>`
    annotations.  `content` is the prose text inside the `\\<open>...\\<close>`
    on the comment's first line (truncated at the first `\\<close>` if present).
    """
    out = []
    for i, line in enumerate(lines, 1):
        m = COMMENT_LINE_RE.search(line)
        if not m:
            continue
        rest = m.group(1)
        close_idx = rest.find("\\<close>")
        content = rest[:close_idx] if close_idx >= 0 else rest
        out.append((i, content.strip()))
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


def _attach_comments(entries: list[Entry], lines: list[str],
                     text_blocks: list[tuple[int, int]],
                     comment_lines: list[tuple[int, str]]) -> None:
    """Attach text blocks (preambles) and \\<comment> lines (roadmaps) to
    the entries they belong to.

    Preamble: text block whose `end` line is within ~3 blank lines of an
    entry's `thy_line`.  Avoids attaching a giant top-of-file narrative
    to the very first entry hundreds of lines later.

    Roadmap: \\<comment> line whose line number lies inside the entry's
    proof span [proof_line+1 .. _proof_extent(...)].
    """
    # --- preambles: text block → next entry, only if adjacent AND small ---
    # Both conditions matter: a 500-line section narrative just before the
    # first definition is NOT that definition's docstring; it's the chapter's
    # introduction.  See UTM.thy lines 28-530 for the canonical example.
    PREAMBLE_MAX_LINES = 30
    entry_starts = sorted([(e.thy_line, e) for e in entries if e.thy_line > 0])
    for tb_start, tb_end in text_blocks:
        if tb_end - tb_start + 1 > PREAMBLE_MAX_LINES:
            continue  # too big to be a per-entry docstring
        # Find the first entry whose thy_line is > tb_end.
        for es, e in entry_starts:
            if es <= tb_end:
                continue
            # Are intervening lines (tb_end+1 .. es-1) all blank?
            gap = lines[tb_end:es - 1]
            if all(not l.strip() for l in gap) and len(gap) <= 3:
                e.preamble = (tb_start, tb_end)
            break

    # --- roadmaps: comment line → containing entry's proof span ---
    for cline, content in comment_lines:
        for e in entries:
            if e.proof_line and e.proof_line < cline <= e.thy_end:
                e.roadmap.append((cline, content))
                break


def _parse_one(thy: str, thy_path: Path) -> TheorySection:
    lines = thy_path.read_text().splitlines()
    entries = extract_entries(lines)
    outline = extract_sections(lines)
    text_blocks = extract_text_blocks(lines)
    comment_lines = extract_comment_lines(lines)
    compute_spans(entries, [s[2] for s in outline], len(lines))
    _attach_comments(entries, lines, text_blocks, comment_lines)
    for e in entries:
        e.theory = thy
    return TheorySection(thy, thy_path, entries, thy_lines=len(lines),
                         outline=outline, text_blocks=text_blocks)


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


def _is_latex_noise(line: str) -> bool:
    """Lines containing LaTeX figure/typesetting markup we want to skip in
    truncated previews of text blocks (e.g. UTM.thy's tikzpicture diagrams)."""
    return bool(LATEX_LINE_RE.search(line))


def _strip_text_wrapper(lines: list[str]) -> list[str]:
    """Strip leading `text \\<open>` / `text_raw \\<open>` and trailing
    `\\<close>` from a text block body, so previews don't show the wrapper.
    Returns a copy; does nothing if the markers aren't found.
    """
    if not lines:
        return lines
    out = list(lines)
    # Strip leading "text \<open>" or "text_raw \<open>"
    first = out[0]
    m = re.match(r"^(\s*)(?:text_raw|text)\s*\\<open>\s*(.*)$", first)
    if m:
        stripped_first = (m.group(1) + m.group(2)).rstrip()
        if stripped_first:
            out[0] = stripped_first
        else:
            out = out[1:]
    if not out:
        return out
    # Strip trailing "\<close>" from last line
    last = out[-1]
    if last.rstrip().endswith("\\<close>"):
        trimmed = last.rstrip()[: -len("\\<close>")].rstrip()
        if trimmed:
            out[-1] = trimmed
        else:
            out = out[:-1]
    return out


def _truncate_preview(lines: list[str], n: int,
                      skip_latex: bool = True) -> tuple[list[str], int]:
    """Return (preview_lines, omitted_count).  Picks up to N non-blank,
    non-LaTeX content lines from the start of `lines`.  `omitted_count` is
    how many *original* lines were not included in the preview.
    """
    if n <= 0:
        return [], len(lines)
    out = []
    consumed = 0
    for line in lines:
        consumed += 1
        if not line.strip():
            continue
        if skip_latex and _is_latex_noise(line):
            continue
        out.append(line)
        if len(out) >= n:
            break
    omitted = len(lines) - consumed
    return out, max(0, omitted)


def _render_preamble(sec: TheorySection, preamble: tuple[int, int],
                     mode: str, context: int) -> str:
    """Render a preamble text block.

    mode='summary': first `context` content lines + "[+N more preamble lines]"
    mode='full':    full slice, wrapper stripped
    """
    start, end = preamble
    body = _strip_text_wrapper(sec.slice(start, end))
    block_size = len(body)
    if mode == "full":
        return "\n".join(body)
    preview, _ = _truncate_preview(body, context)
    suffix = ""
    shown = len(preview)
    remaining = block_size - shown
    if remaining > 0:
        suffix = (f"\n  [+{remaining} more preamble line{'s' if remaining != 1 else ''}, "
                  f"use --comments-only or -V to see]")
    return "\n".join(preview) + suffix


def _render_roadmap(roadmap: list[tuple[int, str]], context: int,
                    proof_remaining: int, mode: str) -> str:
    """Render a proof roadmap (extracted \\<comment> annotations).

    mode='summary': first `context` annotations + "...(N total of M proof lines)"
    mode='full':    all annotations
    """
    if not roadmap:
        # Fallback: show the existing "+N more proof lines" count line.
        if proof_remaining > 0:
            return (f"  [+{proof_remaining} more proof line"
                    f"{'s' if proof_remaining != 1 else ''}]")
        return ""
    if mode == "full":
        shown = roadmap
    else:
        shown = roadmap[:max(1, context)]
    out = []
    for ln, content in shown:
        out.append(f"  | line {ln}: {content}")
    if mode != "full" and len(roadmap) > len(shown):
        rest = len(roadmap) - len(shown)
        out.append(f"  | ...({rest} more annotation{'s' if rest != 1 else ''} "
                   f"in {proof_remaining}-line proof, use -U N to see more)")
    return "\n".join(out)


def render_entry(sec: TheorySection, entry: Entry, *,
                 verbatim: bool = False,
                 comments: str = "on",
                 context: int = 2) -> str:
    """Render a single entry.

    verbatim:        full source slice [thy_line..thy_end]
    comments='on':   preamble (truncated) + header + statement + proof preview
                     + roadmap (truncated)
    comments='off':  header + statement + proof preview only (current default)
    comments='only': preamble (full) + header + roadmap (full), no statement
    context:         lines of preamble preview / roadmap entries shown
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

    out_parts: list[str] = []

    # Preamble (above header)
    if comments != "off" and entry.preamble:
        pmode = "full" if comments == "only" else "summary"
        rendered = _render_preamble(sec, entry.preamble, pmode, context)
        if rendered:
            pstart, pend = entry.preamble
            out_parts.append(f"--- preamble [{pstart}-{pend}] ---")
            out_parts.append(rendered)
            out_parts.append("")

    out_parts.append(header)

    if comments == "only":
        # Skip statement + proof; show only roadmap (full).
        if entry.roadmap:
            out_parts.append("--- roadmap (\\<comment> annotations) ---")
            proof_end = _proof_extent(sec, entry.proof_line, entry.thy_end) \
                if entry.proof_line else entry.thy_end
            out_parts.append(_render_roadmap(entry.roadmap, context,
                                             proof_end - entry.proof_line, "full"))
        elif not entry.preamble:
            out_parts.append("(no comment context for this entry)")
        return "\n".join(out_parts)

    # Statement + proof preview
    if entry.proof_line and entry.proof_line >= entry.decl_end_line:
        statement = sec.slice(entry.thy_line, entry.decl_end_line)
        first_proof = sec.slice(entry.proof_line, entry.proof_line)
        proof_end = _proof_extent(sec, entry.proof_line, entry.thy_end)
        remaining = max(0, proof_end - entry.proof_line)
        out_parts.append("\n".join(statement + first_proof))
        if comments != "off" and entry.roadmap:
            out_parts.append(_render_roadmap(entry.roadmap, context,
                                             remaining, "summary"))
        elif remaining > 0:
            out_parts.append(f"  [+{remaining} more proof line"
                             f"{'s' if remaining != 1 else ''}]")
    else:
        # No proof captured → just the declaration as recorded by the parser.
        body_lines = sec.slice(entry.thy_line, entry.decl_end_line)
        out_parts.append("\n".join(body_lines))

    return "\n".join(out_parts)


# ---------------------------------------------------------------------------
# Verbosity-mode dispatch
# ---------------------------------------------------------------------------

def _emit_matches(sections_by_theory: dict[str, TheorySection],
                  matches: list[Entry], pattern: str, flags: "CmdFlags") -> None:
    if not matches:
        print(f"No entries matching '{pattern}'.")
        return

    if flags.mode == "count":
        print(f"{len(matches)} match(es) for '{pattern}'.")
        return

    if flags.mode == "names":
        for e in matches:
            print(_format_name_line(sections_by_theory[e.theory], e))
        return

    if flags.mode == "all":
        for e in matches:
            print(render_entry(sections_by_theory[e.theory], e,
                               verbatim=flags.verbatim,
                               comments=flags.comments,
                               context=flags.context))
            print()
        return

    # mode == "first"
    e0 = matches[0]
    print(render_entry(sections_by_theory[e0.theory], e0,
                       verbatim=flags.verbatim,
                       comments=flags.comments,
                       context=flags.context))
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


def cmd_theory(sections: list[TheorySection], name: str,
               flags: "CmdFlags") -> None:
    sec = _resolve_theory(sections, name)
    if sec is None:
        print(f"Theory '{name}' not found.  Known theories:")
        for s in sorted(sections, key=lambda x: x.theory):
            print(f"  {s.theory}")
        return

    print(f"## {sec.theory}.thy  ({sec.thy_lines} src lines, {len(sec.entries)} entries)")
    if flags.verbatim:
        for e in sec.entries:
            print()
            print(render_entry(sec, e, verbatim=True))
        return

    # Default: pre-formatted entries, optionally with preamble headers
    print("```")
    for e in sec.entries:
        if flags.comments != "off" and e.preamble:
            ps, pe = e.preamble
            body = _strip_text_wrapper(sec.slice(ps, pe))
            preview, _ = _truncate_preview(body, flags.context)
            if preview:
                print()
                print(f"[preamble {ps}-{pe}]: " + " ".join(
                    line.strip() for line in preview))
        print(e.text)
    print("```")


def cmd_find(sections: list[TheorySection], pattern: str,
             flags: "CmdFlags") -> None:
    pat = re.compile(pattern, re.IGNORECASE)
    by_theory = {s.theory: s for s in sections}
    matches: list[Entry] = []
    for s in sections:
        for e in s.entries:
            if pat.search(e.name):
                matches.append(e)

    _emit_matches(by_theory, matches, pattern, flags)

    if flags.with_comments:
        # Additionally search inside preamble bodies and roadmap content,
        # producing context windows.
        comment_hits = _find_in_comments(sections, pat, flags.context)
        if comment_hits:
            print()
            print(f"--- comment matches for '{pattern}' "
                  f"({len(comment_hits)} hit(s)) ---")
            for hit in comment_hits:
                print(hit)


def _find_in_comments(sections: list[TheorySection], pat: re.Pattern,
                      context: int) -> list[str]:
    """Search inside text blocks and \\<comment> annotations across all
    theories.  Returns formatted hit strings: filename:line + context window.
    """
    hits: list[str] = []
    for sec in sections:
        src = sec.source()
        # Text blocks (preambles + standalone)
        for tb_start, tb_end in sec.text_blocks:
            for ln in range(tb_start, tb_end + 1):
                if ln > len(src):
                    break
                line = src[ln - 1]
                if pat.search(line):
                    lo = max(tb_start, ln - context)
                    hi = min(tb_end, ln + context)
                    snippet = src[lo - 1:hi]
                    hits.append(f"\n{sec.theory}.thy:{ln} "
                                f"(in text block {tb_start}-{tb_end}):")
                    for j, snippet_line in enumerate(snippet, start=lo):
                        marker = ">" if j == ln else " "
                        hits.append(f"  {marker} {j}: {snippet_line}")
        # Inline \<comment> annotations: each entry's roadmap
        for e in sec.entries:
            for ln, content in e.roadmap:
                if pat.search(content):
                    hits.append(f"\n{sec.theory}.thy:{ln} "
                                f"(\\<comment> in {e.name}): {content}")
    return hits


def cmd_show(sections: list[TheorySection], name: str,
             flags: "CmdFlags") -> None:
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
    _emit_matches(by_theory, matches, name, flags)


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


def cmd_outline(sections: list[TheorySection], theory: str,
                flags: "CmdFlags") -> None:
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
    if flags.comments != "off":
        for tb_start, tb_end in sec.text_blocks:
            items.append((tb_start, "text", (tb_start, tb_end)))
    items.sort(key=lambda x: x[0])

    if not items:
        print(f"No outline data for '{sec.theory}'.")
        return

    print(f"Outline of {sec.theory}.thy:\n")
    for ln, kind, payload in items:
        if kind == "section":
            level, title = payload  # type: ignore[misc]
            indent = {"chapter": "", "section": "", "subsection": "  ",
                      "subsubsection": "    "}[level]
            print(f"{indent}{level:>14}: {title}  (line {ln})")
        elif kind == "text":
            tb_start, tb_end = payload  # type: ignore[misc]
            block_size = tb_end - tb_start + 1
            body = _strip_text_wrapper(sec.slice(tb_start, tb_end))
            preview, _ = _truncate_preview(body, flags.context)
            preview_text = " ".join(line.strip() for line in preview)
            if len(preview_text) > 100:
                preview_text = preview_text[:97] + "..."
            print(f"        text     [{tb_start}-{tb_end}, {block_size} lines]: "
                  f"{preview_text}")
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

@dataclass
class CmdFlags:
    mode: str = "first"          # first / all / count / names
    verbatim: bool = False       # -V / --verbatim
    comments: str = "on"         # on / off / only
    context: int = 2             # -U N / --context N
    with_comments: bool = False  # --with-comments (find only)
    file: str | None = None      # --file PATH (parse a single arbitrary .thy)


def _parse_flags(args: list[str]) -> tuple[list[str], CmdFlags]:
    """Strip flags from args, return (positional, flags)."""
    positional: list[str] = []
    f = CmdFlags()
    i = 0
    while i < len(args):
        arg = args[i]
        if arg in ("-a", "--all"):
            f.mode = "all"
        elif arg in ("-c", "--count"):
            f.mode = "count"
        elif arg in ("-n", "--names"):
            f.mode = "names"
        elif arg in ("-V", "--verbatim"):
            f.verbatim = True
        elif arg == "--no-comments":
            f.comments = "off"
        elif arg == "--comments-only":
            f.comments = "only"
        elif arg == "--with-comments":
            f.with_comments = True
        elif arg in ("-U", "--context") and i + 1 < len(args):
            f.context = int(args[i + 1])
            i += 1
        elif arg.startswith("-U"):
            f.context = int(arg[2:])
        elif arg.startswith("--context="):
            f.context = int(arg.split("=", 1)[1])
        elif arg == "--file" and i + 1 < len(args):
            f.file = args[i + 1]
            i += 1
        elif arg.startswith("--file="):
            f.file = arg.split("=", 1)[1]
        else:
            positional.append(arg)
        i += 1
    return positional, f


def load_single_file(path: str) -> list[TheorySection]:
    p = Path(path).expanduser().resolve()
    if not p.exists():
        print(f"ERROR: file not found: {p}", file=sys.stderr)
        sys.exit(1)
    return [_parse_one(p.stem, p)]


def _extract_global_file(argv: list[str]) -> tuple[str | None, list[str]]:
    """Pull `--file PATH` (or `--file=PATH`) out of argv at any position
    BEFORE the command.  Returns (path_or_None, remaining_argv).
    """
    out: list[str] = []
    file: str | None = None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--file" and i + 1 < len(argv):
            file = argv[i + 1]
            i += 2
            continue
        if a.startswith("--file="):
            file = a.split("=", 1)[1]
            i += 1
            continue
        out.append(a)
        i += 1
    return file, out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    # Allow --file as a global flag before the command (git -C style)
    global_file, argv_rest = _extract_global_file(sys.argv[1:])
    if not argv_rest:
        print(__doc__)
        sys.exit(1)

    cmd = argv_rest[0]
    rest = argv_rest[1:]

    positional, flags = _parse_flags(rest)
    if global_file and not flags.file:
        flags.file = global_file

    if flags.file:
        sections = load_single_file(flags.file)
    else:
        sections = load_registry()

    if cmd == "summary":
        cmd_summary(sections)
    elif cmd == "theory" and len(positional) >= 1:
        cmd_theory(sections, positional[0], flags)
    elif cmd == "find" and len(positional) >= 1:
        cmd_find(sections, positional[0], flags)
    elif cmd == "show" and len(positional) >= 1:
        cmd_show(sections, positional[0], flags)
    elif cmd == "defs" and len(positional) >= 1:
        cmd_defs(sections, positional[0])
    elif cmd == "deps" and len(positional) >= 1:
        cmd_deps(sections, positional[0])
    elif cmd == "outline" and len(positional) >= 1:
        cmd_outline(sections, positional[0], flags)
    elif cmd == "largest":
        cmd_largest(sections, positional)
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
