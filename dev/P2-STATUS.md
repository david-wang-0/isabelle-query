# P2 — structure commands: status

`PLAN.md`'s P2 phase: `summary` (+ `--by-session`), `theory`, `defs`,
`outline`, `enclosing`/`at`, `largest`, `lines`, `grep`, `sorry`, `find`,
`show` — each a drop-in match for the Python tool, plus the global behaviour
they all share (root resolution, `CMD PATH...` routing, exit codes, the
`_user_pattern` rewrites, a closed stdout).

## Gate: the difftest matrix

`dev/difftest.sh` with no arguments runs the standard corpus set.  **730 cases,
146 per corpus**, comparing STDOUT byte for byte and the EXIT STATUS exactly;
stderr is compared for non-emptiness where the oracle's is non-empty.

| corpus | cases | clean | pinned | failing |
|---|---|---|---|---|
| `$QUERY_TEST_AFP/Abstract_Completeness` | 146 | 145 | 1 (D8) | 0 |
| `$QUERY_TEST_AFP/AODV` | 146 | 146 | 0 | 0 |
| `$QUERY_TEST_AFP/Category3` | 146 | 146 | 0 | 0 |
| `$QUERY_TEST_DISTRO/FOL` | 146 | 131 | 15 (D7) | 0 |
| `$QUERY_TEST_DISTRO/ZF` | 146 | 131 | 15 (D7) | 0 |

All 31 pins are the two oracle defects recorded as D7 and D8 in
`dev/DIVERGENCES.md`; there are no unexplained failures and no stale pins.

Subjects — theory names, entry names, the biggest proof's span, a file path —
are **derived per corpus from the oracle's own output**, so the matrix carries
to a new corpus with no name written into the script.  That is what let the
same 146 cases run unchanged over an 81-entry AFP entry and over ZF's 7,336.

The matrix covers, per command: the default view, every mode flag
(`-c` / `--names` / `-a` / `-V` / `--statement` / `--comments-only` /
`--no-comments` / `-U N` / `--with-comments` / `--and` / `--theory`), the
path-form positionals (a `.thy` path, a symlink to one, a directory, a
non-`.thy` file, a bare theory name, `-`), the locus grammar (`T:L`, `T:A..B`,
`T:A..`, `T:..B`, the rg `:` marker, a batch, past-EOF, malformed), and the
error paths (unknown theory, unknown positional, bad regex, bad root,
not-a-directory root, empty root, non-integer `-U`/`-N`, unknown flag, an
ambiguous long-option prefix, and each mutually-exclusive pair).

It also covers what is not a single command: `-R` before and after the
subcommand and in all three spellings (`-R DIR`, `-RDIR`, `--root=DIR`, and the
abbreviation `--roo`), `$ISABELLE_QUERY_ROOT`, a `.isabelle-query` marker
naming a subdirectory, discovery by walking up from the cwd, a rootless
directory of theories, a theory piped in on stdin, and a reader that closes the
pipe mid-stream.

## Whole-AFP spot check

`summary --by-session` over the whole of `$QUERY_TEST_AFP` as one root, both
sides, on this machine (12 logical cores):

| side | wall | peak RSS | rows |
|---|---|---|---|
| oracle | 37.6 s | 1.07 GB | 1,052 |
| Scala | **18.6 s** | 4.82 GB | 1,052 |

Identical line count, identical source-line total (6,067,890), identical theory
count (10,262) and identical session count (1,043).  **17 of the 1,043 session
rows differ**, plus the headline entry count and the TOTAL row — and every one
of the 17 is a session already named in `dev/DIVERGENCES.md`:

* D1 (`\<open>\\<close>`) — ResiduatedTransitionSystem, ResiduatedTransitionSystem2,
  HOL-CSP, Circus, Isabelle_Meta_Model;
* D2 (`\<^marker>` against the keyword) — Ceva, Interval_Analysis, MDP-Rewards,
  Complex_Bounded_Operators, Differential_Privacy;
* D3 (quoted `keywords` kind) — Optics, Shallow_Expressions;
* D4 (root-wide keyword union) — Formula_Derivatives, MSO_Regex_Equivalence,
  UTP, Circus.

Entry totals 411,181 vs 409,277 — the same two numbers the P1 sweep reported,
which is the check that P2's aggregation adds no error of its own.

## Timing observations

Not a benchmark (that is P7's `dev/bench.sh`), but what running the matrix 730
times a side made obvious.  5-run minimum, wall clock:

| corpus | invocation | oracle | Scala |
|---|---|---|---|
| Abstract_Completeness (2 thy) | `show step` | 71 ms | 1,024 ms |
| Category3 (28 thy) | `show preserves_limits` | 243 ms | 1,382 ms |
| whole AFP (10,262 thy) | `summary --by-session` | 37.6 s | **18.6 s** |

The crossover is exactly where `PLAN.md` predicted it: ~1 s of `isabelle`
wrapper plus JVM start is a fixed toll that a two-theory query cannot amortise,
and at Category3's size the JIT has not warmed either (the marginal parse cost
above the floor is 358 ms against the oracle's 172 ms — reversed from the
whole-corpus figure).  A single small query is 5-14x SLOWER, and only the
corpus sweep is faster.

This matters for P2's own users more than P1's did: `enclosing Foo:42` in a
build-chase loop is the small cold case, and it is the one the rewrite loses.
It is the argument for the warm-index server mode PLAN parks in P7, and the
reason the jEdit plugin (P5) shares that index rather than shelling out.

The matrix itself costs ~22 minutes for 730 cases, and about 80% of that is the
oracle: it re-parses the corpus per invocation, single-threaded.

## P1 gate

`dev/entrydiff.sh` re-run after every change here: all four variants
(`dump-theories`, `dump-entries`, `--spans`, `--bindings`) byte-identical on all
five standard corpora — 2 / 73 / 28 / 20 / 133 theories and 81 / 2,467 / 1,636 /
616 / 7,336 entries.  Unchanged by P2.

## What is implemented

New modules, in dependency order below the existing engine:

* `output.scala` — `Out`, `Exit_Code`, `Broken_Pipe`, `Flags`.  Output goes
  through a `Writer` on the raw file descriptor, not `System.out`: a
  `PrintStream` swallows the `IOException` from a closed pipe into a flag
  nobody reads, and the whole point of the 141 status is that a pipeline sees
  it.  `Exit_Code` is thrown rather than exiting on the spot, so buffered
  output written before a failure still reaches the terminal.
* `render.scala` — the `[src ...]` extent, the target scope path, preamble and
  annotation previews, `render_entry` with its statement / verbatim / comments
  modes, and the one verbosity dispatch (`emit_matches`) every match-listing
  command funnels through.
* `commands.scala` — the eleven `cmd_*`, plus what more than one of them needs:
  theory resolution and the "did you mean" hint, `_user_pattern` and its
  compile-or-exit-2 wrapper, the noise-span union, the line index, the
  `theory:line` / `theory:A..B` locus grammar, the proof-block drill-down, and
  the prime-aware name pattern.
* `cli.scala` — root resolution (`-R`, `$ISABELLE_LAYOUT_ROOT` /
  `$ISABELLE_QUERY_ROOT`, the `.isabelle-layout` / `.isabelle-query` marker,
  the walk up from the cwd, the nearest `ROOT`), `CMD PATH...` routing
  (file / directory / bare theory name / stdin, deduplicated by real path), the
  parse policy (`syntax` / `infer`), and a hand-rolled argparse mirror.

Changed: `Theory_Section` gained `session`, `is_thy`, `line_window` and a
`java.nio.file.Path`; `Theory` gained `parse_source` (stdin) and `parse_plain`
(a non-theory file); `Py` gained `rstrip`, `re_escape`, `comma` and
`parse_int`.

**No session-model refactor was needed.**  P1 already resolved the owning
session per theory and dropped it on the floor; `summary --by-session` only
needed it carried onto the section.  The keyword union stays root-wide (D4),
and the whole-AFP spot check bounds what that costs: 4 sessions out of 1,043.

### The argument grammar

Hand-rolled, because argparse's *observable* behaviour is part of the contract
a drop-in replacement keeps: unambiguous long-option abbreviation, an error on
an ambiguous one, short-flag bundling (`-ac`) and glued values (`-U3`, `-N3`,
`-RDIR`), `--opt=value`, `--`, positionals gathered across interleaved options
(`grep PAT -c FILE`), `-R` on either side of the subcommand, `-h` / `--version`
firing during the read so they beat a missing required positional, and exit 2
on any usage error.  Every one of those is a difftest case.

Two deliberate departures, both invisible to the matrix: subcommand names are
NOT abbreviated (neither is argparse's), and the help text is this tool's own
(PLAN exempts help prose; the flags themselves match).

The subcommands P3 and P4 own are registered as **not yet ported** and exit 2
with a message naming the phase.  They must never answer silently.

## Divergences added by P2

Three, all in `dev/DIVERGENCES.md`, all cases where reproducing the oracle
would mean shipping a defect:

* **D7** — `graph._build_line_index` sorts `(src_start, thy_end, Entry)` triples
  and one `axiomatization gle gless where …` line yields two entries with
  identical spans, so Python compares two `Entry` dataclasses and raises
  `TypeError`.  `grep` and `sorry` **do not run at all** in the oracle on FOL or
  ZF.  30 pinned cases.
* **D8** — the oracle's closed-stdout status is 141 on four corpora and 120 on
  the fifth for the same invocation, depending on whether the failing write
  lands inside the command body or in the interpreter's shutdown flush.  The
  rewrite is 141 in both cases.  1 pinned case.
* **D9** — two Python-only regex spellings (`(?P<n>...)`, `(?#comment)`) have no
  `java.util.regex` equivalent and are rejected with exit 2 rather than
  misread.  Not pinned; no corpus-derived pattern uses them.

Not a divergence, but worth knowing: `outline` **crashes on both sides** for a
`paragraph` / `subparagraph` heading — the heading recogniser accepts six
spellings and the indent table has four.  Same partial stdout, same exit 1,
stderr non-empty on both, so it is byte-parity on a shared bug.  Two difftest
cases hold that agreement in place (`paragraph-outline`, `paragraph-summary`)
so neither side can "fix" it by accident.

## What P3 should watch out for

1. **D7 disarms the oracle for most of P3.**  Every usage command
   (`callers`, `callees`, `unused`, `methods`, and the citation `graph`) builds
   the same line index that crashes, so on FOL and ZF there is nothing to diff
   against.  Either pick gate corpora with no multi-name `axiomatization`, or
   accept a pin on those two and verify against the source by hand.  Check
   before writing the matrix, not after.
2. **Theory-name keying is last-wins.**  `sections_by_theory` and
   `build_line_index` are keyed by theory NAME, mirroring the reference.  That
   is fine per session and wrong over a corpus where two entries both declare a
   `Misc`; P3's call graph is the first corpus-global consumer, so it will be
   the first to see it.
3. **`Flags` is a P2 subset.**  `--drop-names-upto`, `--keep`, `--by-theory`,
   `--roots` and `--external`'s recursive interaction still need adding, along
   with the shared `_add_drop_names_flag` help default that `-h` must list.
4. **The method/attribute tables are DATA.**  `_isabelle_namespace.py` and
   `_census_namespace.py` are committed scans; PLAN says port them as data.
   Note `cli._configure_namespace` gates the whole namespace machinery to five
   verbs — the pure text/structure commands P2 ships must keep paying nothing
   for it.
5. **`Discovery.thy_imports` returns `Thy_Header` import names**, and the
   reference's `parse_thy_imports` returns the raw clause tokens.  `deps` /
   `uses` print those tokens verbatim for an out-of-project import
   (`HOL-Library.FuncSet  [out-of-project]`), so the two spellings have to be
   compared before `deps` is written, not after it diffs.
6. **Peak RSS is 4.8 GB on a whole-AFP run** against the oracle's 1.07 GB (JVM
   default heap policy, not a working-set measurement).  A corpus-global call
   graph adds to the high-water mark, so this is worth a look before P3 rather
   than after.
7. **`resolve_theory` is linear and `stat`s per section** on the path branch.
   Fine for a batch of loci; a per-name lookup inside a graph walk would not be.
8. Reusable, already ported, do not re-roll: `Commands.noise_spans`,
   `build_line_index` / `entry_at_line`, `isa_word_pattern` (the prime-aware
   citation boundary), `binding_kinds` (the "is a named conjunct of" phrasing),
   `user_pattern` / `compile_user_pattern`, `parse_locus` / `parse_line_range`.
9. **Never edit `query_base/src` while a difftest is running.**  `isabelle
   query` rebuilds the component on demand when a source is newer than the jar,
   so an edit mid-run makes every remaining case fail to compile.  One run was
   lost to this.
