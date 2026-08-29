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

For a project you query often, the warm client is the same command line again,
at a fraction of the cost:

```sh
python3 <checkout>/query_base/lib/scripts/query_client.py -R AFP/thys callers foo
```

Alias it to `query` if you like — it takes the same arguments, prints the same
bytes, and returns the same exit status.

## What is identical

**Everything in the differential matrix**, which is the whole verb surface:
`summary`, `theory`, `defs`, `outline`, `enclosing`/`at`, `largest`, `lines`,
`grep`, `sorry`, `find`, `show`, `callers`, `callees`, `deps`, `uses`, `refs`,
`graph`, `unused`, `methods`/`method`, and every `shape` view. Same
subcommands, same flags, same abbreviations, same positional grammar, same
`-R/--root` on either side of the command name.

Same **output, byte for byte** — 2,086 (corpus × invocation) cases across seven
corpora are diffed on every change, plus the whole entry set and theory set
over the entire AFP and the entire distribution `src`.

Same **exit statuses**: `0` ran, `1` unresolved subject, `2` usage error or an
unreadable root, `141` closed stdout. Same rule that a root which cannot be
read reports on stderr and never returns an empty success.

Same **semantics**: live-text scanning, locale scope, session discovery, the
method-vs-fact router, the `M1`–`M6` metric definitions. `SCANNING.md` and
`METRICS.md` describe both implementations.

## What is new

- **`instances NAME`** — where a locale or class is instantiated
  (`instantiation`, `instance`, `interpretation`, `global_interpretation`,
  `sublocale`).
- **`codeqs NAME`** — declared code-equation sites of a constant: `[code]` and
  kin, `declare c [code …]`, `lemmas … [code] = …`, and the constant's own
  `fun`/`primrec`/`definition` default equations.
  Read the caveats in `README.md` before trusting a short answer — mixfix
  notation defeats the head rule, and it under-reports there.
  Each row is `LOCUS  NAME  KIND  source`; `--sorts` adds the sort, arity or
  signature **the source writes** at that site. No prover runs, so no type is
  ever inferred: a site that writes none shows none.
- **An Isabelle/jEdit plugin** — find usages, find definition, find
  instantiations, find code equations, search by name, quick-open, peek, and
  toolbar/keyboard exposure for Isabelle's existing navigate-back/forward
  stacks.
- **A warm server** — four commands added to the stock `isabelle server`, plus
  a stdlib-only Python client, and `isabelle query` itself finds that server
  and uses it (`--no-server`, or `$ISABELLE_QUERY_NO_SERVER=1`, keeps the query
  in this process; stdin runs, the development dumps, `shape census` and
  `-h`/`--version` never delegate). See `dev/BENCH.md`. The variables the tool reads
  (`$ISABELLE_QUERY_ROOT`, `$ISABELLE_LAYOUT_ROOT`,
  `$ISABELLE_QUERY_NAMESPACE`) are sent **with each request** and bound for it
  alone, so setting one in your shell means what it means cold — the server
  never consults its own environment for them.
- **`-V/--version`** reports `0.8.0-scala`, not `0.7.0`. Deliberately: a script
  that pins a version must be able to tell the two apart.

## What is deliberately different

Twelve recorded divergences, each with the evidence in `dev/DIVERGENCES.md`.
Eleven of them are cases where the Python implementation disagrees with
Isabelle's own lexer or header parser, and reproducing it would mean shipping a
known bug. **No entry is ever lost**: the set of declarations the oracle
reports is a strict subset of what this engine reports, on both corpora.

| | what changes for you |
|---|---|
| **D1** | A cartouche whose body is a single backslash no longer swallows the rest of the file. 1,867 more records over 5 AFP entries. |
| **D2** | `definition\<^marker>\<open>tag …\<close> name` is a declaration. 751 more records in the distribution, 16 in the AFP. |
| **D3** | `keywords "cmd" :: "kind"` with a *quoted* kind is read as that kind. 37 more records over 2 entries. |
| **D4** | The cross-session keyword union differs when the whole AFP is one root; each entry read on its own is byte-identical. 16 records over 4 entries. |
| **D5** | A `\<comment>` whose cartouche opens on the next line is a comment. 1 record. |
| **D6** | A structural marker inside a name is a shared weakness, **not** fixed — both implementations do the same thing, and it is recorded so nobody thinks it was checked. |
| **D7** | `grep`, `sorry` and most of the usage family now run at all on a theory with a multi-name `axiomatization`; the oracle raises `TypeError` there. |
| **D8** | A closed stdout is always exit `141`. The oracle sometimes says `120`, depending on whether the output fit in the pipe buffer. |
| **D9** | Two Python-only regex spellings are *rejected with a diagnostic*, not silently misread. Neither appears in the docs or tests. |
| **D10** | `unused -r`'s `[cascade depth N]` marker is not reproducible in the oracle even against itself; ours is deterministic. |
| **D11** | The method/attribute table is the **committed** one, not one resolved from whichever heaps happen to be built on your machine — so two machines give the same answer. Step down to the Pure floor happens by base logic, as before. |
| **D12** | `\w` is Java's, not Python's: `²`/`½` are word characters to the oracle and not here; a combining mark is one here and not there. 1 record in 306,525 over the whole AFP, in two derived count fields. **Not fixed** — see `dev/DIVERGENCES.md` for why the fix is a lexer-level change needing its own gate. |

## Things that move

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
