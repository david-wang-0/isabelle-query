---
name: prefer-head-over-sed
description: "To view file lines from the shell, use `head -N` not `sed -n '1,Np'` (sed is now permission-gated); better, use the Read tool"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

When viewing the first N lines of a file from a Bash command, use `head -N file` — not `sed -n '1,Np' file`, which is now permission-gated in this environment. Best of all, prefer the dedicated Read tool over shelling out at all.

**Why:** explicit correction — "Instead of `sed -n '1,<n>p'` which for some reason now is permission gated, please use `head -40`." A gated command stalls on a permission prompt; `head` doesn't.
**How to apply:** reach for the Read tool to inspect files; if a quick shell peek is genuinely warranted, `head`/`tail` over `sed -n`. Pairs with [[bare-tool-invocation]] as a shell-hygiene preference.
