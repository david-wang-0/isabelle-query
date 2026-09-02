# Divergences from the Python oracle

Every place the Scala engine deliberately does NOT reproduce the Python
implementation, with the evidence that made it deliberate.  `PLAN.md` requires
this list to stay near-empty and every entry to have a reason better than "hard
to match".

**The oracle is upstream 0.8.1 as of P9** (`git merge v0.8.1`, `[p9-merge]`;
`src/isabelle_query/` and `tests/` are that release, frozen).  That matters for
reading this file, because most of what is written down here was *found* here
and has since been fixed **upstream**: D1, D3, D7 and D10 were fixed in 0.8.0
from this port's own findings (upstream ships them as
`scripts/probe_scala_port_findings.py`), and D2 and D6 closed on both sides
during P9.  Those entries stay, with their evidence and with the release that
closed them, because the evidence is what made the case; they are history, not
open differences.

## Measured at the close of P9 (2026-09-02)

Whole AFP, `afp-2025-2` (Isabelle2025-2 vintage), all four dump variants:

| | oracle 0.8.1 | this engine | differing |
|---|---:|---:|---:|
| theories | 10,262 | 10,262 | **0** |
| entries | 411,181 | 411,181 | **0** |
| entries `--spans` | 411,181 | 411,181 | **1** (D5) |
| entries `--bindings` | 411,181 | 411,181 | **0** |

Whole Isabelle distribution `src` — 1,818 theories, 101,388 records on each
side, **byte-identical in all four variants, 0 differing records**.  So is
each of the seven standard corpora (`Abstract_Completeness`, `AODV`,
`Category3`, `FOL`, `ZF`, `Sequents`, `CTT`): 28 of 28 dumps identical.

**One record differs over both corpora, and it is D5** —
`Substitutions_Lambda_Free:58`, whose `decl_end` the oracle puts at 67 and this
engine at 62.  Nothing is gained or lost on either side: the two engines now
report the same `theory:line:tag:name` set, where at P1 the oracle's was a
strict subset that missed 1,904 AFP and 509 distribution declarations.  D1
(1,867 records), D2 (751 + 16), D3 (37) and D4 (16) are all agreement now.

---

## D1 — `\<open>\\<close>`: a cartouche whose body is a backslash

**RESOLVED UPSTREAM in 0.8.0 (`[cartouche-escape]`, 5329089), from this port's
own finding — upstream ships the check as
`scripts/probe_scala_port_findings.py`.  The evidence below stays because it is
what made the case; the two implementations now agree on all 1,867 records
(verified at the close of P9: the whole-AFP entry dump is byte-identical, and
`ResiduatedTransitionSystem/LambdaCalculus` has the same 378 records on each
side).**

**Cost while it lasted: 1,867 records over 5 entries.** ResiduatedTransitionSystem (1,052),
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

**CLOSED ON BOTH SIDES during P9 (`[marker-decl]`).**  Upstream took the
declaration site in 0.8.0 (9ab103f, 141e3b6, 092981d) from this port's finding;
this port took the three halves it was still missing in P9 S2 — all four formal
comments redact in the LIVE view (909bf9c), the name grammar stops at a
structural token (5c21d76), and one heading recogniser skips markers (50a6e55).
The whole distribution `src` dump is byte-identical now, 509 declarations and
242 span records included, and so is the AFP's.

**Cost while it lasted: 751 records in the Isabelle distribution, 16 in the AFP.**  This is the
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

The residual this entry used to carry — a marker *inside* the name — is D6, and
it closed with the same tag on both sides.

## D3 — `keywords "cmd" :: "kind"` with a QUOTED kind

**RESOLVED UPSTREAM in 0.8.0 (`[keyword-kind-quoted]`, 53f0af9), from this
port's own finding.  The two implementations now read the same keyword table,
and the 37 records agree.  It had a second-order effect worth following: with
`alphabet` in the oracle's table too, the oracle now reproduces D4 — see
there.**

**Cost while it lasted: 37 records over 2 entries when each is read on its own** —
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

**NO LONGER A DIVERGENCE — it is a shared weakness now, and that is a
consequence of D3.**  P1 recorded this as a difference on the reasoning quoted
below: *"the oracle has the same union and only escapes the symptom because D3
keeps `alphabet` out of its table."*  0.8.0 fixed D3, so `alphabet` is in the
oracle's table too and the oracle mints the same 16 records.  Measured at the
close of P9: the whole-AFP entry dump is byte-identical, and
`Isabelle_C/C11-FrontEnd/appendices/C_Appendices:831:DEF:\<^verbatim>` — the
phantom whose name is read out of a `text` block — is line 203,558 of BOTH
dumps.  Upstream filed the defect as `[keyword-scope]`, which is where
`todo.md` carries it; the entry stays here because it is where the measurement
is written down.

**Cost: 16 records over 4 entries, and only when the whole AFP is passed as ONE
root.** Formula_Derivatives (4), MSO_Regex_Equivalence (3), UTP (3), Circus (6).
Each of those four is byte-identical when read as its own root.

Both implementations union every discovered theory header's `keywords` table
over the whole root, mirroring Isabelle's session-wide `Keywords.++`.  That is
right for one session and too coarse for a corpus of a thousand: with the AFP
`thys` directory as the root, Optics' `alphabet` command is in scope for
Formula_Derivatives, whose `sublocale DA < DAs` / `alphabet init delta ...`
continuation line then reads as a declaration.

This is the tool's existing design, not a change.  It is listed because a
whole-corpus sweep shows it.  Fixing it means scoping the table per session,
which changes the parse of every custom-command entry and belongs with the
session model, on both sides.

## D5 — `\<comment>` whose cartouche is on the next line

**OPEN, and as of P9 it is the ONLY differing record over either corpus.**
Upstream measured it and declined: `[comment-newline]` in `todo.md` reports one
occurrence in 11,514 theories against a change to `_scan_nonisar_spans`' state
machine, the highest-risk code in that package, and keeps the item on the list
as a *measured* decision rather than deleting it.  The port is right on the
record — Isabelle's `comment_prefix` allows the newline — and reproducing the
oracle here would mean charging five lines of English to a lemma's statement,
so it stays.

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

## D6 — a structural marker inside a name (shared weakness, since FIXED on both sides)

**CLOSED ON BOTH SIDES during P9 (`[marker-decl]`).**  Upstream split the
lexical atom from the NAME atom in 0.8.0 (9ab103f) so a name stops at a
structural token; this port took the same split in P9 S2 (5c21d76), leaving the
citation and `shape` tokenisers on the lexical atom.  All 7 AFP names come out
right on both sides —
`Differential_Privacy/…/Source_and_Sink_Algebras_Constructions:194:DEF:coprod_final_sink`
is the same record in both dumps — and the 3 records this entry warned would
move did move, on both sides together, so nothing about them is a difference.
The **related residual** below is agreement too: on
`First_Order_Terms/Term:37` both implementations now print `?`, and both still
miss `inj_on_Fun_fun`, which is the `goal` route's missing name-lookahead and
is nobody's divergence.

The evidence that made the case, as it stood at P1.  Both implementations built
a name out of Isabelle markup tokens
(`\<^sub>`, `\<^bold>` and friends are ordinary name characters), and neither
stopped at a *structural* one, so

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

Related residual, and it closed with the rest: where a marker's cartouche fills
the whole declaration line
(`lemma \<^marker>\<open>contributor \<open>...\<close>\<close>` with the name on the
next line, First_Order_Terms/Term:37) the Scala engine reported `?` and the
oracle the marker text.  Since 0.8.0 the oracle reports `?` as well.
Both still miss `inj_on_Fun_fun`; recovering it needs the
`goal` route to take the name-lookahead the `def` and `typedecl` routes already
take, which would rename every `lemma`-alone-on-its-line declaration and so is
a change to make deliberately, with its own corpus diff.

## D7 — the oracle's line index crashes on a multi-name `axiomatization`

**RESOLVED UPSTREAM in 0.8.0 (`[span-ties]`, ec4f1c1), from this port's own
finding — upstream ships the check as
`scripts/probe_scala_port_findings.py`.  `_build_line_index` sorts by the two
integers only now, which is the rule `Usage_Graph` has always run, so the whole
usage family answers on FOL and ZF.  The 132 pins this entry carried are gone
from `dev/difftest-pins`: at oracle 0.8.1 every one of them AGREES, and a pin
that describes nothing is a stale pin, which fails the run by design.**

**Cost while it lasted: `grep`, `sorry` and most of the usage family did not run AT ALL in the
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

Reproducing the crash would have meant shipping a `TypeError` as a feature, so
this was a deliberate divergence.  132 difftest cases were pinned on it (66
each on FOL and ZF), the pins carrying the exit-status difference (1 vs 0) as
well as the stdout one; `[p9-merge]` deleted all 132 when the oracle stopped
raising.

Which P3 verbs SURVIVE is the useful part of the map, because it says exactly
what the line index is for: `deps`, `uses` and `graph imports` read the import
graph and never build one; `callers` without `-r` walks the source directly;
and every verb that resolves its subject first (`refs No_Such`) returns before
the graph exists.  Those run in the oracle on FOL and ZF, and they are clean.
It also cost the P3 gate two distribution corpora, replaced by `Sequents` and
`CTT` — the two the oracle can carry, verified by running it there first.

## D8 — a closed stdout: both tools say 0 below a threshold and 141 above it, and the thresholds differ

**REWRITTEN AT P9.  The three pins are unchanged and none of them is stale; the
PROSE was wrong twice, and both errors flattered this side.**  What this entry
used to claim — "the rewrite is 141 throughout" and "the oracle's 0 is the same
defect from the other side" — is not what either tool does.  Both tools exit
**0** when the whole answer fits the buffers between them and the reader, and
**141** when a write actually fails; they differ only in where that threshold
sits (~8 KB here, 64 KB there), and upstream 0.8.1 now documents the 0 as the
contract rather than as a defect (`[closed-stdout]`, 5b5d66b — a docs-only
commit).  The measurements below stand; the reading of them is corrected at the
end of the entry.

**Cost: 3 difftest cases on the standard corpora (`closed-stdout` on
`Abstract_Completeness`, `shape-census-pipe` on `Abstract_Completeness` and
`CTT`), plus three on the demo — where `Demo_Extras`'s `find … -a -V` is
12,739 bytes, over this engine's 8 KB and under the reference's 64 KB, which is
the threshold difference in one case.**

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

Five runs each, stable.  Pinned as `shape-census-pipe` on the two corpora under
64K; a pin on the other five would be stale.

### What P9 corrects, and what is left

Every row of the P4 table above still holds at oracle 0.8.1, and the three pins
are what say so: `shape-census-pipe` differs on `Abstract_Completeness` and
`CTT` and on no other corpus, `closed-stdout` differs on
`Abstract_Completeness` alone, and the difftest reports **3 pinned, 0 stale**,
so none of them has rotted.

**The 120 is still a defect, and it is still the oracle's** — which is worth
saying plainly, because upstream's own
`scripts/probe_scala_port_findings.py` files D8 as *"NOT ours — the contract
was wrong, not the code"*.  Half of that is right (the 0) and half is not.
`closed-stdout Abstract_Completeness` (`find . . . . -a -V | head -3`) is
unchanged at 0.8.1:
the answer is over the pipe buffer, so a write does fail, but the first one to
fail is the interpreter's shutdown flush — outside the `except
BrokenPipeError` around the command body — and Python exits 120 after printing
`Exception ignored while flushing sys.stdout` to stderr.  Upstream's own
`tests/test_closed_stdout.py` asserts that noise "does not occur", and it does
occur; their case is a census on a large corpus, which fails inside the body.
That pin is earned.

**The 0 is NOT a defect, on either side, and this entry used to say it was.**
0.8.1's README now states it: *"`141` is not promised for every `| head`.  When
the whole answer fits the pipe buffer no write ever fails and the status is
`0` — the same as `seq 10 | head`, where `seq 200000 | head` dies of SIGPIPE.
The producer wrote everything; the reader chose to stop."*  That is the honest
description of the mechanism, and it applies here too.

**Because this engine is not 141 throughout.**  It writes through a
`BufferedWriter` over an `OutputStreamWriter` on the raw descriptor
(`query_base/src/output.scala`), and the encoder under that writer holds 8,192
bytes — so a whole answer under 8 KB leaves the process in ONE `write(2)`, that
write lands in a pipe buffer nothing has closed yet, and the run exits **0**.
An answer over it is split across several writes, and between two of them the
reader has been woken, printed its lines and gone: the second write is EPIPE
and the run is 141.  Measured on `Abstract_Completeness`, `| head -1`, five
runs each and identical every time:

| argv | bytes | this engine |
|---|---:|---|
| `summary -c` | 113 | **0** |
| `theory Abstract_Completeness --names` | 4,139 | **0** |
| `theory Abstract_Completeness` | 5,913 | **0** |
| `largest -N 82` (the whole corpus) | 7,806 | **0** |
| `find . -a` | 20,052 | 141 |

So the shape is the oracle's shape, with the threshold at the writer's encoder
buffer instead of at the pipe's.

What is left of D8 is one defect and one difference:

- the oracle's 120-with-stderr-noise, which is a real defect and keeps its
  `closed-stdout Abstract_Completeness` pin;
- **a documented difference in THRESHOLD** — 8 KB here against 64 KB there —
  which is what the two `shape-census-pipe` pins measure.  Neither number is
  promised to a caller, and neither could be: they belong to a JVM writer's
  encoder and to a Linux pipe.  `README.md` and `CONTRIBUTING.md` therefore say
  `141` means *a write failed because a downstream reader closed the pipe*, and
  say that `141` is not promised for every `| head` — true of both tools, and
  committing neither to a size.

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
`ISABELLE_QUERY_NAMESPACE=committed` and a whole-AFP `callers mono` agrees.  At
P4 that agreement was 1,363 output lines with **4 differing lines, all of them
D1** — the two `Hiding` hits whose owning entry the oracle's unterminated
cartouche had swallowed.  Re-taken at the close of P9 against oracle 0.8.1,
which has D1's fix: `callers mono --reach name` is **1,365 lines on each side
and byte-identical**, 0 differing.  The residual was D1's, and D1 is closed.

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

(The pair as first measured, at P7c, when 795 of the 1,361 hits were
impossible.  Both figures moved during P9, with the parser and with
`[symbol-body-tokens]` / `[import-leaf]` / `[citation-reach]`; **re-taken at
the close of P9 on the same corpus and the same pin, the pair is 1,363 →
634**, so 729 of the hits a name-only scan reports are in theories whose whole
import closure declares no `mono`.  Two more corpus-scale figures from the
same run, because they are what the rule is FOR: the citation graph goes
2,291,456 edges → 1,355,188, a 41% cut, and `unused -c` goes 97,568 → 101,154
— it **grows** by 3,586 entries, each of them kept alive until now by a
citation its citer could never have meant.  Every figure here is under the
committed-namespace pin the harness runs with; under the unpinned default the
`callers` pair was 2,437 → 1,311 at P7c — a different baseline, the same
pruning, the same sites.)  The shape of every one of them:

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
`"Simple/Reach"` (`HOL-UNITY`) — and at P7c both implementations carried it
under that spelling.  Isabelle does not: `Thy_Header.import_name` takes the
last segment.  Only the RESOLUTION was corrected then — an import was matched
against the theory ids PLUS a leaf index, one entry per prefixed name — because
correcting the NAME meant leaving byte parity with the reference.  Without the
resolution half a sibling that imports `"../Simple/Reach"`, or simply `Reach`,
resolves to a leaf no known name matches, and every attribution across that
edge is pruned in silence: over `src/HOL` that was one entry (`UNITY/WFair`'s
`is`, reported dead and not dead).  `dev/p7cprobe.sh` §8b is the fixture.

The naming half closed in P10 (`[p10-theory-leaf]`) and is **D15**: the theory
is now called `Reach`, so no loaded name carries a directory and the leaf index
is an identity — the four spellings `Reach.import_candidates` maps are down to
the three that concern the IMPORT token, which is where the rule always
belonged.

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

---

## D15 — a theory a ROOT addresses by PATH is named by its LEAF

**Cost: 178 of `dev/difftest.sh`'s 2,149 cases, on the three gate corpora whose
ROOT addresses a theory by path (FOL 33, Sequents 47, CTT 98) and on no other;
0 records on `dev/entrydiff.sh`, whose dumps key by path.  Every case is pinned
below, by id.**  It is the only deliberate difference in oracle-shared STDOUT
that this port carries by choice rather than by defect, and it is here because
Isabelle's own name for a theory is not the reference's.

### What Isabelle calls it

A session ROOT may address a theory in a subdirectory by path — the grammar
has no per-theory `in` clause, so `theories "LK/Propositional"` is how it is
done, and the distribution does it in `FOL`, `Sequents`, `CTT`, `HOL-UNITY`
and a dozen more.  The declared string RESOLVES the file; it does not NAME the
theory:

```scala
// $ISABELLE_HOME/src/Pure/Build/sessions.scala:650  (the illegal-name check)
val thy_name = Thy_Header.import_name(thy)

// $ISABELLE_HOME/src/Pure/Build/sessions.scala:658  (global_theories)
val thy_name = Path.explode(thy).file_name

// $ISABELLE_HOME/src/Pure/Thy/thy_header.scala:78-82
def import_name(s: String): String =
  Url.get_base_name(s) match {
    case Some(name) if !File.is_thy(name) => name
    case _ => error("Malformed theory import: " + quote(s))
  }

// $ISABELLE_HOME/src/Pure/General/url.scala:117,122-125
private val separators2 = ":/\\"
def get_base_name(s: String, suffix: String = ""): Option[String] = {
  val i = s.lastIndexWhere(separators2.contains)
  if (i + 1 >= s.length) None else Library.try_unsuffix(suffix, s.substring(i + 1))
}
```

So the theory `theories "LK/Propositional"` declares is called
`Propositional`, `:` and a backslash separate as `/` does, and a `.` does not
(a session-qualified `HOL.List` is one name, not two).  Isabelle raises an
error in the two degenerate cases — a string ending in a separator, and a base
name still ending in `.thy`; a query tool has nothing to gain by refusing to
answer, so `Discovery.import_name` keeps the declared string in those two and
resolution is unchanged.

### What the oracle calls it

The declared string, verbatim.  Upstream 0.8.1 reads its layout through
`isabelle-layout` 0.2.2, whose `session_theories` appends `entry[0]` — the
ROOT's own spelling — for a declared theory and `q.stem` — the leaf — for one
reached through the import closure (`isabelle_layout/theories.py:326` against
`:339`).  The two halves of one function disagree, and the ROOT half is the
one that disagrees with `isabelle build`.  This engine had the same split and
now offers `Discovery.import_name(name)` on the ROOT side, which is what
`theory_stem` was already doing on the closure side.

### Before and after

```
$ isabelle query -R $QUERY_TEST_DISTRO/CTT summary
| Theory          | Src | D | L | T | Key Exports          |     ORACLE 0.8.1
| CTT             | 945 | 12 | 55 | 0 | Arrow, Times, ...   |
| ex/Typechecking |  80 |  0 |  1 | 0 |                     |     <-- moves
| ex/Elimination  | 227 |  0 |  2 | 0 | Axiom_of_Choice     |     <-- moves
| ex/Equality     |  67 |  0 |  8 | 0 | split_eq, when_eq   |     <-- moves
| ex/Synthesis    | 104 |  0 |  0 | 0 |                     |     <-- moves

| CTT             | 945 | 12 | 55 | 0 | Arrow, Times, ...   |     THIS ENGINE
| Typechecking    |  80 |  0 |  1 | 0 |                     |
| Elimination     | 227 |  0 |  2 | 0 | Axiom_of_Choice     |
| Equality        |  67 |  0 |  8 | 0 | split_eq, when_eq   |
| Synthesis       | 104 |  0 |  0 | 0 |                     |
```

The name the oracle prints is one no Isabelle command answers to, and the tool
disagrees with its own output: `theory ex/Typechecking` works on the oracle
because the string happens to be the name, and `theory Typechecking` — the
name `isabelle build`, `isabelle jedit` and every `imports` clause use — does
not.  Here both work: the leaf is the NAME and `ex/Typechecking` is the LABEL,
which is the same tuple matched as a suffix, so the round trip closes from
either end.

### Upstream defect 1 closes on this side with it

`dev/P9-STATUS.md` §"Upstream defects found", item 1: the label tuple is the
resolved parent's path components plus the DECLARED name, so a colliding
path-spelled theory labels with its directory TWICE and no verb takes the
label back.  On a two-root fixture — `one/ex/Foo.thy` and `two/ex/Foo.thy`,
each declared `theories "ex/Foo"`:

```
$ query -R FIX largest                              ORACLE 0.8.1
     2  LEMMA     two_pad          two/ex/ex/Foo  (4..5)
     1  LEMMA     one_side         one/ex/ex/Foo  (4..4)
     1  LEMMA     two_side         two/ex/ex/Foo  (6..6)
$ query -R FIX enclosing one/ex/ex/Foo:4
one/ex/ex/Foo:4: no such theory 'one/ex/ex/Foo' (did you mean .../two/ex/Foo.thy?)

$ isabelle query -R FIX largest                     THIS ENGINE
     2  LEMMA     two_pad          two/ex/Foo  (4..5)
     1  LEMMA     one_side         one/ex/Foo  (4..4)
     1  LEMMA     two_side         two/ex/Foo  (6..6)
$ isabelle query -R FIX enclosing one/ex/Foo:4
one/ex/Foo:4 → one_side (LEMMA) — one/ex/Foo [src 4..4, 1 lines]  (in proof)
```

The label arithmetic did not change: with the NAME on the end of the tuple
instead of the declared string, depth 1 is `Foo` twice, depth 2 `ex/Foo`
twice, and depth 3 settles at `one/ex/Foo` / `two/ex/Foo` — the directory
once, and a label whose `name_parts` match a tuple suffix, which is what makes
it valid input.  The fixture is `dev/p9probe.sh` §3c; §3 is the same shape
inside one root, and `dev/p7cprobe.sh` §8b is the import-resolution half.

### What did NOT change

Resolution: the declared string still names the file, and every theory the
engine loaded before it loads now.  `dev/entrydiff.sh`'s four dumps key every
record by PATH (`Query_Tool.theory_key`), not by theory name — deliberately,
`[name-is-not-identity]` — so the entry set and the theory set over the whole
AFP and the whole distribution `src` are unmoved by this, and were verified so.
`Reach`'s import resolution is untouched: a header may still write
`imports "ex/Foo"` for a theory called `Foo`, and `Reach.import_candidates`
maps that spelling by its leaf as it always did.

### The pinned cases

**178 of the 2,149 matrix cases, and not one on a corpus without a
path-declared theory.**  The other four gate corpora (`Abstract_Completeness`,
`AODV`, `Category3`, `ZF`) and both demo corpora are byte-identical, as they
were.  Each case is pinned by its own id in `dev/difftest-pins` — no family
glob, because not one command family moves whole: `grep-anchored` moves on FOL
and `grep-count` does not, since only the first prints a locus.

**FOL** — 33 cases; the ROOT declares `"Locale_Test/Locale_Test"`.

- **the theory name** — substituting leaf for declared string in the ORACLE's stdout gives this engine's, byte for byte (25):
  `graph-imports`, `graph-imports-dot`, `shape-census`, `shape-census-resume`, `shape-census-resume-garbage`, `shape-census-resume-missing`, `shape-root-after-view`, `shape-root-before-view`, `shape-root-equals-view`, `shape-root-glued-group`, `shape-steps-json-all`, `shape-summary`, `shape-summary-content-code`, `shape-summary-content-prose`, `shape-summary-json`, `shape-summary-json-scope`, `shape-summary-scope-content`, `shape-summary-scope-entry`, `shape-summary-scope-equals`, `shape-summary-scope-proof`, `summary-by-session-v`, `summary-default`, `uses-batch`, `uses-last`, `uses-recursive`

- **the locus column width** — `grep` / `methods` / `shape steps` size the locus column to the widest locus, and the widest got shorter; identical after collapsing runs of spaces (8):
  `grep-alternation`, `grep-anchored`, `grep-cartouche`, `methods-alias`, `methods-named`, `methods-named-names`, `shape-steps-all`, `shape-steps-all-long`

**Sequents** — 47 cases; the ROOT declares `"LK/Propositional"`, `"LK/Quantifiers"`, `"LK/Hard_Quantifiers"`, `"LK/Nat"`.

- **the theory name** — substituting leaf for declared string in the ORACLE's stdout gives this engine's, byte for byte (37):
  `graph-citation`, `graph-default`, `graph-drop0`, `graph-reach-name`, `largest-default`, `methods-least-used`, `shape-census`, `shape-census-resume`, `shape-census-resume-garbage`, `shape-census-resume-missing`, `shape-root-after-view`, `shape-root-before-view`, `shape-root-equals-view`, `shape-root-glued-group`, `shape-steps-json-all`, `shape-summary`, `shape-summary-content-code`, `shape-summary-content-prose`, `shape-summary-json`, `shape-summary-json-scope`, `shape-summary-scope-content`, `shape-summary-scope-entry`, `shape-summary-scope-equals`, `shape-summary-scope-proof`, `summary-by-session-v`, `summary-default`, `unused-default`, `unused-drop2`, `unused-keep`, `unused-keep-list`, `unused-keep-repeat`, `unused-keep-unknown`, `unused-recursive`, `unused-roots`, `uses-batch`, `uses-default`, `uses-recursive`

- **the locus column width** — `grep` / `methods` / `shape steps` size the locus column to the widest locus, and the widest got shorter; identical after collapsing runs of spaces (8):
  `grep-alternation`, `grep-anchored`, `grep-cartouche`, `shape-steps-all`, `shape-steps-all-long`, `unused-by-theory`, `unused-by-theory-abbrev`, `unused-recursive-by-theory`

- **the name-sorted order** — `graph imports` sorts nodes and edges by name; identical as sets of lines (2):
  `graph-imports`, `graph-imports-dot`

**CTT** — 98 cases; the ROOT declares `"ex/Typechecking"`, `"ex/Elimination"`, `"ex/Equality"`, `"ex/Synthesis"`.

- **the theory name** — substituting leaf for declared string in the ORACLE's stdout gives this engine's, byte for byte (72):
  `deps-batch`, `deps-last`, `enclosing-alias-at`, `enclosing-batch`, `enclosing-blocks`, `enclosing-entry`, `enclosing-mid`, `enclosing-open-lo`, `enclosing-open-range`, `enclosing-range`, `enclosing-rg-marker`, `find-batch`, `graph-citation`, `graph-default`, `graph-drop0`, `graph-imports`, `graph-imports-dot`, `graph-reach-name`, `largest-default`, `largest-top-glued`, `largest-top-long`, `largest-top3`, `methods-least-used`, `outline-last`, `refs-batch`, `refs-last`, `shape-census`, `shape-census-resume-garbage`, `shape-census-resume-missing`, `shape-lemma`, `shape-lemma-batch`, `shape-lemma-config`, `shape-lemma-json`, `shape-lemma-substring`, `shape-root-after-view`, `shape-root-before-view`, `shape-root-equals-view`, `shape-root-glued-group`, `shape-steps-config`, `shape-steps-config-corpus`, `shape-steps-config-equals`, `shape-steps-config-multi-pick`, `shape-steps-config-multi-pick2`, `shape-steps-json`, `shape-steps-json-all`, `shape-steps-locus-json`, `shape-summary`, `shape-summary-content-code`, `shape-summary-content-prose`, `shape-summary-json`, `shape-summary-json-scope`, `shape-summary-scope-content`, `shape-summary-scope-entry`, `shape-summary-scope-equals`, `shape-summary-scope-proof`, `shape-widest-json`, `show-batch`, `summary-by-session-v`, `summary-default`, `theory-last`, `unused-default`, `unused-drop2`, `unused-keep`, `unused-keep-list`, `unused-keep-repeat`, `unused-keep-unknown`, `unused-recursive`, `unused-roots`, `uses-batch`, `uses-default`, `uses-last`, `uses-recursive`

- **the locus column width** — `grep` / `methods` / `shape steps` size the locus column to the widest locus, and the widest got shorter; identical after collapsing runs of spaces (25):
  `grep-alternation`, `grep-anchored`, `grep-cartouche`, `methods-alias`, `methods-named`, `methods-named-names`, `shape-steps`, `shape-steps-all`, `shape-steps-all-long`, `shape-steps-config-plain`, `shape-steps-locus`, `shape-steps-open-hi`, `shape-widest`, `shape-widest-fanin`, `shape-widest-huge`, `shape-widest-live`, `shape-widest-metric-equals`, `shape-widest-n-glued`, `shape-widest-n3`, `shape-widest-top-long`, `shape-widest-w1`, `shape-widest-w2`, `unused-by-theory`, `unused-by-theory-abbrev`, `unused-recursive-by-theory`

- **an oracle-written resume prefix** — `shape census --resume` skips records keyed by (session, theory), and `dev/difftest.sh` derives the prefix from the ORACLE, so this engine re-emits the eleven CTT records its keys name.  A prefix this engine wrote resumes to nothing (1):
  `shape-census-resume`

