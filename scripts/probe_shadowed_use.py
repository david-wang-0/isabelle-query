#!/usr/bin/env python3
"""Probe: for a shadowed name, which lines count as a USE?

`_shadowed_uses_on_line` decides whether a mention of a name that is also a
proof method (`simp`) is a real citation of an entry of that name, or the
method doing its job.  Prints the decision and both of its inputs per line, so
a wrong verdict can be attributed to the fact extractor or to the term scan.
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import graph  # noqa: E402

NAME = sys.argv[1] if len(sys.argv) > 1 else "simp"
LINES = [
    "  by simp",
    "  apply (auto simp: refl)",
    "  by (simp add: refl)",
    'lemma term_use: "simp = 0"',
    "  unfolding simp_def by simp",
    "  using simp by auto",
    "  by (rule simp)",
    "  declare foo [simp]",
]

print(f"name={NAME!r}   in _NON_CITATION: {NAME in graph._NON_CITATION}")
print(f"{'use?':<6} {'cited_facts':<34} line")
print("-" * 90)
for ln in LINES:
    cited, covered = graph._cited_facts_on_line(ln)
    use = graph._shadowed_uses_on_line(ln, {NAME})
    print(f"{str(bool(use)):<6} {str(sorted(cited)):<34} {ln!r}")
