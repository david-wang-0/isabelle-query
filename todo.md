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

## Open work from the Scala rewrite

These are recorded in `dev/P7-STATUS.md` and `dev/P7C-STATUS.md` with their
evidence; the entries here are the handles.

- [ ] `[namespace-by-value]` Thread the method/attribute table through as a
      **value** instead of binding `isabelle.query.Namespace`'s process-global
      state.  It decides whether `auto` is a proof method or a fact, and every
      resident host has had to work around it separately: the jEdit plugin
      serialises all engine calls through one worker thread, and the warm
      server restores the committed default before every request under one
      lock.  Both are correct and both cost the same thing — **no two projects
      can be queried at once in one JVM**.  The fix changes the signature of
      every analysis in `usage_graph`, `usage` and `shape`, so it needs the
      full differential matrix plus both probes, not a spot check.  Removing
      the lock is the only thing that lifts the server's throughput ceiling.

- [ ] `[regex-dialect]` D12: `\w` is `java.util.regex`'s here and Python's in
      the oracle, so `²`/`½` are word characters to the oracle and not to us,
      and a combining mark is one to us and not to it.  One record differs
      across the whole AFP (`Feuerbach/special`, two derived count fields).
      The fix is a translation layer in `Py.compile` — `\w` → `[\p{L}\p{N}_]`,
      `\W` its complement, `\s` to Python's whitespace set (which also
      differs), and `\b` as explicit lookarounds because Java derives it from
      its own `\w`.  It sits under the deepest lexical primitive in the engine,
      so it changes what a NAME is, what `grep` matches and what the call graph
      sees: it needs the P1 entry-set gate re-run over both corpora, not just
      the difftest.  Evidence in `dev/DIVERGENCES.md` §D12.

- [ ] `[reach-position]` D13 filters citation attribution by import
      VISIBILITY, which is a property of the theory pair.  Within one theory
      it says nothing: a citation written above the declaration it names is
      still attributed to it, and `dev/p7cprobe.sh` §2 pins that as the
      shipped behaviour rather than an accident.  The refinement is a linear
      check — a same-theory site may name a declaration only at or above it —
      and the entry index already carries the line numbers it needs.  What
      makes it more than an inequality is what legitimately breaks it:
      `lemmas` re-exports, a locale's `sublocale`-induced bindings and a
      `context ... begin` re-entry all bind a name at a line other than its
      declaration's, so a naive check would prune real citations.  Entry
      condition is a fixture per case, and the whole-corpus delta measured
      before and after — the same shape of evidence D13 itself carries.

- [ ] `[theory-name-leaf]` A ROOT may address a theory in a subdirectory by
      PATH — `theories "Nested/Nested_Fix"`, there being no per-theory `in`
      clause in the grammar — and both this engine and the reference then call
      the theory `Nested/Nested_Fix`.  Isabelle does not: `Thy_Header`'s
      `import_name` takes the last path segment, and `Sessions`' own
      `global_theories` check spells it `Path.explode(thy).file_name`.  So the
      name in a `summary` row, in a locus and in `theory`'s "Known theories"
      list is one Isabelle would not recognise — and `theory
      "Nested/Nested_Fix"` then fails to find a theory it has just listed, on
      BOTH implementations.  The reachability filter's half of this is closed
      (`Reach.build`'s alias table, `dev/p7cprobe.sh` §8b); the NAME is not.
      What stops it being a one-word change in `Discovery.session_theories` is
      parity: the reference spells them the same way, and three difftest
      corpora contain one — `Locale_Test/Locale_Test` (FOL),
      `LK/Propositional` and three more (Sequents), `ex/Typechecking` and
      three more (CTT).  Entry condition is therefore a D-series entry, its
      own pins, and `dev/entrydiff.sh` re-run over the five P1 corpora.

- [ ] `[index-footprint]` The resident index is **~190 bytes per source line**,
      about four times the source it indexes (`src/HOL`: 34 MB of `.thy` →
      154 MB of heap; the AFP: 281 MB → 1,156 MB, in a 4.5 GB process).
      Bytes-per-line is flat to within 12% across corpora spanning 34x, so
      lines — not entries, not theories — is what it scales with.

      Attributed by heap histogram (`jcmd GC.class_histogram`, diffed against
      the empty server, `src/HOL` loaded; the 155 MB it accounts for matches
      the 154 MB measured independently, so nothing significant is missing):

      | MB | B/line | instances | class |
      |---:|---:|---:|---|
      | 53.1 | 66.4 | 666,842 | `[B` (String backing arrays) |
      | 27.9 | 34.9 | 914,904 | `java.lang.String` |
      | 17.3 | 21.6 | 452,272 | `scala.Tuple2$mcII$sp` (region spans) |
      | 17.2 | 21.5 | 562,342 | `::` (cons cells holding those spans) |
      | 12.8 | 16.0 | 2,902 | `Array[List[…]]` — `nonisar` + `inner`, one pair per section |
      | 7.8 | 9.7 | 78,279 | `isabelle.query.Entry` |
      | 6.5 | 8.1 | 1,452 | `Array[String]` — the `lines` arrays |
      | 6.4 | 8.0 | 1,451 | `Array[Set[Int]]` — `notes`, one per section |

      Three targets fall out, and the ordering is not what it looked like
      before the histogram:

      **(a) The per-line region structures are 53.7 MB — 35%, the largest
      single item.** `Regions.Result` carries FOUR arrays indexed by line
      (`nonisar`, `inner`, `open_at`, `notes`), and the two span arrays are
      `Array[List[(Int, Int)]]`: a cons cell (32 B) plus a tuple (32 B) per
      span, over an 8-byte array slot per line that exists whether or not the
      line has any spans. A CSR-style encoding — `starts: Array[Int]`,
      `ends: Array[Int]`, and one `offset: Array[Int]` of length `nlines+1` —
      is about 7 MB for the same 452k spans. Contained: `regions.scala`
      produces it and `Model.blank_all` is nearly the only consumer.
      (The tuples are already specialised `$mcII$sp`, so there is no `Integer`
      boxing to remove — the cost is headers and cons cells, not boxing.)

      **(b) `Entry.text` is a second copy of text `lines` already holds** —
      built by `mkString("\n")` in `entries.scala`. The histogram shows
      914,904 Strings against 838,047 lines, and the excess is one per entry
      (78,279); `[B` totals 53 MB against 34 MB of actual source. Worth ~20 MB
      (13%). `Entry` already carries `src_start`/`thy_end`, so this is a span
      plus a back-reference to the section.

      **(c) One `String` per theory instead of `Array[String]`**, with an
      `Array[Int]` of line offsets: removes the per-String object and array
      header, ~30 MB (19%). But `model` is the bottom of the module DAG, so
      every scanner above changes — the same blast radius as
      `[namespace-by-value]`, and the reason to do (a) and (b) first.

      Together (a)+(b)+(c) would take ~190 B/line to roughly 80. Note the JVM
      already runs `-XX:+UseStringDeduplication`, which is why `[B` instances
      (667k) trail String instances (915k) — some of the duplication above is
      already being collapsed at runtime, and the remaining wins are structural
      rather than a matter of interning harder.

      Separately, and orthogonal to all three: the server currently **refuses**
      over `$ISABELLE_QUERY_SERVER_LIMIT` (4000 theories) rather than bounding
      itself. An LRU over sections with reparse-on-miss would turn that into a
      memory budget; `shape census` already holds one session live at a time
      for exactly this reason, so the pattern exists in-tree. The cost is
      thrashing on whole-corpus queries, which wants measuring before it is
      built.

- [ ] `[settings-shell]` The `bin/isabelle` settings shell is ~180 ms and, since
      `[p8-coldpath]` cached the other two, it is now the largest single item
      on the cold path after the parse. It is sourced once by `bin/isabelle` to
      dispatch the tool and again by `isabelle java` to start the JVM, so a
      cold `isabelle query` pays it twice for one invocation. The thin client
      already caches its own slice of it (`$ISABELLE_QUERY_CLIENT_CACHE` holds
      `$ISABELLE_HOME_USER`, keyed on everything that could change it), which
      is evidence the caching is *possible* and no evidence at all that it is
      safe in general — a settings environment is a hundred variables, some
      derived from the others, and a component that cached the wrong one would
      be wrong in a way no probe here would notice. Entry condition is deciding
      whether this is the component's business at all: the second sourcing is
      `isabelle java`'s, i.e. the distribution's, and the honest answer may be
      that only `Query_Main`-without-`isabelle-java` avoids it — which trades
      the settings shell for hard-coding a classpath, and that is worse.
      Measurements in `dev/P8-STATUS.md`.

- [ ] `[client-console-name]` Half closed by `[p7d-shim]`: the warm client is
      now what a plain `isabelle query` runs (`query_base/lib/Tools/query`),
      so it has a console name — the component's own.  What REMAINS open is
      the bare `query` name on a user's PATH, a drop-in for the Python tool,
      which is what `PLAN.md` §P7 anticipated.  Still deliberately not taken:
      it decides what `query` means on a user's PATH, and that is an
      installer's call, not the component's.

## Done

Nothing — by design. Completed work is recorded in its commit messages, which
carry the reasoning, the rejected alternatives and the before/after, and cannot
drift from the code the way a summary of them can. Recover one by its tag:

    git log --grep='\[locus-roundtrip\]'

See `CONTRIBUTING.md`.
