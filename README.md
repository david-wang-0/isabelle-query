# isabelle-query

`query` is a command-line tool for **querying an Isabelle/Isar project**.
It examines the project's entries (definitions, lemmas, theorems,
datatypes), call graph, theory dependencies, outstanding `sorry`s, and dead
code. It parses the project's `.thy` sources on every invocation, so results
always match the current tree, and even a large project parses in a fraction of
a second. It's aimed at large projects: AFP entries (or the AFP itself),
or industrial verification, that need more than grep-and-examine.

## What it does

```sh
query summary              # theory overview table
query theory MyTheory      # entries in a theory (-n for terse names)
query find <regex>         # search entry names (--statement: search statements)
query show <name>          # a named entry's declaration + body (--statement: declaration only)
query enclosing FILE:LINE  # which entry owns a line/range (FILE:A..B); inverse of outline
query callers <name> [-r]  # who references a name  (reverse; -r = transitive)
query callees <name> [-r]  # what a name references (forward)
query deps <theory> [-r]   # what a theory imports  (forward; reverse: uses)
query sorry                # outstanding sorry's
query unused               # dead-code / unused-entry analysis
```

Every subcommand takes `-h`; `query -h` lists all 17.

## Examples

Point `query` at any session directory with `-R` (or `--root`):

```sh
query -R AFP/thys largest                          # the biggest entries, by line count
query -R AFP/thys callers metric_domain_tfin_def   # every proof step that cites a fact
query -R AFP/thys find --statement tfin            # lemmas *stated about* tfin, whatever they're named
query -R AFP/thys enclosing Tfin.thy:412           # which lemma owns the line a build error names
query -R AFP/thys enclosing Tfin:88..140           # every entry a diff hunk / multi-line error touches
query -R AFP/thys grep simp Tfin.thy:88..140       # search just a hunk, for a token that recurs all over
```

Locations and spans share one grammar (`theory:line`, `theory:A..B`), so the
tool's output is valid input: a locus from `callers` / `sorry` pastes into
`enclosing`, and a span from `outline` / `largest` pastes into `lines`.

## Why two kinds of scan

The two examples above are the tool's two kinds of question:

- **Structure** — *what is declared, and where* (`largest`, `summary`,
  `theory`, `find`, `show`, `outline`, and its inverse `enclosing`).
- **Usage** — *which facts cite which* (`callers`, `callees`, `unused`).

The call graph used by usage scans is constructed only when needed, so
most commands stay fast.

The tool reads one Isabelle **session directory** (a directory containing a
`ROOT` file). Run `query` from inside a project and it finds the session
automatically. For a tree with several sessions in sibling subdirectories, name
the session directory (relative to the project root) in a one-line
`.isabelle-query` marker file at the root, or pass `--root <dir>` / set
`$ISABELLE_QUERY_ROOT`.

## Installation

Requires Python 3.9 or greater. Installs a `query` command on your `PATH`.

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

By András Salamon, with Claude Opus 4.6, 4.7, and 4.8. Extracted with its
git history from a larger Isabelle/Isar formalisation of computational
complexity results. [MIT](LICENSE).

