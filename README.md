# isabelle-query

`query` is a command-line tool for **querying the live theory index of an
Isabelle/Isar project** — its entries (definitions, lemmas, theorems,
datatypes), call graph, theory dependencies, outstanding `sorry`s, and dead
code. It parses the project's `.thy` sources on every invocation, so results
always match the current tree (a full parse of ~90 theories runs in well under
a second). It's aimed at large developments — sizeable AFP entries, industrial
verification, research formalisations — where grep-and-eyeball stops scaling.

## What it does

```sh
query summary              # theory overview table
query theory MyTheory      # entries in a theory (-n for terse names)
query find <regex>         # search entry names
query show <name>          # a named entry's declaration + body
query callers <name> [-r]  # who references a name  (reverse; -r = transitive)
query callees <name> [-r]  # what a name references (forward)
query deps <theory> [-r]   # what a theory imports  (forward; reverse: uses)
query sorry                # outstanding sorry's
query unused               # dead-code / unused-entry analysis
```

Every subcommand takes `-h`; `query -h` lists all 15.

The tool reads one Isabelle **session directory** (a directory containing a
`ROOT` file). Run `query` from inside a project and it finds the session
automatically. For a tree with several sessions in sibling subdirectories, name
the session directory (relative to the project root) in a one-line
`.isabelle-query` marker file at the root, or pass `--root <dir>` / set
`$ISABELLE_QUERY_ROOT`.

## Installation

Requires Python ≥ 3.9. Installs a `query` command on your `PATH`.

```sh
pip install isabelle-query     # from PyPI (once published)
pip install .                  # from a checkout
```

## Developer installation

An editable install — source edits take effect immediately, no reinstall:

```sh
git clone <repo-url>
cd isabelle-query
python -m venv .venv && source .venv/bin/activate   # optional but recommended
pip install -e .
```

## Authors & license

By András Salamon, with Claude Opus 4.6, 4.7, and 4.8. Extracted — with its full
git history — from a larger Isabelle/Isar formalisation of computational-
complexity results. [MIT](LICENSE).
