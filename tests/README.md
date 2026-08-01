# Tests

Stdlib `unittest` — no third-party dependency, no install required. `support.py`
puts `../src` on `sys.path`, so the suite runs against the working tree.

## Run the default (fast, self-contained) suite

```sh
python -m unittest discover -s tests -v
```

These use small inline theory snippets (`tests/test_names.py`,
`tests/test_call_graph.py`, `tests/test_keywords.py`) and run in well under a
second. The call-graph tests pin `cli._build_call_graph` to a brute-force
oracle (`support.brute_force_call_graph`), so the linear builder can never
silently drift from the obvious O(lines×names) one.

`tests/test_keywords.py` covers the **custom outer-syntax command scanner**:
an AFP entry may define its own theory commands (AOT's `AOT_theorem`,
`AOT_define`, ...) via a `keywords "name" :: kind` header clause — which *is*
Isabelle's keyword table (`Pure/Thy/thy_header.ML`). The scanner reads that
clause and routes each command through the matching built-in branch, so the
facts are indexed and the inflated spans they used to cause collapse.

`tests/test_methods.py` covers the **proof-method scanner** behind `query
methods` / `query method NAME` — the complement of the citation router. It
pins the precision property that makes the tally trustworthy: a method-namespace
token that is really a term variable (`N`, `order`) is counted *only* in
introducer position (after `by` / `apply` / `proof`), never as a bare token, so
the AFP-wide ranking is `simp`/`auto`/`blast`/`metis`/… rather than a list of
common variable names. It also pins the documented under-count (the trailing
method of `by (induct x) auto` is not tallied — never over-counted).

`tests/test_call_graph.py`'s `DropShortNames` covers the **single-char-name
filter** (`--drop-names-upto L`, default 1). A length-1 token (`x`, `a`, `f`,
the wildcard `_`) is a bound variable in nearly every proof — on the AFP,
length-1 names carry ~28% of all citation in-edges across 51 universal-variable
names, essentially all noise — so by default they are not citation-graph nodes,
while length-2+ short *lemma* names (`le`, `id`) are kept. The threshold is a
parameter of the shared `_is_citation_name`, so the fast builder and the oracle
stay in parity at every value (`scripts/analyze_citation_names.py` is the
reusable diagnostic that produced the evidence for the default).

`tests/test_known_failures.py` is a catalogue of *recoverable* parser corner
cases (comment-prefixed names, abbreviation LHS heads). Each asserts the
desired behaviour and is marked `@expectedFailure`, so the suite stays green
today but reports an "unexpected success" the moment the parser is improved to
handle one — a built-in to-do list toward full AFP coverage. The largest
former entry, **name on a following line**, is now handled — see the
`ContinuationLineName` tests in `tests/test_names.py`.

## Run the corpus-scale checks

`tests/test_corpus.py` is skipped unless you point it at a tree of `.thy` files
(an AFP checkout, e.g. your local `afp/thys`):

```sh
ISABELLE_QUERY_CORPUS=~/repos/afp/thys python -m unittest tests.test_corpus -v
```

It mirrors `load_index`: it scans every header into the custom-command union
first, so the measurements reflect the real parse. It asserts the full-tree
robustness targets: unparsed-name (`?`) rate below 7% (the residual is
genuinely-anonymous lemmas and nameless commands — a `C` C-code block, an
`autocorres` invocation), no `(in locale)` prefix leaking into a name, the
reported-bug regression that AOT's `AOT_theorem` run no longer inflates
`beta-C-cor:3`'s span, and the fast call-graph builder matching the oracle on a
bounded slice (never inventing edges; dropping at most 0.5%, in practice
~0.01%).

## Run the performance checks

`tests/test_perf.py` is opt-in (timing has no place in the fast default suite):

```sh
ISABELLE_QUERY_PERF=1 python -m unittest tests.test_perf -v
```

It guards both phases against the *per-theory* O(n²) traps each has actually
hit, with `BuildScaling` and `ParseScaling`:

* **build** — `_entry_at_line` rebuilding a keys list per call (O(lines ×
  entries)) and the prose-skip test rescanning every range per line (O(lines ×
  ranges));
* **parse** — `compute_spans` and `_attach_comments` each scanning the whole
  entry list per entry / block / comment (O(entries²)), the dominant cost on an
  *entry-dense* theory (thousands of short declarations — the real AFP has
  files like `SEC1v2_0_Test_Vectors` with ~6,700).

All are per-theory quadratics, so the synthetic corpus scales **per-theory
size** (not theory count): one theory's definitions, lemmas, text blocks and
comments all grow together — scaling theory count alone would keep each
quadratic linear in corpus size and hide it. The assertion is a **scaling
ratio**, not an absolute wall-clock floor — run at size S and 4·S and require
the ratio to stay near linear (~4), nowhere near quadratic (~16). A reintroduced
per-entry/per-line O(n) factor blows the ratio (verified by monkeypatching each
old form back: build ~4→~13, parse ~4→~15) long before it would trip a fragile
absolute threshold; a deliberately loose build-throughput floor (20k lines/s,
vs the ~150k+ measured) catches only order-of-magnitude regressions.
`scripts/profile_build.py` is the matching diagnostic — it times the parse and
build phases separately and, with `--cprofile`, names the hot functions per
phase.

The driver of the parse cost is *entry count*, not line count: the AFP's
longest file by lines (`Tarski_Neutral`, 44k lines) has only ~1,800 entries and
parses in ~50ms, whereas a same-size file of short declarations would have
~40k. The fix makes both phases linear in entry count — the entry-dense
extreme (17k entries) dropped from ~10s to ~70ms, and full AFP `load_index`
from ~5.2s to ~4.7s.

## Future work (parser corner cases)

Near-term — the one remaining `@expectedFailure` in `test_known_failures.py`:
the **infix/mixfix definition** name (`abbreviation "x \<oplus> y \<equiv> .."`).
The LHS-head heuristic returns the first operand (`x`); the true name is the
operator (`\<oplus>`), which needs mixfix-aware parsing of the equation.

Done — the whole name-on-the-decl-line family, which together took the AFP
`?` rate from ~5.9% to ~4.0% while the call-graph oracle parity held:

* **name on a following line** (`inductive_set` / `definition` with the keyword
  alone on its line and the name beneath it). `DECL_RE` now anchors on a token
  boundary so a lone keyword matches at all, and `_lookahead_name` reads the
  name from the first content line below without consuming it (spans unchanged)
  — ~1,455 names recovered, ~8,377 silently-dropped decls surfaced.
* **margin-comment-prefixed name** (`definition \<comment> \<open>..\<close> bar
  :: ...`) — `_strip_decl_prefix` skips a leading `\<comment>` cartouche
  (~190 entries).
* **implicit-name definition/abbreviation** (`abbreviation "lhs x \<equiv> .."`
  → `lhs`) — `_lhs_head_name` reads the LHS head of the quoted equation
  (~9,000 entries).
* **bare reserved keyword** in the name slot (`lemma assumes ...`, `... by ...`)
  no longer captured as a name — ~630 misparses removed from the call graph.

See the `ContinuationLineName`, `ParseDefName` and reserved-keyword tests in
`tests/test_names.py`.

Longer-term nice-to-have — a **true ground-truth oracle from Isabelle's own
outer-syntax parser**. `support.brute_force_call_graph` is only a slow
*regex* reference: it guards the fast builder against drift, not against
absolute error. Isabelle's real lexer (ML `Outer_Syntax`/`Token`/`Thy_Header`,
mirrored in Scala `isabelle.Outer_Syntax`) would, on a small sample, *measure*
this tool's true error rate against the parser itself. A full `isabelle build`
checks proofs (CPU-days for the AFP — too slow); an outer-syntax-only parse is
feasible but needs the per-session keyword table assembled (the same
`keywords :: kind` headers the scanner already reads). Do it for rigor once the
corner cases above are closed.
