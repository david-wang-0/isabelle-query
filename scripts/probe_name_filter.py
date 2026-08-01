#!/usr/bin/env python3
"""Probe: which method/attribute table does the CLI configure for a ROOT, and
does it shadow ordinary fact names?

Written while triaging issue #2's R1 fixture, whose lemma is named `foo`.
Run with a ROOT directory argument; prints the table the CLI ends up using
and whether candidate fixture names are shadowed by it.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from isabelle_query import cli, graph  # noqa: E402

CANDIDATES = ["foo", "bar", "baz", "zzz", "helper", "aux_fact", "my_fact"]

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
os.environ["ISABELLE_QUERY_ROOT"] = str(root)
sections = cli.load_index()

print(f"root={root}  sections={[s.theory for s in sections]}")
print(f"session(s)={sorted({s.session for s in sections})}")
print(f"active table: methods={len(graph._PROOF_METHODS)} "
      f"attributes={len(graph._ATTRIBUTES)}")
print(f"  'foo_method1' present: {'foo_method1' in graph._PROOF_METHODS}"
      "   <- marks the harvested census table")
print()
for n in CANDIDATES:
    print(f"  {n:<10} citation-name={cli._is_citation_name(n, cli._DROP_NAMES_UPTO)!s:<6} "
          f"method={n in graph._PROOF_METHODS!s:<6} attr={n in graph._ATTRIBUTES}")

print()
g = cli._build_call_graph(sections, cli._DROP_NAMES_UPTO, derived=True)
print(f"call-graph all_names: {sorted(g.all_names)}")
for n in sorted(g.all_names):
    print(f"  {n}: callers={sorted(g.callers.get(n, set()))}")
