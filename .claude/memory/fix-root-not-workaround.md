---
name: fix-root-not-workaround
description: "When a dev-tooling glitch appears (e.g. a stale editable shim), fix the cause, don't route around it"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

When a piece of dev tooling misbehaves, fix the root cause rather than working around it in the moment. Concrete case this session: the installed `query` console-script shim was stale (it imported the old `isar_query` module name and crashed). The right fix is `pip install -e .` to regenerate the shim — NOT invoking the CLI via `python -c "from isabelle_query.cli import main; main()"` to dodge the broken shim.

**Why:** explicit correction — "Please don't work around such glitches; rather pip install -e to update the shim." A workaround leaves the real defect in place to bite again (and to mislead the next person); fixing the cause clears it for good.
**How to apply:** when a wrapper/shim/cache/generated artifact is stale or broken, regenerate it (reinstall, rebuild, clear the cache) instead of bypassing it. Pairs with [[bare-tool-invocation]]: keep the bare-name tools actually working so they can be used bare.
