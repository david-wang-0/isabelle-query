#!/usr/bin/env python3
r"""Query the theory index — computed live from .thy files on every invocation.

All commands re-parse the theory tree (<100ms).  Results are always in
sync with the current .thy source.  Use -h/--help on any subcommand for
its options.

File organisation (section banners below mark each):

* **Parsing** — `Entry` / `TheorySection` / `CallGraph` dataclasses,
  ROOT walking, .thy parsing, span attribution.
* **Call graph and shared filter helpers** —
  `_build_text_ranges` / `_build_def_sites` provide the per-theory
  exclusion ranges (prose text blocks; definition-site spans) shared by
  single-name search (`_find_callers`) and bulk graph construction
  (`_build_call_graph`).  `_transitive_closure` is the BFS used by both
  `callers -r` and `callees -r`.

  Two forward/reverse pairs, at different granularities: entry-level
  `callees` (forward) / `callers` (reverse) over proof-body references;
  theory-level `deps` (forward) / `uses` (reverse) over imports.
* **Rendering** — `_format_extent`, `render_entry`, preview/comment
  formatting.
* **Verbosity-mode dispatch** — the `-c`/`-n`/`-a`/`-V` resolution shared
  across subcommands.
* **Commands** — one `cmd_*` function per subcommand.  Output discipline:
  `-c` prints a bare integer (no decoration); verbose forms print
  hits + footers.
* **Argument parsing** — argparse subparsers.  Shared flag helpers
  (`_add_count_flag`, `_add_names_flag`, `_add_path_files_arg`,
  `_add_mode_flags`, `_add_verbatim_flag`, `_add_comment_flags`,
  `_add_context_flag`) keep per-subparser declarations short and
  uniform.
"""

from __future__ import annotations

import re
import sys
from bisect import bisect_right
from dataclasses import dataclass, field
from pathlib import Path

from isabelle_query.common import (
    default_t_dir,
    discover_roots,
    parse_root_sessions,
    parse_thy_imports,
    resolve_session_theory,
)

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
    thy_end: int = 0        # 1-indexed structural end (to next entry-or-section,
                            # minus one).  Includes any trailing inter-lemma
                            # `text \<open>...\<close>` block — NOT a safe cut
                            # boundary for relocations (use body_end_line).
    body_end_line: int = 0  # 1-indexed last line that belongs to this entry's
                            # body (the closing `qed`, the terminating `by` /
                            # `.`, or for declarations the last header line).
                            # Stops before any trailing inter-lemma `text` /
                            # `\<comment>` block.  Safe cut boundary for
                            # `bin/move-block.py`.
    # Comment context attached during _parse_one:
    preamble: tuple[int, int] | None = None
        # (start, end) of the `text \<open>...\<close>` block immediately
        # preceding this entry, if one exists within ~3 blank lines.
    roadmap: list[tuple[int, str]] = field(default_factory=list)
        # (line_no, content) for `\<comment> \<open>...\<close>` annotations
        # found inside this entry's proof body.
    conjuncts: list[str] = field(default_factory=list)
        # Named conjuncts of a multi-`shows` lemma (e.g. mttm_step_src's
        # mttm_step_src_neq_t).  Each is a citable fact that resolves to
        # this entry under show / find / callers / callees, but is not a
        # separate Entry (so it never inflates counts or splits call-graph
        # attribution — resolution happens at the command boundary).


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
    comment_ranges: list[tuple[int, int]] = field(default_factory=list)
        # Multi-line ranges for `\<comment> \<open>...\<close>` annotations;
        # consumed by cmd_grep's live-source filter so a token on a continuation
        # line of a multi-line \<comment> is not misclassified as live source.
        # (Distinct from comment_lines, which records first-line content for the
        # roadmap-attachment feature.)
    is_thy: bool = True
        # False for a non-`.thy` path passed as a trailing grep positional
        # (e.g. `query grep PAT notes.md`).  Such a section is parsed
        # *plainly* (no entries, outline, text/comment blocks): the Isabelle
        # entry-grammar does not apply to Markdown / prose, so cmd_grep treats
        # it as plain `grep` — every matched line, no synthesised owning-entry
        # label, no live/comment classification.
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


@dataclass
class CallGraph:
    """Name-level dependency graph built by a single pass over all sources."""
    callers: dict[str, set[str]]   # callee_name → {caller entry names}
    callees: dict[str, set[str]]   # caller_name → {callee names referenced}
    all_names: set[str]            # universe of indexed entry names


DECL_RE = re.compile(
    r"^(definition|abbreviation|fun|primrec|inductive_set|inductive|lemma|corollary|theorem|axiomatization|datatype|type_synonym|record)\s"
)

TAG_MAP = {
    "definition": "DEF", "abbreviation": "ABBREV",
    "fun": "FUN", "primrec": "FUN",
    "inductive_set": "INDSET", "inductive": "IND",
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
NAME_RE = re.compile(r"^(\w[\w']*)")
# Type-decl names: optional type-args (a single \<open>'a\<close> or a
# tuple \<open>('a, 'b)\<close>) precede the name.
TYPEDECL_NAME_RE = re.compile(
    r"^(?:'\w+\s+|\([^)]*\)\s+)?(\w[\w']*)"
)

# Named conjuncts of a multi-`shows` lemma: `shows NAME:` / `and NAME:`
# in the *shows* region.  Gated by the SHOWS_*_RE so the `assumes ... and
# X:` region — whose `and`-bound names are hypotheses, not citable facts —
# is excluded (shows always follows assumes in Isabelle's lemma grammar).
SHOWS_AT_START_RE = re.compile(r"shows\b")     # applied to a stripped line
SHOWS_ANYWHERE_RE = re.compile(r"\bshows\b")   # applied to the decl-line rest
CONJUNCT_RE = re.compile(r"(?:shows|and)\s+(\w[\w']*)\s*:")


def _isa_word_pattern(name: str) -> str:
    """Return a regex pattern matching `name` as an Isabelle identifier.

    Isabelle allows primes (') in identifiers, so \\b is wrong — it
    treats ' as a word boundary.  We use negative lookbehind/lookahead
    for [\\w'] instead.
    """
    return r"(?<![\w'])" + re.escape(name) + r"(?![\w'])"


def _parse_name(text_after_tag: str) -> str:
    m = NAME_RE.match(text_after_tag.strip())
    return m.group(1) if m else "?"


def _parse_typedecl_name(text_after_tag: str) -> str:
    r"""Parse a type_synonym / datatype / record's name, skipping any
    leading type-argument list (\<open>'a\<close> or \<open>('a, 'b)\<close>)."""
    m = TYPEDECL_NAME_RE.match(text_after_tag.strip())
    return m.group(1) if m else "?"


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


def extract_comment_ranges(lines: list[str]) -> list[tuple[int, int]]:
    """Return [(start_line, end_line)] (1-indexed inclusive) for every
    \\<comment> \\<open>...\\<close> annotation, including multi-line bodies.

    Tracks \\<open>/\\<close> balance starting from the line that contains
    \\<comment>.  A \\<comment> on a line without \\<open> yields a single-
    line range (covers tag-only annotations without explicit body).
    """
    out: list[tuple[int, int]] = []
    i = 0
    while i < len(lines):
        if "\\<comment>" in lines[i]:
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
            e = Entry(tag, _parse_typedecl_name(rest), f"{tag} {rest}",
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
        if keyword in ("definition", "abbreviation", "fun", "primrec",
                       "inductive_set", "inductive"):
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            name = _parse_name(rest)
            buf = [f"{tag} {rest}"]
            decl_end_line = decl_line
            i += 1
            open_quotes = rest.count('"') % 2
            past_where = False  # for `definition`/`abbreviation`: tracks whether
                                # the body's quoted RHS has begun, so we don't
                                # break at the type signature's closing quote.
            while i < len(lines):
                cline = lines[i]
                if BLANK_RE.match(cline):
                    break
                if DECL_RE.match(cline):
                    break
                stripped = cline.strip()
                if stripped.startswith("\\<comment>") or stripped.startswith("text "):
                    break
                where_on_this_line = bool(re.search(r"\bwhere\b", stripped))
                buf.append(f"  {stripped}")
                open_quotes = (open_quotes + stripped.count('"')) % 2
                i += 1
                decl_end_line = i  # 1-indexed line just appended
                if keyword in ("definition", "abbreviation"):
                    # Break when the body's quoted RHS closes (after `where`).
                    if past_where and open_quotes == 0 and '"' in stripped:
                        break
                    if where_on_this_line:
                        past_where = True
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
            # Named conjuncts: scan the `shows` region only.  `shows` may
            # appear inline on the decl line (one-liner) or on its own line.
            in_shows = bool(SHOWS_ANYWHERE_RE.search(rest))
            conjuncts: list[str] = (
                CONJUNCT_RE.findall(rest) if in_shows else [])
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
                if SHOWS_AT_START_RE.match(stripped):
                    in_shows = True
                if in_shows:
                    conjuncts.extend(CONJUNCT_RE.findall(stripped))
                buf.append(f"  {stripped}")
                i += 1
                decl_end_line = i

            entries.append(Entry(tag, name, "\n".join(buf),
                                 thy_line=decl_line,
                                 decl_end_line=decl_end_line,
                                 proof_line=proof_line,
                                 conjuncts=conjuncts))
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
    comment_ranges = extract_comment_ranges(lines)
    comment_lines = extract_comment_lines(lines)
    compute_spans(entries, [s[2] for s in outline], len(lines))
    _attach_comments(entries, lines, text_blocks, comment_lines)
    for e in entries:
        e.theory = thy
    # Compute body_end_line: for entries with a proof, walk forward from
    # proof_line through proof / by / qed / blank lines, stopping at the
    # next text \<open>...\<close> block or declaration.  For pure
    # declarations (no proof), body ends at decl_end_line.  Computed after
    # compute_spans because _proof_extent needs thy_end as a search bound.
    sec_for_extent = TheorySection(thy, thy_path, entries, thy_lines=len(lines))
    sec_for_extent._source_cache = lines
    for e in entries:
        if e.proof_line:
            e.body_end_line = _proof_extent(sec_for_extent, e.proof_line, e.thy_end)
        else:
            e.body_end_line = e.decl_end_line or e.thy_line
    return TheorySection(thy, thy_path, entries, thy_lines=len(lines),
                         outline=outline, text_blocks=text_blocks,
                         comment_ranges=comment_ranges)


def _parse_plain(thy: str, path: Path) -> TheorySection:
    """Build a *plain* section for a non-`.thy` file (e.g. a design memo
    passed as a grep positional).  The Isabelle entry/section/comment
    grammar does not apply to Markdown or prose, so we deliberately skip
    `extract_entries` and friends: a plain section has no entries, no
    outline, and no text/comment ranges.  cmd_grep then degrades to
    ordinary line-based `grep` over it — no synthesised owning-entry
    labels, no live/comment classification (every match is reported)."""
    lines = path.read_text().splitlines()
    sec = TheorySection(thy, path, [], thy_lines=len(lines), is_thy=False)
    sec._source_cache = lines
    return sec


def _add_one_section(thy: str, thy_path: Path,
                     seen_paths: set[Path],
                     sections: list[TheorySection]) -> None:
    """Append a parsed section, deduplicating by resolved absolute path
    so that symlinked theories (e.g.\\ `link/Foo.thy`
    -> `sub/Foo.thy`) appear once even if both the symlink
    and the target are encountered.

    `.thy` paths are parsed with the full Isabelle entry grammar
    (`_parse_one`); any other path is parsed plainly (`_parse_plain`)
    so grep over a Markdown/prose file does not invent bogus entries."""
    if not thy_path.exists():
        return
    resolved = thy_path.resolve()
    if resolved in seen_paths:
        return
    seen_paths.add(resolved)
    if thy_path.suffix == ".thy":
        sections.append(_parse_one(thy, thy_path))
    else:
        sections.append(_parse_plain(thy, thy_path))


def _sections_from_dir(root_dir: Path,
                       seen_paths: set[Path],
                       sections: list[TheorySection]) -> None:
    """Enumerate theories under `root_dir` and append parsed sections.

    Walks every ROOT file under `root_dir` (via `discover_roots`) and
    each session declared in each ROOT (via `parse_root_sessions`).
    Theories are resolved against the declaring session's directory,
    honouring its `in <subdir>` and `directories` clauses (so a theory
    a session declares to live `in "sub"` is found under `sub/`, not
    beside its ROOT file).

    Falls back to a recursive `*.thy` glob if no ROOTs are found
    (legacy behaviour for non-Isabelle-session directories).  Dedup
    by resolved path via `_add_one_section`.
    """
    roots = discover_roots(root_dir)
    if roots:
        for root_path in roots:
            for session in parse_root_sessions(root_path):
                for thy_entry in session.theories:
                    thy_path = resolve_session_theory(session, thy_entry)
                    if thy_path is None:
                        continue
                    name = thy_entry[0]
                    _add_one_section(name, thy_path, seen_paths, sections)
    else:
        for thy_path in sorted(root_dir.rglob("*.thy")):
            _add_one_section(thy_path.stem, thy_path,
                             seen_paths, sections)


_ROOT_OVERRIDE: Path | None = None  # set by main() from --root


def active_t_dir() -> Path:
    """The session directory the index is built from: the `--root`
    override if `main()` set one, else :func:`default_t_dir` (which
    consults `$ISABELLE_QUERY_ROOT` and walks up from the cwd)."""
    return _ROOT_OVERRIDE if _ROOT_OVERRIDE is not None else default_t_dir()


def load_index() -> list[TheorySection]:
    """Walk the active session directory, parsing each declared theory.
    Searches at the session root and in any sub-directory declared by
    ROOT's `directories` clause.  See :func:`active_t_dir` for how the
    directory is resolved (`--root` / `$ISABELLE_QUERY_ROOT` / cwd discovery)."""
    sections: list[TheorySection] = []
    seen_paths: set[Path] = set()
    _sections_from_dir(active_t_dir(), seen_paths, sections)
    return sections


# ---------------------------------------------------------------------------
# Call graph and shared filter helpers — `_build_text_ranges` /
# `_build_def_sites` underpin both single-name search (`_find_callers`)
# and bulk graph construction (`_build_call_graph`).  `_transitive_closure`
# drives the -r form for callers and uses, and the unused-cascade.
# ---------------------------------------------------------------------------

def _build_line_index(sections: list[TheorySection]
                      ) -> dict[str, list[tuple[int, int, Entry]]]:
    """For each theory, build a sorted list of (thy_line, thy_end, Entry)
    for binary-search lookup of which entry owns a given line."""
    index: dict[str, list[tuple[int, int, Entry]]] = {}
    for sec in sections:
        spans = [(e.thy_line, e.thy_end, e) for e in sec.entries
                 if e.thy_line > 0]
        spans.sort()
        index[sec.theory] = spans
    return index


def _entry_at_line(line_index: list[tuple[int, int, Entry]],
                   line_no: int) -> Entry | None:
    """Binary search for the entry whose [thy_line, thy_end] contains line_no."""
    keys = [s[0] for s in line_index]
    idx = bisect_right(keys, line_no) - 1
    if idx < 0:
        return None
    start, end, entry = line_index[idx]
    if start <= line_no <= end:
        return entry
    return None


def _build_text_ranges(sections: list[TheorySection]
                       ) -> dict[str, list[range]]:
    """Per-theory line ranges that contain prose to skip during identifier search.

    Combines top-level ``text \\<open>...\\<close>`` blocks and per-entry
    preambles, so a name mention inside a comment isn't classified as a
    proof-body call.  Used by both single-name search (`_find_callers`)
    and bulk graph construction (`_build_call_graph`).
    """
    text_ranges: dict[str, list[range]] = {}
    for sec in sections:
        ranges: list[range] = []
        for tb_start, tb_end in sec.text_blocks:
            ranges.append(range(tb_start, tb_end + 1))
        # Per-entry preambles (text blocks immediately above entries).
        for e in sec.entries:
            if e.preamble:
                pr_start, pr_end = e.preamble
                ranges.append(range(pr_start, pr_end + 1))
        text_ranges[sec.theory] = ranges
    return text_ranges


def _build_def_sites(sections: list[TheorySection],
                     names: set[str] | None = None,
                     ) -> dict[str, dict[str, set[range]]]:
    """Per-theory map of definition-site line ranges, keyed by entry name.

    Used to exclude the definition itself from a search for references
    to that name.  When ``names`` is given, only those names are tracked;
    otherwise every entry with a source location is included.

    Result shape: ``def_sites[theory][name] = {range(thy_line, thy_end+1), ...}``
    """
    def_sites: dict[str, dict[str, set[range]]] = {}
    for sec in sections:
        site_map: dict[str, set[range]] = {}
        for e in sec.entries:
            if e.thy_line <= 0:
                continue
            if names is None or e.name in names:
                site_map.setdefault(e.name, set()).add(
                    range(e.thy_line, e.thy_end + 1))
            # A named conjunct's declaration site is its parent's span, so a
            # `callers CONJUNCT` search excludes the `shows ... and C:` line.
            # Restricted to explicitly-queried names (never the names=None
            # broad pass) so conjuncts don't leak into the call-graph universe.
            if names is not None:
                for c in e.conjuncts:
                    if c in names:
                        site_map.setdefault(c, set()).add(
                            range(e.thy_line, e.thy_end + 1))
        def_sites[sec.theory] = site_map
    return def_sites


def _build_call_graph(sections: list[TheorySection]) -> CallGraph:
    """Single-pass scan building a full name-level call graph.

    Uses the shared filtering helpers (`_build_text_ranges`,
    `_build_def_sites`): skips text blocks, definition sites, and
    antiquotation-only mentions.
    """
    # 1. Collect candidate names (same filter as cmd_dead).
    name_set: set[str] = set()
    for sec in sections:
        for e in sec.entries:
            if e.tag in ("LEMMA", "THEOREM", "FUN", "DEF", "ABBREV") and e.name != "?":
                name_set.add(e.name)

    # 2. Build def-site and text-block exclusion ranges.
    def_sites = _build_def_sites(sections, name_set)
    text_ranges = _build_text_ranges(sections)

    # 3. Build line-to-entry index for caller attribution.
    line_index = _build_line_index(sections)

    # 4. Antiquotation pattern.
    antiq_re = re.compile(r'@\{(?:text|thm|term|const)\s+["\']?\w+["\']?\}')

    # 5. Single pass over all source lines.
    callers: dict[str, set[str]] = {n: set() for n in name_set}
    callees: dict[str, set[str]] = {}

    for sec in sections:
        lines = sec.source()
        t_ranges = text_ranges.get(sec.theory, [])
        d_map = def_sites.get(sec.theory, {})
        idx = line_index.get(sec.theory, [])
        for line_no_0, line in enumerate(lines):
            line_no = line_no_0 + 1
            if any(line_no in r for r in t_ranges):
                continue
            stripped = antiq_re.sub('', line)
            for name in name_set:
                if name not in stripped:
                    continue
                if not re.search(_isa_word_pattern(name), stripped):
                    continue
                d_ranges = d_map.get(name, set())
                if any(line_no in r for r in d_ranges):
                    continue
                caller_entry = _entry_at_line(idx, line_no)
                if caller_entry is None or caller_entry.name == "?":
                    continue
                callers[name].add(caller_entry.name)
                callees.setdefault(caller_entry.name, set()).add(name)

    return CallGraph(callers=callers, callees=callees, all_names=name_set)


def _transitive_closure(graph: dict[str, set[str]],
                        seeds: set[str]) -> dict[str, int]:
    """BFS from seeds through graph edges.  Returns {name: depth}."""
    visited: dict[str, int] = {}
    frontier = seeds
    depth = 0
    while frontier:
        next_frontier: set[str] = set()
        for name in frontier:
            if name in visited:
                continue
            visited[name] = depth
            next_frontier |= graph.get(name, set())
        frontier = next_frontier - set(visited)
        depth += 1
    return visited


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _format_extent(entry: Entry) -> str:
    """Format the `[src ...]` extent annotation for an entry.

    Surfaces `body_end_line` separately from `thy_end` when the two
    diverge (i.e., the entry has a trailing inter-lemma `text` /
    `\\<comment>` block).  The body end is the safe cut boundary for
    `bin/move-block.py`; the outline end (`thy_end`) is the structural
    end-of-region the next entry-or-section starts after.
    """
    if not entry.thy_line:
        return ""
    span_size = entry.thy_end - entry.thy_line + 1
    body_end = entry.body_end_line or entry.thy_end
    if body_end < entry.thy_end:
        body_size = body_end - entry.thy_line + 1
        return (f"[src {entry.thy_line}-{entry.thy_end}, "
                f"body {entry.thy_line}-{body_end}, "
                f"{body_size}/{span_size} lines]")
    return f"[src {entry.thy_line}-{entry.thy_end}, {span_size} lines]"


def _format_name_line(sec: TheorySection, entry: Entry) -> str:
    ext = _format_extent(entry)
    span = f" {ext}" if ext else ""
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
    if remaining == 1:
        return "\n".join(body)
    if remaining > 0:
        suffix = (f"\n  [+{remaining} more preamble lines, "
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
        if rest == 1:
            ln, content = roadmap[len(shown)]
            out.append(f"  | line {ln}: {content}")
        else:
            out.append(f"  | ...({rest} more annotations "
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
    ext = _format_extent(entry)
    header = f"--- {entry.name} ({entry.tag}) — {sec.theory}.thy {ext} ---"

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
        elif remaining == 1:
            extra = sec.slice(entry.proof_line + 1, entry.proof_line + 1)
            out_parts.append("\n".join(extra))
        elif remaining > 0:
            out_parts.append(f"  [+{remaining} more proof lines]")
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
        print(len(matches))
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
    print("# Theory Index\n")
    print(f"{total} entries across {len(sections)} theories  "
          f"(parsed live from .thy files)\n")
    print("## Theories\n")
    print("Source-line counts (`.thy` file size), entry counts, and key exports.\n")
    print("| Theory | Src | D | L | T | Key Exports |")
    print("|--------|----:|--:|--:|--:|-------------|")
    for sec in sections:
        defs = [e for e in sec.entries
                if e.tag in ("DEF", "ABBREV", "FUN", "DATATYPE", "RECORD", "TYPE")]
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
    """Resolve a theory by path or by name.

    Two argument forms, so callers can paste either a file path or a
    bare theory name:

      - **Path form** — the argument carries a path separator or a
        ``.thy`` suffix (e.g. ``sub/Foo.thy``).
        Matched against each section's resolved path, so symlinks and
        relative/absolute spellings all land on the same section.
      - **Name form** — a bare theory name (e.g.
        ``Foo``), matched against the section's theory
        name (exact, then case-insensitive).  This is the convenience
        spelling: the name is looked up among the sections already
        discovered through the ``common.py`` ROOT-walking routines.
    """
    if name.endswith(".thy") or "/" in name:
        try:
            target = Path(name).resolve()
        except OSError:
            target = None
        if target is not None:
            for s in sections:
                if s.path.resolve() == target:
                    return s
        # Path that doesn't match a known section: fall back to its
        # stem so `path/to/Foo.thy` still resolves to theory `Foo`.
        stem = Path(name).stem
        for s in sections:
            if s.theory == stem:
                return s
        return None
    for s in sections:
        if s.theory == name:
            return s
    for s in sections:
        if s.theory.lower() == name.lower():
            return s
    return None


def _suggest_theory(sections: list[TheorySection], name: str) -> str | None:
    """Closest theory to `name`, as a cwd-relative `.thy` path suggestion
    for a 'did you mean ...?' hint; None if nothing is close.

    Matches on the theory *stem*, so a mistyped path
    (`path/to/Fooo.thy`) is handled like a bare name (`Fooo`)."""
    import difflib
    by_name = {s.theory: s for s in sections}
    matches = difflib.get_close_matches(
        Path(name).stem, list(by_name), n=1, cutoff=0.6)
    if not matches:
        return None
    sec = by_name[matches[0]]
    try:
        return str(sec.path.relative_to(Path.cwd()))
    except ValueError:
        return str(sec.path)


def _resolve_conjunct(sections: list[TheorySection], name: str) -> str | None:
    """If `name` is a named conjunct of a multi-`shows` lemma, return the
    parent lemma name; else None.  Lets callers/callees/show resolve a
    conjunct to the entry that bundles it."""
    for sec in sections:
        for e in sec.entries:
            if name in e.conjuncts:
                return e.name
    return None


def cmd_theory(sections: list[TheorySection], name: str,
               flags: "CmdFlags") -> None:
    sec = _resolve_theory(sections, name)
    if sec is None:
        print(f"Theory '{name}' not found.  Known theories:")
        for s in sorted(sections, key=lambda x: x.theory):
            print(f"  {s.theory}")
        return

    # Terse modes: the theory's namespace as a bare list, no header or
    # code-fence decoration, so output is greppable / scriptable.
    if flags.mode == "count":
        print(len(sec.entries))
        return
    if flags.mode == "names":
        for e in sec.entries:
            print(_format_name_line(sec, e))
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
    # Shell users often reach for grep-style escaped alternation
    # ('a\|b\|c'); in Python's re, '\|' is the literal '|' character,
    # which would silently match nothing.  Preprocess to PCRE-style
    # alternation so the pattern does what the user expects.
    pattern = pattern.replace(r"\|", "|")
    pat = re.compile(pattern, re.IGNORECASE)
    by_theory = {s.theory: s for s in sections}
    matches: list[Entry] = []
    for s in sections:
        for e in s.entries:
            if pat.search(e.name):
                matches.append(e)
            elif any(pat.search(c) for c in e.conjuncts):
                matches.append(e)  # matched via a named `shows` conjunct

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
        # Conjunct fallback (before substring): NAME may be a named conjunct
        # of a multi-`shows` lemma; resolve to the parent that bundles it.
        for s in sections:
            for e in s.entries:
                if name in e.conjuncts:
                    matches.append(e)
        if matches:
            parents = ", ".join(sorted({e.name for e in matches}))
            print(f"# '{name}' is a named conjunct of {parents}:")
    if not matches:
        # Substring fallback
        for s in sections:
            for e in s.entries:
                if name.lower() in e.name.lower():
                    matches.append(e)
    _emit_matches(by_theory, matches, name, flags)


def cmd_defs(sections: list[TheorySection], theory: str,
             flags: "CmdFlags") -> None:
    sec = _resolve_theory(sections, theory)
    if sec is None:
        print(f"Theory '{theory}' not found.")
        return
    matches = [e for e in sec.entries
               if e.tag in ("DEF", "ABBREV", "FUN", "DATATYPE", "RECORD", "TYPE")]
    if not matches:
        print(f"No definitions found in '{sec.theory}'.")
        return
    if flags.mode == "count":
        print(len(matches))
        return
    if flags.mode == "names":
        for e in matches:
            print(_format_name_line(sec, e))
        return
    for e in matches:
        print(render_entry(sec, e))
        print()


def _bfs_depths(adj: dict[str, list[str]], start: str) -> dict[str, int]:
    """Breadth-first distances from *start* over adjacency map *adj*.

    The start node sits at conceptual depth -1, so its immediate
    neighbours land at depth 0 ("direct").  *start* itself is excluded
    from the result; the visited guard makes the walk safe on the
    import DAG regardless."""
    depths: dict[str, int] = {}
    queue: list[tuple[str, int]] = [(start, -1)]
    while queue:
        node, depth = queue.pop(0)
        for nxt in adj.get(node, []):
            if nxt != start and nxt not in depths:
                depths[nxt] = depth + 1
                queue.append((nxt, depth + 1))
    return depths


def cmd_deps(sections: list[TheorySection], theory: str,
             reverse: bool = False, recursive: bool = False) -> None:
    """Theory-level (not entry-level) import dependencies.

    Forward (``reverse=False``): the theories this one imports.
    Reverse (``reverse=True``): the theories that import this one.
    Exposed as the ``deps`` (forward) / ``uses`` (reverse) subcommand
    pair — the theory-graph analogue of the entry-level
    ``callees`` / ``callers`` pair (brew's ``deps`` / ``uses``
    convention).

    Direct by default; ``recursive`` (``-r``) gives the transitive
    closure with per-hop depth labels — matching the direct/``-r``
    semantics of the entry-level pair."""
    target = _resolve_theory(sections, theory)
    if target is None:
        print(f"Theory '{theory}' not found.")
        return

    sec_by_name: dict[str, TheorySection] = {s.theory: s for s in sections}

    def emit(found: dict[str, int]) -> None:
        for name, depth in sorted(found.items(), key=lambda kv: (kv[1], kv[0])):
            sec = sec_by_name[name]
            tag = "  [direct]" if depth == 0 else f"  [depth {depth}]"
            print(f"  {name}  ({sec.thy_lines} src lines, "
                  f"{len(sec.entries)} entries){tag}")

    scope = "transitively" if recursive else "directly"

    if reverse:
        # Invert the in-project import adjacency: child -> theories that
        # import the child.  The reverse direction needs the whole graph
        # regardless of depth, so the full scan here is unavoidable.
        rev: dict[str, list[str]] = {s.theory: [] for s in sections}
        for s in sections:
            for imp in parse_thy_imports(s.path):
                if imp in sec_by_name:
                    rev[imp].append(s.theory)
        if recursive:
            found = _bfs_depths(rev, target.theory)
        else:
            found = {name: 0 for name in rev.get(target.theory, [])}
        if not found:
            print(f"No in-project theory imports {target.theory} ({scope}).")
            return
        print(f"Theories that import {target.theory} ({scope}):")
        emit(found)
        return

    # Forward.  Direct: just the target's own import line.  Recursive:
    # lazy BFS over the imports graph.  Out-of-project imports (e.g.
    # HOL-Library.*) are direct edges, so they show in both modes.
    in_project: dict[str, int] = {}  # name -> depth (0 = direct import)
    out_of_project: set[str] = set()
    if recursive:
        queue: list[tuple[str, int]] = [(target.theory, -1)]
        while queue:
            name, depth = queue.pop(0)
            sec = sec_by_name.get(name)
            if sec is None:
                continue
            for imp in parse_thy_imports(sec.path):
                child = sec_by_name.get(imp)
                if child is None:
                    out_of_project.add(imp)
                elif imp not in in_project and imp != target.theory:
                    in_project[imp] = depth + 1
                    queue.append((imp, depth + 1))
    else:
        for imp in parse_thy_imports(target.path):
            if imp not in sec_by_name:
                out_of_project.add(imp)
            elif imp != target.theory:
                in_project[imp] = 0

    if not in_project and not out_of_project:
        print(f"{target.theory} has no upstream dependencies.")
        return

    header = ("Import-transitive dependencies" if recursive
              else "Direct imports")
    print(f"{header} of {target.theory}:")
    emit(in_project)
    for name in sorted(out_of_project):
        print(f"  {name}  [out-of-project]")


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


def _find_callers(sections: list[TheorySection], name: str,
                   external: bool = False,
                   ) -> list[tuple[str, int, str]]:
    """Find proof-body usages of *name* across all .thy files.

    Returns a list of (theory_name, line_no, line_text) triples, filtering
    out:
      - The definition site itself (same theory, within the entry's span).
      - Lines inside ``text \\<open>...\\<close>`` blocks (prose, not proof).
      - Antiquotation-only mentions: ``@{text name}``, ``@{thm name}``,
        ``@{term name}`` where the *only* occurrence of *name* on the line
        is inside an antiquotation.

    When ``external`` is true, additionally skip every line in the
    theory(ies) that define *name* — useful for "is anything outside
    Foo using Foo's primitives?" audits where intra-theory
    cross-references are noise.
    """
    word_re = re.compile(_isa_word_pattern(name))
    # Antiquotation pattern: @{text/thm/term/const "?name"?}
    antiq_re = re.compile(
        r'@\{(?:text|thm|term|const)\s+["\']?' + re.escape(name) + r'["\']?\}')

    # Shared infrastructure: per-theory def-site ranges (for `name`) and
    # text-block ranges (prose to skip).
    all_def_sites = _build_def_sites(sections, {name})
    def_theories: set[str] = {th for th, m in all_def_sites.items() if m}
    text_ranges = _build_text_ranges(sections)

    results: list[tuple[str, int, str]] = []
    for sec in sections:
        # External mode: skip every line in the defining theory(ies),
        # treating intra-theory cross-references as noise.
        if external and sec.theory in def_theories:
            continue
        lines = sec.source()
        t_ranges = text_ranges.get(sec.theory, [])
        d_ranges = all_def_sites.get(sec.theory, {}).get(name, set())
        for line_no_0, line in enumerate(lines):
            line_no = line_no_0 + 1
            if not word_re.search(line):
                continue
            # Skip definition site.
            if any(line_no in r for r in d_ranges):
                continue
            # Skip text blocks.
            if any(line_no in r for r in t_ranges):
                continue
            # Skip if the only occurrences are inside antiquotations.
            stripped = antiq_re.sub('', line)
            if not word_re.search(stripped):
                continue
            results.append((sec.theory, line_no, line.rstrip()))
    return results


def _render_graph_results(sections: list[TheorySection],
                          reachable: dict[str, int],
                          label: str, seed: str,
                          flags: 'CmdFlags') -> None:
    """Shared rendering for callers -r and uses -r."""
    if flags.mode == "count":
        print(len(reachable))
        return
    if not reachable:
        print(f"No {label}s found for '{seed}'.")
        return

    # Build name → (theory, Entry) lookup for rendering.
    by_name: dict[str, tuple[str, Entry]] = {}
    for sec in sections:
        for e in sec.entries:
            if e.name not in by_name:
                by_name[e.name] = (sec.theory, e)

    if flags.mode == "names":
        for name in sorted(reachable):
            if name in by_name:
                thy, e = by_name[name]
                print(f"  {name} ({e.tag}) — {thy}")
            else:
                print(f"  {name}")
        return

    print(f"{len(reachable)} transitive {label}(s) of {seed}:\n")
    for name, depth in sorted(reachable.items(), key=lambda x: (x[1], x[0])):
        indent = "  " * (depth + 1)
        if name in by_name:
            thy, e = by_name[name]
            print(f"{indent}{name} ({e.tag}) — {thy} [L{e.thy_line}]")
        else:
            print(f"{indent}{name}")


def _enclosing_entry(sec: TheorySection, line_no: int) -> Entry | None:
    """Return the entry whose [thy_line, thy_end] span contains *line_no*.

    Used by ``cmd_callers`` to annotate each hit with its enclosing lemma
    name — answering "which proof is calling this?" in one line rather
    than requiring a follow-up ``show`` invocation.
    """
    for e in sec.entries:
        if e.thy_line and e.thy_end and e.thy_line <= line_no <= e.thy_end:
            return e
    return None


def cmd_callers(sections: list[TheorySection], name: str,
                flags: 'CmdFlags') -> None:
    """Print proof-body usages of a lemma/definition."""
    if flags.recursive:
        graph = _build_call_graph(sections)
        if name not in graph.all_names:
            parent = _resolve_conjunct(sections, name)
            if parent is not None:
                print(f"# '{name}' is a named conjunct of {parent}; "
                      f"recursive caller closure operates at the {parent} "
                      f"(entry) level.")
                name = parent
            else:
                print(f"'{name}' not found in the entry index.")
                return
        reachable = _transitive_closure(graph.callers, {name})
        reachable.pop(name, None)
        _render_graph_results(sections, reachable, "caller", name, flags)
        return

    hits = _find_callers(sections, name, external=flags.external)
    if flags.mode == "count":
        print(len(hits))
        return
    if not hits:
        print(f"No callers found for '{name}'.")
        return
    # Build theory → section lookup once for enclosing-entry lookup and
    # trailing-context line access.
    sec_by_theory: dict[str, TheorySection] = {s.theory: s for s in sections}
    n_after = max(0, flags.context)
    print(f"{len(hits)} caller(s) of {name}:\n")
    for theory, line_no, text in hits:
        sec = sec_by_theory.get(theory)
        encl = _enclosing_entry(sec, line_no) if sec is not None else None
        encl_tag = f"  [in {encl.name}]" if encl is not None else ""
        print(f"  {theory}:{line_no}:{encl_tag}  {text.strip()}")
        if n_after > 0 and sec is not None:
            src = sec.source()
            # 1-indexed line_no → 0-indexed slice start at line_no
            # (i.e., the line *after* the match).
            for off, ctx in enumerate(src[line_no:line_no + n_after], start=1):
                ctx_no = line_no + off
                print(f"  {theory}:{ctx_no}-  {ctx.rstrip()}")


def cmd_callees(sections: list[TheorySection], name: str,
                flags: 'CmdFlags') -> None:
    """Entry-level forward edge: the entries this entry references in
    its proof body (its callees).  Pairs with `cmd_callers` (reverse).
    Not to be confused with the theory-level `deps` / `uses` pair."""
    graph = _build_call_graph(sections)
    if name not in graph.all_names:
        parent = _resolve_conjunct(sections, name)
        if parent is not None:
            print(f"# '{name}' is a named conjunct of {parent}; "
                  f"reporting {parent}'s callees (shared proof body).")
            name = parent
        else:
            print(f"'{name}' not found in the entry index.")
            return

    if flags.recursive:
        reachable = _transitive_closure(graph.callees, {name})
        reachable.pop(name, None)
        _render_graph_results(sections, reachable, "dependency", name, flags)
        return

    by_name: dict[str, tuple[str, Entry]] = {}
    for sec in sections:
        for e in sec.entries:
            if e.name not in by_name:
                by_name[e.name] = (sec.theory, e)

    used = graph.callees.get(name, set())
    if flags.external:
        # Mirror of `callers --external`: drop callees defined in NAME's
        # own theory, leaving only its cross-theory dependencies.
        own_theory = by_name.get(name, (None,))[0]
        used = {u for u in used
                if by_name.get(u, (None,))[0] != own_theory}
    if flags.mode == "count":
        print(len(used))
        return
    if not used:
        scope = "cross-theory " if flags.external else ""
        print(f"No {scope}references found in {name}'s body.")
        return

    print(f"{len(used)} callee(s) of {name}:\n")
    for uname in sorted(used):
        if uname in by_name:
            thy, e = by_name[uname]
            print(f"  {uname} ({e.tag}) — {thy} [L{e.thy_line}]")
        else:
            print(f"  {uname}")


def _compute_unused(graph: CallGraph,
                    keep: set[str] | None = None) -> set[str]:
    """Entries with zero callers (directly unused).

    Names in `keep` are treated as live roots — never flagged as unused
    regardless of caller count.  Use this to exclude top-of-pyramid
    theorems (e.g. AFP-headline statements) which legitimately have
    zero callers in the project but should not be pruned.
    """
    keep = keep or set()
    return {n for n in graph.all_names
            if n not in keep and not graph.callers.get(n, set())}


def _compute_unused_recursive(graph: CallGraph,
                              keep: set[str] | None = None
                              ) -> dict[str, int]:
    """Fixed-point cascade: an entry is unused if all its callers are unused.

    Names in `keep` are treated as live roots — never flagged, and
    entries whose callers include a kept name stay live too (the
    cascade stops at the live frontier).

    Returns {name: depth} where depth 0 = directly unused (zero callers),
    depth 1 = became unused when depth-0 entries are removed, etc.
    """
    keep = keep or set()
    unused: dict[str, int] = {n: 0 for n in _compute_unused(graph, keep)}
    changed = True
    depth = 1
    while changed:
        changed = False
        for name in graph.all_names - set(unused) - keep:
            callers = graph.callers.get(name, set())
            if callers and callers <= set(unused):
                unused[name] = depth
                changed = True
        depth += 1
    return unused


def _compute_forest(graph: CallGraph,
                    sections: list[TheorySection],
                    keep: set[str] | None = None
                    ) -> list[tuple[str, int, int, int, int]]:
    """Compute the forest of unused roots with exclusive subtree sizes.

    For each root (zero callers, modulo `keep`), compute:
    - total cone: all entries transitively reachable via callees
    - exclusive subtree: entries reachable ONLY from this root

    Names in `keep` are treated as live and excluded from the root
    set; their support cones don't contribute to the forest.

    Returns list of (root_name, exclusive_entries, exclusive_lines,
    total_entries, total_lines) sorted by exclusive_lines descending.
    """
    roots = _compute_unused(graph, keep)
    keep = keep or set()

    # For each entry, compute the set of roots that can reach it.
    # An entry is "exclusive" to a root iff its root-set is exactly
    # {root}.  Include kept (live) roots in the seed so that entries
    # shared between an unused root and a live root are NOT counted
    # as exclusive to the unused root — those would survive a prune.
    #
    # Fixed-point iteration:
    #   root_set(X) = {X}                            if X is a root
    #               = union(root_set(c) for c in callers(X))   else
    #
    # A single-pass BFS is INCORRECT here: a node's root-set must
    # accumulate from ALL its callers, but BFS-via-callees visits each
    # node once at first discovery, missing later-discovered caller
    # contributions.  The DAG (no cycles, per Isabelle theory order)
    # makes fixed-point iteration converge in O(longest-caller-chain)
    # passes.
    all_roots = roots | keep
    root_sets: dict[str, set[str]] = {r: {r} for r in all_roots}

    changed = True
    while changed:
        changed = False
        for name in graph.all_names:
            if name in all_roots:
                continue
            new_rset: set[str] = set()
            for c in graph.callers.get(name, set()):
                new_rset |= root_sets.get(c, set())
            if not new_rset:
                continue
            if root_sets.get(name) != new_rset:
                root_sets[name] = new_rset
                changed = True

    # Entry line-size lookup.
    entry_lines: dict[str, int] = {}
    for sec in sections:
        for e in sec.entries:
            if e.name in graph.all_names and e.name not in entry_lines:
                entry_lines[e.name] = (e.thy_end - e.thy_line + 1
                                       if e.thy_line > 0 else 0)

    # For each root, compute exclusive entries (reachable only from it).
    # Total cone = all entries whose root-set includes this root.
    result: list[tuple[str, int, int, int, int]] = []
    for root in sorted(roots):
        exclusive_entries = 0
        exclusive_lines = 0
        total_entries = 0
        total_lines = 0
        for name, rset in root_sets.items():
            if root in rset:
                sz = entry_lines.get(name, 0)
                total_entries += 1
                total_lines += sz
                if len(rset) == 1:
                    exclusive_entries += 1
                    exclusive_lines += sz
        result.append((root, exclusive_entries, exclusive_lines,
                        total_entries, total_lines))

    result.sort(key=lambda x: -x[2])  # by exclusive lines desc
    return result


def _render_unused(sections: list[TheorySection],
                   entries: list[tuple[str, Entry, int]],
                   flags: 'CmdFlags', recursive: bool) -> None:
    """Shared rendering for unused and unused -r."""
    if not entries:
        print("No unused entries found.")
        return

    label = "transitively unused" if recursive else "unused"
    total = len(entries)

    if flags.mode == "count":
        print(total)
        return

    if flags.by_theory:
        from collections import Counter
        theory_entries: dict[str, list[tuple[Entry, int]]] = {}
        for theory, e, depth in entries:
            theory_entries.setdefault(theory, []).append((e, depth))
        counts = Counter({t: len(es) for t, es in theory_entries.items()})
        total_lines = sum(
            e.thy_end - e.thy_line + 1 for es in theory_entries.values()
            for e, _ in es if e.thy_line > 0)
        print(f"{total} {label} entries across {len(theory_entries)} theories "
              f"({total_lines} source lines):\n")
        for theory, count in counts.most_common():
            tes = theory_entries[theory]
            lines = sum(e.thy_end - e.thy_line + 1 for e, _ in tes
                        if e.thy_line > 0)
            names = ", ".join(e.name for e, _ in tes[:4])
            if len(tes) > 4:
                names += f", ... (+{len(tes) - 4})"
            print(f"  {count:3d}  {theory:<30s}  {lines:5d} lines  {names}")
        return

    if recursive:
        direct = sum(1 for _, _, d in entries if d == 0)
        cascade = total - direct
        total_lines = sum(
            e.thy_end - e.thy_line + 1 for _, e, _ in entries
            if e.thy_line > 0)
        print(f"{total} {label} entries "
              f"({direct} direct + {cascade} cascading, "
              f"{total_lines} source lines):\n")
    else:
        print(f"{total} unused entries (zero callers):\n")

    print(f"{'Tag':<8}  {'Name':<42}  Theory  (span)")
    print(f"{'-' * 8:<8}  {'-' * 42:<42}  ------")
    for theory, e, depth in entries:
        size = e.thy_end - e.thy_line + 1 if e.thy_line > 0 else 0
        depth_mark = f"  [cascade depth {depth}]" if recursive and depth > 0 else ""
        print(f"{e.tag:<8}  {e.name:<42}  {theory}  "
              f"({e.thy_line}-{e.thy_end}, {size} lines){depth_mark}")


def _render_forest(sections: list[TheorySection],
                   forest: list[tuple[str, int, int, int, int]],
                   flags: 'CmdFlags') -> None:
    """Render the forest root summary."""
    if not forest:
        print("No unused roots found.")
        return

    # Entry lookup for tag/theory.
    by_name: dict[str, tuple[str, Entry]] = {}
    for sec in sections:
        for e in sec.entries:
            if e.name not in by_name:
                by_name[e.name] = (sec.theory, e)

    if flags.mode == "count":
        print(len(forest))
        return

    print(f"{len(forest)} unused roots:\n")
    print(f"  {'Root':<42s}  {'Excl':>5s}  {'Lines':>6s}  "
          f"{'Total':>5s}  {'Lines':>6s}  Theory")
    print(f"  {'-' * 42:<42s}  {'-' * 5:>5s}  {'-' * 6:>6s}  "
          f"{'-' * 5:>5s}  {'-' * 6:>6s}  ------")
    for root, ee, el, te, tl in forest:
        if root in by_name:
            thy, entry = by_name[root]
            tag = entry.tag
        else:
            thy, tag = "?", "?"
        print(f"  {root:<42s}  {ee:>5d}  {el:>6d}  {te:>5d}  {tl:>6d}  {thy}")


def cmd_unused(sections: list[TheorySection], flags: 'CmdFlags') -> None:
    """List entries with zero callers in proof bodies."""
    graph = _build_call_graph(sections)

    keep = set(flags.keep)
    if keep:
        unknown = keep - graph.all_names
        if unknown:
            print(f"warning: --keep names not found in call graph: "
                  f"{', '.join(sorted(unknown))}", file=sys.stderr)

    if flags.roots:
        forest = _compute_forest(graph, sections, keep)
        _render_forest(sections, forest, flags)
        return

    if flags.recursive:
        unused_map = _compute_unused_recursive(graph, keep)
    else:
        unused_map = {n: 0 for n in _compute_unused(graph, keep)}

    unused_entries: list[tuple[str, Entry, int]] = []
    for sec in sections:
        for e in sec.entries:
            if e.tag in ("LEMMA", "THEOREM", "FUN", "DEF", "ABBREV") and e.name != "?":
                if e.name in unused_map:
                    unused_entries.append((sec.theory, e, unused_map[e.name]))

    _render_unused(sections, unused_entries, flags, flags.recursive)


def _grep_sections(sections: list[TheorySection], pat: re.Pattern
                   ) -> list[tuple[str, int, str, "Entry | None", bool, bool]]:
    """Walk every section's source and return one tuple per line that
    matches `pat`.  Each tuple is (loc_name, line_no, line_text,
    owning_entry, is_live, is_thy), where loc_name is the file's real
    name (e.g. `Foo.thy`, `notes.md`) so plain non-`.thy`
    positionals report their actual filename rather than `<stem>.thy`.
    `is_thy` is False for non-`.thy` positionals (Markdown / prose),
    which have no Isabelle entries and hence no owning-entry column —
    `cmd_grep` shows the matched line text directly for those.

    is_live = True iff the line is genuine proof / declaration source —
    not inside a top-level `text \\<open>...\\<close>` block, not inside
    a per-entry preamble (a small text block attached to a following
    declaration), and not inside a multi-line `\\<comment>
    \\<open>...\\<close>` annotation.

    owning_entry is the lemma/theorem/definition whose span contains the
    matching line, via binary-search lookup (None if the line is outside
    any indexed entry — e.g. between top-level declarations).
    """
    line_index = _build_line_index(sections)
    out: list[tuple[str, int, str, Entry | None, bool, bool]] = []
    for sec in sections:
        lines = sec.source()
        noise: list[range] = []
        for tb_start, tb_end in sec.text_blocks:
            noise.append(range(tb_start, tb_end + 1))
        for cb_start, cb_end in sec.comment_ranges:
            noise.append(range(cb_start, cb_end + 1))
        for e in sec.entries:
            if e.preamble:
                ps, pe = e.preamble
                noise.append(range(ps, pe + 1))
        idx = line_index.get(sec.theory, [])
        for line_no_0, line in enumerate(lines):
            if not pat.search(line):
                continue
            line_no = line_no_0 + 1
            is_live = not any(line_no in r for r in noise)
            owner = _entry_at_line(idx, line_no)
            out.append((sec.path.name, line_no, line.rstrip(), owner,
                        is_live, sec.is_thy))
    return out


def cmd_grep(sections: list[TheorySection], pattern: str,
             flags: 'CmdFlags') -> None:
    """Regex-search live source across all theories.

    Default: only matches in live source (declarations + proof bodies),
    skipping `text \\<open>...\\<close>` blocks, per-entry preambles, and
    multi-line `\\<comment> \\<open>...\\<close>` annotations.  Use
    `--all` to also include prose matches; each non-live hit is tagged.

    Pattern accepts both Python regex syntax (`a|b|c`) and shell-grep-
    style alternation (`a\\|b\\|c`); the latter is rewritten to the
    former before compiling, mirroring `cmd_find`'s behaviour.
    """
    pattern = pattern.replace(r"\|", "|")
    try:
        pat = re.compile(pattern)
    except re.error as exc:
        print(f"ERROR: invalid regex '{pattern}': {exc}", file=sys.stderr)
        sys.exit(2)

    all_hits = _grep_sections(sections, pat)
    live_hits = [h for h in all_hits if h[4]]
    dead_hits = [h for h in all_hits if not h[4]]
    hits = all_hits if flags.include_all else live_hits

    if flags.mode == "count":
        print(len(all_hits) if flags.include_all else len(live_hits))
        return

    if not hits:
        print(f"No {'' if flags.include_all else 'live '}"
              f"matches for '{pattern}'.")
        return

    if flags.include_all:
        print(f"{len(all_hits)} match(es) for '{pattern}' "
              f"({len(live_hits)} live, "
              f"{len(dead_hits)} in comments/text):\n")
    else:
        print(f"{len(live_hits)} live match(es) for '{pattern}':\n")

    if flags.mode == "names":
        # Compact: location + owning entry, no source line.  For a
        # non-`.thy` positional there is no owning entry, so names mode
        # would be content-free — fall back to the matched line text.
        loc_w = max((len(f"{t}:{ln}") for t, ln, *_ in hits), default=0)
        for loc_name, ln, text, owner, is_live, is_thy in hits:
            loc = f"{loc_name}:{ln}"
            marker = "" if is_live else "  [in comment/text]"
            if not is_thy:
                print(f"  {loc:<{loc_w}}  {text.strip()}{marker}")
                continue
            owner_str = (f"{owner.name} ({owner.tag})"
                         if owner is not None and owner.name != "?"
                         else "—")
            print(f"  {loc:<{loc_w}}  {owner_str}{marker}")
        return

    # Default: location + owning entry + matched line text.  Non-`.thy`
    # positionals have no entry column — show the line inline on one row.
    loc_w = max((len(f"{t}:{ln}") for t, ln, *_ in hits), default=0)
    for loc_name, ln, text, owner, is_live, is_thy in hits:
        loc = f"{loc_name}:{ln}"
        marker = "" if is_live else "  [in comment/text]"
        if not is_thy:
            print(f"  {loc:<{loc_w}}  {text.strip()}{marker}")
            continue
        if owner is not None and owner.name != "?":
            owner_str = f"{owner.name} ({owner.tag})"
        else:
            owner_str = "—"
        print(f"  {loc:<{loc_w}}  {owner_str}{marker}")
        print(f"    {text.strip()}")


def cmd_sorry(sections: list[TheorySection], count_only: bool) -> None:
    r"""List open goals: every live `sorry` as its location + owning entry.

    A thin specialisation of `grep` over the fixed `sorry` token, sharing
    the same `_grep_sections` engine.  Two refinements over a bare
    `grep '\bsorry\b'`: the boundary is prime-aware (`_isa_word_pattern`,
    so the identifier `sorry'` is not a false hit, unlike Python's `\b`),
    and only *live* matches count (a `sorry` inside a `text` / `\<comment>`
    block is not an open goal).  Replaces both the count-only
    `grep -c '\bsorry\b'` idiom and the shell sorry-counter formerly in
    `count-axioms.sh`.  `-c` prints the bare count (build-summary form);
    otherwise prints `FILE:LINE  entry (TAG)` per goal then a total.
    """
    pat = re.compile(_isa_word_pattern("sorry"))
    hits = [h for h in _grep_sections(sections, pat) if h[4]]
    if count_only:
        print(len(hits))
        return
    if not hits:
        print("No sorries.")
        return
    loc_w = max(len(f"{loc}:{ln}") for loc, ln, *_ in hits)
    for loc_name, ln, _text, owner, _live, _is_thy in hits:
        owner_str = (f"{owner.name} ({owner.tag})"
                     if owner is not None and owner.name != "?"
                     else "—")
        print(f"  {f'{loc_name}:{ln}':<{loc_w}}  {owner_str}")
    print(f"{len(hits)} sorr{'y' if len(hits) == 1 else 'ies'}")


def _parse_line_range(spec: str) -> tuple[int, int]:
    """Parse `A..B` or `A` into a (start, end) inclusive pair.  Raises
    ValueError on malformed input.
    """
    if ".." in spec:
        a_str, b_str = spec.split("..", 1)
        a, b = int(a_str), int(b_str)
    else:
        a = b = int(spec)
    if a < 1 or b < a:
        raise ValueError(f"invalid range '{spec}': require 1 <= start <= end")
    return a, b


def cmd_lines(file_path: str, ranges: list[str]) -> None:
    """Print the specified line ranges of FILE with `NR| CONTENT` prefix.

    Sandbox-friendly alternative to `awk 'NR>=A && NR<=B {…}'` loops;
    multiple ranges separated by blank lines (rg-style `--` separators
    between hunks).  Width of the line-number column adapts to the
    largest line number requested.
    """
    p = Path(file_path).expanduser().resolve()
    if not p.exists():
        print(f"ERROR: file not found: {p}", file=sys.stderr)
        sys.exit(1)
    try:
        parsed = [_parse_line_range(r) for r in ranges]
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)
    lines = p.read_text().splitlines()
    n_lines = len(lines)
    max_no = max((b for _, b in parsed), default=1)
    width = len(str(min(max_no, n_lines)))
    for i, (a, b) in enumerate(parsed):
        if i > 0:
            print("--")
        a_clamped = max(1, a)
        b_clamped = min(n_lines, b)
        if a_clamped > n_lines:
            print(f"# range {a}..{b}: past end of file ({n_lines} lines)",
                  file=sys.stderr)
            continue
        for nr in range(a_clamped, b_clamped + 1):
            print(f"{nr:>{width}}| {lines[nr - 1]}")
        if b > n_lines:
            print(f"# range {a}..{b}: truncated at line {n_lines}",
                  file=sys.stderr)


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
# Argument parsing (argparse with subcommands)
# ---------------------------------------------------------------------------

import argparse


@dataclass
class CmdFlags:
    """Uniform flag bundle passed to command functions."""
    mode: str = "first"          # first / all / count / names
    verbatim: bool = False       # -V / --verbatim
    comments: str = "on"         # on / off / only
    context: int = 2             # -U N / --context N
    with_comments: bool = False  # --with-comments (find only)
    recursive: bool = False      # -r / --recursive
    by_theory: bool = False      # --by-theory (unused)
    roots: bool = False          # --roots (unused)
    keep: frozenset[str] = frozenset()  # --keep (unused: live roots)
    include_all: bool = False    # --all (grep: include in-comment matches)
    external: bool = False       # --external (callers: skip defining theory)


def _flags_from_ns(ns: argparse.Namespace) -> CmdFlags:
    """Build CmdFlags from an argparse Namespace."""
    f = CmdFlags()
    # Precedence: count > names > all > default ("first").
    if getattr(ns, "all", False):
        f.mode = "all"
    if getattr(ns, "names", False):
        f.mode = "names"
    if getattr(ns, "count", False):
        f.mode = "count"
    f.verbatim = getattr(ns, "verbatim", False)
    if getattr(ns, "no_comments", False):
        f.comments = "off"
    elif getattr(ns, "comments_only", False):
        f.comments = "only"
    f.context = getattr(ns, "context", 2)
    f.with_comments = getattr(ns, "with_comments", False)
    f.recursive = getattr(ns, "recursive", False)
    f.by_theory = getattr(ns, "by_theory", False)
    f.roots = getattr(ns, "roots", False)
    keep_args = getattr(ns, "keep", None) or []
    f.keep = frozenset(n for arg in keep_args
                       for n in arg.split(",") if n.strip())
    f.include_all = getattr(ns, "all_hits", False)
    f.external = getattr(ns, "external", False)
    return f


def _load_sections(ns: argparse.Namespace) -> list[TheorySection]:
    """Load theory sections from trailing positional PATHs, or the
    project ROOTs.

    Subcommands that accept ``nargs='*'`` trailing path positionals
    (``grep PATTERN ...``, ``callers NAME ...``) pre-populate
    ``ns.files``; the parse is then restricted to those paths
    instead of the full project index.

    Each positional may be:

    * a ``.thy`` file path  -> that single theory.
    * a directory containing a ``ROOT`` file  -> all theories
      declared by ROOT (resolved through ROOT's ``theories`` and
      ``directories`` clauses, matching Isabelle's own semantics).
    * a directory with no ``ROOT``  -> recursive ``*.thy`` glob.

    Results are unioned and deduplicated by resolved absolute
    path, so passing two directories where one holds symlinks into
    the other does not double-count the shared theories.
    """
    files: list[str] = list(getattr(ns, "files", None) or [])
    if not files:
        return load_index()
    sections: list[TheorySection] = []
    seen_paths: set[Path] = set()
    index: list[TheorySection] | None = None  # lazily loaded for name lookups
    for fp in files:
        p = Path(fp).expanduser().resolve()
        if p.is_dir():
            _sections_from_dir(p, seen_paths, sections)
            continue
        if p.exists():
            _add_one_section(p.stem, p, seen_paths, sections)
            continue
        # Not an on-disk path: accept a bare theory name (or a path whose
        # stem names a known theory), resolved against the full index —
        # matching how outline/show/defs/callees take names.
        if index is None:
            index = load_index()
        sec = _resolve_theory(index, fp)
        if sec is not None:
            _add_one_section(sec.path.stem, sec.path, seen_paths, sections)
            continue
        suggestion = _suggest_theory(index, fp)
        hint = f" (did you mean {suggestion}?)" if suggestion else ""
        print(f"ERROR: not a path or known theory: {fp}{hint}",
              file=sys.stderr)
        sys.exit(1)
    return sections


# -- Shared flag groups (added to subparsers that need them) ----------------

def _add_mode_flags(p: argparse.ArgumentParser) -> None:
    # Composite bundle for subparsers that accept all three.  Not mutually
    # exclusive: -a -n composes (= "all matches as names").  Precedence at
    # resolution: -c > -n > -a > default.  Subparsers wanting only a
    # subset call the per-flag helpers (`_add_count_flag`,
    # `_add_names_flag`) directly.
    p.add_argument("-a", "--all", action="store_true",
                   help="show all matches")
    _add_count_flag(p, "just print the count (wins over -a / -n)")
    _add_names_flag(p, "names + tags + theory only (composable with -a)")


def _add_count_flag(p: argparse.ArgumentParser,
                    help_text: str = "just print the count") -> None:
    p.add_argument("-c", "--count", action="store_true", help=help_text)


def _add_names_flag(p: argparse.ArgumentParser,
                    help_text: str = "names + tags + theory only") -> None:
    p.add_argument("-n", "--names", action="store_true", help=help_text)


def _add_path_files_arg(p: argparse.ArgumentParser) -> None:
    """Add the rg/grep-style trailing PATH positionals.

    Resolved by `_load_sections`: each may be a .thy file (single
    theory), a directory containing a ROOT (theories per ROOT's
    `theories` clause), or a directory without (recursive *.thy glob).
    Results dedup'd by resolved path so `t/ archive/` doesn't double-
    count symlinked theories.
    """
    p.add_argument("files", nargs="*", metavar="PATH",
                   help="restrict search to specific .thy files or "
                        "directories (rg/grep-style trailing positionals); "
                        "a bare theory name resolves to its .thy. "
                        "Directories with a ROOT are expanded via the "
                        "ROOT's `theories` clause; directories without are "
                        "walked recursively for `*.thy`.  Results are "
                        "dedup'd by resolved path, so `t/ archive/` does "
                        "not double-count symlinked theories.")

def _add_verbatim_flag(p: argparse.ArgumentParser) -> None:
    p.add_argument("-V", "--verbatim", action="store_true",
                   help="full source slice (statement + proof)")

def _add_comment_flags(p: argparse.ArgumentParser) -> None:
    g = p.add_mutually_exclusive_group()
    g.add_argument("--no-comments", action="store_true",
                   help="suppress preamble and roadmap")
    g.add_argument("--comments-only", action="store_true",
                   help="show only preamble + roadmap")

def _add_context_flag(p: argparse.ArgumentParser) -> None:
    p.add_argument("-U", "--context", type=int, default=2, metavar="N",
                   help="lines of preview / context (default 2)")


# -- Subcommand handlers (thin wrappers) -----------------------------------

def _run_summary(ns: argparse.Namespace) -> None:
    cmd_summary(_load_sections(ns))

def _run_theory(ns: argparse.Namespace) -> None:
    cmd_theory(_load_sections(ns), ns.name, _flags_from_ns(ns))

def _run_defs(ns: argparse.Namespace) -> None:
    cmd_defs(_load_sections(ns), ns.theory, _flags_from_ns(ns))

def _run_deps(ns: argparse.Namespace) -> None:
    sections = _load_sections(ns)
    for i, thy in enumerate(ns.theory):
        if i > 0:
            print()
        cmd_deps(sections, thy, recursive=ns.recursive)

def _run_theory_uses(ns: argparse.Namespace) -> None:
    sections = _load_sections(ns)
    for i, thy in enumerate(ns.theory):
        if i > 0:
            print()
        cmd_deps(sections, thy, reverse=True, recursive=ns.recursive)

def _run_outline(ns: argparse.Namespace) -> None:
    cmd_outline(_load_sections(ns), ns.theory, _flags_from_ns(ns))

def _run_largest(ns: argparse.Namespace) -> None:
    args: list[str] = []
    if ns.n is not None:
        args.append(str(ns.n))
    if ns.theory is not None:
        args.append(ns.theory)
    cmd_largest(_load_sections(ns), args)

def _run_find(ns: argparse.Namespace) -> None:
    sections = _load_sections(ns)
    flags = _flags_from_ns(ns)
    for i, pat in enumerate(ns.pattern):
        if i > 0:
            print()
        cmd_find(sections, pat, flags)

def _run_show(ns: argparse.Namespace) -> None:
    sections = _load_sections(ns)
    flags = _flags_from_ns(ns)
    for i, n in enumerate(ns.name):
        if i > 0:
            print()
        cmd_show(sections, n, flags)

def _run_callers(ns: argparse.Namespace) -> None:
    cmd_callers(_load_sections(ns), ns.name, _flags_from_ns(ns))

def _run_callees(ns: argparse.Namespace) -> None:
    sections = _load_sections(ns)
    flags = _flags_from_ns(ns)
    for i, n in enumerate(ns.name):
        if i > 0:
            print()
        cmd_callees(sections, n, flags)

def _run_unused(ns: argparse.Namespace) -> None:
    cmd_unused(_load_sections(ns), _flags_from_ns(ns))

def _run_grep(ns: argparse.Namespace) -> None:
    cmd_grep(_load_sections(ns), ns.pattern, _flags_from_ns(ns))

def _run_sorry(ns: argparse.Namespace) -> None:
    cmd_sorry(_load_sections(ns), getattr(ns, "count", False))

def _run_lines(ns: argparse.Namespace) -> None:
    file_arg = ns.file
    if not Path(file_arg).expanduser().exists():
        # Accept a bare theory name (or a stem-naming path), like grep.
        index = load_index()
        sec = _resolve_theory(index, file_arg)
        if sec is not None:
            file_arg = str(sec.path)
        else:
            suggestion = _suggest_theory(index, file_arg)
            hint = f" (did you mean {suggestion}?)" if suggestion else ""
            print(f"ERROR: file not found: {file_arg}{hint}",
                  file=sys.stderr)
            sys.exit(1)
    cmd_lines(file_arg, ns.ranges)


# -- Parser construction ----------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    top = argparse.ArgumentParser(
        prog="query",
        description="Query the theory index — computed live from .thy files.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    top.add_argument(
        "-R", "--root", metavar="DIR",
        help="Isabelle session directory to query — the directory "
             "containing ROOT, or a parent of per-session ROOTs.  "
             "Overrides $ISABELLE_QUERY_ROOT, any .isabelle-query marker, and "
             "auto-discovery.  Must precede the subcommand.")

    sub = top.add_subparsers(dest="command", title="commands")

    # summary
    p = sub.add_parser("summary", help="theory overview table")
    p.set_defaults(func=_run_summary)

    # theory
    p = sub.add_parser("theory",
                       help="show all entries for a theory "
                            "(-n for a terse namespace listing)")
    p.add_argument("name", help="theory name")
    _add_names_flag(p, "list the theory's namespace entries terse "
                       "(name, tag, line; no bodies) — one per line")
    _add_count_flag(p, "just print the entry count")
    _add_verbatim_flag(p)
    _add_comment_flags(p)
    _add_context_flag(p)
    p.set_defaults(func=_run_theory)

    # defs
    p = sub.add_parser("defs",
                       help="list definitions in a theory "
                            "(-n for terse name listing)")
    p.add_argument("theory", help="theory name")
    _add_names_flag(p, "list definition names terse (name, tag, line)")
    _add_count_flag(p, "just print the definition count")
    p.set_defaults(func=_run_defs)

    # deps
    p = sub.add_parser("deps",
                       help="theories these import (direct; -r for "
                            "transitive); reverse is `uses`")
    p.add_argument("theory", nargs="+", metavar="THEORY",
                   help="one or more theory names or .thy paths "
                        "(brew-style: reported in turn)")
    p.add_argument("-r", "--recursive", action="store_true",
                   help="transitive closure (all indirect imports)")
    p.set_defaults(func=_run_deps)

    # uses (theory-level reverse of deps; brew's deps/uses convention)
    p = sub.add_parser("uses",
                       help="theories that import these (direct; -r for "
                            "transitive); reverse of `deps`")
    p.add_argument("theory", nargs="+", metavar="THEORY",
                   help="one or more theory names or .thy paths "
                        "(brew-style: reported in turn)")
    p.add_argument("-r", "--recursive", action="store_true",
                   help="transitive closure (all indirect importers)")
    p.set_defaults(func=_run_theory_uses)

    # outline
    p = sub.add_parser("outline", help="section structure with entries")
    p.add_argument("theory", help="theory name")
    _add_comment_flags(p)
    _add_context_flag(p)
    p.set_defaults(func=_run_outline)

    # largest
    p = sub.add_parser("largest", help="top N largest entries by span")
    p.add_argument("n", nargs="?", type=int, default=None,
                   help="number of entries (default 20)")
    p.add_argument("theory", nargs="?", default=None,
                   help="restrict to a theory")
    p.set_defaults(func=_run_largest)

    # find
    p = sub.add_parser("find", help="find entries by name (regex)")
    p.add_argument("pattern", nargs="+", metavar="PATTERN",
                   help="regex pattern(s), case-insensitive. "
                        "Pass multiple patterns to run each search "
                        "sequentially (separated by blank lines).")
    _add_mode_flags(p)
    _add_verbatim_flag(p)
    _add_comment_flags(p)
    _add_context_flag(p)
    p.add_argument("--with-comments", action="store_true",
                   help="also search inside text blocks and comments")
    p.set_defaults(func=_run_find)

    # show
    p = sub.add_parser("show", help="show one or more specific entries")
    p.add_argument("name", nargs="+", metavar="NAME",
                   help="entry name(s); each matched exact-then-substring. "
                        "Pass multiple names to print entries sequentially "
                        "(separated by blank lines).")
    _add_mode_flags(p)
    _add_verbatim_flag(p)
    _add_comment_flags(p)
    _add_context_flag(p)
    p.set_defaults(func=_run_show)

    # callers
    p = sub.add_parser("callers", help="find proof-body usages")
    p.add_argument("name", help="entry name")
    _add_path_files_arg(p)
    _add_count_flag(p)
    p.add_argument("-r", "--recursive", action="store_true",
                   help="transitive closure (all indirect callers)")
    _add_names_flag(p)
    p.add_argument("-C", "--context", type=int, default=0, metavar="N",
                   help="show N trailing lines after each match (rg-style; "
                        "useful for multi-line `[where ..., OF ...]` "
                        "invocations whose argument list spans 2-3 lines)")
    p.add_argument("--external", action="store_true",
                   help="exclude callers inside the theory that defines "
                        "NAME (e.g. when auditing whether anything outside "
                        "a given theory uses its primitives, that theory's "
                        "own internal cross-references are noise).  Only "
                        "affects the non-recursive form; "
                        "transitive closure via -r ignores this flag.")
    p.set_defaults(func=_run_callers)

    # callees
    p = sub.add_parser("callees",
                       help="entries this entry references; reverse is "
                            "`callers`")
    p.add_argument("name", nargs="+", metavar="NAME",
                   help="entry name(s); pass multiple to report each in "
                        "turn (separated by blank lines), so "
                        "`callees A B C` replaces a gate-tripping "
                        "`for n in A B C; do callees $n` loop")
    _add_count_flag(p)
    _add_names_flag(p)
    p.add_argument("-r", "--recursive", action="store_true",
                   help="transitive closure (all indirect callees)")
    p.add_argument("--external", action="store_true",
                   help="exclude callees defined in NAME's own theory, "
                        "leaving only cross-theory dependencies (mirror of "
                        "`callers --external`).  Only affects the "
                        "non-recursive form; transitive closure via -r "
                        "ignores this flag.")
    p.set_defaults(func=_run_callees)

    # grep
    p = sub.add_parser("grep",
                       help="regex search across live theory source")
    p.add_argument("pattern",
                   help="regex pattern (Python syntax; `\\|` rewritten to `|` "
                        "for shell-grep compatibility)")
    _add_path_files_arg(p)
    p.add_argument("-a", "--all", dest="all_hits", action="store_true",
                   help="include matches inside text blocks / "
                        "\\<comment> annotations (default: live source only)")
    _add_count_flag(p)
    _add_names_flag(p, "locations + owning entry only "
                       "(skip the matched line text)")
    p.set_defaults(func=_run_grep)

    # sorry — located open-goal listing (grep specialised to the sorry token)
    p = sub.add_parser("sorry",
                       help="list open goals: every live `sorry` with its "
                            "location + owning entry")
    _add_path_files_arg(p)
    _add_count_flag(p, "just print the count (build-summary form)")
    p.set_defaults(func=_run_sorry)

    # lines
    p = sub.add_parser("lines",
                       help="print specified line ranges of FILE "
                            "with `NR| CONTENT` prefix (sandbox-friendly "
                            "alternative to awk loops)")
    p.add_argument("file", metavar="FILE",
                   help="file to read (any text file; no theory parsing). "
                        "A bare theory name (or stem-naming path) resolves "
                        "to its .thy, like outline/show/defs.")
    p.add_argument("ranges", nargs="+", metavar="RANGE",
                   help="line range(s); each `A..B` (inclusive) or `A` "
                        "(single line).  Multiple ranges separated by "
                        "`--` in output.")
    p.set_defaults(func=_run_lines)

    p = sub.add_parser("unused", help="list entries with zero callers")
    _add_count_flag(p)
    p.add_argument("-r", "--recursive", action="store_true",
                   help="cascade: include entries whose callers are all unused")
    p.add_argument("--by-theory", action="store_true",
                   help="group by theory with line counts")
    p.add_argument("--roots", action="store_true",
                   help="forest summary: each root with exclusive subtree size")
    p.add_argument("--keep", action="append", metavar="NAME[,NAME...]",
                   help="treat these names as live roots (never flag as "
                        "unused, and stop the cascade at them).  Repeatable, "
                        "or pass a comma-separated list.  Use for AFP-headline "
                        "theorems and other intentional zero-caller entries.")
    p.set_defaults(func=_run_unused)

    return top


def main():
    global _ROOT_OVERRIDE
    parser = _build_parser()
    ns = parser.parse_args()
    if ns.root:
        _ROOT_OVERRIDE = Path(ns.root).expanduser().resolve()
    if not hasattr(ns, "func"):
        parser.print_help()
        sys.exit(1)
    ns.func(ns)


if __name__ == "__main__":
    main()
