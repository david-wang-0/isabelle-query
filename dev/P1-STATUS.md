# P1 — engine core: status

`PLAN.md`'s P1 phase: discovery, tokenisation, region semantics, entry
recognition with the full name-binding table, block structure / locale scope,
and a `dump-entries` that can be diffed against the Python oracle.

## Gate: entry-set and theory-set parity on the standard corpora

`dev/entrydiff.sh` with no arguments runs the standard set.  Four variants per
corpus — `dump-theories`, `dump-entries`, `dump-entries --spans`,
`dump-entries --bindings` — all **byte-identical**:

| corpus | theories | entries | status |
|---|---|---|---|
| `$QUERY_TEST_AFP/Abstract_Completeness` | 2 | 81 | clean |
| `$QUERY_TEST_AFP/AODV` | 73 (declares 1) | 2,467 | clean |
| `$QUERY_TEST_AFP/Category3` | 28 | 1,636 | clean |
| `$QUERY_TEST_DISTRO/FOL` | 20 | 616 | clean |
| `$QUERY_TEST_DISTRO/ZF` | 133 | 7,336 | clean |

`Category3` is the locale/symbol-heavy choice, verified against the oracle
before adopting it: 136 `LOCALE` entries, 194 declarations whose name carries
an Isabelle markup symbol, and 1,419 of its 1,636 entries with a non-empty
target — i.e. it exercises locale scope, `(in foo)` retargeting and
symbol-bearing names, which the other four barely touch.

`--bindings` is an addition to the oracle's record (the extra names one
declaration binds, plus the resolved target).  Without it the whole
name-binding table — the `and`-lists, rule labels, constructors, selectors,
record fields, locale assumptions and `shows` conjuncts P1 is supposed to
deliver — would be invisible to the gate and would first surface as a `callers`
bug in P3.

## Full-corpus sweep

Whole `$QUERY_TEST_AFP` (AFP for Isabelle2025-2) as a single root, one process
per side, on this machine (12 logical cores):

| | records | wall | peak RSS |
|---|---|---|---|
| `isabelle query dump-entries --spans` | 411,181 | **19.9 s** | 4.3 GB |
| `dev/dump_oracle.py entries --spans` | 409,277 | 37.6 s | 1.1 GB |
| `isabelle query dump-theories` | 10,262 | 5.4 s | 3.0 GB |
| `dev/dump_oracle.py theories` | 10,262 | 3.3 s | 58 MB |

Theory sets are **byte-identical** over the whole AFP.  Entry records differ in
1,952 places, all of them documented in `dev/DIVERGENCES.md` (D1–D5) and all of
them cases where the oracle disagrees with Isabelle's own lexer or its own
header parser; 1,867 of the 1,952 are a single Python tokenizer bug
(`\<open>\\<close>` losing its close and swallowing the rest of the file).

1.9x on entries is the honest number for a first cut, and it is not where the
rewrite's headroom is: the JVM is doing the same per-byte work as Python but in
parallel, and the theory-set case (5.4 s vs 3.3 s) shows the ~1 s process
start-up the wrapper costs.  Discovery is the part that has had no attention —
it stats the tree repeatedly (`real()` per candidate, a full `.thy` walk per
session for the stem index) and is single-threaded.  Peak RSS is the JVM's
default heap policy rather than a working-set measurement.

## What is implemented

- `py_text.scala` — Python-compatible `splitlines` / `strip` / regex dialect.
  These are observable in the output, not implementation detail.
- `model.scala` — `Entry`, `Theory_Section`, the three length-preserving views.
- `regions.scala` — token classification (live / inner / noise) over
  `Token.explode`, ML-body detection by owning command, the nested-marker
  recovery scan, whole-noise line ranges.
- `entries.scala` — the declaration grammar: the built-in commands and the
  custom-keyword table, the five routes (`target` / `typedecl` / `axiom` /
  `def` / `goal`), the full name-binding table, headings and document blocks,
  span boundaries, `begin`/`end` block stacks and `(in foo)`, preamble and
  annotation attachment, proof extents.
- `discovery.scala` — ROOT / ROOTS walk (`Sessions.parse_root_entries` /
  `parse_roots`), session directories, theory resolution, the in-entry import
  closure with the base library and other entries excluded.
- `theory.scala` — per-theory parse, the session-wide keyword union, and
  `Par_List` parallelism.
- `query_tool.scala` — `dump-entries` / `dump-theories` (hidden, dev-only).

## What P2 should watch out for

1. **The keyword union is root-wide** (D4).  It is right for a session and
   wrong for a corpus, and `summary --by-session` will make it visible: the
   session structure P2 needs is exactly the scope the table should have.
2. **`Entry.text`** is built for every entry (the pre-formatted body the Python
   `theory` command prints) and nothing in P1 reads it.  It is carried so that
   `theory` / `show` can be ported without re-parsing, but it is a real share
   of the allocation in a corpus sweep; if P2's rendering does not want it in
   that shape, drop it rather than keeping two.
3. **`live_source` / `outer_source` are lazy on `Theory_Section` but eager in
   `parse_one`** (the scanners need them).  A command that only wants entries
   still pays for both arrays.
4. **Name lookahead is asymmetric** between the routes — `def` and `typedecl`
   scan forward for a name on a following line, `goal` does not.  That is the
   oracle's behaviour and it is why `lemma` alone on its line is `?`.  Changing
   it is a deliberate, diffable change (see D6), not a bug fix to slip in.
5. **`\<comment>` positions inside terms** are recovered by a bounded second
   scan (`Regions.scan_nested`).  The annotations feature (`show
   --comments-only`, `find --with-comments`) is the first thing that reads
   them, so P2 should diff annotations explicitly — the P1 gate does not.
6. **Alt-strings** (`` `...` ``) are classified as inner syntax, which is what
   Isabelle's lexer says and what the oracle does NOT do (it has no alt-string
   state at all).  No corpus difference showed up, because the AFP's backquotes
   are all inside terms — but a stray outer-level backquote would blank to the
   next one on the Scala side only.
7. **Legacy `{* ... *}` verbatim** is noise to the oracle and is not a token in
   Isabelle2025-2 at all.  68 AFP files contain `{*`; none of them produced a
   difference, so every occurrence is inside a comment or a term.  If one ever
   surfaces at outer level, it needs a decision rather than a patch.
