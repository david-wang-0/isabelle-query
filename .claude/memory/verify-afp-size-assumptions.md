---
name: verify-afp-size-assumptions
description: "don't assume AFP theory size/shape; check afp-metrics.py — there are massive, entry-dense theories"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: ef4b3238-179f-4694-a1b1-cea8fbcab1b1
---

Before claiming a per-theory cost is "harmless on the AFP", verify against the
real distribution, not an average. The maintainer keeps an `afp-metrics.py` script
(run `afp-metrics.py --top 20`) which ranks
entries/sessions/**single .thy files** by line count and prints min/median/mean/p95/max.

Concrete tail (2026 snapshot): largest single .thy is `IsaGeoCoq/Tarski_Neutral.thy`
at ~44k lines; entry-size p95 ~21k lines, max ~120k. Line count and *entry* count
diverge sharply — `Tarski_Neutral` has 44k lines but only ~1,800 entries (long
proofs), while `SEC1v2_0_Test_Vectors` is ~6,700 short declarations. Parse cost
scaled with **entry count**, not lines.

**Why:** I wrote in tests/README that a parse-side super-linearity was "harmless,
theories are ~640 lines on average" — the average hid the tail, and I had the
wrong cost driver (lines vs entries). It was a real O(entries^2) already paid by
entry-dense files.

**How to apply:** when reasoning about corpus-scale cost, pull the actual
distribution from afp-metrics.py and identify the cost driver by measuring the
real worst-case file, not by extrapolating from a synthetic or an average. See
[[isabelle-query-correctness-approach]] for the same verify-against-reality stance.
