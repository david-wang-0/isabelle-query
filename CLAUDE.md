# CLAUDE.md — orientation for isabelle-query

This is the every-session story: what this repo is, how it is laid out, and
where everything else is written down. It is deliberately short. `PLAN.md` is
normative for the rewrite; granular working rules live in `.claude/memory/`
(auto-loaded, and written for the *Python* project — where they conflict with
this file or `PLAN.md`, those win); the current session's in-flight state lives
in the gitignored `prompt.md` handoff.

## What this is

An **Isabelle component, in Isabelle/Scala**, that answers structural questions
about an Isabelle/Isar project by parsing its `.thy` sources — **no build, no
proof replay, no prover process.** Three front ends over one engine:

- **`isabelle query …`** — the command line: 22 verbs, 24 names.
- **the Isabelle/jEdit plugin** — find usages / definition / instantiations /
  code equations, quick-open, peek, and navigation exposure.
- **a warm server + thin client** — four commands folded into the stock
  `isabelle server`, and a non-JVM Python client, for when the JVM start-up is
  the whole cost.

It answers four kinds of question:

- **Structure** — *what is declared, and where*: `summary`, `theory`, `defs`,
  `find`, `show`, `largest`, `outline`, `grep`, `lines`, `sorry`, and
  `outline`'s inverse `enclosing`/`at` (which also names the enclosing
  locale/class target).
- **Usage** — *which facts cite which*: `callers`, `callees`, `deps`, `uses`,
  `refs`, `graph`, `unused`, `methods` (built on a call graph constructed only
  when needed). `deps`/`uses` read the `imports` clause; `refs` rolls the
  *citation* graph up by theory, so the two disagreeing is the signal.
- **Sites** — *where a name is used in a particular syntactic role*:
  `instances` (locale/class instantiation) and `codeqs` (code equations).
  Rewrite-only: no Python counterpart, so no differential coverage — they are
  gated by `dev/p6bprobe.sh` against hand-computed fixtures instead.
- **Shape** — *the proof-complexity shape of individual steps*: `shape`
  (`summary`/`steps`/`lemma`/`widest`/`census`).

`isabelle query -h` lists all subcommands; each takes `-h`. Locations and spans
share one grammar (`theory:line`, `theory:A..B`), so the tool's output is valid
input.

## Architecture

One component tree, chained through `etc/components`:

```
<repo root>                    ← ONE component; etc/components chains the two below
  query_base/                  ← the engine, the CLI, and the server commands
    etc/build.props            ← sources + services = Query_Tools, Query_Server_Commands
    src/*.scala
    lib/scripts/query_client.py   ← the non-JVM thin client
  jedit_query/                 ← the jEdit plugin (depends on query_base's jar)
  src/isabelle_query/          ← the PYTHON reference implementation (FROZEN — read only)
  tests/                       ← the Python test suite (reference for semantics; not run here)
  dev/                         ← differential harness, probes, benchmarks, phase notes
```

`query_base/src` is a strict module DAG — each imports only from earlier links:

    py_text → namespace → model → regions → entries → discovery → theory
      → output → render → usage_graph → commands → usage → sites
      → shape_data → shape → shape_cmds → cli → server → query_tool

- `py_text` — the Python-semantics primitives the port needs (`re` dialect
  bridging, `str.split`/`strip` edge cases). Nothing above it re-derives them.
- `namespace` — the committed method/attribute/keyword tables, as DATA, and the
  one seam that binds them. **Process-global mutable state**; see below.
- `model` — `Entry`, `Theory_Section`, `Call_Graph`, plus the two redacted
  views every scanner reads: `live_source` (noise blanked, terms kept — a
  citation scan must see `mono` in `lemma "mono f"`) and `outer_source` (terms
  blanked too — command position, where a declaration may start).
- `regions` / `entries` — one tokenizer pass over `Token.explode` feeds all of
  it. Nothing in the grammar uses indentation as evidence; block structure comes
  from the `begin`/`end` pair every target block shares.
- `discovery` / `theory` — ROOT/ROOTS, sessions, the in-entry import closure,
  and the root-wide custom-keyword union built before any body is parsed.
- `usage_graph` / `usage` / `sites` / `shape` — the analyses.
- `cli` — the argument grammar and `cmd_*` dispatch. `CLI.run_result` is the
  whole CLI minus the two things only a process may do (write to file
  descriptors, exit); `CLI.run` is that plus those two. The server uses the
  former, which is why there is exactly one dispatch path.
- `server` — the warm index and the four `Server.Commands` entries.

**Discovery loads what `isabelle build` compiles:** each session's ROOT-declared
theories *plus the transitive closure of their in-entry `imports`*. Imports of
other AFP entries and of the base library (`HOL-*`, `Pure`) are not followed;
orphan `.thy` files are excluded.

**`Namespace` is process-global and mutable, and that is the one shared-state
hazard in the tree.** It decides whether `auto` is a proof method or a fact, it
is bound per project, and `shape census` binds the broad HOL union
unconditionally by design. Every resident host therefore has to rebind: the
jEdit plugin serialises engine calls through one worker thread
(`Query_Index.with_namespace`), and the server restores the committed default
before every request under one lock (`Query_Server.run`). A new resident caller
must do the same or it will read whatever the last one left.

## The dev loop

**Never register a work-in-progress component into the real
`$ISABELLE_HOME_USER`** — a compile error there breaks `isabelle jedit`
start-up for every session. Develop against the gitignored scratch user home:

```sh
FORK=$(git rev-parse --show-toplevel)
USER_HOME="$FORK/.dev" isabelle components -u "$FORK"      # once
USER_HOME="$FORK/.dev" isabelle scala_build                # compile dirty modules
USER_HOME="$FORK/.dev" isabelle query …                    # run the tool
```

`ISABELLE_HOME_USER` cannot be overridden from the environment; `USER_HOME` can,
and `ISABELLE_HOME_USER` derives from it. Registering into the real home is a
release step, from a green tree only (`dev/P5-STATUS.md` §"Registering it for
real").

Plugin changes also need the dynamic shim jar, which `scala_build` does not
build:

```sh
USER_HOME="$FORK/.dev" isabelle scala -e '{ isabelle.Isabelle_System.init();
  isabelle.Scala_Project.plugins.foreach(p => p.context().build()) }'
```

`### Missing Isabelle component:` on stderr is pre-existing noise.

## Verification

There is no pytest on the Scala side. Correctness comes from four harnesses,
all reading corpora from `$QUERY_TEST_AFP` / `$QUERY_TEST_DISTRO` (see
`.dev/corpora.env`) and none from a hard-coded path:

| harness | what it establishes |
|---|---|
| `dev/difftest.sh` | 2,086 (corpus × invocation) cases against the Python oracle: stdout byte-for-byte, exit statuses, stderr presence |
| `dev/entrydiff.sh` | the entry set and theory set over the whole AFP and the whole distribution `src` |
| `dev/p5probe.sh`, `p6probe.sh`, `p6bprobe.sh` | the jEdit plugin, without a display |
| `dev/p7probe.sh` | the warm server and the thin client |

Every probe **refuses (exit 2) without its corpora** rather than skipping into
a green. Every difference from the oracle is recorded in `dev/DIVERGENCES.md`
with evidence; the list stays near-empty and "hard to match" is not a reason.

Correctness is judged against **Isabelle semantics, not prior behaviour**:
hand-compute a fixture value first, then make the code match.

## The document map

| doc | what |
|---|---|
| `PLAN.md` | **normative** for the rewrite: why Scala, the parity contract, the phases |
| `README.md` | user-facing entry point: what the tool is, the verbs, install. Kept **short** — the GitHub landing page |
| `MIGRATING.md` | user-facing: coming from the Python `query` |
| `SCANNING.md` | user-facing: how a project is read — live-text scanning, locale scope, method-vs-fact names, session discovery |
| `METRICS.md` | user-facing: the `shape` command reference and the `M1`–`M6` table |
| `CONTRIBUTING.md` | **normative**: the CLI contract, the verification habits, where design decisions are recorded |
| `query_base/src/shape.scala` + the Python `shape.py` | **authoritative** metric definitions |
| `dev/DIVERGENCES.md` | every deliberate difference from the oracle, with its evidence |
| `dev/P1..P7-STATUS.md` | what each phase established, and what it left for the next |
| `dev/BENCH.md` | the three-column benchmark and how it was taken |
| `todo.md` | **open work only** — not a changelog |
| `.claude/memory/` | granular working rules (written for the Python project) |
| `prompt.md` | **gitignored** end-of-session handoff |

Design decisions are recorded **in commit messages**, cross-referenced by a
stable `[tag]`: `git log --grep='\[p7-server\]'`. There is no changelog and no
design-decision archive — a summary of a commit drifts from it, and the commit
does not.

## Working essentials

- **Corpora.** AFP checkout and distribution `src` come from
  `.dev/corpora.env`; enumerate a corpus's sessions via
  `Discovery.iter_sessions`, never a bare walk.
- **Never edit `query_base/src` while a difftest is running** — the harness
  rebuilds and would test a tree that no longer exists.
- **Never launch jEdit, never build heaps, never touch the real
  `~/.isabelle`.** Plugin verification is `dev/p[56]*probe.sh` plus a manual
  checklist for the user.
- **Reuse over re-roll.** Corpus tooling goes through `Theory.plan` /
  `CLI.Session` / `shape`'s primitives; don't write a second parser.
- **Commits.** Small, single-concern, frequent — commit often, don't push
  unless asked. Write bodies to a `.commit-msg` scratch file in the working
  tree (not `/tmp`, which is gated); `rm` it after. Trailer: name the model
  that actually did the work, e.g.
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
  (or `Claude Opus 5` when an Opus agent authored the change).
- **No absolute personal paths** (`/home/…`, `~/…`, `file://…`) in any
  committed file. Grep the diff before every commit.

## Release status

`pyproject.toml` tracks the **Python** package (0.7.0, frozen). The Scala
tool's version is `CLI.version` — currently **0.8.0-scala**, deliberately
distinguishable so a script can tell the two apart. Versioning is alpha: a
breaking change takes the minor slot, patch bumps stay additive.

There is no changelog, by the same reasoning that keeps design decisions in
commit messages.

## Credit

Two layers, credited separately. The original Python tool (the frozen
`src/isabelle_query/` tree and everything inherited from upstream) is "By
András Salamon, with Claude Opus 4.6, 4.7, 4.8, and 5." The Scala component,
the `instances` and `codeqs` verbs, the jEdit plugin and its IDE features,
the warm server and thin client, and `demo/` are "By David Wang, with Claude
Fable 5 and Claude Opus 5." Do not credit new artifacts or new features to
the upstream author.
