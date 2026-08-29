# P7b — the cold CLI delegates to the warm server

P7 gave the JVM toll two answers and left the tool itself paying it. This is
the third: a typed `isabelle query` now looks for the same warm server the thin
client uses, starts one if there is none, and runs the query there.

## What shipped

| | |
|---|---|
| `query_base/src/delegate.scala` | new — detection, spawn, staleness, the bypass list, the opt-out, the fallback |
| `query_base/src/server.scala` | `Query_Server.default_server_name` — the shared name, in one place |
| `query_base/src/query_tool.scala` | the hook, at the process entry point and nowhere else |
| `query_base/src/cli.scala` | `--no-server` in `isabelle query -h` |
| `query_base/lib/scripts/query_client.py` | the shared-name comment, and the `absolutize` defect below |
| `dev/p7probe.sh` | §15, 26 checks; §16 stops what §15 started |
| `dev/bench.sh` | a `delegate` tier, and `--no-server` on every cold measurement |
| `dev/difftest.sh` | `$QUERY_DIFFTEST_DELEGATE=1`; `--no-server` otherwise |
| `dev/p5probe.sh`, `p6probe.sh`, `p6bprobe.sh`, `dev/entrydiff.sh` | pinned to the local path |
| `README.md`, `MIGRATING.md`, `CONTRIBUTING.md`, `dev/BENCH.md` | the three access modes |

## The design, in the order a request meets it

**1. The flag comes off first.** `--no-server` is read and removed before any
argument is parsed. It is listed in `isabelle query -h` and deliberately *not*
in the grammar: putting it in `resolve_long` would let `--no-serv` be accepted
as a no-op and then delegate anyway, which is the one outcome the flag exists
to prevent. `$ISABELLE_QUERY_NO_SERVER=1` is the same switch for a shell; the
flag wins, because it is on the line the user is looking at.

**2. The bypass list**, `Query_Delegate.bypass`, one place, mirrored in prose in
the README and by `COLD_ONLY_COMMANDS` in the client:

| bypassed | why |
|---|---|
| `-` anywhere in the argv | the only route into `CLI.read_stdin`, and the server cannot see this process's stdin |
| `dump-entries` / `dump-imports` / `dump-theories` | corpus dumps, written straight past any capture |
| `shape census` | 256 MB through a synchronous single-message protocol is *slower* warm (dev/BENCH.md), and a census does not go through `load_index` at all |
| `-h`, `--help`, `-V`, `--version` | text, no project |
| no arguments | prints its usage |
| **a RELATIVE token that names something here** | it may be a path or a pattern, and only the grammar knows — see the defect below |

**3. Detection.** `Server.private_data.list` on the stock
`$ISABELLE_HOME_USER/servers.db`, read without the write lock, matching on the
shared name — `isabelle_query`, or `$ISABELLE_QUERY_CLIENT_SERVER`, which names
the server for *both* front ends. `.list` rather than `.find`, which is `.list`
plus an `active` probe: that probe is a whole connect-and-round-trip, and the
connection we are about to make is the same probe. A row that went stale in
between is handled anyway, because it has to be.

**4. Staleness, without an extra round trip.** The jar stamp goes out as
`client_id` on the `query_run` itself, and the server refuses a mismatch — the
mechanism P7 already built for the thin client. `query_version` would be a
second round trip to learn the same thing, and it could not learn more: when
the stamp matches, the server is running *this jar*, so the protocol number
matches by construction. On a mismatch the server is stopped through the
protocol (`Server.exit` — a `kill -9` would leave a row pointing at a dead
port), a new one started, and the request replayed exactly once.

**5. Spawn.** `isabelle server -n NAME`, detached through `setsid` where the
platform has it, environment inherited (so a scratch `USER_HOME` keeps talking
to a scratch server), working directory `/` because a shared server must not
inherit one caller's cwd. The address is read from the greeting line with
`Server.Info.parse`, the distribution's own reader for it. **Concurrent starts
need no lock of ours**: the spawned process runs `Server.init`, which
finds-or-inserts under the registry's transaction lock, so a loser prints the
winner's line and exits at once and both callers end up with the same server.

**6. Nothing is printed until the whole answer is in hand.** The reply is one
message; it is decoded and only then written. So a fallback — at any point, for
any reason — cannot duplicate or truncate output, because nothing has been
written yet.

**7. Falling back is silent.** No server, a dead row, a refused connection, a
protocol the server does not speak, a socket that dies mid-request: all of them
run the query here instead, and none of them says so unless
`$ISABELLE_QUERY_SERVER_VERBOSE=1`. A note on stderr would change the bytes a
caller compares against a cold run, and byte identity is what this mode is
judged on. Verbose mode prints the stage split (`registry` / `connect` /
`query_run`), which is what the measurements below are read from.

**8. stderr is written BEFORE stdout.** The two streams arrive separately and
must be replayed in some order. A diagnostic has to survive a closed stdout,
and the one diagnostic the tool emits before computing anything — the Pure-floor
namespace note — comes first in a cold run too. The thin client writes stdout
first. That difference is recorded rather than hidden, and it is why every
harness here captures the two streams into separate files.

**9. A refusal the server makes about ITSELF is not the user's answer.** Over
the resident-index size cap the thin client reports and exits 2, because its
user chose the warm path. Here the user typed `isabelle query`, which has always
answered that question cold, so the cap falls back instead. The cap bounds the
server's memory; it must not shrink the tool.

## The defect this phase found

`§15` caught it, and it was in **shipped P7 code** as well as in the new file.
Both front ends rewrote every argument that named an existing file or directory
into an absolute path, on the reasoning that this is "exactly the set the tool
would have resolved as paths".

It is not that set. `find .` searches for the **regex** `.`; `grep pat .`
searches the **directory** `.`; the two tokens are spelled identically and only
the command's grammar tells them apart. The rewrite turned the first into a
search for the caller's own working directory — and the wrong answer arrived
looking like a correct empty one:

```
$ isabelle query -R <an AFP entry> find . -a      # delegated, before
No entries matching '/the/directory/you/happened/to/be/in'.
```

Rewriting *nothing* is equally wrong: `grep pat .` would then be served
against the server's own `/`.

**The fix, in both front ends.** `-R`/`--root`'s argument is still rewritten in
all four spellings — it is a directory in every invocation there is, so no
grammar has to be consulted. Every other token is left exactly as typed, and an
invocation carrying one that *names something here* does not go over the socket
at all.

Two kinds of token are **not** ambiguous, and excluding them is what keeps the
rule from swallowing the warm path whole:

- one that **names nothing** — the server resolves it exactly as a local run
  would, and gives the same `not a path or known theory` when it resolves to
  nothing. This is every ordinary subject, theory name, pattern and locus;
- one that is **absolute** — it means the same thing in any working directory,
  so it needs no rewriting and gets none, and whether the grammar reads it as a
  path or as a pattern the server reads it the same way. `dev/difftest.sh` is
  full of these (`grep NAME $THY1_PATH`, `lines $THY1_PATH 2..5`), and they all
  go over the socket.

A `~`-prefixed token *is* ambiguous despite looking absolute: expanding it could
corrupt a pattern, and not expanding it would send it to a server whose
`user.home` is somebody else's.

The price is that a *relative* path argument — `grep pat .`, `find .`,
`lines ./X.thy 2..5` — always runs cold. That is the honest shape of the trade:
the transport is not a parser, and the alternative to refusing is guessing.

## SIGPIPE, and one honest edge

A delegated `… | head -3` exits 141, as cold, and §15d pins both halves: a
reader that is already gone when the first byte is written, and a reader that
leaves three lines into a two-megabyte answer — well past the pipe's own
capacity, so the writer is still writing when it goes.

**The edge, stated plainly.** A delegated answer is written in one go, so an
answer that fits entirely inside the pipe buffer (64 KB on Linux) lands before
a reader like `head -3` has been scheduled to leave — and the run exits 0 where
a cold run, which flushes as it computes, exits 141. The bytes the reader sees
are identical; only the writer's own status differs, and only below the pipe
capacity. It is structural: an answer that is buffered and then replayed either
lands whole or fails, and buffering is what makes the fallback safe. The thin
client has had the same property since P7.

## Verification

### `dev/p7probe.sh` — 85 checks, 0 failing (was 58)

§0–§14 are P7's, unchanged, and now run with `$ISABELLE_QUERY_NO_SERVER=1`
exported: every `isabelle query` in them is the *cold reference* a served
answer is compared against, and without the pin each of those comparisons would
have quietly become warm against warm. §15 turns delegation back on per
invocation.

```
15. the cold CLI delegates by itself (P7b)
  ok    a cold CLI with no server starts one, under the shared name
  ok    and that first, spawning invocation already answers correctly
  ok    the next invocation finds THAT server and starts nothing
  ok    24 invocations: identical stdout, stderr and exit
  ok    and the comparison can say no (one byte added is caught)
  ok    a downstream that has already gone is 141, delegated as cold
  ok    output piped into `head -3` exits 141, as cold
  ok    a changed jar is detected and the server shut down and replaced
  ok    and the registry row is the NEW server's, not the dead one's
  ok    and the answer across the restart is still the cold one
  ok    a second server, started by a PINNED delegating CLI, is up
  ok    an UNPINNED delegating CLI gets the unpinned answer, and the note
  ok    and a PINNED one still gets the pinned answer (forwarded, not ignored)
  ok    $ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd
  ok    a census runs here, not over the socket (a 256 MB reply is slower warm)
  ok    a '-' argument runs here (the server cannot read our stdin)
  ok    a development dump runs here (it writes past any capture)
  ok    -h runs here, and documents the flag that turns this off
  ok    `find .` runs here and searches for the PATTERN, not for the cwd
  ok    and the thin client does the same, for the same reason
  ok    a name that is not a file is still delegated
  ok    and an ABSOLUTE path argument is delegated, unrewritten
  ok    --no-server answers here and starts no server
  ok    and $ISABELLE_QUERY_NO_SERVER=1 does the same for a shell
  ok    after the server is killed the answer is still right
  ok    a server that accepts and says nothing falls back, and answers
```

Three of those deserve a note.

- **The identity check is 24 invocations across every family** — structure,
  usage, shape, both site verbs — and includes all three exit statuses: 0, an
  unresolved subject (1) and a usage error (2). stdout, stderr and the status
  are each compared byte for byte.
- **Failability is demonstrated in place** rather than in a separate section:
  one byte is appended to a captured answer and the same `cmp` must report it,
  and the count of non-empty answers is asserted, so the comparison cannot pass
  by comparing nothing.
- **The fallback check needs a server that is not one.** A killed server leaves
  a dead port, which the CLI *rescues* by starting a replacement — so it does
  not exercise the fallback at all. §15i therefore also stands up a listener
  that accepts a connection and then says nothing, registers it under a
  probe-private name, and checks that the CLI gives up on it and answers
  locally. Both the listener and its registry row are removed afterwards.

### `dev/difftest.sh` with delegation forced on

`QUERY_DIFFTEST_DELEGATE=1` sends the whole matrix over the socket, against the
same Python oracle. It is the end-to-end statement that a user who never
configures anything gets the oracle's answers.

| corpus | cases | clean | pinned | failing | stale pins |
|---|---:|---:|---:|---:|---:|
| `Category3` | 298 | 297 | 1 | 0 | 0 |
| `Category3` + `Sequents` | 596 | 594 | 2 | 0 | 0 |

The `Category3` line is the per-corpus figure `dev/P4-STATUS.md` records for
the cold path, reproduced exactly; the pair adds `Sequents` at the same
298 / 297 / 1. **0 failing and 0 stale pins** is the statement that matters: not
one of the 596 answers changed by arriving over a socket, and no divergence the
matrix already knows about stopped being one.

### The rest of the gate

| harness | result |
|---|---|
| `dev/p5probe.sh` | green — plugin/CLI parity (`callers R`: plugin 23, CLI 23) |
| `dev/p6probe.sh` | green — IDE features, and the `show -V` cross-check |
| `dev/p6bprobe.sh` | green — 27 shell checks and the fixture layer |

All three now run with the local path pinned, so what they establish is
unchanged from P6c: they are about the engine, not about the transport.

## Benchmarks

`dev/bench.sh delegate`, median of 5, same machine and method as the rest of
`dev/BENCH.md`, which records them in full.

| invocation | cold ms | warm ms | delegated ms |
|---|---:|---:|---:|
| `show fair_fenum` — 2 theories | 1090 | 37 | **973** |
| `summary` on `src/HOL` — 1451 theories | 4194 | 68 | **1036** |
| `instances comm_monoid` on `src/HOL` | 4586 | 338 | **1332** |

**It is JVM start plus the answer, and that is the honest framing.** About
0.9 s of every delegated figure is the JVM, which this mode pays and the thin
client does not. So the tiny row is all floor and saves nothing worth naming;
the two `src/HOL` rows save 3.2 s and 3.3 s — 4.0x and 3.4x — because the parse
they no longer do was the whole cost. This mode exists for big-corpus command
lines and for places where adding a Python entry point is not worth it, not to
beat the client.

The stage split, from `$ISABELLE_QUERY_SERVER_VERBOSE=1` on the tiny row:

```
query-delegate: registry   60 ms      open servers.db (JDBC + native library)
query-delegate: connect     6 ms      TCP, password, greeting
query-delegate: query_run  37 ms      the request, the answer, and the JSON
query-delegate: delegated, 105 ms
```

Two thirds of that is first-use cost in a JVM that started a moment ago: the
SQLite driver and its native library for the registry read, and class loading
for the round trip (the same request measures ~1 ms inside the long-lived thin
client).

## Watch-outs for whoever comes next

1. **`--no-server` is the harnesses' business now.** Any script that compares
   the ENGINE with something else must pin it (or export
   `$ISABELLE_QUERY_NO_SERVER=1`), or it is testing the transport as well,
   depends on whether a server happened to be up, and leaves a corpus-sized JVM
   behind it. `dev/bench.sh`'s cold column is the sharpest case: without the
   flag it would have been measuring the delegated path under the cold label.

2. **The registry read is the delegated path's own cost, and it is SQLite.**
   The stage split (`$ISABELLE_QUERY_SERVER_VERBOSE=1`) says so plainly: the
   `query_run` round trip is tens of milliseconds in a cold JVM, the connect is
   single digits, and opening `servers.db` — which loads the JDBC driver and
   its native library — is the largest single item. It is why this mode can
   never approach the thin client, and it is unavoidable without keeping a copy
   of the server's password somewhere the Isabelle registry did not put it.

3. **A new environment variable read by the engine still has three places to
   go**, and now a fourth is not one of them: `CLI.request_env`, `FORWARDED_ENV`
   in the client, and a check in `dev/p7probe.sh` §9b/§15f. The delegating CLI
   reads `CLI.request_env` directly, so it needs no separate list — that was
   the point of putting the contract there.

4. **The bypass list is `Query_Delegate.bypass`, and a dump-shaped verb must be
   added to it** — and to `COLD_ONLY_COMMANDS` in the client, which is the same
   list on the other front end. A verb that writes straight to the process's
   stdout, or reads its stdin, is otherwise silently wrong when served.

5. **`isabelle query` now starts a resident JVM on its own.** That is the
   feature, but it means a user who runs one query against the whole AFP leaves
   a process holding that index until they stop it — there is no idle timeout
   anywhere in `isabelle server`. `isabelle server -x -n isabelle_query`, or
   the client's `--client-stop`, is how it goes away. The 4000-theory cap is
   what keeps an accident from becoming a 5 GB one, and hitting it falls back
   rather than refusing.
