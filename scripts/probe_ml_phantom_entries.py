#!/usr/bin/env python3
r"""Corpus probe: entries DECLARED on a line that is not live Isar text.

`extract_entries` walks the raw source and skips only `text` blocks, so a line
inside an ML body still gets the declaration grammar applied to it.  ML's `fun`
is spelled exactly like Isabelle's, so `fun interpret_floatariths_congs ctxt =`
inside `ML \<open>...\<close>` mints an Isabelle entry that does not exist.

This is the same class issue #3 describes — a non-Isar region read as live text
— but at the DECLARATION scan rather than the citation scan, which is why
redacting the citation scan did not fix it.  It surfaced from the other end:
after the switch, such phantoms lose their (equally phantom) ML citations and
start showing up in `unused`.

Reports every entry whose declaration line lies inside a `nonisar_ranges` span,
by declaring command, since the fix is to feed `extract_entries` the same
redacted view.

Usage:  probe_ml_phantom_entries.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

n_entries = n_all = n_phantom = 0
by_tag: Counter = Counter()
samples: list[tuple[str, str, str]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception:  # noqa: BLE001
            continue
        n_entries += 1
        n_all += len(sec.entries)
        if not sec.nonisar_ranges:
            continue
        dead = set()
        for lo, hi in sec.nonisar_ranges:
            dead.update(range(lo, hi + 1))
        src = sec.source()
        for e in sec.entries:
            if e.thy_line in dead:
                n_phantom += 1
                by_tag[e.tag] += 1
                if len(samples) < 20:
                    samples.append((e.name, f"{sec.theory}:{e.thy_line}",
                                    src[e.thy_line - 1].strip()[:88]))

print(f"theories={n_entries}  entries={n_all:,}")
print(f"  declared on a NON-ISAR line: {n_phantom:,} "
      f"({100.0 * n_phantom / max(n_all, 1):.3f}%)")
print("\nby tag:")
for tag, c in by_tag.most_common():
    print(f"  {tag:<10} {c}")
print("\nsamples:")
for name, loc, text in samples:
    print(f"  {name:<34} {loc}")
    print(f"      {text}")
