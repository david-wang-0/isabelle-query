#!/usr/bin/env python3
r"""Corpus probe: what `Entry.annotations` homes, and what `roadmap` keeps.

Two questions at once, because they trade off against each other:

1. **Did the roadmap move?**  Widening attachment must not disturb the proof
   roadmap that already worked.  The old rule is recomputed here from scratch
   (`proof_line and proof_line <= line <= thy_end`) and compared against today's
   `Entry.roadmap` property, entry by entry.  `roadmap drift` must be 0 — this
   is the whole safety claim, and a unit test cannot make it because the shapes
   that break it (a one-liner whose declaration line IS its proof line) are
   found by volume, not by construction.

2. **How much is still unowned?**  The point of the change.  Reports the tag
   split of what is now attached, and what remains outside every entry's span
   (theory-level prose; locale-closing `end \<comment> \<open>Context of ...`).

Usage:  probe_annotations.py [N_ENTRIES]
"""
import sys
from bisect import bisect_right
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

kinds: Counter = Counter()
by_tag: Counter = Counter()
unowned: Counter = Counter()
drift: list[str] = []
n_thy = n_notes = n_attached = 0

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
            lines = sec.source()
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        _spans, note_starts, _inner = parsing.scan_regions(lines)
        notes = parsing.extract_comment_lines(lines, note_starts)
        n_notes += len(notes)

        # (1) the old rule, recomputed independently of the parser.
        old: dict[int, list[tuple[int, str]]] = {}
        placed = sorted((e.thy_line, e) for e in sec.entries if e.thy_line > 0)
        keys = [k for k, _ in placed]
        for line_no, content in notes:
            idx = bisect_right(keys, line_no) - 1
            if idx < 0:
                unowned["above the first declaration"] += 1
                continue
            e = placed[idx][1]
            if e.proof_line and e.proof_line <= line_no <= e.thy_end:
                old.setdefault(id(e), []).append((line_no, content))
            elif line_no > e.thy_end:
                unowned["past the entry's span (locale structure)"] += 1
        for _ln, e in placed:
            if old.get(id(e), []) != e.roadmap:
                drift.append(f"  {thy_path.stem}:{e.thy_line} {e.name}\n"
                             f"      old={old.get(id(e), [])}\n"
                             f"      new={e.roadmap}")

        # (2) what the wider rule now homes.
        for e in sec.entries:
            for _line_no, _content, kind in e.annotations:
                n_attached += 1
                kinds[kind] += 1
                by_tag[(kind, e.tag)] += 1

pct = lambda c: f"{100.0 * c / max(n_notes, 1):5.1f}%"  # noqa: E731
print(f"theories={n_thy:,}  genuine \\<comment> notes={n_notes:,}\n")
print(f"  ATTACHED       {n_attached:>6}  {pct(n_attached)}")
for kind in ("decl", "statement", "proof"):
    print(f"    {kind:<12} {kinds[kind]:>6}  {pct(kinds[kind])}")
print(f"  unowned        {sum(unowned.values()):>6}  "
      f"{pct(sum(unowned.values()))}")
for why, c in unowned.most_common():
    print(f"    {why:<44} {c:>6}  {pct(c)}")

print("\nby entry tag (the definition families are the ones that had NOTHING):")
for (kind, tag), c in by_tag.most_common(14):
    print(f"  {kind:<10} {tag:<10} {c:>6}")

print(f"\nroadmap drift vs the old rule: {len(drift)}"
      f"{'  <- MUST BE 0' if drift else '   (byte-identical)'}")
for d in drift[:12]:
    print(d)
