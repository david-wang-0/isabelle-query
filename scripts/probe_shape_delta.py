#!/usr/bin/env python3
"""Corpus probe: what does the `proof_line` fix move in the shape census?

`shape` skips an entry with no `proof_line` (`_scan_steps` returns early), so
2,513 of 40,361 AFP facts contributed no steps at all — silently, since a proof
that produces no records is indistinguishable from one that was not asked
about.  Recovering them adds proofs to the census and changes per-theory
aggregates, which is a real move in published numbers rather than a bug fix
with no consequences.

Run against the fixed tree, then against the previous one (checkout the file,
rerun, diff) — the counts printed here are what to compare.

Usage:  probe_shape_delta.py [N_ENTRIES]
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, shape  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 60
FACT_TAGS = {"LEMMA", "THEOREM", "COROLLARY"}

n_facts = n_with_proof = n_steps = n_nonempty = 0

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception:  # noqa: BLE001
            continue
        for e in sec.entries:
            if e.tag not in FACT_TAGS or e.thy_line <= 0:
                continue
            n_facts += 1
            if not e.proof_line:
                continue
            n_with_proof += 1
            try:
                steps = shape._scan_steps(sec, e)
            except Exception:  # noqa: BLE001
                continue
            n_steps += len(steps)
            if steps:
                n_nonempty += 1

print(f"facts={n_facts:,}  with a proof_line={n_with_proof:,} "
      f"({100.0 * n_with_proof / max(n_facts, 1):.2f}%)")
print(f"  steps in the census: {n_steps:,}")
print(f"  proofs contributing at least one step: {n_nonempty:,}")
