# Todo list

**Open work only.** Ordered by priority (highest first).  Tags are stable
handles for cross-referencing in commits/PRs — and, once an item ships, for
finding it again with `git log --grep`.

Conventions for changing the tool (the CLI contract, verification habits) live
in `CONTRIBUTING.md`.

- [ ] `[common-shim]` **What is `common.py` for, now that the parser left?**
      Measured by `scripts/probe_common_surface.py`: it offers **26** names,
      in-repo code wants **15**, and of those **9 are plain redirects** to
      public `isabelle_layout`.  The remaining six are the module's only claim
      to exist -- and five of them are wanted **by tests alone**
      (`MARKER_NAME`, `classify_import`, `_strip_block_comments`,
      `_strip_cartouches`, `is_hol_base`), while the sixth,
      `is_known_nonhol_base`, is public in `isabelle_layout.distribution`.
      So **no production code in query needs anything that is not public
      upstream**; `common.py` is a redirect kept alive by two things, and both
      are now decidable:

      * **The deprecation window.**  11 of the 26 names are used by nothing in
        this repository -- they are there for callers outside it that still
        import `isabelle_query.common`.  That window was opened when
        `isabelle-layout` was unpublished and there was nowhere else to go;
        it is on PyPI now, so those callers can move.
      * **Tests of code that is no longer ours.**  `test_thy_header.py`,
        `test_session_theories.py`, `test_base_logic.py` and
        `test_discover_roots.py` test the moved parser through query's shim,
        and `isabelle-layout` already carries its own versions of all four
        (plus `test_public_api.py`, `test_project_root.py`).  These duplicates
        are the *only* reason query touches layout's private names at all.

      This subsumes `[layout-privates]`, which framed the private imports as
      query's exposure.  They are not: **8 of the 9 private re-exports are
      unused in this repository**.  The exposure is a service to downstream
      callers, and closing the window removes it without changing a line of
      query's logic -- which is also what would let the uncapped dependency
      stop being a standing risk.
      Order: confirm no downstream project still imports
      `isabelle_query.common` (this cannot be checked from here); drop the 11
      unused re-exports; retire the duplicated tests in favour of layout's;
      then decide whether the six survivors justify a module or should be two
      direct `isabelle_layout` imports at their call sites.
      Deleting a compatibility surface is outward-facing -- the user's call,
      not a tidy-up.

- [ ] `[watchdog-guard]` `common.run_guarded` is dead here and duplicated
      upstream.  Nothing in `src/`, `tests/` or `scripts/` calls it; its own
      docstring says so ("DEPRECATED -- unused in this repository ... retained
      pending that tooling's review"), its callers are `bin/build_record.py`
      and `bin/isabelle-watchdog.py`, which are not in this repository, and
      `isabelle_watchdog.guard` already carries a copy.  It is the last thread
      connecting query to watchdog, which it otherwise does not use at all.
      The review it was retained for is now possible: watchdog is published
      (0.3.1) and depends on `isabelle-layout`, so if the `bin/` tooling has
      moved into that package, query's copy is dead weight and should go.
      Deleting it is a (tiny) break in `common`'s surface, so confirm no
      downstream script imports it first -- that surface is the whole reason
      `common.py` still exists.  Pinned meanwhile by
      `tests/test_layout_surface.py`, with a comment saying the pin is not an
      endorsement.

- [ ] `[record-fields]` **(correctness.)** A `record` declares a constant per
      field — `record state = ip :: "ip" | sn :: "sqn"` binds `ip` and `sn` as
      selectors — and none is indexed.  `[declared-names]` deliberately left
      this out: a record's `=` introduces its *parent type*, and its fields
      are bare `name :: type` lines, so the datatype constructor scan would
      invent names if pointed at one (hence the DATATYPE tag gate in
      `_constructors`).  It needs a scan of its own over the same body
      `_scan_decl_body` now returns.  96 RECORD entries over 120 AFP entries.
      Also open, and cheaper: `axiomatization`'s name regex is `[a-z_]+`, so
      an axiom whose name starts with a capital (`AOT_model:38`'s
      `AOT_model_nonactual_world`) gets no entry at all.

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

- [ ] `[markup-oracle]` Ground truth for **spans and the step model**, from
      `PIDE/markup` in a built session database.  The #8 entity export gives
      names and a *name* position (`offset..end_offset` brackets the name, not
      the declaration), so declaration extents, command segmentation and
      comment regions — what `parsing.scan_regions` actually computes, and
      what `enclosing`/`outline`/`largest` and every `shape` metric rest on —
      have no oracle at all.  `PIDE/markup` is the theory text with Isabelle's
      markup interleaved: on `DitherTM` it decodes to 87 `command_span`s, each
      carrying the keyword, Isabelle's own **kind** and an exact extent
      (`definition 66..68`, `lemma 74..74`, `by 75..75`).  The kind field
      (`thy_goal_stmt` / `qed` / `prf_script` / `prf_decl`) is Isabelle's own
      version of the goal / closing / plumbing split `shape` builds by hand —
      on that theory `query` sees 38 steps where Isabelle marks 41 proof
      commands, which is a checkable discrepancy nothing currently checks.
      `scripts/probe_pide_markup.py` already decodes it.
      **Build it as a fixture generator, not a reference.**  A heap is a
      snapshot; comparing today's parse against it live would ossify, and the
      only cure for a stale reference is a rebuild — the one thing this tool
      must never do.  `isabelle_sources` carries a plain SHA-1 digest and the
      compressed body of every source consumed, so (a) staleness is *decided*
      — gate every comparison on the digest and skip a moved theory with a
      reason, never as a disagreement — and (b) the snapshot contains its own
      inputs, so a `(source, answer)` pair harvested from it stays
      self-consistent forever and replays with **no Isabelle installed**.
      That is what gets these checks into `pytest` instead of a heap-dependent
      `make` target, and it is why building more heaps is worth it: the cost
      is paid once and the artifact is permanent.  Two constraints when
      harvesting: commit minimal extracted snippets, not whole AFP files
      (licensing and size), and record the Isabelle release in the fixture,
      since it pins that release's semantics.

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

- [ ] `[graph-export]` Machine-readable output for the citation graph
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

## Done

Nothing — by design. Completed work is recorded in its commit messages, which
carry the reasoning, the rejected alternatives and the before/after, and cannot
drift from the code the way a summary of them can. Recover one by its tag:

    git log --grep='\[locus-roundtrip\]'

See `CONTRIBUTING.md`.
