# P6d — a directory level for the two site views: status

One change, in the panel only. `instances` and `codeqs` results stop being a
flat list of theories and become a **directory → file → site** tree; usages
and find-definition are untouched.

| | |
|---|---|
| `jedit_query/src/query_search.scala` | `Result_Kind.folders`, `Folder`, `directory_of`, the collapse rule, `tree` |
| `jedit_query/src/query_dockable.scala` | folder nodes, the two new captions, `enclosing_result`, `open_result` |
| `dev/p6bprobe.{sh,scala}` | two nested fixture theories and §3c — 22 more Scala checks, 1 more shell check |
| `README.md`, `demo/CHEATSHEET.md` | the paragraph, and the one demo line whose panel behaviour moves |

## Why a directory level at all

A site listing is the one view in this plugin that reports two opposite things
in one list. `[code]` adds an equation and `[code del]` takes it away;
`interpretation` installs a locale and the theory next door may do nothing of
the sort. The rows already say *which* — the tag column has done that since
P6b — but "registered here, unregistered there" is a statement about **where**,
and a flat list of theory names is the one shape that cannot make it.

`src/HOL` is the corpus that shows it. `codeqs rev` reports five sites in four
theories, which the tree reads as:

```
Library (1 site in 1 theory)
  Time_Functions (1 site)
Imperative_HOL/ex (2 sites in 2 theories)
  Imperative_Reverse (1 site)
  Linked_Lists (1 site)
List (2 sites)
```

Three directories, and the flat list gave no hint that two of the five are one
experiment sitting under one session while two more are `List.rev` itself.
(That is the real tree, computed headlessly from the index — not a sketch.)

## The tree, as shipped

**Derivation.** `Query_Search.directory_of(root, path)` — the `Group`'s own
theory path against the project root. This is arithmetic on a path the index
already handed back: no `Files` call, no directory walk, nothing that could
block the EDT. Three answers, each a real case:

* **`Nil`** — the theory sits directly in the root, or the index knows no path
  for it. It hangs at the top level beside the directories, which is why a
  project whose theories are all in its root gains no level and looks exactly
  as it did before this existed.
* **the relative segments** — the ordinary case.
* **the absolute directory, as ONE segment** — for a theory the ROOT reaches
  outside the root directory. A `..` chain would be rendered as structure and
  there is none to read.

**The collapse rule.** A directory holding exactly one directory and no files
of its own is not a level, it is a prefix: `a/b/c` with nothing else under `a`
is one node reading `a/b/c`. Without it a shallow project gains a column of
arrows that each reveal a single arrow, and a deep one buries every answer.
The discriminator is *files of its own*: `Data_Structures/` with two theories
in it **and** a `sub/` below does not collapse, because it is a level.

The rule runs over the **result**, not over the filesystem. `codeqs rev` on
`src/HOL` shows `Imperative_HOL/ex` as one node even though `Imperative_HOL/`
holds nine other theories on disk, because none of them answers this question.
That is the right reading — the tree is a map of the answer, not of the
project — but it means the same directory can appear as `Imperative_HOL/ex` in
one result and as `Imperative_HOL` with an `ex` under it in another.

**Order.** The engine's throughout — section load order, which is the build's
own — with a directory taking the position of the first theory that put it
there. The one re-ordering is at render time: directories before the loose
files of the same level, because a heading below the rows it heads reads as an
afterthought.

**Expansion defaults.**

| level | usages | definition | instances / codeqs |
|---|---|---|---|
| result set | open | open | open |
| directory | — | — | **open** |
| file | closed | open | **open** |

Two decisions there.

*Directory nodes always open, and there is no knob for it.* A closed directory
node shows a name and a number, which is strictly less than the flat list it
replaced; there is no kind for which that is the right default. Making it
configurable would have been a switch whose off position is worse than the
status quo ante.

*Both site kinds now open EXPANDED, reversing P6b.* P6b opened them collapsed
on the usages argument — `Category3`'s `category` has 37 sites. The hierarchy
is what flips it: a site row is already the answer (the locus, the name, and
the role that says whether the site gives or takes away), so a tree that hides
every one of them behind a directory node has replaced a list of answers with
a list of directory names. A site list is bounded by **declarations**, tens of
them; a usage list is bounded by citations, and that is the difference the P6b
argument turned on. The Collapse button is one click for the listing that is
the exception.

**Counts, at every level, in the kind's own words.**

* the result root — `37 sites in 12 theories`, unchanged;
* a **directory** — `Data_Structures (4 sites in 3 theories)`, through the
  *same* `count_caption` the root uses, so the two levels cannot drift into two
  vocabularies. In this tree one file is one theory, which is why the second
  half reads "theories";
* a **file** under a site set — `Tree (2 sites)`. Spelled out, where usages and
  definition keep the bare `(2)` they have always shown: one row under a
  directory's `(4 sites in 3 theories)`, a bare number reads as a different
  quantity.

**Gestures.** Nothing on a leaf moved — the row name, the italic role, the
Sorts toggle and all five policies arrive with `Hit`, as they have since P5. A
**directory is not a navigation target**: it has no file and therefore no line,
so `target_of` is `None` and every gesture is a no-op on one. What is left is
the JTree's own double-click toggle, which is what a HyperSearch folder node
does. The popup menu's *Expand* / *Collapse* / *Remove* work on a directory
because they were written against any node, and `expand_all` / `collapse_all`
were already depth-independent.

**The one generalisation.** A leaf now finds its result set an arbitrary number
of levels above it, so the walk-up is written once (`enclosing_result`) and
`result_name` / `result_kind` are its two readers.

## What the probe proves

`dev/p6bprobe.sh` — **143 Scala checks + 28 shell checks, green** (was 121 +
27), plus the CLI/panel cross-check and the failability demonstration.

**§3c, from synthetic group lists.** The tree is arithmetic over a path, so a
fabricated group list is the whole input and every shape a corpus has can be
written down: a theory in the root beside the directories; a directory with
files *and* a sub-directory, which must not collapse; a two-segment chain and a
three-segment one, which must; a group whose path the index does not know; one
outside the root. Counts are checked at each level and against the input
total, no group is lost or duplicated, and a leaf's `[code del]` tag survives
the move — the property the whole feature exists to preserve. The captions are
pinned for all four kinds, including that usages and definition keep the bare
number.

**A fixture that is really nested.** `Nested/Nested_Fix.thy` registers `quad`
with `[code]`; `Deep/Down/Deeper_Fix.thy` retracts it with `[code del]`. Both
declare their own constant, so no count pinned by P6b or P6c moves; the fixture
project goes from three theories to five and that one expectation is updated.
This is the only part a synthetic list cannot show — that the paths the
**index** hands back really do relativise against the root the panel builds
from — and `Deep/Down` gives the collapse rule a real subject.

### The finding: a path-qualified `theories` entry is pruned

The nested fixture was first written the obvious way:

```
session P6B_Nested = P6B_Fix +
  theories
    "Nested/Nested_Fix"
```

Discovery finds the theory and parses it, and `summary` lists it — under the
name **`Nested/Nested_Fix`**, slash and all, which is not what Isabelle calls
it (`Nested_Fix`). P7c's reachability filter then drops every site in it
silently: an importing theory writes `imports "../../Nested/Nested_Fix"`, and
`Reach.import_target` falls back to the *leaf* of the import (`Nested_Fix`)
against a set of known names in which the theory is spelled with its prefix.
`codeqs quad` answered 2 where the source has 3, with nothing on stderr.

That is `dev/P7C-STATUS.md`'s watch-out #2 — "a hole in the closure prunes
silently" — arriving from a direction nobody had tried. It is **not fixed
here**: it is an engine question in `query_base`, and this phase does not touch
`query_base`. The fixture is written `session … in DIR` instead, which is how
Isabelle's own ROOTs are written (`src/HOL` has thirty of them) and which
gives the theory the bare name it has in a real project. Whoever picks the
hole up should decide it once: either theory names never carry a directory
prefix (a `Discovery` question), or `import_target` matches by leaf on **both**
sides.

## The gate

| harness | result |
|---|---|
| `dev/p6bprobe.sh` | **143 + 28 checks, green** (was 121 + 27) |
| `dev/p5probe.sh` | green — plugin/CLI parity |
| `dev/p6probe.sh` | green — IDE features |
| `dev/p7probe.sh` | **85 checks, green**, no stray processes |
| `dev/p7cprobe.sh` | **28 checks, green** |
| `dev/entrydiff.sh` (Category3) | **4 checks, 0 differing** — 28 theories, 1,636 entries, unchanged |
| `dev/difftest.sh` (Category3) | **298 cases: 297 clean, 1 pinned, 0 failing, 0 stale pins** |

**`query_base` was not touched**, so the two differential harnesses are run as
canaries on one corpus rather than as the full matrix. That is the rule this
repo already applies: P6c ran everything because it changed `cli.scala`,
`sites.scala` and `server.scala`; this phase changes two files under
`jedit_query/src` and two under `dev/`, and the engine cannot see either. Both
canaries agree with the figures P6c recorded for that corpus.

The two site verbs' CLI output is unchanged — the tree is the panel's
rendering of a result set the engine already produced, and `--names` still
prints the same loci in the same order, which is what the probe's CLI/panel
cross-check pins.

## Manual jEdit checklist (David)

Nothing below has run with a display. P5's 13, P6's 14–31, P6b's 32–39 and
P6c's 40–53 still apply, **with one correction**: item 34 says a
find-instantiations set "opens **collapsed**". It does not any more — see item
55. New for P6d:

54. **The demo gains one level and nothing else.** Open the demo, right-click
    `assoc_op` (or `Q instances assoc_op` from the name field): the result set
    holds **one** directory node, `Demo_Extras (8 sites in 1 theory)`, with
    `Demo_Sites (8 sites)` under it and the eight rows under that. Every site
    in the demo is in `Demo_Extras`, so one node is the right answer; if a
    second appears, something is grouping by the wrong thing.
55. **The rows are visible without a click.** The same set opens with the
    directory *and* the theory expanded — reading the eight rows must take no
    gesture at all. (This is the reversal of P6b item 34.)
56. **`src/HOL` is the nested case.** Open a theory under the distribution's
    `src/HOL`, and run **Find code equations** on `rev` (five sites). Expect
    three top-level entries, in this order: `Library (1 site in 1 theory)` and
    `Imperative_HOL/ex (2 sites in 2 theories)` as directory nodes, then `List
    (2 sites)` as a loose file beside them, because `List.thy` is in the root
    of that project. `Imperative_HOL/ex` is the collapse rule doing its job: it
    must be **one** node with one arrow, not two nested ones — even though
    `Imperative_HOL/` holds nine other theories on disk, because none of them
    is in this result. Then **Find instantiations** on `comm_monoid` there,
    which is the wider case: `Analysis`, `Number_Theory` and `Algebra` as
    directory nodes (five sites over four theories under the last), and
    `Bit_Operations`, `Groups` and `Boolean_Algebras` loose beside them.
    Both trees were computed headlessly from the real index before this list
    was written; if what the panel shows differs, the panel is what is wrong.
57. **Counts add up down the tree.** On that same result, the directory node's
    count must equal the sum of its files' counts, and the result root's must
    equal the sum of the top-level ones. Delete a leaf with DELETE: every count
    above it drops by one, immediately — they are computed from the tree, not
    frozen at build time.
58. **A directory is not a link.** Double-click a directory node: it toggles
    open/closed and **no buffer is opened**. Single-click selects it and does
    nothing else. ALT+click must not raise a peek popup. Right-click it: the
    menu offers *Expand*, *Collapse*, *Remove*, *Clear all* — and no *Open*,
    because there is nothing to open.
59. **Expand and Collapse still reach the bottom.** With a `src/HOL` site
    result on screen, press **Collapse**: everything closes, including the
    directory nodes. Press **Expand**: everything opens again, directories and
    files and rows, in one press.
60. **Registration and retraction, side by side.** Find code equations on a
    constant that has a `[code del]` somewhere — `twice` in the demo has one in
    `Demo_Code:62`. The `[code]` row and the `[code del]` row must both be
    visible, each still marked in italics, and each under the file and
    directory it is written in. This is the case the whole level exists for.
61. **The other two views did not move.** Find usages of anything: still a flat
    list of theory nodes, still **collapsed**, still `Theory (7)` with a bare
    number. Find definition: still expanded, still one group with the engine's
    `[src A..B]` caption. If either has gained a directory node, the kind seam
    has leaked.
62. **Sorts still repaints.** Tick **Sorts** on an `instances` result over
    `src/HOL`: the rows re-spell in place, the query does not re-run, and the
    directory/file expansion state survives — the deeper tree must not be lost
    to a display toggle.

## Watch-outs for whoever comes next

1. **The directory level is a PANEL notion, not an engine one.** The CLI still
   prints `Theory:LINE`, and `Theory` is a bare name. If a verb ever wants to
   print the directory, that is `Discovery`'s naming question and not this
   code — do not compute it twice.

2. **`Query_Search.tree` is called once per result, on the EDT.** It is
   `O(sites)` with a `LinkedHashMap` per level, which is nothing beside the
   scan that produced the result. If a future kind ever hands it tens of
   thousands of groups, the cost is the Swing nodes, not the tree.

3. **A `Folder` deliberately carries no path.** A directory has no line to jump
   to, and giving it one would put a fifth navigation target into a table whose
   whole point is that every gesture is defined against a `Hit`. If "reveal in
   the file browser" is ever wanted, add it to the popup menu and read the path
   from the first group below the node.

4. **The name is one or more segments, joined with `/` on every platform.**
   That is a display choice (it is how Isabelle spells a theory path), so the
   node's name is not a `Path` and must not be parsed back into one.

5. **`Result_Kind` now carries two booleans and they are not independent.** A
   kind with `folders` and without `expand_groups` is legal and the panel
   handles it — directories open, files closed — but no kind is that today.
   The branch is live rather than dead because `folder_paths` is empty for the
   kinds that have no directories, which is what lets one `open_result` serve
   all four.

6. **The path-qualified `theories` hole above is still open**, and it is worth
   a fixture wherever it gets fixed: it is the only spelling found so far that
   makes a theory's *name* disagree with Isabelle's.
