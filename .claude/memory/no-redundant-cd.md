---
name: no-redundant-cd
description: "Don't cd into the working directory in Bash commands — it trips the permission gate"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: ef4b3238-179f-4694-a1b1-cea8fbcab1b1
---

Do not prefix Bash commands with `cd ~/projects/query` (or `cd` to any already-current dir). The session already starts in the project root, and a redundant `cd` hits the permission gate. Run commands directly. Same spirit as avoiding `git -C` — use plain commands from the existing cwd.

**Why:** the cwd is already correct; the `cd` adds nothing but a permission prompt.
**How to apply:** write `python -m unittest ...` not `cd ~/projects/query && python -m unittest ...`. Only `cd` when genuinely moving to a *different* directory the command needs.
