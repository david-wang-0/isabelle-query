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
query summary              # theory overview table (--by-session: corpus/session aggregate)
query theory MyTheory      # entries in a theory (-n for terse names)
query find <regex>         # search entry names (--statement: search statements)
query show <name>          # a named entry's declaration + body (--statement: declaration only)
query enclosing FILE:LINE  # which entry + nearest proof block owns a line/range; inverse of outline
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
query -R AFP/thys find --statement tfin            # lemmas *stated about* tfin, whatever they're named
query -R AFP/thys enclosing Tfin.thy:412           # the lemma *and nearest proof block* a build error sits in
query -R AFP/thys enclosing Tfin.thy:412 -b        # ...the full nesting path: entry then each block, outer to inner
query -R AFP/thys enclosing Tfin:88..140           # every entry a diff hunk / multi-line error touches
query -R AFP/thys grep simp Tfin.thy:88..140       # search just a hunk, for a token that recurs all over
```

Locations and spans share one grammar (`theory:line`, `theory:A..B`), so the
tool's output is valid input: a locus from `callers` / `sorry` pastes into
`enclosing`, and a span from `outline` / `largest` — or a proof block from
`enclosing`'s own drill-down (`▸ have key 11..14`) — pastes into `lines`.

## Why two kinds of scan

The two examples above are the tool's two kinds of question:

- **Structure** — *what is declared, and where* (`largest`, `summary`,
  `theory`, `find`, `show`, `outline`, and its inverse `enclosing`).
- **Usage** — *which facts cite which* (`callers`, `callees`, `unused`).

The call graph used by usage scans is constructed only when needed, so
most commands stay fast.

Both kinds of scan read **only live Isar text**. A name inside a `(* ... *)`
comment, a `\<^cancel>` region, a `text` block or an `ML` body is not a
citation, so it never invents a caller or hides a dead lemma; and a command
word inside one is not a command, so a commented-out `end` does not truncate
the declaration above it. `grep --with-comments` shows the non-live matches
too, marked as such.

**Aggregating across a corpus.** `summary --by-session` rolls the per-theory
counts up to the **session** and **corpus** level — one row per session plus a
grand total — so it is useful run against a whole corpus (`query -R AFP/thys
summary --by-session`), an entry with several sessions, or a single session,
not just one theory at a time. `-v` expands each session to its theories; `-c`
prints only the grand totals (entries / source lines / theories / sessions).
Line totals match `wc -l` over the same build-referenced file set.

The tool reads one Isabelle **session directory** (a directory containing a
`ROOT` file). Run `query` from inside a project and it finds the session
automatically. For a tree with several sessions in sibling subdirectories, name
the session directory (relative to the project root) in a one-line
`.isabelle-query` marker file at the root, or pass `--root <dir>` / set
`$ISABELLE_QUERY_ROOT`.

Discovery loads what the build **compiles**: each session's ROOT-declared
theories *plus the transitive closure of their in-entry `imports`* (bare,
self-qualified, or relative-path). An entry that declares a few leaf theories
and pulls the rest in via `imports` (common in the AFP — `AODV` declares 1,
builds 73) is therefore loaded in full, while imports of *other* entries and of
the Isabelle base library (`HOL-*`, `Pure`) are not followed, and orphan `.thy`
files that no declared root imports are excluded — exactly the set
`isabelle build` would process.

## Proof-shape metrics (`query shape`)

A third kind of question: not *what* is declared or *which* facts cite which,
but the **shape** of the individual proof steps — a family of source-level
proof-complexity measures. The measures are **incomparable axes** — proof
*width* is only one of several, alongside length, depth, and working-set size —
each capturing a different facet of how a proof is structured. Exposed as
`query shape`, it is a nested family of views:

```sh
query shape summary                         # per-theory aggregate table
query shape steps [THEORY[:A..B]] [--json]  # per-step records
query shape lemma <name>                    # one proof: every step + M6 curve
query shape widest [-N n] [--metric M]      # the widest steps (M = w2|w1|fanin|live)
query shape census                          # stream per-proof JSONL over a corpus
```

Every metric is **source-level** — computed by parsing `.thy` text, with **no
Isabelle build and no proof replay** — so a `census -R AFP/thys` over the whole
AFP stays feasible (~1–2 min). Each is either *exact* at source level or a
token-based *estimator* (whose error a phase-2 Isabelle companion calibrates);
estimator columns carry an `_est` suffix everywhere, so the two are never
silently conflated.

The one place Isabelle informs the result is the method/attribute **table** that
tells a proof method (`by auto`) from a fact citation. `census` uses a fixed,
committed **approximate** table — the union of the distribution sessions that
undergird most AFP entries (HOL, HOL-Library, HOL-Analysis, HOL-Eisbach,
HOL-Decision_Procs, chosen by scanning what 988 AFP sessions build on) — so it
needs no Isabelle and regenerates identically anywhere. Being fixed, it is an
approximation: methods an entry *defines itself* (e.g. an Eisbach `cs_concl`) or
that come from a niche logic (Nominal's `nominal_induct`) are not in it, so on
those steps the *automation* axis under-counts — a few % of proofs, only in
method-defining entries; **fan-in and width are unaffected** (measured Δ=0). A
more precise, per-session census is in progress. The per-project table verbs
(`callers`/`callees`/`unused`/`methods`/`shape`) instead resolve a **session-
exact** table from a loaded Isabelle heap when one is built (cached; never a
build), falling back to the committed table (with a warning) otherwise.

A second, smaller committed table works the same way: `const_canon_est`
canonicalises operator glyphs to their Isabelle constant (`\<le>` → `less_eq`)
via a **notation table** resolved once from a heap by
`scripts/extract_notation.py` and checked in (`_notation.py`), so runtime stays
pure-Python. A glyph the table doesn't carry falls back to itself, so it only
ever dedups, never loses, a constant — and the raw-glyph `const_est` beside it
stays table-independent.

| id | column | measures | kind |
|----|--------|----------|------|
| M1 | `w1_est` | distinct free variables in the stated proposition | estimator |
| — | `const_est` | distinct constants in the stated proposition (names + operator notation) | estimator |
| — | `const_canon_est` | `const_est` with operator glyphs canonicalised to their Isabelle constant (`\<le>`/`\<subseteq>` → one) | estimator |
| M2 | `w2_src` | as-written proposition width, in tokens | exact |
| M3 | `frame_ratio` | delta-tracing overhead (components mentioned / changed) | definitional¹ |
| M4 | `dag_ratio_est` | cross-step redundancy (repeated bracketed subterms / block) | estimator |
| M5a | `fanin` | distinct facts cited for a step | exact |
| M5b | `live` | named facts simultaneously live at a step | exact |
| M5c | `introduce`/`consume` | fact-introducing vs fact-citing lines | exact |
| M6 | extension curve | width remaining after naming the *k* most-repeated subterms | estimator |

¹ M3 is defined purely syntactically, so it has no estimator/reference split —
but it needs a per-corpus config (below). The exact definitions, the reference
(elaborated-term) semantics behind each estimator, and the known approximations
are documented in `src/isabelle_query/shape.py`.

**The axes.** These are not one blended "shape" but several distinct
dimensions, grouped into named axes — width is one of them:

| axis | asks | metrics |
|------|------|---------|
| Length | how big is the proof? | `n_steps`, `n_goals`, `proof_lines`, `proof_tokens` (raw + `_code`), `entry_lines` |
| Depth | how deeply nested? | `depth_max` |
| Width | how big is one step? | M1 `w1_est`, `const_est`/`const_canon_est`, M2 `w2_src` |
| Space | how many facts held at once? | M5a `fanin`, M5b `live`, M5c `introduce`/`consume` |
| Redundancy | how much is re-said? | M4 `dag_ratio_est`, M6 extension curve |
| Automation | how is it discharged? | `trivial_frac`, `method_kinds` (kind histogram), induction discipline (`n_induct`, `induct_arbitrary_max`, `induct_recursion`) |
| Framing | how much to say to change a little? | M3 `frame_ratio` |

Length, Depth, Width, and Space are the classic proof-complexity resources
(Length *size* and Depth *nesting* are separate axes with a length–depth
tradeoff); Redundancy, Automation, and Framing are Isar-specific. This
seven-axis taxonomy is what `query shape` reports.

**JSONL — the join contract.** `--json` (and `census`) emit one JSON object per
line, keyed by a stable `(theory, lemma, line)` position, so a metric value can
be joined against later per-step experiments (mask a step, regress
reconstruction success against its width) with no re-instrumentation. There are
two record shapes — per-step (`steps` / `lemma` / `widest`) and per-proof
aggregate (`summary --json` / `census`); the full field lists are in the
`shape_cmds` module docstring. `census` flushes per line and `--resume FILE`
skips entries already recorded, so a killed whole-AFP run resumes where it
stopped, and per-entry parse failures are skipped rather than aborting the run.

**Corpus configs (M3).** Because source parsing cannot resolve types, M3's
"configuration type" degrades to a per-corpus list of selector / constructor /
relation names. Supply it as TOML — one `[corpus]` table per entry — and pass
`--config FILE [--corpus NAME]` to `steps` / `lemma` to add the `frame_ratio`
columns; `configs/m3.toml` ships a `Cook_Levin` table. Without a config the
family runs on every other metric. Normalize cross-corpus comparisons per proof
or per goal step, never per kloc (line counts confound formatting).

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

