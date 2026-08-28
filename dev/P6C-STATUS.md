# P6c — the pre-push refinement: status

Five changes, one theme: everything the two site verbs and the warm server
promised, said out loud. Three are new capability (row names, `--sorts`, search
by name), two are defects found by the P7 verification and fixed properly (the
server's captured environment, a benchmark row that measured nothing).

| | |
|---|---|
| `query_base/src/sites.scala` | `Site.name` / `Site.sorts`, the two naming chains, `expression_instances`, `arity_parts`, `written_type`, the four-column renderer |
| `query_base/src/cli.scala` | `--sorts` on both verbs; `Session.env` — the request's environment, not the process's |
| `query_base/src/server.scala` | `query_run` binds the request's `env` and nothing else |
| `query_base/lib/scripts/query_client.py` | `FORWARDED_ENV`, and `~` expanded client-side |
| `jedit_query/src/query_name_search.scala` | new: resolution and gating for the panel's name field |
| `jedit_query/src/query_dockable.scala` | the name field + completion popup, the Sorts toggle, the row name on a leaf |
| `dev/p6bprobe.{sh,scala}` | a third fixture theory and 27 more checks |
| `dev/p7probe.sh` | §9b — six checks the environment leak got past |
| `dev/bench.sh`, `dev/BENCH.md` | a `tiny` tier, and the corrected row |

## 1. A name before every row

`instances` and `codeqs` printed `LOCUS  KIND  source`. On `Category3`, that is
thirty-seven rows of `interpret J: category J` distinguished only by their line
numbers. Each row now carries the name **the source gives that site**, in the
column `callers` and `methods` put their owning entry in:

```
Limit:1368            J                  interpret       interpret J: category J
DualCategory:66       dual_category      sublocale       sublocale dual_category \<subseteq> category comp
Topological_Spaces:3644  prod            instantiation   instantiation prod :: (topological_space, ...
List:3249             rev_conv_fold      [code]          lemma rev_conv_fold [code]: "rev xs = fold Cons xs []"
List:87               rev                default         primrec rev :: "'a list \<Rightarrow> 'a list" where
```

**Not `code_thms`' layout.** That prints a constant and then a block of
equations beneath it. This stays one flat row per site, because the row is what
pastes: the locus is still the first field, and `dev/p6bprobe.sh` §5 shows
`sed`-ing it out of a printed row and feeding it to `enclosing` — with and
without `--sorts`.

### The chains, written first and derived second

`instances`, in order:

1. **the qualifier the author wrote on THIS instance** — `Cop` in
   `sublocale Cop: dual_category C ..`. Per instance, not per command:
   `interpretation L1 x + q: L2 y` names only the second, which is why
   `expression_heads` was rewritten as `expression_instances` returning pairs
   (the old name survives as its `.map(_._2)`);
2. **the type constructor** of an `instantiation` / `instance` arity;
3. **the `L` of `sublocale L \<subseteq> M`** — where the interpretation is
   *installed*, which is exactly what `context L begin … sublocale M … end`
   says the other way round. Treating the two spellings as one answer is the
   decision here, and it is why the fallback below is the enclosing *block*
   rather than nothing;
4. **the context the site sits in** — the enclosing entry (`Commands.enclosing_entry`)
   for an `interpret` inside a proof, else the innermost named target block
   (`Entries.block_stacks`) for a bare `interpretation` inside a locale;
5. **`?`**.

`codeqs`: the **fact that provides the equation** — the declaration's own name
for a `lemma` / `definition` carrying the attribute and for a `default` row;
the binding label a `declare` / `lemmas` attaches to (`fib.simps`,
`twice_lemmas`); the constant for a `[[code drop: c]]`. The **first** name the
command writes, never the later ones on a `lemmas` right-hand side: those are
what the label is bound *to*, and a row named after one of them would say the
site is somewhere it is not.

### `?`, and why not something cleverer

A bare `interpretation L ..` at top level is named in no way at all. It prints
`?` — the engine's own placeholder for an unnamed declaration (`Entries`; and
SCANNING.md, "an opener that carries no name is left unnamed rather than
guessed at"). It is deliberately **not** given the locale's own name, which
would make every such row repeat the question, and not left blank, which would
read as a rendering bug.

## 2. `--sorts`

Off, the name cell is the bare subject. On, it is spelled as the source spells
it:

```
Topological_Spaces:3644  prod :: (topological_space, topological_space) topological_space  instantiation …
Interval:738             interval :: ("{topological_space, preorder}") topological_space   instantiation …
List:87                  rev :: 'a list \<Rightarrow> 'a list                              default …
```

**Written text only**, and the `-h`, the README and the tooltip all say so.
This tool runs no prover; a type it printed that the source does not contain
would be the one column nobody could check against the file. So a quoted brace
sort stays quoted, `instantiation "fun" ::` keeps its arity verbatim, and a
declaration that leaves its type to Isabelle shows none. On `codeqs twice`,
exactly one of four rows gains a `::`, and that is the honest answer.

Two implementation notes:

* the signature is read where the `::` is visible in the **outer** view, which
  is what keeps the `::` of `lemma foo: "f :: nat \<Rightarrow> bool"` — inside
  a term — from being taken for the declaration's own;
* a command may carry `\<^marker>\<open>tag …\<close>` before its arguments.
  The outer view blanks the cartouche but **not** the `\<^marker>` token, so
  `instantiation\<^marker>… vec :: …` read the marker as the type constructor
  until the body was cut past it. `Finite_Cartesian_Product:304` is the row
  that showed it.

## 3. Search by name, in the panel

Every finder in this plugin needed a name **under the caret** — right when you
are reading the theory that mentions it, useless when you are not. Isar's own
`code_thms c` / `print_interps L` take the subject as an argument. The panel
now does too: a field, fuzzy completion, and a *Find* button offering the
finders that name admits.

* **Resolution is exact-then-fuzzy.** A name that *is* a declaration wins
  outright — a project with `map` and `map_map` must not have `map` mean the
  second because it scores higher — and only a name that is not one falls
  through to `Query_Fuzzy`, the ranking go-to-symbol already uses. A name that
  matches nothing is passed through unchanged, so the finder still runs and the
  panel still reports honestly; refusing here would be a second, quieter way to
  say no.
* **The offered set is the context menu's predicate.** `Query_Search.is_subject`,
  which is `Sites.resolve`, which is what the CLI exits 1 on. Usages, external
  usages and definition are offered for *any* name, because they answer for one
  this project only cites. A **cold index offers only those three** — the
  predicate needs entries, reading it must not parse on the EDT, and the
  identical degradation is already documented for the right-click menu
  (`dev/P6B-STATUS.md` item 33).
* **EDT discipline unchanged.** The field reads `index.snapshot` — a volatile
  field, never a parse — behind the same 50 ms `Delay.last` coalescing
  quick-open uses. Every engine call still goes through `request` →
  `Query_Index.background` → `with_namespace`, on the one worker thread.
* **The completion popup is non-focusable** and driven from the field's own key
  handler. A dialog would be `Query_Quick_Open` again, and the panel already
  has somewhere to put results. ENTER runs the default finder, CTRL+ENTER (and
  the button) offers the rest: a menu on every ENTER is one gesture too many
  for the thing people want nine times in ten.
* Results are an **ordinary result set** — same tree, grouping, five gesture
  policies, peek and history. Nothing about navigation is written twice.
* `isabelle-project-query.search-by-name` is the keyboard route (a text field
  in a dockable is otherwise mouse-only) and ships **no default shortcut**, for
  the reason the two site actions ship none.

The **Sorts** checkbox sits beside **Stack** and **repaints rather than
re-queries**: `Hit` carries the name and the written sort separately, so the
toggle re-spells the rows already on screen instead of throwing them away. A
display choice must not cost a parse.

## 4. The server stops reading its own environment

**The defect.** `isabelle server` inherits the environment of whichever client
happened to start it and keeps it for the life of the process. A server first
reached by a client with `$ISABELLE_QUERY_NAMESPACE=committed` set therefore
answered *every* later client — clean environment, identical argv — as though
they had pinned it too: on ZF, `callers induct` came back **1** where the typed
command says **250**, because under the committed HOL table `induct` is a proof
method rather than a citation. Nothing in the request said so and nothing in
the answer did either.

**The fix is not save-and-restore.** It makes the process environment
*unreachable* from a request. `CLI.Session` gains an `env` function; a
command-line run binds the process's own, and `query_run` binds a map built
from the request's `env` object and from nothing else. A client that sends none
gets an **empty** environment rather than the server's — an inherited variable
is invisible in the argv and therefore undebuggable from the caller's side, so
the safe default is "unset", not "whatever this JVM was started with". This is
the same shape as the existing namespace fix one level up: restore to a known
state, then bind for this request, under the lock that already exists.

**Every variable the tool reads, decided one at a time.** `CLI.request_env` is
the list; `FORWARDED_ENV` in the client mirrors it; `dev/p6bprobe` and
`dev/p7probe` between them exercise all of it.

| variable | read by | P6c |
|---|---|---|
| `ISABELLE_LAYOUT_ROOT` | `CLI.default_root_from` | **forwarded** (and still sent as `env_root`, which `query_open` needs — it has no argv) |
| `ISABELLE_QUERY_ROOT` | `CLI.default_root_from` | **forwarded**, likewise |
| `ISABELLE_QUERY_NAMESPACE` | `CLI.configure_namespace` | **forwarded** — the variable this fix exists for |
| `ISABELLE_QUERY_JAR` | `Query_Server.component_id` | **ignored**: the server's own component stamp. A client that could redefine it could make a stale server look fresh |
| `ISABELLE_QUERY_SERVER_LIMIT` | `Query_Server.default_limit` | **ignored**: the server's memory bound, not a caller's. The per-request equivalent exists and is `--client-limit` |
| `ISABELLE_QUERY_CLIENT_*` (4) | the client script | **never sent**: they configure the client process |
| `user.home` (JVM property) | `CLI.expanduser` | **client-side**: the client already made `-R` absolute and now expands `~` first, because in a served run that property is the *server's* home |

The protocol number is unchanged. `env` is an optional field, and a client that
omits it now gets the documented empty environment instead of the bug.

**How it is checked** — `dev/p7probe.sh` §9b, and it needs a *second* server,
because the question is what a process inherits at start-up and that cannot be
asked of one already running. Start one from a pinned client, then: an unpinned
client must get 250 *and* the Pure-floor note on stderr; a pinned client must
still get 1 (forwarded, not merely ignored); and `$ISABELLE_QUERY_ROOT` must
resolve the project from a cwd that is not it. A non-vacuity check runs first
and prints both cold numbers, so the log says what the pin is worth.

Two of those checks failed on the first run for a reason worth keeping: they
folded stderr into the compared file, and the step-down prints a *note* there —
so the warm output differed from the cold precisely **because** the fix was
working.

## 5. The benchmark row that measured nothing

`dev/bench.sh`'s tiny tier ran `show expand`, and `Abstract_Completeness`
declares nothing called `expand`. All three columns timed the same
`No entries matching 'expand'.` — process start and the parse, and none of the
rendering — and the bench's own three-way agreement check passed, because three
identical error messages are identical. That is the failure mode the agreement
check exists to catch, arriving from the other side.

Re-measured with `show fair_fenum`, a 27-line lemma that is really there:

| invocation | oracle ms | cold ms | warm ms |
|---|---:|---:|---:|
| `show fair_fenum` | 73 | 1091 | **33** |
| ~~`show expand`~~ (the old row) | 73 | 1060 | **31** |

Nearly the same numbers, and *that* is the point worth recording: on a
two-theory corpus the JVM **is** the measurement, so a bad subject was
invisible in the timings and visible only in output nobody read.
`dev/bench.sh tiny` was added so one row can be re-measured without disturbing
a table whose other tiers take twenty minutes; the tier argument is now
validated rather than silently selecting nothing. `dev/P7-STATUS.md` keeps its
numbers and gains a dagger — a status report is what was measured then, and
rewriting it would hide the mistake rather than record it.

## The gate

| harness | result |
|---|---|
| `dev/p6bprobe.sh` | **121 fixture checks + 27 shell checks, green** (was 78 + 22) — plus the CLI/panel cross-check and the failability demo |
| `dev/p7probe.sh` | **58 checks, green** (was 52), no stray processes |
| `dev/p5probe.sh` | green — plugin/CLI parity |
| `dev/p6probe.sh` | green — IDE features |
| `dev/entrydiff.sh` | **28 checks, 0 differing** — 2 / 73 / 28 / 20 / 133 / 14 / 5 theories, 81 / 2,467 / 1,636 / 616 / 7,336 / 406 / 82 entries, unchanged |
| `dev/difftest.sh` (full matrix) | **2,086 cases: 1,946 clean, 140 pinned, 0 failing, 0 stale pins** — identical to P4, P6b and P7 |

The full regression was run rather than a spot check because this phase touched
`cli.scala`, `sites.scala` and `server.scala`, and every command goes through
the first.

The two verbs' changed output format constrains nothing in the matrix: they
have no oracle, and the matrix has no case whose oracle-comparable output
mentions them.

**What the new probe checks prove.** `Names_Fix.thy` is a third fixture theory,
separate on purpose — the line numbers in `Sites_Fix.thy` and `Code_Fix.thy`
*are* expectations, and adding a case to either would have moved them. It holds
one site per link of the chain, in order:

```
12  interpretation plain q ..                     inside `locale holder begin`  -> holder
14  sublocale sub: plain q ..                     written qualifier             -> sub
18  interpretation plain id ..                    top level, nothing written    -> ?
20  sublocale holder \<subseteq> inner: plain q .. qualifier beats target        -> inner
24  interpret plain id ..                         inside a lemma                -> anon_interpret
```

Two of the new checks were written wrong first and are worth the note: both
matched `::` against the whole **row**, and the source column quoted at the
right of every arity row has a `::` of its own — so both passed on the wrong
thing. They now match the name **cell**, and the HTML one pins a prefix.

## Manual jEdit checklist (David)

Nothing below has run with a display. P5's 13, P6's 14–31 and P6b's 32–39 still
apply unchanged. New for P6c:

40. **Site rows are named.** Find instantiations of a locale with several
    interpretations: every leaf reads `LINE: NAME  <i>kind</i>  source`. An
    `interpret J: category J` row says `J`; a `sublocale L ⊆ M` row says `L`; a
    bare `interpretation M ..` inside a locale says the locale's name; one at
    top level with no qualifier says `?`.
41. **The Sorts toggle repaints.** Find instantiations of a *class* (something
    with `instantiation` sites — `comm_monoid` in `src/HOL`), then tick
    **Sorts**: the rows already on screen gain `:: (…) …` **without the query
    re-running** (the caption must not go back to "searching"), and the
    expansion state must survive. Untick: they go back. The setting must
    survive a jEdit restart.
42. **Sorts adds nothing where nothing is written.** On a `codeqs` result, only
    the rows whose declaration writes a signature change. This is the caveat,
    seen: no type is invented for the others.
43. **The name field completes.** Click into **Name:**, type three letters of a
    declaration in the project: a list drops below the field, best match
    selected, each row showing the entry's tag and locus in grey. UP/DOWN moves
    it; ESC closes it and leaves the text; clicking a row fills the field.
44. **ENTER is Find usages.** Type an exact name, press ENTER: a usages result
    set appears in the tree, identical in every way to one from the right-click
    menu (collapsed, same gestures, same peek).
45. **The Find menu is kind-aware.** With the field holding a **locale** name,
    press the **Find** button: *Find usages*, *Find external usages*, *Find
    definition* and *Find instantiations* — and no *Find code equations*. With
    a **constant**, the other way round. With a **lemma**, only the first
    three. CTRL+ENTER in the field must open the same menu.
46. **A cold index offers only three.** Restart jEdit, open the panel without
    running any query, type a locale name and press **Find**: only the ungated
    three. Run any query once, then try again: the site item appears. (Same
    documented degradation as item 33 — if it feels wrong in practice, the fix
    is the same one.)
47. **A fuzzy name still answers.** Type a name with a letter missing
    (`comm_monod`): the caption shows `→ comm_monoid (LOCALE in …)` and ENTER
    searches for the resolved name, not the typed one.
48. **A name that exists nowhere is not swallowed.** Type `zzz_nothing` and
    press ENTER: the panel says `no usages of zzz_nothing — …`, not silence.
    *Find instantiations* on it (via the action, which does not gate) must say
    `'zzz_nothing' is not a locale or class declared in this project — …`.
49. **The keymap is still clean.** `search-by-name` ships no shortcut, so the
    first start after this build must not raise a keymap dialog.
50. **The panel still fits.** The name field, the caption and the seven buttons
    share one row; at a narrow dock width the *caption* is what ellipsises (it
    is the BorderLayout centre), not the field or the buttons.

## Watch-outs for whoever comes next

1. **The name column is a fifth thing `Sites` computes per site, and one of
   them is not free.** `Entries.block_stacks` is a second pass over a theory's
   lines. It is built lazily and at most once per theory, and only for a theory
   that actually has a site, so `instances` over `src/HOL` pays it for a
   handful of files — but a future verb that names *every* line would need the
   stacks cached on the section instead, and `model.scala` explains why that is
   not free either.
2. **`--sorts` widens the name column to its longest cell.** One
   `(topological_space, topological_space) topological_space` pushes the source
   column fifty characters right for every row of that listing. That is the
   flag doing what it says; if it ever needs a cap, cap the *padding*, not the
   cell, or the output stops being copy-pasteable.
3. **The codeqs signature is the ROW's declaration, not the constant's.** A
   `[code]` lemma row shows no type even though the constant has one written
   somewhere else. That is deliberate — the cell says `NAME :: T` and `NAME`
   there is a fact, not a term — but the first person to ask "why does only one
   row have a type" is asking a fair question. The answer is in `-h`.
4. **`Query_Name_Search.resolve` never refuses.** An unmatched name is passed
   through, so the finder runs and the panel reports. If a future field wants
   to *prevent* a hopeless search, the place is the caller, not `resolve`: the
   panel's refusal is more informative than a disabled button.
5. **The completion popup reads the snapshot on the EDT, per keystroke**, like
   the context menu. It is a volatile read plus `Query_Fuzzy` over
   `entry_names` — the same work go-to-symbol does per keystroke, and measured
   nowhere. `Query_Quick_Open` forces `entry_by_name` on the worker before
   showing its list; the panel's field does not, because it only reaches
   `definition` for the ≤50 rows it renders. On a 50,000-entry index that first
   lookup builds the map **on the EDT**. If the field ever feels slow on the
   first keystroke, that is why.
6. **`CLI.request_env` is now the contract.** A new environment variable read
   anywhere in the engine must be added there *and* to `FORWARDED_ENV` in the
   client, or it will silently mean nothing over the socket — which is the
   quieter version of the bug this phase fixed. `dev/p7probe.sh` §9b is where a
   check for it goes.
7. **The jEdit plugin still reads the process environment**, through
   `Session.env`'s default, and that is right: an editor *is* a process the
   user started. Only the server overrides it.
