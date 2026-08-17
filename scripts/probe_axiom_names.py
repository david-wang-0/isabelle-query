#!/usr/bin/env python3
r"""Corpus probe: which `axiomatization` names get an entry?

`axiomatization` declares constants before its `where` and labelled axioms
after it:

    axiomatization
      f :: "nat \<Rightarrow> nat" and
      Cap :: "nat"
    where
      ax1: "f 0 = 0" and
      Upper: "f 1 = 1"

Two things used to lose names here, and the first hides the second:

  * `where` in column 0 matched `TOPLEVEL_RE` (`^[a-z]`) and *ended* the scan,
    so every labelled axiom after it was dropped -- whatever it was called;
  * the name pattern was `[a-z_]+`, so a name with a capital, a digit or a
    prime (`Upper`, `ax1`, `f'`) matched nothing even when reached.

This counts AXIOM entries corpus-wide so the two trees can be diffed.  Output
is deliberately stable and sorted: run it under the old tree and the new one
and compare, per CONTRIBUTING's A/B habit.

Only theories whose text contains `axiomatization` are parsed -- no other
theory can yield an AXIOM entry -- which is what keeps this a ~seconds probe
rather than a full-corpus parse.

Usage:  probe_axiom_names.py [N_ENTRIES] [--names]
"""
import os
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if not os.environ.get("PYTHONPATH"):
    sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
_args = sys.argv[1:]
SHOW_NAMES = "--names" in _args
if SHOW_NAMES:
    _args.remove("--names")
LIMIT = int(_args[0]) if _args else 10_000

tot: Counter = Counter()
names: list[str] = []
failures: list[str] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            text = thy_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "axiomatization" not in text:
            continue
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception as exc:  # noqa: BLE001
            # Counted and reported, never swallowed: a probe that drops a
            # theory it could not parse reports a smaller number as if it were
            # a smaller *phenomenon*.  A same-line entry collision once made
            # this scan raise on 66 theories, and a silent `continue` showed
            # that as "fewer axioms" rather than as a bug.
            tot["parse_failures"] += 1
            failures.append(f"  {thy_path.name}: {type(exc).__name__}: {exc}")
            continue
        found = [e for e in sec.entries if e.tag == "AXIOM"]
        if not found:
            continue
        tot["theories"] += 1
        tot["entries"] += len(found)
        for e in found:
            if e.name == "axiomatization":
                tot["umbrella"] += 1
                continue
            tot["named"] += 1
            # The classes the old `[a-z_]+` pattern could not spell.
            if not e.name[0].islower() and e.name[0] != "_":
                tot["initial_capital"] += 1
            if any(c.isdigit() for c in e.name):
                tot["has_digit"] += 1
            if "'" in e.name:
                tot["has_prime"] += 1
            if SHOW_NAMES:
                names.append(f"{sec.theory}:{e.thy_line}\t{e.name}")

print(f"entries scanned: up to {LIMIT}")
print(f"theories with an AXIOM entry: {tot['theories']}")
print(f"AXIOM entries total: {tot['entries']}  "
      f"(umbrella {tot['umbrella']} + named {tot['named']})")
print(f"  named with an initial capital: {tot['initial_capital']}")
print(f"  named containing a digit:      {tot['has_digit']}")
print(f"  named containing a prime:      {tot['has_prime']}")
if tot["parse_failures"]:
    print(f"\n!! theories that FAILED to parse: {tot['parse_failures']} "
          f"— the count above is not a measurement of the corpus")
    print("\n".join(failures[:10]))
if SHOW_NAMES:
    print("\nnames:")
    print("\n".join(sorted(names)))
