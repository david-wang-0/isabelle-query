#!/usr/bin/env python3
"""Corpus probe: how often does a discharge step name a method we cannot?

`trivial_frac`'s denominator is "steps carrying an extracted method", which is a
*table-dependent* notion — unlike every other shape axis, whose denominators are
positional.  So a method the bound table does not carry does not merely go
unclassified: it leaves the denominator, and a proof with no recognised method at
all returns `None` — indistinguishable from a genuinely structural body.

Whether that matters is a question about size and distribution, not about API
taste, so this measures both under the DEFAULT table (the broad union):

  * `discharge`     — steps whose line carries a `by` / `apply` introducer.
    Positional and table-free: `by`/`apply` are Isar keywords.
  * `named`         — of those, the ones `_leading_method` could name.
  * `unrecognised`  — the difference.  A real method the table lacks: an
    entry-defined Eisbach combinator, a niche logic's tactic, or a method the
    line-anchored scan cannot see (wrapped onto a continuation line).
  * `None-proofs`   — proofs where `trivial_frac` is undefined, split by whether
    the proof actually discharges anything.  The second class is the misreport.

Reports the per-entry spread too: a uniform 1% is noise a consumer can ignore,
while the same 1% concentrated in a few entries is a bias correlated with proof
style — which is the shape of every measurement fault this tool has had.

Usage:  probe_method_coverage.py [N_ENTRIES]
"""
import os
import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if not os.environ.get("PYTHONPATH"):
    sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph, shape  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 40

# `by`/`apply` only — a discharge.  `proof` is a goal transformer and is left out
# on purpose: `proof (induct n)` opens a block, it does not close a goal.
_DISCHARGE_RE = re.compile(r"\b(?:by|apply)\b\s*\(?\s*([\w']+)")

n_entries = 0
tot = Counter()
unnamed_tokens: Counter = Counter()
per_entry: list[tuple[float, str, int, int]] = []

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
    e_discharge = e_unrec = 0

    for sec in secs:
        live = sec.live_source()
        for entry in sec.entries:
            pm = shape.analyze_proof(sec, entry)
            if pm is None:
                continue
            proof_discharge = 0
            for s in pm.steps:
                line = live[s.line - 1] if s.line - 1 < len(live) else ""
                m = _DISCHARGE_RE.search(line)
                if not m:
                    continue
                proof_discharge += 1
                e_discharge += 1
                tot["discharge"] += 1
                if s.method:
                    tot["named"] += 1
                else:
                    tot["unrecognised"] += 1
                    e_unrec += 1
                    unnamed_tokens[m.group(1)] += 1
            tot["proofs"] += 1
            if shape.trivial_frac(pm.steps) is None:
                tot["none_proofs"] += 1
                # The misreport: it DOES discharge, we just could not name how.
                if proof_discharge:
                    tot["none_but_discharges"] += 1

    if e_discharge:
        per_entry.append((100 * e_unrec / e_discharge, ent.name,
                          e_unrec, e_discharge))

d, n, u = tot["discharge"], tot["named"], tot["unrecognised"]
print(f"entries={n_entries}  proofs={tot['proofs']}  "
      f"table={len(graph._PROOF_METHODS)} methods (the default union)")
print(f"discharge steps (by/apply, positional): {d}")
print(f"  named by the table:   {n} ({100 * n / max(d, 1):.2f}%)")
print(f"  UNRECOGNISED:         {u} ({100 * u / max(d, 1):.2f}%)")
print(f"\nproofs with trivial_frac is None: {tot['none_proofs']} "
      f"({100 * tot['none_proofs'] / max(tot['proofs'], 1):.2f}%)")
print(f"  …of which actually discharge something: "
      f"{tot['none_but_discharges']} "
      f"({100 * tot['none_but_discharges'] / max(tot['proofs'], 1):.2f}% of "
      f"proofs) — these are the misreports")

print("\nper-entry unrecognised rate (worst 12 of "
      f"{len(per_entry)}):")
for pct, name, u_, d_ in sorted(per_entry, reverse=True)[:12]:
    print(f"  {pct:6.2f}%  {name:<38} {u_}/{d_}")
med = sorted(p for p, *_ in per_entry)
if med:
    print(f"  median entry: {med[len(med) // 2]:.2f}%")

print(f"\nunrecognised tokens after by/apply: {len(unnamed_tokens)} distinct")
for name, c in unnamed_tokens.most_common(20):
    print(f"  {name:<24} {c}")
# The tail is where a false positive would hide: if the introducer-position
# argument holds, even a once-seen token should look like a tactic name, not
# like a term fragment the regex tripped over.
singles = sorted(t for t, c in unnamed_tokens.items() if c == 1)
print(f"\n  …tail: {len(singles)} tokens seen exactly once")
for i in range(0, min(len(singles), 40), 4):
    print("    " + "  ".join(f"{t:<26}" for t in singles[i:i + 4]))
