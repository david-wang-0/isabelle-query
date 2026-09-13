# Migrating from the Python `query`

The Scala tool is a drop-in replacement for the Python `isabelle-query`
([ott2/isabelle-query](https://github.com/ott2/isabelle-query)). If you have
been using `query`, everything you know still applies; what changes is how you
install it and how you type its name.

## The three-line version

```sh
isabelle components -u <this checkout>   # install (once)

query -R AFP/thys callers foo            # before
isabelle query -R AFP/thys callers foo   # after
```

A plain `isabelle query` uses a thin Python client to find a compatible jEdit,
PIDE, or dedicated query host. If none is usable, its automatic fallback is
owned by the client through a private stdin pipe and exits when that invocation
ends. Reused jEdit/PIDE hosts and manually managed dedicated servers remain
running; attaching does not guarantee their lifetime. Query transport
reconnects once on failure before emitting output. Repeated commands reuse
indexes only while a host survives; see [benchmarks](dev/BENCH.md) for timings.

Without `python3`, the CLI uses a fresh JVM. `--no-server` selects that route
explicitly; `--client-stop` stops a dedicated server, never an embedded host.
Terminal queries read saved files even inside jEdit; the plugin's UI also reads
dirty buffers. See [host and cache controls](README.md#the-warm-server).

The Scala engine remains source-only. It can query broken, unbuilt, and
mid-refactor theories, preserves the existing context defaults, and keeps exact
snapshots when files or dirty buffers change. Large sources use compressed
immutable blocks internally; this does not change excerpts or command output.

## What is identical

**Everything in the differential matrix**, which is the whole verb surface:
`summary`, `theory`, `defs`, `outline`, `enclosing`/`at`, `largest`, `lines`,
`grep`, `sorry`, `find`, `show`, `callers`, `callees`, `deps`, `uses`, `refs`,
`graph`, `unused`, `methods`/`method`, and every `shape` view. Same
subcommands, same flags, same abbreviations, same positional grammar, same
`-R/--root` on either side of the command name.

The compatibility harness compares **output byte for byte** and exit statuses
across seven corpora, plus whole entry and theory sets over AFP and the
Isabelle distribution. The oracle is the frozen `src/isabelle_query/` tree;
the harness rejects an unexpected oracle identity. Routine checks use
`make test`; upstream version changes trigger full compatibility, also available through
`make compat`. See [tests/README.md](tests/README.md) for accounting and gates,
and [recorded differences](dev/DIVERGENCES.md) for exceptions.

Same **exit statuses**: `0` ran, `1` unresolved subject, `2` usage error or an
unreadable root, `141` a write failed because a downstream reader closed the
pipe. Same rule that a root which cannot be read reports on stderr and never
returns an empty success.

Same **semantics**: live-text scanning, locale scope, session discovery, the
method-vs-fact router, the `M1`–`M6` metric definitions.
[SCANNING.md](SCANNING.md) and [METRICS.md](METRICS.md) describe both implementations.

## What is new

- **`instances NAME`** — where a locale or class is instantiated
  (`instantiation`, `instance`, `interpretation`, `global_interpretation`,
  `sublocale`); with **`-r`** also the sites of everything that *extends* the
  subject, transitively (`class X = NAME + …`, `locale X = … NAME …`,
  `subclass`, `instance X ⊆ NAME`, `sublocale`), each row carrying a `VIA`
  column that names which member of that closure the line writes. `nat`
  instantiates `comm_monoid_diff` and never writes `ab_semigroup_add`, so it
  is reported for the latter only under `-r`.
- **`codeqs NAME`** — declared code-equation sites of a constant: `[code]` and
  kin, `declare c [code …]`, `lemmas … [code] = …`, and the constant's own
  `fun`/`primrec`/`definition` default equations.
  Read the [source caveats](README.md#source-semantics-and-limits): mixfix
  notation hides the head symbol and can cause under-reporting.
  Each row is `LOCUS  NAME  KIND  source`; `--sorts` adds the sort, arity or
  signature **the source writes** at that site. No prover runs, so no type is
  ever inferred: a site that writes none shows none.
- **An Isabelle/jEdit plugin** — find usages, find definition, find
  instantiations, find code equations, search by name, quick-open, peek, and
  toolbar/keyboard exposure for Isabelle's existing navigate-back/forward
  stacks. **Find instantiations (transitive)** is the menu's form of
  `instances -r`, and its rows name the member of the closure they write the
  same way the CLI's `VIA` column does.
- **Resident query hosts and cache controls** — shared jEdit/PIDE endpoints,
  dedicated servers, and a stdlib-only Python client. Retention is bounded and
  controlled per host; see [README.md](README.md#the-warm-server), including
  which requests use the cold route. `ISABELLE_QUERY_ROOT`,
  `ISABELLE_LAYOUT_ROOT`, and `ISABELLE_QUERY_NAMESPACE` travel with each
  request and are bound for it alone.
- **Callers refresh and result removal** — repeating a callers query refreshes
  its existing scoped group. Delete removes the selected row; deleting an
  entire result set cancels its pending refresh, while a deleted child group or
  hit may reappear when that refresh finishes. Clear removes all results and
  cancels all pending results.
- **`-V/--version`** reports `0.8.1-scala.0.3`, not `0.8.1`. Deliberately:
  the number in front of `-scala` names the upstream release whose contract
  this port matches, the `-scala` marker is what lets a script that pins a
  version tell the two tools apart, and the `MINOR.PATCH` after it is the
  port's own release counter for changes that leave that contract alone. A
  script that wants "the same contract as `0.8.1`" should compare the prefix
  up to `-scala`.

## What is deliberately different

[dev/DIVERGENCES.md](dev/DIVERGENCES.md) records the differences and their
evidence. The migration-relevant cases are:

| | what changes for you |
|---|---|
| **D5** | A `\<comment>` whose cartouche opens on the next line is one comment. 1 record over both corpora, and the only one. |
| **D8** | On a closed stdout both tools exit `0` below a threshold and `141` above it; the thresholds differ (8 KB here, 64 KB there), and the oracle additionally exits `120` with `Exception ignored while flushing sys.stdout` for one shape of large answer. |
| **D9** | Two Python-only regex spellings — `(?P<n>…)` and `(?#…)` — are *rejected with a diagnostic*, not silently misread. Neither appears in the docs or tests. |
| **D11** | The method/attribute table is the **committed** one, not one resolved from whichever heaps happen to be built on your machine — so two machines give the same answer. Step down to the Pure floor happens by base logic, as before. |
| **D12** | `\w` is Java's, not Python's: `²`/`½` are word characters to the oracle and not here; a combining mark is one here and not there. 1 record in 306,525 over the whole AFP, in two derived count fields. **Not fixed** — see `dev/DIVERGENCES.md` for why the fix is a lexer-level change needing its own gate. |
| **D14** | For the visibility filter below, a name an entry *binds* (a datatype constructor, a `shows` conjunct, a `.simps`) counts as a declaration of that name. Upstream consults entries only, so `callers <constructor>` can differ on a corpus where the name is mentioned outside the binder's import cone. |
| **D15** | A theory a `ROOT` addresses by path — `theories "ex/Typechecking"` — is called `Typechecking`, which is what `Thy_Header.import_name` calls it and what `isabelle build` compiles. The Python tool prints `ex/Typechecking`, a name no Isabelle command answers to, and then `theory Typechecking` finds nothing. Here both spellings resolve: the leaf is the name, `ex/Typechecking` is the label. |

Closed parser and analysis differences remain documented there. D13's import
visibility filter is now shared: a citation is attributed only to a declaration
the citing theory can see. Both tools default to `--reach closure`; use
`--reach name` to restore name-only attribution.

## Things that move

- **An unresolvable subject now answers on stderr with exit `1`, and a count
  mode prints `0`.** This is the Python tool's own 0.8.1 contract, which the
  port matches; against **0.7.0** — and against earlier builds of this port —
  it is a **breaking change**, because both used to print the diagnostic on
  stdout and exit `0`:

  ```
  # 0.7.0                              # 0.8.1 and this port
  $ query callees zzz -c               $ isabelle query callees zzz -c
  'zzz' not found in the entry index.  isabelle query: 'zzz' is not in the entry index
  $ echo $?                            $ echo $?
  0                                    1
  ```

  Nine verbs: `theory`, `defs`, `outline`, `deps`, `uses`, `refs`, `callees`
  (with or without `-r`), `callers -r`, `methods NAME`. If you grep **stdout**
  for the old sentence, read stderr and the exit status instead. `callers NAME`
  without `-r` is *not* in the list — it scans text, so zero mentions is an
  honest `0` — and `find zzz -c` / `show zzz -c` now print `0` rather than
  `No entries matching 'zzz'.`, with `--names` printing nothing at all.
- **A printed `theory:line` is now qualified as far as it needs to be.** Over a
  corpus 498 AFP theory names name more than one theory, so `largest`,
  `enclosing`, `callers`, `methods`, `grep`, `sorry` and the `shape` views print
  `Virtual_Substitution/QE:3495` rather than an ambiguous `QE:3495`. A name used
  once is unchanged, so a single-session run looks exactly as it did. Both
  spellings resolve, and the qualified one is the one a locus pastes back as.
  This is the Python tool's own 0.8.1 behaviour; against **0.7.0** and against
  earlier builds of this port it moves the first column of those verbs, so a
  script that split on `:` still works and one that compared the theory field
  against a bare name needs the suffix rule instead.
- **`$ISABELLE_QUERY_REACHABILITY` is gone**, replaced by
  `--reach {closure,name}` on `callers`, `callees`, `refs`, `unused` and
  `graph`. It never existed in the Python tool — it was this port's own channel
  from P7c to P9 — and the flag is what upstream 0.8.1 spells, so the two tools
  now take the same word in the same place. `closure` is the default on both, so
  only a caller that set the variable to `off` has anything to change:
  `--reach name`, per invocation. Nothing reads the environment for it any more,
  on the CLI, over the warm server, in the plugin, or from a library call.
- **Installation** is `isabelle components -u`, not `pip install`. There is no
  PyPI package for the Scala tool and no Python runtime dependency.
- **The program name** in diagnostics is `isabelle query`, not `query` or
  `isabelle-query`.
- **Help text** wording differs. Every flag that existed still exists; the
  prose around it was rewritten.
- **`dump-entries` / `dump-theories` / `dump-imports`** exist as hidden
  development commands (the differential harness needs them). They are not a
  user interface and their format is not stable.

## The Python tool is still here

`src/isabelle_query/` and `tests/` are kept in this repository, frozen, as the
reference implementation and the test oracle. They are read, never edited. If
you want the Python tool itself, install it from PyPI — this checkout is not a
substitute for it.
