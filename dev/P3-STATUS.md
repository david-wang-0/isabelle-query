# P3 — usage commands: status

`PLAN.md`'s P3 phase: the call graph and the eight verbs that read it —
`callers`, `callees`, `deps`, `uses`, `refs`, `graph` (citation | imports, JSON
or DOT), `unused`, `methods` / `method` — plus the method/attribute namespace
ported as committed data, and the flags P2 deferred (`--drop-names-upto`,
`--keep`, `--by-theory`, `--roots`).

Every subcommand of the reference tool is now ported except `shape`, which
stays registered as **not yet ported** (P4) and exits 2 rather than answering.

## Gate: the difftest matrix

`dev/difftest.sh` with no arguments: **1,589 cases, 227 per corpus over seven
corpora**, comparing STDOUT byte for byte and the EXIT STATUS exactly; stderr
is compared for non-emptiness where the oracle's is non-empty.

| corpus | cases | clean | pinned | failing |
|---|---|---|---|---|
| `$QUERY_TEST_AFP/Abstract_Completeness` | 227 | 225 | 2 (D8, D10) | 0 |
| `$QUERY_TEST_AFP/AODV` | 227 | 226 | 1 (D10) | 0 |
| `$QUERY_TEST_AFP/Category3` | 227 | 226 | 1 (D10) | 0 |
| `$QUERY_TEST_DISTRO/FOL` | 227 | 161 | 66 (D7) | 0 |
| `$QUERY_TEST_DISTRO/ZF` | 227 | 161 | 66 (D7) | 0 |
| `$QUERY_TEST_DISTRO/Sequents` | 227 | 226 | 1 (D10) | 0 |
| `$QUERY_TEST_DISTRO/CTT` | 227 | 226 | 1 (D10) | 0 |

**1,451 clean, 138 pinned, 0 failing, 0 stale pins.**  Every pin is D7, D8 or
D10 in `dev/DIVERGENCES.md`; there are no unexplained failures.

The 89 new cases cover, per verb: the default view, every mode flag
(`-c` / `--names` / `-a` / `--external` / `--by-theory` / `--roots`), the `-r`
transitive form of each of the four verbs that has one, both `graph` kinds in
both formats, the `--keep` list in all three spellings (repeated flag, comma
list, an unknown name), a batch of subjects, an unresolvable subject for every
verb that takes one, and the argument grammar of every NEW flag: glued `-U2`
and `-fdot`, `--drop-names-upto=2`, the `--drop` abbreviation, a non-integer
`--drop-names-upto`, an invalid `graph` kind and format, and `--r` — which is
ambiguous between `--recursive` and `--roots` and must exit 2 on both sides.

Two distribution corpora joined the standard set.  FOL and ZF cannot carry the
usage family at all (D7: the oracle's line index raises `TypeError` there), so
`Sequents` and `CTT` were checked against the oracle FIRST and added as the two
non-HOL gate corpora the family can actually run on.  Both also carry the whole
P2 matrix clean, so the P2 gate is now checked on seven corpora rather than
five.  `CCL` and `FOLP` were tried and rejected: both D7-crash.

Pins take shell globs in both columns now (`callees-*  FOL`), which is what
keeps the 132 D7 pins to 32 lines.  A glob that over-reaches is still caught,
because a pinned case that AGREES is still a stale pin.

## Whole-corpus spot check

`unused` over the whole of `$QUERY_TEST_AFP` **cannot be diffed**: the oracle
dies on it in 73 s with the D7 `TypeError`, so there is nothing to compare
against.  Both halves of the check were done anyway, one-sided where it had to
be:

| run | side | wall | peak RSS | output |
|---|---|---|---|---|
| whole AFP, `unused` | oracle | 1:13 | 1.53 GB | **traceback, exit 1** (D7) |
| whole AFP, `unused` | Scala | **0:58** | 4.65 GB | 90,064 unused entries |
| whole AFP, `callers mono` | oracle | 0:44 | 1.71 GB | 1,363 lines |
| whole AFP, `callers mono` | Scala | **0:24** | 4.85 GB | 1,363 lines |
| AODV, `unused` | oracle | 0:00.41 | 36 MB | 1,094 lines |
| AODV, `unused` | Scala | 0:01.57 | 1.05 GB | 1,094 lines, **identical** |

`callers` is the substitute whole-AFP diff, and it is a real one: it is the one
usage verb that walks the source directly instead of building the line index,
so it survives D7 on the whole corpus.  With the namespace pinned on both sides
(`$ISABELLE_QUERY_NAMESPACE=committed` — see D11) the two agree on all 1,363
lines except **4, all of them D1**: two `Hiding` hits whose owning entry the
oracle's unterminated cartouche has swallowed.  The hit set — every
`theory:line` — is identical.

The downscaled `unused` check (AODV, as `PLAN.md` directs when the oracle dies)
is **byte-identical**, 1,094 lines.

**Memory.**  Peak RSS for a corpus-global call graph over the whole AFP is
4.65 GB, against P2's 4.82 GB for `summary --by-session` — so the graph costs
*less* than the P2 high-water mark, not more, and is well inside the ~8 GB
budget.  That is not luck: `live_source` stopped being a cached `lazy val`.
Every consumer reads the redacted view once per section and binds it to a
local, so caching bought nothing and held a second full copy of the corpus text
for the life of the process.  The other memory choices were made the same way —
def-site spans are a per-theory buffer rather than a set, and the candidate
scan intersects a tokenised line against the name set rather than materialising
any cross product.

## P1 gate

`dev/entrydiff.sh` re-run at the end: all four variants byte-identical on all
seven corpora — 2 / 73 / 28 / 20 / 133 / 14 / 5 theories and 81 / 2,467 /
1,636 / 616 / 7,336 / 406 / 82 entries.  Unchanged by P3.

## What is implemented

Three new modules, in dependency order:

* `namespace.scala` — the committed method / attribute / keyword tables as
  DATA (37 + 101 + 222 Pure, 211 + 390 census union), plus the late-bound
  router.  See "the namespace" below.
* `usage_graph.scala` — line→entry attribution, the prose and def-site
  exclusion masks, the positional fact extractor (`cited_facts_on_line` and
  the shadowed-name rule built on it), the single-pass call graph, the method
  census, and the one BFS behind every `-r`.  `noise_spans`,
  `build_line_index`, `entry_at_line` and `sections_by_theory` MOVED here out
  of `Commands`: that is where the reference keeps them, and one definition per
  notion is the point.
* `usage.scala` — the eight commands, plus a small `Json` writer that
  reproduces `json.dumps(indent=2, sort_keys=True, ensure_ascii=False)` byte
  for byte (keys sorted, two-space indent, `": "`, and only `"`, `\` and the
  C0 controls escaped — every `\<^sub>` name goes out raw).

Changed elsewhere: `Flags` gained `by_theory` / `roots` / `keep` /
`drop_names_upto`; `Cmd` gained a per-command `context_default` (a preview
wants `-U 2`, a caller listing wants `-U 0`) and `Opt` / `Pos` gained
`choices`; `Discovery.Session_Info` gained `parent`; `Commands` gained
`resolve_binding`; `Py` gained `format_fixed`.

Two ports that would have been silently wrong:

* **`Py.format_fixed`.**  Python's `f"{x:5.1f}"` rounds the double's exact
  binary value half-to-EVEN; `String.format("%.1f")` rounds half-UP.  They part
  company on any exact midpoint, and a percentage hits one regularly — one use
  of a method out of sixteen introducers is exactly `6.25%`, which the
  reference prints as `6.2`.  `BigDecimal(double)` + `HALF_EVEN` is the exact
  reproduction.
* **`refs` and `graph` are NOT in the namespace-command set.**  They build the
  same citation graph as `callers`, and they do read the table — but the
  reference does not list them, so they keep the committed default whatever the
  project's base logic is.  On a non-HOL session `callers -r` therefore routes
  against the Pure floor while `graph` routes against the HOL union, and the
  two disagree about whether `iff` is a method or a fact.  This is visible in
  the graph both commands print (it was 24 edges on FOL), so it is behaviour to
  reproduce, not an inconsistency to tidy away.

## The namespace, and its one default

`Namespace` holds both committed tables and binds **the broad HOL-family union
by default, for every caller** — the CLI and a direct engine caller get the
same table, which is `CONTRIBUTING.md`'s "a configurable global that moves a
measurement gets ONE default".  Stepping DOWN to the minimal Pure floor is an
explicit call, and the CLI makes it in exactly one place: `configure_namespace`
at dispatch, gated to the verbs that read the table
(`callers`/`callees`/`unused`/`methods`/`method`/`shape`), for a project whose
declared sessions resolve to a base logic that is *positively* not HOL.  An
unknown base — an out-of-scope parent session name reached under
`-R <sub-session>` — stays on the default; that asymmetry is deliberate and
ported from the reference.  `$ISABELLE_QUERY_NAMESPACE=committed` pins the
default and short-circuits even the step-down.

What is NOT ported is the runtime resolution: the reference dumps a
session-exact table out of a **built heap** when it can find one.  See D11 —
it makes the oracle's answer depend on which heaps the machine has built
rather than on the theories, none of the seven gate corpora has a built heap, and closing
it needs an `isabelle dump` resolver that belongs with the warm index, not with
the command port.

## Divergences added by P3

Two, both in `dev/DIVERGENCES.md`:

* **D10** — the oracle's `unused -r` prints DIFFERENT cascade depths on
  consecutive runs of the same corpus: the fixed-point loop tests each name
  against the unused set *as it grows*, so a chain collapses by however far
  Python's per-process string hash orders it.  The rewrite runs the cascade
  level-synchronised, which is deterministic and is what the flag's help text
  promises.  Verified on all five corpora that can run it: strip the
  `[cascade depth N]` markers and the two outputs are byte-identical, so the
  pin is exactly one case wide.
* **D11** — the heap-resolved namespace, above.

D7's entry was updated with what P3 measured: 66 pinned cases per corpus, and —
more useful — the list of usage verbs that SURVIVE it, which is a precise map
of what the line index is actually for.

Not a divergence, but worth knowing: `Discovery.thy_imports` (Isabelle's
`Thy_Header`) and the reference's `parse_thy_imports` (a regex over the
comment-stripped text) return **identical token lists** — 10,262 AFP theories
and 1,818 distribution theories, zero differing records.  P2 flagged this as
something to check before writing `deps`; it was, with a `dump-imports` dev
command that stays in the tree (`dev/dump_oracle.py imports` on the other
side) so a future change to either header parser is caught.

## What P4 (shape) should watch out for

1. **The method table is bound, and `shape` is already in the gate list.**
   `Namespace` is late-bound exactly so `shape`'s identifier classifier reads
   the same table the census does; `shape census` must bind the census union
   **unconditionally** (the reference does), not inherit whatever the project
   fallback picked, or a census stops regenerating identically.
2. **`Usage_Graph.cited_facts_on_line` is the M5a fan-in extractor**, ported
   whole including the `covered` flag, which no P3 verb reads.  P4's width
   metric is its first real consumer — do not write a second one.
3. `GOAL_KEYWORDS` / `CONTEXT_KEYWORDS` / `PLUMBING_KEYWORDS` /
   `CLOSING_KEYWORDS` are already there, and `Usage_Graph` deliberately holds
   them (the reference keeps them in `graph.py` for the same reason: the step
   classifier and the fact extractor must read one list).
4. **`Py.format_fixed` exists now** — `shape` prints many more percentages and
   ratios than `methods` does, and `String.format` is wrong for all of them.
5. The `shape` verb is a NESTED subcommand group
   (`shape summary|steps|lemma|widest|census`), which the hand-rolled parser
   does not model yet: `Cmd` has one flat option/positional list.  Adding a
   nested level is the first CLI change P4 needs, and `-h` must work at both
   levels.
6. `configs/m3.toml` and `--config` / `--corpus` are part of the shape surface
   and need a TOML reader; there is none in the Isabelle Scala classpath.

## What P5 (the jEdit plugin) should watch out for

1. **There is now a name→def-site index and a callers entry point that need no
   CLI.**  `Usage_Graph.entry_by_name(sections)` is the name→(theory, entry)
   lookup a "find usages" action resolves the caret identifier through, and
   `Usage.find_callers(sections, name, external)` returns
   `(theory, line, text)` triples directly — no `Out`, no argv, no exit codes.
   That pair is the whole plugin path: resolve, then list.  Keep it that way;
   do not push rendering into them.
2. **`find_callers` is the right primitive for the dockable, `build_call_graph`
   is not.**  The former is O(one name × source) and answers in a second on a
   session; the latter is a corpus-global build (58 s / 4.65 GB over the AFP)
   and is only worth it for `-r`, `unused` and the graph export.  A plugin that
   builds the whole graph on every right-click will feel broken.
3. **Every command still takes `List[Theory_Section]`**, so the warm index the
   plugin keeps is exactly the argument they want.  The one thing that is NOT
   incremental is the section list itself: `Theory.plan` + `Theory.parse` are
   per-file, so a dirty-buffer reparse can replace one element, but the
   custom-command keyword union is built root-wide in a first pass and would
   have to be recomputed when a header changes.
4. **`live_source` / `outer_source` are recomputed on every call** (that is
   what keeps the corpus-global peak down).  A plugin that redraws from them
   per keystroke wants its own cache at the buffer level; do not put the
   `lazy val` back on `Theory_Section`, or a whole-session index will hold two
   copies of every buffer.
5. **`Namespace` is process-global mutable state.**  One jEdit process may hold
   several projects; binding the table per query is fine for a CLI run and
   wrong for a plugin. Either pass the table into the graph builder or bind it
   per index, before two open sessions start disagreeing about `auto`.
6. `Commands.resolve_theory` is linear and `stat`s per section on the path
   branch (a P2 note that still stands): a per-name lookup inside an
   interactive loop wants an index, not that scan.
7. Theory-name keying is still **last-wins** (`sections_by_theory`,
   `build_line_index`, `noise_ranges`, `build_def_sites`).  It is faithful to
   the reference and invisible per session, but a plugin indexing a whole AFP
   checkout will have several `Misc`es, and the masks of one will be applied to
   the other.  Fixing it is a change to observable output, so it needs its own
   corpus diff — not a quiet fix inside the plugin.
