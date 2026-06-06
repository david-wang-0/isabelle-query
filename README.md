# isabelle-query

`query` is a command-line tool for **querying an Isabelle/Isar project**.
It examines the project's entries (definitions, lemmas, theorems,
datatypes), call graph, theory dependencies, outstanding `sorry`s, and dead
code. It parses the project's `.thy` sources on every invocation, so results
always match the current tree, and even a large project parses in a fraction of
a second. It's aimed at large developments: AFP entries (or the AFP itself),
or industrial verification, where grep-and-eyeball isn't enough.

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

Every subcommand takes `-h`; `query -h` lists all 16.

## Examples

Point `query` at any session directory with `-R` (or `--root`):

```sh
query -R AFP/thys largest                          # the biggest entries, by line count
query -R AFP/thys callers metric_domain_tfin_def   # every proof step that cites a fact
```

## Why two kinds of scan

The two examples above are the tool's two kinds of question:

- **Structure** — *what is declared, and where* (`largest`, `summary`,
  `theory`, `find`, `show`).
- **Usage** — *which facts cite which* (`callers`, `callees`, `unused`).

The usage scan is heavier and runs only when you ask about the call graph, so
everyday commands stay fast.

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
git clone https://github.com/ott2/isabelle-query
cd isabelle-query
python -m venv .venv && source .venv/bin/activate   # optional but recommended
pip install -e .
```

## Authors & license

By András Salamon, with Claude Opus 4.6, 4.7, and 4.8. Extracted — with its full
git history — from a larger Isabelle/Isar formalisation of computational-
complexity results. [MIT](LICENSE).

