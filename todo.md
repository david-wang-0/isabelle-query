# Todo list

**Open work only.** Ordered by priority (highest first).  Tags are stable
handles for cross-referencing in commits/PRs — and, once an item ships, for
finding it again with `git log --grep`.

Conventions for changing the tool (the CLI contract, verification habits) live
in `CONTRIBUTING.md`.

- [ ] `[bare-provenance]` Split `n_bare` by *why* a goal step has no
      proposition.  It pools two unrelated things — **bare by construction**
      (`show ?thesis`, `also`, `case`, `interpret`) and **the scanner found
      none** — and that pooling is what hid issue #9(b) for as long as it did:
      a wrapped statement was booked as bare, where nobody would look for a
      scanner fault.  Suggested by the issue itself (its item 3, marked
      optional) and left out of the fix because it changes the emitted record,
      not just the numbers in it.  Two consequences worth having: a rise in
      `n_bare` becomes interpretable as a fact about writing style rather than
      possibly about the scanner, and the residue the narrow (b) rule leaves —
      `obtain x where` on its own line, which is still booked bare — becomes
      *countable* instead of invisible, which is the prerequisite for deciding
      whether to widen the lookahead.  Land it as a new field, not a
      redefinition of `n_bare`, so stored census rows stay comparable.

- [ ] `[citation-reach]` Attribute a citation only to a declaration its
      theory can SEE.  `callers` / `callees` / `unused` / `graph citation`
      resolve a cited token by NAME alone: find `mono` on a line, look up
      every entry called `mono`, report the line as a caller of all of them.
      Over one session that is right — everything in a session sees
      everything it declares.  Over a corpus it is not: the AFP has two dozen
      lemmas spelled `mono`, and a site in `Mono_Bool_Tran` (whose whole
      in-project closure is two theories, neither declaring one) was reported
      as a caller of all of them.  The `mono` there is HOL's `Orderings.mono`,
      arriving through an `imports Main` query deliberately does not follow.
      **This is the generalisation of what `refs`'s `owner_of` already does**
      (see `git log --grep='\[theory-refs\]'`) — closure-scoped ownership,
      applied at the two attribution points so every verb inherits it from
      one place: the citation router's candidate filter and the single-name
      section filter.  The rule is a NECESSARY condition on visibility, not a
      sufficient one, so it can only ever DROP an attribution: a site in T may
      name a declaration in D iff `D = T` or D is in T's transitive in-project
      `imports` closure; a name the project declares nowhere is not filtered
      at all.  `unused` may honestly GROW, which is the point — an entry kept
      alive only by an unreachable same-name citation is dead.  `shape` and
      `methods` are out of scope: neither attributes a token to an entry.
      Ship it behind a compatibility switch, because a corpus-scale delta that
      cannot be turned off cannot be measured against the old numbers.
      D13 in the Scala port's `dev/DIVERGENCES.md`, which measures whole-AFP
      `callers mono` going 1,361 -> 566 — *their* figure on their checkout,
      not reproduced here, so measure ours before quoting it.  Their
      `[reach-position]` is the refinement after this one: within a single
      theory, visibility says nothing, and a citation written above the
      declaration it names is still attributed to it.  What makes that more
      than an inequality is `lemmas` re-exports, `sublocale`-induced bindings
      and `context ... begin` re-entry, all of which bind a name at a line
      other than its declaration's.

- [ ] `[proof-extent-view]` `_proof_extent` looks for its boundaries in RAW
      source, so a commented-out one ends a proof that has not ended.  All four
      of its tests — `text `, a heading, `DECL_RE`, and the column-0 anchor —
      run against `sec.source()`, which is the question `extract_entries`
      already answers with the outer view ("a `lemma` written inside a term or
      a comment is blanked there, so it cannot match").  This is the last
      scanner still asking the raw line.
      **248 of the AFP's 295,775 proofs stop at a boundary inside a redacted
      region**: 231 at a commented-out declaration
      (`ABY3_Protocols/Multiplication_Synthesization:56`), 11 at a heading
      inside a `(* ... *)` block (`Chomsky_Schuetzenberger/Dyck_Language_Syms:64`,
      and `HOL/Analysis/Retracts:1268`), 6 at a commented-out `text`
      (`Alpha_Beta_Pruning/Alpha_Beta_Linear:340`).  `body_end_line` is short by
      however far the block runs, and it feeds `shape`'s `proof_lines` /
      `proof_tokens` and `show --proof`.
      Not the one-line fix it looks like, which is why it is filed rather than
      folded into [marker-decl]'s diff.  The outer view blanks a `text` block's
      cartouche, so `stripped.startswith("text ")` stops matching there; and
      switching `DECL_RE.match(cline)` to `_match_decl_at` would drop the
      column-0 anchor at the same time, widening the boundary to indented
      declarations — a second change riding along, and the one with the bigger
      corpus delta.  Do them as two steps with two diffs.

- [ ] `[count-mode-zero]` `-c` / `--count` prints a sentence, not a count,
      when nothing matches: `find zzz -c` says `No entries matching 'zzz'.`
      where a count mode should say `0`.  The sentence is emitted by
      `render._emit_matches`'s empty guard, which runs *before* the mode
      dispatch, so every verb funnelling through it (`find`, `show`) is
      affected.  Small, but it is the difference between `$(query find X -c)`
      being arithmetic and being a parse error — and the empty case is the
      one a script most wants to branch on.  Check the other count paths at
      the same time (`refs`, `callers`, `callees`, `methods`) rather than
      fixing one: whether they agree is not currently pinned anywhere.

- [ ] `[closed-stdout]` A closed stdout does not reliably exit 141.
      `CONTRIBUTING.md` fixes the status at `128 + SIGPIPE`, so a pipeline and
      a `$?` check read the same as they do for `yes | head`.  The handler
      catches `BrokenPipeError` around the command body, points fd 1 at
      `/dev/null` and exits 141 — which only works when the failing write
      lands INSIDE that body.  Two ways out:
        - the whole answer fits the interpreter's buffers, the first failing
          write is the shutdown flush, the `except` never runs, and Python
          exits **120** with `Exception ignored while flushing sys.stdout`;
        - the whole answer fits the 64K pipe buffer, so no write ever fails,
          and the command exits **0** while `head` is still being scheduled.
      The second is the bad one: a script checking `$?` sees success on a
      truncated answer.  Measured here — `shape census | head -3` on
      `Abstract_Completeness` exits 0, five runs out of five; the split is
      output size, not a race (D8's table puts it at the 64K buffer, 141 on
      every corpus above it).  Fix by writing through a handle whose failure
      surfaces where it happens rather than at interpreter shutdown, and pin
      the status on a corpus under 64K as well as one over — one alone cannot
      tell the two failure modes apart.
      D8 in the Scala port's `dev/DIVERGENCES.md`; reproduced by
      `scripts/probe_scala_port_findings.py`.

- [ ] `[axiom-names]` `axiomatization` names, two ways.  Found while building
      the `[span-ties]` fixture — the synthetic form did not reproduce the
      crash, and chasing why turned up both of these on real sources.

      **(a) A phantom entry named after the keyword.**  When the command
      stands alone on its line, with its names on the lines below —

          axiomatization
            eq :: \<open>['a, 'a] \<Rightarrow> o\<close>  (infixl \<open>=\<close> 50)
          where refl: \<open>a = a\<close> and ...

      — the name scan takes the keyword itself, and `find '^axiomatization$'`
      answers with an `AXIOM` entry called `axiomatization` spanning one line.
      **11 in FOL, 10 in ZF, 0 in AODV.**  They inflate `summary`'s entry
      count and are citable names that nothing can cite.  Same root as the
      Scala port's D6 residual: the `goal` route does not take the
      name-lookahead the `def` and `typedecl` routes already take.  Their note
      is the warning to heed — adding it renames every
      `lemma`-alone-on-its-line declaration, so it needs its own corpus diff,
      which is why this is an item and not a one-liner.

      The same gap now has a second, sharper instance.  Since [marker-decl],
      `First_Order_Terms/Term:37` —

          lemma \<^marker>\<open>contributor \<open>Martin Desharnais\<close>\<close>
            inj_on_Fun_fun[simp]: "\<And>A ts. inj_on (\<lambda>f. Fun f ts) A" and

      — parses to `?` rather than to the garbled `\<^marker>\<open>contributor`
      it used to give.  That is honest and it is still not the name: the marker
      ends the line, so the name sits on the next one and only the lookahead
      can reach it.  A one-record fixture for the change, and the case that
      shows the lookahead belongs on the `goal` route regardless of markers.

      **(b) An UNTYPED name is not indexed at all.**

          axiomatization glob_one and glob_inv          -- FOL/ex/.../Locale_Test1:719
            where glob_lone: \<open>prod(glob_one(prod), x) = x\<close>

      indexes `glob_lone` and `glob_linv` (the axioms) but neither `glob_one`
      nor `glob_inv` (the constants).  With `::` ascriptions on a continuation
      line the same declaration indexes both, so the split is the type
      annotation, not the `and`.  Take the two together: they are the same
      scan, and (a)'s lookahead is most of (b)'s answer.

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
      back to one theory.  Half the resolver side is already done: since
      `[name-roundtrip]` a theory name containing a separator resolves as a
      name, so a qualified `ae/Bla` will not be mistaken for a path that does
      not exist.  What is still open is the emitter choosing the prefix.

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
