---
name: upstream-dependency-testing
description: "isabelle-query depends on isabelle-layout only (NOT watchdog), uncapped; test each new layout/watchdog release explicitly rather than pinning defensively — expect to update query's side"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
  modified: 2026-08-19T21:15:10.099Z
---

`isabelle-query`'s only runtime dependency is **`isabelle-layout`** (`>=0.2.2`,
**no upper bound**). It does **not** use `isabelle-watchdog` at all — no import,
no declared dependency, not installed in the venv. The two are siblings: layout
was split out of `isabelle_query.common`, and watchdog was the case that
motivated the split (it declared no runtime deps and wrote its own broken ROOT
reader; watchdog 0.3.1 now declares `isabelle-layout>=0.2.2`).

When a new `isabelle-layout` or `isabelle-watchdog` release lands, **test against
it explicitly**. Do not add a defensive upper bound in anticipation. If something
breaks, the expected fix is to **update query's side**, not to pin.

**Why:** the version cap was dropped deliberately (2026-08-10). It never
protected anything — a downstream cap cannot stop an upstream publish, only
query's resolution of one — and it was standing in for the real exposure, which
was that `common.py` imported 24 layout names, **eight of them private**. No
version range makes a moved private name safe; it only makes it later. Layout
also moves fast: 0.1.1 → 0.2.0 → 0.2.2 in two days.

**That exposure is now zero** (2026-08-19, v0.7.0). `common.py` is deleted;
query imports **ten public layout names and no private ones**, at their real
call sites. `tests/test_layout_surface.py` enforces it — it walks every `.py`
in the repo with `ast` and fails on an import from a `_`-prefixed layout module
or of a `_`-prefixed name. So the uncapped dependency is now a considered
position (public API, which upstream must version) rather than a standing risk.
Keep it that way: a private import re-added is a version range needed again.
`git log --grep='common-shim'`.

**How to apply:** on an upstream release, `pip install --upgrade isabelle-layout`
in the venv **first** — testing the working tree instead of the published wheel
proves nothing about what users get — then run `pytest`,
`scripts/probe_discovery_closure.py` (expect `CLOSED` over 9,910 AFP theories)
and an entry-set count (57,064 over 120 AFP entries). `tests/test_layout_surface.py`
catches a name that *disappeared*; it cannot catch one that changed behaviour,
which is what the corpus probes are for. Bump the floor in `pyproject.toml` only
to a version actually **on PyPI** — twice now a floor has named a version that
existed only as a local install. Verify install resolution end to end with a
throwaway venv (`python3 -m venv .scratch-venv && .scratch-venv/bin/pip install .`),
not by reading metadata. Related: [[release-versioning-policy]],
[[fix-root-not-workaround]].
