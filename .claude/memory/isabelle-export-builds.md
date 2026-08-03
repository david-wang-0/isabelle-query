---
name: isabelle-export-builds
description: "`isabelle export` triggers a build; read the session .db read-only with sqlite instead"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
  modified: 2026-08-03T00:48:37.227Z
---

`isabelle export` is **not** read-only. Despite reading like a query, it builds
the session first if anything is stale — invoking `isabelle export -l -d
~/repos/afp/thys <SESSION>` rebuilt an already-built AFP session (1:52 elapsed).
That violates the standing "never trigger an Isabelle build" constraint.

**Why:** the constraint is absolute in this project, and the trap is that the
command name suggests a read. Nothing in its `-l` (list) flag hints that a build
is a precondition.

**How to apply:**
- To read `export_theory` data, open the session database directly:
  `$ISABELLE_HOME_USER/heaps/<platform>/log/<SESSION>.db`, table
  `isabelle_exports`, via a `file:...?mode=ro` sqlite URI. A file read cannot
  build anything. A never-built session simply has no row — detect and stop.
- Export bodies are **Zstd**-compressed (magic `28 B5 2F FD`), not XZ. Python
  3.14 has `compression.zstd` in the stdlib, so this stays dependency-free.
- `scripts/probe_export_oracle.py` already does all of this; reuse it rather
  than re-deriving ([[reuse-query-parser-for-tooling]]).
- Generalise the caution: before running any `isabelle` subcommand here, check
  whether it has a build precondition. `isabelle getenv` / `components` are
  safe; anything session-scoped probably is not.
