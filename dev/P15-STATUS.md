# P15 — the port meets upstream 0.9.8

Upstream released 0.9.3 to 0.9.8 after P14: six releases, 31 commits,
`git log v0.9.2..v0.9.8`. Two of them are issue-sized changes to the parser
(`lemmas` / `theorems` as entries, an axiomatization's `for` tail), two are
about running several subjects at once (the all-or-nothing batch, one call
graph per request, `callers -f tsv`), one is three changes to `show` (`[k of
n]`, the near-name note, `--premises`), and the rest are about naming: the
`<toplevel>` caller by its theory's label, labels that always round-trip, and
rows that name a declaration the citing theory can see. P15 merged the
release, ported all of it, and closed the phase with the matrix, the entry
projections and the Scala suite green against oracle 0.9.8. Done 2026-10-16,
one day; the plan is `dev/P15-PLAN.md`.

## Method

P14's. The main session (Claude Opus 5.5) planned and wrote one gap report
per step before any code moved — the upstream rule, the Scala sites,
oracle-vs-port fixtures (`.dev/p15/gap-*.md`, scratch); Claude Opus 5.5
agents implemented, one step each, **each in its own git worktree** with its
own scratch Isabelle home. Wave 1 (S1 parser, S2 subject loop, S3 `show`) ran
in parallel from the S0 commit; S4 (labels and attribution) branched from
`main` once S1 and S2 had landed, since it needs `-f tsv` and a graph whose
`lemmas` are entries. The upstream commit messages were the specs, upstream's
new unittest files the fixtures, and every step reported its gate counts with
each red case attributed to a later step. The merge order was S3, S2, S1,
then S4; the only conflict was `cli.scala`'s `show` dispatch, where S2 had
moved every `each` site to the per-subject `Out` and S3 had given `cmd_show`
an `err` parameter, and both were kept. The coverage manifests auto-merged.

34 commits on `main` from the P14 close to the S7 merge (`29a79f4`), 27 of
them non-merge, not counting upstream's 31; `query_base` moved by 16 files,
+1,264 / −179 lines (one new module, `premises.scala`); `tests/scala` by 32
files, +2,304 / −131.

## What the survey established (S0)

Cold matrix against oracle 0.9.8 with the engine as P14 left it (`afd7715`,
only the oracle pin and the version string moved):

| | vs 0.9.8 at afd7715 |
|---|---:|
| cases | 2,359 |
| clean | 1,801 |
| pinned | 186 |
| failing | 372 |
| stale pins | 0 |

By verb `unused` 53, `graph` 37, `summary` 34, `find` 31, `theory` 25, `show`
24, `callees` 24, `refs` 22, the rest ≤ 15 each; by corpus AODV 103, FOL 68,
ZF 65, Sequents 51, CTT 45, Category3 32, Abstract_Completeness 8. The gap
reports attributed the bulk to `[lemmas-entries]` (entry counts, extents, and
aliases missing from the graph) and `[show-match-count]`, and the grouped
`callers` header and the declaration choice to S4.

## The gate, step by step

| step | what landed | gate it reported |
|---|---|---|
| S1 `[axiom-for]` `[lemmas-entries]` `[cut-span]` | `for` / `if` close an axiomatization's name collection; `lemmas` / `theorems` → one `LEMMAS` entry per named group, each citing its own right-hand side, `LEMMAS` citable; `Entry.cut_span` / `shares_line` | entrydiff clean on all seven corpora with the oracle's new `LEMMAS` entries (AODV 90, FOL 28, ZF 346, Sequents 10, CTT 19); an API probe of tag, name, text, target and span equal on 13,140 entries; 15 FOL cases newly D15 (`Locale_Test`'s two uncited `lemmas`) |
| S2 `[subject-batch]` `[graph-once]` `[callers-tsv]` `[stale-heap-dump]` | `CLI.each` buffers per subject, all or nothing; a per-request graph memo on `CLI.Session`, not a global; `callers -f tsv` with the `<toplevel>` row; the heap-dump fix N/A (D11) | `callers -r -c` with 8 names on AODV costs what 1 does (8:1 ≈ 1.0, was ≈ 1.5) |
| S3 `[show-near-names]` `[show-match-count]` `[show-premises]` | the near-name note, on stderr under `--names` / `-c`; `[k of n]` in `show` / `find` headers; `premises.scala`, `show --premises` | premise rows equal to the oracle in all 10,607 `show --premises -a` blocks over the seven corpora |
| S4 `[toplevel-label]` `[label-depth]` `[callee-attribution]` | `<toplevel>` by the locus label; one `label_chain` for printer and resolver; rows name a visible declaration, nearest first; the grouped `callers NAME` | its 33 cases 0 failing; p9probe 152/152 (§1c re-expressed for the batch contract), p7cprobe 45/45 |
| S5 merge | the four branches on `main` | 372 → 34 (wave 1) → **1** failing of 2,359 (2,157 clean, 201 pinned): `CTT/closed-stdout`, D8; entrydiff 28/28; `make test-scala` 1,482/1,482 |
| S6 hosts | the plugin renders nothing that changed; p7probe re-scoped to the owned-fallback lifecycle `[p7probe-scoping]`, one real client defect fixed (a warm answer into a closed pipe exited 120, now 141); §18 asks the P15 contracts served vs cold | p5 69, p6 104, p6b 247 + 63, p7 17 failing → 109/109, p7c 45, p9 152, all green |
| S7 gate | 15 matrix ids for the P15 contracts (337 → 352 per corpus); CTT's closed-stdout pinned to D8 (the oracle's answer moved, not the port); D15 at 200; the inventory is 0.9.8's, 1,482 IDs | full matrix 2,464: 2,260 clean, 204 pinned, 0 failing, 0 stale |
| S8 docs | README, SCANNING, CONTRIBUTING, MIGRATING, CLAUDE.md, PLAN, DEMO, CHEATSHEET, todo | the demo transcripts re-run cold: 62 → 63 entries and 25 → 26 unused, both from `Demo_Code`'s `lemmas thrice_code`, which the oracle counts too |

## Measured at the close of P15 (2026-10-16)

The full cold `dev/difftest.sh` on the merged tree (S7), seven gate corpora:

| | oracle 0.9.8 |
|---|---:|
| cases | 2,464 |
| clean | 2,260 |
| pinned | 204 (200 D15, 4 D8) |
| failing | **0** |
| stale pins | 0 |
| entry projections (S5) | 28 of 28 identical |

`make test-scala`: 1,399 passed, 83 explicit non-pass dispositions, 0
failures, 1,482 of 1,482 upstream IDs (every unittest 0.9.8 discovers outside
the fork's own test files). The probes: `p5probe` 69, `p6probe` 104,
`p6bprobe` 247 + 63, `p7probe` 109, `p7cprobe` 45, `p9probe` 152 — all green.

COMPAT: pending

## What moved

Upstream's own figures, from the release notes, for what the port now prints
too. The gate corpora are a subset of the AFP and the distribution, so these
are the population the change was measured on, not a re-measurement here.

- `[lemmas-entries]`: **10,608 new AFP entries**, none lost; **189** existing
  entries end earlier, most because a `text` preamble now goes to the alias
  it introduces. Entry counts, `summary` and `unused` move with them, and a
  closure now passes through an alias (NDTHT's `callers -r
  table_tape_facts`: 29 → 40).
- `[axiom-for]`: named AFP AXIOM entries **1,081 → 1,071**, all ten `for`
  variables in Registers.
- `[callee-attribution]`: **66%** of AFP edges into a name declared in more
  than one theory had been shown at a declaration the citer could not see.
- `[graph-once]`: upstream's ten names in 1.96 s against 1.94 s for one; here
  `callers -r -c` with 8 names on AODV against 1 is ≈ 1.0 (it was ≈ 1.5).
- `[toplevel-label]`: 370 AFP theories' top-level citations had been folded
  into a same-named theory's node; `[label-depth]` repaired nine AFP labels,
  which under D15's leaf names prints nothing different here.

## Decisions taken

- **The version counter did not move.** `CLI.version` is `0.9.8-scala.0.3`:
  the number in front of `-scala` names the oracle and moved with it. P15 did
  carry one port-side fix the oracle does not share — the thin client's exit
  status on a closed pipe (`f10e4c5`) — and whether that takes the patch slot
  is left as a release decision.
- **`[stale-heap-dump]` has nothing to port**: the port never resolves the
  method table from heaps (D11). Its three test IDs carry python-only
  dispositions.
- **`[cut-span]` is ported without a surface.** `Entry.cut_span` and
  `shares_line` exist and are pinned by upstream's tests; no verb prints them
  and the port has no supported library API, so the user docs do not
  advertise them.
- **D15 grows, D8 gains a corpus.** D15 183 → 200: FOL's `Locale_Test`, the one
  theory its ROOT addresses by path, gained two `LEMMAS` entries (15 cases),
  and two of S7's new cases are D15 on CTT. `CTT/closed-stdout` exits 120 in
  the oracle and 141 here because the oracle's answer moved — 0.9.7 made it
  larger and 0.9.4 writes a batch whole at the end — so its broken pipe is
  now met only by the shutdown flush; it was 141 on both at 0.9.2 (D8, "P15:
  CTT joins").
- **`shows … for x` is agreement, not a divergence.** Both tools count 3
  conclusions; S7 records no entry, and the case goes upstream (below).

## Candidate upstream reports

- **`lemma forfix: assumes "P x" shows "Q x" for x`** — `show --premises`
  prints `shows: 3 conclusions` in both tools, reading `for` and `x` as bare
  conclusions. Isar's `long_statement` takes no `for` after `shows`, so
  Isabelle rejects the input: harmless, and unreachable in a theory that
  builds, but the count is wrong for what it was given.
- **`scripts/probe_cut_span.py`** names a corpus path under the author's home
  directory as the example in its usage line, and 0.9.8's CONTRIBUTING
  repeats it.
  The fork's CONTRIBUTING did not take the sentence; the script is upstream's
  and stays as shipped.
- **D17** (P14) is still open: `shape.py`'s `annotate_fanin` clears `pending`
  before `seen = facts | pending` in 0.9.8 as in 0.9.2, and it has not been
  reported.

## What is left open

- The compat stamp for 0.9.8 (the line above).
- `[client-status-stale]` and `[client-restart-owned]` in `todo.md`: S6
  observed that `--client-status` against a stale dedicated server exits 2
  ("selected server is unavailable or incompatible"), so its `[STALE: …]`
  marker shows only via `--client-restart`; and that `--client-restart` on a
  manually managed server replaces it with an owned fallback that dies with
  the invocation.
- `[decl-identity]` (upstream's `todo.md`, 0.9.8's "Known limits"): `graph
  citation`'s node table and `codeqs` still pick one declaration per name, and
  types and constants share one namespace — open on both sides.
- Nothing is pushed.
