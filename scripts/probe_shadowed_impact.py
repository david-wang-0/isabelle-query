#!/usr/bin/env python3
"""Corpus probe: what do shadowed names do once they are back in the graph?

An entry whose name is also a proof method / attribute is now a citation node
whose edges are decided positionally.  The risk is the guard failing: if a
`by simp` were read as a use, a `definition simp` would collect an in-edge from
almost every proof in the session — so this reports, per shadowed name, how
many callers it actually gains, and flags any that look like an explosion.

Binds the broad census union deliberately: that is the table a HOL-base project
with no built heap gets, and the one that shadows the most names.

Usage:  probe_shadowed_impact.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph  # noqa: E402
from isabelle_query import _census_namespace as census  # noqa: E402
from isabelle_query import _isabelle_namespace as isa_ns  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120
EXPLOSION = 25  # callers for one shadowed name that would suggest a bad guard

graph.configure_namespace(census.PROOF_METHODS, census.ATTRIBUTES,
                          isa_ns.KEYWORDS)

recovered: Counter = Counter()   # shadowed name -> callers found
orphan: Counter = Counter()      # shadowed name -> declared but no caller
suspicious: list[tuple[int, str, str]] = []
n_entries = n_shadowed = 0

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    secs = []
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            secs.append(cli._parse_one(thy_path.stem, thy_path))
        except Exception:  # noqa: BLE001
            pass
    if not secs:
        continue
    n_entries += 1
    g = cli._build_call_graph(secs, derived=True)
    for name in g.all_names:
        if name not in graph._NON_CITATION:
            continue
        n_shadowed += 1
        n_callers = len(g.callers.get(name, ()))
        if n_callers:
            recovered[name] += n_callers
        else:
            orphan[name] += 1
        if n_callers >= EXPLOSION:
            suspicious.append((n_callers, ent.name, name))

print(f"entries={n_entries}   shadowed declared names={n_shadowed}")
print(f"  with >=1 caller recovered: {sum(recovered.values())} edges "
      f"over {len(recovered)} distinct names")
print(f"  with no caller (correctly not inflated): {sum(orphan.values())} "
      f"over {len(orphan)} distinct names")
print("\nmost-cited shadowed names (edges recovered):")
for name, c in recovered.most_common(15):
    print(f"  {name:<24} {c}")
print(f"\nnames with >={EXPLOSION} callers in ONE entry "
      f"(would suggest the method guard is leaking): {len(suspicious)}")
for c, ent, name in sorted(suspicious, reverse=True)[:15]:
    print(f"  {c:>5}  {name:<20} {ent}")
