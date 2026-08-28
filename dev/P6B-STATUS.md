# P6b — find instantiations, find code equations: status

`PLAN.md`'s P6b: the first two capabilities in this fork with **no Python
counterpart**. There is no oracle, so they are excluded from the differential
matrix by construction and verified against hand-computed fixtures instead
(`dev/p6bprobe.sh`), per the repo's correctness doctrine.

Two CLI verbs, two engine entry points, two `Result_Kind`s in the jEdit panel,
two context-menu items and two actions.

## The scope line, stated where a user reads it

These verbs report **declared source sites**. Isar's `print_codesetup`,
`code_thms` and `print_interps` need a running prover and report the
**processed** setup — after the code generator's preprocessing, after
`[code del]` has taken effect, and including everything an imported session
declared. This is the complement of that, and each sees what the other cannot:
a static scan sees the `[code del]` *site* that a processed view has already
folded away, and cannot see an equation that arrives from `HOL-Library`.

The line is in `query_base/src/sites.scala`'s header comment and in both verbs'
`-h` (on the subject positional, so it does not widen the top-level command
list).

## The verbs

| verb | subject | sites |
|---|---|---|
| `query instances NAME` | a **locale or class** the project declares | `instantiation` blocks, `instance` arities, `interpretation`, `global_interpretation`, `interpret`, `sublocale` |
| `query codeqs NAME` | a **constant** the project declares | the constant's own `definition` / `fun` (`default`), a declaration carrying a code attribute, a `declare` / `lemmas` that attaches one, a `[[code drop:]]` |

Both are lookup-family (`CONTRIBUTING.md`): a subject list, corpus-global, no
trailing `PATH` positionals, `-R` on either side of the verb, `-c/--count` and
`--names`. `--names` prints bare `THEORY:LINE` loci, one per line — for a
*site* list the identity of a hit is its locus, and that is the tool's own span
grammar, so the output pipes straight into `enclosing` / `lines`. The probe
checks that round trip mechanically.

**Naming.** The vocabulary is short plural nouns (`deps`, `refs`, `defs`,
`callers`, `callees`, `methods`). `instances` reads as the English of the
question and its overlap with Isabelle's `instance` command is a feature, since
`instance T :: C` is one of the site kinds; `interps` (after `print_interps`)
was rejected for naming only one of the four commands. `codeqs` contracts "code
equations"; `code` alone would fence the whole `code_printing` / `code_reflect`
family off from any later verb, and `code_thms` both carries an underscore no
verb here has and names the Isar diagnostic this is explicitly *not*.

## Semantics decisions

Every one of these could have gone the other way. The grammars are quoted from
Isabelle's own rails in `Doc/Isar_Ref/Spec.thy`, not recalled.

### Instantiations

1. **`sublocale L ⊆ M` is a site of M, not of L.** `L` is where the
   interpretation is *installed*; `M` is what is interpreted. The older
   `sublocale L < M` spelling is still in the 2025-2 rail and is read too. This
   is the single case that separates the scan from a grep: on `Category3`, 38
   interpretation-family lines mention `category` and `instances category`
   reports **37** — the missing one is
   `sublocale category ⊆ identity_functor C ..`, which `instances
   identity_functor` reports instead. Both halves are pinned.

2. **Extensions are excluded.** `class D = C + …`, `locale K = L + …`,
   `subclass D`, and `instance C ⊆ D` extend a locale or relate two classes;
   they supply no type and no term. They already appear under `callers`, which
   is where "who mentions L" belongs. Including them would make "3
   instantiations of monoid" mean two different relations at once.

3. **An arity cites only its target sort.** `instantiation prod ::
   (exhaustive, exhaustive) exhaustive` instantiates `exhaustive` **once**; the
   sorts in parentheses constrain the arguments. A sort may be a brace list
   (`:: "{order_bot, order_top, linorder}"`) and is then several classes.

4. **`interpret` (a proof-body interpretation) counts**, marked as such.
   Excluding it would under-report, which for a "find all sites" verb is the
   worse failure; the `kind` column lets a reader filter.

5. **A bare `instance ..` inside an `instantiation` block is not a second
   site.** It has no `::`, and the block header already counted.

### Code equations

6. **The attribute set is `Pure/Isar/code.ML`'s, and stops there.** That file's
   single `Attrib.setup` for `code` accepts:

   | spelling | effect | reported as |
   |---|---|---|
   | `[code]` | add a (possibly abstract) equation | `[code]` |
   | `[code equation]` / `[code prepend]` / `[code nbe]` | add an equation | `[code …]` |
   | `[code abstract]` / `[code abstype]` | add an abstract equation / abstype certificate | `[code …]` |
   | `[code del]` | **retract** an equation | `[code del]` |
   | `[code drop: cs]` / `[code abort]` | drop / abort the implementation | `[code drop]`, `[code abort]` |

   Everything else spelled `code_*` is a **different store**: `code_unfold`,
   `code_post` and `code_abbrev` are `Attrib.setup`s in
   `Tools/Code/code_preproc.ML` — the code generator's *preprocessor* simpsets,
   which rewrite a term before equations are looked up and are not equations of
   any constant; `code_pred_intro` / `code_pred_inline` belong to the predicate
   compiler. Reporting them under "code equations of c" would answer a
   different question in the same words. The token boundary after `code` is
   what keeps `code_unfold` out.

   **Retractions are reported, marked.** A listing that showed the equations
   and hid the `[code del]` that is the reason one is not in force would be the
   more misleading of the two.

7. **The head rule, not "cited in the statement".** A code equation belongs to
   the constant at the **head of its left-hand side**. `lemma [code]: "thrice n
   = twice n + n"` is an equation of `thrice`; the cheaper rule would also
   report it under `twice`, and a verb that does that is useless on any
   constant appearing in a right-hand side. Implemented as: take the
   propositions after `shows` (the quoted terms and cartouches), drop premises
   up to the last top-level `\<Longrightarrow>`, take the left of the first
   top-level equality (`=`, `\<equiv>`, `==`, `\<longleftrightarrow>`), and
   collect the identifiers in **head position** there — the first token, and
   the first token after each `(`.

   The second half is what makes `[code abstract]` work: an abstract equation
   reads `Rep_T (f x) = …`, whose outermost head is the projection and whose
   subject is `f`. It over-reports by exactly one case, `f (g x) y = …`, which
   is the direction this repo's approximations lean (`SCANNING.md`: "an
   unlisted method may add a spurious citation, never remove a true one").

8. **`default` sites, and when they are suppressed.** `definition` and
   `fun`/`primrec`/`function` register default code equations with no attribute
   written; those are reported as `default` and are the sites a purely
   attribute-driven scan would miss entirely. `datatype` registers
   *constructors* (`code_datatype`), `inductive` needs an explicit `code_pred`,
   and an `abbreviation` is unfolded before code generation sees it — none of
   the three has default equations. A declaration that **carries** a code
   attribute does not also emit a `default` row: there is one equation there,
   and two rows on one line would say there are two.

9. **`declare` / `lemmas` sites attribute by fact name.** A cited fact denotes
   the constant when it *spells* it (`c`, `c.simps`, `c_def`, `c_defs`,
   `c_code`) or when it names an entry that is a lemma whose own equation head
   is the constant — which is how `declare card_set [code]` lands under `card`.
   For `lemmas name [code] = facts` **all** names in the command are
   considered, LHS and RHS: which of them carries the equation is a question
   about the theorem, and the site is real either way round.

### Both

10. **Live text only**, as everywhere: `(* … *)`, `\<comment>`, `\<^cancel>`
    and ML bodies are blanked, and a command word is recognised only where a
    *command can start* in the outer view — which is also what keeps a `text`
    block and a `section` heading from contributing a site, since both live
    inside a cartouche the outer view blanks.

11. **Structure is read on `outer`, names on `live`.** Both bugs the fixtures
    caught were the same mistake in reverse: in `outer` a quoted name **is**
    whitespace, so skipping whitespace there walks straight past the thing
    being read. The qualifier pattern's trailing `\s*` skipped over `"open"` in
    `interpretation q: "open" id` and read `id`; the arity scan skipped over
    `"{mynull, ord}"` entirely.

12. **Subject resolution scans every entry of the name, not the first.** `rev`
    is a `primrec` in `List` *and* a locale-local `LEMMA` in `Groups_List`;
    taking whichever came first turned a good subject into "is a LEMMA, not a
    constant". The right-kinded declaration wins; a wrong-kinded one is only
    what the diagnostic names.

## The exit-code contract

`CONTRIBUTING.md` fixes it, and here it had to be **argued** rather than copied,
because `callers` does not distinguish: `query callers zzz` prints "No callers
found for 'zzz'." and exits **0** whether or not `zzz` exists — and that is
right for it, since `find_callers` is a pure text scan that never needed the
name to be an entry. (Verified against the oracle: every lookup verb behaves
this way; the oracle's exit 1 is reserved for `resolve_file_source`, i.e. a
`PATH`/locus token that is neither a path nor a known theory.)

These two verbs are different. "Which sites instantiate L" is answerable only
when L is a locale or class **this project declares**: for a locale from an
imported session the scan would find some sites and silently miss the sublocale
chains reaching it, and a typo would look exactly like a locale nobody
instantiates. So:

| case | stdout | stderr | exit |
|---|---|---|---|
| known subject, sites found | the listing | — | 0 |
| known subject, **no** sites | `No instantiations found for 'X'.` | — | **0** |
| known subject, no sites, `-c` | `0` | — | 0 |
| unknown subject | *empty* | `ERROR: 'X' is not a locale or class declared in this project` | **1** |
| known subject of the wrong kind | *empty* | `ERROR: 'X' is a LEMMA in Thy, not a constant` | **1** |
| unknown subject, `-c` | *empty* — **never `0`** | the same | 1 |

The last row is the point: printing `0` for a question that could not be asked
is precisely the silent zero `CONTRIBUTING.md` is about. All seven rows are
pinned in `dev/p6bprobe.sh` §5.

A **bound name** (a datatype constructor, a `shows` conjunct) resolves through
`Commands.resolve_binding` and is reported with the family's `#` note line
(`# 'Node' is a constructor of mytree.`), exactly as `callers -r` does — which
means the note precedes a `-c` count, as it does there.

`Sites.resolve` is the one predicate: the CLI exits 1 on it, the panel refuses
on it, and the context menu asks it before offering an item.

## jEdit

P6's seam held exactly as `dev/P6-STATUS.md` predicted: a new kind is
`expand_groups`, the count caption, the leaf renderer, and a `Group`/`Hit`
producer. **No second tree**; the navigation, the five gesture policies, peek
and the back/forward history all arrive with `Hit`.

* `Result_Kind.Instantiations` / `Result_Kind.Code_Equations`, both
  **collapsed** by default like usages — `Category3`'s `category` has 37 sites.
* `Hit.tag` is the site's syntactic role (`sublocale`, `[code del]`), rendered
  in italics before the source line: the one thing a site row says that a
  usages row does not, and the same column the CLI prints. Italic, not a
  colour, for the reason already at `hit_html`.
* `count_caption` counts **sites**, not hits: "37 hits" describes a search, an
  instantiation is a place where something happens.
* `Result.refused` — a refusal is **not** an empty result set. The panel shows
  the sentence the CLI would exit 1 with.
* `Request` swaps `definition: Boolean` for the `Result_Kind` it produces.
* Context menu: *Find instantiations of X* / *Find code equations of X*,
  offered **only when the already-built index says the name is of the right
  kind**. Absent rather than disabled, which is what this menu already does
  (its contract is `null` for "nothing to contribute"). It reads a volatile
  snapshot and never parses, because it runs on the EDT for every right-click —
  so a **cold index offers neither item**. That is the one place the menu is
  less capable than the action.
* Actions `isabelle-project-query.find-instantiations` /
  `.find-code-equations`, on the plugin menu, deliberately **not** gated on the
  kind: from the keyboard there is nothing to look at that would explain a
  missing item, and the panel's refusal says more than a key that does nothing.
  **No default shortcuts** — three chords are already claimed and P6's
  watch-out #9 says they will meet a real keymap.

## What the probes prove

`dev/p6bprobe.sh` — **100 checks, green** (78 in the Scala layer, 22 in the
shell layer), plus the CLI-vs-panel cross-check and the failability
demonstration.

* The **grammars** pinned as pure functions, on the spellings a real corpus is
  full of: a quoted type constructor, a quoted brace sort, a quoted *argument*
  sort, a quoted qualifier (`"and":`), a `?` qualifier, symbol-bearing and
  qualified locale names, a compound `L1 + L2`, a `+` inside a term, the old
  `sublocale L < M`. Two of these were live bugs when first run.
* The **scans** over fixture theories the script writes, every count computed
  by hand from the source first — including the four decoys (`text` block,
  `(* … *)` note, `\<^cancel>` region, and a locale *extension*).
* The **seam**: both kinds' `expand_groups`, all four count captions, all four
  empty nouns, the tag reaching the rendered leaf, a refusal being
  distinguishable from an empty answer, and the menu predicate agreeing with
  the CLI's.
* The **resources**: both actions exist, have labels, ship no shortcut, are on
  the plugin menu, and their BeanShell bodies resolve to real methods.
* The **exit-code contract**, all seven rows.
* **Real corpora**: `Category3`'s `instances category` = the grep minus exactly
  `Functor:265`, and that same line reported under `identity_functor`;
  `src/HOL`'s `codeqs rev` finding `List:87` (the `primrec`) and `List:3249`
  (`rev_conv_fold [code]`) despite the `Groups_List` name collision.
* **Failability**: `P6BPROBE_FAILDEMO=1` perturbs two expectations by one and
  the script requires exactly two FAILs and a non-zero exit.

Re-run of the earlier gates, unchanged by this phase:

* `dev/p5probe.sh` — **64 checks + CLI parity, green** (P5's 62 plus the two
  new action targets it picks up automatically).
* `dev/p6probe.sh` — **104 checks + CLI parity, green** (P6's 103 plus one new
  non-vacuity guard, below).

### A vacuous check found in `dev/p6probe.sh`

`p6probe`'s "every menu entry is an action or the dockable" read the plugin
menu with `(?m)^key=(.*)$`. That property is written with trailing-backslash
continuations, so the read captured a lone `\`, the split produced an **empty**
list, and `forall` on nothing is true: for all of P6 the check could not fail.
Found only because the P6b probe copied the code and got an empty list for its
own new entries. Continuation lines are now joined, and a size guard sits in
front of both copies.

## Regression

Both gates re-run at the end of the phase, on the seven standard corpora:

* `dev/entrydiff.sh` — **28 of 28 checks green**, all four variants
  (`dump-theories`, `dump-entries`, `--spans`, `--bindings`) byte-identical.
  2 / 73 / 28 / 20 / 133 / 14 / 5 theories and 81 / 2,467 / 1,636 / 616 /
  7,336 / 406 / 82 entries — unchanged by P6b, as P4 left them.
* `dev/difftest.sh`, no arguments (the full matrix) — **2,086 cases, 1,946
  clean, 140 pinned, 0 failing, 0 stale pins**. Identical to P4's figures; the
  140 pins are P3's 138 (D7 × 132, D8 × 1, D10 × 5) plus P4's two census pins.

The two new verbs are **not** in the matrix and cannot be: there is nothing to
diff against. Nothing in the matrix moves because of them either — it has no
top-level invalid-choice case (the only place the new command names would
appear in oracle-comparable output), and stderr is compared for non-emptiness
only. `isabelle query -h` now lists two commands the oracle does not, which is
the divergence `PLAN.md`'s P6b authorises; help text is outside the byte
comparison by the parity contract, so it needs no `dev/DIVERGENCES.md` entry.

## Manual jEdit checklist (David)

Nothing below has been run; the plugin has still never been loaded by a jEdit.
P5's 13 and P6's items 14–31 still apply unchanged. New for P6b:

32. **The menu is kind-aware.** With the panel warm (run any query first),
    right-click a **locale** name: *Find instantiations of X* appears and
    *Find code equations of X* does not. Right-click a **constant**: the other
    way round. Right-click a **lemma** name: neither.
33. **A cold index offers neither.** Restart jEdit, open a theory, and
    right-click a locale name before any query: only the four P5/P6 items. Run
    Find usages once, then right-click again: the site item appears. (This is
    the documented degradation, not a bug — but if it feels wrong in practice,
    the fix is to show the items always and let the panel refuse.)
34. **Find instantiations.** On a locale with several interpretations: the set
    opens **collapsed**, the root reads `instantiations of X (LOCALE in Thy) —
    N sites in M theories`, and each leaf reads `LINE: <i>kind</i>  source`,
    the kind being `interpretation` / `sublocale` / `instantiation` / …. Click
    a leaf: it lands on that line.
35. **Find code equations.** On a `fun` with a `[code]` lemma somewhere: at
    least a `default` row at the declaration and a `[code]` row at the lemma.
    A `[code del]` site, if the project has one, is present and marked.
36. **A refusal is not an empty answer.** Put the caret on a *lemma* name and
    invoke the action `isabelle-project-query.find-instantiations` (Plugins →
    Isabelle Project Query, or bind a key): the caption must read
    `'X' is a LEMMA in Thy, not a locale or class — …`, and the tree must not
    gain an empty result set.
37. **An honest zero still is one.** Same action on a locale nothing
    instantiates: the caption reads `no instantiations of X — …`.
38. **The gestures are shared.** ALT+click a site row → peek popup;
    shift-click → new pane; double-click → active pane. Nothing about a site
    row should behave differently from a usages row.
39. **The keymap merge is unchanged.** Neither new action ships a shortcut, so
    the first start after this build must **not** raise a keymap dialog.

## Watch-outs for P7

1. **`Sites` needs no `Namespace` binding, and that is worth keeping.** Neither
   scan enters the citation router, so neither depends on the per-project
   method table. The panel still runs them inside `index.with_namespace` for
   one path; a warm server can run them on any thread without the binding
   `callers` needs. If a future refinement starts consulting
   `Namespace.non_citation`, that property is lost — say so explicitly.
2. **`find_code_equations` memoises `live_source` per theory.** `model.scala`
   makes the views `def`s on purpose (a cached view is a second copy of the
   corpus). The cross-theory fact-name resolution needs one theory's view at a
   time, so the cache holds only the theories a `declare` actually reaches
   into — a handful. Over a whole-AFP root that bound is not proved; a server
   that keeps the process alive should either scope the cache to the call or
   measure it.
3. **Mixfix notation defeats the head rule.** `lemma upto_code [code]: "[i..j]
   = upto_aux i j []"` is an equation of `upto`, written in `upto`'s own
   notation; the head rule reads no identifier at all there. This is
   irreducible without a parser that knows the project's `notation`
   declarations, and it under-reports — the one place these scans lean the
   unsafe way. A user who finds `codeqs c` short should be told to check
   `grep`.
4. **The corpus-global scan does not separate same-named constants.**
   `codeqs rev` over `src/HOL` reports `List.rev`, `Imperative_Reverse.rev` and
   `Linked_Lists.rev` together. `callers` has the same property and it is
   inherent to a name-based tool, but a site listing invites the reader to
   treat the rows as one constant's equations. If P7 grows a `--theory` scope
   for lookup verbs, these two want it first.
5. **A custom `thy_decl` keyword can mint a spurious `default` row.**
   `src/HOL/Data_Structures/Time_Functions.thy`'s `time_fun drop` is indexed as
   a `FUN` entry named `drop`, so `codeqs drop` reports a `default` site there.
   That is faithful to the entry index rather than to Isabelle, and it is an
   entry-recognition question (P1's), not a site question — but it is visible
   for the first time here.
6. **The context menu now reads the index snapshot on the EDT.** It is a
   volatile field read plus a linear scan over entries, per right-click. On a
   1450-theory project that scan is over ~50 000 entries twice. It has not been
   measured; if a right-click ever feels slow, `Sites.resolve` is where to add
   the map lookup `Query_Index.Snapshot.entry_by_name` already provides.
7. **Two more verbs on `query -h`.** P7 rewrites `README.md` / `CLAUDE.md` for
   the Scala tool; these two are the first entries in those docs with no Python
   equivalent, and the scope paragraph above is what they need to carry.
