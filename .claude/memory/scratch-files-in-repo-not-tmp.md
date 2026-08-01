---
name: scratch-files-in-repo-not-tmp
description: "write scratch/temp files (e.g. git commit-message files for `git commit -F`) inside the repo working tree, not /tmp — /tmp is permission-gated in this environment"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: bd12fa7c-6231-4fea-b046-588e4903c07e
---

When I need a temporary file — most often a multi-paragraph git commit
message fed via `git commit -F` — write it **inside the repo working tree**
(then `rm` it after committing), not in `/tmp`. `/tmp` is permission-gated
here and tripping the gate interrupts the flow; the repo directory is not
gated.

**Why:** the tcsh shell makes inline multi-paragraph commit messages awkward
(the `Co-Authored-By: ... <noreply@anthropic.com>` trailer's angle brackets
read as shell redirection), so a message file + `git commit -F` is the clean
path — but the file has to live somewhere ungated. Same gate-avoidance
instinct as [[no-redundant-cd]].

**How to apply:** put the scratch file at a repo-local path (e.g.
`./.git-commit-msg.tmp` or the repo root), stage only the real files with
explicit paths (never `git add -A`, so the temp file can't sneak in), commit
with `-F`, then `rm` it. Relates to the commit mechanics in
[[small-frequent-commits]].
