# P9 — the port meets upstream 0.8.1, and most of the divergence list closes

Upstream released 0.8.0 and 0.8.1 while this port was in P7–P8: 43 commits,
`git log v0.7.0..v0.8.1`. Roughly half of them are fixes to defects **this port
found** — upstream ships the check as `scripts/probe_scala_port_findings.py` —
and the other half are changes the port did not have. P9 merged that release
into the fork, ported the second half, and retired the entries in
`dev/DIVERGENCES.md` that the first half made obsolete.

The result is the thing the parity contract was written for and had never
reached: over the whole AFP and the whole Isabelle distribution `src`, the two
implementations now report **the same entry set, the same theory set and the
same bindings, byte for byte**, and exactly one record's span differs.

## What the survey established (2026-09-01)

The 2,086-case matrix, the Scala side pinned exactly as `dev/difftest.sh`
pinned it then, run against each oracle in turn:

| | vs 0.7.0 | vs 0.8.1 |
|---|---:|---:|
| clean | 1,946 | 1,695 |
| pinned | 140 | 44 |
| failing | 0 | **251** |
| stale pins | 0 | **96** |

Four read-only gap analyses (`.dev/gap-G[1-4]-*.md`, gitignored) then
established, by running both tools on fixtures and corpora, exactly which
upstream changes the port already had and which it lacked: 6 items already
equivalent, 17 to port, in four clusters. Those reports were the working specs
for S1–S4; the plan is `dev/P9-PLAN.md`.

## The gate, step by step

Every step's own commit records its attribution; the rule was that a step may
leave the gate red only in cases it *names* as belonging to a later step, the
count must not go up, and no case may go newly red. It never did.

| | cases | clean | pinned | failing | stale | what it left red |
|---|---:|---:|---:|---:|---:|---|
| S0 `[p9-merge]` | 2,086 | 1,791 | 3 | **292** | 0 | everything S1–S4 |
| S1 the CLI contract | 2,107 | 1,894 | 3 | **210** | 0 | S2–S4 |
| S2 the parser | 2,107 | 1,949 | 3 | **155** | 0 | S3 (101), S4 (54) |
| S3 the citation graph | 2,149 | 2,092 | 3 | **54** | 0 | S4 alone |
| S4 loci and shape | 2,149 | 2,146 | 3 | **0** | 0 | — |
| S5 close-out | 2,149 | 2,146 | 3 | **0** | 0 | — |

S0 deleted every pin the survey found stale — the whole D7 family (132 lines of
`dev/difftest-pins`) and D10's `unused-recursive *` — and re-pointed the
harness at a 0.8.1 oracle built from the merged tree, with the oracle's version
pinned so that a stale `query` on `PATH` is a refusal rather than a plausible
red. S1 and S3 added cases rather than removing them (3 empties per corpus,
then 6 `--reach` cases per corpus), which is why the total grows while the
failures fall.

Three pins survive, all D8, all on the two smallest standard corpora:
`closed-stdout Abstract_Completeness` and `shape-census-pipe` on
`Abstract_Completeness` and `CTT`.

The warm matrix (`QUERY_DIFFTEST_WARM=1`) was taken once on
`Abstract_Completeness` at S4: 307 cases, 305 clean, 2 pinned, 0 failing, and
no server left registered.

**The demo corpora moved too, and S5 caught it.** `demo/Demo_Extras` used to
differ in ~60 cases on purpose — it holds the `definition\<^marker>` showcase
the oracle could not index — and the `cwd-discovery Demo_Core` pin was D2's.
Both facts expired with D2. The demo run is now 614 cases: 611 clean, 3 pinned,
0 failing, 0 stale, the D2 pin deleted and one new D8 pin earned
(`Demo_Extras`'s `find … -a -V` is 12,739 bytes, over this engine's 8 KB writer
buffer and under the reference's 64 KB pipe).

## The corpus at the close

`dev/entrydiff.sh` against oracle 0.8.1, whole corpora, all four dump variants
(2026-09-02):

| | theories | records | differing |
|---|---:|---:|---|
| whole AFP (`afp-2025-2`) | 10,262 | 411,181 | **1**, in `--spans` only |
| whole distribution `src` | 1,818 | 101,388 | **0** |
| the seven standard corpora | — | — | **0** (28 of 28 dumps identical) |

The one differing record is **D5**, `Substitutions_Lambda_Free:58` — a
`\<comment>` whose cartouche opens on the next line, where the port is right
and upstream has measured the fix and declined it (one occurrence in 11,514
theories against a change to the riskiest code in that package).

Everything else that used to differ is agreement, and the accounting is exact:

| entry | records | state |
|---|---:|---|
| D1 `[cartouche-escape]` | 1,867 | resolved upstream 0.8.0, from this port's finding |
| D2 `[marker-decl]` | 751 distro + 16 AFP | closed on BOTH sides during P9 |
| D3 `[keyword-kind-quoted]` | 37 | resolved upstream 0.8.0, from this port's finding |
| D4 `[keyword-scope]` | 16 | **no longer a divergence** — a shared weakness now |
| D5 `[comment-newline]` | 1 | open, and the only one |
| D6 `[marker-decl]` (name grammar) | 7 names | closed on BOTH sides during P9 |
| D7 `[span-ties]` | 132 difftest cases | resolved upstream 0.8.0, from this port's finding |
| D10 `[cascade-level]` | the `unused -r` depth marker | resolved upstream 0.8.0, from this port's finding |

**D4 is the one that needed investigating rather than ticking.** The plan
expected its 16 records to still differ. They do not, and the reason is a
second-order effect of D3: P1 recorded D4 as a divergence precisely because
"the oracle has the same union and only escapes the symptom because D3 keeps
`alphabet` out of its table" — so fixing D3 upstream gave the oracle the same
over-wide keyword table and it now mints the same 16 phantoms.
`Isabelle_C/…/C_Appendices:831:DEF:\<^verbatim>`, whose name is read out of a
`text` block, is line 203,558 of *both* dumps. It stays in `dev/DIVERGENCES.md`
as the place the measurement is written down, and `todo.md`'s `[keyword-scope]`
is the handle on both sides.

### Corpus-scale figures, re-taken

Whole AFP, cold, committed namespace, `--no-server`:

| | `--reach name` | `--reach closure` (default) |
|---|---:|---:|
| `callers mono -c` | 1,363 | **634** |
| `unused -c` | 97,568 | **101,154** |
| citation-graph edges | 2,291,456 | **1,355,188** (−41%) |

`unused` **grows** by 3,586, which is the filter working rather than a cost.

S3 measured the same two numbers as a step delta rather than as a mode
comparison — before S3 against after it, both at the default: `callers mono -c`
632 → **634** and `unused -c` 93,058 → **101,154**. Those deltas match
upstream's: `unused` +8,332 there for `[name-is-not-identity]`, +8,096 net here
including the edges `[citation-reach]` 1a puts back.

**The absolute figures do NOT match upstream's published 561 and 97,747, and
that is the corpus, not the port.** Upstream's docs quote a 9,910-theory AFP;
this one is 10,262. Run on the SAME corpus the two agree exactly: whole-AFP
`callers mono --reach name` is **1,365 lines on each side, byte-identical, 0
differing**. That also retires D11's last residual, which was 4 differing lines
"all of them D1".

## Decisions this phase made

- **`--reach {closure,name}` is a flag, and `$ISABELLE_QUERY_REACHABILITY` is
  deleted.** It was env-only from P7c on the argument that an argv flag would
  exist on only one of the four front doors. Half of that was wrong: the thin
  client and the warm server forward argv verbatim, so a flag reaches them with
  no second channel, and the plugin and a library caller want a parameter
  rather than a global anyway. `Reach.enabled` and the server's per-request
  rebinding of it went with it — one process-global fewer than P7c shipped, and
  the "one default, one channel" rule intact. Upstream spells the flag the same
  way, so this is convergence as well as simplification.
- **D14 is a deliberate, stated difference, and the only one in the citation
  graph.** For the visibility filter the port counts a name an entry BINDS — a
  datatype constructor, a `shows` conjunct, a `.simps` — as a declaration of
  that name; upstream consults entries only. Isabelle binds those names in the
  theory that writes them, `codeqs Cons` has no subject at all without the
  rule, and `callers <constructor>` is the plugin's commonest call. No gate
  corpus exercises it, so nothing is pinned and nothing needs to be; the
  fixture is in the entry.
- **`CLI.version` is `0.8.1-scala`, and the number tracks the ORACLE.** The
  suffix is what lets a script tell the two tools apart; the number names the
  upstream release whose contract the port matches, which P9 is where it became
  0.8.1. That is a policy, recorded in `CLAUDE.md`, not a semver argument about
  this tree's own history.

## Upstream defects found, worth reporting rather than diverging over

All three were verified against oracle 0.8.1 on a fixture, and the port
reproduces each byte for byte. None is hit by any gate corpus.

1. **A colliding path-spelled ROOT name labels as `two/ex/ex/Foo`.** The label
   tuple is the resolved parent's path components plus the DECLARED name, so
   when the ROOT spells the name `"ex/Foo"` the directory appears twice — and
   no verb takes the label back (`enclosing two/ex/ex/Foo:3` is "no such
   theory"). Both tools print exactly that, including the hint.
2. **`shape steps <label>` filters by theory NAME, not by the label.** So
   `shape steps -a beta/Examples` lists `alpha/Examples:4` as well: the label is
   right and the FILTER is the open question. Identical on both sides.
3. **The "did you mean …?" hint picks a colliding theory last-wins.**
   `_suggest_theory` ranks over `{s.theory: s for s in sections}`, so on a
   corpus with two `Examples` the hint names whichever section came last. This
   one the port did NOT reproduce — it ranked over the sections and so named the
   FIRST — which made it an unrecorded divergence rather than a shared quirk.
   S5 fixed the port to match (`Exampels:1` now suggests `beta/Examples.thy` on
   both sides). Neither choice is right; equality is what is available.

## What the next phase inherits

- **`[theory-name-leaf]`** — a ROOT may spell a theory `"Nested/Nested_Fix"`
  and both implementations then call it that, where Isabelle's
  `Thy_Header.import_name` takes the last segment. Upstream did not change the
  naming either, so parity still holds it back; the reachability half is closed
  (`Reach.leaf_index`, `dev/p7cprobe.sh` §8b). Entry condition unchanged: a
  D-series entry, its own pins, and `dev/entrydiff.sh` re-run.
- **`[namespace-by-value]`** — untouched by P9 and still the reason no two
  projects can be queried at once in one JVM. P9 removed the *other* global
  (`Reach.enabled`), which makes this the last one.
- **`instances` / `codeqs` still print a bare `site.theory`.** S4 qualified the
  nine oracle-shared emitters; the two rewrite-only verbs were left alone
  because they have no oracle to be judged against and their rows are already
  path-grouped in the plugin. On a collision corpus their locus is therefore
  ambiguous where every other verb's is not — a small, self-contained follow-up
  with `Render.locus_labels` already in place.
- **The `-scala` versioning note.** Because the number now tracks the oracle,
  the next upstream release moves it whether or not this tree changes. Whoever
  takes that on should decide it deliberately: the alternative is to let the
  number drift and say so in `CLAUDE.md`.
