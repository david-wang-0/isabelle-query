# query

A command-line tool for **querying the live theory index of an
Isabelle/Isar project** — its entries, call graph, theory dependencies,
and dead code — computed by parsing the project's `.thy` sources on
*every* invocation. There is no cached database to rebuild: results are
always in sync with the current theory tree (a full parse of ~90
theories runs in well under a second).

It is aimed at large formalisations where grep-and-eyeball stops
scaling — the largest AFP entries, industrial verification trees, or
research developments with hundreds of interdependent lemmas.

## Install

```sh
pip install -e .        # from a clone, for development
# or
pip install .           # a regular install
```

This puts a `query` command on your `PATH` (entry point
`isabelle_query.cli:main`). Requires Python ≥ 3.9.

## Pointing it at a project

`query` reads one Isabelle **session directory** — a directory
containing a `ROOT` file, or a parent of several per-session `ROOT`s.
It is resolved in this order:

1. The `--root DIR` / `-R DIR` flag (must come *before* the subcommand).
2. The `$ISABELLE_QUERY_ROOT` environment variable.
3. The nearest **`.isabelle-query` marker file** at or above the current
   directory (see below).
4. **Auto-discovery**: the nearest directory at or above the current
   directory that contains a `ROOT` file.

For a single-session project (a `ROOT` at its top level, as most AFP
entries have) the common case is zero-config — `cd` in and run `query`.

### Multi-session trees: the `.isabelle-query` marker

A project may keep several sessions in sibling subdirectories (with,
perhaps, unrelated or vendored `ROOT`s elsewhere in the tree).
Auto-discovery can't guess which subtree you mean, so name it **once**
with a marker file at the project root, committed to the repo:

```sh
echo t > .isabelle-query          # the session dir, relative to this file
```

From then on `query` works from anywhere in the project with no flags —
it walks up to the marker and scans the named directory. The first
non-blank, non-comment line is used; an empty marker means "the session
root is this directory". `--root` and `$ISABELLE_QUERY_ROOT` still override it for
ad-hoc queries against other trees:

```sh
query --root ~/afp/thys/Some_Entry summary
```

## Usage

```sh
query summary                 # theory overview table
query theory MyTheory         # all entries in a theory (-n for terse names)
query defs MyTheory           # definitions in a theory
query find <regex>            # search entry names
query show <name>             # show a named entry's declaration + body
query callers <name> [-r]     # who references this name (-r = transitive)
query callees <name> [-r]     # what this entry references
query deps <theory> [-r]      # theories it imports   (reverse: `uses`)
query grep <regex>            # live regex search over .thy sources
query sorry                   # outstanding `sorry`s
query unused [--roots]        # dead-code / unused-entry analysis
```

Every subcommand takes `-h/--help`; most support `-c` (bare count),
`-n` (terse names), and verbosity flags. Run `query -h` for the full
list.

## Layout

```
src/isabelle_query/
├── cli.py       # the `query` CLI: parsing, call graph, rendering, subcommands
└── common.py    # ROOT/session parsing — the single source of truth for
                 # "which .thy files belong to the build"
```

## Provenance

These tools were developed inside a larger Isabelle/Isar formalisation
of classical computational-complexity results. This repository was
extracted from that project with **full git history preserved** — back
to the original shell script that first auto-generated a lemma registry
— using `git filter-repo`, then restructured into an installable
package.

## Authors

Andras Salamon, with Claude Opus 4.6, 4.7, and 4.8.

## License

[MIT](LICENSE) © 2026 Andras Salamon.
