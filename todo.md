# Todo list

Ordered by priority (highest first).  Tags are stable handles for cross-
referencing in commits/PRs.

- [ ] `[multi-name]` Extend the remaining single-name lookup verbs to
      accept a **list**, so a `for n in A B C; do query CMD $n` loop
      (which trips the permission gate on every iteration) collapses to
      one gate-free call — the load-bearing reason to prefer `query` over
      looped shell `grep`.
      **Done:** `show`, `callees`, `callers`, `deps`, `uses`, `find`
      (patterns), `methods`.  `callers` was the hard case — its `name`
      positional and the trailing `files` PATH positionals were two greedy
      positionals that argparse can't disambiguate.  Resolved per the CLI
      contract below: `callers` dropped its PATH positionals (a file-subset
      caller set is a footgun anyway — it reads as complete but isn't) to
      join the lookup family as a plain `NAME...` verb, scoped by `-R` /
      `--external`.
      **Remaining (optional, low value):** `theory` / `defs` / `outline`
      each take a single theory name; `nargs='+'` is cheap and consistent
      but theory-scoped queries are batched far less than entry-scoped
      ones.  Route them through `_add_subject_list_arg` if/when touched.

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

- [ ] `[locus-roundtrip]` Make the tool's output valid input to the tool —
      "be round-trippable", a tighter rule than "be consistent".  Three
      sites, one principle:
      - **Loci**: every emitted location is a marker-free `theory:line`
        that pastes straight into `enclosing` / `lines` / an editor.
        `callers`/`methods` drop the dangling rg `:` and the jammed
        `[in owner]`, rendering owner as a separate `name (TAG)` field
        (the format `methods --names` and `grep` already use); `enclosing`
        drops its stray `.thy`.  `_parse_locus` strips a trailing `:`/`-`
        defensively, so real rg/grep paste-ins (and context lines) resolve.
      - **Spans**: render every span with `..` (the *input* range grammar),
        not `-`, across `_format_extent` / `outline` / `largest` /
        `enclosing`, so a span on screen (`Tfin 8..12`) pastes into
        `lines`/`enclosing` without hand-translation.  This is what makes a
        visible range "chain into the next step".
      - **Names**: see `[disambig-names]` — a printed name must resolve back
        to exactly one theory.
      Most of this ships alongside the `enclosing`-range / grep-line-scope
      / `lines`-colon batch; tracked here so the principle is not lost.
      **Landed in 0.2.7:** the `enclosing` half — `_parse_locus` strips the
      rg `:`/`-` marker and accepts `A..B`, and `enclosing` emits the bare
      `theory:line` form.  **Pending for 0.3.0:** the span-`..` render and
      the `callers`/`methods` reformat (the muscle-memory-breaking pieces).

- [ ] `[feature-audit]` Standing critical pass over each subcommand:
      output formats, defaults, and past design choices.  Re-benchmark
      against AWS AutoCorrode's `iq` tool
      (`https://github.com/awslabs/AutoCorrode/blob/main/iq/README.md`)
      to see which of its affordances we still lack.
      Open design questions (the headline comment-search gap and the
      `-n`/`--names` overload are now *closed* — see Done):
      - The `grep` render format (location + owner + line) vs `iq`'s.
      - Optional: a comments-/prose-**only** view.  `grep -a` is additive
        (live source *plus* comments); there's no way to see *only* the
        cartouche prose, which is what a PDF-commentary reader wants.

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

- [ ] `[graph-export]` Machine-readable export of the reference graph
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
