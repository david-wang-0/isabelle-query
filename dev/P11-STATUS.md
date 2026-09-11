# P11 — warm-query performance and optional MCP integration

Status: complete; production implementation, independent review and all planned
gates accepted. Measured on 2026-09-09 against baseline
`a60447e4f457ac804ffb27c9dc26fa592c6a311c`, using Isabelle2025-2.

The plan is `dev/P11-PLAN.md`. GPT-5.6 Sol (Codex), at Medium/High reasoning,
implemented the five workstreams; a separate GPT-6 Astra (Codex) agent reviewed
designs, source, probes and supplied execution evidence. The main GPT-6 Astra
session planned, coordinated and ran integration verification. No commits,
publication, real-user component registration, heap builds or jEdit restarts
were performed. The frozen Python reference and its tests were unchanged.

## Result

- The warm-server fingerprint uses one directory walk with unchanged file-map
  semantics, including ROOT/ROOTS and discovered external source paths.
- The call-graph word scan resolves direct and derived names in one regex pass,
  preserving direct-name precedence and the symbol/quoted/visibility filters.
- Reach memoization retains at most four weak list identities in LRU order.
  Dead-key values are released on a later cache access; the cap is by entry
  count, not bytes.
- Server retention uses a whole-index LRU, defaulting to 128 MiB of cached
  normalized source text measured as UTF-8 bytes, plus a 256-index metadata cap.
  This is neither an exact heap bound nor an RSS bound. Oversized root-addressed
  requests may run transiently without evicting unrelated admissible indexes;
  reusable opens refuse nonretentable roots. Zero disables idle retention.
  The existing 4,000-theory per-request admission cap remains independent.
  Explicit IDs expire; root-addressed clients recreate evicted indexes.
- Open/run/close share one lifecycle lock. Refresh stages results before
  publication; cleanup and accounting also cover failed requests. Explicit
  roots are validated through the CLI parser, and request-owned stdin can be
  denied without changing global process input.
- `pide_mcp_query` is an opt-in component exposing one generic `query` tool.
  It delegates to the same warm engine, validates input, resolves defaults
  lazily, and returns CLI exit/stdout/stderr. It does not hold a separate cache
  or clear process-owned indexes when one tool stops. Normal component chaining
  does not depend on MCP.
- Contributor guidance now describes immutable per-request namespace tables;
  README documents retention and optional MCP use.

## Measurements

These are focused measurements, not general end-to-end speedup claims. Both
source versions were compiled in isolated scratch homes. The graph benchmark
parses once before timing and times `reach=name`; the four-mode equality check
separately includes import closure. Fingerprints were sampled in alternating order.

| Operation | Baseline | P11 | Scope |
|---|---:|---:|---|
| HOL fingerprint | 15.071 ms | 8.594 ms | Median of 9; complete maps equal |
| HOL graph, derived names enabled | 4,056.864 ms | 3,140.792 ms | Median of 5 after 2 warmups; graph build only |
| Same graph allocation | 2,786,779,000 bytes | 2,225,579,744 bytes | Current-thread allocation; about 20.1% less |
| HOL graph, derived names disabled | 3,109.933 ms | 3,040.512 ms | Small timing movement; no robust speedup claim |
| Same graph allocation | 2,116,167,984 bytes | 2,135,543,096 bytes | About 0.9% more |
| Alternating HOL/Category3 reach lookups | 272.107 ms | 0.023 ms | One loop of 10 lookups after priming; avoids rebuilds |

HOL contains 1,451 parsed sections and 35,250,326 source characters in this
graph run. Complete caller/callee/name digests agree in all four combinations
of derived names on/off and visibility by name/import closure. The reach
measurement uses HOL and Category3 (28 sections), checks the same closure
counts, and consumes each lookup in constant time. Its very short hot-cache
duration does not justify a precise general speedup ratio.

The baseline macro benchmark also measured warm HOL summary at 63 ms (median
of 5) and freshness recheck at 13 ms (best of 5). These are context only;
there is no matched P11 macro comparison in this round.

## Verification

| Gate | Outcome |
|---|---|
| Original engine build without either MCP dependency | PASS; both MCP jar variables and classpath entries absent |
| Isolated engine and jEdit plugin build | PASS |
| Optional adapter build and service discovery | PASS |
| `p11graph.sh` | PASS; hand-computed fixtures, four complete graph digests and measurements |
| `p11serverprobe.sh` | PASS; 7 fingerprint checks and deliberate-failure check |
| `p11reachprobe.sh` | PASS; weak-identity/LRU/clear/concurrency checks and deliberate-failure check |
| `p11serverretention.sh` | PASS; 54 assertions and deliberate-failure check |
| `p11mcp-probe.sh` | PASS; 42 direct-handler checks and deliberate-failure check |
| `p7probe.sh` | PASS; 37 protocol checks and 91 shell checks; servers cleaned up |
| `p7cprobe.sh` | PASS; 45 checks |
| `p9probe.sh` | PASS; 153 checks |
| Full warm differential matrix | PASS; 2,149 cases: 1,968 clean, 181 pinned, 0 failing, 0 stale pins |

Production/component sources were independently compared byte-for-byte with
the isolated regression snapshot. Regression probes used the existing frozen
Python oracle at version 0.8.1. Local loopback server probes required sandbox
escalation; their scratch homes and server identities were isolated from the
real installation.

The retention gate exercises real cap-refusal cleanup of an existing index,
recreation, exact aggregate accounting, source growth/shrink, oversized and zero
budgets, expired IDs, pinned roots, post-refresh CLI failure without MRU
promotion, and bounded concurrent lifecycle calls. Worker-result staging and
its exception boundary were source-reviewed, not tested with an injected
post-parse worker exception. Expected parser failures return omitted sections.

MCP evidence covers optional compilation, service discovery and direct handler
behavior, including an open FIFO proving stdin denial does not wait for EOF.
It does not establish live PIDE-session initialization or MCP wire acceptance.
Both server and handler probes establish stable IDs and zero reparsing on warm
reuse; the handler also checks that the index use count advances.

Reproduce with the existing corpus environment and scratch-home conventions.
Set `CURRENT_USER_HOME` and `BASELINE_USER_HOME` to isolated, already-built
homes; the remaining uppercase path arguments below are placeholders:

```sh
export USER_HOME="${CURRENT_USER_HOME:?set an isolated current scratch home}"
dev/p11graph.sh "${BASELINE_USER_HOME:?set an isolated baseline scratch home}" "$CURRENT_USER_HOME" CORPUS
dev/p11serverprobe.sh CORPUS 9
dev/p11reachprobe.sh
USER_HOME="$BASELINE_USER_HOME" dev/p11reachprobe.sh --bench ROOT_A ROOT_B 5
USER_HOME="$CURRENT_USER_HOME" dev/p11reachprobe.sh --bench ROOT_A ROOT_B 5
dev/p11serverretention.sh
PIDE_MCP_COMPONENT=DEPENDENCY_ARCHIVE dev/p11mcp-probe.sh
dev/p7probe.sh
dev/p7cprobe.sh
dev/p9probe.sh
QUERY_ORACLE=ORACLE QUERY_DIFFTEST_WARM=1 dev/difftest.sh
```

Consult each runner's header for required isolated homes and baseline archive.
No full cold differential matrix, hosted CI, or live heap-backed MCP session is
claimed by these results.
