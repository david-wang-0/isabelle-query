# Divergences from the Python oracle

Every place the Scala engine deliberately does NOT reproduce the Python
implementation, with the evidence that made it deliberate.  `PLAN.md` requires
this list to stay near-empty and every entry to have a reason better than "hard
to match"; each one below is a case where the Python implementation disagrees
with Isabelle's own lexer or its own header parser, and reproducing it would
mean shipping a known bug.

Measured over the whole AFP (Isabelle2025-2 vintage): 411,181 Scala records vs
409,277 oracle records over 10,262 theories, 1,952 differing records — all of
them accounted for below.  Over the whole Isabelle distribution `src`: 101,388
vs 100,879 records over 1,818 theories, 751 differing, all of them D2.  Both
theory sets are byte-identical.

No entry is ever LOST: over both corpora the set of `theory:line:tag:name`
identities the oracle reports is a subset of the Scala engine's.  Every
difference is a declaration the oracle misses, or a span that moves because a
missed declaration turns out to be the neighbour that bounds it.

The five standard P1 corpora (`Abstract_Completeness`, `AODV`, `Category3`,
`FOL`, `ZF`) are byte-identical in all four dump variants; none of these cases
occurs there.

---

## D1 — `\<open>\\<close>`: a cartouche whose body is a backslash

**Cost: 1,867 records over 5 entries.** ResiduatedTransitionSystem (1,052),
ResiduatedTransitionSystem2 (742), HOL-CSP (49), Circus (13),
Isabelle_Meta_Model (11).

```isabelle
fun resid  (infix \<open>\\<close> 70)          -- ResiduatedTransitionSystem/LambdaCalculus:566
lift_definition Hiding :: \<open>...\<close> (infixl \<open>\\<close> 69)   -- HOL-CSP/Hiding:171
```

The residuation / hiding operator is written by putting a single backslash in a
cartouche.  The Python tokenizer's one-pass alternation lists `\\` (a string
escape) before `\<open>`, so at the `\\<close>` it consumes the two backslashes
as an escape, never sees the close, and stays in cartouche state **to the end of
the file**.  Every declaration below that line then reads as "inside a term":
`_scan_decl_body` never terminates, `_is_boundary_at` is skipped, and the
declaration swallows the rest of the theory.  `resid` is reported with
`decl_end=10926` — the last line of the file.

An escape is a rule of the *string* lexer.  Isabelle scans a cartouche body
raw, so the body here is `\\` and the cartouche closes normally;
`Token.explode` gets this right, and so the Scala engine finds the 1,867
declarations the oracle loses.

## D2 — `definition\<^marker>\<open>tag ...\<close> name`

**Cost: 751 records in the Isabelle distribution, 16 in the AFP.**  This is the
one divergence that is large where it matters most: `HOL/Analysis` tags its
declarations for the document build throughout, so the oracle silently misses
509 distribution declarations — `istopology`, `moebius`, `is_Arg`,
`subtopology`, `pullback_topology`, `retract_of`, `aff_dim` … — plus the 242
span records their absence shifts.  AFP entries affected: Ceva,
Interval_Analysis, MDP-Rewards, Complex_Bounded_Operators, Tabulation_Hashing,
First_Order_Terms, Differential_Privacy.

```isabelle
definition\<^marker>\<open>tag important\<close> istopology :: "..."   -- HOL/Analysis/Abstract_Topology:13
typedef\<^marker>\<open>tag important\<close> 'a topology = "..."     -- HOL/Analysis/Abstract_Topology:17
lemma \<^marker>\<open>tag important\<close> fold_absorb:            -- Tabulation_Hashing/Xor:118
```

A document marker is written with no space after the command keyword, so the
oracle's `DECL_RE`, which requires `(?=\s|$)`, does not match at all and the
declaration is missed entirely.  The custom-command path fails the same way for
`typedef`: its lead token is read as `typedef\<^marker>`, which is in no keyword
table.  Where the marker does follow a space, the oracle's name parser reads the
marker itself as the name (`\<^marker>\<open>tag`), because Isabelle markup
tokens are name characters.

`\<^marker>` is a formal comment to Isabelle's lexer, so the Scala outer view
blanks it and the command is recognised; `strip_decl_prefix` then steps over
every formal-comment marker (`\<comment>`, `\<^cancel>`, `\<^latex>`,
`\<^marker>`, in both spellings) the way the reference implementation already
steps over `\<comment>`, and the name comes out right.

Residual, deliberately not fixed here — see D6.

## D3 — `keywords "cmd" :: "kind"` with a QUOTED kind

**Cost: 37 records over 2 entries when each is read on its own** —
Optics (6), Shallow_Expressions (31).

```isabelle
theory Lens_Instances
  imports ...
  keywords "alphabet" "statespace" :: "thy_defn"        -- Optics/Lens_Instances:5
```

Isabelle's header grammar takes the kind as a *name*, quoted or not.  The
oracle's hand-rolled keyword-block parser only reads a kind out of an `op`
token — an unquoted run — so a quoted kind yields no tag, the command is never
entered in the table, and every `alphabet ...` / `expr_constructor ...`
declaration in those sessions is invisible.  The Scala side reads the header
with `Thy_Header`, which is the parser Isabelle itself uses.

## D4 — cross-session keyword union, whole-corpus root only

**Cost: 16 records over 4 entries, and only when the whole AFP is passed as ONE
root.** Formula_Derivatives (4), MSO_Regex_Equivalence (3), UTP (3), Circus (6).
Each of those four is byte-identical when read as its own root.

Both implementations union every discovered theory header's `keywords` table
over the whole root, mirroring Isabelle's session-wide `Keywords.++`.  That is
right for one session and too coarse for a corpus of a thousand: with the AFP
`thys` directory as the root, Optics' `alphabet` command is in scope for
Formula_Derivatives, whose `sublocale DA < DAs` / `alphabet init delta ...`
continuation line then reads as a declaration.

This is the tool's existing design, not a change: the oracle has the same union
and only escapes the symptom because D3 keeps `alphabet` out of its table.  It
is listed because a whole-corpus sweep shows it.  Fixing it means scoping the
table per session, which changes the parse of every custom-command entry and
belongs with the session model, not with P1.

## D5 — `\<comment>` whose cartouche is on the next line

**Cost: 1 record.** Substitutions_Lambda_Free/Substitutions_Lambda_Free:58.

```isabelle
  shows \<open>\<exists> k. u k = \<emptyset>\<close>
\<comment>
\<open>
  This lemma could easily be generalized ...
\<close>
proof-
```

Isabelle's `comment_prefix` allows any blanks — newlines included — between
`\<comment>` and its cartouche, so this is one formal comment.  The oracle
matches the marker with a per-LINE regex, so it sees a bare `\<comment>` and a
separate live cartouche, and charges all five prose lines to the lemma's
statement (`decl_end=67` instead of `62`).

## D6 — a structural marker inside a name (shared weakness, NOT fixed)

Both implementations build a name out of Isabelle markup tokens
(`\<^sub>`, `\<^bold>` and friends are ordinary name characters), and neither
stops at a *structural* one, so

```isabelle
definition\<^marker>\<open>tag important\<close> coprod_final_sink\<^marker>\<open>tag important\<close> :: "..."
```

is indexed as `coprod_final_sink\<^marker>\<open>tag` rather than
`coprod_final_sink`.  7 names in the whole AFP.

Deliberately left alone.  Truncating a name at `\<open>` / `\<comment>` /
`\<^marker>` would correct 7 names but would also change 3 records that
currently AGREE with the oracle, and the effect is observable in `find` /
`show` output rather than in the entry set — so it belongs with P2's difftest
matrix, where the change can be judged against the commands that display it,
not with P1's entry-set parity.

Related residual: where a marker's cartouche fills the whole declaration line
(`lemma \<^marker>\<open>contributor \<open>...\<close>\<close>` with the name on the
next line, First_Order_Terms/Term:37) the Scala engine reports `?`.  The oracle
reports the marker text.  Both miss `inj_on_Fun_fun`; recovering it needs the
`goal` route to take the name-lookahead the `def` and `typedecl` routes already
take, which would rename every `lemma`-alone-on-its-line declaration and so is
a change to make deliberately, with its own corpus diff.

## D7 — the oracle's line index crashes on a multi-name `axiomatization`

**Cost: `grep`, `sorry` and most of the usage family do not run AT ALL in the
oracle on 2 of the 7 standard corpora** (`FOL`, `ZF`).  P3 confirmed the
prediction: 66 pinned cases per corpus, 15 from P2 and 51 from the usage
family.

```
$ query -R $QUERY_TEST_DISTRO/ZF sorry
Traceback (most recent call last):
  ...
  File ".../isabelle_query/graph.py", line 52, in _build_line_index
    spans.sort()
TypeError: '<' not supported between instances of 'Entry' and 'Entry'
```

`_build_line_index` builds `(src_start, thy_end, Entry)` triples and sorts
them.  Python compares tuples element by element and only reaches the third
component when the first two are equal — which is exactly what one
`axiomatization` line produces:

```isabelle
axiomatization gle gless where ...      -- FOL/ex/Locale_Test/Locale_Test1:548
```

```
ex/Locale_Test/Locale_Test1:548:AXIOM:gle:src=548-548:decl_end=548:…
ex/Locale_Test/Locale_Test1:548:AXIOM:gless:src=548-548:decl_end=548:…
```

Two `Entry` dataclasses with identical spans, `Entry` has no ordering, and the
sort raises.  ZF hits it in `ZF_Base`, `Coind/Static` and `ex/LList`; FOL in
`ex/Locale_Test/Locale_Test1`.  The whole command dies with a traceback and
exit 1 before printing anything.

The Scala engine sorts by the two integers only and leaves equal spans in entry
order, so `grep` and `sorry` answer normally.  Verified against the source: on
FOL, `grep subst_all` reports the one live hit at `IFOL.thy:830` and, with
`--with-comments`, the ML-body mention at `FOL.thy:348` — the two occurrences a
raw `grep -rn` finds, correctly classified.

Reproducing the crash would mean shipping a `TypeError` as a feature, so this
is a deliberate divergence.  132 difftest cases are pinned on it (66 each on
FOL and ZF); the pins carry the exit-status difference (1 vs 0) as well as the
stdout one.

Which P3 verbs SURVIVE is the useful part of the map, because it says exactly
what the line index is for: `deps`, `uses` and `graph imports` read the import
graph and never build one; `callers` without `-r` walks the source directly;
and every verb that resolves its subject first (`refs No_Such`) returns before
the graph exists.  Those run in the oracle on FOL and ZF, and they are clean.
It also cost the P3 gate two distribution corpora, replaced by `Sequents` and
`CTT` — the two the oracle can carry, verified by running it there first.

## D8 — a closed stdout is 141, always; the oracle sometimes says 120

**Cost: 1 difftest case, on the smallest corpus.**

`CONTRIBUTING.md` fixes the closed-stdout status at 141 — `128 + SIGPIPE`, what
a shell reports for a process killed by SIGPIPE, so a pipeline and a `$?` check
read the same as they do for `yes | head`.  The oracle implements it by
catching `BrokenPipeError` around the command body, pointing fd 1 at
`/dev/null` (so the interpreter's own shutdown flush has somewhere to go) and
exiting 141.

That only works when the failing write lands INSIDE the command body.  When the
whole answer fits in the interpreter's buffers and the first failing write is
the shutdown flush, the `except` never runs and Python exits 120 with the
message the comment beside that handler describes as fixed:

```
$ query -R $QUERY_TEST_AFP/Abstract_Completeness find . . . . -a -V | head -3
Exception ignored while flushing sys.stdout:
BrokenPipeError: [Errno 32] Broken pipe
$ echo ${PIPESTATUS[0]}
120
```

Reproducibly 120 on `Abstract_Completeness` and reproducibly 141 on the other
four standard corpora, for the same invocation — the status depends on how much
output happened to be buffered when the reader went away, which is not
something a caller can reason about.

The rewrite writes through a `Writer` on the raw file descriptor and lets the
`IOException` propagate, so the failure is caught wherever it happens and the
status is 141 in both cases.  Pinned as `closed-stdout` on
`Abstract_Completeness`.

**P4 found the same defect from the other side, and it is sharper.**  When the
WHOLE answer fits in the 64K pipe buffer, the oracle's writes never fail at
all: it fills the buffer without blocking, finishes, and exits **0** — while
`head` is still being scheduled.  `shape census | head -3` is the case, and the
split is exactly the output size, not a race:

| corpus | census bytes | oracle | rewrite |
|---|---:|---|---|
| `Abstract_Completeness` | 34,320 | **0** | 141 |
| `CTT` | 54,522 | **0** | 141 |
| `Sequents` | 298,272 | 141 | 141 |
| `FOL` | 383,077 | 141 | 141 |
| `Category3` | 1,020,051 | 141 | 141 |
| `AODV` | 1,677,225 | 141 | 141 |
| `ZF` | 5,267,121 | 141 | 141 |

Five runs each, stable.  So the oracle's closed-stdout status is 0, 120 or 141
depending on how much output there happened to be — which is the same fact D8
already records, now with the threshold named.  The rewrite is 141 throughout,
which is what `CONTRIBUTING.md` fixes.  Pinned as `shape-census-pipe` on the
two corpora under 64K; a pin on the other five would be stale.

## D9 — regex dialect: two Python-only spellings are rejected, not misread

**Cost: two constructs, neither of which appears in the docs, the tests, or any
corpus-derived pattern.**

User patterns are Python `re` in the oracle and `java.util.regex` here.  Over
the subset the tool's own documentation and output teach — literals,
alternation, character classes, anchors, `\b`, `\d`/`\w`/`\s` (Unicode-aware on
both sides, via `Pattern.UNICODE_CHARACTER_CLASS`), inline flags `(?i:...)`,
backreferences, and possessive quantifiers — the two agree, and the
`_user_pattern` rewrites (`\|` → `|`, escaping `\<...>` markup tokens) are
ported exactly, including Python's own `re.escape` character set.

Two Python spellings have no `java.util.regex` equivalent:

| pattern | oracle | rewrite |
|---|---|---|
| `(?P<n>step)` — Python named group (Java spells it `(?<n>step)`) | matches | `ERROR: invalid regex`, exit 2 |
| `(?#comment)step` — inline comment | matches | `ERROR: invalid regex`, exit 2 |

Both fail LOUDLY, on stderr with exit 2, which is the behaviour the CLI
contract already fixes for a bad pattern — never a silent "no matches", which
is the failure this tool exists to prevent.  Not worth a translation layer: the
first is spelled `(?<n>...)` here and the second is a comment.

## D10 — `unused -r`'s cascade depths are not reproducible, even oracle-to-oracle

**RESOLVED UPSTREAM in 0.8.0 (`[cascade-level]`, d8a50c9), which took this
reading and credited it here.  The evidence below stays because it is what
made the case; the divergence is closed, the `unused-recursive` pin is gone
from `dev/difftest-pins`, and the two implementations print the same depths.**

**Cost while it lasted: the `[cascade depth N]` marker on `unused -r`, on
every corpus.  The unused SET, the entry order, the header counts and every
other `unused` form agreed exactly.**

```
$ cd $QUERY_TEST_AFP/Abstract_Completeness
$ for i in 1 2 3; do query unused -r | md5sum; done
ff3ceb082e9b587d9b53db2e15554932  -
0b77629fe55a49f6ace6ecb22ed9d772  -
0b0c2f0227edc6b7c228f1e3b2342767  -
```

Three runs, one corpus, three answers.  `_compute_unused_recursive` iterates

```python
for name in graph.all_names - set(unused) - keep:
    callers = graph.callers.get(name, set())
    if callers and callers <= set(unused):
        unused[name] = depth
```

and re-reads `unused` — which the loop body is growing — on every test.  So a
name whose only caller was marked EARLIER IN THE SAME PASS is given the same
depth as its caller instead of one more, and how far a chain collapses depends
on the order names come out of `graph.all_names`, i.e. on Python's per-process
string hash seed.  `PYTHONHASHSEED=0` makes it repeatable, which is the proof
rather than a fix: nothing about the corpus decides the number printed.

The rewrite runs the cascade LEVEL-SYNCHRONISED: each pass tests against the
set as it stood before the pass, so depth 1 means "became unused when the
depth-0 entries were removed", which is what the flag's help text promises.
That is deterministic, and it is the only reading under which two runs of a
build-hygiene check can be diffed.

The divergence was confined to the marker.  Verified at the time on all five
corpora the oracle could run this on: strip `  [cascade depth N]` from both
sides and the outputs were byte-identical.  0.8.0's `_compute_unused_recursive`
now snapshots the frontier before each pass, which is the same rule
`Usage.compute_unused_recursive` has always run, and the whole form agrees
without stripping anything.

## D11 — the oracle's method table is resolved from BUILT HEAPS; ours is committed

**Cost: nothing on any gate corpus, and everything on a machine where the
project being queried happens to have a built session heap.**

`cli._configure_namespace` tries `_namespace_resolve.resolve_project` before
falling back to a committed table: the router is bound to the union of the
*dumped* method/attribute name spaces of whichever declared sessions have a
built heap.  So the answer `callers` gives depends on what has been built on
the machine, not only on the source being read.  On this one:

```
$ query -R $QUERY_TEST_AFP callers mono -c
1361                     # table resolved from built heaps: 172 methods, 440 attributes
$ ISABELLE_QUERY_NAMESPACE=committed query -R $QUERY_TEST_AFP callers mono -c
1361                     # census union: 211 methods, 390 attributes
```

and the same query on a machine with no AFP heaps built would use the *Pure
floor* instead (the AFP declares ZF-based sessions, so
`_use_broad_fallback` steps the whole corpus down), which reports **2437** —
`mono` stops being a known attribute, so `assumes mono: "…"` starts counting as
a use.  Three tables, three answers, and which one a caller gets is a fact
about which heaps their Isabelle user home holds rather than about their
theories.

The rewrite has no heap-dump path — `PLAN.md`'s P3 asks for the committed
tables as data, and an ML dump is a different kind of dependency from
"parse the sources".  It therefore reproduces the reference's **no-heap**
behaviour exactly, which is the branch the reference itself takes on a clean
machine and on all seven gate corpora (verified: each binds either the census
union or the Pure floor, never a dump).  Pin both sides with
`ISABELLE_QUERY_NAMESPACE=committed` and a whole-AFP `callers mono` agrees on
1,363 output lines with **4 differing lines, all of them D1** — the two
`Hiding` hits whose owning entry the oracle's unterminated cartouche has
swallowed.

Closing it means an `isabelle dump`-backed resolver, which belongs with the
server/plugin work (a warm index has somewhere to keep the result), not with
the command port.

**P4 made the difftest pin symmetric, and that is a correction.**  Until P4 the
harness pinned only the ORACLE to `committed`.  That short-circuits the
reference's step-DOWN to the Pure floor as well, so on a non-HOL corpus the
oracle kept the broad HOL union while the rewrite — correctly reproducing what
the reference does on a clean machine — stepped down, and every table-reading
verb compared two *different* tables.  It happened to be invisible for
`callers` / `methods` on the gate corpora.  It is plainly visible in `shape
steps` on ZF, where `field` is a proof method under the census union and a free
variable under the Pure floor:

```
Zorn:484   goal   6   2   0   0   u \<in> field(r)     # both sides, union
Zorn:484   goal   6   3   0   0   u \<in> field(r)     # both sides, Pure floor
```

Run UNPINNED, the two implementations agree on that line and on the whole of
`shape steps`: the oracle steps ZF down to the floor and so does the rewrite,
including the stderr warning.  So the step-down logic is verified equal; it is
simply not what the pinned gate measures, and pinning both sides is what makes
the gate compare one table against itself.

## D12 — `\w` is not the same character class in Python and Java

**Cost: 1 record in 306,525 over the whole AFP, in two derived count fields; 0
on all seven gate corpora.**

```isabelle
  \<comment> \<open>Then (u²+v²)*D = (cy*u-(cx-bx)*v)² ...\<close>   -- Feuerbach/Feuerbach:359
```

The engine's lexical atom is

```
ISA_WORD_CHAR = (?:\\<\^?\w+>|[\w'])
```

and every scanner built on it inherits whatever `\w` means.  The two dialects
disagree in **both directions**:

| character | category | Python `\w` | Java `\w` (`UNICODE_CHARACTER_CLASS`) |
|---|---|---|---|
| `²` U+00B2, `½` U+00BD | `No` | **yes** | no |
| `Ⅸ` U+2168 | `Nl` | yes | yes |
| combining acute U+0301 | `Mn` | no | **yes** |
| `é`, `α` | `Ll` | yes | yes |

Python's `\w` is `str.isalnum() or '_'`, i.e. `L* ∪ Nd ∪ Nl ∪ No ∪ _`; Java's is
`\p{Alpha} ∪ \p{M} ∪ \p{Nd} ∪ \p{Pc} ∪ join controls`.  So `\p{No}` is a word
character for the reference and not for the rewrite, and a combining mark is
one for the rewrite and not for the reference.

Only one thing in the whole AFP notices.  `u²` is ONE proposition token to the
reference and two (`u`, `²`) here, so `Feuerbach/special` reports
`proof_tokens` 2,151 against the oracle's 2,149 — the line has three `²`, but
`)²` splits into two tokens on both sides, so only the two glued to a letter
move.  No entry NAME is affected (the P1 entry-set dump is byte-identical over
both corpora), and no gate corpus contains such a character at all.

Not fixed here, and the reason is proportion.  A faithful `\w` means a small
regex-dialect translation layer in `Py.compile` — `\w` → `[\p{L}\p{N}_]`,
`\W` → its complement, and `\s` → Python's `str.isspace()` set, which also
differs (Python calls `\x1c`–`\x1f` whitespace and Unicode does not).  `\b`
cannot be rewritten as a class at all, because Java derives it from its own
`\w`.  That layer sits under the deepest lexical primitive in the engine, so
it changes what a NAME is, what `grep` matches and what the call graph sees,
and it needs the P1 entry-set gate re-run over both corpora to land.  For two
tokens in one AFP proof, that is a P7 change with its own verification, not a
P4 one.

**P7 did not take it either, and for the same reason.**  P7's own gate was the
warm server, which touches `cli.scala` and nothing lexical; folding a change to
the engine's deepest primitive into that phase would have meant one gate
covering two unrelated risks.  It is carried forward as open work —
`todo.md`'s `[regex-dialect]` — with the entry-set re-run named as its
entry condition.  This entry stays the evidence; the todo item is the handle.


## D13 — a citation is attributed only to a declaration its theory can SEE

**RESOLVED BY CONVERGENCE: upstream shipped the same rule in 0.8.0
(`[citation-reach]`, 1e6cbd2, refined by `[name-is-not-identity]` and
`[import-leaf]`), so this is no longer a difference between the two
implementations.**  It stays as a full entry because it is the reasoning behind
a rule both tools now apply, because `dev/difftest.sh` compared against the
name-only mode for four phases and the record of why belongs somewhere, and
because ONE clause of it still differs — see **D14**.

**Cost while it was a divergence: nothing on any gate corpus, and everything on
a corpus with more than one import tree.**  It was the one entry here that was
neither a reproduced bug nor a corrected one: a **narrowing** of what the tool
claims, able only to remove an answer, with a switch.

The 0.7.0 reference implementation attributes a citation by NAME alone.  It finds the
token `mono` on a line, looks up the entries called `mono`, and reports the
line as a caller of all of them.  Over one session that is right — everything
in the session can see everything the session declares.  Over a corpus it is
not:

```
$ isabelle query -R $QUERY_TEST_AFP callers mono -c --reach name
1361
$ isabelle query -R $QUERY_TEST_AFP callers mono -c
566
```

(The pair as first measured, at P7c.  Both numbers have since moved, with the
parser and with `[symbol-body-tokens]` / `[import-leaf]`; the corpus-scale
figures are re-taken at the close of P9 — `dev/P9-PLAN.md` S5.)  795 of those
1,361 hits are impossible.  (Both figures are under the
committed-namespace pin the harness runs with; under the unpinned default the
pair is 2,437 → 1,311 — a different baseline, the same pruning, the same
sites.)  The shape of every one of them:

```
$ isabelle query -R $QUERY_TEST_AFP callers mono -U 0 | grep Mono_Bool_Tran:45
  Mono_Bool_Tran:45   mono_const (LEMMA) 44..47  "mono (\<lambda>_. c)"     # off only

$ isabelle query -R $QUERY_TEST_AFP deps Mono_Bool_Tran -r
Import-transitive dependencies of Mono_Bool_Tran:
  Complete_Lattice_Prop  (109 src lines, 10 entries)  [direct]
  Conj_Disj  (270 src lines, 28 entries)  [direct]
  WellFoundedTransitive  (132 src lines, 8 entries)  [depth 1]
  Main  [out-of-project]
```

`MonoBoolTranAlgebra`'s whole import closure is three in-project theories and
`Main`, and none of the three declares a `mono`.  The `mono` on that line is
HOL's own `Orderings.mono`, arriving through an `imports Main` that `query`
deliberately does not follow — so the line was being reported as a caller of
two dozen unrelated AFP lemmas that happen to be spelled `mono`, in
`Abs_Int0`, `EpiMonoIso`, `Heyting`, `Safety_Logic`, `Constructions` and the
rest.  Not one of them is in scope where it is cited.

**The rule.**  A site in theory T may be attributed to a declaration in theory
D iff `D = T`, or D is in T's transitive in-project `imports` closure.  It is a
NECESSARY condition on visibility, not a sufficient one, so:

- it only ever **drops** an attribution; nothing new is invented;
- a site that still reaches several same-named declarations stays
  multi-attributed — inside `src/HOL`, where everything imports `Main`,
  `callers rev` answers **668 either way**, because every theory there really
  can see `List.rev`;
- a name the project declares NOWHERE is not filtered at all.  `callers`
  answers for any token, and about a token that is not declared here no import
  closure has an opinion;
- **position within a theory is not consulted.**  A citation above the
  declaration it names is still attributed to it.  The linear-position
  refinement is real future work, not a silent partial: it needs an ordering
  the entry index already has, and it would make the rule depend on where a
  `lemmas` or a locale re-export sits.

It applies at the two attribution points, so every verb inherits it from one
place: the citation router's candidate filter (`callers -r`, `callees`, `refs`,
`graph citation`, `unused`) and the single-name section filter (`callers`,
`instances`, `codeqs`).  **`unused` may honestly GROW** — an entry kept alive
only by an unreachable same-name citation is now correctly dead.  `shape` is
untouched and out of scope: it counts cited TOKENS per step and never
attributes one to an entry.  `methods` likewise — a proof method is identified
by position, not by lookup.

Two approximations, both deliberately on the permissive side, because this
filter must never remove an attribution that could be real:

- the graph is keyed by theory NAME, and where a corpus declares one theory
  name twice (the AFP has many a `Misc`) the adjacency is the UNION of every
  section of that name.  `Usage.import_depths`, which `deps` and `refs` read,
  takes the last-wins section instead; the two agree except on such a
  duplicate, where this one reaches further;
- a declaration is any entry of that spelling, whatever its tag — **and any
  name an entry BINDS**, which is the one clause upstream does not share.  See
  **D14**, which states it, gives the fixture and says why the port keeps it.

And one place where it used to be broader than `deps`: an import spelled as a
**path**.  `HOL-MicroJava` reaches across its own subdirectories with `imports
../BV/Altern`, which discovery follows — those theories are in the index — but
the name-level rule could not map it, because its `.` rule finds the `.` of
`..`.  For the closure that is a HOLE, and a hole prunes: `callers rev` over
the distribution was 608 against 668 before the leaf rule.  `deps` printed a
cosmetic `[out-of-project]` line for the same token, because that is what the
0.7.0 reference printed — and upstream then fixed its own side in 0.8.1
(`[import-leaf]`), so the two resolvers are one rule again and `deps` /
`uses` / `refs` / `graph imports` resolve the path too.  `dev/p7cprobe.sh` §8
is the standing canary for the closure half.

The leaf rule has to hold on BOTH sides, and the other side is the same
divergence seen from the theory end.  A ROOT may address a theory in a
subdirectory by path — `"Locale_Test/Locale_Test"` (FOL),
`"LK/Propositional"` (Sequents), `"ex/Typechecking"` (CTT),
`"Simple/Reach"` (`HOL-UNITY`) — and both implementations then carry it under
that spelling.  Isabelle does not: `Thy_Header.import_name` takes the last
segment.  The reference's naming is reproduced here all the same, so only the
resolution is corrected — an import is matched against the theory ids PLUS the
leaf index, one entry per prefixed name.  Without it a sibling that imports
`"../Simple/Reach"`, or simply `Reach`, resolves to a leaf no known name
matches, and every attribution across that edge is pruned in silence: over
`src/HOL` that was one entry (`UNITY/WFair`'s `is`, reported dead and not
dead).  `dev/p7cprobe.sh` §8b is the fixture; `todo.md`'s
`[theory-name-leaf]` is the handle for the naming half, which is held back by
parity with the reference and nothing else.

**The compatibility mode.**  `--reach name` restores name-only attribution
exactly, on `callers`, `callees`, `refs`, `unused` and `graph` — every verb the
scoping moves — and both implementations spell it that way, with `closure` the
default on both.

It was `$ISABELLE_QUERY_REACHABILITY=off` from P7c to P9 S3, env-only on the
argument that a global moving a measurement gets one default AND one channel,
and that an argv flag would exist on only one of the four front doors.  The
second half of that was wrong: the thin client and the warm server forward argv
verbatim, so a flag reaches them without a second channel, and the plugin and a
library caller want the default anyway — which a parameter gives them with no
global to rebind.  P9 S3 deleted the variable.  `dev/difftest.sh` correspondingly
stopped pinning the rewrite side: for four phases it exported `off` there and
nowhere else, because a differential matrix can measure a difference and not an
improvement; now both engines answer the same question by default and six
`*-reach-name` cases pin the compatibility mode by NAME instead.

## D14 — a BOUND name is a declaration too, for the visibility filter

**Cost: nothing on any gate corpus** — no corpus in the matrix declares a name
in one entry's bindings and mentions it from a theory that cannot see the
binder, so nothing is pinned for this and nothing needs to be.  It is one
refinement of D13's rule, in D13's own direction, and it is stated here because
it is the one place where the two implementations answer a `callers` question
differently on purpose.

D13 fixes the rule: a site in theory `T` may be attributed to a declaration in
theory `D` iff `D = T`, or `D` is in `T`'s transitive in-project `imports`
closure.  Everything then turns on what counts as *a declaration of the name*.
Upstream 0.8.1 consults ENTRIES only (`graph._Visibility.declared_in`, an entry
of any tag).  The port consults entries **and the names an entry BINDS** — a
datatype constructor, a `shows … and C:` conjunct, an introduction rule, a
`.simps` — because Isabelle binds those names in the theory that writes them
and a theory that does not import it cannot write them either.

```
X.thy:  theory X imports Main begin
          datatype colour = Bar | Baz
          locale rev = fixes r :: nat
        end
Z.thy:  theory Z imports X begin  lemma z_mentions_bar: "Bar = Bar" by simp  end
W.thy:  theory W imports Main begin  lemma w_mentions_bar: "Bar = Bar" by simp  end
```

`callers Bar`: upstream reports **2** — `Z:5` and `W:4` — because no ENTRY is
named `Bar`, so `declared_in["Bar"]` is empty and the filter declines to
constrain anything.  The port reports **1**, dropping `W:4`: `Bar` IS declared
here, by `datatype colour` in `X`, and `W` imports only `Main`.  The `Bar` on
`W:4` is a constructor of something else, exactly as the `mono` on
`Mono_Bool_Tran:45` is a different `mono`.

**Why the port keeps it.**  The filter's licence is that it may only drop what
the citing theory positively cannot see, and a constructor bound in `X` is not
visible from a `W` that does not import `X`.  Dropping bindings from the
declared set would not make the port more correct, only more permissive in the
one case the rule was written for; and `codeqs Cons` — a rewrite-only verb with
no oracle — is precisely the question "where is this CONSTRUCTOR given a code
equation", which needs the binding to be a declaration or the verb has no
subject at all.  `instances` and `codeqs` therefore use the same rule, and
`callers` uses it too rather than carrying two answers to one question.

The cost is a `callers <constructor>` that can differ from the oracle's on a
corpus where a bound name is mentioned outside the binder's import cone.  It is
the plugin's commonest call (the word under the caret), which is the argument
for the port's reading rather than against it.

Everything else about the declared set now agrees: an entry of ANY tag counts
(a `comp` that is a TYPE, a `rev` that is a LOCALE), which is
`[citation-reach]`'s own rule and was the port's last gap in it.
