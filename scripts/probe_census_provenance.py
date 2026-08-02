#!/usr/bin/env python3
"""Probe: which heap in the census union contributes a given name?

The census table is the union of several built heaps, so a name that shadows
an ordinary fact name (`foo` shadows `lemma foo`) came from ONE of them.  This
attributes each queried name to the heaps that carry it, which is the
difference between "the table was harvested from junk" and "the table is
right, and the wrong table is being used".

Usage:  probe_census_provenance.py [NAME ...]
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import _namespace_resolve as nsr  # noqa: E402
from isabelle_query import _census_namespace as census  # noqa: E402
from isabelle_query import _isabelle_namespace as pure  # noqa: E402

# The union documented in _census_namespace's provenance block.
SESSIONS = ["HOL", "HOL-Library", "HOL-Analysis", "HOL-Eisbach",
            "HOL-Decision_Procs"]
NAMES = sys.argv[1:] or ["foo", "foo_method1", "foo_method3", "bar", "baz"]

tables = {"Pure(shipped)": {"methods": set(pure.PROOF_METHODS),
                            "attributes": set(pure.ATTRIBUTES)}}
for s in SESSIONS:
    r = nsr.resolve_namespace(s)
    tables[s] = {"methods": set(r["methods"]), "attributes": set(r["attributes"])}
    print(f"resolved {s:<22} methods={len(r['methods']):>4} "
          f"attributes={len(r['attributes']):>4}")
print(f"{'census(union)':<31} methods={len(census.PROOF_METHODS):>4} "
      f"attributes={len(census.ATTRIBUTES):>4}")
print()

for n in NAMES:
    carriers = [s for s, t in tables.items()
                if n in t["methods"] or n in t["attributes"]]
    in_census = n in census.PROOF_METHODS or n in census.ATTRIBUTES
    print(f"{n:<14} census={str(in_census):<6} carriers={carriers or '-'}")
