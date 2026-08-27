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
