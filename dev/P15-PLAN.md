# P15 — sync the port with upstream 0.9.8

**Status: IN PROGRESS** (opened 2026-10-16). This is the task list for
bringing the Scala engine to parity with upstream isabelle-query **0.9.8**
(from 0.9.2). It is the plan; the per-step evidence goes into commit
messages under the tags below, and the closing measurements go into
`dev/P15-STATUS.md`.

Upstream's six releases are 31 commits (`git log v0.9.2..v0.9.8` on the
`upstream` remote). The release notes are the six bump commits: `9a6e18e`
(0.9.3), `cc4ce2d` (0.9.4), `a895253` (0.9.5), `707511d` (0.9.6), `5e90b7f`
(0.9.7), `3f2b97c` (0.9.8). Every item carries **upstream's own tag**, so
`git log --grep='\[lemmas-entries\]'` finds the change on both sides. The
method is P9's and P14's: the main session plans, one implementing agent per
step works in its own worktree from a blueprint with the upstream commits as
its spec and the harnesses as its gate. Each step's gap report — the
upstream rule, the Scala sites, oracle-vs-port fixtures — was written before
any code moved (`.dev/p15/gap-{parser,callers,show,attrib}.md`, scratch).

## What upstream did, sorted by what it means here

| release | tag | what | port today |
|---|---|---|---|
| 0.9.3 | `[axiom-for]` | an axiomatization's `for` / `if` tail declares no names | indexes `F`, `x` as AXIOM entries |
| 0.9.3 | `[cut-span]` | `Entry.cut_span`, `shares_line`; `parse_theory(root=)` | no API; `root=` is already `Theory.plan` + `parse_one` |
| 0.9.4 | `[subject-batch]` | a batch with unknown names reports all of them, stdout empty, exit 1 | `CLI.each` streams, stops at the first |
| 0.9.4 | `[graph-once]` | one call graph per run, not per subject | rebuilt per subject (5 sites in `usage.scala`) |
| 0.9.4 | `[callers-tsv]` | `callers -f tsv` (+ `fcdc879`'s `<toplevel>` row) | `-f` rejected, exit 2 |
| 0.9.4 | `[stale-heap-dump]` | a failed method-table dump is not re-run | **N/A**: the port never dumps a heap (D11) |
| 0.9.5 | `[toplevel-label]` | `<toplevel>` callers named by the locus label | bare theory name (`usage_graph.scala:588`) |
| 0.9.6 | `[label-depth]` | every theory label round-trips | no output change under D15 (leaf names); port the shared `label_chain` |
| 0.9.7 | `[lemmas-entries]` | `lemmas` / `theorems` groups are `LEMMAS` entries | not entries; `callers -r` stops at an alias |
| 0.9.7 | `[show-near-names]` | `# no entry named 'X'; N whose name contains it:`; notes to stderr under `--names`/`-c` | missing |
| 0.9.7 | `[show-match-count]` | `[k of n]` in each header of a multi-match `show` / `find` | missing |
| 0.9.7 | `[show-premises]` | `show --premises`, new module `premises.py` | flag rejected |
| 0.9.8 | `[callee-attribution]` | rows name declarations the citer can see; `callers NAME` groups a colliding name | first-wins declaration |

Upstream's `[decl-identity]` (todo.md) is open on both sides: `graph
citation` and `codeqs` still pick one declaration per name.

## Ground rules for every step

As P14's (`dev/P14-PLAN.md` § Ground rules), and the implementing agents'
common brief is `.dev/p15/AGENT-COMMON.md`. In short: own worktree
`../isabelle-query-p15-sN` on branch `p15/sN-…` with its own scratch
`USER_HOME`; the oracle is the main tree's `.dev/oracle/bin/query` (0.9.8);
hand-compute first, upstream's Python tests are the fixtures, ported into
`tests/scala/<suite>/` with rows in `tests/scala/coverage/<suite>.json` and
`tests/scala/upstream-tests.json`; byte parity on stdout, exit status and
stderr presence; `make test-scala` is the fast loop, never `make test` /
`make compat` before S7; a step may leave the matrix red only in cases it
names as a later step's, and the count never rises.

## The steps

Wave 1 (S1, S2, S3) runs in parallel from the S0 commit. S4 branches from
`main` after S1 and S2 have landed (it needs `-f` from S2's TSV work and a
graph whose `lemmas` are entries). S5 merges, S6–S8 close.

### S0 — merge upstream v0.9.8, re-point the harnesses `[p15-merge]` — **DONE**

- [x] `git merge v0.9.8`, P14's resolution policy (upstream's `src/`,
      `scripts/`, `pyproject.toml`, upstream test files, `.claude/memory/`;
      OURS for README/CONTRIBUTING/SCANNING/CLAUDE.md/Makefile; `todo.md`
      auto-merged and reviewed). `98f790d`.
- [x] `ORACLE_VERSION=0.9.8`, `CLI.version = "0.9.8-scala.0.3"`,
      plugin.props, `dev/p13-loading.scala`; oracle venv re-installed;
      `make test-scala` 1,396/1,396. `afd7715`.
- [ ] Baseline matrix recorded here (`.dev/p15/difftest-baseline.log`).

### S1 — the parser `[axiom-for]` `[lemmas-entries]` `[cut-span]`

Worktree `p15/s1-parser`. Spec: `07294de`, `f692bbd`, `589d58b`, `0efd9c5`;
gap report `.dev/p15/gap-parser.md`.

- [ ] `[axiom-for]` — `entries.scala` `axiom_line` loop and its separator
      regex: `for` / `if` close name collection, threaded through both
      callers. Fixture `tests/test_axiom_names.py`.
- [ ] `[lemmas-entries]` — `lemmas` / `theorems` join the declaration regex
      and tag map as `LEMMAS`, a "facts" branch splits `a = x and b = y`
      into one entry per named group (port `_thmdefs`), each citing only
      its own right-hand side; an anonymous `lemmas [simp] = foo` stays a
      `<toplevel>` citation; a `text` preamble goes to the alias it
      introduces. `LEMMAS` joins `Usage_Graph.citable_tags`; `sites.scala`'s
      codeqs declaration pass skips it. Fixture `tests/test_lemmas_entries.py`.
- [ ] `[cut-span]` — port `Entry.cut_span` / `shares_line` (`model.scala`,
      a `mark_shared_lines` pass in `entries.scala`); `parse_theory(root=)`'s
      tests ported through `Theory.plan` + `parse_one`, the bad-root case
      through `CLI.Session.load_index` as P14 did.
- [ ] Re-stamp the parser generator's `source-audit.json` for 0.9.8,
      regenerate (upstream changed `test_instantiation_spans`' fixture to
      `declare bar [simp]`), port the new IDs.
- [ ] Gate: `dev/entrydiff.sh` on all seven corpora (the oracle gains
      LEMMAS entries on AODV 90, FOL 28, ZF 346, Sequents 10, CTT 19);
      `difftest -k` on `show*`, `find*`, `summary*`, `unused*`, `callers*`.

### S2 — the subject loop `[subject-batch]` `[graph-once]` `[callers-tsv]` `[stale-heap-dump]`

Worktree `p15/s2-batch`. Spec: `3b3d993`, `1f222d7`, `a506f9a`, `fcdc879`,
`78c4626`; gap report `.dev/p15/gap-callers.md`.

- [ ] `[subject-batch]` — `CLI.each` buffers stdout per subject and keeps
      going on an exit-1 subject; at the end, any unknown → stdout empty,
      every unknown on stderr, exit 1; any other non-zero exit propagates at
      once with stdout empty. Every list verb. Server/client unchanged
      (verify with `dev/p7probe.sh`).
- [ ] `[graph-once]` — the call graph built once per request: a cache on
      `CLI.Session` (a value per request, NOT a global —
      `[p10-namespace-value]`) passed to the five `usage.scala` sites.
- [ ] `[callers-tsv]` — `callers -f tsv` and `-c -f tsv` exactly as
      upstream, incl. `fcdc879`'s `<toplevel>` row (`THEORY:<toplevel>`,
      `TOPLEVEL`, theory, session, locus). The `-r` rows' declaration choice
      is first-wins here behind one helper; S4 replaces it with
      `[callee-attribution]`'s rule (name the helper in the commit).
- [ ] `[stale-heap-dump]` — no code; its 3 test IDs get python-only
      dispositions citing D11.
- [ ] Fixtures: `tests/test_subject_loop.py`, `test_namespace_cache.py`,
      the TSV halves of `test_toplevel_callers.py`.
- [ ] Gate: `difftest -k` on `callers*`, `callees*`, `deps*`, `refs*`,
      `uses*`, `instances*`; `dev/p7probe.sh`; `callers -r` on 8 names
      timed before/after on AODV.

### S3 — `show` `[show-near-names]` `[show-match-count]` `[show-premises]`

Worktree `p15/s3-show`. Spec: `d5bbffe`, `32f9b79`, `e2b0324`; gap report
`.dev/p15/gap-show.md` (byte formats, sites, an outline of `premises.py`).

- [ ] `[show-near-names]` — the fallback note; it and the bound-name note go
      to stderr under `--names` / `-c` (`cmd_show` gains `err`).
- [ ] `[show-match-count]` — `[k of n] ` in `render_entry`'s header for
      `show` and `find` when n > 1.
- [ ] `[show-premises]` — new module `premises.scala` after `entries` in
      `build.props` (and the CLAUDE.md DAG line); `--premises` in the
      `show` flag group. Every premise form upstream's tests and
      `scripts/probe_premise_forms.py` name. Match the oracle's
      `shows "Q x" for x` = 3 conclusions and report it for a divergence
      entry; do not "fix" it.
- [ ] Fixtures: `tests/test_show_near_names.py`, `test_show_match_count.py`,
      `test_show_premises.py` → cli suite.
- [ ] Gate: `difftest -k` on `show*`, `find*`; `show --premises -a` per
      theory byte-equal to the oracle on the seven corpora.

### S4 — labels and attribution `[toplevel-label]` `[label-depth]` `[callee-attribution]`

Worktree `p15/s4-attrib`, branched from `main` after S1 and S2 land. Spec:
`a7cb09f`, `d2df572`, `67f3bb6`, `e1cea87`, `cc5b130`, `f76d8dc`; gap
reports `.dev/p15/gap-callers.md` §toplevel/label, `.dev/p15/gap-attrib.md`.

- [ ] `[toplevel-label]` — `<toplevel>` nodes named by `Render.locus_labels`
      (`render` already sits below `usage_graph`; fix the comment at
      `usage_graph.scala:643` that says otherwise); the two Scala test
      reference builders follow.
- [ ] `[label-depth]` — port `label_chain` so the label printer and the name
      resolver share one definition; no output change expected (D15).
- [ ] `[callee-attribution]` — import depths kept with the closure; an
      all-declarations index (by identity: `Entry` is a case class); the
      `callees` (plain, `-r`, `--names`, `--external`, `--reach name`) and
      `callers -r` (block, `--names`, `-f tsv` — replacing S2's helper)
      rules; the grouped `callers NAME` header. `dev/p9probe.sh`'s three
      expectations (§13a ×2, §17a) take the hand-checked grouped text.
- [ ] Fixtures: `tests/test_callee_attribution.py` → graph suite;
      `test_toplevel_callers.py`'s graph half; `test_disambig_names.py`.
- [ ] Gate: `difftest -k` on `callers*`, `callees*`, `graph*`, `unused*`;
      `dev/p9probe.sh`, `dev/p7cprobe.sh`.

### S5 — land the branches on `main`

- [ ] Merge S1, S3, S2 (wave 1), then S4. Resolve `cli.scala`,
      `commands.scala`, `usage.scala` and the coverage JSONs by hand;
      `make test-scala`.
- [ ] Full `dev/difftest.sh` and `dev/entrydiff.sh`; every remaining red case
      named to S7 or pinned to a `dev/DIVERGENCES.md` entry with evidence.

### S6 — the plugin and the server

- [ ] The jEdit plugin renders nothing that changed (`query_search.scala`
      uses `format_name_line` only); confirm with `dev/p5probe.sh`,
      `p6probe.sh`, `p6bprobe.sh`. The batch contract and `-f tsv` over the
      socket: `dev/p7probe.sh`. `COLD_ONLY_COMMANDS` untouched.

### S7 — the matrix, the pins, the divergence list, the compat baseline

- [ ] `dev/difftest.sh`: `c` lines for `callers -f tsv` (`-r`, `-c`), a
      batch with an unknown name, a batch with a bad regex, `show
      --premises`, a near-name `show`, `show --names` on a near name; each
      non-vacuous on at least one corpus. `tests/scala/coverage/matrix.json`.
- [ ] `dev/DIVERGENCES.md`: new entries for anything S5 left red; the
      `shows … for x` count if upstream does not take it.
- [ ] `tests/scala/upstream-tests.json`: `upstream_version` 0.9.8, new
      `python_tree_id`; each `coverage/<suite>.json` exhaustive.
- [ ] `make compat` stamps `dev/upstream-compat-baseline.json` for 0.9.8.
      Only after S5 is green.

### S8 — docs, version, status

- [ ] Fold `.dev/p15/upstream-docs.diff` into OUR README, SCANNING,
      CONTRIBUTING; MIGRATING (what 0.9.3–0.9.8 brought, `-V`
      `0.9.8-scala.0.3`); CLAUDE.md release status, DAG line, verb notes;
      `demo/DEMO.md` version line; `PLAN.md` phase list.
- [ ] `dev/P15-STATUS.md`; `todo.md`; memory `p15-upstream-sync`.
