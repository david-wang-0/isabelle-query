#!/usr/bin/env python3
r"""Corpus probe: where do `\<comment>` notes actually sit, and which attach?

`_attach_roadmaps` attaches a note to an entry when

    entry.proof_line <= line <= entry.thy_end

so a note on the declaration line, one in the statement above the proof, and
every note in an entry with no `proof_line` are all dropped.

Whether that is right is a judgement about what a roadmap IS, so this reports
the real distribution with samples of each position rather than arguing from
one constructed case.  It is also how the boundary got fixed: the rule was once
`proof_line < line`, which excluded not 9 notes but every SINGLE-LINE proof,
since such a proof has no line strictly inside it.

Usage:  probe_roadmap_positions.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120
# A second argument names one bucket to dump in FULL, so a small category can
# be read case by case instead of sampled — which is how you tell a parser gap
# from a category that genuinely does not belong.
DUMP = sys.argv[2] if len(sys.argv) > 2 else None
MAX_SAMPLES = 4

kinds: Counter = Counter()
proofless_tags: Counter = Counter()
samples: dict[str, list[str]] = {}
n_thy = n_notes = 0


def note(kind, thy, line_no, lines, e=None):
    kinds[kind] += 1
    bucket = samples.setdefault(kind, [])
    if len(bucket) >= MAX_SAMPLES and not (DUMP and DUMP in kind):
        return
    ctx = f"  {thy}:{line_no}"
    if e is not None:
        ctx += (f"   [{e.name} decl={e.thy_line} proof={e.proof_line} "
                f"end={e.thy_end}]")
    bucket.append(ctx + "\n      " + lines[line_no - 1].strip()[:104])


for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
            lines = sec.source()
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        _spans, note_starts = parsing.scan_regions(lines)
        placed = sorted((e.thy_line, e) for e in sec.entries if e.thy_line > 0)
        keys = [k for k, _ in placed]
        from bisect import bisect_right
        for line_no, _content in parsing.extract_comment_lines(lines,
                                                               note_starts):
            n_notes += 1
            idx = bisect_right(keys, line_no) - 1
            if idx < 0:
                note("before any entry", thy_path.stem, line_no, lines)
                continue
            e = placed[idx][1]
            if line_no > e.thy_end:
                note("after the entry ends", thy_path.stem, line_no, lines, e)
            elif line_no == e.thy_line:
                note("on the declaration line", thy_path.stem, line_no, lines, e)
            elif not e.proof_line:
                # Split by tag: a DEF/FUN genuinely has no proof, whereas a
                # LEMMA/THEOREM without a `proof_line` is a parser gap, and its
                # notes are dropped for the wrong reason.
                proofless_tags[e.tag] += 1
                note("entry has no proof_line", thy_path.stem, line_no, lines, e)
                if e.tag in ("LEMMA", "THEOREM"):
                    # The parser-gap subset: a fact WITH a proof whose
                    # `proof_line` was never found.  Small enough to read.
                    note(f"no proof_line but tag={e.tag}", thy_path.stem,
                         line_no, lines, e)
            elif line_no == e.proof_line:
                note("ON the proof line (ATTACHED)", thy_path.stem, line_no, lines, e)
            elif line_no < e.proof_line:
                note("in the statement", thy_path.stem, line_no, lines, e)
            else:
                note("in the proof body (ATTACHED)", thy_path.stem, line_no,
                     lines, e)

print(f"theories={n_thy}  genuine \\<comment> notes={n_notes:,}\n")
for kind, c in kinds.most_common():
    mark = "  <- attached today" if "ATTACHED" in kind else ""
    print(f"  {kind:<30} {c:>6}  {100.0 * c / max(n_notes, 1):5.1f}%{mark}")
print("\nthe `no proof_line` bucket, by tag  (LEMMA/THEOREM here = a parser gap):")
for tag, c in proofless_tags.most_common():
    print(f"  {tag:<12} {c}")
for kind, _c in kinds.most_common():
    print(f"\n--- {kind} ---")
    for s in samples.get(kind, []):
        print(s)
