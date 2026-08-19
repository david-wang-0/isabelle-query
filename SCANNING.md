# How `query` reads a project

What the tool considers to be a declaration, a citation, and a project — the
behaviour worth knowing before trusting a result. For the `shape` family see
[METRICS.md](METRICS.md); for the CLI surface see [README.md](README.md).

## Only live Isar text counts

A name inside a `(* ... *)` comment, a `\<comment>` note, a `\<^cancel>` region,
an `ML` body, a document block (`text`, `text_raw`, and the in-proof `txt`) or a
section **heading** is **not** a citation, so it never invents a caller or hides
a dead lemma. A command word inside one is not a command, so a commented-out
`end` does not truncate the declaration above it. And a *declaration* inside one
is not a declaration, so a superseded `definition` left behind in a comment — or
an ML `fun`, which Isabelle and ML spell the same — is not reported as an entry.

`grep --with-comments` shows the non-live matches too, marked as such.

Two different mechanisms, and the difference is why the list above is worth
spelling out rather than summarising as "comments":

**Lexical regions** are found by a character-level scan rather than by line,
because none of this is line-oriented: comments nest, and a `(*` inside a `"..."`
term — HOL's multiplication section, `fold (*) xs` — opens nothing at all. Scans
then read the source with exactly those characters blanked, so a region sharing
its line with real proof text loses only itself. In `by (simp add: foo) (* not bar
*)`, `foo` is a citation and `bar` is not; and `using foo by simp \<comment>
\<open>note\<close>` keeps both the citation and the `simp` that `query methods`
counts.

**Command-introduced prose** — a document block or a heading — is not lexical: it
is prose because of the command in front of it, so it is masked whole lines at a
time instead. Nothing distinguishes its cartouche from a term's, so there is no
character-level rule to find it; the *command word* is the only evidence. This is
why the set of recognised commands is load-bearing. When `txt` was missing from
it, the English of 542 AFP blocks was read as Isar; when headings were missing,
`section \<open>Consequences proved using helper\<close>` cited `helper`, so a
lemma named only in a section title looked used and dropped out of `unused`.

Which commands those are is read from Isabelle's own keyword table rather than
listed from memory — so the headings are all six of `chapter`, `section`,
`subsection`, `subsubsection`, `paragraph` and `subparagraph`, and a command a
particular session defines for itself (Isabelle_DOF's `section*[l::t]`) is not
one. A heading's title has three spellings, all of them written: the two
cartouches and a plain quoted string, `section "Preliminary lemmas"`.

The converse also follows from "prose because of the command in front of it": a
heading keyword *inside* a document block is English, not a command. A `text`
block citing a textbook — "…follows Kleinberg and Tardos, chapter \"Dynamic
Programming\"" — declares no chapter, and `outline` does not list one.

## Layout carries no meaning

Isar is whitespace-insensitive, so a declaration is recognised wherever a
*command* can start — at any indentation and at any block depth, inside a
`locale`, a `context`, or a theory body its author simply chose to indent.
Declarations end at a real terminator: the next command, or an `end` /
`context` / `lemmas` / `ML`.

A blank line ends nothing structural, and a rule or equation list spaced out for
legibility is still one declaration — a line beginning `|` cannot start a new
command, so it continues the one above, across blank lines and `(* ... *)` notes
alike. What stops the scan is outer syntax: a `text` block between two rules
does end the declaration, because it is a command.

## The names one declaration binds

Isabelle binds more than one name per command, and each of them is citable:

| written | also binds |
|---|---|
| `inductive p where r1: "..." \| r2: "..."` | the rules `r1`, `r2` |
| `fun f and g and h where ...` | the constants `g`, `h` |
| `definition F where eq_fold: "..."` | the equation `eq_fold` |
| `datatype t = disc: A (sel: ty) \| B` | `A`, `B`, `disc`, `sel` |
| `record r = x :: ty` *(one per line)* | the selectors `x` |
| `locale L = assumes a: "P"` | the assumption `a` |
| `lemma l: shows x: "P" and y: "Q"` | the conjuncts `x`, `y` |

`show`, `find`, `callers` and `callees` all resolve these to the declaration
that binds them, and say how — `'termi_z' is an introduction rule of terminate`,
`'ip' is a field of state`. They are deliberately **not** separate entries: one
command has one span, so counting `fun f and g and h` three times would
triple-count it under `largest` and give `enclosing` three owners for each of
its lines.

This matters for precision as much as recall. A name the tool cannot find has no
declaration site to exclude, so its own definition reads as a citation of
itself: `callers termi_z` used to report three callers, two of which were the
`| termi_z: "..."` lines declaring it.

A `locale` and a `class` *are* entries — each declares a name — spanning the
head only, up to but not including `begin`. `context` and `interpretation` are
not: they reopen or instantiate an existing target rather than declare one.

## Methods and attributes vs fact names

Isabelle's method and attribute names overlap ordinary fact names — `insert`,
`trans`, `mono`, `cases` and even `finally` are all real Isabelle tokens *and*
real declared names. Where a project declares one, usage scans decide by
**position**: `using foo`, `by (rule foo)` and a mention inside a statement all
count as uses of the entry, while `by simp`, `auto simp: h` and `[symmetric]` are
the method or attribute of that name and count as nothing.

Position decides on its own where it can. The token straight after
`by` / `apply` / `proof` is the method whatever it is called, so `query methods`
and the shape metrics need no table there — which is what lets them see a tactic
an entry defines for itself (`by auto2`, `by (cs_concl ...)`), where a fixed table
never could. Everywhere else — a bare argument, a name in a statement — position
is not enough, and a table decides; see
[METRICS.md](METRICS.md#the-method-table-and-what-it-still-decides) for
where it comes from and where it is approximate. A narrow table is the safe
direction there: an unlisted method may add a spurious citation, never remove a
true one.

## Locale scope

A declaration inside a `locale` / `class` / `context` / `instantiation` block
belongs to that target, and `enclosing` names it as a narrowing scope path:

```
HaltingProblems_K_aux:30 → K0 (DEF) — HaltingProblems_K_aux ▸ context hpk [src 28..32, ...]
```

Nothing is printed for a theory-level declaration, which is the common case:
30.9% of AFP entries have a target, 26.5% by lexical nesting and 4.4% by an
explicit `(in foo)` modifier. Where both are present and disagree, `(in foo)`
wins — it retargets the declaration, which is what Isabelle does.

Blocks are found structurally, not by indentation: every target block opens with
the token `begin` and closes with `end`, whichever command introduced it, so
there is one pair to track rather than a table of openers and closers.

A target's **name** is spelled like any other Isabelle name, and both awkward
spellings occur: it may contain markup symbols (`locale \<Z>`,
`locale split\<^sub>i_tree`, `instantiation \<o> :: AOT_subst`) and it may be
written as a quoted identifier when the word would otherwise be reserved
(`locale "functor" =`, `instantiation "pseqp" :: ord`). It may also be
qualified (`context Rings.dvd`), which an ordinary entry name may not. Quoted
names are read from the live view rather than the outer one — outer blanks
inner syntax, which is exactly where the quotes put the name — while the
command keyword is still matched at outer-syntax position.

An opener that carries no name is left unnamed rather than guessed at: `context`
alone opens an anonymous context whose elements follow on later lines, and 430
of the 1,247 `context` blocks over 120 AFP entries are of that kind.

## What counts as the project

The tool reads one Isabelle **session directory** (a directory containing a
`ROOT` file). Run `query` from inside a project and it finds the session
automatically. For a tree with several sessions in sibling subdirectories, name
the session directory (relative to the project root) in a one-line
`.isabelle-query` marker file at the root, or pass `-R/--root <dir>` / set
`$ISABELLE_QUERY_ROOT`.

Discovery loads what the build **compiles**: each session's ROOT-declared
theories *plus the transitive closure of their in-entry `imports`* (bare,
self-qualified, or relative-path). An entry that declares a few leaf theories and
pulls the rest in via `imports` — common in the AFP, where `AODV` declares 1 and
builds 73 — is therefore loaded in full. Imports of *other* entries and of the
Isabelle base library (`HOL-*`, `Pure`) are not followed, and orphan `.thy` files
that no declared root imports are excluded: exactly the set `isabelle build`
would process.

The call graph behind the usage scans is constructed only when needed, so most
commands stay fast.

## Aggregating across a corpus

`summary --by-session` rolls the per-theory counts up to the **session** and
**corpus** level — one row per session plus a grand total — so it is useful
against a whole corpus (`query -R AFP/thys summary --by-session`), an entry with
several sessions, or a single session, not just one theory at a time. `-v`
expands each session to its theories; `-c` prints only the grand totals (entries
/ source lines / theories / sessions). Line totals match `wc -l` over the same
build-referenced file set.

## The prose view

`show <name> --comments-only` prints what the author *wrote about* an entry
rather than the entry: its leading `text` preamble, plus every `\<comment>` note
inside its span, grouped by which part of the entry each one annotates.

```
--- annotations (\<comment>) ---
  statement:
    | line 102: For a sound system \<open>\<Sigma>\<close>
    | line 109: We have that \<open>f(\<alpha>s)\<close> is applicable
  proof:
    | line 202: the induction is on the plan, not the state
```

The grouping is the content: a note on the statement says what is being claimed,
one in the proof says how it is reached. `definition`s have no proof and so only
ever have the first kind — which is exactly where a definition's construction
gets narrated, round by round in a `do { ... }` body. `find --with-comments`
searches all of it, and reports which part each hit is in.
