#!/usr/bin/env python3
r"""Corpus probe: the selector constants a `record` declares [record-fields].

    record ('n,'p,'ba) flowgraph_rec =
      edges :: "('n,'p,'ba) edge set"
      main  :: "'p"

`edges` and `main` are constants, cited wherever the record is used, and until
[record-fields] they were indexed nowhere — so `find edges` missed, `show edges`
said "No entries matching", and `callers edges` reported the record's own field
line as a caller of it.

Two things to measure, and the second is the one that decides the shape:

  * **Recall** — how many records and fields there are, and whether the scan
    finds fields in all of them.  A record that yields *zero* fields is an
    extraction failure, not a record without fields; those are printed.
  * **Precision** — record fields are short, generic words far more often than
    datatype constructors are (`main`, `init`, `entry`, `trans`, `acc`).  Every
    one becomes a citation node, so this counts the ones that (a) collide with
    an existing indexed entry, where the graph then cannot tell two declarations
    apart, or (b) are proof-method names, where the table already filters them.

Usage:  probe_record_fields.py [N_ENTRIES] [--show N]
"""
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if not os.environ.get("PYTHONPATH"):
    sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph, model  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
_args = sys.argv[1:]
SHOW = 0
if "--show" in _args:
    i = _args.index("--show")
    SHOW = int(_args[i + 1])
    del _args[i:i + 2]
LIMIT = int(_args[0]) if _args else 10_000

tot: Counter = Counter()
field_names: Counter = Counter()
empty: list[str] = []
samples: list[str] = []
# entry name -> the kinds of declaration that bind it, per session
collisions: Counter = Counter()
collide_samples: list[str] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    # Per entry, so a collision means "two declarations of this name that a
    # reader of THIS project would have to tell apart" — across the whole AFP
    # every short word collides with something, which measures nothing.
    declared: dict[str, str] = {}
    fields_here: dict[str, str] = {}
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception:  # noqa: BLE001
            tot["parse_failures"] += 1
            continue
        tot["theories"] += 1
        for e in sec.entries:
            declared.setdefault(e.name, e.tag)
            if e.tag != "RECORD":
                continue
            tot["records"] += 1
            fields = [n for n, kind in e.bindings if kind == "field"]
            tot["fields"] += len(fields)
            if not fields:
                tot["records_with_no_fields"] += 1
                if len(empty) < 12:
                    empty.append(f"  {sec.theory}:{e.thy_line} {e.name}")
            for n in fields:
                field_names[n] += 1
                fields_here.setdefault(n, f"{sec.theory}:{e.thy_line}")
                if len(n) <= model._DROP_NAMES_UPTO:
                    tot["below_short_name_floor"] += 1
                if n in graph._PROOF_METHODS:
                    tot["field_is_a_method_name"] += 1
            if len(samples) < SHOW:
                samples.append(f"  {sec.theory}:{e.thy_line} {e.name} -> "
                               f"{', '.join(fields[:8])}")
    for n, where in fields_here.items():
        if n in declared and declared[n] != "RECORD":
            collisions[declared[n]] += 1
            tot["collides_with_another_declaration"] += 1
            if len(collide_samples) < 12:
                collide_samples.append(
                    f"  {ent.name}: field {n!r} at {where} also declared as "
                    f"{declared[n]}")

print(f"entries scanned: up to {LIMIT}   theories: {tot['theories']:,}")
if tot["parse_failures"]:
    print(f"!! parse failures: {tot['parse_failures']} — not a measurement")
print(f"\nRECALL")
print(f"  records:                        {tot['records']:,}")
print(f"  fields declared:                {tot['fields']:,}")
print(f"  distinct field names:           {len(field_names):,}")
print(f"  records yielding NO field:      {tot['records_with_no_fields']} "
      f"(an extraction failure, not a record without fields)")
for e in empty:
    print(e)
print(f"\nPRECISION")
print(f"  field name collides with another declaration in the same entry: "
      f"{tot['collides_with_another_declaration']}")
for tag, c in collisions.most_common():
    print(f"    …with a {tag:10} {c}")
for s in collide_samples:
    print(s)
print(f"  field name is a proof method (filtered by the table): "
      f"{tot['field_is_a_method_name']}")
print(f"  field name below the short-name floor (never a citation node): "
      f"{tot['below_short_name_floor']}")
print(f"\ncommonest field names:")
for n, c in field_names.most_common(15):
    print(f"    {n:28} {c}")
if samples:
    print("\nsamples:")
    print("\n".join(samples))
