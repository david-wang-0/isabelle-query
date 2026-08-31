#!/usr/bin/env python3
r"""What a bare `theory:line` locus cost the located-hit verbs [disambig-loci].

`[disambig-names]` shipped `render.theory_labels` (the shortest suffix that
names one theory) and put it on `largest`, plus the resolver half.  Every
`theory:line` emitter still printed the bare stem, so `largest` said
`Virtual_Substitution/QE` and `enclosing Virtual_Substitution/QE:3495` answered
`QE:3495` — the tool accepting a qualified name and handing back an ambiguous
one.

Three measurements, in rising order of severity:

1. **Ambiguous rows.**  Rows whose locus names more than one theory, so
   pasting it back is a coin flip.
2. **Shadowed theories.**  `graph._sections_by_theory` is a last-wins
   `{name: section}` map, so every earlier section sharing a name is
   unreachable through it.
3. **Wrong-file rows.**  `cmd_callers` used to look its hit's section up in
   that map to fill the owner column and the `-U` context lines, so a hit in a
   shadowed theory read its OWNER and its CONTEXT out of a different file.
   That is a wrong attribution, not an ambiguous label — which is what
   reclassified this item from cosmetic threading.  Column 3 deliberately
   re-runs the OLD lookup and stays a witness of the defect's size; it is not
   a check on today's output, which takes the section straight from the hit.

Before, over the AFP (9,910 theories, 461 shared names covering 1,219
sections, 758 shadowed).  Every `ambiguous` count is now 0:

    verb                   rows    ambiguous   was wrong
    sorry                     6            4         n/a
    grep obtain           73296        10945         n/a
    callers mono            232           37          18
    callers assms        161426        16673        9239
    callers distinct        284            7           2
    callers wf              307           59          59
    methods induct        24288         4461         n/a

    python scripts/probe_disambig_loci.py [ROOT] [NAME ...]

ROOT defaults to the AFP checkout; NAME to a few heavily-cited names.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph  # noqa: E402
from isabelle_query.render import file_locus, locus_labels  # noqa: E402

DEFAULT_NAMES = ["mono", "assms", "distinct", "wf"]


def main() -> int:
    argv = sys.argv[1:]
    root = Path(argv[0]).expanduser() if argv else (
        Path.home() / "repos" / "afp" / "thys")
    names = argv[1:] or DEFAULT_NAMES
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    labels = locus_labels(sections)
    print(f"{len(sections)} theories under {root}\n")

    # --- 1. how ambiguous is a bare theory name here at all? ---------------
    per_name: Counter[str] = Counter(s.theory for s in sections)
    collide = {n for n, c in per_name.items() if c > 1}
    ambiguous_secs = [s for s in sections if s.theory in collide]
    qualified = sum(1 for v in labels.values() if "/" in v)
    print(f"{len(collide)} theory names are used by more than one theory, "
          f"covering {len(ambiguous_secs)} of {len(sections)} sections")
    print(f"{qualified} labels are qualified\n")

    # --- 2. what the by-name index cannot reach ---------------------------
    by_theory = graph._sections_by_theory(sections)
    shadowed = [s for s in sections if by_theory.get(s.theory) is not s]
    print(f"_sections_by_theory reaches {len(by_theory)} of {len(sections)} "
          f"sections; {len(shadowed)} are shadowed by a later same-name "
          f"section\n")

    # --- 3. per-verb rows, and the ones read out of the wrong file --------
    print(f"{'verb':22} {'rows':>9} {'ambiguous':>10} {'was wrong':>11}")
    print(f"{'-' * 22} {'-' * 9:>9} {'-' * 10:>10} {'-' * 11:>11}")

    # grep / sorry: the locus is a FILE (`Examples.thy:12`), ambiguous in
    # exactly the way a theory name is.  Counted on what the verb PRINTS.
    for pat_src, verb in ((r"\bsorry\b", "sorry"), ("obtain", "grep obtain")):
        hits = cli._grep_sections(sections, re.compile(pat_src))
        if verb == "sorry":
            hits = [h for h in hits if h[4]]
        amb = sum(1 for h in hits if "/" not in file_locus(labels, h[0])
                  and h[0].stem in collide)
        print(f"{verb:22} {len(hits):9} {amb:10} {'n/a':>11}")

    # callers NAME: the one verb that also re-derived its section by name.
    #
    # "Wrong file" is decided without re-scanning and without guessing which
    # side of a collision a hit came from: the row prints the hit's own raw
    # line, so if the section the owner column was read from does not have
    # that text on that line, the row is self-inconsistent.  Provable from
    # the output alone, and the reason this reads `by_theory` explicitly —
    # it is reproducing what `cmd_callers` used to do.
    for name in names:
        hits = cli._find_callers(sections, name)
        amb = sum(1 for s, _ln, _tx in hits
                  if "/" not in labels.get(s.path, s.theory)
                  and s.theory in collide)
        wrong = 0
        for sec, ln, text in hits:
            was = by_theory.get(sec.theory)
            src = was.source() if was is not None else []
            if ln > len(src) or src[ln - 1].rstrip() != text:
                wrong += 1
        print(f"{'callers ' + name:22} {len(hits):9} {amb:10} {wrong:11}")

    # methods NAME: same locus, owner already resolved by the scanner.
    _counts, located = graph._scan_methods(sections, only="induct")
    amb = sum(1 for p, *_ in located
              if "/" not in labels.get(p, p.stem) and p.stem in collide)
    print(f"{'methods induct':22} {len(located):9} {amb:10} {'n/a':>11}")

    print("\nthe five most-shadowed names (rows here were unreachable "
          "through the by-name index):")
    for n, c in Counter(s.theory for s in shadowed).most_common(5):
        seen = sorted(labels[s.path] for s in sections if s.theory == n)
        print(f"  {n:24} {c} shadowed section(s), labels: {seen[:3]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
