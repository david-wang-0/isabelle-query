---
name: speed-and-accuracy-over-correctness
description: "isabelle-query targets speed + good accuracy, not 100% correctness; don't keep flagging breakage risk"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
  modified: 2026-08-02T22:24:20.834Z
---

`isabelle-query` is young and expected to keep changing. The target is **speed
and good accuracy, not 100% correctness**. Do not keep raising breakage risk,
caveating that numbers will move, or framing a change as dangerous — say what
changed and move on.

**Why:** the user said so directly — *"This code was done in the last few weeks,
not years, and I am expecting lots of things to still be changing so no need to
keep stressing breakage — it is what it is."* Repeated risk-flagging reads as
hedging and slows the work down. It also produced a factual slip: I described
weeks-old code as having been "correct by luck for years."

**How to apply:**
- Report a change's measured effect once, plainly, and continue. One statement
  that entry counts move ~10% is enough; it does not need restating per commit.
- Don't infer code age or maturity from how settled it looks. Check `git log`
  before characterising history.
- This is about *narration*, not rigour. The verification habits are still
  wanted — diff the entry set ([[reuse-query-parser-for-tooling]]), check a new
  test can fail, measure on the AFP ([[afp-checkout-location]]). Do the checks;
  just don't editorialise about the risk they cover.
- Approximations are acceptable where they are cheap and nearly always right;
  perfect coverage of corner cases (auto2's `@proof`/`@qed`, exotic custom
  syntax) is explicitly not the bar. See [[isabelle-query-correctness-approach]]
  for what the bar *is* — Isabelle semantics, not bit-parity with old behaviour.
