---
name: namespace-extractor-misses-induction
description: "the `induction` method registers via the Induct.gen_induct_setup factory, not Method.setup — the namespace extractor now matches that helper form; watch it on any regen"
metadata:
  node_type: memory
  type: project
---

`induction` (the modern goal-consuming structural-induction method, the commonest
in current Isar) is registered by the **factory helper**
`Theory.local_setup (Induct.gen_induct_setup \<^binding>\<open>induction\<close> ...)`
in `Tools/induction.ML` — **not** the direct `Method.setup \<^binding>\<open>...\<close>`
form. `scripts/extract_isabelle_namespace.py`'s method regex originally anchored
only on `Method.setup`/`method_setup`, so it silently dropped `induction`.
(`induct` uses the same factory but slipped into the namespace anyway via
`LCF/LCF.thy`'s incidental `method_setup induct`; `cases` uses the direct
`Method.setup` form. So `induction` was the sole casualty.)

**Fixed 2026-07-23 at the root** (not a workaround): the extractor's method ML
regex now also matches `gen_induct_setup \<^binding>\<open>NAME\<close>`, and the
namespace was regenerated (Isabelle2025-2, 243→244 methods, delta = `+induction`
only). An earlier interim commit (983cc9b) had curated `induction` back in at
`graph.PROOF_METHODS`; that workaround was **reverted** once the extractor fix
landed.

**Gotchas for the next regen.** (1) Point `--src` at the **2025-2** source
(`/Applications/Isabelle2025-2.app/src`)
— flat layout (`Tools/induct.ML`, not `src/Tools/`). Do
**not** point `--src` at `~/repos/isabelle` (that is HEAD, a different version).
(2) Other methods may register via similar factory helpers (`gen_cases_setup`
etc.); if a known method turns up missing, check for a `gen_*_setup`/`*.setup`
indirection the regex doesn't cover, and extend it — fix the extractor, never
hand-edit the generated file. Verify with
`_leading_method("by (induction x)") == "induction"`.

**Why:** without `induction`, `by (induction ...)` / `proof (induction ...)` steps
carried no `Step.method` — uncounted in the `methods` census and the
method_kinds/trivial reductions.
