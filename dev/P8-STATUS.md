# P8 — one router, one routing policy, and a corrected diagnosis

P7b through P7d ended with the tool's routing written down twice: once in
`query_base/lib/scripts/query_client.py` (755 lines of Python) and once in
`query_base/src/delegate.scala` (594 lines of Scala). The two agreed on
purpose — same bypass list, same registry lookup, same jar-stamp staleness
rule, same "never a wrong answer, then never a hang, then fast" ordering — and
were kept in step by hand.

They were also **stacked**. When the client could not serve a request it set
`$ISABELLE_QUERY_NO_CLIENT=1` and re-exec'd `isabelle query`, which since P7d is
the shim, which took the JVM path, which ran `Query_Delegate`, which looked up
the registry the client had just failed on — and, on some paths, tried to start
a server the client had just failed to start. The environment mark existed
solely to keep that hop from looping back into the client.

P8 deletes the second copy.

## What shipped

| | |
|---|---|
| `query_base/src/delegate.scala` | **deleted** (594 lines) |
| `query_base/src/query_tool.scala` | `main_tool` is `local_tool(CLI.strip_no_server(args))`; no branch |
| `query_base/src/cli.scala` | `CLI.strip_no_server`, where the flag it strips is already documented |
| `query_base/etc/build.props` | one source fewer |
| `query_base/lib/Tools/query` | the router: splits the argv, runs the client, falls back on exit 97 |
| `query_base/lib/scripts/query_client.py` | `cold()` returns `EXIT_RUN_COLD` instead of exec'ing; `find_isabelle` may return `None` |
| `query_base/src/server.scala` | header: the corrected diagnosis |
| `dev/p7probe.sh` | §15 rewritten as the decline protocol; §16 gains 16f/16g; §11 and §13 retargeted at the front door |
| `dev/difftest.sh` | `$QUERY_DIFFTEST_WARM` (old name still honoured) |
| `README.md`, `CONTRIBUTING.md`, `MIGRATING.md`, `CLAUDE.md`, `dev/BENCH.md` | the diagnosis, and "One router" |

## The decline protocol

The client no longer runs the cold path; it **declines** and the shim runs it.

- The client exits **97** (`EXIT_RUN_COLD`) having written nothing to stdout.
  97 is outside the CLI's exit contract (0 ran, 1 unresolved subject, 2 usage,
  141 closed stdout), outside `sysexits.h` (64–78), and below the shell's
  signal range (129+), so it can never be confused with an answer.
- The shim runs the client as a **child**, not an `exec`: it has to outlive it
  in order to be the thing that falls back.
- The invariant that makes it safe is the one the client already had — nothing
  reaches stdout until a complete OK reply is in hand — so a decline at any
  point cannot duplicate or truncate output.

**The shim splits the argv.** `--client-*` options are recognised only before
the first tool argument and all carry the prefix, which is what lets the shim
keep them for the client and hand the rest to the cold path. Without the split
a fallback would have to re-run `--client-cold` against a JVM that does not
know the option.

Client options divide into **actions** (`--client-status`, `--client-stop`,
`--client-restart`), which need the client to exist, and the rest
(`--client-cold`, `--client-verbose`, `--client-limit`, `--client-timeout`),
which only tune a run that is happening anyway. An action the routing has
switched off is refused with exit 2; a non-action in the same position is moot.
**This distinction was found by the probe**: the first spelling of the split let
`$ISABELLE_QUERY_NO_SERVER=1 isabelle query --client-status` fall through to the
JVM with an empty argv, which printed the usage text and exited 0 — an answer to
a question nobody asked (§16g).

## The tripwire stays

`$ISABELLE_QUERY_SHIM_REENTRY` should now be unreachable: the client no longer
re-enters the tool name and the JVM path no longer routes anywhere. It is kept
because it was earned twice, the hard way, and because it is five lines against
445 live JVMs. §16e checks it still fires, so it cannot rot into a comment.

## The diagnosis this phase corrected

Through P7 the README, `server.scala`, `delegate.scala`, `query_client.py`, the
shim and `dev/BENCH.md` all said, in one form or another, that **the tool's
floor is the JVM** and that `isabelle query -V` costs ~850 ms because a JVM
takes that long to start. That is wrong, and it argued for the wrong design —
"avoid starting a JVM" rather than "avoid re-reading the corpus".

Measured on the reference machine (13th Gen i9-13950HX, Isabelle2025-2,
bundled Zulu JDK 21), 2026-08-29:

| what a cold `isabelle query` pays | ms |
|---|---:|
| `scala_build` — a second JVM, only to check whether the component is stale | ~405 |
| the `bin/isabelle` settings shell, sourced again by `isabelle java` | ~180 |
| **the JVM itself** | **~30** |
| Isabelle/Scala class loading, 53 jars | ~250 |
| the parse | 421 ms (28-theory entry) / 2755 ms (`src/HOL`) / ~19 s (AFP) |

Corroborating measurements:

```
java -version, bundled JDK 21          30 ms
isabelle getenv ISABELLE_HOME         185 ms   (pure bash; starts no JVM)
Query_Main direct, cached env, -V     345 ms   (no bash, no scala_build)
  ... same, trimmed 11-jar classpath  244 ms
  ... same, plus an AppCDS archive    155 ms
Query_Main direct, summary, 2 theories 576 ms
  ... plus an AppCDS archive          375 ms
```

And the server's own accounting, from `--client-status` on `Category3`:

```
index  Category3  28 theories, 1636 entries, 421 ms build / 1 ms recheck, 3 uses
```

So the warm path's 33 ms is **two** savings of very different size: ~865 ms of
process setup, and — the larger one, and the one that grows with the corpus —
the parse. A resident *index* is the point; a resident JVM is only how it is
held. No compiler removes the second: an AOT-compiled binary would still parse
1451 theories to answer `instances comm_monoid`.

`query_client.py` also claimed that resolving `$ISABELLE_HOME_USER` cost "a full
JVM boot". `isabelle getenv` is pure bash. The settings cache it justified is
still worth having, at 185 ms against the client's own ~33 ms budget, but for
the real reason.

## Coverage

`dev/p7probe.sh`: **81 checks, 0 failing** (baseline before this phase: 91). The
ten that went were the delegate's own — a second implementation of spawning,
staleness detection, environment forwarding, the bypass list and relative-token
handling — every one of which §9–§12 already checks against the implementation
that remains. Three checks are new and cover ground nothing did before:

- **§15a** the decline contract itself: exit 97, empty stdout, and a served
  verb that does *not* exit 97 (so the constant means something).
- **§15c** SIGPIPE on the **warm** path. It was only ever asked of the delegate,
  which means the client — now the only writer on the fast path that is not the
  engine — was never tested for it.
- **§16f/§16g** a client option with no client to run it, and a client action
  with the warm path switched off.

## Two things the probe taught about the environment

Both cost a debugging cycle and are worth writing down:

1. **`bin/isabelle` exports its own `$ISABELLE_TOOL`** when it dispatches a
   tool, so a stub passed from outside never reaches the client. The same is
   true of every component variable, `$ISABELLE_QUERY_BASE_HOME` included.
2. **`isabelle env CMDLINE` runs a command inside the settings environment**,
   which is where such an override does survive. §13 and §16f therefore run
   `lib/Tools/query` as a script under `isabelle env` rather than as
   `isabelle query`.

## What it left for the next phase

- **The cold path still pays ~405 ms for `scala_build`** on every invocation,
  to answer a question — "is this component stale?" — that a timestamp
  comparison answers in milliseconds.
- **AppCDS is measured but not shipped.** A 15 MB archive off the bundled JDK 21
  takes the direct-JVM `summary` from 576 ms to 375 ms with no new toolchain.
  It needs an invalidation rule keyed on the jar, and `-Xshare:auto` so a stale
  archive degrades rather than fails.
- **`Query_Delegate.absolutize` went with the file.** Nothing used it but the
  delegate; if a future caller needs argv path-rewriting, it is in the history
  at `git log --grep='\[p7-server\]'`.
