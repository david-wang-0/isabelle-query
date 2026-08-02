#!/usr/bin/env python3
r"""Corpus probe: fact entries whose `proof_line` was never found.

`proof_line` is set by scanning DOWN from the declaration for the first line
matching `PROOF_RE`, and that scan stops at a blank line or at the next
declaration.  A lemma that puts a blank line between its statement and its
`proof` therefore ends up with `proof_line = 0`, as does one whose statement
contains something the scan treats as a stopper.

That is not a roadmap problem, it is a parser gap with several consumers:

  * `_attach_roadmaps` — needs `proof_line` to bound the proof body;
  * `_proof_blocks` — returns [] immediately when it is 0, so `enclosing -b`
    silently offers no drill-down;
  * `_proof_extent` / `body_end_line` — falls back to the declaration end;
  * `shape` — a proof with no `proof_line` contributes no steps.

Reports how many LEMMA/THEOREM-ish entries lack one, and — since the cause is
what decides the fix — what the first non-blank line after the statement
actually is.

Usage:  probe_missing_proof_line.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120
FACT_TAGS = {"LEMMA", "THEOREM", "COROLLARY"}

n_facts = n_missing = 0
causes: Counter = Counter()
samples: dict[str, list[str]] = {}


def classify(lines, e):
    r"""Why did the scan miss it?  Walk the statement the way the parser does."""
    i = e.thy_line          # 0-indexed line after the declaration line
    saw_blank = False
    while i < len(lines) and i < e.thy_end:
        line = lines[i]
        if not line.strip():
            saw_blank = True
            i += 1
            continue
        if parsing.PROOF_RE.match(line):
            return ("blank line before the proof" if saw_blank
                    else "proof present but scan stopped early"), i + 1
        i += 1
    return "no proof line anywhere in the span", 0


for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
            lines = sec.source()
        except Exception:  # noqa: BLE001
            continue
        for e in sec.entries:
            if e.tag not in FACT_TAGS or e.thy_line <= 0:
                continue
            n_facts += 1
            if e.proof_line:
                continue
            n_missing += 1
            cause, found = classify(lines, e)
            causes[cause] += 1
            bucket = samples.setdefault(cause, [])
            if len(bucket) < 6:
                bucket.append(
                    f"  {thy_path.stem}:{e.thy_line} {e.name!r} "
                    f"span={e.thy_line}..{e.thy_end}"
                    + (f"  proof really at {found}" if found else "")
                    + "\n      decl:  " + lines[e.thy_line - 1].strip()[:92]
                    + (("\n      proof: " + lines[found - 1].strip()[:92])
                       if found else ""))

print(f"fact entries (LEMMA/THEOREM/COROLLARY): {n_facts:,}")
print(f"  with no proof_line: {n_missing:,} "
      f"({100.0 * n_missing / max(n_facts, 1):.2f}%)")
print("\nwhy:")
for cause, c in causes.most_common():
    print(f"  {cause:<40} {c:>6}")
for cause, _c in causes.most_common():
    print(f"\n--- {cause} ---")
    for s in samples.get(cause, []):
        print(s)
