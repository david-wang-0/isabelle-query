# PLAN.md — the Scala rewrite (normative for this fork)

This fork rewrites `isabelle-query` — currently pure Python (see `CLAUDE.md`,
`README.md`, `SCANNING.md`, `METRICS.md`, all describing the Python tool, which
remains in-tree as the reference implementation and test oracle) — as an
**Isabelle component in Isabelle/Scala**, usable both as `isabelle query …` on
the command line and from within Isabelle/jEdit (right-click → find usages).

Everything here is normative for the rewrite. Where this file is silent, the
Python implementation and its docs are the spec. The inherited
`.claude/memory/` describes the *Python* project's workflows; where it
conflicts with this file (e.g. "pure-Python", pytest habits), this file wins.

## Why Scala (decision, recorded)

- Isabelle components are routinely written in Scala; the Isabelle linter
  (component: `linter_base` + `jedit_linter`) is the structural template.
- A component jar with `requirements = env:ISABELLE_SCALA_JAR` reuses the
  distribution's own parsing stack, replacing the two largest hand-rolled
  parts of the Python tool with the real thing:
  - `isabelle.Token.explode(keywords, text)` — the actual Isar outer-syntax
    lexer (nested `(* *)` comments, cartouches, quoted strings/alt-strings,
    verbatim, control symbols). Replaces `parsing.scan_regions`.
  - `isabelle.Thy_Header` — theory name / imports / per-theory `keywords`.
  - `isabelle.Sessions.parse_root_entries` / `parse_roots` — ROOT/ROOTS files.
    Replaces the `isabelle-layout` dependency.
  - `isabelle.Keyword` / `Outer_Syntax` — command-kind classification
    (`document_heading`, `document_body`, `thy_decl`, …). Replaces the
    heap-extracted keyword table.
- SML would run inside a prover process (heap required, no standalone CLI);
  Scala runs standalone on the JVM and is also the language of Isabelle/jEdit,
  so one codebase serves both frontends.

## Component layout

```
<repo root>                    ← registered as ONE component (etc/components chains)
  etc/components               ← lists: query_base [, jedit_query once it exists]
  etc/settings
  query_base/                  ← the engine + CLI tool (no jEdit deps)
    etc/settings               ← ISABELLE_QUERY_JAR=…/lib/classes/isabelle_query.jar
    etc/build.props            ← module, requirements=env:ISABELLE_SCALA_JAR,
                                 sources, services=isabelle.query.Query_Tools
    src/*.scala
  jedit_query/                 ← the jEdit plugin (depends on query_base jar)
    etc/settings, etc/build.props, src/*.scala
  src/isabelle_query/          ← Python reference implementation (frozen; do not edit)
  tests/                       ← Python test suite (reference for semantics; not run in CI here)
  dev/                         ← differential harness, benchmark scripts, dev notes
```

The Python tree is **frozen**: never edit it, only read it. It is upstream's
0.8.1 release since `[p9-merge]`. The runtime oracle is a `query` built from
*that* tree and named by `$QUERY_ORACLE`; `dev/difftest.sh` pins its version
and refuses to run against any other, so a stale `query` on `PATH` is a
refusal rather than a plausible red.

## Build / dev loop (verified mechanics)

Never register a work-in-progress component into the user's real
`$ISABELLE_HOME_USER` — a compile error there breaks `isabelle jedit` startup
for every session. Develop against a scratch user home inside the repo
(gitignored):

```sh
FORK=$(git rev-parse --show-toplevel)
USER_HOME="$FORK/.dev" isabelle components -u "$FORK"      # once
USER_HOME="$FORK/.dev" isabelle scala_build                # compile dirty modules
USER_HOME="$FORK/.dev" isabelle query …                    # run the tool
```

(`ISABELLE_HOME_USER` itself is set unconditionally by the distribution's
settings and cannot be overridden from the environment; `USER_HOME` can, and
`ISABELLE_HOME_USER` derives from it.) Registration into the real home is a
release step, done only from a green tree.

## Parity contract

The Scala tool must be a drop-in replacement for the Python CLI:

- **Same subcommands, same flags** (`query -h` lists 22 incl. aliases `at`,
  `method`; each subcommand's `-h` lists its flags). The CLI contract in
  `CONTRIBUTING.md` (lookup vs search families, exit codes 0/1/2/141,
  never-empty-success, `_user_pattern` rewrites) carries over in full.
- **Byte-identical stdout** for every valid non-help invocation, modulo:
  - help text (`-h`) — same flags must exist; prose may differ;
  - the program name in messages (`isabelle query` vs `query`);
  - documented divergences collected in `dev/DIVERGENCES.md` (each entry:
    command, input, both outputs, why). Keep this list near-empty; every entry
    needs a reason better than "hard to match".
- **Same exit codes** (0 ran / 1 unresolved subject / 2 usage or unreadable
  root / 141 SIGPIPE-equivalent on closed stdout).
- stderr: must be non-empty exactly where Python's is; wording may differ.
- `-V/--version`: report the fork's own version, `UPSTREAM-scala.MINOR.PATCH`.
  The NUMBER names the upstream release whose contract the port matches
  (`0.8.1` since P9, and `dev/difftest.sh` refuses to run if it differs from
  the oracle version it pins); the `-scala` marker is what lets a script tell
  the two tools apart; the `MINOR.PATCH` after it counts the port's own
  releases (`0.8.1-scala.0.1` since P10).

Regex dialect: user patterns are Python `re` in the oracle, `java.util.regex`
in Scala. For the pattern subset that appears in docs/tests (literals,
alternation, classes, anchors, `\b`) the two agree; the `_user_pattern`
rewrites (`\|` → `|`, `\<^sub>`-escaping) must be ported. Divergences beyond
that are acceptable only if recorded.

## Differential testing (the primary verification)

`dev/difftest.sh` — runs oracle (`query`) and rewrite (`isabelle query`) over a
matrix of (corpus × invocation) pairs, diffing stdout + exit codes. Corpora
come from environment variables / arguments, never hard-coded paths (this repo
may be published; no absolute personal paths anywhere in committed files —
grep the diff for `/home/`, `~/` before every commit):

- `$QUERY_TEST_AFP` — an AFP `thys` directory (Isabelle2025-2 vintage).
- `$QUERY_TEST_DISTRO` — the Isabelle distribution's `src` directory.

Standard corpus set (small → large), chosen inside the harness by name:
several individual AFP entries of different character (e.g. one tiny, one with
locale-heavy structure, one with a big `imports` closure like `AODV`, one with
Isabelle-symbol names), one distribution session dir (e.g. `src/ZF` or
`src/FOL`), and — for the full-sweep tier only — all of `$QUERY_TEST_AFP`.

The invocation matrix covers every subcommand, with subjects auto-derived per
corpus (pick entry/theory names out of the oracle's own `find`/`summary`
output, so the matrix ports across corpora). Add every discovered discrepancy
to the matrix as a pinned case before fixing it.

Entry-set parity has a dedicated deep check, mirroring `CONTRIBUTING.md`'s
"diff the entry set" rule: a hidden `isabelle query dump-entries` (name, kind,
theory, span, target — same fields as `scripts/dump_entries.py --spans`)
diffed against the Python dump over whole corpora. Same for the discovered
theory **set** (not count).

## Performance (the motivation — verify it)

Benchmark protocol in `dev/bench.sh`: hyperfine (or 5-run min of `time`) on
- cold single-entry query (`summary`, `callers X`) on a mid-size AFP entry,
- corpus sweep (`summary --by-session`, `dump-entries`) over all of
  `$QUERY_TEST_AFP`,
for oracle vs rewrite. Record results in `dev/BENCH.md` with machine + date.

Expectations to beat: Python full-AFP parse ≈ 1–2 min; single entry well under
1 s. JVM startup (~0.5–1 s via the `isabelle` wrapper) means tiny corpora may
not beat Python cold — the wins must come from (a) parallel per-theory parsing
(`isabelle.Par_List`), (b) doing less work per byte (real lexer, one pass),
and later (c) a warm-index server shared with the jEdit plugin. If cold small
queries end up slower, say so in `dev/BENCH.md` and prioritise the server mode.

## Phases (each ends with: builds green, difftest green at its tier, committed)

Every phase gate is re-established by an **independent verifier agent**
(adversarial re-run of the gate, cheat scan of the new code, repo hygiene)
before the next phase starts; the implementing agent's own green report is
necessary but not sufficient.

- **P1 — engine core.** Discovery (ROOT + in-entry import closure per
  `SCANNING.md` "What counts as the project"), tokenisation, region semantics
  (`live_source` / `outer_source` equivalents), entry recognition with the full
  name-binding table (`SCANNING.md` "The names one declaration binds"), block
  structure / locale scope, `dump-entries`. **Gate:** entry-set + theory-set
  parity on the standard corpora.
- **P2 — structure commands.** `summary` (+`--by-session`), `theory`, `defs`,
  `outline`, `enclosing`/`at` (locus grammar!), `largest`, `lines`, `grep`,
  `sorry`, `find`, `show`. **Gate:** difftest matrix green for these.
- **P3 — usage commands.** Call graph, `callers`, `callees`, `deps`, `uses`,
  `refs`, `graph` (JSON + DOT), `unused`, `methods`/`method` (port the
  committed method/attribute table from `_isabelle_namespace.py` /
  `_census_namespace.py` as data). **Gate:** difftest green for these.
- **P4 — shape family.** `shape summary|steps|lemma|widest|census` per
  `METRICS.md` + `shape.py` (authoritative). Largest single phase; may ship
  after the jEdit pair (P5/P6) if that flows better — the CLI must then say
  "not yet ported", never silently succeed empty.
- **P5 — jEdit plugin.** `jedit_query` module (template: `jedit_linter`):
  - a **Query dockable** whose presentation follows jEdit's own
    directory-search results window (the HyperSearch Results dockable — tree
    grouped by file with hit counts, line-number-prefixed previews with the
    match highlighted, click → jump, successive result sets kept as siblings).
    Study `org/gjt/sp/jedit/search/HyperSearchResults.java` in the jEdit
    sources bundled with the Isabelle distribution's jedit component
    (`contrib/jedit-*/jedit5.7.0-patched/jEdit/`); match its idiom rather
    than inventing a new results UI;
  - right-click **"Find usages"** via jEdit 5.7's
    `org.gjt.sp.jedit.gui.DynamicContextMenuService` (verified present in the
    bundled jEdit): resolve the identifier at the caret via the engine's name
    index (not PIDE markup — works without waiting for the prover), run
    `callers`, show in the dockable;
  - index over the session directory of the current buffer, live buffer text
    for dirty buffers, mtime-based invalidation otherwise — this warm index is
    the seed of the CLI server mode.
  Manual testing only by the user (never restart their running jEdit; ask).
- **P6 — IDE features** (immediately after P5 — it builds on the plugin's
  infrastructure; ideally the same agent continues). Baseline survey lives in
  the gitignored `.dev/JEDIT-NOTES.md` ("Navigation survey"). The slate:
  - **navigate back/forward**: Isabelle/jEdit already ships complete jump
    stacks (`isabelle.jedit.Isabelle_Navigator`, actions
    `navigate-backwards`/`navigate-forwards`) but with no default keybinding
    or toolbar exposure — expose it (keybindings, dockable/toolbar buttons)
    rather than re-implementing;
  - **Find Definition in the sidebar**: render the engine's `show` output
    (declaration + body) in the query dockable instead of jumping a pane —
    no such view exists in jEdit today (only the ctrl+hover tooltip and the
    ctrl+click pane jump);
  - **sidebar result-view contract** (P5 built the architecture; P6 completes
    it): find-definition and find-usages share ONE result-tree machinery and
    identical navigation; find-definition renders EXPANDED by default,
    find-usages snippets COLLAPSED, with per-item expand and
    expand-all/collapse-all; expansion arrows follow the HyperSearch
    sidebar's per-file-match arrow idiom;
  - **click-policy differentiation on dockable results**: single / double /
    shift / middle click open the hit in the current pane vs a new pane
    (EditPane split) vs a peek preview — one configurable gesture→action
    indirection, defaults double-click = active pane, shift-click = new
    pane (P5 shipped the indirection over jEdit properties; P6 adds peek
    and, if workable pre-PIDE, an Isabelle-options surface);
  - **go-to-symbol / quick-open** fuzzy lookup over the query index;
  - **peek-definition** popup (reuses tooltip + hyperlink-resolution
    machinery);
  - **outline**: bundled SideKick already covers much of it via Isabelle's
    registered parsers — check coverage, fill gaps only.
- **P6b — find instantiations + find code equations** (after find-usages and
  find-definition are in; before P7). New capabilities, no Python
  counterpart; both are same-shaped "sites in a syntactic role citing a
  name" scans on the existing citation machinery (live text only, as
  everywhere):
  - *instantiations* of a locale/class: `instantiation` blocks,
    `interpretation`/`global_interpretation` and `sublocale` declarations
    whose subject is that name;
  - *code equations* of a constant: declarations carrying a code attribute
    (`[code]`, `[code equation]`, `[code abstract]`, and kin) whose
    statement mentions the constant, `declare c [code …]` and
    `lemmas … [code] = …` sites, plus the implicit default equations of the
    constant's own `fun`/`primrec`/`definition`. Scope stated honestly in
    docs: declared source sites — the complement of Isar's
    `print_codesetup`/`code_thms`, which need a running prover and show the
    processed setup (`[code del]`, preprocessing) a static scan cannot.
  Each: engine query + a new CLI verb (rewrite-only: excluded from the
  differential matrix by construction — covered by the probe/unit harnesses
  instead, with hand-computed fixtures per the repo's correctness doctrine)
  + jEdit context-menu action and further dockable result kinds (collapsed
  by default, same navigation and gesture policies as usages).
- **P7 — polish + warm server.** Benchmarks recorded; README/CLAUDE.md
  rewritten for the Scala tool; register-for-real instructions. Server mode
  (decided; study + protocol sketch in the gitignored `.dev/SERVER-NOTES.md`):
  **extend the stock `isabelle server`** with a `Server.Commands` service from
  this component (the command table folds in component services — no Isabelle
  patch), rather than a custom daemon — inheriting its lifecycle, discovery
  registry, and loopback + per-user-password security. Ship a **non-JVM thin
  client** (small Python, line-protocol socket; measured warm round trip
  ~7 ms vs the Python tool's ~76 ms cold floor and cold `isabelle query`'s
  ~850 ms) — it may eventually take over the `query` console name for
  drop-in warm parity. A `query_version` handshake detects a stale server
  predating a component rebuild. The in-process Scala API stays the
  documented path for headless-PIDE hosts.

## Conventions

- Small single-concern commits; design decisions in commit messages with
  `[tag]` handles (inherited convention). Trailer credits the model that
  authored the commit, e.g.
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- No absolute personal paths (`/home/…`, `~/…`) in any committed file.
- Scala style: follow the Isabelle distribution's `src/Pure` idiom (2-space
  indent, `object`-per-concept, `isabelle.*` imports), not sbt/IDE idiom.
  No external Scala dependencies — the Isabelle classpath only.
- The Python tree and `tests/` are read-only reference material.
