---
name: reuse-infrastructure-not-reinvent
description: "the direction is NOT 'reduce regex' — regexps are often the right tool; it is 'don't reinvent infrastructure that already exists' (Isabelle's own tables offline; query's own primitives at runtime)"
metadata:
  node_type: memory
  type: feedback
---

Standing steer (user, 2026-07-23, correcting my earlier "reduce-regex" framing):
**the concern is reinventing existing infrastructure, not the use of regexps** —
regexps are frequently the right tool (keyword-table parsing, the runtime token
router, delimiter anchoring). Do not frame work as "get rid of regex."

**The archetype — the namespace extractor reinvents Isabelle's method table.**
`scripts/extract_isabelle_namespace.py` is generated from Isabelle's own
distribution (good — not hand-curated), but it **statically regex-scans `.thy` /
`.ML` source for registration *sites*** (`method_setup`, `Method.setup`,
`gen_induct_setup \<^binding>\<open>NAME\<close>`, `Attrib.setup`), i.e. it
*reconstructs* a table Isabelle already assembles at runtime. That reconstruction
is only as complete as our list of registration idioms — which is exactly why the
`gen_induct_setup` factory form silently dropped `induction`
([[namespace-extractor-misses-induction]]). A **running** Isabelle has the method
table fully populated regardless of registration form.

**Task A, sharpened:** replace only the **method/attribute code-site scan** with a
runtime enumeration — that is the part reinventing a table. **Keep** the
keyword-block regex (`_pure_block_keywords` reads a *declarative* table from
`Pure.thy` — enumerating a fixed list, not chasing code idioms; low risk, regex is
right there).

**FINAL DESIGN (2026-07-24, user decisions) — minimal-ship + session-exact.**
COMPLETE. The union-floor (further below) was compensating for resolving the
*wrong* table (a fixed HOL base): it re-imposes the all-distribution table on every
session, wrong for a session that builds only on Pure and defines its own `auto`.
Model: **(1)** ship a *minimal* committed table = the **Pure** dump (37 methods /
101 attributes; keywords unchanged 222) — `extract_isabelle_namespace.py` now dumps
`_namespace_resolve.dump("Pure")` for methods/attributes (RETIRED the `.thy`/`.ML`
registration-site source scan; keeps the declarative keyword scan). **(2)** Table
verbs resolve **session-exact**: `resolve_project(sessions)` = union of the dumped
tables of the project's *built* session heaps (each self-complete with its deps —
no HOL base injected), over the Pure floor. Nominal session → its own `eqvt`; Pure
session → its own `auto`. **(3)** Resolve **only** for the table verbs
(`callers`/`callees`/`unused`/`methods`/`shape`, gated by `_NAMESPACE_COMMANDS`);
the 99% (find/grep/enclosing/…) never resolve → no cache-miss dump for a `find`.
**(4)** Fallback: session heap unbuilt **or Isabelle absent** → the minimal Pure
table (user's Q2: minimal, no HOL assumption; census excluded from resolution =
reproducible Pure — pin any verb with `ISABELLE_QUERY_NAMESPACE=committed`).
**Warn** to stderr when on the fallback AND the project's sessions build on non-Pure
(SessionInfo.parent ≠ Pure) — then `auto`/`blast` may over-cite. **Tests:** the
8 HOL-method tests are conditional (`support.needs_hol_methods`) — bind the runtime
HOL table when Isabelle supplies it, else skip; verified both ways.
Commits `b63cbc6` (gating) → `ee234e4` (session-exact + warning) → `70b6dbf`
(ship Pure + conditional tests). **Cost:** the union-floor's non-regression-vs-
broad-committed no longer applies (committed IS minimal now); a no-Isabelle HOL
query over-cites unless the heap is built — accepted (it's a Python tool people run
against AFP without Isabelle; the warning flags it). Implementation in progress
this session; the paragraphs below are the prior (union-floor) design, kept for the
non-HOL over-citation *evidence* which still stands.

**CENSUS + two follow-up fixes (2026-07-24, later — SUPERSEDES "census → Pure").**
The earlier note that `shape census` stays on the committed Pure table is WRONG:
Pure lacks auto/blast/induct, collapsing the automation axis for ~70% of proofs.
A census is a *corpus* (many logics, no single session to resolve against) whose
output ships in `data/`, so it needs one **fixed, broad, reproducible** table.
Ship `_census_namespace.py` = the union of the HOL-family + **HOL-Eisbach** heaps
(201 methods / 381 attributes), generated via `extract_isabelle_namespace.py
--census` (reuses `resolve_project`); `cli._bind_census_namespace` binds it
unconditionally (independent of $ISABELLE_QUERY_NAMESPACE and of built heaps).
**Why a union is correct for census — measured, not assumed** (`scripts/
census_table_sensitivity.py`, 9,275 proofs / 7 built AFP entries): of the three
census axes that read the table, **fan-in never reads it** (`graph._cited_facts_
on_line` uses fixed role-sets — Δ=0), **automation is introducer-anchored** (a
match after by/apply/proof is a real method by construction — union only adds
correct recognitions), and **M1/M5b width** (`classify_identifier`, position-
blind) showed Δ=0. Union ≡ per-entry-exact on all three EXCEPT automation for
entries defining their *own* methods (CZH's Eisbach `cs_concl`/`cs_prems`): ~2.8%
of proofs, irreducible for any fixed table — only a per-base DAG would recover it.
Commits `ec98b72`→`6e08f0f`→`dce7fae`. TWO bugs surfaced and were fixed: **(1)**
the ML dump scoped to `List.last(Thy_Info.get_names())` — one terminal theory —
silently dropping methods registered in *sibling* theories (HOL-Eisbach's
`match`/`solves` live in the `Eisbach` theory, not the heap's `Complex_Main`);
now unions every loaded theory's space + folds the ML script's stat into the
cache fingerprint (`38cb79c`). **(2)** `dump()` never passed `-d`, so on a box
where the project/AFP is not a registered Isabelle *component*, session-exact
resolution silently fell back to Pure for EVERY non-distribution session (a
non-distribution session got 37 Pure methods, not its real 115) — and `resolve_project` mis-reported the
failure as `isabelle:<session>`, suppressing the warning. Thread the project ROOT
dirs (`SessionInfo.root_path.parent`) through to `dump(-d …)`, gate fold-in on an
actual dump (`e16e01a`). Register the AFP with `isabelle components -u ~/repos/afp`
(2025-2). NOT YET DONE: docs currency, version bump — the remaining release tasks.

**Status (2026-07-23): adoption refactor COMPLETE (union-floor).** Resolver is
`src/isabelle_query/_namespace_resolve.py` (promoted from `scripts/`; ML dump is
package data `_dump_namespace.ML`, ships in the wheel; subprocess has a timeout →
committed on hang). `graph` holds the reconfigurable tables
(`_PROOF_METHODS`/`_ATTRIBUTES`/`_KEYWORDS`/`_NON_CITATION`, committed defaults)
behind `graph.configure_namespace(m, a, kw)`; `commands` + `shape` read them
late-bound. `cli._configure_namespace(ns)` runs at dispatch (never import): base
`resolve_namespace("HOL")` for most verbs, `resolve_augmented_sessions(...)` for
project-scoped `shape` (not `census` — it ships data, stays reproducible), each
**unioned with committed**. `$ISABELLE_QUERY_NAMESPACE=committed` short-circuits to
committed. Tests never reach `main()` (all stop at `parse_args`) → suite stays
committed-deterministic (**564-green**) regardless of installed Isabelle. On-box:
cold dump 1.6 s / warm 0.3 ms (~5600×). Commits `745729f` (reconfigurable) →
`4377811` (promote) → `6f9c77e` (base wiring) → `7f48aca` (shape augmentation) →
`c7776f4` (harness --runtime) → `b16a0bf` (union-floor fix).

**KEY CORRECTION to last session's "swap is safe" (which was HOL-only).** Broadened
validation on a non-HOL slice (HOLCF-Prelude/Launchbury/Nominal2/CCS/Psi_Calculi,
121 sections) showed **replacing committed with base HOL over-cites on non-HOL
logics**: base HOL lacks `eqvt`/`Seq_induct`, so `[eqvt]` attribute uses become
phantom citations — **+269 in-edges to an `eqvt` lemma, +20 to `defined`**. The
committed table's cross-logic breadth is *protective coverage* for unbuilt logics,
not just over-collection. So the static scan is over-exclusion **on HOL** (the
old +3 enabled/invariant/lem) *and* base HOL is over-inclusion **on non-HOL** —
symmetric defects. Resolution: **union, never replace** — reject-set = committed ∪
resolved (∪ built-session tables for shape). Re-validated: union-floor call graph
is byte-identical to committed on both the non-HOL slice (2542 names/8238 edges,
0 lost/gained, `eqvt` re-excluded) and a pure-HOL slice (49 sec, identical). So the
runtime table's real wins are: broader **attributes** (110→364), **shape
augmentation** of built-session methods (HOL 115/314 ∪ HOL-Analysis = 125/351:
`measurable`, `norm`, `bounded_linear`), and auto-pickup of new methods on upgrade
— NOT narrowing (that's what over-cites). Cost: the +3 HOL recovery is **given up**
(it required narrowing). **Step 4 (regen committed to narrow 115/314) is ABANDONED**
— committed stays broad as the safe fallback. Base HOL = 115 methods / 314
attributes; enumerate via `isabelle ML_process -l HOL -r -f` (2025-2 has **no
`process` tool**). Prototype commits `8f8c450`→`1068ec9`. Harness `--runtime` flag
does the committed-vs-runtime straddle in-process.

**Possible future (opt-in, NOT default):** a `runtime-strict` mode that *replaces*
committed (recovering +3) — only correct for a user who builds every logic heap so
augmentation always covers the loaded logic. Deferred; union-floor is the safe
default.

**Governing constraint (REVISED 2026-07-23, user decision): purity is warm-path,
not absolute.** The old rule was "no Isabelle process in the runtime, only in
`scripts/`." The adoption refactor loosened it, by explicit user choice (the
"auto-dump on cache-miss" purity model): the runtime `src/` **may** spawn Isabelle
— but *only* on the namespace cache-miss path, **never at import, never on the
warm path** (cache hit = file read), **never a build** (the no-build heap guard
stands), and never for the parse itself (`.thy` scanning stays pure-Python — the
~1–2 min whole-AFP value prop is untouched; the dump enumerates the method table,
it does not parse the corpus). No Isabelle installed → committed fallback. So the
invariant is now "import + warm path spawn nothing," not "the runtime never spawns
Isabelle." The generated `_isabelle_namespace.py` stays the committed *fallback*
(and the keyword source of truth — keywords are never dumped). The inward form of
the same principle — reuse query's OWN primitives instead of re-rolling them — is
[[reuse-query-parser-for-tooling]]; the balancer consolidation (one
`parsing._balanced_end` behind the paren/cartouche façades and the induction arg
lexer, commits b76bb7f/d59ed74) is a done instance.

**Why:** re-deriving a table someone else owns is brittle at the derivation seams
(a missed registration form = a silently missing method), whereas the owner's
table is complete by construction.

**How to apply:** before hand-rolling a scan of Isabelle source, ask whether
Isabelle (offline) or query's own layer already holds the answer assembled; if so,
consume it. Regex stays wherever it reads a fixed/declarative form directly.
