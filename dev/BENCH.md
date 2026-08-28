# Benchmark — the three ways to ask this tool a question

Produced by `dev/bench.sh`, which is the authority on method; this file records
what it printed and what the numbers mean.

|  | what it is |
|---|---|
| **oracle** | the Python implementation (`query` 0.7.0 on `PATH`), cold |
| **cold** | `isabelle query` 0.8.0-scala, a fresh JVM per invocation |
| **warm** | `query_base/lib/scripts/query_client.py` against a resident server |

Every figure is a **median** of 5 runs (3 for the whole-AFP tier), wall clock,
measured around the whole invocation exactly as a user pays for it — process
start included, because process start is the thing under discussion. A
discarded warm-up run precedes each series, so no column is charged for
another's page cache. Every row's three answers are compared (md5 of captured
stdout); a disagreement is printed in the table rather than hidden.

## Machine and date

```
date:      2026-08-28
host:      Linux 7.1.9-1-MANJARO x86_64
cpu:       13th Gen Intel(R) Core(TM) i9-13950HX
cores:     32
memory:    62 GB
isabelle:  Isabelle2025-2
oracle:    query 0.7.0
rewrite:   query 0.8.0-scala
load:      2.3 (1 min) at the start of the run — no other heavy process
```

Corpora: an AFP checkout of the Isabelle2025-2 vintage (10,336 `.thy` files,
10,262 loaded across 1,043 sessions, 411,181 entries) and the distribution's
own `src`. Paths come from `$QUERY_TEST_AFP` / `$QUERY_TEST_DISTRO`.

## (a) tiny — `Abstract_Completeness`, 2 theories, 81 entries

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `show fair_fenum` [^1] | 73 | 1091 | **33** |
| `summary` | 72 | 1078 | **38** |
| `callers mono` | 73 | 1069 | **33** |

The cold column is almost pure JVM: ~1.1 s to answer a question about two
files. That is the crossover P2 recorded and the reason the warm mode exists.

[^1]: **This row replaces a bad one.** It read `show expand`, and
`Abstract_Completeness` declares nothing called `expand` — so all three columns
timed the same `No entries matching 'expand'.`, measuring the parse and the
process start and none of the rendering, and agreeing with each other for the
wrong reason. The old figures were 73 / 1060 / **31** ms. `fair_fenum` is a
27-line lemma that exists, and the row was re-measured on its own
(`dev/bench.sh tiny`, added for exactly this) on the same machine and date,
median of 5, all three answers byte-identical. The other two rows of this tier
are the original run's; re-measured beside the replacement they came out
79 / 1105 / 41 and 76 / 1107 / 35, i.e. within noise, so nothing here is stale.

## (b) medium — `Category3`, 28 theories

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `callers comp_assoc` (206 callers) | 290 | 1441 | **112** |
| `callers category_axioms` (25 callers) | 280 | 1416 | **59** |
| `shape summary` | 916 | 1851 | **364** |

## (c) `src/HOL` — 1451 theories, 78,279 entries

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `instances comm_monoid` | n/a | 4485 | **331** |
| `codeqs rev` | n/a | 4531 | **315** |
| `summary` | 4865 | 4197 | **64** |

`instances` and `codeqs` have no Python counterpart, hence no oracle column.
This is the tier the warm mode was built for: the cold tool spends ~4.5 s
parsing 1451 theories to answer a lookup, and answers the same question warm in
a third of a second — or 64 ms where the answer is a table it already has.

`summary` here is the one row whose three answers are **not** identical: the
oracle reports 77,845 entries and both Scala columns 78,279. That is D2
(`definition\<^marker>\<open>tag …\<close> name`), the documented divergence
where the oracle's hand-rolled lexer misses a declaration Isabelle's own does
not. The two Scala columns agree byte-for-byte with each other, which is what
this table is checking.

### What the staleness recheck costs

The warm index re-stats every source file on **every** request — that is the
whole invalidation story, and it is deliberately the expensive-but-honest
reading rather than a timestamp on the directory.

```
first open:  2755 ms, 1451 theories, 78279 entries, 1468 files fingerprinted
recheck:       12 ms (best of 5), 0 theories reparsed
```

12 ms to prove that 1468 files have not moved. A single edited theory reparses
one theory (`dev/p7probe.sh` §4 measures 23 ms on a 2-theory project, dominated
by the sweep rather than the parse).

## (d) the whole AFP — 10,336 `.thy` files

RUNS=3. The warm column needs an index over the whole checkout, which the
default 4000-theory cap refuses; `--client-limit 0` is what asks for it, and
the resulting resident index is a ~5 GB process (see the memory table).

| invocation | oracle ms | cold ms | warm ms | output |
|---|---:|---:|---:|---:|
| `summary --by-session` | 37,487 | 19,497 | **269** | 34 KB |
| `shape census` | 176,573 | 154,160 | 170,447 | 256 MB |

Two rows, two different lessons.

**`summary --by-session` is the warm mode's best case at scale**: 269 ms
against the cold tool's 19.5 s and the oracle's 37.5 s, because everything
expensive — parsing 10,262 theories — is already done and the answer is 34 KB.
Once a day's editing has warmed the index, a whole-AFP overview costs about
what a directory listing costs.

**`shape census` is the warm mode's worst case, and it is slower than cold.**
170 s against 154 s. Two reasons, both structural rather than incidental:

1. `shape census` does not go through `load_index` at all — it iterates
   sessions itself, one session live at a time, precisely so a corpus run's
   memory is bounded by the largest session rather than by the corpus. So it
   gets **no benefit** from a warm index, and the warm column is the cold time
   minus JVM start.
2. It then pays for the transport. The reply is 256 MB, and `query_run` is
   **synchronous with a single reply** — the server buffers the whole answer,
   JSON-encodes it, and the client decodes it before writing a byte. That is
   the ~16 s, and it is the deliberate design of the protocol showing its
   limit: one request, one answer, no streaming. A `NOTE`-per-chunk variant
   would fix this row and cost every other row a task fork and two extra
   messages.

The two Scala columns are byte-identical at 306,525 records; the oracle's
304,987 differ by the documented D-series. **Use the cold tool for a census.**
The client cannot know that for you — it is a transport, not a planner — so
this is a fact for the user, which is why it is stated here and in
`dev/P7-STATUS.md` rather than hidden in a heuristic.

## Memory — peak RSS

Isabelle's `etc/settings` **overwrites** `$ISABELLE_TOOL_JAVA_OPTIONS` from the
environment, and this JVM ignores `$_JAVA_OPTIONS`, so the only heap override
that takes effect is a line in `$ISABELLE_HOME_USER/etc/settings`. `bench.sh`
writes one, runs, and removes it again. Anyone quoting a memory number for this
tool has to say which heap it was taken under, or the number is the ceiling
rather than the working set.

| invocation | stock heap MB | `-Xmx512m` MB |
|---|---:|---:|
| `summary` (src/HOL) | 2715 | 831 |
| `callers comp_assoc` (Category3) | 972 | 581 |
| `summary --by-session` (whole AFP) | 4708 | **OOM** |

Python oracle, for scale: `query -R src/HOL summary` peaks at **171 MB**.

Three things this settles, and the first corrects the record:

1. **P3's 4.65 GB and P4's 4.63 GB were the ceiling, not the footprint.** The
   stock heap here is `-Xms512m -Xmx4g`; a workload that fills it reports ~4.7 GB
   whatever it actually needs. Capped at 512 MB, `src/HOL`'s `summary` still
   produces byte-identical output at 831 MB — so its real working set is under
   512 MB of heap and the 2715 MB figure is GC laziness, not demand.
2. **The whole AFP genuinely does not fit in 512 MB**, and fails the right way:
   `java.lang.OutOfMemoryError: Java heap space` on stderr, empty stdout, and a
   non-zero exit. It does not answer a truncated corpus. That is worth stating
   because "runs at 512 MB" was the shape of P4's claim, and it holds for the
   streaming census (one session live at a time) but not for `summary
   --by-session`, which holds every section at once.
3. **The rewrite costs an order of magnitude more memory than the oracle** for
   the same answer (2715 vs 171 MB on `src/HOL`, or 831 vs 171 with the heap
   pinned). Some of that is the JVM's floor and some is that `Theory_Section`
   keeps the source text; none of it is hidden by quoting the ceiling.

A resident server holds its indexes until they are closed, so these numbers are
also the server's: `query_close` exists because nothing else bounds it, and the
size cap (`ISABELLE_QUERY_SERVER_LIMIT`, default 4000 theories) exists so a
stray `-R` at an AFP checkout cannot silently make the server a 5 GB process.

## Reading the three columns

- **A small cold query loses, and no amount of engineering fixes it.** ~1 s of
  JVM start per invocation is a fixed toll the oracle does not pay. Anything
  under a second of real work is faster in Python, cold.
- **The cold tool wins where there is work to do.** `src/HOL summary`:
  4197 ms against the oracle's 4865, and it finds 434 more declarations while
  doing it.
- **The warm client wins everywhere a human waits for an answer**: 2.3x on the
  tiny tier, 2.5–4.7x on the medium one, 76x on a `src/HOL` lookup and 139x on
  a whole-AFP `summary --by-session`, both against a resident index. The floor
  is the client process itself — about 15 ms of Python start plus 16 ms of
  imports, against a sub-millisecond round trip.
- **It loses on exactly one workload, and predictably**: a whole-corpus
  `shape census`, which bypasses the index by design and returns 256 MB
  through a protocol that buffers a whole reply. Run that one cold.
- **The round trip is not the cost.** A `query_run` against a warm index
  measures ~1.0 ms end to end inside the client. It was 43 ms until the
  framing's length header and payload went out in one write with `TCP_NODELAY`
  set — two writes let Nagle hold the second segment for a delayed ACK, the
  textbook 40 ms. Worth knowing before optimising anything else.

## Reproducing

```sh
source .dev/corpora.env            # or export the two variables yourself
dev/bench.sh tiny                  # tier (a) alone, for re-measuring one row
dev/bench.sh small                 # tiers (a)-(c), about two minutes
dev/bench.sh full                  # adds the whole-AFP tier
dev/bench.sh memory                # peak RSS at both heaps
```

`RUNS=n` overrides the sample count. The script refuses without both corpora
and the Python oracle on `PATH` — a benchmark missing a column is not one.
