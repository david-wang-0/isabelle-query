---
name: authorship-credit
description: How to credit authorship on the query / isabelle-query project artifacts
metadata: 
  node_type: memory
  type: feedback
  originSessionId: ef4b3238-179f-4694-a1b1-cea8fbcab1b1
---

When crediting authorship on artifacts for the `query` / isabelle-query project (pyproject `authors`, README, acknowledgments, etc.), credit **"András Salamon, with Claude Opus 4.6, 4.7, and 4.8"** — not just the human, and not a single model version.

**Why:** the work was developed collaboratively with Claude across those Opus versions over time; the user explicitly asked for this phrasing.

**How to apply:** put the human author in structured `authors` fields, and name the Claude Opus 4.6 / 4.7 / 4.8 collaboration in README "Authors"/acknowledgment prose. This is distinct from the per-commit `Co-Authored-By: Claude Opus 4.8` trailer, which still goes on individual commits. See [[user-andras-salamon]].
