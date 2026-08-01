"""Core data model — the dataclasses and shared tag families every layer reads.

The bottom of the ``isabelle_query`` module DAG
(``model → parsing → graph → render → commands → cli``): pure data with no
dependency on any other package module, so every layer above can import it
without risking a cycle.

* :class:`Entry` / :class:`TheorySection` / :class:`CallGraph` — the parsed
  representation of a theory tree (populated by ``parsing``, consumed
  everywhere).
* :class:`CmdFlags` — the uniform flag bundle threaded from the CLI into each
  command function.
* The tag frozensets (:data:`_DEFINITION_TAGS`, :data:`_CITABLE_TAGS`) and the
  call-graph short-name floor (:data:`_DROP_NAMES_UPTO`) — membership lists
  named once here so they can't drift between the call sites that share them.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


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
    thy_end: int = 0        # 1-indexed end of this entry's span: the line
                            # before the *next* entry's src_start (its leading
                            # `text` preamble, if any, else its declaration) or
                            # the next section.  Includes this entry's trailing
                            # blank lines but NOT the following entry's leading
                            # doc block — that block documents, and belongs to,
                            # the following entry (see src_start).  For a safe
                            # relocation cut use body_end_line (which also drops
                            # the trailing blanks).
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

    @property
    def src_start(self) -> int:
        """First line of this entry's span: the leading `text` preamble's
        start if one is attached, else the declaration line.  The preamble
        documents THIS entry, so it counts as part of this entry's extent
        (and is excluded from the preceding entry's `thy_end`)."""
        return self.preamble[0] if self.preamble else self.thy_line

    @property
    def line_count(self) -> int:
        """Inclusive source-span length [src_start..thy_end]; 0 if unplaced."""
        return self.thy_end - self.src_start + 1 if self.thy_line > 0 else 0


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
        # folded into `_noise_spans`, so every search/scan (grep, methods, the
        # call graph, proof-block drill-down) skips them as non-live source.
        # (Distinct from comment_lines, which records first-line content for the
        # roadmap-attachment feature.)
    nonisar_ranges: list[tuple[int, int]] = field(default_factory=list)
        # Lines holding no live Isar text: `(* ... *)` comments (which nest),
        # `\<^cancel>` regions, legacy `{* ... *}` verbatim, and ML bodies.
        # Lexical, not grammatical — see `parsing.extract_nonisar_ranges`.
        # Folded into `_noise_spans` (so no scan reads them as proof text) and
        # into the span-boundary mask (so a commented-out `end` does not cut
        # the declaration above it).
    is_thy: bool = True
        # False for a non-`.thy` path passed as a trailing grep positional
        # (e.g. `query grep PAT notes.md`).  Such a section is parsed
        # *plainly* (no entries, outline, text/comment blocks): the Isabelle
        # entry-grammar does not apply to Markdown / prose, so cmd_grep treats
        # it as plain `grep` — every matched line, no synthesised owning-entry
        # label, no live/comment classification.
    session: str | None = None
        # The Isabelle *session* this theory was declared by (the `session
        # NAME` in the owning ROOT), attached when loaded via a ROOT walk
        # (`_sections_from_dir`); None for a single-file / stdin / bare-glob
        # load with no owning session.  A theory reached through several
        # sessions is attributed to the first that references it (matching
        # the build's own first-declaration-wins dedup).  Consumed by
        # `summary --by-session` to roll theory-level counts up to the
        # session / corpus level.
    line_window: tuple[int, int | None] | None = None
        # Optional inclusive 1-indexed [lo, hi] line window from a grep
        # `PATH:A..B` positional, set by `_load_sections(windows=True)`.
        # `_grep_sections` skips lines outside it; commands that don't read
        # it (largest/sorry) never see a window (the suffix isn't parsed for
        # them, so `largest Foo:1..9` errors rather than silently ignoring).
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


# Tag families shared across commands.  Named so the membership lists can't
# drift between call sites: definition-like exports vs the citation-graph-
# eligible kinds (the two sets genuinely differ — datatypes/records/types are
# definitions but never call-graph nodes; lemmas/theorems are the reverse).
_DEFINITION_TAGS = frozenset(
    {"DEF", "ABBREV", "FUN", "DATATYPE", "RECORD", "TYPE"})
_CITABLE_TAGS = frozenset({"LEMMA", "THEOREM", "FUN", "DEF", "ABBREV"})


# Default short-name floor for the citation graph: a length-1 token (`x`, `a`,
# the wildcard `_`) is a bound variable in nearly every proof, so by default
# single-char names are not citation nodes.  Overridden per-invocation via
# ``--drop-names-upto`` (see ``graph._is_citation_name``).
_DROP_NAMES_UPTO = 1


@dataclass
class CmdFlags:
    """Uniform flag bundle passed to command functions."""
    mode: str = "first"          # first / all / count / names
    verbatim: bool = False       # -V / --verbatim
    statement: bool = False      # --statement / --stmt
                                 # find: match the statement slice (input);
                                 # show: render only the statement slice (output)
    comments: str = "on"         # on / off / only
    context: int = 2             # -U N / --context N
    with_comments: bool = False  # --with-comments (find + grep: search prose)
    recursive: bool = False      # -r / --recursive
    by_theory: bool = False      # --by-theory (unused)
    roots: bool = False          # --roots (unused)
    keep: frozenset[str] = frozenset()  # --keep (unused: live roots)
    external: bool = False       # --external (callers: skip defining theory)
    drop_names_upto: int = _DROP_NAMES_UPTO  # --drop-names-upto (call graph)
