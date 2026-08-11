# isabelle-query

`query` is a command-line tool for **querying an Isabelle/Isar project** — its
entries (definitions, lemmas, theorems, datatypes), call graph, theory
dependencies, outstanding `sorry`s, dead code, and the shape of its proofs.

It parses the project's `.thy` sources on every invocation, so results always
match the current tree: **no Isabelle build, no proof replay**. A large project
parses in a fraction of a second, and the whole AFP in a couple of minutes. It is
aimed at projects big enough that grep-and-examine has stopped working — AFP
entries, the AFP itself, or industrial verification.

Pure Python. One runtime dependency,
[isabelle-layout](https://pypi.org/project/isabelle-layout/) — the ROOT and
theory-header parser, split out so that reading an Isabelle project's structure
does not require installing a CLI. `pip` fetches it for you.

## Commands

```sh
query summary              # theory overview table (-S: corpus/session aggregate)
query theory MyTheory      # entries in a theory (-n for terse names)
query find <regex>         # search entry names (--statement: search statements)
query show <name>          # a named entry's declaration + body
query enclosing FILE:LINE  # which entry + proof block owns a line; inverse of outline
query callers <name> [-r]  # who references a name  (reverse; -r = transitive)
query callees <name> [-r]  # what a name references (forward)
query deps <theory> [-r]   # what a theory imports  (forward; reverse: uses)
query sorry                # outstanding sorry's
query unused               # dead-code / unused-entry analysis
query shape <view>         # proof-shape metrics (summary|steps|lemma|widest|census)
```

Every subcommand takes `-h`; `query -h` lists all 18.

## Examples

Point `query` at any session directory with `-R` (or `--root`):

```sh
query -R AFP/thys largest                          # the biggest entries, by line count
query -R AFP/thys callers metric_domain_tfin_def   # every proof step that cites a fact
query -R AFP/thys find --statement tfin            # lemmas *stated about* tfin, whatever their name
query -R AFP/thys enclosing Tfin.thy:412           # the lemma and nearest proof block a build error sits in
query -R AFP/thys enclosing Tfin:88..140           # every entry a diff hunk touches
query -R AFP/thys grep simp Tfin.thy:88..140       # search just a hunk
```

Locations and spans share one grammar (`theory:line`, `theory:A..B`), so the
tool's output is valid input: a locus from `callers` / `sorry` pastes into
`enclosing`, and a span from `outline` / `largest` — or a proof block from
`enclosing`'s own drill-down (`▸ have key 11..14`) — pastes into `lines`.

## What it reads

Only **live Isar text**. A name in a comment, a `\<comment>` note, a `text`
block or an `ML` body is not a citation, so it never invents a caller or hides a
dead lemma — and a `definition` left behind in a comment is not an entry.
Regions are found by a character-level scan, not by line, so
`by (simp add: foo) (* not bar *)` keeps `foo` and drops `bar`.

Layout carries no meaning: Isar is whitespace-insensitive, so a declaration is
recognised wherever a *command* can start, at any indentation and any block
depth. Discovery loads what `isabelle build` compiles — each session's declared
theories plus the closure of their in-entry imports.

See **[SCANNING.md](SCANNING.md)** for the details: locale scope, method names
that collide with fact names, corpus aggregation, and the prose view.

## Proof-shape metrics

`query shape` measures the shape of individual proof steps — how big a step is,
how deeply nested, how many facts it holds at once, how much is re-said, and how
it is discharged. All source-level, no build.

```sh
query shape summary                  # per-theory aggregate table
query shape lemma <name>             # one proof: every step
query -R AFP/thys shape census       # per-proof JSONL over a whole corpus
```

See **[METRICS.md](METRICS.md)** for the command reference, the metric table, and
the JSONL record schema.

## Exit status

`0` the command ran; `1` the request could not be resolved (unknown theory or
path, no subcommand); `2` bad usage — an argparse error, or **a root that could
not be read**; `141` a downstream reader closed the pipe (`query shape census |
head`), as a shell reports for SIGPIPE.

A root that yields no theories is reported on stderr and never as an empty
success, so a script can tell a broken run from an honestly empty one:

```
$ query -R /typo/path shape census
query: /typo/path: no such directory (given to -R/--root)
$ echo $?
2
```

## Installation

Requires Python 3.9 or greater. Installs a `query` command on your `PATH`, and
pulls `isabelle-layout` from PyPI.

```sh
pip install isabelle-query     # from PyPI (once published)
pip install .                  # from a checkout
```

An editable install, for working on the tool itself:

```sh
git clone https://github.com/ott2/isabelle-query
cd isabelle-query
python -m venv .venv && source .venv/bin/activate   # optional but recommended
pip install -e .
```

## Documentation

| file | what |
|---|---|
| [SCANNING.md](SCANNING.md) | how `query` reads a project — what counts as a declaration, a citation, and a session |
| [METRICS.md](METRICS.md) | `query shape` command reference and metric definitions |
| [CONTRIBUTING.md](CONTRIBUTING.md) | the CLI contract and where design decisions are recorded |

## Authors & license

By András Salamon, with Claude Opus 4.6, 4.7, 4.8, and 5. [MIT](LICENSE).
