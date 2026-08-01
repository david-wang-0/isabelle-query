---
name: bare-tool-invocation
description: "Run pytest/pip/query by bare name — the project venv is already active; don't prefix with .venv/bin/"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

Invoke dev tools by their bare name: `pytest`, `pip`, `query` — not `.venv/bin/pytest`, `.venv/bin/python -m pytest`, or `.venv/bin/pip`. The project virtualenv is already on PATH for the session, so the bare name resolves to it.

**Why:** explicit corrections this session — "please use pytest directly" (rejecting `.venv/bin/python -m pytest`) and "just pip" (rejecting `.venv/bin/pip`). The full venv path is noise when the venv is active.
**How to apply:** write `pytest -q`, `pip install -e .`, `query grep ...`. Same env-hygiene spirit as [[no-redundant-cd]] — don't over-qualify what the environment already resolves.
