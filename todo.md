# Todo list

**Open work only.** Ordered by priority (highest first).  Tags are stable
handles for cross-referencing in commits/PRs — and, once an item ships, for
finding it again with `git log --grep`.

Conventions for changing the tool (the CLI contract, verification habits) live
in `CONTRIBUTING.md`.

- [ ] `[axiom-untyped]` An `axiomatization` constant with NO type ascription is
      not indexed.

          axiomatization glob_one and glob_inv     -- FOL/ex/.../Locale_Test1:722
            where glob_lone: \<open>prod(glob_one, x) = x\<close>

      indexes `glob_lone` (the axiom) but neither constant.  `_AXIOM_NAME_RE`
      is `([A-Za-z_][A-Za-z0-9_']*)\s*:` — it requires a colon, which is why a
      TYPED constant matches (`f :` out of `f :: "nat"`) and an untyped one
      never does.  The split is the type annotation, not the `and`.
      **Cost: 1 command, corpus-wide.**  Measured over the AFP, FOL, ZF and
      HOL/: exactly the one case above, and zero elsewhere.  That is the whole
      argument for leaving it: the colon is what stops the scan matching
      `where`, `and`, and any word in a proposition, and relaxing it to catch
      one declaration risks the over-match it was written to prevent.
      What would make it worth doing is a narrower rule rather than a looser
      one — before `where`, on the command's own lines, a bare `NAME (and
      NAME)*` list IS a constant list — which is a small grammar of its own,
      not a regex tweak.  Split out of `[axiom-names]`, whose other half
      shipped; the two turned out not to share a scan after all (the phantom
      was an unconditional placeholder, not a missing lookahead).

- [ ] `[name-is-not-identity]` A theory NAME is used as a section's identity in
      four more places, and each collapses two theories into one over a corpus.
      Split out of `[disambig-loci]`, which fixed the fifth: `_find_callers`
      handed its hits' theory names up and `cmd_callers` looked them back down
      through `graph._sections_by_theory`, a last-wins `{name: section}` map,
      so 9,239 of `callers assms`' 161,426 AFP rows printed a line out of one
      file beside an owner out of another.  The same map, and the same
      last-wins collapse, still backs:

          graph._Visibility.by_theory   import closure keyed by theory name
          graph._build_line_index       {theory: [entry spans]} for _entry_at_line
          commands._noise_ranges        {theory: [prose spans]}
          commands._build_def_sites     {theory: {name: def-site ranges}}

      **758 of the AFP's 9,910 sections are shadowed** in such a map (461 names
      shared, covering 1,219 sections).  Unmeasured whether any of the four
      actually mis-answers — several are read back with the *same* section that
      wrote them, in which case the collapse is invisible and the fix is
      hygiene.  `_Visibility` is the one to measure first: it decides the
      import closure per name, so two `Preliminaries` share one closure and a
      citation may be kept or dropped on the other theory's imports.
      Measure before changing anything — the parent item was filed as cosmetic
      and turned out to be a wrong-attribution bug, and the reverse is just as
      possible here.  `scripts/probe_disambig_loci.py` counts the shadowing.

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

- [ ] `[comment-newline]` A `\<comment>` may be separated from its cartouche
      by a newline.  Isabelle's `comment_prefix` allows any blanks between the
      marker and the cartouche it owns, newlines included, so

          shows \<open>\<exists> k. u k = \<emptyset>\<close>
          \<comment>
          \<open>
            This lemma could easily be generalized ...
          \<close>

      is ONE formal comment.  `_MARKER_OPEN_RE` matches marker-plus-cartouche
      as a single token within a line, so the scanner sees a bare `\<comment>`
      and then a separate LIVE cartouche, and charges all the prose to the
      statement above it (`decl_end_line` 9 where the declaration ends at 5).
      Costs 1 record in the whole AFP — Substitutions_Lambda_Free:58 — so this
      is low priority, but it is the only entry on this list whose fix is
      confined to one regex and its state machine.  D5 in the Scala port's
      `dev/DIVERGENCES.md`.

- [ ] `[keyword-scope]` The custom-command table is unioned over the whole
      ROOT, which is right for a session and too coarse for a corpus.  Both
      implementations mirror Isabelle's session-wide `Keywords.++`, but with
      the AFP `thys` directory as one root that puts Optics' `alphabet` in
      scope for Formula_Derivatives, whose `sublocale DA < DAs` /
      `alphabet init delta ...` continuation line then reads as a
      declaration.  16 records over 4 entries (Formula_Derivatives,
      MSO_Regex_Equivalence, UTP, Circus), and only when the whole AFP is
      passed as ONE root — each of the four is clean read as its own root.
      Fixing it means scoping the table per session, which changes the parse
      of every custom-command entry, so it belongs with the session model.
      Newly visible rather than newly introduced: before `[keyword-kind-quoted]`
      the quoted-kind bug kept `alphabet` out of the table and hid it.
      A second instance found while measuring [marker-decl]:
      `Isabelle_C/C_Appendices:831` mints a phantom `DEF` whose name is read
      out of a `text` block's prose, because Isabelle_C's `C_export_file` is in
      scope for a theory that never imports it.  Same shape, different session,
      and it shows the cost is not confined to one continuation-line accident.
      D4 in the Scala port's `dev/DIVERGENCES.md`.
      Sibling observation, same shape, deliberately not filed separately
      (D11): the method/attribute table is resolved from whichever declared
      sessions happen to have a BUILT HEAP, so `callers` can answer
      differently on two machines reading identical sources — heap union, the
      committed census union, or the Pure floor, three tables and three
      answers.  It is documented behaviour rather than a defect (CLAUDE.md
      says so), and it does not reproduce here — this machine has no AFP heaps,
      so the default and `ISABELLE_QUERY_NAMESPACE=committed` both answer 1261
      for `callers mono -c`.  Worth knowing before any measurement is quoted
      across machines.

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
      `.isabelle-query` scoping; (b) it shares machinery with the shipped
      `graph` verb (serialise an adjacency) and `refs` (citation rollup)
      but over a different node set (files, not entries).  Record the
      need; don't build until the scope call is made.

## Done

Nothing — by design. Completed work is recorded in its commit messages, which
carry the reasoning, the rejected alternatives and the before/after, and cannot
drift from the code the way a summary of them can. Recover one by its tag:

    git log --grep='\[locus-roundtrip\]'

See `CONTRIBUTING.md`.
