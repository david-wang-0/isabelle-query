#!/usr/bin/env python3
r"""Corpus probe: how many entries acquire a locale/class target?

The reporting counterpart of `probe_locale_naming.py` (which measured whether
blocks can be ATTRIBUTED — 4,003/4,003).  This measures what the attached
`Entry.blocks` / `Entry.in_target` actually cover once the parser records them,
and how the two kinds of evidence overlap: lexical nesting inside `locale foo
... begin`, versus an explicit `(in foo)` written on the declaration.

They are not the same question, and Isabelle lets them disagree — a
`lemma (in bar)` inside `locale foo` belongs to `bar` — so the count of entries
where BOTH are present and they differ is worth watching.

Usage:  probe_targets.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120


def main() -> None:
    n_thy = n_entries = 0
    n_lexical = n_explicit = n_both = n_disagree = n_any = 0
    depth: Counter = Counter()
    kinds: Counter = Counter()

    for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
        for thy_path in sorted(ent.rglob("*.thy")):
            try:
                sec = cli._parse_one(thy_path.stem, thy_path)
            except Exception:  # noqa: BLE001
                continue
            n_thy += 1
            for e in sec.entries:
                n_entries += 1
                if e.blocks:
                    n_lexical += 1
                    depth[len(e.blocks)] += 1
                    kinds[e.blocks[-1][0]] += 1
                if e.in_target:
                    n_explicit += 1
                if e.blocks and e.in_target:
                    n_both += 1
                    if e.in_target != e.blocks[-1][1]:
                        n_disagree += 1
                if e.target:
                    n_any += 1

    pct = lambda n: f"{100.0 * n / max(n_entries, 1):.1f}%"  # noqa: E731
    print(f"theories={n_thy:,}   entries={n_entries:,}")
    print(f"\nentries with a target of any kind   {n_any:>7,}  ({pct(n_any)})")
    print(f"  lexically inside a named block    {n_lexical:>7,}  ({pct(n_lexical)})")
    print(f"  explicit `(in foo)` modifier      {n_explicit:>7,}  ({pct(n_explicit)})")
    print(f"  both present                      {n_both:>7,}")
    print(f"    and they DISAGREE               {n_disagree:>7,}"
          f"   (`(in foo)` wins — Isabelle retargets)")
    print("\nnesting depth of the lexical chain:")
    for d in sorted(depth):
        print(f"  depth {d:<3} {depth[d]:>7,}")
    print("\ninnermost block kind:")
    for k, c in kinds.most_common():
        print(f"  {k:<16} {c:>7,}")


if __name__ == "__main__":
    main()
