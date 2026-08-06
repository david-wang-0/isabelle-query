# How `query` reads a project

What the tool considers to be a declaration, a citation, and a project — the
behaviour worth knowing before trusting a result. For the `shape` family see
[METRICS.md](METRICS.md); for the CLI surface see [README.md](README.md).

## Only live Isar text counts

A name inside a `(* ... *)` comment, a `\<comment>` note, a `\<^cancel>` region,
a `text` block or an `ML` body is **not** a citation, so it never invents a
caller or hides a dead lemma. A command word inside one is not a command, so a
commented-out `end` does not truncate the declaration above it. And a
*declaration* inside one is not a declaration, so a superseded `definition` left
behind in a comment — or an ML `fun`, which Isabelle and ML spell the same — is
not reported as an entry.

`grep --with-comments` shows the non-live matches too, marked as such.

The regions are found by a character-level scan rather than by line, because
none of this is line-oriented: comments nest, and a `(*` inside a `"..."` term —
HOL's multiplication section, `fold (*) xs` — opens nothing at all. Scans then
read the source with exactly those characters blanked, so a region sharing its
line with real proof text loses only itself. In `by (simp add: foo) (* not bar
*)`, `foo` is a citation and `bar` is not; and `using foo by simp \<comment>
\<open>note\<close>` keeps both the citation and the `simp` that `query methods`
counts.

## Layout carries no meaning

Isar is whitespace-insensitive, so a declaration is recognised wherever a
*command* can start — at any indentation and at any block depth, inside a
`locale`, a `context`, or a theory body its author simply chose to indent.
Declarations end at a real terminator (the next command, or an `end` / `context`
/ `lemmas` / `ML`), never at a blank line, which in Isar ends nothing.

## Methods and attributes vs fact names

Isabelle's method and attribute names overlap ordinary fact names — `insert`,
`trans`, `mono`, `cases` and even `finally` are all real Isabelle tokens *and*
real declared names. Where a project declares one, usage scans decide by
**position**: `using foo`, `by (rule foo)` and a mention inside a statement all
count as uses of the entry, while `by simp`, `auto simp: h` and `[symmetric]` are
the method or attribute of that name and count as nothing.

Which names are methods is decided by a table; see
[METRICS.md](METRICS.md#the-method-table-and-where-it-limits-the-numbers) for
where it comes from and where it is approximate.

## Locale scope

A declaration inside a `locale` / `class` / `context` / `instantiation` block
belongs to that target, and `enclosing` names it as a narrowing scope path:

```
HaltingProblems_K_aux:30 → K0 (DEF) — HaltingProblems_K_aux ▸ context hpk [src 28..32, ...]
```

Nothing is printed for a theory-level declaration, which is the common case:
31.4% of AFP entries have a target, 27.0% by lexical nesting and 4.5% by an
explicit `(in foo)` modifier. Where both are present and disagree, `(in foo)`
wins — it retargets the declaration, which is what Isabelle does.

Blocks are found structurally, not by indentation: every target block opens with
the token `begin` and closes with `end`, whichever command introduced it, so
there is one pair to track rather than a table of openers and closers.

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
