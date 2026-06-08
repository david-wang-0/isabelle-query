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

- [ ] `[find-stmt]` `find-stmt PAT` — regex/token search over each
      entry's **statement slice only** (the declaration, not the proof
      body), a token-level approximation of Isabelle's `find_theorems`
      (explicitly *not* term/type-aware: no unification, no type
      matching).  Fills a real gap between the existing search verbs:
      `find` matches entry **names**, `grep` matches **every** source
      line; neither lets you ask "which lemmas are *stated about* this
      constant, whatever they're named."  Reuses the same statement/proof
      split that `-V/--verbatim` and the call graph already rely on.
      Should accept multiple patterns like `find`, and honour the PATH
      positionals via `_load_sections` for corpus scoping.

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

- [ ] `[graph-export]` Machine-readable export of the reference graph
      (`callers`/`callees` adjacency) and the import graph
      (`deps`/`uses`) as `--json` and/or DOT, for piping into `jq`,
      Graphviz, or external analysis.  Lowest effort of the open items —
      the adjacency already exists in `CallGraph` and the import maps;
      this is purely a serialization surface.  Decide the shape: `--json`
      flags on the existing graph subcommands vs a dedicated `graph`
      subcommand that emits the whole graph at once.

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
