# P7 — warm server, benchmarks, docs, push preflight

The last phase. What it shipped, what it measured, what it found, and what is
left for a human.

## What shipped

| | |
|---|---|
| `query_base/src/server.scala` | the warm index and four `Server.Commands` entries |
| `query_base/lib/scripts/query_client.py` | the non-JVM thin client |
| `query_base/src/cli.scala` | split into `run_result` (no I/O, no exit) and `run` |
| `query_base/etc/build.props` | `services = … isabelle.query.Query_Server_Commands` |
| `dev/p7probe.sh` + `dev/p7probe.py` | 52 checks, the server's gate |
| `dev/bench.sh` + `dev/BENCH.md` | the three-column benchmark |
| `README.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `MIGRATING.md`, `SCANNING.md`, `METRICS.md` | the push-preflight rewrite |

## The protocol, as shipped

Four commands, contributed to the stock `isabelle server` by a
`Server.Commands` service. No new daemon, no new registry, no new password
scheme — a registered component's jars are already on every `isabelle server`
process's classpath, so the warm mode costs one class and one `services =`
line. The study is in the gitignored `.dev/SERVER-NOTES.md`; the decision is
recorded in `PLAN.md` §P7.

```
query_version {}                        -> OK {protocol, version, component_id, jar, indexes}
query_version {"open": true}            -> ... ++ {open: [index stats]}

query_open  {root?, cwd?, env_root?, limit?, client_id?}
                                        -> OK {index_id, root, theories, entries,
                                               files_checked, build_ms, check_ms,
                                               reparsed, uses}

query_run   {argv, cwd?, env_root?, root?, index_id?, limit?, client_id?}
                                        -> OK {exit, output, error, index_id,
                                               refresh_ms, run_ms, component_id}

query_close {}                          -> OK {closed: N}     # everything
query_close {"index_id": …}             -> OK {closed: 1}
query_close {"root": …}                 -> OK {closed: 0|1}
```

Five decisions inside it, each of which could have gone the other way:

1. **`query_run` takes an `argv` and hands it to `CLI.run_result`.** Same
   grammar, same commands, same exit statuses as the command line. The
   alternative — a second command language over the socket — would be a second
   thing to keep in parity with the Python oracle, and the parity is the
   project. Getting there is what split `CLI.run`: `run_result` is the whole
   CLI minus the two things only a process may do (write to file descriptors,
   exit), and `run` is that plus those two.

2. **Synchronous replies, not `Server.Task`s.** The sketch in SERVER-NOTES used
   a task, following `session_build`. A query has neither progress nor
   cancellation to report: it is one request, one answer. A task costs a thread
   fork and two extra messages on a round trip whose whole budget is
   single-digit milliseconds, and blocking is per-client anyway (the server
   forks a thread per accepted socket), so a whole-corpus query holds up only
   the client that asked for it. **The price**: a client that disconnects
   mid-query leaves the work running to completion. A cancellable variant needs
   the task form back.

3. **The exit status is DATA; only a refusal the CLI cannot express is an
   `ERROR`.** Exit 1 (unresolved subject) and exit 2 (usage, bad root) arrive
   as `OK {exit: n}` and the client re-emits them, because a wrapper that saw
   `ERROR` could not tell a refused subject from a crashed server. Over the
   size cap, an empty root, a missing root, an unknown index id, a missing
   `argv`, a stale component: those are `ERROR` with a message.

4. **The namespace is rebound before every request, under one lock.** See
   below — this is the phase's main correctness question.

5. **The client resolves nothing the CLI could resolve.** It forwards its own
   `cwd` and `$ISABELLE_QUERY_ROOT` and the server applies
   `CLI.default_root_from` — the policy has one definition. The one thing the
   client does rewrite is relative paths in `argv` (any token that names an
   existing file or directory, and `-R`'s argument), because a served run
   happens in the server's working directory and would otherwise resolve
   somewhere else. `p7probe.sh` §9 pins all four cases: absolute `-R`, relative
   `-R`, no `-R` at all, and an unrelated cwd.

## The `Namespace` fix

**The problem** (P4-STATUS §2, P5-STATUS §8): `isabelle.query.Namespace` is
process-global mutable state. It decides whether `auto` is a proof method or a
fact citation, it is bound per project (a ZF session steps down to the Pure
floor, a HOL one does not), and the corpus-wide `shape` view binds the broad
HOL union **unconditionally** — by design, because a census must regenerate
identically anywhere. In a process that exits that is harmless. In a resident
one it poisons every later request in that JVM.

**What was considered.** Threading the table through as a value is the real
fix and stays the right answer, but it changes the signature of every analysis
in `usage_graph`, `usage` and `shape` — a refactor with its own gate, not a
P7 change. Binding per index and hoping requests do not interleave is not a
fix; it is the same bug with a smaller window.

**What shipped** — restore, then bind, then run, all under one lock:

```scala
engine_lock.synchronized {
  Namespace.use_census_namespace()          // the state a fresh process starts in
  CLI.run_result(argv, out, err, prepare)   // configure_namespace steps down as usual
}
```

Three properties make this the honest version rather than a patch:

- **It restores rather than saves-and-restores.** `CLI.configure_namespace`
  only ever steps *down* from the committed default, so starting every request
  at that default makes the binding idempotent across projects regardless of
  what the previous request did — including a request that failed halfway.
- **The lock spans the whole run**, not just the binding. "The table is the one
  this request bound" is only true if exactly one request is in flight.
- **The policy is not restated.** `CLI.configure_namespace` remains the single
  definition, including the `$ISABELLE_QUERY_NAMESPACE=committed` pin. The
  server adds no second rule.

**The price, stated plainly**: two clients querying two projects serialise, and
because the warm index is provided lazily (see the defect list below) an index
*build* also happens under that lock. On a warm index the lock is held for
milliseconds; on a cold one, for the parse. The jEdit plugin pays the same
price through its single worker thread, for the same reason.

**How it is checked** (`p7probe.py` §6), and this is the check the whole design
exists for: a ZF `callers induct` immediately after a corpus-wide `shape` run
on the same project must equal its cold answer — `induct` is a HOL method, so
under a leaked broad table it stops being a citation. And the reverse: a HOL
project queried right after ZF must not inherit the Pure floor. Both green.

## Invalidation

Per request, and deliberately the expensive-but-honest reading: every `.thy`,
`ROOT` and `ROOTS` under the root, by mtime **and** size. Size alongside mtime
because a coarse filesystem clock can hide an edit landing in the same
millisecond as the last one — `p7probe.py` §4 rewrites a theory without
sleeping first, precisely to exercise that.

Measured on `src/HOL` (1451 theories, 1468 files fingerprinted):

```
first open:  2755 ms
recheck:       12 ms   (best of 5, 0 theories reparsed)
```

12 ms to prove nothing moved. When something has moved, discovery runs again
and only the files whose own key changed are reparsed — one edited theory in a
1451-theory project reparses one theory. Deleted and added files are covered
too, and both are checked against the cold answer rather than against an
expectation: an orphan `.thy` that no ROOT names must NOT be picked up, warm or
cold, and the probe pins the agreement rather than the behaviour.

## What the probe found

`dev/p7probe.sh` — 52 checks, all green, no stray processes. Three real
defects, all in code the probe tests:

1. **`query_run` pre-warmed the wrong root.** It guessed a root from the
   client's working directory, but `-R` lives in the `argv` — so the guess and
   the run disagreed whenever a caller passed one, and the *guessed* root could
   refuse (empty, over the cap) a query that never went near it. Fixed by
   providing the index lazily, through `CLI.Session.index_provider`, for
   whatever root `active_root` actually resolves.
2. **The size cap was only checked when something had to be reparsed.** An
   already-warm root sailed past `--client-limit 1`. It now runs on every
   refresh, *before* the unchanged-sources short cut: the limit belongs to the
   request, not to the index.
3. **An explicit `index_id` did not pin the root it named.** It now prepends
   the index's own `-R` when the argv does not carry one.

And two expectations the probe had wrong, corrected against the tool: an empty
*search* result is exit 0 (the search family's contract), and exit 1 belongs to
the *lookup* family — `codeqs` on a name that is not a constant is the case
that carries it.

## End-to-end transcript

The gate's own run, `dev/p7probe.sh`, is the transcript: it opens an index,
runs 20 verbs warm against the cold tool, edits a file and re-queries, adds and
deletes one, detects a changed jar and restarts, kills the server mid-session
and answers anyway, and stops what it started.

```
0. the server starts, and it is the one we asked for
  ok    the client started a server under its own name
  ok    and the registry knows it
  ok    a freshly started server is not stale
1. handshake
  ok    protocol number matches the client's  [1]
  ok    component id is this checkout's jar  [1787939631624:962913]
2. query_open, and what a re-open costs
  ok    the index has theories and entries  [2 theories, 81 entries]
  ok    the first open parses every theory  [2]
  ok    and reparses nothing  [recheck 1 ms over 3 files]
3. warm/cold parity -- the served answer IS the typed answer
  ok    20 invocations agree with the cold tool  [stdout, stderr presence, exit]
  ok    an unresolved subject is OK with exit 1 and a diagnostic  [OK exit=1]
  ok    a usage error is OK with exit 2 and a diagnostic  [OK exit=2]
4. invalidation -- an edit is visible on the next request
  ok    an edited theory changes the answer at once  [exit 0, refresh 23 ms]
  ok    and the changed answer still equals the cold one
  ok    a file added on disk lands warm exactly as it lands cold
  ok    and a DELETED theory drops out of the warm index too
5. refusals arrive as protocol errors
  ok    over the cap the reply is ERROR, not OK  [ERROR]
  ok    and carries no empty answer to be mistaken for a result
6. the namespace does not leak between requests
  ok    a ZF `callers` after a corpus-wide shape run equals the cold answer
  ok    and the HOL project right after it does not inherit the Pure floor
7. bad requests are refused, not guessed at
  ok    and the connection survives them all  [OK]
12. staleness -- a rebuilt component is not answered from the old one
  ok    a changed jar is detected and the server restarted
  ok    the new server carries the new stamp  [...962913 -> ...962913]
13. failure falls back, and the answer is still right
  ok    after the server is killed mid-session the answer is still right  [1109 ms]
  ok    a client that cannot start a server falls back to the cold tool
15. nothing is left running
  ok    the probe's server is stopped
  ok    and it is out of the registry

52 checks: 0 failing
P7PROBE SERVER OK
```

The staleness check uses `touch` on the jar rather than a rebuild — the stamp
is mtime+size, `touch` changes exactly what a rebuild changes, and it is
deterministic where a 30-second rebuild is a race. A real rebuild was exercised
by hand during development and behaves identically (the client reports
`component rebuilt under the server; restarting` on the first request after
`isabelle scala_build`).

## Benchmarks

Full table, method and machine in [`BENCH.md`](BENCH.md). The headline, median
of 5:

| | oracle (Python, cold) | cold JVM | warm client |
|---|---:|---:|---:|
| `show` on a 2-theory entry | 73 ms | 1060 ms | **31 ms** |
| `callers` on a 28-theory entry | 290 ms | 1441 ms | **112 ms** |
| `summary` on `src/HOL` (1451 theories) | 4865 ms | 4197 ms | **64 ms** |
| `instances` on `src/HOL` | n/a | 4485 ms | **331 ms** |
| `summary --by-session`, whole AFP | 37,487 ms | 19,497 ms | **269 ms** |
| `shape census`, whole AFP (256 MB out) | 176,573 ms | **154,160 ms** | 170,447 ms |

**The push condition is met**: the thin client beats the Python oracle on every
interactive query measured, by 2.3x on the smallest and 139x on the largest.

**One row goes the other way, and it is stated rather than dropped.** A
whole-corpus `shape census` is 170 s warm against 154 s cold. Two structural
reasons, neither incidental: `shape census` does not go through `load_index` at
all (it iterates sessions itself so a corpus run's memory is bounded by the
largest session, not the corpus), so it gets no benefit from a warm index; and
its 256 MB reply then pays for a protocol that is **synchronous with a single
reply** — the server buffers the whole answer, JSON-encodes it, and the client
decodes it before writing a byte. That is the ~16 s. A `NOTE`-per-chunk variant
would fix this one row and cost every other row a task fork and two extra
messages. Run a census cold; the client is a transport, not a planner, so it
cannot decide that for you.

The whole-AFP index the `summary --by-session` row needs is refused by the
default 4000-theory cap and admitted by `--client-limit 0`; the resulting
resident process is ~5 GB.

The memory column of P4's record is corrected there too: every Scala figure
recorded so far was the JVM's 4 GB ceiling, not a working set. Pinned at
`-Xmx512m`, `src/HOL`'s `summary` produces byte-identical output at 831 MB
(against 2715 MB unpinned) — but the whole AFP genuinely does not fit, and
fails the right way, with `OutOfMemoryError` on stderr and an empty stdout
rather than a truncated answer.

## The full regression, at the end of the phase

P7 touched `query_base/src/cli.scala`, which every command goes through, so the
whole suite was warranted rather than a spot check. All of it run after the
last code commit, on a quiet machine.

| harness | result |
|---|---|
| `dev/entrydiff.sh` | **28 checks, 0 differing** — theory set, entry set, spans and bindings over all seven standard corpora (Abstract_Completeness, AODV, Category3, FOL, ZF, Sequents, CTT) |
| `dev/difftest.sh` | **2,086 cases: 1,946 clean, 140 pinned, 0 failing, 0 stale pins** — identical to the totals P4 and P6b recorded, so the CLI split moved nothing |
| `dev/p5probe.sh` | OK — plugin/CLI parity |
| `dev/p6probe.sh` | OK — IDE features |
| `dev/p6bprobe.sh` | OK — 100 fixture checks + 22 shell checks |
| `dev/p7probe.sh` | OK — 52 checks, no stray processes |

No `isabelle server` process survives the run, and the scratch home's
`etc/settings` is back to empty (the benchmark's heap pin removed).

## Hygiene sweep

`.gitignore` covers every build output and scratch path — verified with
`git check-ignore` on the two jars, `.dev/`, `__pycache__` under the new
`lib/scripts/`, and `.commit-msg`. `git status` is clean of build artefacts.

A full scan of **tracked** files for `/home/`, `~/`-prefixed paths, `file://`
and secret-shaped strings turns up **nothing introduced by the rewrite**. Every
hit is one of:

- prose about the rule itself (`CLAUDE.md`, `PLAN.md`);
- `query_base/src/cli.scala:565`, `s.startsWith("~/")` — tilde expansion in
  code, which the rule explicitly exempts;
- `.github/workflows/release.yml`, `GH_TOKEN: ${{ github.token }}` — the
  standard Actions idiom, not a secret.

**One finding needs a human decision before the push** (below).

## What remains for a human

1. **`.claude/memory/` and `scripts/` carry personal paths, and they are
   tracked.** Thirteen memory files reference `~/repos/afp`, `~/projects/query`
   and `~/.claude/projects/…`, and one carries the author's email; four
   `scripts/*.py` default to `~/repos/afp/thys`. All of it is **inherited**:
   `git diff 24fcbf0..HEAD -- .claude/memory scripts src/isabelle_query tests`
   is empty, so the rewrite touched none of it. It is left alone deliberately —
   `.claude/memory` is tracked on purpose (see `memory-tracked-in-repo.md`) and
   is the agent's live working memory, so un-tracking or rewriting it is a call
   for its owner, not a hygiene sweep. If this repository is to be public,
   decide before pushing.

2. **The jEdit manual checklist.** Nothing here has run with a display.
   `dev/P5-STATUS.md` §"The checks" and `dev/P6-STATUS.md` / `dev/P6B-STATUS.md`
   list what to click. The registration step is `dev/P5-STATUS.md`
   §"Registering it for real" — build green in the scratch home first, because
   a registered component that fails to build breaks `isabelle jedit` start-up
   for every session.

3. **The push itself.** A GitHub `origin` exists
   (`david-wang-0/isabelle-query`) and nothing has been pushed. When ready:

   ```sh
   git push -u origin main
   ```

4. **The warm client has no console name.** It is invoked as
   `python3 query_base/lib/scripts/query_client.py`. Giving it the `query` name
   (a `lib/Tools/` script, or a shell alias) is a one-line decision that
   belongs to whoever installs it — `PLAN.md` §P7 anticipated it and it is
   deliberately not taken here.

## Watch-outs for whoever comes next

1. **The engine lock is the throughput ceiling, and the `Namespace` refactor is
   what lifts it.** Every request in the server JVM serialises, index builds
   included. Threading the method table through as a value removes the reason
   for the lock; nothing else does.

2. **`shape census` gets no benefit from the warm index.** It iterates sessions
   itself rather than going through `load_index`, so the warm column for it is
   the cold time minus JVM start. If the census is ever the thing being
   optimised, that is where to start — along with P4's two recorded
   redundancies (`cited_facts_on_line` computed three times per step,
   `greedy_extract` twice per block).

3. **A resident index is resident memory.** `query_close` is the only bound
   besides the size cap, and there is no idle timeout anywhere in
   `isabelle server` — a started server runs until it is stopped. The default
   cap of 4000 theories clears `src/HOL` and every AFP entry and refuses a
   whole checkout; `--client-limit 0` overrides it, and the whole-AFP index is
   a ~5 GB process.

4. **`dump-entries` / `dump-imports` / `dump-theories` and `-` route cold, by
   construction.** The dumps write straight to the process's own stdout (they
   are corpus-sized, not socket-sized) and `-` reads the client's stdin, which
   the server cannot see. `COLD_ONLY_COMMANDS` in the client is where that list
   lives; a new dump-shaped verb must be added to it.

5. **The client's settings cache keys on `PATH`, `ISABELLE_TOOL`, `USER_HOME`,
   `HOME` and `ISABELLE_IDENTIFIER`.** That is what keeps a scratch-home client
   from talking to the real registry. A new environment variable that changes
   which Isabelle is meant has to go into `settings_key()`.

6. **D12 is still open, and still deliberate.** `\w` is Java's, not Python's;
   one record differs AFP-wide. The fix is a translation layer under
   `Py.compile`, which changes what a NAME is — it needs the P1 entry-set gate
   re-run over both corpora, not just the difftest. Recorded in
   `dev/DIVERGENCES.md` as future work.

7. **The bench's heap pin is a line in a settings file.** `restore_heap` had a
   `grep -v … && mv` that silently did nothing when the pin was the file's only
   content (grep exits 1 when it filters everything away), and the pin survived
   a whole run. Fixed, but the shape of that bug is worth remembering: anything
   that edits `$ISABELLE_HOME_USER/etc/settings` must be verified to have
   undone it.
