# P12: reuse running query hosts and make Scala tests routine

## Tasks

- [x] Inspect current transports, host lifecycles, and bounded index retention.
- [x] Add an embedded query endpoint with host identity and cache controls.
- [x] Advertise it from jEdit and the optional PIDE MCP launcher.
- [x] Make the thin client discover compatible hosts before starting its dedicated server.
- [x] Verify attachment, fallback, lifecycle isolation, retention, and CLI behavior.
- [x] Obtain an independent Fable review and resolve findings.
- [x] **Final task: port the Python regression coverage to a fast Scala suite; gate the Python/Scala compatibility sweep on upstream-version changes.**

## Design

### Lifecycle follow-up

- [x] Tie a client-spawned fallback to the client's lifetime through an owned
  stdin pipe; leave reused servers running.
- [x] Recover once from a disappearing host before emitting output.
- [x] Verify normal exit, client death, ownership isolation, and reconnection.
- [x] Rerun every benchmark case on the verified implementation.
- [x] Replace obsolete benchmark results and shorten current documentation.

Verification: 49 transport tests, 13 private JVM lifecycle tests, 108 host
integration checks, and all 1,177 Scala regressions passed. Cached `make test`
took 4.43 seconds. The matrix harness now explicitly owns its server; its
invocation/expectation body remains byte-identical and no fresh compatibility
matrix is claimed. Fable's final immutable-snapshot review passed with notes.

The full benchmark attempted all 367 invocations: 366 succeeded; the capped
whole-AFP memory case timed out at 600 seconds. Cold/warm outputs agree, and
Python differences are fully explained by existing D12/D15. The private server
and registry were cleaned up. A memory-case deadline now prevents indefinite
stalls; its cleanup and continuation behavior passed isolated fixtures.
Current measurements and reproduction are in [BENCH.md](BENCH.md).

The follow-up changes automatic fallbacks from persistent daemons to
client-owned processes. A terminal client exits after its query; long-lived
jEdit/PIDE hosts and explicitly managed servers still retain indexes between
clients. The checkpoints below retain the preceding implementation's evidence;
the follow-up results above describe the final lifecycle.

Reuse Isabelle's authenticated loopback server protocol and private `servers.db`
registry. Embedded handlers expose only the five query commands and register
only their own row, without probing or pruning other servers. Cooperating
jEdit/PIDE JVMs host a query endpoint; no arbitrary JVM
injection or new prover session is involved. They advertise a reserved
process-specific name and host kind. An already-running JVM needs the updated
component loaded before it can advertise.

The client probes compatible advertised hosts with a bounded discovery budget,
then uses or starts its dedicated query server. Probe protocol and loaded
component identity before sending a query. Preserve explicit server selection.
Skip stale/incompatible shared hosts; never restart or shut down their JVMs.
Dedicated-server lifecycle actions must not silently target a discovered shared
host. Preserve caller cwd, roots, environment, output, and exit status.
Automatic discovery excludes unrelated registry names. An unverifiable default
endpoint may be bypassed by a uniquely named managed fallback; explicit server
selection remains exclusive. Never stop an unverifiable endpoint to recover.

jEdit advertises through plugin start/stop. The optional PIDE component supplies
a `pide_mcp` launcher which invokes the upstream Scala entry directly inside a
host lifecycle bracket, including zero-session servers. No upstream PIDE source
patch or tool-schema side effects are needed. The launcher must not redispatch
its own tool name, alter upstream arguments, or write to MCP stdout.

Indexes remain resident by default within the existing source-byte/entry limits.
`--client-cache on|off|clear` controls the selected host: off clears and disables
retention; on restores bounded retention; clear drops current indexes without
changing that policy. `--no-server` remains the one-shot escape hatch. Terminal
queries continue using saved source, including when the host is jEdit.

## Done criteria

- Real isolated socket tests prove reuse of the host PID and index, freshness,
  bounded fallback, wrong-version skipping, cache controls, and no shared-host
  restart on stale code or lifecycle commands.
- jEdit component compiles and plugin lifecycle is checked; a zero-session PIDE
  stdio process serves terminal queries concurrently and exits cleanly on EOF.
- Fable's review is recorded with reproducible evidence and all required fixes.
- Routine regression checks run in Scala without Python oracle execution or a
  JVM per case. Coverage mapping identifies Python-only cases and intentional
  differences. The compatibility job detects upstream-version changes, fails
  on unexplained differences, and records the checked version only on success.
- Existing work is preserved. No commits or live shared-process restarts.

## Final-task coverage inventory

The initial inventory contains 82 Python modules and 1,255 collected unittest
cases. Preserve parameterized/looped subcases as well as method-level coverage.

| Area | Cases | Intended Scala destination |
| --- | ---: | --- |
| Parsing, source views, spans | 437 | Parser regressions |
| Citations and graphs | 183 | Graph regressions and independent reference edges |
| Shape and census | 203 | Shape regressions |
| Queries, rendering, source locations | 224 | Query regressions |
| CLI, subprocess, stdin | 76 | In-process CLI tests plus process-only checks |
| Discovery and namespace | 99 | Discovery/namespace regressions |
| API and dependency contracts | 25 | Scala equivalents or explicit Python-only disposition |
| Optional corpus and performance | 8 | Separate integration/performance tier |

The differential matrix contains 307 distinct invocation IDs across seven
corpora (2,149 comparisons per mode). Its 181 existing pins comprise 178 D15
theory-name cases and three D8 pipe cases. The initial upstream Python version
is 0.8.1. Version-gate state must include source/dependency identity and must
not claim a newly successful compatibility run when one was skipped.

## Verification checkpoints

- Core endpoint and cache controls: 41 focused checks, plus the 54 existing
  retention checks and their deliberate-failure check.
- Shipping Python transport: 33 focused checks after Fable's findings.
- Real hosted/fallback integration: 68 checks with the narrowed endpoint,
  including PIDE stdio and headless invocation of the jEdit plugin lifecycle.
- Fable approved the feature after checking query-only dispatch, own-row
  registration, managed fallback recovery, lifecycle controls, and stdin isolation.
- Graph port: 183 passing cases, three explicit optional cases;
  all 186 original IDs accounted for and standalone runner exits successfully.
- Shape port: all 203 IDs pass after a scoped census load-failure fix; an added
  subcase checks that a failed session cannot claim a later session's source.
- Parser port: 448 passing cases and 14 explicit dispositions, all 462 IDs
  accounted for. Legacy-verbatim redaction is fixed; two original expected
  failures retain strict predicates, and unexpected passes fail the suite.
- CLI port: 343 passing cases and 61 explicit dispositions, all 404 IDs
  accounted for.
- Combined `make test`: 1,177 Scala passes, 78 separately reported dispositions,
  zero failures, and 33 passing transport tests. All 1,255 upstream IDs are
  accounted for. The first compilation run took 31.12 seconds; the cached run
  took 5.40 seconds on the development machine.
- Expected-failure handling passed 16 negative controls. The compatibility gate
  passed bounded failure/cleanup checks. Upstream remains 0.8.1, so the full
  compatibility matrix was skipped; the baseline identifies historical P11
  evidence rather than claiming a fresh sweep.
- Fable's final review found no confirmed defect in the migration or scoped
  production fixes. Review sampled translations and checked exact test-ID
  accounting; it was not an exhaustive manual comparison of every assertion.
- Updated component jars were built and the optional PIDE launcher registered.
  Existing jEdit/PIDE JVMs were not restarted; future starts load advertising.
