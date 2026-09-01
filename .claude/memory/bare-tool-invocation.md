---
name: bare-tool-invocation
description: "Run pytest/pip/query by bare name — the project venv is already active; don't prefix with .venv/bin/"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
  modified: 2026-08-30T22:01:12.861Z
---

Invoke dev tools by their bare name: `pytest`, `pip`, `query` — not `.venv/bin/pytest`, `.venv/bin/python -m pytest`, or `.venv/bin/pip`. The project virtualenv is already on PATH for the session, so the bare name resolves to it.

**Why:** explicit corrections — "please use pytest directly" (rejecting `.venv/bin/python -m pytest`) and "just pip" (rejecting `.venv/bin/pip`). The full venv path is noise when the venv is active.
**How to apply:** write `pytest -q`, `pip install -e .`, `query grep ...`. Same env-hygiene spirit as [[no-redundant-cd]] — don't over-qualify what the environment already resolves.

**When bare names DON'T resolve** (seen 2026-08-30): if `which pytest` comes back empty and `python` is not found, the session was not launched from an activated venv. Bash tool calls inherit the environment Claude Code was started in, and shell state does not persist between calls — so `source .venv/bin/activate.csh` mid-session fixes the user's terminal and not the tool's. Don't try to patch it with a `settings.json` PATH entry; the user's stated preference is to relaunch Claude Code from an activated shell. Until then use `.venv/bin/python` / `.venv/bin/query` explicitly and say why, rather than silently falling back to system `python3` — which cannot import `isabelle_layout` and fails confusingly.
