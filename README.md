# isabelle-query

An **Isabelle component** for querying an Isabelle/Isar project — its entries
(definitions, lemmas, theorems, datatypes), call graph, theory dependencies,
locale instantiations, code equations, outstanding `sorry`s, dead code, and the
shape of its proofs.

It parses the project's `.thy` sources on every invocation, so results always
match the current tree: **no Isabelle build, no proof replay, no prover
process.** It is aimed at projects big enough that grep-and-examine has stopped
working — AFP entries, the AFP itself, or industrial verification.

Three front ends over one engine:

| | |
|---|---|
| **`isabelle query`** | the command line — 22 verbs (24 names, with the `at` and `method` aliases), `-h` on each |
| **Isabelle/jEdit plugin** | find usages, find definition, find instantiations, find code equations, quick-open, peek, and Isabelle's own jump stacks given the toolbar buttons they never had |
| **warm server + thin client** | the same command line against a **resident parsed index**, at about 1/2 to 1/72 of the cold cost — and a plain `isabelle query` *is* the thin client (with a JVM fallback wherever `python3` is missing), so the warm index is not something you have to opt into. See [dev/BENCH.md](dev/BENCH.md) |

Written in Isabelle/Scala against the distribution's own parsing stack:
`Token.explode` is the real Isar outer-syntax lexer, `Thy_Header` the real
theory-header parser, `Sessions.parse_root_entries` the real ROOT reader. No
external dependencies — the Isabelle classpath only.

## Install

```sh
isabelle components -u <this checkout>
```

That registers the whole tree as one component: the engine and CLI
(`query_base`), and the jEdit plugin (`jedit_query`). `isabelle query` works
immediately; the plugin jar is built at jEdit start-up, so restart jEdit to
pick it up. To remove it:

```sh
isabelle components -x <this checkout>
rm -f "$ISABELLE_HOME_USER/jedit/jars/isabelle_jedit_query.jar"
```

The second line matters — `$JEDIT_SETTINGS/jars` is not cleaned when a
component is deregistered, and a stale plugin jar whose library jar has gone
away fails loudly on every start-up.

## Commands

```sh
isabelle query summary                 # theory overview table (--by-session: corpus aggregate)
isabelle query theory MyTheory         # entries in a theory (--names for terse names)
isabelle query find <regex>            # search entry names (--statement: search statements)
isabelle query show <name>             # a named entry's declaration + body
isabelle query outline [THEORY]        # section structure with entries
isabelle query enclosing FILE:LINE     # which entry + proof block owns a line (alias: at)
isabelle query largest [-N n]          # the biggest entries, by span
isabelle query grep <regex> [PATH...]  # regex search across live theory source
isabelle query lines FILE A..B         # print line ranges with a `NR| CONTENT` prefix
isabelle query defs <theory>           # definitions in a theory
isabelle query sorry                   # outstanding sorry's
isabelle query callers <name> [-r]     # who references a name  (reverse; -r = transitive)
isabelle query callees <name> [-r]     # what a name references (forward)
isabelle query deps <theory> [-r]      # what a theory imports  (reverse: uses)
isabelle query refs <theory>           # what a theory cites, by owning theory
isabelle query graph [citation|imports] # the whole graph as JSON (-f dot for Graphviz)
isabelle query unused                  # dead-code / unused-entry analysis
isabelle query methods                 # proof methods used, by frequency (alias: method)
isabelle query instances <locale>      # where a locale or class is instantiated
isabelle query codeqs <const>          # declared code-equation sites of a constant
isabelle query shape <view>            # proof-shape metrics (summary|steps|lemma|widest|census)
```

Point it at any session directory with `-R` (or `--root`); with no `-R` it
finds the nearest project at or above the working directory.

```sh
isabelle query -R AFP/thys callers metric_domain_tfin_def   # every proof step that cites a fact
isabelle query -R AFP/thys find --statement tfin            # lemmas *stated about* tfin
isabelle query -R AFP/thys enclosing Tfin.thy:412           # what a build error sits in
isabelle query -R AFP/thys enclosing Tfin:88..140           # every entry a diff hunk touches
isabelle query -R AFP/thys grep simp Tfin.thy:88..140       # search just a hunk
```

Locations and spans share one grammar (`theory:line`, `theory:A..B`), so the
tool's output is valid input: a locus from `callers` / `sorry` / `instances`
pastes into `enclosing`, and a span from `outline` / `largest` pastes into
`lines`.

### `instances` and `codeqs`, and what they do not see

Each row is `LOCUS  NAME  KIND  source`, one site per line — the name sits where
`callers` puts its owning entry, and the locus stays first so a row still pastes
into `enclosing`.

The **name** is what the source calls that site, never something inferred:

```
Category3/DualCategory:66  dual_category  sublocale  sublocale dual_category \<subseteq> category comp
HOL/Topological_Spaces:3644  prod  instantiation  instantiation prod :: (topological_space, ...
HOL/List:3249  rev_conv_fold  [code]  lemma rev_conv_fold [code]: "rev xs = fold Cons xs []"
```

- `instances` — the written qualifier (`Cop` in `sublocale Cop: dual_category C ..`),
  else the type constructor an `instantiation` / `instance` names, else the
  target of a `sublocale L ⊆ M`, else the context the site sits in (the
  enclosing locale, or the lemma an `interpret` is inside).
- `codeqs` — the fact that provides the equation: the `lemma` / `lemmas` name
  carrying the attribute, the binding label a `declare` attaches to
  (`fib.simps`), or, for a `default` row, the defining entry's own name.

A site the source gives no name at all — a bare `interpretation L ..` at top
level — prints `?`, the same placeholder the engine uses for an unnamed
`context`. It is not given the locale's own name, which would make every row
repeat the question.

**`--sorts`** adds the sort, arity or signature **as the source writes it**:
`prod :: (topological_space, topological_space) topological_space`,
`rev :: 'a list ⇒ 'a list`. This tool runs no prover, so **nothing is
inferred** — a site whose source writes no type shows none, and the flag is a
way of reading the source, not of typing it.

These two have no Python counterpart and one shared caveat, stated here because
it is the honest scope rather than a footnote.

Both report **declared source sites** — the complement of Isar's
`print_interps` / `print_codesetup`, which need a running prover and show the
*processed* setup: after preprocessing, after `[code del]` has taken effect,
and including whatever an imported session installed. A static scan sees the
text; the prover sees the result.

`codeqs` finds an equation by the **head symbol of its statement**, and mixfix
notation defeats that rule. `lemma upto_code [code]: "[i..j] = upto_aux i j []"`
is an equation of `upto` written in `upto`'s own notation, where the head rule
reads no identifier at all. This is irreducible without a parser that knows the
project's `notation` declarations, and it **under-reports** — the one place
these scans lean the unsafe way. If `codeqs c` looks short, check with `grep`.

Neither verb separates same-named constants *within* what a theory can see:
`codeqs rev` over `src/HOL` reports `List.rev`, `Imperative_Reverse.rev` and
`Linked_Lists.rev` together, because every theory there imports `Main` and so
could be naming any of them. That much is inherent to a name-based tool
(`callers` has it too), and a site listing invites the reader to treat the rows
as one constant's equations.

What *is* separated is the impossible case. A site is reported only in a theory
that can **see** a declaration of the name — its own, or one in its transitive
`imports` closure — so across disjoint trees the rows no longer run together.
Over the whole AFP `callers mono` drops from 1,361 hits to 566: the 795 that go
are in theories whose entire import closure declares no `mono`, where the token
is HOL's own `Orderings.mono` arriving through an `imports Main` that `query`
does not follow. The same filter is what `callers`, `callees`, `refs`,
`unused`, `graph citation`, `instances` and `codeqs` all read.

`ISABELLE_QUERY_REACHABILITY=off` turns it off, restoring name-only
attribution.

Both exit `1` when the subject is not a locale/class (resp. not a constant)
declared in the project, rather than reporting zero sites.

## The jEdit plugin

Right-click in a theory buffer, or use the *Isabelle Query* dockable:

- **Find usages** — resolves the identifier at the caret through the engine's
  name index, not PIDE markup, so it answers without waiting for the prover.
- **Find definition** — the declaration *and its body*, rendered in the panel
  rather than jumping a pane. jEdit has no such view otherwise.
- **Find instantiations** / **Find code equations** — the two verbs above,
  presented as a **directory → file → site tree**.
- **Search by name** — a name field in the panel, so a finder can be run on
  something that is not under the caret (what `code_thms c` gives you at the
  prompt). Fuzzy completion over the index; the *Find* button offers the
  finders that name admits, gated exactly as the right-click menu is.
- **Sorts** — the CLI's `--sorts` as a toggle; it repaints the rows already on
  screen rather than re-running the query.
- **Quick-open / go to symbol** — fuzzy lookup over the index.
- **Peek definition** — a popup that does not move the editor.
- **Navigate back / forward** — Isabelle already ships complete jump stacks
  (`Isabelle_Navigator`) with no default keybinding or toolbar exposure; the
  plugin exposes them rather than re-implementing them.

The two site views group by **directory** as well as by file, because a site
list mixes registrations with retractions — `[code]` in one file, `[code del]`
in another — and where in the project each is written is what tells them apart.
The levels come from each theory's own path relative to the project root; a
directory chain with nothing else in it (`Deep/Down/`) is shown as one node
rather than as two arrows, so a project whose theories all sit in its root
looks exactly as it did and one with session subdirectories gains a single
level. Every level carries its own count, directories open down to the file
level, and the site rows themselves are visible without a click. Usages and
find-definition keep the flat per-theory presentation.

Results share one tree, following jEdit's own HyperSearch Results idiom:
grouped by file, line-numbered previews, successive result sets kept as
siblings. Find-definition renders expanded, usages collapsed. Click policy is
configurable (Plugin Options → Isabelle Query) through one gesture→action
table: by default double-click and Enter open in the current pane, shift-click
in a new pane, alt-click peeks, middle-click opens a new view, and single-click
does nothing.

The index is per project (discovered from the buffer's own path), refreshed
from live buffer text for dirty buffers and mtime for the rest, and **refuses
rather than truncates** above a size limit — a partial index answers "no
usages" for a name that is used.

## The warm server

A plain `isabelle query` **is** a thin Python client over a resident index: the
component ships an external tool under the tool's own name, which `isabelle`
dispatches to before any JVM starts. The server is the stock `isabelle server`
extended with four commands (`query_version`, `query_open`, `query_run`,
`query_close`) contributed as a `Server.Commands` service, so it inherits that
server's lifecycle, discovery registry and security model (loopback bind,
per-user password). Nothing new listens on anything, and no JVM is on the fast
path.

```sh
isabelle query summary               # the thin client; starts a server on first use
isabelle query --client-status       # what is resident, and what it cost
isabelle query --client-stop         # shut it down
isabelle query --no-server <args>    # no client, no server: one JVM, right here
```

What the warm mode saves is the **parse**, not the process start: 19 of the
whole AFP's 20 cold seconds are reading theories, and no amount of faster
starting touches that. Measured 2026-08-30, median of 5 — method, the full
table and the cost breakdown are in [dev/BENCH.md](dev/BENCH.md):

| | Python `query` | JVM tool, cold | thin client |
|---|---:|---:|---:|
| `show` on a 2-theory AFP entry | 75 ms | 697 ms | **32 ms** |
| `callers` on a 28-theory entry | 284 ms | 1086 ms | **112 ms** |
| `summary` on `src/HOL` (1451 theories) | 4863 ms | 3890 ms | **64 ms** |
| `summary --by-session` over the whole AFP | 37.4 s | 19.0 s | **0.28 s** |

The index costs about **2–3 KB per entry** of retained heap — 154 MB for
`src/HOL`'s 78,279 entries, 1.2 GB for the AFP's 411,181. Process RSS runs much
higher (~4.5 GB with the AFP loaded) because the collector keeps what it has
committed, so size a host by that rather than by the index.
`$ISABELLE_QUERY_SERVER_LIMIT` (default 4000 theories) **refuses rather than
truncates**, so a stray `-R` at a whole checkout cannot quietly turn the server
into a multi-gigabyte process.

One workload goes the other way and is bypassed for it: a whole-corpus
`shape census` returns 256 MB, which is slower through the socket than
cold. Typing `isabelle query` runs it cold without being asked.

**Staleness.** The server polls before every answer — a walk over every `.thy`,
`ROOT` and `ROOTS`, one `stat` each, no file read — which is 12 ms across
`src/HOL`'s 1468 files. One edited theory in 1451 reparses one theory. Two
limits worth knowing: a `stat` is not a hash, and the server has no editor, so
**unsaved buffer changes are invisible to it**. The jEdit plugin reads dirty
buffers itself and does not share that limitation.

**One router.** `lib/Tools/query` decides who answers and nothing downstream
decides again. When the client will not serve a request it exits **97** having
written nothing, and the shim runs the JVM instead; the answer is byte-for-byte
the cold answer, exit status included (a declined `… | head -3` still exits
141). Nothing is printed until the whole reply is in hand, so a decline can
neither duplicate nor truncate output.

Never served, and the list is deliberate: anything reading stdin (`-`), the
`dump-*` development verbs, `shape census`, `-h`/`--help`/`-V`/`--version`,
and a **relative** argument naming a file or directory here — `find .` searches
for the regex `.` while `grep pat .` searches the directory `.`, only the
command's grammar tells them apart, and a transport is not a parser. Absolute
paths mean the same thing anywhere, so they are served. The list lives in
`query_client.py` and nowhere else.

```sh
ISABELLE_QUERY_NO_CLIENT=1 …         # skip the thin client; answer in this JVM
ISABELLE_QUERY_NO_SERVER=1 …         # skip both; the same wish as --no-server
ISABELLE_QUERY_NO_CDS=1 …            # do not use the cold path's AppCDS archive
ISABELLE_QUERY_ALWAYS_BUILD=1 …      # run scala_build always, not only when stale
```

`$ISABELLE_QUERY_CLIENT_SERVER` names the server (default `isabelle_query`).
The variables the *tool* reads — `$ISABELLE_QUERY_ROOT`,
`$ISABELLE_LAYOUT_ROOT`, `$ISABELLE_QUERY_NAMESPACE`,
`$ISABELLE_QUERY_REACHABILITY` — travel in the request and are bound for that
request only, so one set in your shell means the same thing warm as cold,
whoever happened to start the server.

## What it reads

Only **live Isar text**. A name in a comment, a `\<comment>` note, a `text`
block or an `ML` body is not a citation, so it never invents a caller or hides
a dead lemma — and a `definition` left behind in a comment is not an entry.
Regions come from Isabelle's own lexer, so `by (simp add: foo) (* not bar *)`
keeps `foo` and drops `bar`.

Layout carries no meaning: Isar is whitespace-insensitive, so a declaration is
recognised wherever a *command* can start, at any indentation and any block
depth. Discovery loads what `isabelle build` compiles — each session's declared
theories plus the closure of their in-entry imports.

See **[SCANNING.md](SCANNING.md)** for the details: locale scope, method names
that collide with fact names, corpus aggregation, and the prose view.

## Proof-shape metrics

`isabelle query shape` measures the shape of individual proof steps — how big a
step is, how deeply nested, how many facts it holds at once, how much is
re-said, and how it is discharged. All source-level, no build.

```sh
isabelle query shape summary                  # per-theory aggregate table
isabelle query shape lemma <name>             # one proof: every step
isabelle query -R AFP/thys shape census       # per-proof JSONL over a whole corpus
```

See **[METRICS.md](METRICS.md)** for the command reference, the metric table,
and the JSONL record schema.

## Exit status

`0` the command ran; `1` the request could not be resolved (a subject that is
not a locale, not a constant, an unknown theory or path); `2` bad usage — an
argument error, or **a root that could not be read**; `141` a downstream reader
closed the pipe, as a shell reports for SIGPIPE.

A root that yields no theories is reported on stderr and never as an empty
success, so a script can tell a broken run from an honestly empty one:

```
$ isabelle query -R /typo/path shape census
isabelle query: /typo/path: no such directory (given to -R/--root)
$ echo $?
2
```

## Relationship to the Python original

This is a rewrite of the pure-Python `isabelle-query`
([ott2/isabelle-query](https://github.com/ott2/isabelle-query), on PyPI). The
Python tree is kept **in this repository, frozen**, as the reference
implementation and the test oracle: `src/isabelle_query/` and `tests/`. Nothing
in the Scala engine is a translation — it is a reimplementation checked against
the original's output.

Verification is **differential**, not unit: `dev/difftest.sh` runs both tools
over a matrix of 2,086 (corpus × invocation) cases across seven corpora,
diffing stdout byte-for-byte and comparing exit statuses, and
`dev/entrydiff.sh` diffs the whole entry set and theory set over the entire AFP
and the entire distribution `src`.

Where the two differ, the difference is recorded in
[dev/DIVERGENCES.md](dev/DIVERGENCES.md) with the evidence — twelve entries,
and **no entry is ever lost**: over both corpora the set of
`theory:line:tag:name` identities the oracle reports is a strict subset of this
engine's. The rewrite finds 1,904 declarations across the AFP that the Python
implementation misses, mostly `definition\<^marker>\<open>…\<close> name`
(D2), because it uses Isabelle's lexer instead of a hand-rolled one.

Moving from the Python tool: see **[MIGRATING.md](MIGRATING.md)**.

## Documentation

| file | what |
|---|---|
| [demo/DEMO.md](demo/DEMO.md) | a guided tour — a 666-line corpus written to be queried, every verb with a named thing to point at ([CHEATSHEET.md](demo/CHEATSHEET.md) is the one-line-per-feature form) |
| [SCANNING.md](SCANNING.md) | how a project is read — declarations, citations, sessions |
| [METRICS.md](METRICS.md) | `shape` command reference and metric definitions |
| [MIGRATING.md](MIGRATING.md) | coming from the Python `query` |
| [CONTRIBUTING.md](CONTRIBUTING.md) | the CLI contract and where design decisions are recorded |
| [PLAN.md](PLAN.md) | the rewrite's normative plan, phase by phase |
| [dev/BENCH.md](dev/BENCH.md) | the benchmark numbers and how they were taken |
| [dev/DIVERGENCES.md](dev/DIVERGENCES.md) | every deliberate difference from the oracle |

## Authors & license

The original (Python) isabelle-query is by András Salamon, with Claude Opus
4.6, 4.7, 4.8, and 5 — see [upstream](https://github.com/ott2/isabelle-query),
kept in-tree under `src/isabelle_query/` as the frozen reference
implementation. The Scala rewrite, the `instances` and `codeqs` verbs, the
jEdit plugin and its IDE features (find usages/definition/instantiations/code
equations, quick-open, peek, search-by-name, navigation exposure), the warm
server and thin client, and the demo project are by David Wang, with Claude
Fable 5 and Claude Opus 5. [MIT](LICENSE).
