#!/usr/bin/env python3
r"""Corpus probe: what is left of the line-granular noise notion?

Issue #3's step 3 asks for `_noise_spans` to be rebuilt on the tokenizer.  Two
of its inputs are still line-based extractors, and they fail in opposite
directions — this measures both before deciding whether either is worth moving.

  `\<comment> \<open>...\<close>`  — a MARGINAL comment.  It normally trails
      live proof text (`by simp \<comment> \<open>why\<close>`), and
      `comment_ranges` marks the whole line, so the `by simp` is dropped from
      the method census and any citation beside it is dropped from the graph.
      That is a FALSE NEGATIVE — a true edge lost — which this project treats
      as the worse direction.

  `section`/`subsection` headings — matched on raw lines, so one inside a
      comment or an ML body mints a phantom outline row AND a phantom span
      boundary, cutting the entry above it short.  Same class as the
      commented-out declaration, different scan.

Reports how often each actually occurs, so step 3 is scoped by evidence rather
than by symmetry.

Usage:  probe_line_noise_residual.py [N_ENTRIES]
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

n_thy = n_lines = 0
n_comment_lines = n_comment_partial = 0
n_sections = n_section_phantom = 0
n_uncovered = 0
comment_samples: list[tuple[str, str]] = []
uncovered_samples: list[tuple[str, str]] = []
section_samples: list[tuple[str, str]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            lines = thy_path.read_text(errors="replace").splitlines()
        except OSError:
            continue
        n_thy += 1
        n_lines += len(lines)

        spans = parsing.extract_nonisar_spans(lines)
        ranges = parsing.extract_nonisar_ranges(lines, spans)
        dead = {i for lo, hi in ranges for i in range(lo, hi + 1)}

        # --- marginal comments that share their line with proof text ---
        for lo, hi in parsing.extract_comment_ranges(lines):
            n_comment_lines += hi - lo + 1
            head = lines[lo - 1]
            before = head.split("\\<comment>")[0].strip()
            if before and not before.startswith("(*"):
                n_comment_partial += 1
                if len(comment_samples) < 10:
                    comment_samples.append(
                        (f"{thy_path.stem}:{lo}", head.strip()[:96]))
            # Safety: `comment_ranges` has been dropped from `_noise_spans` in
            # favour of the tokenizer.  The test is whether the MARKER survives
            # redaction — not whether live text does, since live text surviving
            # beside a note is the entire point.  A surviving `\<comment>` means
            # the tokenizer never saw the note, so its prose is back in the
            # scans: a regression.
            for k in range(lo, hi + 1):
                red = lines[k - 1]
                for a, b in spans.get(k, []):
                    red = red[:a] + " " * (b - a) + red[b:]
                if "\\<comment>" in red:
                    n_uncovered += 1
                    if len(uncovered_samples) < 10:
                        uncovered_samples.append(
                            (f"{thy_path.stem}:{k}", lines[k - 1].strip()[:96]))

        # --- headings minted inside a region ---
        for _level, title, line_no in parsing.extract_sections(lines):
            n_sections += 1
            if line_no in dead:
                n_section_phantom += 1
                if len(section_samples) < 10:
                    section_samples.append(
                        (f"{thy_path.stem}:{line_no}",
                         lines[line_no - 1].strip()[:96]))

print(f"theories={n_thy}  lines={n_lines:,}")
print(f"\n\\<comment> lines: {n_comment_lines:,}")
print(f"  sharing their line with live proof text: {n_comment_partial:,} "
      f"({100.0 * n_comment_partial / max(n_comment_lines, 1):.1f}% of them)"
      "\n  ^ each of these currently drops the LIVE half too (false negative)")
for loc, text in comment_samples:
    print(f"    {loc:<32} {text}")
print(f"\n  \\<comment> markers surviving redaction (must be 0 — prose the "
      f"tokenizer missed): {n_uncovered:,}")
for loc, text in uncovered_samples:
    print(f"    {loc:<32} {text}")
print(f"\nsection headings: {n_sections:,}")
print(f"  inside a non-Isar region (phantom heading + span boundary): "
      f"{n_section_phantom:,}")
for loc, text in section_samples:
    print(f"    {loc:<32} {text}")
