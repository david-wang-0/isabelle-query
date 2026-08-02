---
name: authorship-credit
description: How to credit authorship on the query / isabelle-query project artifacts
metadata: 
  node_type: memory
  type: feedback
  originSessionId: ef4b3238-179f-4694-a1b1-cea8fbcab1b1
  modified: 2026-08-02T01:10:42.536Z
---

When crediting authorship on artifacts for the `query` / isabelle-query project (pyproject `authors`, README, acknowledgments, etc.), credit **"András Salamon, with Claude Opus 4.6, 4.7, 4.8, and 5"** — not just the human, and not a single model version.

**Why:** the work was developed collaboratively with Claude across those Opus versions over time; the user explicitly asked for this phrasing. The list grows as later models contribute — Opus 5 was added on 2026-08-01, when it shipped the issue-#2 fix and v0.5.1.

**How to apply:** put the human author in structured `authors` fields, and name the Claude Opus 4.6 / 4.7 / 4.8 / 5 collaboration in README "Authors"/acknowledgment prose. This is distinct from the per-commit `Co-Authored-By:` trailer, which still goes on individual commits and names **the model doing the work** (currently `Claude Opus 5 (1M context) <noreply@anthropic.com>`); CLAUDE.md carries the verbatim trailer, so bump it there too when the working model changes. See [[user-andras-salamon]].
