---
name: small-frequent-commits
description: "Commit cadence for this user — small, frequent, semantically coherent commits"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: ef4b3238-179f-4694-a1b1-cea8fbcab1b1
---

Make small, frequent, **semantically coherent** commits — don't batch a whole session's work into one large commit. When a change-set spans separable concerns (e.g. a feature plus a distinct correctness fix it surfaced), split them into separate commits, even when the changes live in the same file.

**Why:** the user explicitly asked for "small frequent commits going forward, ideally semantically coherent."

**How to apply:**
- Split by concern, not by file. Example from this project: the `_isa_word_pattern` substring/symbol over-match fix was committed separately (`fix:`) from the custom-keyword scanner feature (`feat:`) that surfaced it.
- To stage only some hunks of a file non-interactively (interactive `git add -p` / `-i` are unavailable in this harness): extract the target hunk from `git diff <file>` into a patch file and `git apply --cached --recount <patch>`, then commit.
- Order matters: land a prerequisite fix first if the feature's tests depend on it (the scanner's call-graph oracle parity needed the boundary fix).
- Commit when a coherent unit is done rather than accumulating; still only commit when asked, work on `main`, never push.

See [[isabelle-query-correctness-approach]] and [[user-andras-salamon]].
