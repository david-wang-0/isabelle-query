# P14 — sync the port with upstream 0.9.2

**Status: IN PROGRESS** (started 2026-09-29). This is the task list for
bringing the Scala engine to parity with upstream isabelle-query **0.9.2**
(from 0.8.1), and for retiring every divergence upstream has since closed.
It is the plan; the per-step evidence goes into commit messages under the
tags below, and the closing measurements go into `dev/P14-STATUS.md`.

Upstream's three releases are 33 commits (`git log v0.8.1..v0.9.2` on the
`upstream` remote). The release notes are the three bump commits, `5457e02`
(0.9.0), `efd7295` (0.9.1) and `b8f1f95` (0.9.2). Every item below carries
**upstream's own tag**, so `git log --grep='\[body-end-text\]'` finds the
change on both sides. The method is P9's (`dev/P9-PLAN.md`): the main
session plans, one implementing agent per step works from a blueprint with
the upstream commit as its spec and the harnesses as its gate.

## What upstream did, sorted by what it means here

**0.9.1 is this fork's own PR #11 landing** — `instances`, `codeqs` and the
bound-name reach rule (D14), ported to Python on
`upstream/sites-and-bound-reach` (`dev/P10-STATUS.md`, memory
`upstream-pr-branch`). So the rewrite-only verbs now HAVE an oracle, the
`p6bprobe.sh` fixtures are no longer the only evidence for them, and D14 is
no longer a divergence. Upstream then fixed three things in the Python port
that the Scala originals still have:

| tag | what | Scala site |
|---|---|---|
| `[midline-interpret]` | `then interpret …`, `from x interpret …` are sites; the command need not start the line | `sites.scala` `INST_CMD_RE` is `^`-anchored |
| `[code-heads]` | an equation's head is its FIRST token, whole (`HOL.equal`); the after-`(` rule applies to `[code abstract]` only; a top-level operator anywhere in the lhs means no head | `sites.scala` `head_identifiers` takes the first token after EVERY `(` |
| `[code-clauses]` | in `lemma a [code]: … and b [code]: …` each attribute reads only its own statement | `sites.scala`, the attribute-to-statement pairing |

**New behaviour the port lacks**, in upstream's order:

| release | tag | what |
|---|---|---|
| 0.9.0 | `[cite-direction]` | `using` / `unfolding` cite BACKWARD in M5a fan-in (`annotate_fanin`) |
| 0.9.0 | `[markup-step-model]` | `unfolding` is a plumbing step keyword |
| 0.9.0 | `[decl-commands]` | `proposition` is a declaration, tag LEMMA |
| 0.9.1 | `[ignore-case]` | `grep -i/--ignore-case`; `find -i` accepted as a no-op |
| 0.9.2 | `[unused-locals]` | `unused --locals [PATH…]`: proof-local names bound and never read; plain `unused` now REJECTS a PATH (exit 2) |
| 0.9.2 | `[body-end-text]` | a `text` block inside a proof no longer ends `body_end_line`; text-block bodies masked from the boundary tests; `LETTER_SYMS` moves down from `shape` to `parsing` |

**Already equivalent, nothing to port**: `[bound-name-reach]` (our D14,
Option A — single-name scans only, bulk graph untouched); the shared
visibility per transitive `instances -r` request (`Reach.site_filter` is
already memoised per name in `sites.scala`); the `_fail_subject` exit
contract of the site verbs.

## What the survey established (2026-09-29)

The 2,149-case matrix run against oracle 0.9.2, with the Scala side pinned
exactly as `dev/difftest.sh` pins it at `9f2ad6b`: **see `dev/P14-STATUS.md`
once the baseline run completes** (`.dev/p14/difftest-baseline.log`). The
matrix has no cases for `instances`, `codeqs`, `unused --locals` or `grep -i`
yet — S7 adds them — so the baseline measures S1–S3 only.

## Ground rules for every step

- Each step is implemented in its OWN git worktree, branched from the S0
  commit, so four agents can edit `query_base/src` at once without a
  difftest rebuilding a half-edited tree. Each worktree registers its own
  component into its own scratch home (`USER_HOME="$WT/.dev"`) and never
  touches the real `$ISABELLE_HOME_USER`, jEdit, or heaps. The oracle is the
  main tree's `.dev/oracle/bin/query` (0.9.2, editable on the frozen tree,
  which no step edits).
- **Hand-compute first.** Every ported rule gets a fixture whose expected
  output is derived from the rule and cross-checked against oracle 0.9.2.
  The Python tests upstream shipped with each change are the fixtures;
  their Scala ports go into `tests/scala/<suite>/` with rows in
  `tests/scala/coverage/<suite>.json` and `tests/scala/upstream-tests.json`
  (the inventory grows from 1,255 upstream IDs to 1,396).
- Oracle parity is judged by `dev/difftest.sh` against **0.9.2** (the
  `-k` form on a step's own cases while developing; the full matrix when the
  step lands on `main`) and `dev/entrydiff.sh`. A step may leave the gate red
  only in cases it names as belonging to a LATER step; the count must not go
  up, and the commit message records the count and the attribution.
- `make test-scala` is the fast loop. `make test` is NOT: the version moved,
  so the compat gate would run the whole four-hour comparison; that is S7's
  last act, via `make compat`.
- Small single-concern commits; body via a `.commit-msg` file in the working
  tree, removed after; trailer names the model that did the work. No absolute
  personal paths in anything committed — grep the diff.
- Where a rule was ALREADY right here and upstream matched us, do not touch
  the code; update the divergence entry.

## The steps

### S0 — merge upstream v0.9.2, re-point the harnesses `[p14-merge]` — **DONE**

- [x] `git merge v0.9.2` (merge base is exactly 0.8.1). Upstream's tree for
      `src/`, `scripts/`, `pyproject.toml`, the upstream files of `tests/`,
      `.claude/memory/`; OURS wholesale for `README.md`, `CONTRIBUTING.md`,
      `SCANNING.md`, `CLAUDE.md`, `Makefile` (folded in by S8); `todo.md`
      auto-merged and reviewed. Verified `git diff v0.9.2 -- src/isabelle_query
      pyproject.toml scripts` empty. `bf880b7`.
- [x] `ORACLE_VERSION=0.9.2`, `CLI.version = "0.9.2-scala.0.3"`, plugin.props,
      `dev/p13-loading.scala`. The oracle venv re-installed (`pip install -e .`
      refreshes the editable metadata; the old dist-info said 0.8.1). `9f2ad6b`.
- [ ] Baseline matrix recorded in `dev/P14-STATUS.md`.

### S1 — the small parser and shape rules `[decl-commands]` `[markup-step-model]` `[cite-direction]` `[ignore-case]`

Worktree `p14/s1-small`. Upstream commits `305ca40`, `910008c`, `b725141`.

- [ ] `[decl-commands]` — `proposition` joins `Entries.DECL_RE` and
      `TAG_MAP` (`entries.scala:43,49`), tag LEMMA, riding with `corollary`.
      Fixture: `tests/test_proposition.py` (7 tests; the central one is the
      symmetry — two identical one-line proofs bracketing a proposition must
      scan to the same steps). Check `namespace.scala:110` (it already lists
      `proposition` as a keyword) and `Discovery`'s custom-keyword union for
      any second table.
- [ ] `[markup-step-model]` — `"unfolding"` joins `Usage_Graph.PLUMBING_KEYWORDS`
      (`usage_graph.scala:198`); the `classify_step_line` comment at
      `shape.scala:110-113` is rewritten as upstream's is (the fallback now
      carries only lines led by a fact name or an unknown word).
- [ ] `[cite-direction]` — `Shape.annotate_fanin` (`shape.scala:535`) gains
      `BACKWARD_CITE_CMDS = {using, unfolding}`: such a plumbing step credits
      the OPEN goal with its fresh facts (`fresh = facts -- seen`), a goal
      step sets `open_goal = s, seen = facts | pending`, a closing step
      clears both. Fixture: `tests/test_shape.py::CitationDirection` (each
      goal cites a different fact — with `[1, 1]` a direction error is
      invisible) plus the four other new `test_shape.py` tests.
- [ ] `[ignore-case]` — `grep -i/--ignore-case` sets `Flags.ignore_case`
      and `Commands.compile_user_pattern(…, ignore_case)` (the parameter
      exists, `commands.scala:478`); `find -i` is a no-op flag like `-n`.
      Fixture: `tests/test_ignore_case.py` (5 tests).
- [ ] Scala test ports: parser (`test_proposition`), shape (the 5 new
      `test_shape` IDs), cli (`test_ignore_case`); inventory rows.
- [ ] Gate: `difftest -k 'shape-*'` and `-k 'grep-*'` on one corpus; the
      full matrix on landing (expect the `shape-*` census/lemma families and
      any `summary`/`defs` case on a corpus with a `proposition` to clear).

### S2 — `[body-end-text]`, the proof extent

Worktree `p14/s2-extent`. Upstream commits `857b35f`, `a70ee34`.

- [ ] `Entries.proof_extent` (`entries.scala:1176`) takes the outer view
      with terms AND comments blanked (`blank_all(outer, nonisar_spans)` —
      find the Scala spelling in `theory.scala`/`regions.scala`) and a
      `prose` line mask of every text block's BODY (`(lo+1, hi)` for
      `hi > lo`). A `text` / heading boundary ends the proof only when
      `line_no > proof_close_line(outer, proof_line, thy_end)`, computed
      lazily at the first prose boundary met; `DECL_RE` is not tested on
      masked lines; a block's first line stays live.
- [ ] `proof_close_line`: `_PROOF_STRUCT_RE` verbatim through `Py`
      (`(?<![\w'.?])(proof|qed|subgoal|by|done|sorry|oops)(?![\w']|\.[\w\\])`
      `|(?<!\.)(\.\.|\.)(?![\w'.\\])|([{}()\[\]])`), the `kinds` stack with
      `nest` for `(`/`[`, terminators closing the entry's own goal or a
      `subgoal`. The five lexical slips upstream fixed are in that regex:
      `foo..`, `bar.`, `INV.\<Phi>o_def`, `qed.`, `?proof`.
- [ ] Also `LETTER_SYMS` moves from `Shape` (private, `shape.scala:1015`) to
      `Entries` (public) — S4 and S3 both read it; do the move HERE so the
      two other worktrees rebase onto it (or, if they land first, take
      theirs).
- [ ] Fixture: the proof-extent halves of `tests/test_unused_locals.py`
      (`body_end_line` reaches the `qed` past a mid-proof `text`; the
      "lemma reduction1 …" prose line is not a boundary) plus a hand-written
      one for each lexical slip. Cross-check every `body_end_line` on the
      seven gate corpora against the oracle (`dev/entrydiff.sh` compares
      `body_end`).
- [ ] Gate: `dev/entrydiff.sh` on all seven corpora; `difftest -k
      'enclosing-*'`, `-k 'shape-*'`, `-k 'largest*'`, `-k 'show*'`.

### S3 — `[unused-locals]`

Worktree `p14/s3-locals`. Upstream commit `fceb3ef`. The largest step: a new
module `proof_locals.scala` (474 Python lines) between `usage_graph` and
`usage` in the DAG, the `Usage.cmd_unused_locals` renderer, and the CLI's
`unused` gaining `--locals` and PATH positionals.

- [ ] `Proof_Locals`: `_TOKEN_RE` / `_TERM_TOKEN_RE` built from
      `Entries.LETTER_SYMS` (S2's move — replicate the one-line move if S2
      has not landed; the merge takes one copy); `body_tokens` over the two
      views with the noise spans skipped; the `Walk` state (scopes keyed
      `("f", name)` / `("t", name)`, `kinds`, `goals` by depth, `pending`,
      `this`, `this_stale`); `_walk` token loop exactly as upstream's,
      including `_CHAIN_READERS`, `_THIS_RESETS`, `_TRANSFORMING_ATTRS`,
      `_unqualified` (`local.`), the `define … where … for` rule, the
      `[unfolded I_defs]` attribute-argument reads. Public: `scan_proof`,
      `proof_end`, `unread_locals(sections, windows, keep)`.
- [ ] CLI: `unused` gets `--locals` and `files*` (PATH); plain `unused` with
      a PATH exits 2 with `isabelle query: unused: PATH arguments need
      --locals; entry-level \`unused\` is corpus-wide, scope it with -R`;
      `--locals` with `-r`/`--roots` exits 2 (`unused: --locals takes neither
      -r nor --roots`); `_locals_selection`: `THY:A..B`/`THY:LINE` windows
      via the existing `windows=true` section loader, `THY:ENTRY`
      (`^(.+):([A-Za-z][\w'.]*)$`, only when not a locus and not an existing
      path) selecting `(src_start, body_end_line or thy_end)`, unknown
      entry → `fail_subject("no entry 'X' in TOKEN")` exit 1; a bare file
      anywhere takes all of it.
- [ ] Renderer `cmd_unused_locals`: rows `LOCUS  NAME  KIND  ENTRY` with
      column widths as upstream (`{locus:<w0}  {name:<w1}  {kind:<w2}  {entry}`),
      `-c`, `--by-theory` (`N unread local names across K theories:` then
      `  %4d  thy`), empty → `No unread local names found.`; `--keep` names.
- [ ] Fixture: `tests/test_unused_locals.py` (16 tests, the nine hand-read
      rows first). Port to the cli suite; the walks-agree test needs S2's
      `proof_close_line` — if S2 has not landed, port it as a test that
      compares `proof_end` to the oracle's value on the fixture and leave a
      note for the merge.
- [ ] Gate: `unused --locals` on all seven corpora byte-equal to the oracle
      (`-c`, default, `--by-theory`, a window, an entry, an unknown entry,
      `-r` refusal, plain `unused PATH` refusal) — these become S7's matrix
      cases, so write them as `c` lines for `dev/difftest.sh` now.

### S4 — the site verbs, upstream's three fixes `[midline-interpret]` `[code-heads]` `[code-clauses]`

Worktree `p14/s4-sites`. Upstream commits `34eaaca`, `9a93f83`, `115a9a8`.

- [ ] `[midline-interpret]` — `interpret` is also searched mid-line on the
      outer view, at a word boundary with the lookbehind that keeps
      `M.interpret` / `interpret_foo` out; the header of a mid-line command is
      read with the line's prefix BLANKED, not cut. Theory-level commands stay
      line-start only.
- [ ] `[code-heads]` — `head_identifiers`: the first token of the lhs, a
      qualified name read whole and matched with `denotes`; the after-`(`
      rule only for `[code abstract]`; any top-level operator symbol in the
      lhs (`xs @ ys`, `A - set xs`, `set xs \<union> A`) → no head.
      `LETTER_SYMS` (now `Entries.LETTER_SYMS`, S2) decides that `\<alpha>`
      is an argument, not an operator.
- [ ] `[code-clauses]` — each `[code…]` attribute reads only its own
      statement; clause boundaries `and NAME [attrs]:` on the outer view,
      only past the last `shows`; a later statement's row carries its own
      name and line.
- [ ] Fixtures: `tests/test_sites_instances.py` (53) and
      `tests/test_sites_codeqs.py` (44) — port to the cli suite; also
      `tests/test_bound_name_reach.py` (10) to the graph suite, since it is
      the same PR. Re-run `dev/p6bprobe.sh` (its hand-computed fixtures must
      still hold; where a fixture encoded the old head rule, upstream's
      commit message is the evidence for changing it).
- [ ] Gate: the S7 matrix cases for `instances`/`codeqs` (write them now as
      `c` lines: default, `-c`, `--names`, `--sorts`, `-r`, `-r -c`, an
      unknown subject, a bound-name subject, a non-locale subject) byte-equal
      to the oracle on the seven corpora.

### S5 — land the four branches on `main`

- [ ] Merge in the order S2, S1, S4, S3 (S2 carries the `LETTER_SYMS` move
      the other two need; S3 has the widest `cli.scala` footprint and goes
      last). Resolve `cli.scala` / `entries.scala` overlaps by hand; rebuild;
      `make test-scala`.
- [ ] Full `dev/difftest.sh` and `dev/entrydiff.sh`; every remaining red case
      named to S7 or pinned to a `dev/DIVERGENCES.md` entry with evidence.

### S6 — the plugin and the server see the new verbs

- [ ] `jedit_query`: nothing in the plugin drives `unused`, but its
      `Query_Index` and the server's `Server.Commands` route by verb name —
      confirm `unused --locals` and `grep -i` pass through `CLI.run_result`
      unchanged (they should: one dispatch path). `dev/p7probe.sh` over the
      socket for `unused --locals -c` and `grep -i`.
- [ ] `query_client.py`'s `COLD_ONLY_COMMANDS` untouched (nothing new needs
      the cold path).

### S7 — the matrix, the pins, the divergence list, the compat baseline

- [ ] `dev/difftest.sh`: new `c` lines for `instances` (9 forms), `codeqs`
      (7), `unused --locals` (8), `grep -i` (2), `find -i` (1), `unused PATH`
      (exit-2 contract). Corpus-specific subject names via the existing
      `$NAME1`-style variables; every new case must be non-vacuous on at
      least one corpus (the count of 2,149 moves; record the new total).
- [ ] `tests/scala/coverage/matrix.json` rows for the new invocation IDs.
- [ ] `dev/DIVERGENCES.md`: D14 moves to the resolved-upstream preamble
      (0.9.1 adopted it); the `instances`/`codeqs` "no oracle" wording
      everywhere (CLAUDE.md, README, CONTRIBUTING, `p6bprobe.sh` header)
      goes; a new entry for anything S5 left red, with evidence.
- [ ] `dev/difftest-pins`: no new pins without an entry; stale pins deleted.
- [ ] `tests/scala/upstream-tests.json`: `upstream_version` 0.9.2 and the
      new `python_tree_id` (`dev/check-upstream-compat.py`'s `source_tree`);
      1,396 IDs; each `coverage/<suite>.json` exhaustive.
- [ ] `make compat` (the forced full comparison) stamps
      `dev/upstream-compat-baseline.json` for 0.9.2. Only after S5 is green.

### S8 — docs, version, status

- [ ] Fold upstream's user-facing material from `.dev/p14/upstream-docs.diff`
      (`git diff v0.8.1 v0.9.2 -- README.md SCANNING.md CONTRIBUTING.md`)
      into OUR docs: README (`unused --locals` and the site-verb sections,
      `-i`, the site verbs' exit contract, the 22-verb line already true);
      SCANNING (bound names as declarations, "Extending a target is not
      instantiating it", the bare theory label of a site — check which of
      these our SCANNING already carries from the P10 work); CONTRIBUTING
      (`unused` is both families, split by a flag; the two site verbs' exit
      discipline); MIGRATING (what 0.9.x brought, `-V` now
      `0.9.2-scala.0.3`); CLAUDE.md release status and the sentence saying the
      site verbs have no oracle; `demo/DEMO.md` version line; `PLAN.md`.
- [ ] `dev/P14-STATUS.md`: the closing measurements (matrix, entrydiff,
      scala-tests, probes), what moved in `shape` numbers on the gate corpora
      (upstream's own figures: `[cite-direction]` +15.9% fanin over 40 AFP
      entries, `[decl-commands]` +0.44% steps, `[body-end-text]` +1.65%
      steps), and what is left open.
- [ ] `todo.md`: nothing new opens unless a step leaves something.
- [ ] Memory: `p14-upstream-sync` (method, decisions, what is open); the
      `upstream-pr-branch` memory becomes DONE (PR #11 merged as 0.9.1).
