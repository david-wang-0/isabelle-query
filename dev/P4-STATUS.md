# P4 — the shape family: status

`PLAN.md`'s P4 phase: `query shape summary|steps|lemma|widest|census`, the
per-step engine behind them, and the M3 corpus-config surface.  **Every
subcommand of the reference tool is now ported**; `CLI.unported` is empty and
`query -h` no longer carries a "not yet ported" line.

## Gate: the difftest matrix

`dev/difftest.sh` with no arguments: **2,086 cases, 298 per corpus over seven
corpora**, of which the shape section is **497, 71 per corpus**.

| corpus | shape cases | clean | pinned | failing |
|---|---|---|---|---|
| `$QUERY_TEST_AFP/Abstract_Completeness` | 71 | 70 | 1 (D8) | 0 |
| `$QUERY_TEST_AFP/AODV` | 71 | 71 | 0 | 0 |
| `$QUERY_TEST_AFP/Category3` | 71 | 71 | 0 | 0 |
| `$QUERY_TEST_DISTRO/FOL` | 71 | 71 | 0 | 0 |
| `$QUERY_TEST_DISTRO/ZF` | 71 | 71 | 0 | 0 |
| `$QUERY_TEST_DISTRO/Sequents` | 71 | 71 | 0 | 0 |
| `$QUERY_TEST_DISTRO/CTT` | 71 | 71 | 0 | 0 |

**495 clean, 2 pinned, 0 failing, 0 stale pins** in the shape section.

The 71 cases per corpus cover, per view: the default, `--json`, every mode flag
(`--scope` × `--content` on `summary`, `-a` on `steps`, all four `--metric`
values and four spellings of `-N` on `widest`), the span grammar on `steps`
(bare theory, `A..B`, open `A..`, a single line, a past-the-end window, an
unresolvable token, a non-locus token), the batch and substring forms of
`lemma`, `--resume` on `census` against a prefix of the corpus's own oracle
census plus a missing and a garbage resume file, and the argument grammar of
every NEW flag: glued `-N3`, `--scope=entry`, `--metric=w1`, `--config=FILE`,
a non-integer `-N`, an invalid `--metric` / `--scope` / `--content`, `shape
no_such_view`, and `--co` — ambiguous between `--config` and `--corpus`, exit 2
on both sides.

The `--config` surface gets all five outcomes: a single-table file with the
corpus inferred, a multi-table file with `--corpus`, a multi-table file
*without* one (refused), an unknown corpus name, a missing file, and a file
that is not TOML.

Bare `query shape` and every `-h` stay OUT of the byte-comparison matrix, as
they always have — `PLAN.md` fixes the wording of stdout and exempts help text.
They were checked by hand instead, and the check that matters is mechanical:
the FLAG SETS of `shape -h` and of all five `shape <view> -h` are identical to
the oracle's, spelling for spelling, including `-N`/`--top` and the short `-a`.

**The whole matrix, re-run at the end of the phase: 2,086 cases, 1,946 clean,
140 pinned, 0 failing, 0 stale pins.**  The 140 are P3's 138 (D7 × 132, D8 × 1,
D10 × 5) plus the two new D8 census pins.  Nothing P2 or P3 established moved.

**P1 gate:** `dev/entrydiff.sh` re-run at the end — all four variants
(`dump-theories`, `dump-entries`, `--spans`, `--bindings`) byte-identical on all
seven corpora, 28 of 28 checks.  2 / 73 / 28 / 20 / 133 / 14 / 5 theories and
81 / 2,467 / 1,636 / 616 / 7,336 / 406 / 82 entries.  Unchanged by P4.

## D7 does not reach the shape family

Predicted watch-out, and the answer is the useful part: **no shape verb builds
the line index**, so all five run in the oracle on FOL and ZF.  `shape` is the
first command family to be gate-checked on all seven corpora with zero D7 pins
— P2's structure family needed 15 pins each there and P3's usage family 66.

## D11 covers shape's path, and the harness pin was wrong

`ISABELLE_QUERY_NAMESPACE=committed` does reach shape: `shape` is in
`CLI.namespace_commands`, so the env var short-circuits the step-down for
`summary` / `steps` / `lemma` / `widest`.  `census` is bound EARLIER and
unconditionally, exactly as the reference does — before the env check and
independent of the project's base logic — so a census regenerates identically
whatever the corpus's logic is.

What P4 found is that the harness applied the pin to ONE side.  That
short-circuits the oracle's step-DOWN to the Pure floor as well, so on a
non-HOL corpus the two sides were compared against two *different* method
tables.  Invisible for `callers` / `methods`; immediately visible in `shape
steps` on ZF, where `field` is a proof method under the census union and a free
variable under the Pure floor:

```
Zorn:484   goal   6   2   0   0   u \<in> field(r)     # union
Zorn:484   goal   6   3   0   0   u \<in> field(r)     # Pure floor
```

Run **unpinned**, the two implementations agree on that line and on the whole
of `shape steps` — the oracle steps ZF down to the floor, and so does the
rewrite, warning included.  So the step-down logic is verified equal; the gate
now pins both sides, which is what makes it compare one table against itself.
See D11.

## The jEdit plugin

`jedit_query` gets no shape view in this phase, and the stub-to-real transition
is invisible to it: the plugin contains **no reference to `shape`, to
`CLI.unported`, or to the `Cmd` table at all**.  It reaches the engine directly
(`Usage_Graph.entry_by_name`, `Usage.find_callers`) rather than through the CLI,
so there was never a "not yet ported" path to break.  Its one CLI touch-point,
`CLI.configure_namespace(session, "callers")`, still compiles unchanged — the
new sub-verb argument is defaulted — and `jedit_query` builds green on every
`isabelle scala_build` in this phase.

## Whole-AFP census spot check

Both sides, `/usr/bin/time -v`, one run each, over all of `$QUERY_TEST_AFP`.

| side | wall | peak RSS | records | bytes |
|---|---|---|---|---|
| oracle | 2:54.76 | 62 MB | 304,987 | 268 MB |
| Scala (stock `-Xmx4g`) | **2:36.47** | 4.63 GB | 306,525 | 268 MB |
| Scala (`-Xmx512m`) | 2:41.35 | **0.97 GB** | 306,525 | 268 MB |

Not byte-identical, and every difference is accounted for by an existing
divergence.  By `(session, theory, lemma)` key: 295,283 oracle keys, 296,689
Scala keys.

* **1,410 keys only in the rewrite** — declarations the oracle loses.
  ResiduatedTransitionSystem 846, ResiduatedTransitionSystem2 508, HOL-CSP 43,
  Circus 9 (**D1**, the backslash cartouche); Differential_Privacy 2,
  Tabulation_Hashing 1, First_Order_Terms 1 (**D2**, the document marker).
  Exactly D1's and D2's entry lists.
* **4 keys only in the oracle** — all of them named `\<^marker>\<open>tag` or
  `\<^marker>\<open>contributor` (**D2**/**D6**: the oracle reads the marker as
  the declaration's name).  The same declarations appear on our side under
  their real names, so nothing is lost.
* **47 of the 295,279 common keys differ**, in three groups:
  * **43** differ only in the `w1_est_*` / `const_est_*` / `const_canon_est_*`
    columns — the knock-on D1/D2 predicts.  `Shape.build_ctx` seeds the
    classifier with `sec.entries`, so a theory where we find declarations the
    oracle misses has a larger entry-name bucket, and a name that is an entry
    for us classifies `const` where it classified `var` there.
    Differential_Privacy 32, ResiduatedTransitionSystem2 4, Ceva 3,
    Complex_Bounded_Operators 2, MDP-Rewards 2.
  * **3** differ in the span columns — Optics/`mylens_bij_lens` (**D3**: with
    `alphabet` in the keyword table the entry ends after 4 lines, not 25) and
    two in Differential_Privacy (**D2**).
  * **1** differs in `proof_tokens` alone: `Feuerbach/special`, 2,149 against
    2,151.  This is new, and it is **D12**.

**4.63 GB is not the live set, and this was settled by measurement rather than
argued.**  Rerun with the JVM heap capped at 512 MB — which has to be done in
`$ISABELLE_HOME_USER/etc/settings`, since Isabelle's own settings overwrite
`ISABELLE_TOOL_JAVA_OPTIONS` from the environment and the JVM never picked up
`_JAVA_OPTIONS` either, so two earlier attempts at this measured nothing — the
census **completes, exits 0, and emits byte-identical output** at **972 MB**
peak RSS and 2:41 wall.  So ~95% of the stock figure is GC slack: a 4 GB heap
with no pressure to collect, and RSS counts pages touched.  The extra
collection costs 3% of wall time.

That confirms the per-session bound is real — `Session.sections_for_session`
builds one session's sections inside the loop and they are dropped at the end
of it, and `Sec_Ctx.release()` drops the derived views with them.  Nothing here
retains a section past its session.  It also means the 4.6 GB P3 recorded for
a whole-AFP call graph and the 4.63 GB here are the same number for two
workloads with very different footprints, which is exactly what a heap ceiling
looks like and exactly why the memory column of a benchmark has to pin `-Xmx`
before it means anything.

## Timing, and where the rewrite loses

Not a benchmark (that is P7's `dev/bench.sh`), but the shape family's own
shape of the curve `dev/P2-STATUS.md` already recorded.  3-run minimum, wall
clock, on Category3 (28 theories):

| invocation | oracle | Scala |
|---|---:|---:|
| `shape summary` | 911 ms | 1,818 ms |
| `shape steps` | 706 ms | 1,743 ms |
| `shape widest` | 627 ms | 1,712 ms |
| `shape lemma preserves_limits` | 246 ms | 1,387 ms |
| `shape census` | 935 ms | 1,914 ms |

Same crossover as P2's: the ~1 s of `isabelle` wrapper plus JVM start is a
fixed toll that a 28-theory query cannot amortise, and `shape lemma` — the
smallest unit of work in the family — is 5.6x slower, the family's worst case
for exactly that reason.  Only the whole-corpus run is faster, and only by 11%
(2:36 against 2:54) because a census is **analysis-bound, not parse-bound**:
the parse is where the rewrite's advantage lives (Isabelle's own lexer,
`Par_List` per theory), and it is a small share of a run that spends most of
its time in per-proof metric computation, single-threaded on both sides.

The port also faithfully reproduces the reference's redundancy, which is most
of the remaining headroom: `Usage_Graph.cited_facts_on_line` is recomputed
**three times per step** (once in `annotate_fanin`, once in `fact_intervals`,
once in `introduce_consume`), and `greedy_extract` runs twice per block (M4's
full DAG, then M6 at k = 0 and 8).  Memoising the per-line fact scan and
running the per-proof analysis over `Par_List` within a session are both
real wins, and both change work order, so they are P7 with their own
re-verification rather than a parity phase.

## Divergences found

**One new entry, D12** — `\w` is not the same character class in Python and
Java.  Python's is `str.isalnum() or '_'` (`L* ∪ Nd ∪ Nl ∪ No ∪ _`); Java's
`UNICODE_CHARACTER_CLASS` `\w` is `\p{Alpha} ∪ \p{M} ∪ \p{Nd} ∪ \p{Pc}`.  So
`\p{No}` (`²`, `½`) is a word character for the reference and not for us, and a
combining mark is one for us and not for it.  The whole AFP notices once:
`(u²+v²)` is 5 proposition tokens to the reference and 7 here, so
`Feuerbach/special` reports `proof_tokens` 2,151 against 2,149.  No entry NAME
is affected (the P1 dump is byte-identical over both corpora) and no gate
corpus contains such a character.  Deliberately not fixed in P4 — see D12 for
why the fix is a P7-sized change.

**D8 gained the other half of its own story.**  `shape census | head -3` exits
141 on the rewrite always, and on the oracle only when the census EXCEEDS the
64K pipe buffer: under it the oracle fills the buffer, finishes and exits 0
before `head` is scheduled.  Deterministic in the output size, not a race —
stable over five runs, 0 on exactly `Abstract_Completeness` (34K) and `CTT`
(54K), 141 on the other five (298K to 5.2M).  Two pins; a pin on any of the
other five would be stale.

## What is implemented

Three new modules, in dependency order:

* `shape_data.scala` — the two committed tables as DATA: 73 harvested corpus
  constants (the classifier's bucket (c)) and the 33-glyph notation table
  behind `const_canon_est`.
* `shape.scala` — the step scanner and the seven axes.  The metric definitions
  are NOT restated: they live at each metric in the reference's `shape.py`,
  and a second prose copy in Scala would drift from the first.  What the file
  records instead is every place the obvious JVM spelling is silently wrong.
* `shape_cmds.scala` — the five views, plus `Jsonl` and `Toml`.

Changed elsewhere: `Entries.balanced_end` gained a `start` offset;
`Usage_Graph` gained `leading_method`; `Namespace` gained `keyword_names`;
`Py` gained `repr_float`; `Cmd` gained `subs` and the CLI a group parser;
`CLI.Session` gained `sections_for_session`.

Four ports that would have been silently wrong:

* **`Py.repr_float`.**  `json.dumps` writes a float with Python's
  `repr`, which is not `Double.toString` in either half.  The DIGITS are the
  shortest decimal that round-trips (found by rounding the double's exact
  value half-even to p = 1..17 significant digits and stopping at the first
  that reads back equal — if any p-digit string round-trips, the closest one
  does, so this yields Python's digits).  The LAYOUT switches to exponent form
  at decimal point ≤ -4 or > 16, writes a signed two-digit exponent, and
  appends `.0` to anything that would read as an integer.  Java disagrees on
  all three, and a census record carries ten floats.
* **Two different JSON writers.**  `Json` in `usage.scala` is
  `indent=2, sort_keys=True, ensure_ascii=False`; `Jsonl` here is
  `json.dumps(obj)` with the defaults — `", "` / `": "`, INSERTION order, and
  `ensure_ascii=True`, so every non-ASCII character goes out as `\uXXXX`.  The
  reference calls `json.dumps` two different ways and both are observable, so
  the port carries two writers rather than one configurable one.
* **`Namespace.keyword_names`, not `non_citation`.**  The width classifier asks
  "is this identifier term syntax?", which is methods ∪ attributes ∪ keywords.
  The router's reject-set also carries `ARG_MODIFIERS` (`add`, `del`, `only`),
  and reusing it would have made `add` a constant in every proposition that
  mentions one.
* **`Usage_Graph.leading_method` is positional.**  No table lookup, which is
  what keeps the whole automation axis (`trivial_frac`, `method_kinds`)
  independent of which namespace is bound — the property `METRICS.md` claims
  for it.

`Sec_Ctx` is the one structural departure from the reference, and it is a
memory decision: the Scala source views are `def`s by design (P3), so the
prose mask and `outer_source` hang off a per-section object whose `release()`
drops the big one when the section's last proof has been analysed.  A
whole-corpus `summary` therefore does not accumulate a second copy of the
archive.  `cmd_shape_summary` also reduces each proof to its row inside the
analysis callback rather than keeping a `Proof_Metrics` per proof, which the
reference can afford and a 300,000-proof run cannot.

## What P7 should watch out for

1. **D12 is a regex-dialect problem, not a shape problem.**  Fixing it means a
   translation layer in `Py.compile` — `\w` → `[\p{L}\p{N}_]`, `\W` → its
   complement, `\s` → Python's `str.isspace()` set (which also differs:
   Python calls `\x1c`–`\x1f` whitespace, Unicode does not).  `\b` cannot be
   rewritten as a class, because Java derives it from its own `\w`, so that
   one needs `(?<![\p{L}\p{N}_])` / `(?![\p{L}\p{N}_])`.  It sits under the
   deepest lexical primitive in the engine, so it changes what a NAME is, what
   `grep` matches and what the call graph sees: it needs the P1 entry-set gate
   re-run over both corpora, not just the difftest.
2. **`Namespace` is process-global, and `census` now binds it
   unconditionally.**  In a warm server one `shape census` leaves the broad
   union bound for every later query in that process, including a `callers` on
   a ZF project that should have stepped down.  P5's note (#5) said to pass the
   table in or bind per index; the census makes that concrete, because it is
   the one caller that binds on purpose rather than by policy.
3. **The peak-RSS protocol needs an `-Xmx`, and it must be set in the settings
   file.**  Every Scala figure recorded so far (P3's 4.65 GB, P4's stock
   4.63 GB) is the JVM heap ceiling, not the live set — the same number for two
   workloads with very different footprints.  Capped at 512 MB the whole-AFP
   census still completes with identical output at 972 MB and 3% more wall
   time, so the memory column of a benchmark says nothing until the heap is
   pinned.  Note the mechanism: Isabelle's `etc/settings` OVERWRITES
   `ISABELLE_TOOL_JAVA_OPTIONS` from the environment, and the JVM did not pick
   up `_JAVA_OPTIONS` here either, so the only override that takes is a line in
   `$ISABELLE_HOME_USER/etc/settings`.  `dev/bench.sh` should do that, and
   `query`'s own default heap is worth revisiting: 512 MB costs 3% and turns a
   4.6 GB process into a 1 GB one.
4. **Two redundancies are ported faithfully and are the obvious speed-up.**
   `cited_facts_on_line` is computed three times per step and `greedy_extract`
   twice per block.  Memoising the first per line, and running the per-proof
   analysis over `Par_List` within a session (collecting in order, since record
   order is part of the contract), are the two changes that would move the
   census; both change work order and need their own re-verification.
5. **The census's error isolation is slightly coarser than the reference's.**
   `Theory.parse` swallows a per-file failure and drops that theory; the
   reference lets it propagate, so the whole SESSION is reported skipped.  No
   gate corpus exercises it, and the Scala behaviour is the one the whole-root
   path has had since P1, so this is a consistency note rather than a defect —
   but a corpus with an unreadable theory would produce a short session on one
   side and a skipped session on the other.
6. **`Toml` is a subset reader.**  It covers what an M3 config is — tables of
   string lists, comments, wrapped arrays — and refuses everything else.  A
   contributor who writes valid TOML the reader does not model gets exit 1
   rather than a silently dropped selector, which is the right failure, but it
   is a smaller language than `tomllib` accepts.
7. **`shape` is the first nested group, and the parser now models one.**  A
   second group (if `graph` ever grows sub-verbs) needs nothing new; but the
   group level accepts only `-h` / `-R` / `--version`, exactly as argparse's
   does, so a group-level flag would have to be added to every view instead.
