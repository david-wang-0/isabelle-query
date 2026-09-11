# P11 — warm-query performance and integration

Status: complete. Independent Astra review accepted the implementation and
evidence; the full warm differential matrix passed 2,149 cases (1,968 clean,
181 documented divergences, zero failures and zero stale pins). Measurements,
focused gates and verification limits are recorded in `dev/P11-STATUS.md`.

Scope: six findings from the performance review. Sol Medium/High implementers
own disjoint slices; Astra High reviews designs and final changes. The main
session owns coordination, integration, and acceptance. No commits, publication,
real-user component registration, heap builds, or jEdit restarts in this round.
The Python reference and its tests remain frozen.

## Execution and verification

Read CONTRIBUTING.md and CLAUDE.md. Claim assigned files before editing; do not
revert peers' work. Source writes happen before the shared build/test window.
No worker starts shared builds or differential runs until the orchestrator
freezes source edits and grants the build lane. Independent scratch experiments
must not modify the real installation or other workers' output directories.
Use the scratch Isabelle user home and corpora from `.dev/corpora.env`.
Reports go under `.dev/p11/`; shared prose uses portable paths only.

Done means a reviewed implementation, relevant passing harnesses, measured
performance or bounded-cache evidence, and accurate documentation. A failed
measurement is evidence to refine or reject an optimization, not to claim a
speedup. Preserve output, error, freshness and source-visibility contracts.

## S1 — fingerprint and server retention (Sol High)

Ownership: `query_base/src/server.scala`, the host hooks in `cli.scala`, and
new `dev/p11server*` probes.
Affected sites: fingerprint at line 195; registry at 324; request lifecycle at
388; protocol open/close paths below it.

First combine the `.thy` and ROOT/ROOTS predicates in one `Discovery.walk`.
Keep external discovered files, deduplication, mtime/size, symlink behavior,
and per-request cap checks unchanged. Compare fingerprints against the old
algorithm on nested roots and external files; measure warm fingerprint time.

Then propose a concrete whole-index retention policy before implementing it.
Bound aggregate retained indexes with a configurable source-byte-weighted LRU;
do not describe source-byte accounting as an RSS or exact heap limit. Keep the
existing per-request theory admission cap. Specify default, zero/invalid values,
oversized roots, query_open IDs, automatic client reopening, active-use safety,
lock ordering, failed refreshes, and accounting after edits. Eviction must not
change a running query's answer. Prefer whole-index eviction over a section LRU.
The reviewed policy uses `ISABELLE_QUERY_SERVER_CACHE_MB` (128 MiB by default;
zero disables idle retention) plus a 256-index metadata cap. Charge cached UTF-8
byte lengths of the normalized source strings actually retained, not later disk
stats. This is a source-content proxy, not heap/RSS accounting. Oversized
root-addressed runs may be transient; reusable opens refuse nonretentable roots.
IDs expire on eviction. Open/run/close share a lifecycle lock; refresh commits
transactionally, and failure cleanup preserves the bound. The explicit-root
validator runs after normal CLI parsing and before dispatch, while preserving
help/version and ordinary CLI errors. No post-run provider-use requirement.
The orchestrator and Astra reviewed this design before implementation.
Gate: p7probe plus focused deterministic retention/freshness probes and measured
alternating-root behavior. Document the runtime setting after its design lands.
Expected parse/read failures return an omitted section. Transactional worker
publication is source-reviewed rather than exercised through a synthetic worker
exception; no production injection hook is needed. Runtime gates cover real
refresh refusal on a previously healthy index, post-refresh CLI failure,
aggregate accounting, recreation, and bounded concurrent open/run/close calls.

## S2 — graph word scan (Sol Medium)

Ownership: `query_base/src/usage_graph.scala`, new `dev/p11graph*` probes.
Affected site: build_call_graph word and derived-key passes, lines 543–551.
Walk WORD_RE once, adding direct candidates and derived aliases without a
per-line temporary set. Keep symbol/quoted passes, derived collision precedence,
namespace filtering, reach visibility and definition-site exclusion identical.
Preserve graph/output ordering where observable. Cover derived on/off, direct
name collisions, repeated tokens, symbolic names and shadowed methods. Compare
baseline/new graph equality and benchmark throughput/allocation on real or
representative entry-dense input. Gate: difftest, p7cprobe, p9probe.

## S3 — multi-project reach memo (Sol High)

Ownership: `query_base/src/reach.scala`, new `dev/p11reach*` probes.
Affected site: closure memo, lines 321–341.
Replace the single weak-identity slot with a small bounded weak-identity LRU
(four entries initially). Retain identity rather than structural list equality;
the value must not retain the section-list key. Clean dead keys on access,
maintain thread safety, and make clear_cache clear all entries. Do not change
closure semantics or introduce namespace state. Pin A/B/A reuse, distinct-but-
equal list identities, eviction beyond capacity, and explicit clearing.
Gate: p7cprobe, p9probe and graph differential coverage. Measure alternating
projects against the old one-slot memo; record retained-memory tradeoffs.

## S4 — current contributor guidance (Sol Medium)

Ownership: `CONTRIBUTING.md`, later `todo.md` and `README.md` integration edits.
Replace the obsolete resident-host rebinding rule at line 294 with the immutable
Namespace.Table contract from P10 and the actual reasons for host serialization.
Check nearby current harness counts against existing P10 evidence. Historical
phase documents remain history. Wait for S1/S5 results before documenting their
settings/install instructions or updating open-work entries; do not mark a task
complete before its acceptance evidence exists.

## S5 — opt-in PIDE MCP component (Sol High)

Ownership: new `pide_mcp_query/`, new `dev/p11mcp*` probes.
Follow `[pide-mcp-tools]` in todo.md (line 93), now unblocked by P10.
Inspect the installed PIDE MCP extension API and propose exact component source,
service registration, CLI argv schema, session-root selection, error mapping,
and cache lifecycle before implementation. One generic query tool delegates to
CLI.run_result. Use immutable per-request namespace values and a warm in-process
index; prefer reusing the engine's existing index contract over copied policy.
No dependency from root etc/components: absent MCP installation must leave the
ordinary component build working. Do not change the external MCP repository.
Reuse the process-owned bounded registry; a tool stop must not clear other
callers' caches. Session-directory defaults stay lazy so explicit roots and help
work without them. Deny stdin at the CLI consumption seam through a request-local
provider, including range-qualified stdin paths; never replace global System.in.
Omit optional tool annotations in this first generic adapter; its description
states the actual source-query and input behavior.
The orchestrator and Astra review the proposal before implementation.
Gate: compile with the optional dependency in an isolated scratch home, direct
handle calls against hand-computed fixtures without heaps, root override and
error/empty cases, warm reuse and edits, plus ordinary component build with no
optional component registered. No claims of full live MCP acceptance from a
direct handler probe alone.

## Review and integration

Astra first reviews S1 retention and S5 adapter designs, then changed files and
probe evidence. Implementers remain available for fixes until accepted. The
orchestrator runs one stable combined build, required probe families and full
differential matrix, records outcomes, and updates this status. Performance
numbers must identify baseline/new, corpus, repetitions and measurement scope.
