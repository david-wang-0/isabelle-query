---
name: reuse-query-parser-for-tooling
description: "For corpus/tooling scans reuse query's own parser, don't roll a mini-parser"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

When a script needs to read `.thy` content (harvesting, corpus scans, metrics),
reuse query's own pipeline — `cli._parse_one(name, path)` for a single file
(entries, spans, prose/comment skipping), and the `width` primitives
(`_scan_steps`, `build_ctx`, `_analyze_statement`) for propositions and their
free/bound identifiers — instead of writing fresh regex for quoted spans / binder
scans.

**Why:** the user called this out directly ("reuse existing infrastructure rather
than roll yet another mini-parser"), and it echoes the standing rule that the top
failure mode here is re-implementing repo machinery. A second parser also drifts
from what the tool actually computes — the harvest sample should be *exactly* the
population the metric measures.

**How to apply:** a throwaway `"[^"]*"` regex both over-captured (multi-line
spans) and diverged from query's prose handling; switching to `cli._parse_one` +
`width._scan_steps` fixed both and matched the estimator's own view. See
[[fix-root-not-workaround]], [[query-driven-by-real-usage]].
