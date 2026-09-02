# Todo list

**Open work only.** Ordered by priority (highest first).  Tags are stable
handles for cross-referencing in commits/PRs — and, once an item ships, for
finding it again with `git log --grep`.

Conventions for changing the tool (the CLI contract, verification habits) live
in `CONTRIBUTING.md`.

- [ ] `[markup-step-model]` Resolve ONE discrepancy, then stop.  On
      `DitherTM`, `PIDE/markup` decodes to 87 `command_span`s each carrying
      the keyword, Isabelle's own **kind** (`thy_goal_stmt` / `qed` /
      `prf_script` / `prf_decl`) and an exact extent — and `query` sees **38
      steps where Isabelle marks 41 proof commands**.  Every `shape` metric
      rests on the step model, and `shape` numbers are research output, so
      three unexplained steps are worth an afternoon.
      `scripts/probe_pide_markup.py` already decodes the markup; the work is
      "explain the three".
      **Deliberately NOT a fixture corpus.**  This replaces the former
      `[markup-oracle]`, which specced a committed, digest-gated,
      release-pinned harvest of `(source, answer)` pairs.  The precedent says
      that is the wrong half: `[export-oracle]` used Isabelle ground truth as
      a **one-time discovery instrument**, shipped eight commits under
      `[declared-names]` (713 unindexed names, 40,741 cited occurrences), and
      was then RETIRED — `git log --grep='\[declared-names\]'`.  Nothing
      standing was kept and nothing needs maintaining.  As `probe(#8)` put it:
      once the oracle says WHAT to look for, the measurement is ordinary
      source scanning at full corpus scale, on a machine with no Isabelle.
      So if this finds a defect: fix it, pin it with a **hand-written**
      fixture (per `CLAUDE.md` — hand-compute the value, then make the code
      match), cite the markup finding in the commit message, and let the probe
      go.  No heap dependency enters `pytest`.

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
      statement above it (`decl_end_line` 67 where the declaration ends at 62).
      D5 in the Scala port's `dev/DIVERGENCES.md`.
      **MEASURED, and the measurement says do not do it.**
      `scripts/probe_comment_split_scale.py`, counting the scanner's own
      failure (a marker still live in `live_source()` whose cartouche opens
      below) rather than a text pattern that guesses at the same shape:

          AFP           9,910 theories   1 site
          HOL/FOL/ZF    1,604 theories   0 sites

      **One occurrence in 11,514 theories** — Substitutions_Lambda_Free:63..67,
      costing 4 prose lines wrongly live, 9 tokens in them that name a real
      declaration, and one entry's `decl_end_line`.  The fix is a change to
      the tokenizer state machine, the highest-risk code in the package, to
      carry a pending marker across a line boundary.  That trade is not worth
      taking for one record: leave it unless a cheap route appears that does
      not touch `_scan_nonisar_spans`' state.  Kept on the list as a *measured*
      decision rather than deleted, so it is not re-litigated from the shape.

- [ ] `[decl-body-blank]` The residual 5 of `[decl-body-comment]`: a BLANK
      line before the note breaks the body scan before the note is reached.

          definition                                   HOL/UNITY/WFair.thy:35
                                        <- blank; the scan ends here
            \<comment> \<open>This definition specifies conditional fairness. ...\<close>
            transient :: "'a set => 'a program set" where

      so `transient` is still `src 35..43, body 35..35`.
      **Cost: 5 records** — 4 HOL (`WFair:35`, `Inc:14`, `DBuffer:11`,
      `Complex_Types:111`), 1 ZF (`GenPrefix:25`), 0 AFP.  Down from 50;
      the other 45 shipped as `[decl-body-comment]`.
      **The obvious fix was implemented, measured and REJECTED** — do not
      re-derive it.  "A blank cannot end what has not started" (skip the
      blank-line break while `body` is still empty) repairs all five and reads
      principled.  It also takes corpus-wide containment violations
      (`body_end > thy_end`, a body overlapping the NEXT declaration) from
      **82 to 719**, and the whole-AFP diff from 706 records to 1,799.  Any
      future attempt must report that containment number, not just the diff
      count: `scripts/probe_span_diff.py` prints both.
      Pinned as `expectedFailure` in
      `tests/test_decl_body_comment.py::TheBlankLineVariantIsStillOpen`, so a
      real fix reports an unexpected success rather than going unnoticed.
      Low priority at 5 records: filed so the rejection is not re-litigated.

- [ ] `[pide-mcp-tools]` Offer the engine to coding agents through Kevin
      Kappelmann's PIDE MCP server (`isabelle pide_mcp`, the
      `isabelle-pide-mcp` component).  Its README documents the hook: any
      registered Scala component that requires `env:ISABELLE_PIDE_MCP_JAR`
      and registers a `PIDE_MCP_Tools` service has its tools offered by the
      server -- the same chaining `jedit_query` uses, so nothing on the MCP
      side changes and no fork is needed.  The server's own `find_entities`
      reads PIDE markup and needs loaded theories; this engine answers the
      corpus-wide structural questions (callers, definition, instances,
      code equations, outline, grep) cold, before anything is loaded --
      complementary, not overlapping.  (I/Q, the other Isabelle MCP server,
      has no cross-theory, usage or grep affordance at all.)
      Design, decided 2026-09-02, to follow `[p10-namespace-value]`:
      - An OPT-IN `pide_mcp_query/` component in this repo, registered
        explicitly (`isabelle components -u <repo>/pide_mcp_query`), NOT
        chained from the root `etc/components`: an unset `env:` requirement
        compiles against nothing rather than failing (`Setup/src/Build.java`
        `requirement_paths`), so an unconditional chain would break
        `scala_build` for every user without the MCP component.
      - ONE generic `query` tool taking the CLI argument list, returning
        stdout/stderr text and the exit status, through `CLI.run_result` --
        the single dispatch path the server uses; the CLI help is the
        schema.  Default root from the MCP session's directories, overridable
        per call.  Typed tools (usages, definition, sites) only once usage
        shows which are worth a schema.
      - A warm index in-process, the `Query_Server` cache pattern without the
        socket; a per-request namespace VALUE is what makes that a resident
        host without a rebinding dance, hence the ordering.
      - Verified without a heap: the tools need a project root, not a PIDE
        session, so a probe calls `handle` on a fixture directly.

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

Nothing above this section is a Scala-side statement: the items in the first
section describe the frozen Python reference. Four of them are the same defect
on BOTH sides, and P9 is what made that true of the last two — `[keyword-scope]`
(D4, shared since upstream's `[keyword-kind-quoted]` put `alphabet` in its
keyword table too), `[comment-newline]` (D5, the one record where the two still
differ, and the port is the one that is right), `[decl-body-blank]` (the port
took `[decl-body-comment]` and, like upstream, rejected the blank-line variant
on the containment measurement) and `[axiom-untyped]` (the port took
`[axiom-names]`; this half is a grammar, not a regex tweak, on either side).

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
      (`Reach.leaf_index`, `dev/p7cprobe.sh` §8b); the NAME is not.
      What stops it being a one-word change in `Discovery.session_theories` is
      parity: the reference spells them the same way, and three difftest
      corpora contain one — `Locale_Test/Locale_Test` (FOL),
      `LK/Propositional` and three more (Sequents), `ex/Typechecking` and
      three more (CTT).  Entry condition is therefore a D-series entry, its
      own pins, and `dev/entrydiff.sh` re-run over the seven standard corpora
      — which are byte-identical today, so the change would have to earn every
      record it moves.

- [ ] `[index-footprint]` **Two of the three targets shipped and the third was
      measured and rejected**, so what is left under this tag is the server's
      admission rule, not the index's layout. The resident index is now **~110
      bytes per source line**, about twice the source it indexes (`src/HOL`:
      34 MB of `.thy` → 84 MB of heap; the AFP: 281 MB → 664 MB), down from
      ~190 B/line. Recover the histogram, the three candidates and the
      before/after with `git log --grep='\[index-footprint\]'`; in short, flat
      CSR spans instead of `Array[List[(Int,Int)]]` took 36 MB off `src/HOL`
      and one `String` per theory instead of one per line took another 32,
      while rebuilding `Entry.text` from the section was worth 2 MB (2.3%)
      against a permanent `Entry → Theory_Section` back-reference and an
      invariant that broke parity on its first outing — reverted, with the
      number kept in the reverted commit's message so the question is not
      re-opened without it.

      **RSS barely moved: 4,532 → 4,441 MB.** The process footprint is set by
      the transient peak DURING the parse, not by what survives it, and ZGC
      does not uncommit. So halving the retained index buys headroom — more
      indexes resident per server, room to raise the 4,000-theory cap — and
      not a smaller process. Any further work here has to say which of the two
      it is claiming.

      What is still open: the server **refuses** over
      `$ISABELLE_QUERY_SERVER_LIMIT` (4000 theories) rather than bounding
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
