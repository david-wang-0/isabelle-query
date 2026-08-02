#!/usr/bin/env python3
r"""Corpus probe: which entries does gating the declaration scan remove?

`extract_entries` used to walk the raw source and skip only `text` blocks, so
the declaration grammar was applied inside comments and ML bodies too.  ML's
`fun` is spelled exactly like Isabelle's, and authors routinely supersede a
`definition` by commenting the old one out — so both minted entries that do not
exist.  Feeding it `nonisar_ranges` closes that.

This audit matters more than the count.  Before the gate, a tokenizer false
positive merely ADDED a phantom; now it DELETES a real declaration, which is
the quieter and worse failure.  So the probe parses each theory twice — gated
and ungated — diffs the entries, and classifies every removed one by the
construct that covers it.  Read the `?` bucket: anything landing there is a
declaration removed for a reason the probe cannot see, and is a defect.

Usage:  probe_ml_phantom_entries.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120


def covering_kind(lines, ranges, line_no):
    """Which construct covers `line_no` — found by walking back to its opener.

    The opener is often mid-line, not at a line start: `show ... oops (* TODO`
    opens a comment that swallows the four declarations under it, and a
    classifier that only recognised `(*` in column 0 reported that as
    unexplained.  Searching the whole line is what makes the `?` bucket mean
    "no opener found", which is the only reading under which it is a defect.
    """
    for lo, hi in ranges:
        if not lo <= line_no <= hi:
            continue
        for k in range(lo - 1, max(-1, lo - 10), -1):
            if parsing._leads_with_ml(lines[k]):
                return "ml-body"
            pos, kind = max((lines[k].rfind("(*"), "comment"),
                            (lines[k].rfind("{*"), "verbatim"),
                            (lines[k].rfind("\\<^cancel>"), "cancel"))
            if pos >= 0:
                return kind
        return "region(?)"
    return "?"


n_thy = n_gated = n_ungated = 0
by_kind: Counter = Counter()
by_tag: Counter = Counter()
suspicious: list[tuple[str, str, str]] = []
samples: list[tuple[str, str, str, str]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            lines = thy_path.read_text(errors="replace").splitlines()
        except OSError:
            continue
        n_thy += 1
        ranges = parsing.extract_nonisar_ranges(lines)
        gated = parsing.extract_entries(lines, nonisar_ranges=ranges)
        ungated = parsing.extract_entries(lines, nonisar_ranges=[])
        n_gated += len(gated)
        n_ungated += len(ungated)
        if len(gated) == len(ungated):
            continue
        kept = {(e.name, e.thy_line) for e in gated}
        for e in ungated:
            if (e.name, e.thy_line) in kept:
                continue
            kind = covering_kind(lines, ranges, e.thy_line)
            by_kind[kind] += 1
            by_tag[e.tag] += 1
            text = lines[e.thy_line - 1].strip()[:84]
            if kind in ("?", "region(?)"):
                suspicious.append((e.name, f"{thy_path.stem}:{e.thy_line}",
                                   text))
            elif len(samples) < 14:
                samples.append((kind, e.name, f"{thy_path.stem}:{e.thy_line}",
                                text))

removed = n_ungated - n_gated
print(f"theories={n_thy}  entries: gated {n_gated:,}  ungated {n_ungated:,}")
print(f"  removed by the gate: {removed:,} "
      f"({100.0 * removed / max(n_ungated, 1):.3f}%)")
print("\nby covering construct  (a '?' here is a DEFECT — a real declaration "
      "removed):")
for kind, c in by_kind.most_common():
    print(f"  {kind:<12} {c}")
print("\nby tag:")
for tag, c in by_tag.most_common():
    print(f"  {tag:<10} {c}")
if suspicious:
    print(f"\n!! UNEXPLAINED REMOVALS: {len(suspicious)}")
    for name, loc, text in suspicious[:20]:
        print(f"  {name:<30} {loc}\n      {text}")
print("\nsamples:")
for kind, name, loc, text in samples:
    print(f"  {kind:<9} {name:<28} {loc}\n      {text}")
