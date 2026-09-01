#!/usr/bin/env python3
r"""What the name-keyed line index cost [name-is-not-identity].

`graph._build_line_index` used to return `{theory name: [entry spans]}`, and
every consumer reads it back INSIDE a loop over the same sections.  When two
theories share a name the map keeps one, so the other section's lines were
attributed to entries in a different file.  Three consumers:
`_build_call_graph` (which entry cites what), `_scan_methods` and
`_grep_sections` (the owner column).  `_noise_ranges` and `_build_def_sites`
were keyed the same way and are worse, because both SUPPRESS: a collapse there
drops a real citation and admits a fake one.

The measurement is deliberately independent of the fix — it compares, for
every line of every shadowed section, the owner a NAME-keyed map gives against
the owner that section's own spans give, and so runs unchanged before or
after.  Over the AFP:

    758 of 9,910 sections shadowed
    381,710 of their 449,860 lines given a different owner
      151,758 named an entry from another file
      215,220 lost their owner
       14,732 gained one they do not have
    38,068 lines classified prose-vs-live the wrong way round

and downstream, 48,177 citation edges dropped, 43,912 restored, `unused`
95,696 -> 104,028.

    python scripts/probe_name_keyed_index.py [ROOT]
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph  # noqa: E402


def main() -> int:
    root = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    print(f"{len(sections)} theories under {root}")

    # The name-keyed map the code used to build, reconstructed here so the
    # probe measures the same thing whichever side of the fix it runs on.
    by_name = {}
    for s in sections:
        by_name[s.theory] = graph._build_line_index([s])[s.path]
    seen: dict[str, object] = {}
    for s in sections:
        seen[s.theory] = s
    shadowed = [s for s in sections if seen[s.theory] is not s]
    print(f"{len(shadowed)} sections shadowed in a name-keyed map")

    lines_checked = wrong_owner = lost_owner = gained_owner = 0
    per_theory: Counter[str] = Counter()
    for sec in shadowed:
        own = graph._build_line_index([sec])[sec.path]
        other = by_name.get(sec.theory, [])
        for ln in range(1, sec.thy_lines + 1):
            lines_checked += 1
            a = graph._entry_at_line(own, ln)
            b = graph._entry_at_line(other, ln)
            an = a.name if a is not None else None
            bn = b.name if b is not None else None
            if an == bn:
                continue
            per_theory[sec.theory] += 1
            if an is None:
                gained_owner += 1
            elif bn is None:
                lost_owner += 1
            else:
                wrong_owner += 1

    print(f"\n{lines_checked} lines in shadowed sections")
    print(f"{per_theory.total()} of them get a different owner "
          f"through the name-keyed index:")
    print(f"  {wrong_owner:8}  named a DIFFERENT entry "
          f"(an entry from another file)")
    print(f"  {lost_owner:8}  lost their owner (reported as unowned)")
    print(f"  {gained_owner:8}  gained an owner they do not have")
    print("\nworst theories:")
    for n, c in per_theory.most_common(8):
        print(f"  {n:32} {c}")

    # Prose spans and def sites collapse the same way, and both SUPPRESS.
    noise = {}
    for s in sections:
        noise[s.theory] = graph._noise_ranges([s])[s.path]
    noise_wrong = 0
    for sec in shadowed:
        own = graph._noise_ranges([sec])[sec.path]
        other = noise.get(sec.theory, [])
        own_lines = {n for r in own for n in r}
        other_lines = {n for r in other for n in r}
        noise_wrong += len(own_lines ^ other_lines)
    print(f"\n_noise_ranges: {noise_wrong} lines classified prose-vs-live "
          f"differently in shadowed sections")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
