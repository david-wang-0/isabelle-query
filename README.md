# isabelle-query

An **Isabelle component** for querying Isar source: declarations, callers and
callees, theory dependencies, locale instantiations, code equations, outstanding
`sorry`s, unused entries, and proof-shape metrics. It uses Isabelle's own Scala
lexer and header/session parsers; queries need **no theory build, proof replay,
or prover process**.

`isabelle query` reads **saved source**, reusing parsed sections after a
staleness check. The jEdit plugin also reads dirty buffers. The CLI can reuse a
compatible jEdit/PIDE host or a manually managed server; an automatically
started fallback lasts only for the client invocation.

Parsing is source-only: theories may be broken, unbuilt, or midway through a
refactor. Querying does not require a successful Isabelle build or semantic
markup. Source snapshots are immutable and preserve exact text; large sources
are stored in independent compressed blocks and decoded only where needed.

## Install

```sh
isabelle components -u <this checkout>
```

This registers the engine/CLI (`query_base`) and jEdit plugin (`jedit_query`).
The CLI works immediately. The plugin builds at jEdit startup; pick it up at
the next coordinated restart. Python 3 enables the thin client; without it,
the CLI uses a fresh JVM. No external Scala dependencies are needed.

To remove the component and its otherwise leftover plugin jar:

```sh
isabelle components -x <this checkout>
rm -f "$ISABELLE_HOME_USER/jedit/jars/isabelle_jedit_query.jar"
```

### Optional PIDE MCP tool

The adapter targets external `isabelle-pide-mcp` revision
`be9fdcb52c35bac9ad6df6ddc49c7af803e16b8e` on `Isabelle2025-2`. With that
component installed and registered:

```sh
isabelle components -u <this checkout>/pide_mcp_query
isabelle scala_build
```

The adapter is excluded from the root component list, so ordinary installation
needs no PIDE MCP. It adds a `query` tool accepting CLI arguments and optional
request-local environment:

```json
{
  "argv": ["-R", "/path/to/project", "callers", "my_fact"],
  "env": {"ISABELLE_QUERY_NAMESPACE": "committed"}
}
```

`env` accepts only `ISABELLE_LAYOUT_ROOT`, `ISABELLE_QUERY_ROOT`, and
`ISABELLE_QUERY_NAMESPACE`. Results contain `exit`, `stdout`, and `stderr`;
CLI failures preserve their exit code, while malformed requests are MCP tool
errors. No optional tool annotations are registered.

Root selection uses explicit `-R/--root`, then request root environment, then
one unambiguous canonical directory across currently running PIDE sessions.
Canonical aliases count once; the session set is read on every call. Supply a
root when there are zero or multiple directories; an explicit root works
without a prover session. Use an absolute path if the MCP working directory is
unknown. Commands that actually consume stdin return exit `2`; an inert `-`
retains its normal CLI meaning.

The tool and the adapter's `pide_mcp` launcher share one bounded query cache.
The launcher preserves upstream arguments and stdio and advertises a terminal
query endpoint even with no prover sessions. Deregister `pide_mcp_query` before
the next PIDE start to restore the upstream launcher.

## Commands

Use `-h` on each command for its flags. `-R/--root` selects a session directory;
otherwise the CLI discovers the nearest project at or above the working directory.

```sh
isabelle query summary                 # theory overview; --by-session for a corpus
isabelle query theory MyTheory         # entries; --names for names only
isabelle query find <regex>            # names; --statement for statements
isabelle query show <name>             # declaration and body
isabelle query outline [THEORY]        # sections and entries
isabelle query enclosing FILE:LINE     # owning entry/proof block (alias: at)
isabelle query largest [-N n]          # biggest entries by span
isabelle query grep <regex> [PATH...]  # live source text
isabelle query lines FILE A..B         # numbered source lines
isabelle query defs <theory>           # definitions
isabelle query sorry                   # outstanding sorry sites
isabelle query callers <name> [-r]     # references to a name; -r is transitive
isabelle query callees <name> [-r]     # references from a name
isabelle query deps <theory> [-r]      # imports; uses is the reverse direction
isabelle query refs <theory>           # citations grouped by owning theory
isabelle query graph [citation|imports] # JSON; -f dot for Graphviz
isabelle query unused                  # unused-entry analysis
isabelle query methods                 # proof-method frequencies (alias: method)
isabelle query instances <loc> [-r]    # instantiation sites; -r walks the hierarchy
isabelle query codeqs <const>          # declared code-equation sites
isabelle query shape <view>            # summary|steps|lemma|widest|census
```

Locations and spans compose: `theory:line` or `theory:A..B` from one command
can be pasted into another. Theory names are directory-qualified only as far
as needed to disambiguate them.

```sh
isabelle query -R AFP/thys callers metric_domain_tfin_def
isabelle query -R AFP/thys enclosing Tfin:88..140
isabelle query -R AFP/thys grep simp Tfin.thy:88..140
```

See the [guided tour](demo/DEMO.md), [cheatsheet](demo/CHEATSHEET.md), and
[proof-shape reference](METRICS.md).

### Source semantics and limits

Only live Isar text contributes declarations and citations: comments, text
blocks, and ML bodies do not. Layout and indentation do not determine command
boundaries. Discovery follows session-declared theories and their in-entry
imports; see [SCANNING.md](SCANNING.md).

`instances` and `codeqs` report **declared source sites**, not the processed
setup shown by a prover's `print_interps` / `print_codesetup`. Rows have the
form `LOCUS NAME KIND source`; unnamed sites use `?`. `--sorts` adds only the
sort, arity, or signature written at the site, never inferred types.

`instances -r` also lists the sites of everything that **extends** the subject —
`class X = NAME + …`, `locale X = … NAME …`, `subclass`, `instance X ⊆ NAME`,
`sublocale` — transitively, and adds a `VIA` column naming which of them each
row writes. `nat` instantiates `comm_monoid_diff`, never `ab_semigroup_add` by
name, so it appears only under `-r`. Without the flag the listing is the direct
sites alone.

**`codeqs` under-reports when mixfix notation hides the statement's head
symbol.** Check source with `grep` if an answer looks short. Neither finder
separates same-named declarations that are visible within a theory. Visibility
requires a declaration in that theory or its transitive imports within the
scanned project; heap-only declarations are not discovered.

This import-closure filter also applies to `callers`, `callees`, `refs`,
`unused`, and `graph citation`. Their `--reach name` option restores name-only
attribution; `closure` is the default. `instances` and `codeqs` return exit `1`
for a subject that is not a declared locale/class or constant respectively,
and exit `0` for a valid subject with no sites.

## The jEdit plugin

Use the theory-buffer context menu or the **Isabelle Query** dockable for
usages, definitions, instantiations, code equations, and search by name.
Quick-open, peek definition, and toolbar/keyboard access to Isabelle's own
back/forward navigation are also available. Name resolution uses the source
index without waiting for the prover.

Results show line previews grouped by file; instantiations and code equations
also group by directory. Successive result sets remain visible. Configure
click actions in **Plugin Options → Isabelle Query**; by default double-click
or Enter opens in the current pane, shift-click in a new pane, alt-click peeks,
and middle-click opens a new view. Single-click does nothing.

Repeating a callers query refreshes that identifier's existing group, including
changed, empty, and failed results, while unrelated groups stay visible.
**Delete** removes the selected row. Deleting an entire result set cancels its
pending refresh; a deleted child group or hit may reappear when that refresh
finishes. **Clear** removes every result and cancels all pending results.

The per-project UI index reads dirty buffers and checks saved-file mtimes. It
refuses oversized projects rather than returning a partial index. Terminal
queries hosted by this same JVM still read saved files, not editor buffers.

## The warm server

The thin client first looks for a compatible jEdit or PIDE query endpoint,
then a dedicated server. If none is usable, it starts a fallback whose lifetime
is tied to the client process by a private stdin pipe: **the fallback exits
when that CLI invocation ends**. Reused jEdit/PIDE hosts and manually managed
dedicated servers remain running. Repeated commands therefore reuse indexes
only while their host survives; attaching does not guarantee its lifetime.
The query transport reconnects once on failure before emitting output.

For cross-command caching without jEdit/PIDE, run
`isabelle query_server -n isabelle_query` in a separate foreground terminal.
It serves other clients until stdin closes (Ctrl-D) or you stop it.

Discovery uses Isabelle's private `servers.db` and authenticated loopback
protocol. Embedded endpoints expose `query_version`, `query_open`, `query_run`,
`query_close`, and `query_cache`; they cannot start prover sessions or accept
stock-server shutdown. The client skips dead, stale, or incompatible shared
hosts and never restarts a shared JVM. It does not select unrelated registered
servers or stop an unverifiable endpoint to make room for a fallback.

`ISABELLE_QUERY_CLIENT_SERVER` explicitly selects one registry name, bypassing
automatic discovery; the default dedicated name is `isabelle_query`.
`ISABELLE_QUERY_HOST=0` disables embedded advertisement. Updated components
must be loaded at the host's next coordinated restart.

```sh
isabelle query --client-status       # selected host, PID, retention, open indexes
isabelle query --client-cache off    # clear indexes and disable retention
isabelle query --client-cache on     # reenable bounded retention
isabelle query --client-cache clear  # clear indexes, keep retention policy
isabelle query --client-stop         # stop a dedicated server, never an embedded host
isabelle query --client-limit 8000 summary # request theory limit; 0 disables it
isabelle query --no-server summary   # fresh JVM, no client or server
```

Cache controls affect all query clients of the **selected host** until changed
or that JVM exits; they do not configure future fallback processes. Host
startup environment likewise cannot be changed from a later terminal.

- `ISABELLE_QUERY_SERVER_LIMIT`: per-request admission limit, default **4000
  theories**. Oversized requests are refused, never truncated; transient runs
  obey the same limit. Use `--client-limit` to override it for a request.
- `ISABELLE_QUERY_SERVER_CACHE_MB`: aggregate idle-retention budget, default
  **128 MiB**. Accepts nonnegative integer MiB; unset, empty, negative,
  nonnumeric, or overflowing values use the default. `0` disables retention.
  Oversized roots still work transiently in root-addressed queries.
- The budget counts logical UTF-8 bytes of normalized retained theory source,
  once per section; it is **neither a heap nor an RSS limit** and does not
  measure compressed payloads, decoded views, indexes, graphs, or refresh
  overlap. Eviction removes whole indexes in least-recently-used order, with a
  separate 256-entry cap.
  `query_open` refuses zero-budget or individually oversized roots. Explicit
  handles expire on eviction/close and must be reopened; the thin client's
  root-addressed requests recreate indexes as needed.

Before answering, the server checks `.thy`, `ROOT`, and `ROOTS` file metadata;
changed theories are reparsed. **A stat check is not a content hash**, and
unsaved buffers remain invisible to terminal queries.

Stdin requests, `dump-*`, the `shape` census view, help/version, and ambiguous
relative file/directory arguments use the cold JVM route. Absolute paths can
be served; `-R` is resolved by the client. A transport decline emits no output
before the shim falls back, preserving the CLI answer and exit status.

Additional switches: `ISABELLE_QUERY_NO_CLIENT=1` bypasses the thin client;
`ISABELLE_QUERY_NO_SERVER=1` is equivalent to `--no-server`;
`ISABELLE_QUERY_NO_CDS=1` disables the cold AppCDS archive;
`ISABELLE_QUERY_ALWAYS_BUILD=1` always runs `scala_build`.
`ISABELLE_QUERY_ROOT`, `ISABELLE_LAYOUT_ROOT`, and `ISABELLE_QUERY_NAMESPACE`
travel with each request and are bound only for that request.

For measured latency, memory use, routes, and methodology, see
[dev/BENCH.md](dev/BENCH.md).

## Exit status

`0`: command ran (including an empty search); `1`: unresolved subject;
`2`: bad usage or unreadable root; `141`: a failed write after a downstream
reader closes the pipe. A small answer may finish writing before `head`
closes and exit `0`. Diagnostics go to stderr; count output stays usable.
For example, `find zzz -c` prints `0` and succeeds, while an unknown subject
in `callees zzz -c` reports an error and exits `1`. An unreadable root never
becomes an empty success.

## Reference and development

The frozen Python original, [ott2/isabelle-query](https://github.com/ott2/isabelle-query),
lives in `src/isabelle_query/` with its reference tests. Routine validation is
`make test`; full upstream compatibility is `make compat`. See
[tests/README.md](tests/README.md) for test accounting, gates, and evidence.

| Reference | Contents |
|---|---|
| [MIGRATING.md](MIGRATING.md) | Python compatibility and migration differences |
| [SCANNING.md](SCANNING.md) | declarations, citations, locale scope, sessions |
| [METRICS.md](METRICS.md) | proof-shape metrics and JSONL schema |
| [CONTRIBUTING.md](CONTRIBUTING.md) | CLI contract and contribution rules |
| [PLAN.md](PLAN.md) | normative rewrite plan |
| [dev/DIVERGENCES.md](dev/DIVERGENCES.md) | differences from the Python oracle |
| [dev/BENCH.md](dev/BENCH.md) | benchmark results and methodology |

## Authors & license

The original (Python) isabelle-query is by András Salamon, with Claude Opus
4.6, 4.7, 4.8, and 5 — see [upstream](https://github.com/ott2/isabelle-query),
kept in-tree under `src/isabelle_query/` as the frozen reference
implementation. The Scala rewrite, the `instances` and `codeqs` verbs, the
jEdit plugin and its IDE features (find usages/definition/instantiations/code
equations, quick-open, peek, search-by-name, navigation exposure), the warm
server and thin client, and the demo project are by David Wang, with Claude
Fable 5 and Claude Opus 5. P13 contributions are by GPT-6 Astra, Claude Fable
5.1, and GPT-5.6 Sol. [MIT](LICENSE).
