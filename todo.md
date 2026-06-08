# Todo list

Ordered by priority (highest first).  Tags are stable handles for cross-
referencing in commits/PRs.

- [ ] `[multi-name]` Extend the remaining single-name subcommands to
      accept a **list**, so a `for n in A B C; do query callers $n` loop
      (which trips the permissions gate on every iteration) collapses to
      one gate-free call — the load-bearing reason to prefer `query` over
      looped shell `grep`.
      Already variadic (`nargs='+'`): `show`, `callees`, `deps`, `uses`,
      `find` (patterns), `methods`.  **Remaining:**
      - `callers` — the real work.  Its single positional `name` now
        collides with the trailing `files nargs='*'` PATH positionals it
        gained (two variadic positionals can't disambiguate).  Resolve by
        moving PATH scoping off the positional slot — e.g. an optional
        `--in DIR/FILE...` (or lean on the global `-R/--root`) — freeing
        `name` to become `nargs='+'`.  Mirror `callees`'s blank-line-
        separated per-name reporting.
      - `theory` / `defs` / `outline` — each takes a single theory name;
        making them `nargs='+'` is cheap and consistent, but lower value
        (theory-scoped queries are batched less often than entry-scoped
        ones).  Do these alongside `callers` only if it's free.

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
      Open design questions (the headline comment-search gap is now
      *closed* — see Done):
      - The `-n` = `--names` overload still clashes with the universal
        grep/rg convention where `-n` = line numbers.  `largest` already
        sidestepped this (its count flag became `-N/--top`); decide
        whether to rename the `--names` short flag across
        `find`/`show`/`grep`/`theory`/`callers`/`callees` or document the
        deviation deliberately.
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
