---
name: afp-probe-sampling-is-alphabetical
description: "probes take sorted(AFP)[:N] — an alphabetical prefix, not a random sample; never quote its rate as a corpus rate"
metadata:
  node_type: memory
  type: feedback
---

Every corpus probe in `scripts/` scopes itself with
`sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]`. That is the first N
entries **alphabetically** — `Abstract-Rewriting`, `AODV`, `Auto2_*`,
`AutoCorres2` — not a sample. A rate measured that way describes those entries,
not the AFP.

**Why:** `probe_method_coverage.py` over 40 entries reported **27 distinct
unrecognised proof-method tokens, every one a real tactic, zero false
positives**, and I recommended a design change on that basis. The whole corpus
(957 entries) returned 311 tokens including `the`, `a`, `an`, `means`, `moving`,
`replacing` — English prose leaking from `txt` blocks. The sample did not
contain the entries that write documentation that way, so the *class* of
counter-example was absent, not merely rarer. It also had the reverse effect:
the sample's own worst case (`Auto2_Imperative_HOL`, 87%) made the problem look
commoner than it is — corpus median is 0.00%, with 776 of 950 entries at exactly
zero.

**How to apply:** use the default N for a quick shape check, and say "40 entries"
rather than a bare percentage when reporting it. Before any *decision* rests on
a rate — especially an absolute like "zero false positives" — re-run corpus-wide
(957 entries costs ~100s for a step-level probe, so there is no reason not to).
Treat an absolute from a prefix sample as a hypothesis. See
[[isabelle-query-correctness-approach]] and [[verify-afp-size-assumptions]] for
the same stance on cost rather than incidence.
