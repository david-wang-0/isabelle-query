# Todo list

Ordered by priority (highest first).  Tags are stable handles for cross-
referencing in commits/PRs.

- [ ] `[theory-refs]` Theory-level reference rollup: aggregate the
      per-entry `callees` graph up by owning theory to list what a theory
      **references** — the complement of `theory -n` (which lists a
      theory's own exports), terse with per-name counts.  Note this is
      finer-grained than `deps`/`uses`: those work at the `imports`-clause
      level (theory A imports theory B), whereas this is citation-level
      (which *entries* a theory's proofs actually invoke), so it surfaces
      imports that are declared but unused, and the converse.
      Pairs with a new `--theory THY` scope on `find` so a name search can
      be confined to one theory.

- [ ] `[disambig-names]` AFP-scale output qualifies theory names by the
      **minimal distinguishing path**.  `query largest` (and any verb that
      prints a bare theory name) currently strips both `.thy` and the
      dirname, so across the AFP's thousands of theories the result is a
      wall of unqualified `Bla` / `Foo` — collisions with no way to tell
      `ae/Bla` from `ar/Bla`.  Show just enough *leading* path to make each
      printed name unique within the result set — `ae/Bla`, `ar/Foo` — but
      not the shared root prefix (`t/ae/Bla`).  Compute the shortest path
      suffix unique among the names actually shown, so a single-session run
      stays bare `Bla` and only genuine collisions grow a prefix.  Lands on
      `largest` first.  Deeper than cosmetics: it is a prerequisite for the
      `theory:line` **round-trip** convention (see `[locus-roundtrip]`) — a
      bare `Bla:11` locus is unresolvable when two `Bla`s exist, so the
      emitter must qualify the name far enough for the resolver to round it
      back to one theory.

- [ ] `[feature-audit]` Standing critical pass over each subcommand:
      output formats, defaults, and past design choices.  Re-benchmark
      against AWS AutoCorrode's `iq` tool
      (`https://github.com/awslabs/AutoCorrode/blob/main/iq/README.md`)
      to see which of its affordances we still lack.
      Open design questions (the headline comment-search gap, the
      `-n`/`--names` overload, and the `grep` owner-column span are now
      *closed* — see Done):
      - Optional: a comments-/prose-**only** view.  `grep --with-comments`
        is additive (live source *plus* comments); there's no way to see
        *only* the cartouche prose, which is what a PDF-commentary reader
        wants.

- [ ] `[grep-plain]` Optional `--plain`/`--raw` override on `grep`:
      force plain line-grep (no entry/comment parsing) instead of the
      `infer` parse policy.  Post-routing-refactor the parse mode is an
      explicit per-command property (`_section_from`'s `parse` arg):
      `largest`/`sorry` are `"syntax"`, `grep` is `"infer"` (decide per
      source from the `.thy` suffix, stdin defaulting to syntax-aware).
      This flag would let `grep` take a third value, `"plain"`, forcing
      plain line-grep — useful to override the suffix-less stdin guess
      (`git show REF:notes.md | query grep PAT - --plain`) or the rare
      on-disk case (a `.thy` grepped as raw text, or a theory under another
      extension).  Default stays `"infer"`.  Add the flag through one shared
      helper per the CLI contract.  Scope: `grep` only — `lines` is already
      ignore-syntax (a parse flag is inert) and `largest`/`sorry` *are* the
      entry view (plain guts them), so this is **not** a uniform
      all-commands flag.  Low urgency: `"infer"` parsing of non-theory
      content already degrades gracefully (every line reads as live, so all
      matches still show; only the owner column goes `—`).

- [ ] `[grep-n-noop]` **(regression)** Restore `-n` as a silent no-op on
      `grep` (and any other verb that is a natural target of grep muscle
      memory).  Currently `query grep -n PAT FILE` dies with
      `error: unrecognized arguments: -n` and a usage dump.  `-n` is a real
      flag *elsewhere* in `query` (the `-n`/`--names` overload — see the
      `[feature-audit]` note), so grep can't make `-n` *mean* line-numbers;
      the fix is to **swallow** it (accept-and-ignore) so the ingrained
      `grep -n` reflex doesn't error.  Why it matters beyond cosmetics: an
      LLM caller (and humans) read the argparse error as "query grep is the
      wrong tool" and **fall back to raw `grep`/`rg`**, which is exactly the
      substitution `query` exists to prevent — the error actively
      de-reinforces adoption.  `grep` already prints `path:line` locations,
      so `-n` is redundant there anyway.  This was previously a deliberate
      no-op and appears to have regressed; re-add it (and a
      `tests/test_cli_parser.py` case pinning `grep -n` = `grep`).
      Considered alternative (rejected): *don't print line numbers by
      default, let `-n` turn them on* (grep-faithful).  Rejected because the
      line isn't a grep-`-n` prefix here — it's the `theory.thy:LINE  owner`
      **locus**, load-bearing for navigation and the `theory:line`
      round-trip (`enclosing`/`at`/`lines`; see `[disambig-names]`).
      Dropping it by default is a functional regression, and the always-on
      locus is *why* the no-op works: swallowing `-n` already yields the
      line the reflex wanted.  The genuine tension — global `-n` = `--names`
      vs grep-brain `-n` = line-numbers — belongs to the `[feature-audit]`
      `-n`/`--names` overload question, not to a grep default change.
      (`callers`/`callees` adjacency) and the import graph
      (`deps`/`uses`) as `--json` and/or DOT, for piping into `jq`,
      Graphviz, or external analysis.  Lowest effort of the open items —
      the adjacency already exists in `CallGraph` and the import maps;
      this is purely a serialization surface.  Decide the shape: `--json`
      flags on the existing graph subcommands vs a dedicated `graph`
      subcommand that emits the whole graph at once.

- [ ] `[countstr]` **(exploratory — shape not settled).**  Record the itch,
      not a design.  The need: before a multi-site rewrite (a `replace_all`,
      a `sed`-style sweep), verify *exactly* how many times an exact,
      usually **multi-line** literal block occurs in a file, so the edit's
      reach is known up front — the "inventory the whole match set before
      claiming 'all instances'" discipline applied to a literal block.
      Today this needs an ad-hoc `python3 -c` count (or a throwaway script),
      which is precisely the gate-tripping shell the tool exists to retire.
      Why the shape feels off — the open tensions, all unresolved:
        - **Scope creep.**  `query` is theory-aware (entries, call graphs,
          syntax slices); a raw literal counter is generic text counting,
          nearer `grep -Fc` than anything semantic.  Does it belong here at
          all, or is reaching for `query` over `grep` here a category error?
        - **Not line-count.**  `grep -c` (and the planned `[grep-plain]`)
          count matching *lines*; the whole point is a *multi-line* block as
          one unit, which line-grep structurally can't express.  So this is a
          genuinely new counting mode, not a flag on existing `-c`.
        - **Literal vs regex.**  The `replace_all` use is *fixed-string*
          (exact block, no metachar surprises) — so `-F`/`--fixed`, not the
          regex `grep` takes.  A multi-line *regex* count is a different,
          slipperier beast; conflating them is part of why the shape wobbles.
        - **Input ergonomics (the worst part).**  Supplying a multi-line
          literal on argv is miserable; via stdin (`query countstr FILE -` /
          `< block`) the `-`/stdin sentinel is already the *file* side
          (`[stdin-path]`), so block-on-stdin + file-on-disk collide.  This
          tension is the main reason it isn't ready.
      Tentative leaning (do NOT build yet): a `--fixed`/`-F` + `--count`
      multiline mode on `grep` rather than a new verb — but only once the
      block-input ergonomics are solved.  Low value / low urgency: an inline
      one-liner covers it today; this is about retiring that one-liner, not
      unblocking anything.

- [ ] `[doc-graph]` **(exploratory — scope-uncertain.)**  A reference
      graph + activity survey over a project's **prose documentation**
      (design memos, spec docs, decision records) — the complement of the
      theory-entry graph.  As an Isabelle/Isar project accretes design
      docs, you periodically need to know which docs are stale
      (last-touched, commit churn) and which are actually referenced — and
      critically, *from where*.  Load-bearing lesson: a doc's real
      coupling often lives **outside the
      prose** — a `ROOT` description, a `.thy` `\<comment>`, a sibling
      test-harness's relative link — so a markdown-only "who cites this?"
      scan undercounts; the citer scan must span the whole tracked tree.
      Concrete prototype: a `doc-audit.py` — two modes, a per-file
      table (LASTCOMMIT / commit-count / lines / REF-A [cites from the
      active steering docs] / REF-ALL [cites anywhere in the tree]) and a
      `--refs FILE` location dump (`path:line`).
      Scope tension (same flavour as `[countstr]`): `query` is
      theory-aware (entries, call graphs, syntax slices over `.thy` under
      the `.isabelle-query` root); a doc-reference graph is generic prose
      over arbitrary files, nearer `grep -rl` + `git log` than anything
      semantic.  Does it belong in `query` at all, or is the doc corpus a
      sibling tool's job?  Two sub-questions if it lands here: (a) the
      citer scan wants the *whole* repo, which breaks the `t/`-only
      `.isabelle-query` scoping; (b) it shares machinery with
      `[graph-export]` (serialise an adjacency) and `[theory-refs]`
      (citation rollup) but over a different node set (files, not
      entries).  Record the need; don't build until the scope call is
      made.

- [ ] `[find-conjunction]` Conjunctive multi-pattern on `find` (esp.
      `find --statement`).  Today multiple `PATTERN`s run as **separate**
      searches ("run each search in turn, blank-line separated") — an OR /
      one-report-per-pattern.  The common find_theorems-style query is the
      **AND**: "the entry whose statement mentions *all* of these."  Real
      episode: hunting a length lemma in a large proof corpus,
      `query find --statement 'length' 'encode_entry'` is useless — pattern
      1 alone returns ~180 hits (every `length` in the corpus) — so the
      user falls back to `query find 'encode' | grep length` or `outline THY
      | grep`, exactly the pipe-to-grep the tool is meant to replace.
      Proposal: an `--all`-patterns / `-A` / `--and` flag that keeps only
      entries matched by **every** PATTERN (intersect the per-pattern hit
      sets), reported once.  Composes with `--statement` (the high-value
      case: `find --statement --and 'length' 'encode_entry'` ≈
      `find_theorems "length _ = _" name:encode`), and with `--names`/`-c`.
      Keep the current OR default (it's the "run a batch of searches"
      idiom); `--and` is opt-in.  Small, self-contained; the OR machinery
      already collects per-pattern sets, so this is a fold + flag.

## CLI contract (follow when adding or changing commands)

Two families, each matching an external convention; a command's primary
positional decides which one it is.

- **lookup** (git/brew: `git show REF...`, `brew deps FORMULA`) — the
  primary positional is a **subject** (entry/theory name), one-or-more,
  reported in turn.  Add it with `_add_subject_list_arg`.  **No trailing
  PATH positionals**: "who calls X" is corpus-global, so scope with the
  global `-R/--root` and narrow with *semantic* flags (`--external`,
  `-r/--recursive`), never a file subset.  Members: `show`, `callers`,
  `callees`, `deps`, `uses`, `theory`, `defs`, `outline`, `methods`.
- **search** (grep/rg: `grep PAT PATH...`) — the primary positional is a
  pattern (or nothing), and **paths are the trailing positionals**, added
  with `_add_path_files_arg` (resolved by `_load_sections`).  Members:
  `grep`, `largest`, `sorry` (and `find` once it gains PATH/`--theory`
  scope under `[theory-refs]`).

Shared-feature help text comes from one helper each, so wording can't
drift command-to-command — always add a feature through its helper, never
inline:
`_add_subject_list_arg` (subject list), `_add_path_files_arg` (PATH),
`_add_names_flag` (`--names`; **no `-n`** — reserved for grep's
line-number meaning), `_add_count_flag` (`-c/--count`),
`_add_with_comments_flag` (`--with-comments`; the *only* prose-search
toggle on `find`/`grep` — **no `-a`** for it, since `-a` is the
`_add_mode_flags` show-all mode), `_add_mode_flags` (`-a` / `--names` /
`-c` bundle), `_add_verbatim_flag`, `_add_comment_flags`,
`_add_context_flag` (`-U/--context`; one short flag everywhere, default
per-command), `_add_drop_names_flag`.

## Done / obsolete

- [x] `[src-doc-attribution]` **Fixed: a leading `text` doc block is charged
      to the entry it documents, not the preceding one.**  `src` / `thy_end`
      was `next_entry_thy_line - 1`, so the inter-entry blank *and the
      following entry's leading docstring* folded into the **preceding** entry:
      (a) `enclosing FILE:L` for an `L` in a leading doc named the *previous*
      lemma; (b) `src A..B` (show / find / outline / enclosing) overstated the
      entry by that trailing doc.  `body` was always correct and is unchanged.
      Fix: a span is now `src_start..thy_end`, where `src_start` is the entry's
      leading preamble (if attached) else its declaration line; `compute_spans`
      bounds each span at the *next* entry's `src_start`, so the doc goes to the
      entry it precedes and the predecessor's `src` ends at its body + trailing
      blanks.  `_enclosing_entry` / `_build_line_index` / `cmd_enclosing` range,
      `_format_extent`, and the outline / largest / unused span columns read
      `[src_start, thy_end]`; `_locus_role` gains "in preamble"; `line_count`
      measures the full src span.  Mechanism: `_attach_comments` split into
      `_attach_preambles` (before `compute_spans`) and `_attach_roadmaps` (after
      — needs `thy_end`).  Only the existing ≤30-line / ≤3-blank-gap preamble
      rule is re-homed; a large section narrative stays put.  Tests:
      `tests/test_src_doc_attribution.py` (preamble ownership, enclosing on a
      doc line → following entry, extent rendering, end-to-end `enclosing`);
      `scripts/probe_entry_spans.py` for re-verification.  (Filed
      2026-06-22, during the AR Forward split.)

- [x] `[function-defs]` **Fixed: `function` definitions are indexed as
      entries.**  `function` (a `thy_goal_defn` — defines a constant then proves
      its well-definedness) was absent from `DECL_RE`, so its principal constant
      never reached the index (missing from `theory --names`, `defs`, `find`),
      silently under-covering any per-theory entry list built from `--names`.
      Fix: add `function` to `DECL_RE` / `TAG_MAP` (tag `FUN`); it routes through
      the `def` branch like `fun`, the `(sequential)` option is stripped by
      `_strip_decl_prefix`, and the trailing `by` / `termination` proof falls
      into the body span.  Scope note: the report's guess that `primrec` /
      `inductive` / `inductive_set` were also broken was wrong — those were
      already in `DECL_RE` and index fine (verified, and pinned by a test).
      Tests: `tests/test_names.py::DefinitionalCommands`;
      `scripts/probe_entry_spans.py`.  (Filed 2026-06-30,
      during the shift-wrap retirement dependency analysis.)

- [x] `[open-ranges]` **Added: open-ended line ranges `A..` (to EOF) and
      `..B` (from line 1).**  The range grammar already had a single split
      point — `_parse_line_range` — feeding every surface (`lines`, and the
      `enclosing` / `grep` `FILE:A..B` locus via `_parse_locus` /
      `_split_path_window`).  The bare/closed form rejected an empty side
      (`int("")`).  Now an empty *lower* resolves to line 1 in the parser
      (start-of-file is universal, no file needed) and an empty *upper*
      returns `end is None`, a sentinel each sink substitutes its own length
      for: `cmd_lines` → `n_lines`, `cmd_enclosing` → `sec.thy_lines`, the
      grep window → `len(lines)` (where the None-guard collapsed into a
      uniform full-file default).  Round-trip preserved: the `lines`
      colon-form reconstruction emits `A..` (not `A..None`), and
      `enclosing FILE:A..` echoes the **resolved** concrete span
      (`Demo:5..11`) so it pastes back in *and* reveals where EOF is.  The
      `lo == hi` point-test in `enclosing` stays on the *raw* hi, so `A..`
      is always a range, never mistaken for a single line.  Diagnostics echo
      the open spec the user typed (`# range 9..: past end of file`).  Help
      text on `lines` / `enclosing` updated.  Tests:
      `tests/test_open_ranges.py` (18: parser unit forms, `lines`
      EOF/from-1/whole/past-EOF, `enclosing` open span + resolved echo,
      `grep` open window) — promoted from the live smoke probes.

- [x] `[deps-qualified]` **Fixed: `deps`/`uses` resolve session-qualified
      in-project imports.**  `parse_thy_imports` returns the raw
      `imports`-clause token, but the section index is keyed by **bare**
      theory name (`{s.theory: s}`), so same-session imports (bare
      `Substrate`) matched while cross-session ones
      (`"Proj_Base.Substrate"`) missed: `deps` tagged them
      `[out-of-project]` and `uses` *silently dropped* the importer — the
      worse half, since a "collect importers" loop turns a missed match into
      a confident, wrong "No in-project theory imports X".  Fix: a
      `_resolve_import` helper maps a raw token to the bare in-project theory
      it denotes — direct hit first, else the tail after the last `.` —
      applied at all three in-project sites (forward direct, forward
      recursive, reverse).  The recursive forward walk now enqueues the
      **resolved** bare name, so the transitive closure follows *through* a
      qualified hop instead of re-missing on it.  The raw token is kept for
      the `[out-of-project]` display, so a genuinely external
      `HOL-Library.FuncSet` still prints verbatim.  Tail-matching is correct
      for every realistic tree (external leaf-names like `FuncSet`/`List`
      don't collide with project theory names).  **Known limit (the province
      of `[disambig-names]`):** an external `Sess.Foo` whose tail equals an
      in-project `Foo` and whose `Sess` isn't an in-project session would
      mis-resolve; the airtight guard is to gate the tail-match on the
      qualifier naming a known session (`SessionInfo.name`), deferred as a
      collision concern rather than a routing one.  Tests:
      `tests/test_deps_qualified.py` (two-dir fixture — qualified import →
      `[direct]` not `[out-of-project]`, reverse lists the importer, the
      external import stays out-of-project, recursive reaches the qualified
      child; plus `_resolve_import` unit cases).  (Filed
      2026-06-13, during the AFP-refactor dependency survey.)

- [x] `[multi-name]` Single-name lookup verbs take a **list**, so a
      `for n in A B C; do query CMD $n` loop (which trips the permission
      gate every iteration) collapses to one gate-free call — the
      load-bearing reason to prefer `query` over looped shell `grep`.
      Shipped: `show`, `callees`, `callers`, `deps`, `uses`, `find`
      (patterns), `methods`.  `callers` was the hard case — its `name`
      positional and the trailing `files` PATH positionals were two greedy
      positionals argparse can't disambiguate; resolved per the CLI
      contract by dropping `callers`' PATH positionals (a file-subset
      caller set is a footgun anyway — it reads as complete but isn't) to
      join the lookup family as a plain `NAME...` verb, scoped by `-R` /
      `--external` (`[callers-multi]`).  **Scope still pending (optional,
      low value):** `theory` / `defs` / `outline` each still take a single
      theory name; `nargs='+'` via `_add_subject_list_arg` is cheap and
      consistent but theory-scoped queries batch far less than entry-scoped
      ones, so this tail is deferred until one is touched for other reasons.

- [x] `[locus-roundtrip]` "Output is valid input" — locations and spans now
      share one grammar, so the tool's output pastes back into the tool.
      Loci+spans landed across **0.2.7** (the `enclosing` half) and **0.3.0**
      (the rest):
        - **Loci** — every emitted location is a marker-free `theory:line`.
          `callers`/`methods` dropped the dangling rg `:` and the jammed
          `[in owner]`, rendering owner as a separate `name (TAG) lo..hi`
          field (via the shared `_owner_field`); `enclosing` emits bare
          `theory:line`.  `_parse_locus` strips a trailing `:`/`-` so real
          rg/grep paste-ins and context lines resolve.
        - **Spans** — `..` (the input range grammar) everywhere:
          `_format_extent` (`[src A..B]` → show/find/theory/enclosing),
          `outline`, `largest`, `unused`.
      Applications shipped on the same grammar: `enclosing FILE:A..B` range
      mode, `grep PATH:A..B` line-scoping (`_split_path_window` +
      `_load_sections(windows=True)`), and `lines` colon-form `FILE:RANGE`
      (`_lines_file_and_ranges`).  Tests: `test_enclosing`, `test_locus_format`,
      `test_grep_window`, `test_lines_forms`.  **Remaining (separate item):**
      the **Names** third of the principle — `[disambig-names]`, so a bare
      `Bla:11` resolves when two `Bla`s exist.

- [x] `[enclosing-drilldown]` Nearest enclosing *block* for `enclosing`
      (the deferred half of `[enclosing]`).  Inside a large structured proof
      the owning entry is usually what you already know; the useful answer is
      the **smallest live block** the line sits in, as a pasteable `A..B`
      span — `Nested:13 → structured (LEMMA) … ▸ have key 11..14`.  Three
      modes on an outer→inner spectrum: `-e/--entry` (entry only, the
      original output), default (nearest/innermost block), `-b/--blocks`
      (full nesting path, entry then each block outer→inner).  Motivated by
      a large proof port — a build failure deep in a 588-line proof resolves
      to the `have key` block, not just the lemma.
      **How:** a lightweight, on-demand `_proof_blocks` scan of *just* the
      one resolved entry's proof body (no index/Entry bloat) — a stack of
      `proof` / brace-only `{` opens popped on `qed` / `}`, each block
      labelled by its goal-introducer (`have key:` → `have key 11..14`).  The
      lemma's own outer `proof` is suppressed (the entry already represents
      it); only blocks strictly inside it are reported.  **Conservative &
      fail-safe:** openers/closers are line-anchored (a `proof`/`{` buried in
      a term string or a set-comprehension is ignored), only live lines are
      read (comment/text skipped), and an unbalanced stack returns None so
      output degrades to the entry rather than emit a span it can't stand
      behind.  A flat `by` proof or an in-proof line outside every block
      likewise degrades to the entry (no `▸`).  **Round-trip:** the block
      span is a locus — `▸ have key 11..14` pastes into `lines Nested 11..14`
      / `enclosing Nested:11..14`.  Tests: `tests/test_enclosing_blocks.py`
      over `tests/fixtures/Nested.thy`.  **Known limits (non-fatal, future):**
      inline one-line `{ … }` and inline `have … proof` openers aren't split;
      `case`/`next` sub-blocks inside a `proof (induction)` aren't tracked
      (the nearest block is the enclosing `proof`); a goal proved via
      `using … proof` may label the block `proof` rather than the goal.  The
      "single nested level covers the common build-triage case" bet held.

- [x] `[enclosing]` Line-owner lookup — shipped as `enclosing` (alias
      `at`): `query enclosing FILE:LINE ...` names the entry whose
      `[thy_line, thy_end]` span contains each line, the inverse of
      `outline`.  Pure composition over the existing index — `_parse_locus`
      (last-colon split, so `sub/Foo.thy:42` and `Foo:42` both work),
      `_resolve_theory` for the FILE half (path *or* bare name, `-R`-scoped),
      `_enclosing_entry` for containment, `_format_extent` for the `[src
      A-B]` block.  Lookup-family: one-or-more `FILE:LINE` loci (no PATH
      positionals — the FILE is in the locus), so a batch of build-failure
      loci resolves in one gate-free call; the colon form (not a two-
      positional `at FILE LINE`) is the universal compiler-locus convention
      and dodges the two-greedy-positional argparse trap `callers` hit.
      Each hit also carries a statement/proof *role* (`_locus_role`, from the
      same `proof_line`/`decl_end_line` the renderer slices on) — knowing the
      failing line is the statement vs a proof step tells you what to edit.
      Unlike a `^lemma `-only `awk` scan it names `definition`/`fun`/
      `datatype` owners and reports *no owner* for an inter-section gap
      (where the awk would wrongly attribute the line to the lemma above).
      Malformed / unresolved loci go to stderr without derailing the batch.
      Tests in `tests/test_enclosing.py`.  **Not built (deferred):** the
      proof-internal `have`/`obtain` label drill-down the original note
      floated — the Entry model doesn't capture intra-proof labels yet, so
      that is its own feature, not part of this span-level lookup.

- [x] `[find-stmt]` Statement-slice search — shipped as a shared
      `--statement` (alias `--stmt`) flag, one spelling via
      `_add_statement_flag`, applied per each verb's nature (the find/show
      complement):
        - `find --statement PAT` — match the regex against each entry's
          **declaration slice** (`sec.slice(thy_line, decl_end_line)`, the
          statement, not the proof) instead of its name, a token-level
          `find_theorems` (NOT term/type-aware).  Surfaces lemmas *stated
          about* a constant whatever they are named.  It is a *match-locus*
          flag only: matched entries still render the usual way (it composes
          with `-V`).
        - `show NAME --statement` — render **only** the declaration slice, a
          genuinely new view (both the default render and `-V` include the
          proof; `-V` is the *full* slice).  On `show` it is mutually
          exclusive with `-V` (opposite ends of the slice spectrum), enforced
          by an argparse group; the narrower (statement) view wins
          defensively if both ever reach `render_entry`.
      `_emit_matches` takes an explicit `statement=` *render* selector (not
      read off `flags`) so `find`'s match-locus meaning can't bleed into how
      results are rendered.  Slice text via the `_statement_text` helper
      (falls back to `entry.text` for source-less entries).  Tests in
      `tests/test_statement_slice.py`.  **Scope still pending:** `find`
      remains `-R`-scoped only — the `--theory THY` confinement lives under
      `[theory-refs]`; no PATH/stdin (its multi-pattern positional precludes
      a second greedy positional).
- [x] `[stdin-path]` — `-` is a PATH sentinel for **read from stdin**.
      `_load_sections` grows a one-shot `-` branch that parses the piped
      stream as a theory (entries, live/comment classification, owning-entry
      labels), so the whole search family (`grep`/`largest`/`sorry`) gets it
      at once; `cmd_lines` reads stdin directly since it bypasses section
      parsing.  The load-bearing case works:
      `git show REF:FILE | query lines - A..B` inspects a pre-migration proof
      with no scratch file, line numbers preserved (whole stream read, then
      sliced).  Sections carry a synthetic `<stdin>` location label.  The
      remaining lookup verbs (`outline`/`theory`/`defs`) inherit it for free
      once they grow PATH positionals under `[multi-name]`.  Tests in
      `tests/test_stdin_path.py`.
- [x] `[tactic-stats]` Proof-method usage stats — shipped as the
      `methods` (alias `method`) subcommand: `methods` gives the ranked
      tally of every proof method with counts and corpus share; `methods
      NAME` lists every located use of one method (the `callers` analogue
      for tactics).  Syntactic, exactly as scoped.
- [x] `[grep-comments]` (was folded into `[feature-audit]`) — grep is no
      longer blind to `\<comment>`/`text` cartouche prose: `grep -a/--all`
      includes those matches (tagging each non-live hit `[in
      comment/text]`), and `find --with-comments` does the same for name
      search.
- [x] `[callers-multi]` (the hard part of `[multi-name]`) — `callers`
      takes `NAME...`, dropping its PATH positionals to join the lookup
      family (see CLI contract).  Tests in `tests/test_cli_parser.py`.
- [x] `[grep-owner-span]` (from `[feature-audit]`) — `grep`'s owner column
      now routes through the shared `_owner_field` (the real win: name / tag
      / no-owner rendering can't drift from `callers` / `methods`), but
      *without* the `lo..hi` span — `_owner_field(owner, span=False)`.  The
      span is a per-command **content** choice, not a rendering one, so it is
      a parameter: `callers` / `methods` keep it (a lookup hit's next move is
      to open the owning lemma, so its span is the next locus), `grep` opts
      out (a search hit is already a precise locus — its matched line — so
      the owner's whole-lemma span is constant across the lemma's hits,
      repetitive, and blurs grep toward an `enclosing -e` report).  NB the
      tag is historical: an initial cut *did* show the span on grep; it was
      reverted on exactly that "grep is drifting into a line-owner report"
      objection, keeping the chokepoint and dropping the span.  Tests:
      `tests/test_locus_format.py` (`OwnerFieldSpan`, `GrepFormat`).

- [x] `[names-flag]` (the `-n` part of `[feature-audit]`) — dropped the
      `-n` short flag (collided with grep's `-n` = line numbers); the
      terse view is `--names` only, `-n` left free for its conventional
      meaning.  One-line change at the `_add_names_flag` chokepoint.
- [x] `[grep-with-comments]` (sibling of `[names-flag]`) — the search
      family's "also search prose" toggle is now `--with-comments` on
      *both* `find` and `grep`, via the shared `_add_with_comments_flag`
      helper.  grep's old `-a/--all` spelling is gone: on `find`, `-a`
      already means "show all matches" (the `_add_mode_flags` lookup mode),
      so a grep-only `-a`-for-prose forked `-a`'s meaning across the two
      search verbs.  Collapsed the duplicate `include_all`/`with_comments`
      flag fields into one.
- [x] `[context-flag]` — `--context` now uses one short flag, `-U`,
      everywhere.  `callers` dropped its inline `-C/--context` (a CLI-
      contract violation — features go through one helper) and routes
      through `_add_context_flag`, which gained a per-command `default`.
      `callers` is a lookup-family verb, so `-U` matches its family; and
      rg's `-C` is context on both sides whereas `callers` shows only
      trailing lines (rg's `-A`), so `-C` was mis-aligned anyway.
- [x] `[version-flag]` (from `[feature-audit]`) — top-level `query
      --version`.  Reads the installed dist version via
      `importlib.metadata.version("isabelle-query")` (single source of
      truth: only `pyproject.toml` carries the number; no `__version__`
      literal to drift).  Lazy custom `argparse.Action` so the metadata
      lookup is paid only when asked, not on every sub-100ms run.  Note for
      editable installs: the label reflects the last `pip install -e`, so it
      can lag the live checkout.
