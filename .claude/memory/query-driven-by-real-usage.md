---
name: query-driven-by-real-usage
description: isabelle-query now has an active upstream consumer (a formalization workflow); real usage drives feature priorities and raises the composability bar
metadata: 
  node_type: memory
  type: project
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

As of 2026-06-11, the upstream project that isabelle-query serves (the maintainer's formalization work — see [[user-andras-salamon]]) is exercising the `query` CLI in increasingly sophisticated, *composed* ways — e.g. piping `git show REF:FILE | query lines - A..B` to read a pre-migration proof, which is what motivated the `[stdin-path]` feature. András calls this "a great sign we are on the right track."

**Why:** feature requests now originate from concrete downstream pain points (the `[stdin-path]` todo literally records a workflow that silently failed), not speculation. That makes real usage the design signal: prioritise what an actual session hits, and hold ergonomics/composability (pipes, `-` sentinels, gate-free batch forms) to a higher bar than a toy would need.

**How to apply:** when weighing a feature, favour the one a real upstream workflow has stubbed its toe on. The consumer is still effectively András himself, so the alpha breaking-change latitude in [[release-versioning-policy]] still holds — but the "no consumers to protect" premise is starting to soften, so watch for the point where composed pipelines mean a CLI break has real downstream cost.
