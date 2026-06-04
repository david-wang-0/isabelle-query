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

`tests/test_known_failures.py` is a catalogue of *recoverable* parser corner
cases (names on a following line, comment-prefixed names, abbreviation LHS
heads). Each asserts the desired behaviour and is marked `@expectedFailure`,
so the suite stays green today but reports an "unexpected success" the moment
the parser is improved to handle one — a built-in to-do list toward full AFP
coverage.

## Run the corpus-scale checks

`tests/test_corpus.py` is skipped unless you point it at a tree of `.thy` files
(an AFP checkout, or your `ndtht/afp/thys`):

```sh
ISABELLE_QUERY_CORPUS=../ndtht/afp/thys python -m unittest tests.test_corpus -v
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
