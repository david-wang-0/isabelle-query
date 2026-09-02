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

For a project you query often, the same spelling is already warm: a plain
`isabelle query` runs the thin client against a resident server (starting one
on first use, and falling back to the JVM tool wherever `python3` is missing),
so repeat questions come back in ~35 ms with nothing extra to type.
`--no-server` opts a single invocation out; `isabelle query --client-stop`
shuts the server down.

## What is identical

**Everything in the differential matrix**, which is the whole verb surface:
`summary`, `theory`, `defs`, `outline`, `enclosing`/`at`, `largest`, `lines`,
`grep`, `sorry`, `find`, `show`, `callers`, `callees`, `deps`, `uses`, `refs`,
`graph`, `unused`, `methods`/`method`, and every `shape` view. Same
subcommands, same flags, same abbreviations, same positional grammar, same
`-R/--root` on either side of the command name.

Same **output, byte for byte** — 2,149 (corpus × invocation) cases across seven
corpora are diffed on every change, plus the whole entry set and theory set
over the entire AFP and the entire distribution `src`. The oracle is the frozen
`src/isabelle_query/` tree, and the harness **refuses to run** against a `query`
of any other version.

Same **exit statuses**: `0` ran, `1` unresolved subject, `2` usage error or an
unreadable root, `141` a write failed because a downstream reader closed the
pipe. Same rule that a root which cannot be read reports on stderr and never
returns an empty success.

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
  `-h`/`--version` are never served). See `dev/BENCH.md`. The variables the tool reads
  (`$ISABELLE_QUERY_ROOT`, `$ISABELLE_LAYOUT_ROOT`,
  `$ISABELLE_QUERY_NAMESPACE`) are sent **with each request** and bound for it
  alone, so setting one in your shell means what it means cold — the server
  never consults its own environment for them.
- **`-V/--version`** reports `0.8.1-scala.0.1`, not `0.8.1`. Deliberately:
  the number in front of `-scala` names the upstream release whose contract
  this port matches, the `-scala` marker is what lets a script that pins a
  version tell the two tools apart, and the `MINOR.PATCH` after it is the
  port's own release counter for changes that leave that contract alone. A
  script that wants "the same contract as `0.8.1`" should compare the prefix
  up to `-scala`.

## What is deliberately different

Fifteen recorded divergences, each with the evidence in `dev/DIVERGENCES.md`
— and **most of them are closed**. Most were cases where the Python
implementation disagreed with Isabelle's own lexer or header parser; upstream
has since fixed six of those on its own side, from this port's findings, so the
two tools now agree on the whole AFP and the whole distribution `src` down to a
single record's span (D5).

Seven entries still describe something you would notice, and **D15 is the only
one that changes a byte of shared output on purpose**.

| | what changes for you |
|---|---|
| **D5** | A `\<comment>` whose cartouche opens on the next line is one comment. 1 record over both corpora, and the only one. |
| **D8** | On a closed stdout both tools exit `0` below a threshold and `141` above it; the thresholds differ (8 KB here, 64 KB there), and the oracle additionally exits `120` with `Exception ignored while flushing sys.stdout` for one shape of large answer. |
| **D9** | Two Python-only regex spellings — `(?P<n>…)` and `(?#…)` — are *rejected with a diagnostic*, not silently misread. Neither appears in the docs or tests. |
| **D11** | The method/attribute table is the **committed** one, not one resolved from whichever heaps happen to be built on your machine — so two machines give the same answer. Step down to the Pure floor happens by base logic, as before. |
| **D12** | `\w` is Java's, not Python's: `²`/`½` are word characters to the oracle and not here; a combining mark is one here and not there. 1 record in 306,525 over the whole AFP, in two derived count fields. **Not fixed** — see `dev/DIVERGENCES.md` for why the fix is a lexer-level change needing its own gate. |
| **D14** | For the visibility filter below, a name an entry *binds* (a datatype constructor, a `shows` conjunct, a `.simps`) counts as a declaration of that name. Upstream consults entries only, so `callers <constructor>` can differ on a corpus where the name is mentioned outside the binder's import cone. |
| **D15** | A theory a `ROOT` addresses by path — `theories "ex/Typechecking"` — is called `Typechecking`, which is what `Thy_Header.import_name` calls it and what `isabelle build` compiles. The Python tool prints `ex/Typechecking`, a name no Isabelle command answers to, and then `theory Typechecking` finds nothing. Here both spellings resolve: the leaf is the name, `ex/Typechecking` is the label. |

The rest are history, kept with their evidence: **D1** (a cartouche whose body
is a backslash), **D2** (`definition\<^marker>\<open>tag …\<close> name`),
**D3** (a quoted `keywords` kind), **D6** (a structural marker inside a name),
**D7** (the oracle's line index raising `TypeError` on a multi-name
`axiomatization`) and **D10** (`unused -r`'s cascade depths depending on the
hash seed) were all found here and are all fixed upstream now; **D4** (the
whole-corpus keyword union) turned out to be a weakness both tools share; and
**D13** — a citation is attributed only to a declaration the citing theory can
**see** — is a rule both tools now apply by default, with `--reach name` on
both to turn it off. Over the whole AFP that takes `callers mono` from 1,363
hits to 634, and `unused` honestly **grows**.

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
