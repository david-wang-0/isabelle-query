# Tests

Stdlib `unittest` — no third-party dependency, no install required. `support.py`
puts `../src` on `sys.path`, so the suite runs against the working tree.

## Run the default (fast, self-contained) suite

```sh
python -m unittest discover -s tests -v
```

These use small inline theory snippets (`tests/test_names.py`,
`tests/test_call_graph.py`) and run in well under a second. The call-graph tests
pin `cli._build_call_graph` to a brute-force oracle (`support.brute_force_call_graph`),
so the linear builder can never silently drift from the obvious O(lines×names) one.

`tests/test_known_failures.py` is a catalogue of *recoverable* parser corner
cases (names on a following line, comment-prefixed names, abbreviation LHS
heads, custom fact-command keywords). Each asserts the desired behaviour and is
marked `@expectedFailure`, so the suite stays green today but reports an
"unexpected success" the moment the parser is improved to handle one — a
built-in to-do list toward full AFP coverage.

## Run the corpus-scale checks

`tests/test_corpus.py` is skipped unless you point it at a tree of `.thy` files
(an AFP checkout, or your `ndtht/afp/thys`):

```sh
ISABELLE_QUERY_CORPUS=../ndtht/afp/thys python -m unittest tests.test_corpus -v
```

It asserts the full-tree robustness targets: unparsed-name (`?`) rate below 3%,
no `(in locale)` prefix leaking into a name, and the fast call-graph builder
matching the oracle on a bounded slice (never inventing edges; dropping at most
~0.1%).
