"""Rendering — turning entries into the text the CLI prints.

The fourth layer of the DAG (above ``parsing``, below ``commands``).  Pure
formatting: the ``[src ...]`` extent annotation (``_format_extent``), preamble
and roadmap previews, the single-entry renderer (``render_entry``) with its
statement / verbatim / comments modes, and the shared verbosity-mode dispatch
(``_emit_matches``) that every match-listing command funnels through.

Depends only on ``model`` and ``parsing`` (``_proof_extent`` for the proof-span
walk, ``LATEX_LINE_RE`` for the figure-noise filter) — no call-graph or CLI
dependency.  The locus grammar and proof-block drill-down live with the
commands that consume them, not here.
"""

from __future__ import annotations

import re

from isabelle_query.model import CmdFlags, Entry, TheorySection
from isabelle_query.parsing import LATEX_LINE_RE, _proof_extent

def _format_extent(entry: Entry) -> str:
    """Format the `[src ...]` extent annotation for an entry.

    `src` is the entry's full span ``src_start..thy_end`` — a leading `text`
    preamble through the trailing blanks before the next entry.  The `body`
    span ``thy_line..body_end_line`` is surfaced separately whenever it is
    narrower at either end: a leading doc block (``src_start < thy_line``) or
    a trailing inter-lemma block (``body_end < thy_end``).  The body end is
    the safe cut boundary for `bin/move-block.py`; `src` is the
    end-of-region the next entry-or-section starts after.
    """
    if not entry.thy_line:
        return ""
    src_start = entry.src_start
    span_size = entry.line_count
    body_end = entry.body_end_line or entry.thy_end
    if src_start < entry.thy_line or body_end < entry.thy_end:
        body_size = body_end - entry.thy_line + 1
        return (f"[src {src_start}..{entry.thy_end}, "
                f"body {entry.thy_line}..{body_end}, "
                f"{body_size}/{span_size} lines]")
    return f"[src {src_start}..{entry.thy_end}, {span_size} lines]"


def _format_name_line(sec: TheorySection, entry: Entry) -> str:
    ext = _format_extent(entry)
    span = f" {ext}" if ext else ""
    return f"{entry.name} ({entry.tag}) — {sec.theory}{span}"


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


def _statement_text(sec: TheorySection, entry: Entry) -> str:
    """The entry's statement slice as one string: the declaration lines
    [thy_line..decl_end_line] (the lemma/def statement, not the proof).

    Falls back to `entry.text` for entries without a source location (e.g.
    an AXIOM placeholder), matching how `render_entry` degrades — so a
    statement search still sees *something* for those.
    """
    if not entry.thy_line:
        return entry.text
    return "\n".join(sec.slice(entry.thy_line, entry.decl_end_line))


def render_entry(sec: TheorySection, entry: Entry, *,
                 verbatim: bool = False,
                 statement: bool = False,
                 comments: str = "on",
                 context: int = 2) -> str:
    """Render a single entry.

    statement:       just the declaration slice [thy_line..decl_end_line]
                     (the statement, no proof) — the narrowest view
    verbatim:        full source slice [thy_line..thy_end]
    comments='on':   preamble (truncated) + header + statement + proof preview
                     + roadmap (truncated)
    comments='off':  header + statement + proof preview only (current default)
    comments='only': preamble (full) + header + roadmap (full), no statement
    context:         lines of preamble preview / roadmap entries shown

    `statement` and `verbatim` are opposite ends of the slice spectrum
    (declaration-only vs declaration+proof); `show` declares them mutually
    exclusive at the CLI.  If both somehow arrive, the narrower one wins.
    """
    ext = _format_extent(entry)
    header = f"--- {entry.name} ({entry.tag}) — {sec.theory}.thy {ext} ---"

    # No source location (e.g. AXIOM placeholder) → fall back to entry.text
    if not entry.thy_line:
        return f"{header}\n{entry.text}"

    if statement:
        body_lines = sec.slice(entry.thy_line, entry.decl_end_line)
        return header + "\n" + "\n".join(body_lines)

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
                  matches: list[Entry], pattern: str, flags: "CmdFlags",
                  *, statement: bool = False) -> None:
    # `statement` is the *render* selector (declaration-only).  It is passed
    # explicitly rather than read off `flags` so it stays a `show` concern:
    # on `find`, `flags.statement` means "match the statement slice", which
    # must not bleed into how the matched entries are rendered.
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
                               statement=statement,
                               comments=flags.comments,
                               context=flags.context))
            print()
        return

    # mode == "first"
    e0 = matches[0]
    print(render_entry(sections_by_theory[e0.theory], e0,
                       verbatim=flags.verbatim,
                       statement=statement,
                       comments=flags.comments,
                       context=flags.context))
    if len(matches) > 1:
        print()
        print(f"[+{len(matches) - 1} more match(es).  Use --all to show, "
              f"--names for a list, --count for just the count.]")
