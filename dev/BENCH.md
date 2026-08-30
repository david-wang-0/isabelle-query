# Benchmark — the three ways to ask this tool a question

Produced by `dev/bench.sh`, which is the authority on method; this file records
what it printed and what the numbers mean.

|  | what it is |
|---|---|
| **oracle** | the Python implementation (`query` 0.7.0 on `PATH`), cold |
| **cold** | `isabelle query --no-server` 0.8.0-scala, a fresh JVM per invocation |
| **warm** | `query_base/lib/scripts/query_client.py` against a resident server |
| **delegated** | `isabelle query` with no flags, which finds that same server itself |

The cold column says `--no-server` because since P7b that is what makes it
cold. Without the flag `isabelle query` delegates, and the column would be
measuring the delegated path under the cold label — the same shape of mistake
`dev/P6C-STATUS.md` §5 records for the tiny tier's subject. The flag restores
exactly the previous behaviour, so the figures below are unchanged.

Since P7d the spellings have moved but the columns have not: a plain
`isabelle query` now *is* the warm column (the shim in
`query_base/lib/Tools/query` runs the thin client), the delegated column is
what `$ISABELLE_QUERY_NO_CLIENT=1` buys, and cold is still `--no-server`,
which routes past client and server both. The measurements stand; only the
name of the default path changed — from the slowest warm route to the
fastest.

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
load:      0.23 (1 min) at the start of the run — no other heavy process
```

Tiers (a)–(d) were re-measured on **2026-08-30**, after `[p8-coldpath]` cached
the two cheapest parts of the cold path; the numbers below are that run.
Tier (e) and the memory table are from 2026-08-28 and are marked where they
appear. What moved is the **cold** column, and only where the cold column was
not already dominated by the parse — the two-theory tier lost a third
(1091 → 697 ms), `src/HOL` barely moved (4197 → 3890 ms), and the whole-AFP row
did not move at all. That is the same point the cost table above makes, arrived
at from the other end.

Corpora: an AFP checkout of the Isabelle2025-2 vintage (10,336 `.thy` files,
10,262 loaded across 1,043 sessions, 411,181 entries) and the distribution's
own `src`. Paths come from `$QUERY_TEST_AFP` / `$QUERY_TEST_DISTRO`.

## (a) tiny — `Abstract_Completeness`, 2 theories, 81 entries

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `show fair_fenum` [^1] | 75 | 697 | **32** |
| `summary` | 72 | 724 | **31** |
| `callers mono` | 77 | 716 | **35** |

The cold column here is almost pure process setup: ~0.7 s to answer a question
about two files, of which the JVM proper is ~30 ms. That is the crossover P2
recorded and the reason the warm mode exists. It was ~1.1 s until
`[p8-coldpath]`; what came off is a redundant `scala_build` and the class
loading, which is all there was to take — the remainder is the settings shell,
the JVM, and about 40 ms of actually reading two files.

[^1]: **This row replaces a bad one.** It read `show expand`, and
`Abstract_Completeness` declares nothing called `expand` — so all three columns
timed the same `No entries matching 'expand'.`, measuring the parse and the
process start and none of the rendering, and agreeing with each other for the
wrong reason. The old figures were 73 / 1060 / **31** ms. `fair_fenum` is a
27-line lemma that exists. All three rows of this tier now come from the same
2026-08-30 run, median of 5, all answers byte-identical, so the footnote's
original worry — one row measured apart from its neighbours — no longer
applies. (The 2026-08-28 figures it replaced were 73 / 1091 / 33.)

## (b) medium — `Category3`, 28 theories

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `callers comp_assoc` (206 callers) | 284 | 1086 | **112** |
| `callers category_axioms` (25 callers) | 279 | 1053 | **56** |
| `shape summary` | 914 | 1507 | **354** |

## (c) `src/HOL` — 1451 theories, 78,279 entries

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `instances comm_monoid` | n/a | 4429 | **353** |
| `codeqs rev` | n/a | 4303 | **310** |
| `summary` | 4863 | 3890 | **64** |

`instances` and `codeqs` have no Python counterpart, hence no oracle column.
This is the tier the warm mode was built for: the cold tool spends ~4.4 s
parsing 1451 theories to answer a lookup, and answers the same question warm in
a third of a second — or 64 ms where the answer is a table it already has.

It is also the tier that shows what the cold-path caches are **not** for. They
took ~310 ms off a two-theory query and ~60 ms off this one, because here
almost the whole 4.4 s is the parse. Caching process setup helps exactly where
there is little else to do.

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
first open:  2799 ms, 1451 theories, 78279 entries, 1468 files fingerprinted
recheck:       12 ms (best of 5), 0 theories reparsed
```

12 ms to prove that 1468 files have not moved. A single edited theory reparses
one theory (`dev/p7probe.sh` §4 measures 23 ms on a 2-theory project, dominated
by the sweep rather than the parse).

## (d) the whole AFP — 10,336 `.thy` files

RUNS=3, re-measured 2026-08-30 via `dev/bench.sh afp`. The warm column needs an
index over the whole checkout, which the default 4000-theory cap refuses;
`--client-limit 0` is what asks for it, and the resulting resident index is a
~5 GB process (see the memory table).

| invocation | oracle ms | cold ms | warm ms | output |
|---|---:|---:|---:|---:|
| `summary --by-session` | 37,425 | 19,044 | **275** | 34 KB |
| `shape census` | 176,179 | 156,096 | 156,648 | 256 MB |

Two rows, two different lessons.

**`summary --by-session` is the warm mode's best case at scale**: 275 ms
against the cold tool's 19.0 s and the oracle's 37.4 s, because everything
expensive — parsing 10,262 theories — is already done and the answer is 34 KB.
Once a day's editing has warmed the index, a whole-AFP overview costs about
what a directory listing costs. This is the one row the cold-path caches do not
touch at all: 19 s of parse does not care about 300 ms of process setup.

**`shape census` is the workload the warm mode cannot help**, and the
table now shows it being declined rather than attempted. Two structural
reasons, neither incidental:

1. `shape census` does not go through `load_index` at all — it iterates
   sessions itself, one session live at a time, precisely so a corpus run's
   memory is bounded by the largest session rather than by the corpus. So it
   gets **no benefit** from a warm index.
2. Served, it would then pay for the transport. The reply is 256 MB and
   `query_run` is **synchronous with a single reply** — the server buffers the
   whole answer, JSON-encodes it, and the client decodes it before writing a
   byte. Measured at 170 s against 154 s cold before the bypass existed: the
   deliberate design of the protocol showing its limit. A `NOTE`-per-chunk
   variant would fix this row and cost every other row a task fork and two
   extra messages.

So a census is on the client's bypass list, and **the warm column above is the
cold path reached through the client**: 156.6 s against 156.1 s: the ~550 ms
difference is the client starting, declining and the shim running the query.
You get that by typing nothing — `isabelle query shape census` routes
itself.

The two Scala columns are byte-identical at 306,525 records; the oracle's
304,987 differ by the documented D-series.

That byte-identity is not decoration here. On the first 2026-08-30 run this row
reported a warm column of **29 ms against a 0-byte output**: P8 had changed the
client to DECLINE with exit 97 rather than exec the cold path itself, and
`bench.sh` was still invoking `query_client.py` directly, so it timed the
decline and measured nothing. The cold/warm comparison in this script is what
caught it. `bench.sh` now finishes a decline the way the shim does
(`warm_run`), which is why the column is a real number again.

## (e) heavy — one big session, and the two largest AFP entries

Taken 2026-08-29, same machine and method (median of 5, load < 0.5).
**Pre-cache:** this tier was not re-measured after `[p8-coldpath]`, so its cold
column is pessimistic — by a few hundred ms, not proportionally, since every
row here is parse-dominated. The tier
exists because (a)–(b) are small and (c)–(d) are extreme; this is the middle
a working formalization actually lives in. Corpora: `src/HOL/Analysis` (106
theories, 178k lines, a session-less root — directory discovery on both
sides), and the two largest AFP entries by theory volume, `AutoCorres2` (120k
lines) and `JinjaThreads` (89k). Subjects are hot on purpose, per tier (a)'s
rule: `has_integral` has 515 callers under the oracle, `refines` 1,063,
`wf_prog` 200 — every row pays for a real scan, not a lucky miss.

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| Analysis `summary` | 930 | 2,043 | **88** |
| Analysis `callers has_integral` (515) | 1,115 | 2,376 | **76** |
| Analysis `shape summary` | 6,083 | 4,911 | **2,370** |
| AutoCorres2 `callers refines` (1,063) | 886 | 2,195 | **144** |
| JinjaThreads `summary` | 593 | 1,838 | **80** |
| JinjaThreads `callers wf_prog` (200) | 700 | 1,988 | **148** |

Three things the middle tier shows:

1. **At 100–180k lines the oracle still finishes under the JVM's start-up**,
   so the cold rewrite loses every parse-bound row. The crossover is the one
   compute-bound row, `shape summary`, where the engine's speed pays for the
   JVM even cold (4.9 s against 6.1 s).
2. **Warm is 8–15× the oracle** on lookups and citation scans — and since
   P7d the warm column is what a plain `isabelle query` costs.
3. The `summary` and `shape summary` rows print a DISAGREE marker: the
   documented divergences surfacing in an unpinned run (the entry set —
   11,274 oracle vs 11,676 rewrite on Analysis — is the D-series' "no entry
   is ever lost" direction). The three `callers` rows agree byte for byte,
   import-reachability filter and all.

## Memory — peak RSS

Taken 2026-08-28 and unaffected by `[p8-coldpath]`: an AppCDS archive is
memory-MAPPED, shared and read-only, and skipping `scala_build` removes a
process that had already exited. Neither moves this table.

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

## The auto-delegating CLI (P7b) — a route P8 removed

Kept as a record, not as a description of the tool: `delegate.scala` was deleted
in P8 (see README "One router"), so there is no delegated column any more. What
the numbers still show is where the cost of a cold invocation actually sits, and
they are the reason the interpretation below had to be rewritten.

`isabelle query` with no flags, as it behaved in P7b: a fresh JVM, which then
found the warm server and asked it instead of parsing the corpus itself. This
section used to say "**the floor is JVM start**". That was wrong, and it was
wrong in a way that mattered — it argued for avoiding a JVM when the thing worth
avoiding was a parse. Measured on the same machine, 2026-08-29:

| what a cold `isabelle query` pays | ms |
|---|---:|
| `scala_build` — a second JVM, only to check whether the component is stale | ~405 |
| the `bin/isabelle` settings shell, sourced again by `isabelle java` | ~180 |
| **the JVM itself** | **~30** |
| Isabelle/Scala class loading, 53 jars | ~250 |
| the parse — 421 ms for a 28-theory entry, 2755 ms for `src/HOL` | varies |

Bare `java -version` on the bundled JDK 21 is 30 ms; `isabelle getenv` alone is
185 ms and starts no JVM at all. Running `Query_Main` directly with a cached
environment — no bash, no `scala_build` — costs 345 ms before any work, and
155 ms with an AppCDS archive. So the "~0.9 s of JVM" below is really ~0.03 s of
JVM inside ~0.9 s of process setup, most of it bash and a redundant build check.

### The cold path since P8

Two of those rows are now cached. Measured through the front door, median of 5,
`summary` on a two-theory AFP entry, 2026-08-29:

| | CDS on | CDS off |
|---|---:|---:|
| `scala_build` skipped | **722** | 828 |
| `scala_build` forced | 906 | 1032 |

`$ISABELLE_QUERY_ALWAYS_BUILD=1` and `$ISABELLE_QUERY_NO_CDS=1` are the two
switches, and are how the right-hand column and bottom row were taken.

The two savings are **not additive** — 190 + 126 rather than 382 + 250 — because
a `scala_build` that has just run leaves the page cache warm for the JVM that
follows. A naive sum would have promised 600 ms and delivered 310. Worth
remembering before quoting either number on its own.

Tiers (a)–(d) above WERE re-measured against this cold path on 2026-08-30 and
carry the new figures. The P7b table immediately below, tier (e) and the memory
table were not: they predate the caches and their cold columns are pessimistic
by up to a third. They are kept as taken — a half-re-measured table is worse
than a dated one — and each says so where it appears.

```
date:      2026-08-29 02:50 UTC     (same machine, load 0.31)
runs:      median of 5
```

| invocation | cold ms | warm ms | delegated ms |
|---|---:|---:|---:|
| `show fair_fenum` — 2 theories | 1090 | 37 | **973** |
| `summary` on `src/HOL` — 1451 theories | 4194 | 68 | **1036** |
| `instances comm_monoid` on `src/HOL` | 4586 | 338 | **1332** |

**Read it as process setup plus the answer, and nothing else.** About 0.9 s of
that column is setup in every row — of which the JVM proper is ~30 ms — so the
tiny row is all floor and saves almost nothing (1090 → 973), while the two
`src/HOL` rows save 3.2 s and 3.3 s — 4.0x and 3.4x — because the parse they no
longer do was the whole cost. That asymmetry is the whole argument for a warm
INDEX rather than a warm process, and it is why P8 could delete this column
without giving anything up: the client already covered the case where the win is
large, and where the win is small there was nothing to keep. Each row's
delegated answer was compared with its cold one; a disagreement is printed in
the table rather than hidden.

**Where the rest of a delegated invocation goes**, from
`$ISABELLE_QUERY_SERVER_VERBOSE=1`, on the tiny row:

```
query-delegate: registry   60 ms      open servers.db (JDBC + native library)
query-delegate: connect     6 ms      TCP, password, greeting
query-delegate: query_run  37 ms      the request, the answer, and the JSON
query-delegate: delegated, 105 ms
```

The registry read is the single largest item and it is **SQLite**: opening
`$ISABELLE_HOME_USER/servers.db` loads the JDBC driver and its native library
into a JVM that has just started. The `query_run` figure is class loading, not
work — the same request measures ~1 ms inside the long-lived thin client.
Neither is removable without keeping a copy of the server's password somewhere
the Isabelle registry did not put it, and together they are why this mode is a
convenience rather than a competitor to the client.

**When each of the three warm routes wins**

- **thin client** — interactive use, where 37 ms against 1090 ms is the
  difference between a tool you keep typing at and one you stop reaching for.
- **cold** — one-off runs, a whole-corpus census, anything reading stdin, a
  machine with no `python3`, and any situation where a resident JVM holding an
  index is not wanted. `--no-server` is how you say so. (P7b–P7d had a fourth
  mode between these two, a JVM that delegated; P8 removed it.)

## Reading the columns

- **A small cold query loses, and no amount of engineering fixes it.** ~0.7 s
  of process setup per invocation is a fixed toll the oracle does not pay. Note
  *process setup*, not "JVM start": of the ~1 s it was before `[p8-coldpath]`,
  ~405 ms was `scala_build`, ~180 ms the settings shell, ~250 ms class loading,
  and ~30 ms the JVM. The caches took the first and most of the third; what
  remains is the settings shell, the JVM and the work. Anything under about
  three quarters of a second of real work is still faster in Python, cold.
- **The cold tool wins where there is work to do.** `src/HOL summary`:
  3890 ms against the oracle's 4863, and it finds 434 more declarations while
  doing it.
- **The warm client wins everywhere a human waits for an answer**: 2.3x on the
  tiny tier, 2.5–5.0x on the medium one, 76x on a `src/HOL` lookup and 136x on
  a whole-AFP `summary --by-session`, both against a resident index. The floor
  is the client process itself — about 15 ms of Python start plus 16 ms of
  imports, against a sub-millisecond round trip. Those multiples are against
  the ORACLE; against the cold tool the same rows span 1/2 (Analysis
  `shape summary`, tier (e)) to 1/72 (the whole-AFP `summary --by-session`).
- **It loses on exactly one workload, and predictably**: a whole-corpus
  `shape census`, which bypasses the index by design and returns 256 MB
  through a protocol that buffers a whole reply. Run that one cold.
- **What the warm client actually saves is the parse, not the process.** On
  `src/HOL` the index costs 2799 ms to build and 12 ms to re-check; that ratio,
  not the ~0.7 s of setup, is what makes the 76x. `[p8-coldpath]` is the
  clearest possible demonstration: it removed a third of the setup and moved
  this row by 60 ms. The P7b delegated column is
  the control that proves it: a warm process with a cold parse recovered only
  1090 → 973 ms on a two-theory entry, because there was no parse worth
  skipping. P8 deleted that column.
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
dev/bench.sh delegate              # the auto-delegating CLI, three rows
```

`RUNS=n` overrides the sample count. The script refuses without both corpora
and the Python oracle on `PATH` — a benchmark missing a column is not one.
