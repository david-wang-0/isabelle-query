#!/usr/bin/env python3
"""Split the qualified-label count into its two causes [disambig-loci].

`theory_labels` now grows the label from the theory's declared NAME rather
than the file's stem, and the AFP's qualified-label count moved 1,316 -> 2,113.
Two different things put a `/` in a label and only one of them is
disambiguation:

* the ROOT declares the theory with a directory (`theories "Sub/Thy"`), so the
  theory's own NAME carries the slash and the label merely repeats it;
* the name collides, and `theory_labels` prefixed a directory to separate it.

Quoting 2,113 as "labels this disambiguates" would fold the first into the
second.  This counts them apart.
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.render import locus_labels  # noqa: E402


def main() -> int:
    root = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    labels = locus_labels(sections)

    dir_named = [s for s in sections if "/" in s.theory]
    grown = [s for s in sections
             if labels[s.path] != s.theory]
    qualified = [s for s in sections if "/" in labels[s.path]]
    per_name: Counter[str] = Counter(s.theory for s in sections)
    collide = {n for n, c in per_name.items() if c > 1}

    print(f"{len(sections)} theories under {root}")
    print(f"{len(qualified):5}  labels contain a '/'")
    print(f"{len(dir_named):5}  ...because the ROOT already spells the theory "
          f"with a directory (label == theory name)")
    print(f"{len(grown):5}  ...because `theory_labels` GREW the label to "
          f"separate a collision")
    print(f"{len(collide):5}  colliding names, covering "
          f"{sum(1 for s in sections if s.theory in collide)} sections")
    print("\nexamples of a ROOT-spelled directory name:")
    for s in dir_named[:5]:
        print(f"  {s.theory:52} -> {labels[s.path]}")
    print("\nexamples of a grown label:")
    for s in grown[:5]:
        print(f"  {s.theory:52} -> {labels[s.path]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
