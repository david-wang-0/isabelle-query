# P7c — a citation names what its theory can see

Every scanner in the usage family worked name-first: find the token `mono` on a
line, look up the entries called `mono`, report the line as a caller of all of
them. Over one session that is right, because everything in a session can see
everything the session declares. Over a corpus it is not, and the AFP is a
corpus: 795 of the 1,361 hits `callers mono` reported were in theories whose
entire import closure declares no `mono` at all.

P7c is the necessary condition that removes those, and only those.

## What shipped

| | |
|---|---|
| `query_base/src/reach.scala` | new — the switch, the import resolver, the closure, the two filters |
| `query_base/src/usage_graph.scala` | the citation router's candidate filter |
| `query_base/src/usage.scala` | `find_callers`; `resolve_import` now delegates |
| `query_base/src/sites.scala` | `find_instantiations`, `find_code_equations` |
| `query_base/src/cli.scala` | `REACHABILITY_ENV`, `root_env`, `configure_reachability` |
| `query_base/src/delegate.scala` | the request root reads `CLI.root_env`, not "not the namespace one" |
| `query_base/lib/scripts/query_client.py` | the fourth forwarded variable |
| `dev/p7cprobe.sh` | new — 28 checks, five fixture theories and a probe-private server |
| `dev/difftest.sh` | the third pin, on the rewrite side only |
| `dev/DIVERGENCES.md` | D13 |
| `README.md`, `SCANNING.md`, `MIGRATING.md`, `CONTRIBUTING.md`, `todo.md` | the scope paragraphs, and `[reach-position]` |
| `demo/Demo_Core/Demo_Legacy.thy`, `demo/DEMO.md`, `demo/CHEATSHEET.md` | the tour case |

## The semantics as shipped

> A site in theory `T` may be attributed to a declaration in theory `D` iff
> `D = T`, or `D` is in `T`'s transitive in-project `imports` closure.

A **necessary** condition on visibility and nothing more, which is what makes
it safe to turn on by default: it can only drop an attribution, never invent
one. Five consequences, each a decision rather than a side effect:

1. **Multi-attribution inside the cone stays.** A site that can still see
   several same-named declarations is reported against all of them. Inside
   `src/HOL`, where everything imports `Main`, `callers rev` answers 668 with
   the filter and 668 without.
2. **A name the project declares nowhere is not filtered.** `callers` answers
   for any token — the plugin calls it on whatever word is under the caret —
   and about a token with no in-project declaration an import closure has no
   opinion. It is also the fast path: the closure is not built at all.
3. **Position within a theory is not consulted.** A citation written above the
   declaration it names is still attributed to it. `dev/p7cprobe.sh` §2 pins
   that as the shipped behaviour rather than leaving it an accident, and
   `todo.md`'s `[reach-position]` is the handle for the refinement — which is
   more than an inequality, because `lemmas`, `sublocale` and a `context`
   re-entry all bind a name at a line other than its declaration's.
4. **`unused` may honestly GROW.** An entry kept alive only by a citation that
   could not have been one is now correctly dead. On the demo that is
   `legacy_twice`; over a corpus it is the reason the verb gets more useful
   rather than noisier.
5. **`shape` and `methods` are out of scope, and that is a finding, not an
   omission.** `shape` reads `Usage_Graph.cited_facts_on_line`, which returns
   the TOKENS in citation position on a step; it never asks which entry a token
   denotes, so there is nothing to filter. `methods` identifies a method by
   POSITION after `by`/`apply`/`proof` and consults no name index either. Both
   were checked rather than assumed.

Two approximations, deliberately on the permissive side, because this filter
must never remove an attribution that could be real:

- the graph is keyed by theory **name**, and where a corpus declares one theory
  name twice (the AFP has many a `Misc`) the adjacency is the **union** of every
  section of that name. `Usage.import_depths`, which `deps` and `refs` read,
  takes the last-wins section instead — so the two agree except on such a
  duplicate, where this one reaches further. Last-wins here would have been
  *unsound*: a citation in the first `Misc` judged by the last one's imports.
- a declaration is any entry or bound name of that spelling, whatever its tag.

## The one bug this found in itself

`Reach.import_target` is deliberately **broader** than `Usage.resolve_import`,
and the difference is an import spelled as a path.

`HOL-MicroJava` reaches across its own subdirectories with `imports
../BV/Altern`. Discovery follows it (`Discovery.classify_import` has a `/`
branch), so those theories are in the index — but the name-level rule cannot map
the token, because its `.` rule finds the `.` of `..` and yields `/BV/Altern`.
For `deps` that is a cosmetic `[out-of-project]` line and it stays, because it
is what the reference prints. For the closure it is a **hole**, and a hole
prunes:

```
callers rev over $QUERY_TEST_DISTRO/HOL
  608     with the name-level resolver     <- 60 genuine MicroJava hits lost
  668     with the leaf rule
  668     with the filter off
```

`dev/p7cprobe.sh` §8 is the standing canary: inside one import tree the answer
must not move, and it did.

## The switch

`$ISABELLE_QUERY_REACHABILITY=off` restores name-only attribution exactly.

**Env-only, and that is the design.** `CONTRIBUTING.md` says a configurable
global that moves a measurement gets ONE default and the library caller gets the
same one as the CLI; the same argument makes one **channel** worth having. An
argv flag would exist on exactly one of the four front doors — the CLI has one,
the warm server, the jEdit plugin and a direct `Reach` caller do not — and a
switch only one caller can reach is the same defect in a different place. It is
`$ISABELLE_QUERY_NAMESPACE`'s shape, so a harness pins this exactly as it
already pins that. (A `--no-reachability` global flag was written and dropped:
it would have needed the flag translated into the forwarded request env to
survive delegation, which is a second channel for the same global.)

Bound in `CLI.run_result` immediately after `prepare(s)`, from **this request's**
environment, in **both** directions and **unconditionally**. Unconditional
because the variable it writes is process-global and a warm server serves many
clients: binding it only when a client asks for `off` would pin the switch for
everyone after them, which is precisely the defect `dev/p7probe.sh` §9b records
for the namespace table. It costs one environment lookup, so there is no verb
list to keep in step with either.

Three places, per watch-out 3 of `dev/P7B-STATUS.md`: `CLI.request_env`,
`FORWARDED_ENV` in the thin client, and a check that it survives the socket —
which is `dev/p7cprobe.sh` §6 rather than `dev/p7probe.sh` §9b, because the
fixture that makes the variable observable at all lives here.

Adding the fourth request variable also **corrected** a latent defect in the
delegating CLI: it derived a request's root from "the request variables that are
not the namespace one", so the next non-root variable added to that list would
silently have become a root. `CLI.root_env` now names the two that are
directories, and the derivation reads that.

## The closure

`java.util.BitSet` row per theory over interned theory ids. The alternative — a
`Set[String]` per theory — is what the whole-AFP number rules out: 10,262
theories at an average closure of a few hundred is hundreds of megabytes of
boxed strings, against 10,262² bits = **13 MB** here, flat however deep the
import chains run. Built by iterative post-order DFS (the recursion depth is the
import chain, and AFP entries run hundreds deep), with a fixed-point repair
reached only if a cycle is seen — a cycle is not a legal Isabelle project, but
under-approximating there would prune, so it is repaired rather than left.

Memoised per corpus under a **weak** key on the section list's identity: one
load of a project produces one list, the warm server and the plugin each hold
theirs for the life of the index, and a stale entry must not pin a re-indexed
corpus's sources — which for the plugin is the whole difference between a cache
and a leak.

The name → declaring-theory map is *not* in the closure. `build_call_graph`
builds it in the pass that already mints the name universe, so it costs no extra
traversal; the single-name verbs need one name and scan the entries for it,
after first checking that the name is declared at all.

## Measurements

Whole AFP (`$QUERY_TEST_AFP`, 10,262 theories, Isabelle2025-2 vintage), median
of 3, `--no-server`, namespace pinned:

| `callers mono` | count | wall | peak RSS |
|---|---:|---:|---:|
| filter **on** (default) | **566** | 21.6 s | 4.8 GB |
| filter **off** | 1,361 | 25.2 s | 4.9 GB |

**The filter pays for itself.** It reads one theory header per section — ten
thousand of them, in parallel — and buys skipping four fifths of the per-line
scan, so the default is 3.6 s *faster* than the compatibility mode, not slower.
The 13 MB of bitsets is invisible next to a 4.8 GB high-water mark.

Isolated closure cost, `$QUERY_TEST_DISTRO/HOL` (1,451 theories), where the
filter changes no answer at all and the two runs therefore scan the same
sections:

| `callers rev` | count | wall |
|---|---:|---:|
| filter **on** | 668 | 4.94 s |
| filter **off** | 668 | 4.90 s |

**40 ms over 1,451 theories, which is noise.** Both runs report the same 668
hits and scan the same sections, so the whole difference is the closure: one
header read per theory in parallel, one BitSet OR per import edge. Extrapolated
to the AFP's 10,262 theories it is still well inside the second the whole-AFP
`callers` figures move by.

The graph-building path, same corpus — and a demonstration that "one tree" is
about `Main`, not about a directory. `src/HOL` holds many sessions that do not
import one another, so the dead-code verb finds 251 entries that were being
kept alive by a citation from a theory that could not see them, even though
`callers rev` does not move at all (`List.rev` is under `Main`, visible
everywhere):

| dead-code count, `src/HOL` | entries | wall |
|---|---:|---:|
| filter **on** | 22,388 | 7.9 s |
| filter **off** | 22,137 | 8.5 s |

## Verification

### `dev/difftest.sh` — the standard matrix, unchanged

The seven standard corpora (`Abstract_Completeness`, `AODV`, `Category3`,
`FOL`, `ZF`, `Sequents`, `CTT`), `--no-server`, compatibility mode pinned:

```
2086 cases: 1946 clean, 140 pinned, 0 failing, 0 stale pins
```

Byte for byte the P7b figures. **0 stale pins** is the half that is easy to
miss: a pinned case that starts AGREEING fails the run, so a filter that had
quietly changed a pinned answer would have shown up here as loudly as one that
broke a clean case.

### The demo tree — the documented state, also unchanged

```
dev/difftest.sh demo/Demo_Core demo/Demo_Extras
596 cases: 531 clean, 5 pinned, 60 failing, 0 stale pins
```

All 60 are in `Demo_Extras` and all 60 are D2 — the
`definition\<^marker>\<open>tag …\<close>` showcase the oracle cannot index, which
`dev/difftest-pins` records as deliberately unpinned so the showcase stays
visible. **`Demo_Core` is 0 failing**, which is the check that mattered here:
`Demo_Legacy` gained a lemma, and the corpus the tour is measured on is still
byte-identical to the oracle.

### `dev/entrydiff.sh` — the canary

**28 / 28 clean.** A citation-level change must not move one entry, one span or
one binding, and the theory-set and entry-set dumps over all seven corpora say
it did not.

### The probes

| harness | result |
|---|---|
| `dev/p5probe.sh` | green — plugin/CLI parity (`callers R`: plugin 23, CLI 23) |
| `dev/p6probe.sh` | green — IDE features, and the `show -V` cross-check |
| `dev/p6bprobe.sh` | green — 27 shell checks and the fixture layer |
| `dev/p7probe.sh` | green — **85 checks, 0 failing**, unchanged from P7b |
| `dev/p7cprobe.sh` | green — **28 checks, 0 failing**, new |

Failability is shown in place, as the sibling probes show theirs: §9 asks for
the filtered answer and asserts it is NOT the unfiltered one, and asks for a
name the fixture does not contain and asserts a 0 rather than any default. Every
`expect` in the file passes only if BOTH the filtered and the unfiltered answer
match, so a filter that did nothing would fail §1, §3 and §4 rather than pass
them quietly. The harness demonstrated it can say no three times during
development — once for each of a summary-shape check, a row-diff defeated by
column padding, and an edge count that was counting `nodes` as well.

## Watch-outs for whoever comes next

1. **The gate runs the compatibility mode, and it has to.** A differential
   matrix can only measure a difference, never an improvement, so
   `dev/difftest.sh` pins the rewrite side to `off`. That pin is asymmetric —
   the oracle has no such notion — and it is the ONLY thing keeping the usage
   family byte-identical on a corpus with more than one import tree. Drop it and
   the matrix will report the improvement as hundreds of failures.

2. **A hole in the closure prunes silently.** Any `imports` spelling
   `Reach.import_target` fails to map removes real attributions and says
   nothing. The invariant that catches it is `dev/p7cprobe.sh` §8: inside a
   corpus with one import root the answer must not move. If a new corpus shape
   turns up, add it there before trusting the number.

   One did. A theory the ROOT declares by PATH (`theories
   "Nested/Nested_Fix"`) is carried under that spelling, so an import that
   resolves by LEAF — `imports "../Nested/Nested_Fix"`, or a bare sibling —
   missed it, and `codeqs quad` answered 2 where the source had 3. §8's canary
   did not catch it, and the reason is worth keeping: it asks ONE name on one
   corpus, and `src/HOL` really does hold the shape (`HOL-UNITY` declares
   `"Simple/Reach"`, `"Comp/Alloc"` and a dozen more by path) — it is just not
   `rev` that the hole was eating there. It was eating exactly one entry:
   `UNITY/WFair`'s `is`, which `unused` reported dead and no longer does
   (22,388 → 22,387 over `src/HOL`, and no other change on any gate corpus).
   `dev/p7cprobe.sh` §8b is the fixture that asks the question directly, and
   `Reach.build`'s alias table is the fix. The finding came from P6d
   (`dev/P6D-STATUS.md`), which met it while writing a nested panel fixture.
   Its cosmetic half — the NAME is still wrong — is `todo.md`'s
   `[theory-name-leaf]`, and it is held back by parity, not by difficulty.

3. **The filter is per THEORY, not per line.** Anything that wants "is this
   citation below its declaration" is `[reach-position]` in `todo.md`, and it is
   not a one-line inequality — `lemmas`, `sublocale` and `context` re-entry all
   bind a name somewhere other than its declaration.

4. **`Reach.enabled` is one more process-global.** It joins `Namespace`'s table
   in `todo.md`'s `[namespace-by-value]`: whoever threads that table through as
   a value should thread this alongside it, since both exist for the same reason
   and both are what stop two projects being queried at once in one JVM. This
   one is cheaper to move — it is a single boolean with one writer.

5. **The demo now contains a deliberate spelling coincidence.**
   `Demo_Legacy.legacy_twice` binds a variable called `twice`, which
   `Demo_Extras` declares as a function. It is there to make §2.6 of the tour
   demonstrable, and it moves counts: any change to it moves the entry, line,
   lemma, dead-code, `shape summary` and `methods` figures the tour quotes.
