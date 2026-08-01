---
name: memory-tracked-in-repo
description: Agent memory lives in-repo at .claude/memory (symlinked from the system path) and is version-controlled
metadata:
  node_type: memory
  type: project
---

For the isabelle-query (`~/projects/query`) checkout, the agent memory directory
was moved **into the repo** at `.claude/memory/` and the system-wide path
(under `~/.claude/projects/…/memory`) is now a **symlink** to
it, so the live memory system reads/writes the same files transparently.
`.gitignore` carves it out of the ignored `.claude/` (`.claude/*` +
`!.claude/memory/`); `settings.local.json` stays ignored.

**Why:** the user asked to version-control memories alongside the code (2026-07-21),
to track how the agent's understanding evolves.

**How to apply:** writing or editing a memory here produces a committable change —
after updating memory + `MEMORY.md`, `git add .claude/memory` and commit it (own
`chore(memory):` commit, per [[small-frequent-commits]]). Memory content is now
public in the repo history — keep it about work, not anything sensitive.
