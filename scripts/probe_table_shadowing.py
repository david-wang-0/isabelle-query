#!/usr/bin/env python3
"""Probe: how many real declared facts does each committed table shadow?

A declared entry whose name is in the bound method/attribute table is routed
out of the citation graph (`graph._NON_CITATION`), so `callers` and `unused`
lose it.  That is correct for a name like `simp`, which every proof mentions,
and wrong for a name that is only a method in some *other* session's test
theory.

Counts, over declared AFP entry names, how many are shadowed by each candidate
committed table — so the choice of fallback table is made on measured cost
rather than on an opinion about which names look like test artifacts.

Usage:  probe_table_shadowing.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query import _namespace_resolve as nsr  # noqa: E402
from isabelle_query import _census_namespace as census  # noqa: E402
from isabelle_query import _isabelle_namespace as pure  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 150

hol = nsr.resolve_namespace("HOL")
TABLES = {
    "Pure (shipped floor)": set(pure.PROOF_METHODS) | set(pure.ATTRIBUTES),
    "HOL (resolved)": set(hol["methods"]) | set(hol["attributes"]),
    "census union (current fallback)": (set(census.PROOF_METHODS)
                                        | set(census.ATTRIBUTES)),
}

names: Counter = Counter()
for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception:  # noqa: BLE001
            continue
        for e in sec.entries:
            if e.tag in cli._CITABLE_TAGS and e.name != "?":
                names[e.name] += 1

total = sum(names.values())
print(f"declared entries: {total:,} ({len(names):,} distinct names)\n")
for label, table in TABLES.items():
    hit = {n: c for n, c in names.items() if n in table}
    print(f"{label}")
    print(f"  distinct names shadowed: {len(hit):>5}   "
          f"declarations lost: {sum(hit.values()):>6} "
          f"({100 * sum(hit.values()) / max(total, 1):.3f}%)")
    print(f"  top: {', '.join(n for n, _ in Counter(hit).most_common(8)) or '-'}")

union_only = {n: c for n, c in names.items()
              if n in TABLES["census union (current fallback)"]
              and n not in TABLES["HOL (resolved)"]}
print(f"\nshadowed by the union but NOT by HOL "
      f"(the cost of using the union as the interactive fallback):")
print(f"  distinct names: {len(union_only)}   declarations: {sum(union_only.values())}")
for n, c in Counter(union_only).most_common(20):
    print(f"    {n:<28} {c}")
