#!/usr/bin/env python3
"""Probe: do the issue-#2 tests actually discriminate?

A regression test that passes both before and after a fix is worthless.  This
neutralises the three inputs the fix extends — the non-Isar ranges, the ML span
boundaries, and the uppercase command anchor — restoring 0.5.0 behaviour, then
runs `tests/test_nonisar_regions.py` against it.

Every test asserting the ABSENCE of a phantom edge or a truncated span should
FAIL here; the `KeptLive` guards should still PASS (they assert behaviour the
fix must not change).  Anything in the first group that passes here is not
testing the fix.
"""
import os
import re
import sys
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))
sys.path.insert(0, str(_ROOT / "tests"))

from isabelle_query import parsing  # noqa: E402

# --- restore 0.5.0 behaviour ------------------------------------------------
parsing.extract_nonisar_ranges = lambda lines: []
parsing._SPAN_BOUNDARY_COMMANDS = frozenset({
    "begin", "end", "instance", "instantiation", "interpretation",
    "sublocale", "locale", "context", "declare", "lemmas", "notation",
    "no_notation", "syntax", "no_syntax", "translations",
    "code_printing", "export_code", "code_datatype", "code_reflect",
    "typedecl", "typedef", "consts", "print_translation",
})
parsing._LEADING_CMD_RE = re.compile(r"^([a-z][a-z_0-9]*)")

import test_nonisar_regions as target  # noqa: E402

def flatten(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from flatten(item)
        else:
            yield item


suite = unittest.defaultTestLoader.loadTestsFromModule(target)
all_names = sorted(str(t).split()[0] for t in flatten(suite))
result = unittest.TextTestRunner(verbosity=0,
                                 stream=open(os.devnull, "w")).run(suite)

broke = {str(t).split()[0] for t, _ in result.failures + result.errors}
print(f"ran {result.testsRun} tests against pre-fix behaviour\n")
print(f"FAIL/ERROR ({len(broke)}) — these discriminate:")
for name in sorted(broke):
    print(f"  x {name}")
kept = [n for n in all_names if n not in broke]
print(f"\nPASSED ({len(kept)}) — guards, or not discriminating:")
for name in kept:
    print(f"  . {name}")
