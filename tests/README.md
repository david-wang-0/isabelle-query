# Tests

## Routine Scala validation

```sh
make test
dev/scala-tests.sh --suite parser   # parser, graph, shape, or cli
```

The default runs the Scala regression suite, the cheap upstream identity gate,
and `test_p12_transport.py` for the shipping Python client. It does not run the
frozen Python tests or the Python oracle when upstream is unchanged. Scala
compilation uses a copied `query_base`, a private Isabelle user home, and separate
engine/suite caches under `.dev/scala-tests` (override with `SCALA_TEST_CACHE`;
for example, export it once from `mktemp -d` for a reusable temporary cache).
A test-only change recompiles the
selected suite; a cache hit runs one JVM. No production jar, global component
registration, prover session, or live host is changed. Python 3.11+ orchestrates
builds and reads gate metadata only; Isabelle must be on `PATH`.

`scala/upstream-tests.json` preserves all 1,255 original unittest IDs and their
disjoint groups. Each `scala/coverage/GROUP.json` is exhaustive for that group.
`ported` rows must execute real `TestSupport.test(id)` assertions. Explicit
`python-only`, `optional`, `process`, and documented `known-difference` rows
call `disposition(id, status, reason)` and are counted separately, never as
passing ports. Known differences also require passing Scala assertions; an
unimplemented or failing port is not a known difference. Optional assertions
may execute when their suite enables them, but keep their tier label. Missing,
duplicate, unknown IDs and manifest/runtime disagreements fail validation.

The two original `@unittest.expectedFailure` cases retain the manifest status
`expected-failure`. Use `TestSupport.expectedFailure(id, reason) { ... }` with
the original desired assertions; it registers its own nonpass disposition.
Only IDs with verified upstream decorator metadata in `upstream-tests.json`
are eligible. A failure of shared `check`, `equal`, `contains`, `notContains`,
or `raises` emits the dedicated `CheckFailure` accepted by this bracket.
An unexpected pass (XPASS), empty body, runtime error, or an unrelated bridge
`AssertionError` fails the run. These two cases use direct bodies; `subcase`
collection inside `expectedFailure` is explicitly rejected. An expected
failure is reported separately and never counted as a passing Scala port.

All suites live in `isabelle.query.regression` and expose `def run(): Unit` in
`ParserSuite`, `GraphSuite`, `ShapeSuite`, or `CliSuite`. Shared helpers live in
`scala/support/TestSupport.scala`; special fixtures/helpers stay with the suite.
`parse` uses raw `Theory.parse_one`; `cli` captures output with request-owned
writers and a fresh `CLI.Session`, environment, root and stdin hooks. Use
`subcase(label) { ... }` or `note(label)` to report looped fixture rows. Original
method IDs are an accounting boundary, not a count of logical subcases.

## Upstream compatibility

```sh
python3 dev/check-upstream-compat.py --check-only
# After supplying QUERY_TEST_AFP, QUERY_TEST_DISTRO and QUERY_ORACLE:
make compat
```

The gate checks working Python bytes and executable modes, not merely HEAD or
the version string. Same-version source/metadata drift fails the cheap check;
reviewed maintenance can use `--force`. Version changes trigger complete
compatibility by default. All seven standard corpora are mandatory. The copied
harness executes 28 entry projections and 2,149 warm differential comparisons,
including stale-pin rejection. Entry projections require successful producers
as well as equal bytes. Failed/partial runs never update the baseline. On Linux,
timeout/error cleanup terminates owned command groups and detached servers only
when both their exact name and private user home match this run.
Successful runs record source, layout dependency, corpus, matrix, pin and Scala
identities; local corpus and installation paths are not recorded in the baseline.

The initial baseline explicitly cites prior `dev/P11-STATUS.md` evidence:
2,149 comparisons, including 181 pins. Its unknown historical layout/corpus
identities remain null; bootstrapping did not run a fresh matrix. All 307
invocation IDs in `scala/coverage/matrix.json` retain their exact corpus/process
compatibility tier. That mapping does not claim that a small semantic unit
fixture repeats a seven-corpus byte comparison.

## Lifecycle integration

`dev/p12integration.sh` tests jEdit/PIDE hosts, owner exit/signals, and borrower
recovery in copied components and a private user home. Set `PIDE_MCP_COMPONENT`
to an isolated PIDE dependency archive. The narrower `test_owned_launcher.py`
suite uses `QUERY_OWNED_TEST_COMPONENT` pointing to a compiled private
`query_base`; run it with unittest discovery. Neither belongs in the fast loop.

## Optional reference and performance checks

The frozen Python suite uses stdlib `unittest`; `support.py` loads the in-tree
reference source. These are maintenance commands, not the routine Scala loop:

```sh
python3 -m unittest discover -s tests -v
ISABELLE_QUERY_CORPUS="$QUERY_TEST_AFP" python3 -m unittest discover -s tests -p test_corpus.py -v
ISABELLE_QUERY_PERF=1 python3 -m unittest discover -s tests -p test_perf.py -v
```

Corpus checks cover parsing robustness and graph consistency. Performance checks
compare per-theory scaling, where quadratic regressions can hide if only the
number of theories grows. For query latency and memory measurements, run
`dev/bench.sh all`; see [benchmark method and results](../dev/BENCH.md).

The two original expected failures concern infix abbreviation names and a blank
line before an attached declaration comment. Their Scala equivalents retain
strict expected-failure assertions; they are not ordinary passes.

The graph reference builder checks consistency with the specified source-scanning
rules, not Isabelle semantics. When a semantic probe reveals a defect, add a
small hand-written regression fixture with an independently derived answer.

## P13 focused checks and publication gate

The focused P13 runners are:

```sh
bash dev/p13-source/run.sh
bash dev/p13-pane.sh
```

Those two runners create copied components and private Isabelle homes. The
loading and cache runners instead require `USER_HOME` to name a
parent-coordinated isolated home with copied components already built. Loading
requires its engine jar; cache requires both engine and jEdit jars. With that
prerequisite satisfied, run:

```sh
bash dev/p13-loading.sh
bash dev/p13cache.sh
```

They cover compressed exact source snapshots, bounded fatal-aware parsing,
atomic cache publication and invalidation, scoped callers refresh, Delete/Clear,
and bounded excerpts. For one fast Scala suite, run
`dev/scala-tests.sh --suite parser`; the other valid choices are `graph`,
`shape`, and `cli`. `make test` runs all four plus the transport suite and the
ordinary upstream-identity gate.

For the P13 release, unchanged upstream does not waive the full compatibility
run. Publication requires the final frozen snapshot to pass all 2,149
differential cases and 28 entry projections, with documented divergences
audited, and requires every benchmark case. These gates are separate from the
routine `make test` pass and remain pending until their final reports say PASS.
