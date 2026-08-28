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

**Cost: the `[cascade depth N]` marker on `unused -r`, on every corpus.  The
unused SET, the entry order, the header counts and every other `unused` form
agree exactly.**

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

The divergence is confined to the marker.  Verified on all five corpora the
oracle can run this on: strip `  [cascade depth N]` from both sides and the
outputs are byte-identical.  One pinned case, `unused-recursive`, on every
corpus that can run it at all (on FOL and ZF the D7 family pin gets there
first).

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
