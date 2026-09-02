# P9 — sync the port with upstream 0.8.1

**Status: in progress.** This is the task list for bringing the Scala engine
to parity with upstream isabelle-query **0.8.1** (from 0.7.0), and for
retiring every divergence upstream has since fixed. It is the plan; the
per-step evidence goes into commit messages under the tags below, and the
closing measurements go into `dev/P9-STATUS.md`.

Upstream's two releases are 43 commits (`git log v0.7.0..v0.8.1` on the
`upstream` remote). The release notes are the two bump commits, `de51b28`
(0.8.0) and `f1b3eb4` (0.8.1). Every item below carries **upstream's own
tag**, so `git log --grep='\[unresolved-subject\]'` finds the change on both
sides.

## What the survey established (2026-09-01)

The 2,086-case matrix run against oracle 0.8.1, with the Scala side pinned
exactly as `dev/difftest.sh` pins it today:

| | vs 0.7.0 | vs 0.8.1 |
|---|---:|---:|
| clean | 1,946 | 1,695 |
| pinned | 140 | 44 |
| failing | 0 | 251 |
| stale pins | 0 | 96 |

Four read-only gap analyses (one per cluster) then established, by running
both tools on fixtures and corpora, exactly which upstream changes the port
already has and which it lacks. Their reports are the working specs for the
steps below (gitignored, under `.dev/gap-G[1-4]-*.md`; each names the Scala
sites, the byte-exact output, and hand-computed fixtures).

**Already equivalent, nothing to port** (upstream fixed what the port had
found): `[cartouche-escape]` (D1), `[keyword-kind-quoted]` (D3),
`[span-ties]` (D7), `[cascade-level]` (D10), the closure half of
`[import-leaf]`, the declaration-site half of `[marker-decl]` (D2).

**To port**: everything else — 17 items in four clusters.

## Ground rules for every step

- Work on the main tree, one step at a time, in the order below. Never edit
  `query_base/src` while a difftest is running.
- Dev loop is the scratch home: `USER_HOME="$FORK/.dev"`. Never register into
  the real `$ISABELLE_HOME_USER`, never touch `~/.isabelle`, never launch
  jEdit or build heaps. Another session is using the real installation.
- **Hand-compute first.** Every ported rule gets a fixture whose expected
  output is derived from the rule (and cross-checked against oracle 0.8.1),
  in a new probe `dev/p9probe.sh` — one `§` per item, refuses (exit 2) without
  its corpora, ends with a failability check. The gap reports supply the
  fixtures.
- Oracle parity is judged by `dev/difftest.sh` against **0.8.1** and
  `dev/entrydiff.sh` against the merged tree. A step may leave the gate red
  only in cases it names as belonging to a LATER step; the count must not go
  up, and the step's commit message records the count and the attribution.
- Small single-concern commits; body via a `.commit-msg` file in the working
  tree, removed after; trailer names the model that did the work. No absolute
  personal paths in anything committed — grep the diff.
- Where a rule was ALREADY right here and upstream matched us, do not touch
  the code; update the divergence entry.

## The steps

### S0 — merge upstream v0.8.1, re-point the harnesses `[p9-merge]` — **DONE**

- [x] `git merge v0.8.1` (merge base is exactly 0.7.0). Take upstream's tree
      for `src/`, `tests/`, `scripts/`, `.claude/memory/`, `pyproject.toml`,
      `METRICS.md`; resolve `README.md`, `CONTRIBUTING.md`, `SCANNING.md`,
      `CLAUDE.md` by keeping OUR structure and folding upstream's new
      user-facing content in where it describes behaviour the port will have
      after P9 (mark it as such where it is not yet true); reconcile
      `todo.md` by hand — upstream replaced `[markup-oracle]` with
      `[markup-step-model]`, filed `[keyword-scope]` (our D4),
      `[decl-body-blank]` and `[axiom-untyped]`, and shipped
      `[bare-provenance]`, `[count-mode-zero]`, `[disambig-names]` (the three
      shipped ones stay OUT of the list; a note at the head of "Open work from
      the Scala rewrite" says they are being ported under S4/S1).
- [x] The frozen reference is now 0.8.1: say so in `CLAUDE.md` and
      `CONTRIBUTING.md` ("Two trees, one contract"). `CLI.version` stays
      `0.8.0-scala` until S5.
- [x] `dev/difftest.sh`: the oracle comes from `$QUERY_ORACLE` (default: the
      bare `query` on PATH) and the run **refuses (exit 2)** unless
      `$QUERY_ORACLE --version` reports the pinned version (`0.8.1`), so a
      stale oracle is a refusal, not a plausible red. Document how to make
      one from the merged tree (`python -m venv .dev/oracle && pip install -e .`
      into it) — no path written down in the script. CONTRIBUTING's harness
      table names the pin.
- [x] `dev/entrydiff.sh` / `dev/dump_oracle.py` already import the tree's
      `src/` — verify they run against 0.8.1 and note the seven-corpus
      baseline (expected: FOL 11, ZF 116, Sequents 11, CTT 1,
      Abstract_Completeness 1 records differ; AODV, Category3 0).
      **Measured, exactly as expected** (`$QUERY_ORACLE` now also chooses the
      interpreter `dump_oracle.py` borrows `isabelle_layout` from). All of it
      is `[axiom-names]` (`?` vs `axiomatization`) and `[decl-body-comment]`
      (`decl_end`/`body_end`), both S2.
- [x] `dev/difftest-pins`: delete every pin the survey found stale — the
      whole D7 family (FOL/ZF, 132 lines) and D10 (`unused-recursive *`).
      Keep D8 and the demo pins. The gate is now red by ~290 cases, all
      attributed to S1–S4; record the exact count. **2,086 cases: 1,791 clean,
      3 pinned, 292 failing, 0 stale** (oracle 0.8.1, seven corpora).
- [x] Commit the merge, then the harness changes.

### S1 — the CLI contract (`.dev/gap-G1-cli-contract.md`) `[unresolved-subject]` `[count-mode-zero]` `[name-roundtrip]` — **DONE**

- [x] `[unresolved-subject]` — an unresolvable SUBJECT writes
      `isabelle query: …` to stderr, leaves stdout untouched, exits **1**:
      `theory`, `defs`, `outline`, `deps`, `uses`, `refs`, `callees`,
      `callees -r`, `callers -r`, `methods NAME`. One helper (`fail_subject`,
      the `Exit_Code(1)` pattern `sites.scala` already uses), checked before
      mode dispatch; batch forms print the separator then abort. Message
      texts byte-exact per the report. `callers` (non-recursive), `find`,
      `show`, `enclosing`, `shape lemma/steps`, `--theory` scope are
      **unchanged**.
- [x] `[count-mode-zero]` — `-c` prints `0` and `--names` prints nothing on
      an empty result: reorder `Render.emit_matches` (`find`, `find --and`,
      `show`) and `Usage.render_unused`. `unused --roots -c`, `defs`,
      `callees` stay as they are.
- [x] `[name-roundtrip]` — `Commands.resolve_theory` accepts the printed
      name of a path-spelled theory (`ex/Typechecking`, `LK/Propositional`):
      exact `s.theory == name` between the real-path match and the stem
      fallback. (S4 adds the unique-suffix step after it.)
- [x] `dev/p9probe.sh` §1–§3 with the report's fixtures; new difftest cases
      `find-count-zero`, `find-names-zero`, `show-count-zero`.
- [x] Docs: README exit-status section, CONTRIBUTING "two empties" table,
      MIGRATING.
- [x] Gate: difftest (expect the `*-unknown` family and CTT `deps/refs-last`,
      `deps/refs/uses-batch` to clear), p7probe (exit + stderr over the
      socket, `QUERY_DIFFTEST_WARM=1` on one corpus).

### S2 — the parser (`.dev/gap-G3-parser.md`) — **DONE**

In this order; entrydiff after each commit.

- [x] `[axiom-names]` — the `axiomatization` anchor is named `?`.
- [x] Regions prerequisite — all FOUR formal comments (`\<comment>`,
      `\<^cancel>`, `\<^latex>`, `\<^marker>`) redact in the **live** view,
      as upstream's `_REDACTING_MARKERS = FORMAL_COMMENTS`; rewrite the
      rationale at the top of `regions.scala`.
- [x] `[marker-decl]` 4a — name grammar stops at a structural token
      (`lipschitzI_on\<^marker>…` → `lipschitzI_on`); split the lexical atom
      from the name atom exactly as upstream did, and leave the citation /
      `shape` tokenisers on the lexical one.
- [x] `[marker-decl]` 4b — marked `locale`/`class` names and the `target`
      chain under them. **No code change was needed**: a target's name is read
      from `live`, so the regions prerequisite above fixes it. The second
      route the report offered — skip formal comments inside `target_name` —
      is deliberately NOT taken; the comment there says why.
- [x] `[marker-decl]` 4c — one heading recogniser (`heading_at` +
      `skip_formal_comments`, shared with `strip_decl_prefix`); marked
      headings enter `outline` and bound spans; delete `SECTION_RE`.
      `proof_extent` switched to `heading_at` here (092981d), the mask
      below (1ee841f).
- [x] `[proof-extent-view]` — `proof_extent` tests boundaries on the
      whole-line noise mask (`nonisar_ranges`), both callers.
- [x] `[comment-before-name]` — `lookahead_name` walks past redacted lines
      with the 3-line budget spent only by blanks/`text`, 40-line cap, using
      the live view. Needed one thing under it: an UNTERMINATED formal
      comment is not one FORMAL_COMMENT token to Isabelle's lexer, so
      `Regions.scan` now pairs marker and recovered cartouche itself.
- [x] `[decl-body-comment]` — `scan_decl_body` skips (does not append, does
      not stop at) a line that is blank in the live view; the `record`
      exception stays; the blank-before-note variant is NOT taken (upstream
      measured and rejected it: containment violations 82 → 719).
      Re-measured here on the whole AFP: 411,181 records before and after,
      0 gained, 0 lost, 752 moved; `body_end > thy_end` 81 → 81 and bodies
      reaching into the next declaration 226 → 226.
- [x] `dev/p9probe.sh` §4–§11 from the report's f4–f8 fixtures — 73 checks,
      0 failing, failability shown.
- [x] Gate: `dev/entrydiff.sh` **byte-identical in all four variants** on
      the seven corpora (28/28), on the whole of `src/HOL` (1,451 theories,
      78,279 entries — which is where HOL/Analysis, UNITY, Bali and Hoare
      live), and on CoSMeDis, Tabulation_Hashing, Interval_Analysis,
      ResiduatedTransitionSystem and Optics. `dev/difftest.sh` against
      oracle 0.8.1: **2,107 cases — 1,949 clean, 3 pinned, 155 failing, 0
      stale**, down from 210 failing at S1 with **no case newly red**. The
      155 are S3 (101: the `callees`/`deps`/`graph`/`refs`/`unused`/`uses`
      families) and S4 (45 `bare_kinds` in `shape-census*` /
      `shape-summary-json*`, plus 9 `grep-{alternation,anchored,cartouche}`
      that differ only in the locus column's width — `[disambig-loci]`).
      `Sequents/closed-stdout` (D8) cleared on its own with the entry names.
      p5/p6/p6b/p7c/p7 probes all green.

### S3 — the citation graph (`.dev/gap-G2-graph.md`) — **DONE**

- [x] `[symbol-body-tokens]` — blank `\<…>` symbols before the `[\w']+`
      citation scan in `build_call_graph`, and the two lookbehinds in
      `isa_word_pattern` (`callers`, and the plugin's word-under-caret).
- [x] `[name-is-not-identity]` — line index, prose mask and declaration
      sites keyed by `sec.path`, not `sec.theory`; readers follow
      (`build_call_graph`, `scan_methods`, `grep_sections`, `find_callers`,
      `find_code_equations`). `find_callers` returns the section (S4 needs
      it; `jedit_query/src/query_search.scala` groups by path).
- [x] `[import-leaf]` — `Usage.resolve_import` takes upstream's four-step
      candidate order (exact → dot-tail → leaf → sorted leaf candidates), so
      `deps`/`uses`/`refs`/`graph imports` stop printing a path-spelled
      import as `[out-of-project]`. `Reach.build` takes the union of the
      candidates and its P7c alias table becomes `Reach.leaf_index`, so the
      two resolvers are one rule again.
- [x] `[citation-reach]` — `declared_at` over entries of **any** tag (the
      `TYPE`/`LOCALE` gap that hid `COMP → comp` on Category3). **Decision
      recorded:** the port keeps counting *bound names* in the `callers`
      visibility filter (upstream consults entries only); it drops only
      citations the citing theory cannot see, which is the D13 rule applied
      to a constructor. New entry **D14** in `dev/DIVERGENCES.md` with the
      report's fixture as evidence, and no pin — no gate corpus exercises it.
      `instances`/`codeqs` unchanged. Also took 1c: an unreadable header is
      "unknown", never "imports nothing", so a section parsed from a plugin
      BUFFER is not filtered at all.
- [x] `--reach {closure,name}` as a real flag on `callers`, `callees`,
      `refs`, `unused`, `graph` (help text and position per the report),
      threaded as a value through `build_call_graph` / `find_callers` /
      `Reach.site_filter`; **deleted** the `ISABELLE_QUERY_REACHABILITY`
      channel everywhere (cli, `request_env`, client, docs, demo) — one
      default, one channel, and the flag rides through the server and the
      client verbatim, so `Reach.enabled` and the server's per-request
      rebinding of it are gone. `dev/p7cprobe.sh` rewritten around
      `--reach name`: **45 checks, 0 failing** (was 37).
- [x] `dev/difftest.sh`: dropped the asymmetric reachability pin and its
      comment (both engines default to closure); added
      `callers-reach-name`, `callees-reach-name`, `refs-reach-name`,
      `unused-reach-name`, `graph-reach-name`, `callers-bad-reach` — six per
      corpus, so 2,107 → **2,149** cases.
- [x] `dev/p9probe.sh` §12–§15 from the report's fixtures (Sym/Guard,
      alpha/beta `Preliminaries`, LeafFixture/UnionFixture, the declared-set
      fixture with the D14 case): 73 → **110 checks, 0 failing**,
      failability shown.
- [x] Gate: `dev/difftest.sh` against oracle 0.8.1 with the pin dropped:
      **2,149 cases — 2,092 clean, 3 pinned, 54 failing, 0 stale**, down from
      155 failing at S2 with **no case newly red** (case ids compared with
      `comm`; the 101 that cleared are exactly the
      `callees`/`deps`/`graph`/`refs`/`unused`/`uses` families, and the six
      new cases are clean on all seven corpora). The 54 left are S4 alone:
      45 `bare_kinds` in `shape-census*` / `shape-summary-json*`, and 9
      `grep-{alternation,anchored,cartouche}` that differ only in the locus
      column's width. p7cprobe 45/0, p7probe 87/0, p9probe 110/0, p5/p6/p6b
      green after rebuilding the plugin and the shim jar.
      Whole AFP, cold, committed namespace: `callers mono -c` 632 → **634**
      (1,363 under `--reach name`), `unused -c` 93,058 → **101,154**. The
      step deltas match upstream's (`unused` +8,332 for
      `[name-is-not-identity]` there, +8,096 net here including the edges
      `[citation-reach]` 1a puts back); the absolute gap to upstream's 97,747
      and 561 is the port's ~1,900 extra AFP records (D1/D2/D5) and the ~100
      extra name-level `mono` lines D13 already recorded, both predating P9.
      S5 re-takes the corpus-scale figures.

### S4 — loci and shape (`.dev/gap-G4-render-shape.md`)

- [ ] `[disambig-names]` — `Render.theory_labels` / `locus_labels` /
      `file_locus` (shortest unique path suffix over the **loaded corpus**,
      BFS by depth, exhaustion rule); `cmd_largest` prints the label; the
      resolver's unique-suffix step after S1's exact-name step.
- [ ] `[disambig-loci]` — the other eight emitters: `enclosing` (locus,
      scope, past-end), `callers` (+ context lines, owner from the hit's OWN
      section), `methods`, `grep`, `sorry` (label + suffix), `shape steps`,
      `shape lemma`, `shape widest` (sort key unchanged). `--json` and
      `census` unchanged. jEdit: `Group.caption` / peek use the label; the
      plugin's `sections_by_theory` last-wins lookup goes by path.
- [ ] `[bare-provenance]` — `Step.bare` classified `construction` /
      `undelimited` / `unfound` by upstream's rule; `bare_kinds` object after
      `method_kinds` in `summary_record` (census, `summary --json`), keys in
      that order, all present, sum = `n_bare`. METRICS.md paragraph (already
      merged in S0). Delete the todo item.
- [ ] `dev/p9probe.sh` §16–§18 (the alpha/beta/solo collision root; the
      31-line BARE theory).
- [ ] Gate: difftest (`shape-census*`, `shape-summary-json*`, CTT
      `enclosing-*` clear), p5/p6/p6b, p7probe.

### S5 — close out `[p9-status]`

- [ ] difftest on all seven corpora: **0 failing, 0 stale**; the only pins
      left are D8 (three) and the demo's D8/D2. Then the warm run
      (`QUERY_DIFFTEST_WARM=1`) once.
- [ ] entrydiff over the **whole AFP** and the **whole distribution**
      against 0.8.1; re-take the corpus-scale numbers in
      `dev/DIVERGENCES.md`'s preamble.
- [ ] `dev/DIVERGENCES.md`: D1, D3, D7, D10 → resolved upstream (keep the
      evidence, mark the release); D2, D6 → closed on both sides; D5, D4,
      D9, D11, D12 stay; D8 rewritten (the port is 0-under-~8 KB / 141
      above, upstream now documents 0-under-64 KB as the contract — a
      documented difference in threshold, not a defect on either side); D13
      rewritten for the flag; D14 new.
- [ ] Docs: README (`--reach`, exit status, loci qualification, `bare_kinds`),
      SCANNING (the merged upstream sections now true of the port),
      MIGRATING (breaking exit contract, the env var's removal), CONTRIBUTING
      (the reachability paragraph: flag, not env), CLAUDE.md (release status,
      the frozen tree is 0.8.1), `todo.md` (remove what shipped; keep
      `[theory-name-leaf]` — upstream did not change the naming either).
- [ ] `CLI.version` → `0.8.1-scala`: the suffix says which oracle the port
      matches, and this is the release where that became 0.8.1. Note in
      CLAUDE.md that the version tracks the matched upstream release.
- [ ] `dev/P9-STATUS.md`: the before/after table, the whole-corpus numbers,
      what the next phase inherits. Push.

## Not in scope

- `isabelle_query.api` (`[span-api]`) — a Python import surface; the Scala
  engine's equivalent is the component's classpath and is not versioned
  separately.
- Upstream's `scripts/probe_*.py` — they run against the Python tree and
  arrive with the merge; nothing to port.
- `[theory-name-leaf]`, `[namespace-by-value]`, `[regex-dialect]`,
  `[reach-position]`, `[index-footprint]`, `[settings-shell]` — open work
  that predates this sync and is unaffected by it.
