# Benchmarks and test timing

P13 full run, **2026-09-10: all tiers passed after exact divergence audit**.
All **255 invocations in 69 series exited 0**, with stable repeated output and
no timeout. All 43 output comparisons agree except the documented D12/D15
oracle differences below. The raw harness exits 1 for those differences;
none was silently waived. The audit also rejected a deliberately added
unrelated field change.

## Method and environment

Linux 7.1.9-1-MANJARO x86_64; Core i9-13950HX, 32 logical CPUs, 62 GiB RAM.
Isabelle2025-2; Python query 0.8.1; Scala query **0.8.1-scala.0.2**.
Run started at 19:47 UTC, load averages 1.96, 2.97, 2.53, and took 49.4 minutes.
No competing builds, comparison matrix, host or GUI tests ran during timing.
The private copied candidate's 357 manifest hashes matched before and after.
Manifest SHA256: `6ef9d3d6b65d8a4ef82f71990460c8f29ac1f4f50534d062e7dae8fbe02d220c`.
Query jar SHA256: `e047f3b8217c6794652a35cb40cd849caebc0086a08bd05bcac565bbb2026d29`.

Corpora: AFP 2025-2 (10,336 theory files; 10,262 discovered theories) and distribution HOL.
Warm retention was **512 MiB explicitly configured; default is 128 MiB**,
with request limit 0. This bounds normalized source bytes, not heap or RSS;
compression does not change that admission budget.
Stock JVM options: `-Djava.awt.headless=true -Xms512m -Xmx4g -Xss16m`,
with generational ZGC. No diagnostic GC logging was enabled for timings.
The oracle imported the frozen Python source through `PYTHONPATH`, with bytecode
writes disabled; tiny process-start timings depend on that cache state.

Latency values are wall-clock **medians of three samples**, after one discarded
warmup per series. These are not cold filesystem-cache tests. Memory values are
single samples. Failed samples remain failures, not usable timings.

| Route | Included work |
|---|---|
| Oracle | Fresh Python process |
| Cold | `isabelle query --no-server`, including fresh JVM/process setup |
| Warm | Thin client using an already admitted index in the owned retained host |
| Declined | `--client-cold`: client decline plus cold execution |
| Auto fallback | Fresh absent explicit name: discovery, owned startup, query and teardown |

Warm host startup is excluded; auto startup/teardown are included. An owned
fallback lasts for one client invocation. Census declines the warm route and
runs cold, so its warm-column number is **not** retained-index analysis latency.
Embedded jEdit/PIDE discovery and GUI latency are not measured.

## Query latency

Milliseconds; `n/a` means no oracle verb. Equality is byte-for-byte stdout.

| Corpus | Invocation | Oracle | Cold | Warm | Stdout |
|---|---|---:|---:|---:|---|
| Tiny | `show fair_fenum` | 124 | 697 | 45 | = |
| Tiny | `summary` | 123 | 726 | 49 | = |
| Tiny | `callers mono` | 121 | 732 | 46 | = |
| Category3 | `callers comp_assoc` | 343 | 1155 | 148 | = |
| Category3 | `callers category_axioms` | 322 | 1171 | 84 | = |
| Category3 | `shape summary` | 955 | 1565 | 368 | = |
| HOL | `instances comm_monoid` | n/a | 6182 | 781 | cold/warm = |
| HOL | `codeqs rev` | n/a | 6271 | 738 | cold/warm = |
| HOL | `summary` | 4986 | 5349 | 73 | D15; cold/warm = |
| Whole AFP | `summary --by-session` | 37131 | 29094 | 236 | = |
| Whole AFP | `shape census` | 178831 | 172021 | 168735 | D12/D15; cold/warm = |
| Analysis | `summary` | 959 | 2067 | 100 | = |
| Analysis | `callers has_integral` | 1076 | 2196 | 113 | = |
| Analysis | `shape summary` | 6211 | 4688 | 2623 | = |
| AutoCorres2 | `callers refines` | 966 | 2059 | 215 | = |
| JinjaThreads | `summary` | 619 | 1678 | 96 | D15; cold/warm = |
| JinjaThreads | `callers wf_prog` | 758 | 1834 | 197 | = |

Tiny is Abstract_Completeness; Analysis is HOL/Analysis. Exact audit found only:
56 HOL summary label lines, one JinjaThreads summary label line, and 15,351
census theory labels (D15); among 306,525 census records, only
`Feuerbach.special` additionally differs in `proof_tokens`, Python 2,149 versus
Scala 2,151 (D12). Every other byte/field agrees. See [DIVERGENCES.md](DIVERGENCES.md).
HOL admission reported 1,451 theories, 78,279 entries and 1,468 fingerprinted
files: 4,367 ms build, 7 ms best-of-five recheck, zero reparses.

## Decline and owned auto fallback

| Decline invocation | Cold ms | Warm ms | Declined ms | Stdout |
|---|---:|---:|---:|---|
| `show fair_fenum` | 705 | 40 | 779 | = |
| `summary src-HOL` | 5344 | 68 | 5473 | = |
| `instances comm_monoid src-HOL` | 6200 | 784 | 6253 | = |

| Auto summary | Cold ms | Auto ms | Stdout / endpoint cleanup |
|---|---:|---:|---|
| tiny summary | 712 | 1201 | =; endpoint removed |
| HOL summary | 5380 | 5971 | =; endpoint removed |

## Memory: peak RSS and retained heap

RSS is per-invocation peak from `/usr/bin/time`, in MiB (rounded down as the
harness prints it); it includes nonheap/native/mapped memory. The explicit
memory-case deadline was **180 seconds**. All cases returned matching output
and exit 0, including whole-AFP summary at `-Xmx512m`.

| Cold invocation | Stock 4 GiB heap RSS | 512 MiB heap RSS |
|---|---:|---:|
| `summary src-HOL` | 3245 | 801 |
| `callers comp_assoc Category3` | 1004 | 500 |
| `summary --by-session whole-AFP` | 4018 | 904 |
| Python oracle: HOL summary | 171 | n/a |

The following **selected-source** heap ladder is separate, using single samples
and the same whole-AFP summary. The 512 MiB/4 GiB summary observations come from
the accepted direct candidate comparison; 1/2 GiB observations and all histograms
were then measured on the identical candidate.

| Heap | Summary s | Summary peak RSS MiB | Retained MiB, including Scala runner | Histogram peak RSS MiB |
|---|---:|---:|---:|---:|
| 512m | 130.25 | 910.9 | 452.1 | 953.1 |
| 1g | 32.44 | 1421.5 | 453.6 | 1482.9 |
| 2g | 29.49 | 2290.8 | 451.8 | 2369.2 |
| 4g | 29.44 | 3976.4 | 450.0 | 3239.3 |

Histograms use in-process `DiagnosticCommand.gcClassHistogram`, keep all sections
reachable through measurement, and **include the Scala runner/compiler** (G1).
They are not CLI peak-heap measurements. Each retained 10,262 sections with
293,394,611 normalized UTF-8 source bytes and 95,996,883 stored source-block bytes
(279.8 and 91.5 MiB). The prior runner-inclusive baseline was 647.6 MiB; the current
4 GiB observation is 450.0 MiB, about **30.5% less retained heap**.

The trade is visible: 512 MiB summary now succeeds, where the prior P12 capped
run timed out at 600 seconds, but takes about 130 seconds. Default-heap RSS still
approaches 4 GiB and does not universally improve (HOL RSS increased). Region
and offset arrays, normalized declaration text and other index objects remain.
Full-source consumers still allocate decoded views; per-hit jEdit decompression
cost and phase-by-phase allocation peaks were not measured. This does not claim
that every whole-AFP query or jEdit workload fits a 512 MiB heap.

A bounded, no-GC-diagnostics comparison recorded P12 / batched P13 / selected
four-worker P13 whole-AFP summary at 19.96 / 61.92 / 29.44 seconds, with identical
output. The fixed worker queue recovers 52% of batched latency but retains a
47% cold cost versus P12 in that single-sample comparison. In full-run medians,
whole-AFP cold summary moved from the earlier 21.43 seconds to 29.09 seconds;
warm summary stayed near 0.23 seconds. Earlier batched-run timings are withdrawn,
not part of this final gate.

## Reproduce privately

Use a frozen checkout and a matching verified component. Complete compilation
before measuring; run no competing builds, tests or comparison matrix.

```bash
(
: "${QUERY_TEST_AFP:?AFP thys directory}" "${QUERY_TEST_DISTRO:?distribution src directory}"
: "${QUERY_VERIFIED_COMPONENT:?verified query_base snapshot directory}"
work=$(mktemp -d "${TMPDIR:-/tmp}/query-bench.XXXXXXXX")
mkdir "$work/home"
cp -a "$QUERY_VERIFIED_COMPONENT" "$work/query_base"
export USER_HOME="$work/home"
isabelle components -u "$work/query_base"
isabelle scala_build
isabelle query --no-server -V
export BENCH_CLIENT="$work/query_base/lib/scripts/query_client.py"
export ISABELLE_QUERY_SERVER_CACHE_MB=512 RUNS=3 BENCH_MEMORY_TIMEOUT=180
export PYTHONPATH="$PWD/src" PYTHONDONTWRITEBYTECODE=1
unset ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_NO_CLIENT
isabelle getenv -b ISABELLE_TOOL_JAVA_OPTIONS >"$work/java-options.txt"
sha256sum "$work/query_base/lib/classes/isabelle_query.jar" "$BENCH_CLIENT" \
  dev/bench.sh dev/owned-server.sh >"$work/source-sha256.txt"
rc=0
bash dev/bench.sh all >"$work/bench.log" 2>&1 || rc=$?
printf 'benchmark exit: %s; retained artifacts: %s\n' "$rc" "$work"
)
```

Keep every sample/status and audit every mismatch; nonzero raw harness exit is
not automatically a known divergence. Preserve private owner logs but never
publish their credentials. This run stopped/reaped the owned launcher, left an
empty private server registry, restored heap settings and had no leftover workload.

Standalone retained profiling from the same frozen checkout:

```bash
P13_SOURCE_HISTOGRAM="${TMPDIR:-/tmp}/query-retained.txt" P13_SOURCE_HEAP=4g \
  bash dev/p13-source/run.sh --retain-repl "$QUERY_TEST_AFP"
```

The [probe](p13-source/probe.scala) owns and shuts down its worker pool, including
on failure. Its two-theory teardown smoke covers compiled/Scala-runner success
and post-parse I/O failure; the old-helper control reproduced the completed-run
hang. `--retain` uses the compiled runner; `--profile` measures source payloads
and exact reconstruction rather than total retained heap.

## Test command timing

The prior cached `make test` observation is **4.43 s**: **1,177 Scala passes**, **49 transport passes**, and **78 explicit nonpass dispositions**; no failures reported.
This is one cached test run, not a latency median. The compatibility guard reused evidence:
matrix-body identity and helper hashes were reviewed; **no fresh full oracle matrix ran**.

Agent attribution: GPT-6 Astra (Codex).
