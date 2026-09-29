# P14 — the port meets upstream 0.9.2

Upstream released 0.9.0, 0.9.1 and 0.9.2 after P13: 33 commits,
`git log v0.8.1..v0.9.2`. One of the three releases is this fork's own work
coming back — 0.9.1 is PR #11, the Python port of `instances`, `codeqs` and
the bound-name reach rule (D14) — with three fixes upstream made on top that
the Scala originals did not have. The other two are changes the port lacked:
`proposition` as a declaration, `unfolding` as a proof step with backward
fan-in citation, `grep -i`, `unused --locals`, and a proof extent that runs
past a `text` block inside a proof. P14 merged the release, ported all of it,
and closed the phase with the matrix, the entry projections and the Scala
suite green against oracle 0.9.2. Done 2026-09-29, one day; the plan is
`dev/P14-PLAN.md`.

## Method

The main session (Claude Fable 5.1) planned; six Claude Opus 5.5 agents
implemented, one step each, **each in its own git worktree** with its own
scratch Isabelle home, so four of them edited `query_base/src` concurrently
while the baseline matrix ran in the main tree. The upstream commit messages
were the specs, upstream's own new unittest files the fixtures, and every
step reported its gate counts with each red case attributed to a later step.
The merge order was S2, S4, S3, S1 (the shared `LETTER_SYMS` move landed
first; the widest `cli.scala` footprint last); the only merge conflicts were
the sorted test inventory and one doubled definition.

46 commits on `main` since the plan, 40 of them non-merge; `query_base` moved
by 13 files, +1,133 / −84 lines (one new module, `proof_locals.scala`);
`tests/scala` by 23 files, +4,047 / −142.

## What the survey established (S0)

Cold matrix against oracle 0.9.2 with the engine as P13 left it:

| | vs 0.9.2 at 9f2ad6b |
|---|---:|
| cases | 2,149 |
| clean | 1,722 |
| pinned | 181 |
| failing | 246 |
| stale pins | 0 |

All 246 were attributed before any code moved: 118 `shape-*` on four
corpora (`[markup-step-model]`, `[cite-direction]`, `[body-end-text]`) and
128 Category3 cases across every verb, because that corpus writes 16
indented `proposition` commands the oracle indexes and the port did not.

## The gate, step by step

| step | what landed | gate it reported |
|---|---|---|
| S1 `[decl-commands]` `[markup-step-model]` `[cite-direction]` `[ignore-case]` | `proposition` → LEMMA; `unfolding` a plumbing step; `using`/`unfolding` credit the OPEN goal in M5a; `grep -i`, `find -i` no-op | full matrix 246 → 76 failing, all Category3 `[body-end-text]` |
| S2 `[body-end-text]` | `Entries.proof_close_line` (upstream's `_PROOF_STRUCT_RE` verbatim), `proof_extent` with the outer view and a prose mask; `LETTER_SYMS` down to `Entries` | entrydiff clean on 6 corpora, Category3 clean with S1's rule on top; whole AFP: 319 `body_end` moved, 0 shorter, every record equal to the oracle with S1 |
| S3 `[unused-locals]` | `proof_locals.scala`, `Usage.cmd_unused_locals`, `unused --locals [PATH…]`, plain `unused PATH` exits 2 | 10 new cases byte-equal on 7 corpora; whole AFP 7,822 vs oracle 7,883 rows, the 61 inside `proposition` entries (S1) |
| S4 `[midline-interpret]` `[code-heads]` `[code-clauses]` | mid-line `interpret`; first-token head, `[code abstract]` alone reads past `(`, top-level operator → no head; each `[code]` attribute reads its own clause | 17 new cases, all clean but 5 D15 pins; HOL `instances ab_semigroup_add -r -c` 839 on both tools; `equation_heads` identical on 312,208 HOL term lines |
| S5 merge | the four branches on `main`, one duplicated `LETTER_SYMS` removed, the shared test ID moved to one suite | `make test-scala` 1,396/1,396, 0 failures; entrydiff 28/28; matrix 2,338: 2,151 clean, 186 pinned, 1 transient JVM failure (reran clean) |
| S6 hosts | nothing to change: one dispatch path | warm client = cold JVM = oracle on six new verb forms |
| S7 gate | D14 to history, D15 at 183, **D16** and **D17** new; 27 + 3 matrix ids; counts read from the manifest; inventory 1,396 IDs of 0.9.2; parser generator skips the two hand-written ports | `make compat` PASSED |
| S8 docs | README, MIGRATING, SCANNING, CONTRIBUTING, CLAUDE.md, DEMO, PLAN, todo | demo transcripts re-run cold |

## Measured at the close of P14 (2026-09-29)

The forced `make compat` run (warm mode), which stamped
`dev/upstream-compat-baseline.json` for 0.9.2:

| | oracle 0.9.2 |
|---|---:|
| cases | 2,359 |
| clean | 2,173 |
| pinned | 186 (183 D15, 3 D8) |
| failing | **0** |
| stale pins | 0 |
| entry projections | 28 of 28 identical |

`make test-scala`: 1,317 passed, 79 explicit non-pass dispositions, 0
failures, 1,396 of 1,396 upstream IDs (every unittest 0.9.2 discovers
outside the fork's own test files). `python3 dev/check-upstream-compat.py
--check-only` now passes, so `make test` is the cheap gate again.

## Decisions taken

- **The version counter did not move.** `CLI.version` is `0.9.2-scala.0.3`:
  the number in front of `-scala` names the oracle and moved with it; the
  counter after it is port-side history and nothing port-side changed.
- **Two places where the port reproduces the oracle's reading although it is
  not Isabelle's**, recorded as D16 and D17 rather than fixed, because the
  matrix is the contract: backquoted text is outer text to the oracle's
  locals walk (`Cook_Levin/Reducing:2434`), and M5a counts a fact twice when
  a goal's own `from` line and its `using` line cite the same name
  (`from a  have x: … using a`). D17 is a candidate upstream report.
- **D14 is history.** Upstream adopted the bound-name rule in 0.9.1.

## What is left open

- D17 has not been reported upstream.
- `p7cprobe.sh`'s header still describes the D13-era oracle (stale since
  0.8.x; not about this phase).
- The obsolete worktree `../isabelle-query-upstream-pr` (the PR #11 branch,
  merged upstream) is left for the user to remove.
- Nothing is pushed.
