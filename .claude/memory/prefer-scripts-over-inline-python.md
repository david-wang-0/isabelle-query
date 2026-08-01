---
name: prefer-scripts-over-inline-python
description: Write a script file instead of piping python3 -c / heredocs to bash
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

For ad-hoc verification/probing, write a script file and run it rather than
piping `python3 -c "..."` (or heredocs) into bash. On the isabelle-query repo,
put reusable probes in `scripts/` following the house convention (shebang +
raw docstring with `WHY THIS EXISTS` / `USAGE`); e.g. `scripts/probe_entry_spans.py`.

**Why:** the user asked for this explicitly — inline one-liners fight shell
quoting (Isabelle `\<...>` escaping especially), and a saved script can be
re-run later (e.g. to re-verify a parser fix after the next change). The
existing `scripts/` files (analyze_citation_names, profile_build, ...) are
committed, documented, reusable tools, not throwaways.

**How to apply:** when a check needs more than a trivial expression, create a
named script (in `scripts/` for parser/analysis probes), run it by path with
the active venv python, and keep it. See [[scratch-files-in-repo-not-tmp]]
(temp files live in the working tree, not /tmp) and [[bare-tool-invocation]].
