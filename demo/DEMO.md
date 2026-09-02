# A guided tour of `isabelle query`

`demo/` is a corpus written to be **queried**. Nothing in it is deep; every
declaration exists so that some verb has a named thing to point at, and this
file names the verb beside it. Six theories, two sessions, 683 lines.

Every command below was run against this tree and every excerpt is trimmed from
its real output. Commands are shown **from the repository root**.

Sitting in jEdit and want one line per feature instead? See
**[CHEATSHEET.md](CHEATSHEET.md)**.

> If you have not installed the component yet, `isabelle components -u .` from
> the repository root. During development the tool is run against the repo's own
> scratch Isabelle home instead, so a work-in-progress component stays invisible
> to a real session — prefix every command with `USER_HOME=.dev`.

## The tree

| file | what it is for |
|---|---|
| `demo/ROOTS` | two session directories, so `summary --by-session` has something to aggregate |
| `demo/.isabelle-query` | a project marker reading `.` — makes **both** sessions one project, so a query crosses them |
| `Demo_Core/ROOT` | `session Demo_Core = HOL`, `options [quick_and_dirty]`, declaring **one** theory |
| `Demo_Core/Demo_Types.thy` | one declaration of every kind: datatype with discriminator + selectors, record, class, locales (incl. the quoted `"functor"`), a name with a markup symbol (`f\<^sub>1`), `axiomatization` |
| `Demo_Core/Demo_Ops.thy` | the names one command binds: `fun … and …`, `inductive` with labelled rules, `definition` with a named equation, `shows x: … and y:`. Plus the two decoys, `\<comment>` notes, a fact named exactly `mono`, and the deliberately dead lemma |
| `Demo_Core/Demo_Legacy.thy` | the import nothing cites; also the only `paragraph` / `subparagraph` headings |
| `Demo_Core/Demo_Proofs.thy` | an apply script, a four-link citation chain, a nested Isar proof with one very wide step, the one open goal |
| `Demo_Core/Demo_Sketch.thy` | an **orphan** — in the directory, imported by nothing, read by neither `isabelle build` nor `query` |
| `Demo_Extras/ROOT` | `session Demo_Extras = Demo_Core`, declaring one theory |
| `Demo_Extras/Demo_Sites.thy` | one site per link of the `instances` naming chain |
| `Demo_Extras/Demo_Code.thy` | every code-equation spelling, the one that is documented to be missed, and one declaration the Python oracle cannot index |
| `media/` | where the screen recordings land |

Build both sessions (green, and the `sorry` needs the `quick_and_dirty` on
`Demo_Core`):

```sh
isabelle build -d demo Demo_Core Demo_Extras
```

---

# 1. Structure

## 1.1 Two sessions, one corpus

```sh
isabelle query -R demo summary --by-session
```

```
62 entries · 683 source lines across 6 theories in 2 sessions  (parsed live from .thy files)

| Session | Thy | Src | D | L | T |
|---------|----:|----:|--:|--:|--:|
| Demo_Core | 4 | 428 | 8 | 24 | 2 |
| Demo_Extras | 2 | 255 | 9 | 7 | 0 |
| **TOTAL** | 6 | 683 | 17 | 31 | 2 |
```

`-v` expands each session to its theories; `-c` prints the grand totals alone.
The `Src` column is `wc -l`-comparable over the same file set.

## 1.2 The import closure, and the orphan

`Demo_Core/ROOT` declares **one** theory, `Demo_Proofs`. Four are loaded,
because discovery is what `isabelle build` compiles: the declared roots plus the
transitive closure of their `imports`.

```sh
isabelle query -R demo deps -r Demo_Proofs
```

```
Import-transitive dependencies of Demo_Proofs:
  Demo_Legacy  (51 src lines, 3 entries)  [direct]
  Demo_Ops  (128 src lines, 13 entries)  [direct]
  Demo_Types  (103 src lines, 14 entries)  [depth 1]
  Main  [out-of-project]
```

`Demo_Core/Demo_Sketch.thy` sits in the session directory and no declared root
imports it. It is therefore **not** in the corpus — the negative control for
discovery:

```sh
isabelle query dump-theories demo          # the hidden dev dump; -R is positional here
isabelle query -R demo find sketch_
```

```
Demo_Core/Demo_Legacy.thy
Demo_Core/Demo_Ops.thy
Demo_Core/Demo_Proofs.thy
Demo_Core/Demo_Types.thy
Demo_Extras/Demo_Code.thy
Demo_Extras/Demo_Sites.thy
```
```
No entries matching 'sketch_'.
```

A `grep -r sketch_double demo` finds it. `query` does not, because `isabelle
build` would not.

## 1.3 `theory`, `defs`, `outline`

```sh
isabelle query -R demo theory Demo_Types
```

The rendered form shows the multi-name commands as one entry each:

```
AXIOMATIZATION
  AXIOM cost :: "instr \<Rightarrow> nat" and
  AXIOM fuel :: nat where
  AXIOM cost_pos: "0 < cost i" and
  AXIOM fuel_pos: "0 < fuel"
```

`axiomatization` is the one command whose bound names are **separate** entries:
each is written on its own line and so is independently locatable.

```sh
isabelle query -R demo outline Demo_Types --no-comments
```

```
       chapter: A corpus written to be queried  (line 13)
       section: Containers  (line 21)
      subsection: A datatype, with a discriminator and selectors  (line 23)
        DATATYPE tree  (25..34, 10 lines)
     subsubsection: An instruction set  (line 35)
        DATATYPE instr  (37..44, 8 lines)
```

**The two heading levels `outline` refuses.** `Demo_Legacy` carries a
`paragraph` and a `subparagraph`:

```sh
isabelle query -R demo outline Demo_Legacy ; echo "exit=$?"
```

```
Outline of Demo_Legacy.thy:

       section: Superseded arithmetic  (line 13)
isabelle query: outline: unknown heading level 'paragraph'
exit=1
```

That is deliberate and is **byte-parity, not a bug**: the reference Python
implementation indexes a fixed four-entry table there and dies with a
`KeyError`. Inventing an indent it never printed would be the larger change.
Every other verb reads `Demo_Legacy` normally.

## 1.4 The names one declaration binds

Isabelle binds more than one name per command, and each is citable. `show`
resolves any of them back to the declaring entry **and says how**.

```sh
isabelle query -R demo show Node
isabelle query -R demo show odds
isabelle query -R demo show total_nil
```

```
# 'Node' is a constructor of tree:
# 'odds' is declared together with evens:
# 'total_nil' is a named conjunct of total_facts:
```

The full set the demo exercises: constructors / discriminator / selectors of
`tree` (`Leaf`, `Node`, `is_leaf`, `left`, `item`, `right`), the second constant
of `fun evens and odds`, the rule labels `start` and `hop` of `inductive
reachable`, the named equation `total_eq` of `definition total`, the conjuncts
`total_nil` / `total_cons` of `lemma total_facts`, and the fields `owner` /
`balance` of `record account`.

They are deliberately **not** separate entries: one command has one span, so
counting `fun evens and odds` twice would double-count it under `largest` and
give `enclosing` two owners for each of its lines.

## 1.5 Names that are awkward to spell

```sh
isabelle query -R demo find 'f.*sub.*1' --names
```

```
f\<^sub>1_chain (LEMMA) — Demo_Proofs [src 137..139, body 137..138, 2/3 lines]
f\<^sub>1 (DEF) — Demo_Types [src 83..87, body 85..86, 2/5 lines]
f\<^sub>1_gt (LEMMA) — Demo_Types [src 88..90, body 88..89, 2/3 lines]
```

`Demo_Types` also declares `locale "functor"`, quoted because `functor` is an
Isar command in HOL. The name is read from the **live** view — the outer view
blanks inner syntax, which is exactly where the quotes put it — and it stays
quoted at the site (§3.1).

## 1.6 Loci: `largest`, `enclosing`, `lines`, `grep`

Locations and spans share one grammar (`THEORY:LINE`, `THEORY:A..B`), so the
tool's output is valid input.

```sh
isabelle query -R demo largest -N 6
```

```
 Lines  Tag       Name                                        Theory  (span)
------  --------  ------------------------------------------  ------
    21  LEMMA     account_settlement                          Demo_Proofs  (89..109)
    19  THEOREM   total_grows                                 Demo_Proofs  (65..83)
    16  LEMMA     tree_depth_pos                              Demo_Proofs  (110..125)
    14  FUN       evens                                       Demo_Ops  (17..30)
```

Paste a span straight back in:

```sh
isabelle query -R demo lines Demo_Proofs 95..101
isabelle query -R demo grep total Demo_Proofs:95..109
```

`enclosing` (alias `at`) is the inverse of `outline`, and names the enclosing
locale/class target:

```sh
isabelle query -R demo enclosing Demo_Ops:88 Demo_Ops:95
isabelle query -R demo enclosing -b Demo_Proofs:118
```

```
Demo_Ops:88 → assoc_right (LEMMA) — Demo_Ops ▸ target assoc_op [src 88..89, 2 lines]  (in statement)
Demo_Ops:95 → g_absorb (LEMMA) — Demo_Ops ▸ context idem_op [src 94..95, 2 lines]  (in proof)
Demo_Proofs:118 → tree_depth_pos (LEMMA) — Demo_Proofs [src 110..125, body 110..124, 15/16 lines]  (in proof)
                  ▸ have 118..122
```

Two different ways of being in a target — `lemma (in assoc_op) assoc_right` by
the explicit modifier, `g_absorb` by lexical nesting in a `context` block — and
`-b` gives the full nesting path inside the proof.

A site listing round-trips the same way:

```sh
isabelle query -R demo codeqs twice --names | xargs isabelle query -R demo enclosing
```

```
Demo_Code:24 → twice (FUN) — Demo_Code [src 18..27, body 24..26, 3/10 lines]  (in statement)
Demo_Code:34 → twice_add (LEMMA) — Demo_Code [src 34..36, body 34..35, 2/3 lines]  (in statement)
```

## 1.7 What is *not* there: the two decoys

`Demo_Ops` contains a commented-out `definition decoy_total` and a
`\<^cancel>`-ed `definition cancelled_total`. Neither is a declaration.

```sh
isabelle query -R demo find decoy               # entries
isabelle query -R demo grep decoy               # live source
isabelle query -R demo grep --with-comments decoy
```

```
No entries matching 'decoy'.
No live matches for 'decoy'.
7 match(es) for 'decoy' (0 live, 7 in comments/text):
  Demo_Ops.thy:110  —  [in comment/text]
    definition decoy_total :: "int list \<Rightarrow> int"
```

**0 live, 7 in comments.** This is the whole doctrine in one line: a name in a
comment is not a citation, so it never invents a caller or hides a dead lemma —
and a `definition` left behind in a comment is not an entry.

## 1.8 The prose view

```sh
isabelle query -R demo show total_append --comments-only
```

```
--- annotations (\<comment>) ---
  declaration:
    | line 52: the total of a concatenation splits
  proof:
    | line 55: the list sum already distributes over append
```

The grouping is the content: a note on the declaration says what is being
claimed, one in the proof says how it is reached.

---

# 2. Usage

## 2.1 The citation chain

`total_eq` ← `total_append` ← `total_shift` ← `total_snoc_ge` ← `total_grows`.

```sh
isabelle query -R demo callers total_append        # 1
isabelle query -R demo callers total_append -r     # 5
```

```
5 transitive caller(s) of total_append:

    total_shift (LEMMA) — Demo_Proofs [L45]
      total_snoc_ge (LEMMA) — Demo_Proofs [L49]
        total_grows (THEOREM) — Demo_Proofs [L65]
        total_snoc_twice (LEMMA) — Demo_Proofs [L54]
          demo_export (THEOREM) — Demo_Proofs [L84]
```

One direct caller, five transitive, four levels of indentation. `callees` is the
same graph read forwards; `--external` on either drops the same-theory edges.

## 2.2 `deps` vs `refs` — the disagreement is the signal

`Demo_Proofs` imports `Demo_Legacy` and never mentions a name from it.
`deps` reads the `imports` clause; `refs` rolls the **citation** graph up by
owning theory. The tool names the gap in both directions:

```sh
isabelle query -R demo refs Demo_Proofs
```

```
Demo_Proofs references 11 name(s) from 3 theory/theories:

  Demo_Ops     [direct import]   5
  Demo_Types   [import depth 1]  2
  Demo_Proofs  [self]            4

  Direct imports no citation reaches (1): Demo_Legacy
  Cited but not directly imported (1): Demo_Types
```

Read it as: `Demo_Legacy` is an import that could be dropped, and `Demo_Types`
is a dependency the header does not admit to (it arrives through `Demo_Ops`).

## 2.3 Dead code

```sh
isabelle query -R demo unused -c
isabelle query -R demo unused --by-theory
```

```
25
25 unused entries across 5 theories (135 source lines):

   10  Demo_Proofs    72 lines  reachable_four, total_snoc_twice, demo_export, account_settlement, ... (+6)
    6  Demo_Ops       18 lines  f\<^sub>1_le_double, f\<^sub>1_mono_rule, f\<^sub>1_le_suc, assoc_right, ... (+2)
    2  Demo_Legacy    17 lines  legacy_scale_mono, legacy_twice
```

`dead_helper` is in the `Demo_Ops` group and is the one entry here that is dead
**on purpose** — declared, proved, cited by nothing. The other 24 are a fair
picture of a real answer: most are top-level results a corpus this size exports
rather than consumes. That is what `--keep` is for, and why `-h` calls this a
source-text citation graph rather than ground truth:

```sh
isabelle query -R demo unused -c --keep total_grows,demo_export,account_settlement,tree_depth_pos
```

`-r` cascades (an entry whose only callers are themselves unused), and
`--roots` gives the forest summary.

## 2.4 Methods, and a fact named exactly `mono`

`Demo_Ops` declares `lemma mono`, whose name is also a real Isabelle attribute.
Three uses, three positions:

```sh
isabelle query -R demo callers mono
```

```
3 caller(s) of mono:

  Demo_Ops:76  f\<^sub>1_le_double (LEMMA) 75..77  using mono by simp \<comment> \<open>chained fact\<close>
  Demo_Ops:81  f\<^sub>1_mono_rule (LEMMA) 78..82  using assms by (rule mono) \<comment> \<open>argument of a rule\<close>
  Demo_Ops:84  f\<^sub>1_le_suc (LEMMA) 83..85  by (simp add: mono) \<comment> \<open>bare method argument\<close>
```

The third line is the interesting one. `by (simp add: mono)` holds two
identifiers in method position, and they are read differently:

```sh
isabelle query -R demo methods
```

```
10 proof methods used across 58 by/apply/proof introducers (top 10):

  simp                  26   44.8%
  rule                  12   20.7%
  unfold_locales         9   15.5%
```

`simp` is counted as a **method** and never as a citation of anything; `mono` is
counted as a **fact** and never as a method. `methods rule` lists all twelve
`rule` sites — including `Demo_Ops:81`, which is the same line `callers mono`
reports, seen from the other side.

Note also what the `\<comment>` notes shown in those rows do *not* do: they are
printed, because they are part of the source line, and they are not scanned.

## 2.5 Open goals

```sh
isabelle query -R demo sorry
```

```
  Demo_Proofs.thy:144  reachable_le (LEMMA)
1 sorry
```

Exactly one, and it is the reason `Demo_Core/ROOT` carries
`options [quick_and_dirty]` — a batch build otherwise refuses to cheat.

## 2.6 A name a theory cannot see

`Demo_Extras` declares a function `twice`, and the arrow only goes one way:
`Demo_Extras` imports `Demo_Core`, never the reverse. `Demo_Legacy`'s last
lemma binds a variable called `twice`, which is exactly the coincidence a
name-level scan cannot tell from a citation:

```sh
isabelle query -R demo callers twice
isabelle query -R demo callers twice --reach name
```

```
3 caller(s) of twice:

  Demo_Code:34  twice_add (LEMMA) 34..36  lemma twice_add [code]: "twice n = n + n"
  Demo_Code:38  thrice (DEF) 37..38  where "thrice n = twice n + n"
  Demo_Code:62  —  declare twice.simps [code del]
```
```
4 caller(s) of twice:

  Demo_Legacy:48  legacy_twice (LEMMA) 36..49  lemma legacy_twice: "\<forall>twice. twice \<longrightarrow> twice"
  Demo_Code:34    twice_add (LEMMA) 34..36  lemma twice_add [code]: "twice n = n + n"
  Demo_Code:38    thrice (DEF) 37..38  where "thrice n = twice n + n"
  Demo_Code:62    —  declare twice.simps [code del]
```

A citation is attributed to a declaration only where the citing theory can
**see** it — its own, or one in its transitive `imports` closure. It is a
*necessary* condition, so it only ever removes an impossible answer: inside one
import tree nothing moves at all, and over the whole AFP `callers mono` reports
566 rather than 1,361. The same filter is what `callees`, `refs`, the dead-code
verb, `graph citation`, `instances` and `codeqs` read — and it is why
`legacy_twice` is listed as dead in §2.3: without it, `Demo_Code` looked like
its caller.

`--reach name` restores name-only attribution, which is the mode
`dev/difftest.sh` compares against the Python oracle.

## 2.7 The graphs

```sh
isabelle query -R demo graph imports
isabelle query -R demo graph citation -f dot | dot -Tpng > /dev/null
```

`Demo_Sites`'s import of `Demo_Core.Demo_Proofs` is **absent** from the import
graph, and correctly so: it is a cross-session import, which discovery does not
follow, the same rule that keeps `HOL-*` out.

---

# 3. Sites — `instances` and `codeqs`

These two have no Python counterpart. Both report **declared source sites** —
the complement of Isar's `print_interps` / `print_codesetup`, which need a
running prover and show the *processed* setup. A static scan sees the text; the
prover sees the result.

## 3.1 `instances`: the name is what the source calls the site

```sh
isabelle query -R demo instances assoc_op
```

```
8 instantiation(s) of assoc_op:

  Demo_Sites:70   nat_max           interpretation         interpretation nat_max: assoc_op "max :: nat \<Rightarrow> nat \<Rightarrow> nat"
  Demo_Sites:73   nat_min           global_interpretation  global_interpretation nat_min: assoc_op "min :: nat \<Rightarrow> nat \<Rightarrow> nat"
  Demo_Sites:76   ?                 interpretation         interpretation assoc_op "(+) :: nat \<Rightarrow> nat \<Rightarrow> nat"
  Demo_Sites:93   idem_op           sublocale              sublocale idem_op \<subseteq> assoc_op g
  Demo_Sites:103  sub               sublocale              sublocale sub: assoc_op q
  Demo_Sites:108  holder            interpretation         interpretation assoc_op q
  Demo_Sites:122  times_sg          interpret              interpret times_sg: assoc_op "(*) :: nat \<Rightarrow> nat \<Rightarrow> nat"
  Demo_Sites:129  interp_anonymous  interpret              interpret assoc_op "(@) :: nat list \<Rightarrow> nat list \<Rightarrow> nat list"
```

Eight rows, one per link of the naming chain, in order:

1. **`nat_max`, `nat_min`, `sub`, `times_sg`** — the qualifier the author wrote
   on *this* instance.
2. **`idem_op`** — `sublocale idem_op \<subseteq> assoc_op g` is a site of
   **`assoc_op`**, which is what is interpreted, named after `idem_op`, where
   the interpretation is installed. `instances idem_op` reports *nothing*. This
   is the single case that separates the scan from a `grep`.
3. **`holder`** — a bare `interpretation` inside `locale holder begin … end`
   falls through to the enclosing target.
4. **`interp_anonymous`** — an unqualified `interpret` inside a proof falls
   through to the lemma it sits in.
5. **`?`** — a bare `interpretation` at top level is named in no way at all. It
   is deliberately not given the locale's own name, which would make every such
   row repeat the question.

The quoted locale keeps its quotes at the site:

```sh
isabelle query -R demo instances functor
```

```
  Demo_Sites:81  rev_functor  interpretation  interpretation rev_functor: "functor" rev
```

Class arities are sites too, and a bare `instance` is one:

```sh
isabelle query -R demo instances weight
isabelle query -R demo instances heavy
```

```
  Demo_Sites:21  nat   instantiation  instantiation nat :: weight
  Demo_Sites:30  list  instantiation  instantiation list :: (weight) weight
  Demo_Sites:39  prod  instantiation  instantiation prod :: (weight, weight) weight

  Demo_Sites:58  nat  instance  instance nat :: heavy
```

`class heavy = weight + …` itself is **not** a site: an extension supplies no
type and no term. It appears under `callers weight`, which is where "who
mentions `weight`" belongs.

## 3.2 `--sorts` — written text, never inferred

```sh
isabelle query -R demo instances weight --sorts
```

```
  Demo_Sites:21  nat :: weight                    instantiation  instantiation nat :: weight
  Demo_Sites:30  list :: (weight) weight          instantiation  instantiation list :: (weight) weight
  Demo_Sites:39  prod :: (weight, weight) weight  instantiation  instantiation prod :: (weight, weight) weight
```

The multi-parameter arity is the point: `prod :: (weight, weight) weight`
instantiates `weight` **once** — the sorts in parentheses constrain the
arguments.

On `codeqs`, exactly one of three rows gains a `::`, which is the honest answer:

```sh
isabelle query -R demo codeqs twice --sorts
```

```
  Demo_Code:24  twice :: nat \<Rightarrow> nat  default     fun twice :: "nat \<Rightarrow> nat" where
  Demo_Code:34  twice_add                       [code]      lemma twice_add [code]: "twice n = n + n"
  Demo_Code:62  twice.simps                     [code del]  declare twice.simps [code del]
```

No prover runs, so nothing is inferred: a site whose source writes no type shows
none. And the signature shown is the **row's** declaration, not the constant's —
`twice_add` is a fact, not a term.

## 3.3 `codeqs`: every spelling

```sh
isabelle query -R demo codeqs twice thrice sumlist NDlist
```

```
  Demo_Code:24  twice        default     fun twice :: "nat \<Rightarrow> nat" where
  Demo_Code:34  twice_add    [code]      lemma twice_add [code]: "twice n = n + n"
  Demo_Code:62  twice.simps  [code del]  declare twice.simps [code del]

  Demo_Code:37  thrice       default  definition thrice :: "nat \<Rightarrow> nat"
  Demo_Code:40  thrice_def   [code]   declare thrice_def [code]
  Demo_Code:45  thrice_code  [code]   lemmas thrice_code [code] = thrice_alt

  Demo_Code:28  sumlist        default  primrec sumlist :: "nat list \<Rightarrow> nat" where
  Demo_Code:52  sumlist.simps  [code]   declare sumlist.simps [code]

  Demo_Code:96   NDlist        default          definition NDlist :: "nat list \<Rightarrow> ndlist"
  Demo_Code:101  elems_NDlist  [code abstract]  lemma elems_NDlist [code abstract]: "elems (NDlist xs) = remdups xs"
```

Six kinds in twelve lines of theory text: `default` equations that carry no
attribute at all (a purely attribute-driven scan misses these entirely), a
`[code]` lemma, a `declare`, a `lemmas`, a `[code del]` **retraction shown and
marked**, and a `[code abstract]` over a real `code_datatype` abstype.

The `[code del]` row is the deliberate one. A listing that showed the equations
and hid the retraction that is the reason one of them is not in force would be
the more misleading of the two.

`[code abstract]` reads `projection (constructor args) = …`, whose outermost
head is the projection and whose subject is the constructor; the head rule
collects both, so `codeqs NDlist` finds it. `codeqs elems` exits 1 — a `typedef`
morphism is not an entry this scanner declares, and the verb refuses a subject
it cannot resolve rather than reporting a plausible-looking zero.

## 3.4 The documented blind spot

```sh
isabelle query -R demo codeqs shift
```

```
1 code equation(s) of shift:

  Demo_Code:75  shift  default  definition shift :: "nat \<Rightarrow> nat \<Rightarrow> nat"  (infixl \<open>\<oplus>\<close> 65)
```

**One row, and there are two.** `Demo_Code:78` is

```isabelle
lemma shift_code [code]: "x \<oplus> y = x + y + y"
```

— an equation of `shift`, written in `shift`'s own notation, where the head
position holds no identifier at all. `codeqs` finds an equation by the head
symbol of its left-hand side, and mixfix defeats that rule. This is irreducible
without a parser that knows the project's `notation` declarations, and it
**under-reports** — the one place these scans lean the unsafe way. If `codeqs c`
looks short, check with `query grep`.

## 3.5 Exit status

```sh
isabelle query -R demo instances total ; echo "exit=$?"
```

```
ERROR: 'total' is a DEF in Demo_Ops, not a locale or class
exit=1
```

`instances idem_op` prints `No instantiations found for 'idem_op'.` and exits
**0** — an honest zero. An unresolvable subject exits **1** and prints nothing
on stdout, `-c` included: printing `0` for a question that could not be asked is
precisely the silent zero the contract is about.

---

# 4. Proof shape

```sh
isabelle query -R demo shape summary
```

```
32 proofs across 6 theories  (source-level shape metrics, parsed live)

| Theory | Proofs | Goals | depth:max | Bare% | w2:max | w1:max | fanin:max | live:max | dag:max | lines:max |
|--------|-------:|------:|----------:|------:|-------:|-------:|----------:|---------:|--------:|----------:|
| Demo_Proofs | 13 | 16 | 2 | 44% | 91 | 5 | 2 | 2 | 1.81 | 14 |
| Demo_Sites | 2 | 4 | 1 | 50% | 13 | 0 | 1 | 1 | 1.00 | 5 |
```

`Demo_Ops`, `Demo_Types`, `Demo_Code` and `Demo_Legacy` show zeros across the
width columns: their proofs are one-liners with no goal steps, which is a shape,
not a gap.

**The deliberately wide step.** `account_settlement`'s `ledger` says four
equations at once where four steps would each have been narrow:

```sh
isabelle query -R demo shape widest -N 4
```

```
   w2 location               lemma                     statement
----- ---------------------- ------------------------  ---------
   91 Demo_Proofs:100        account_settlement        total [a, b, c, d] = a + b + c + d \<and> total ([a, b]…
   21 Demo_Proofs:77         total_grows               total (xs @ [y]) \<le> total ((xs @ [y]) @ ys)
   18 Demo_Proofs:104        account_settlement        total [a, b, c, d] = a + b + c + d
```

91 tokens against a corpus median in the teens. Paste the locus into `enclosing`
or `lines` to see it.

One proof, every step, with the M6 curve:

```sh
isabelle query -R demo shape lemma total_grows
```

```
 line kind       w2   w1  fan live  statement
   75 goal       11    3    0    1  total xs \<le> total (xs @ [y])
   77 goal       21    4    0    2  total (xs @ [y]) \<le> total ((xs @ [y]) @ ys)
   79 goal       15    4    2    2  total xs \<le> total ((xs @ [y]) @ ys)

5 goals (2 bare)  w2 max 21 mean 15.7  w1 max 4  fan-in max 2  live max 2 mean 0.7
M6 widest block (3 goals)  k:    0     1     2     4     8    16
                          w2:   47    23    23    23    23    23
```

`shape steps THEORY:A..B` scopes to a span; `shape census` streams one JSON
object per proof (31 lines here) and is the corpus-scale form. The apply-style
scripts `reachable_suc` / `reachable_four` are in the same table as the
structured proofs, which is the comparison the family exists for.

---

# 5. The warm server

The same command line against a resident JVM. The client is a stdlib-only
Python script; no JVM is on the fast path — and it is what a plain
`isabelle query` runs, so the warm path needs no extra spelling.

```sh
isabelle query -R demo summary -c          # starts a server on first use
isabelle query -R demo callers total_append -r
isabelle query --client-status
isabelle query --client-stop
```

```
server        isabelle_query
protocol      1 (client 1)
version       0.8.1-scala
index         <checkout>/demo  6 theories, 62 entries, 147 ms build / 4 ms recheck, 1 uses
```

Timing, on this corpus — the floor is the JVM, and the warm path removes it:

```sh
for i in 1 2 3; do
  /usr/bin/time -f 'cold %e' isabelle query --no-server -R demo callers total_append -r >/dev/null
  /usr/bin/time -f 'warm %e' isabelle query -R demo callers total_append -r >/dev/null
done
```

| | `--no-server` (cold JVM) | thin client (plain `isabelle query`) |
|---|---:|---:|
| `callers total_append -r` on `demo` | 1100 ms | **40 ms** |

**27x**, and the demo is 683 lines — almost all of the cold cost is process
start, which is exactly what the server removes. Output is byte-identical, the
exit status is the same, and any failure falls back to running cold.

---

# 6. The oracle showcase

`Demo_Extras/Demo_Code.thy` contains one declaration written the way
`HOL-Analysis` writes hundreds:

```isabelle
definition\<^marker>\<open>tag important\<close> marked_const :: nat
  where "marked_const = 7"
```

A document marker is written with **no space** after the command keyword. The
Python implementation's declaration regex requires whitespace there, so it does
not see a command at all and the declaration is invisible to it. This engine
uses Isabelle's own lexer, which treats `\<^marker>` as a formal comment.

Run the two side by side:

```sh
query          -R demo/Demo_Extras summary -c        # the Python tool
isabelle query -R demo/Demo_Extras summary -c        # this one
```

```
17 entries · 255 source lines across 2 theories in 1 sessions      <- the oracle
18 entries · 255 source lines across 2 theories in 1 sessions      <- this engine
```

The whole-entry-set differential puts it in one line:

```sh
dev/entrydiff.sh demo/Demo_Core demo/Demo_Extras
```

```
ok    entries          Demo_Core        (43 records)
DIFF  entries          Demo_Extras      (1 differing lines)

+Demo_Code:115:DEF:marked_const
```

That `+` is the property the port is built on, shown rather than asserted:
**no entry is ever lost.** Over the whole AFP and the whole distribution the
oracle's set of `theory:line:tag:name` identities is a strict *subset* of this
engine's, and this is one of the 767 declarations the marker case accounts for.
`dev/DIVERGENCES.md` records it as **D2**.

`Demo_Core` is kept clear of it on purpose, so the rest of the tree is a clean
differential corpus:

```sh
dev/difftest.sh demo/Demo_Core     # 298 cases: 294 clean, 4 pinned, 0 failing
dev/difftest.sh demo/Demo_Extras   # 298 cases: 237 clean, 1 pinned, 60 failing
```

All 60 `Demo_Extras` failures are the same missing entry, counted in a different
column each time. See the pin block in `dev/difftest-pins` for the four pins on
`Demo_Core` and why each is a documented divergence rather than a defect.

---

# 7. The jEdit walkthrough

Install and restart jEdit (the plugin jar is built at start-up):

```sh
isabelle components -u .
isabelle jedit -d demo -l Demo_Core demo/Demo_Extras/Demo_Sites.thy
```

The panel is **Plugins → Isabelle Project Query → Project Query panel**, docked
bottom. The index is per project, discovered from the buffer's own path —
`demo/.isabelle-query` is what makes both sessions one project, so a query from
a `Demo_Extras` buffer can still see a locale declared in `Demo_Core`.

![the panel, first open](media/01-panel.gif)

### 7.1 Find usages, from the caret

1. Open `demo/Demo_Core/Demo_Proofs.thy`. Put the caret in **`total_append`** on
   line 46 and right-click → **Find usages of total_append**.
   One hit, `Demo_Proofs:46`, grouped under its file. Result sets open
   **collapsed**.
2. Same caret, **Find definition of total_append** — the declaration *and its
   body*, rendered in the panel, expanded. jEdit has no such view otherwise.
3. Put the caret on **`total`** (line 46 again) and **Find external usages** —
   the callers outside `Demo_Ops`, which is where `total` is declared.
4. Put the caret on **`dead_helper`** in `Demo_Core/Demo_Ops.thy:124` and
   **Find usages**: the caption reads `no usages of dead_helper` — an honest
   zero, not silence.

![find usages and find definition](media/02-usages.gif)

### 7.2 The two site finders

5. In `demo/Demo_Extras/Demo_Sites.thy`, caret on **`assoc_op`** at line 70 →
   **Find instantiations of assoc_op**. Eight leaves, each reading
   `LINE: NAME  <i>kind</i>  source`. Check the five names against §3.1: the
   `Demo_Sites:76` row says **`?`**, the `Demo_Sites:93` row says **`idem_op`**
   for a `sublocale idem_op ⊆ assoc_op`, and `Demo_Sites:129` says
   **`interp_anonymous`**, the lemma the `interpret` sits in.
6. Caret on **`weight`** at line 21 → **Find instantiations** → three
   `instantiation` rows. Now tick **Sorts** beside **Stack**: the rows already
   on screen gain `:: (weight, weight) weight` **without the query re-running**
   (the caption must not go back to "searching") and the expansion state
   survives. Untick and they go back.
7. In `demo/Demo_Extras/Demo_Code.thy`, caret on **`twice`** at line 34 →
   **Find code equations of twice**: three rows, `default`, `[code]` and
   `[code del]`, the kind in italics. Tick **Sorts** again — only the `default`
   row changes, because only its declaration writes a signature. That is the
   caveat, seen.
8. **The menu is kind-aware.** Right-click `assoc_op`: *Find instantiations*
   appears and *Find code equations* does not. Right-click `twice`: the other
   way round. Right-click **`dead_helper`**: neither. (A cold index offers
   neither — run any query once first.)

![find instantiations, and the Sorts toggle](media/03-sites-sorts.gif)

### 7.3 Search by name

9. Click into the panel's **Name:** field and type `tot` — a list drops below
   the field, best match selected, each row showing the entry's tag and locus in
   grey. UP/DOWN moves it, ESC closes it and leaves the text, clicking a row
   fills the field.
10. Type `total_append` exactly and press **ENTER**: a usages result set,
    identical in every way to one from the right-click menu.
11. Type `assoc_op` and press the **Find** button (or CTRL+ENTER): the menu
    offers *Find usages*, *Find external usages*, *Find definition* and *Find
    instantiations* — and no *Find code equations*. Type `twice` and it is the
    other way round.
12. Type `assoc_o` (a letter missing): the caption resolves it, `→ assoc_op
    (LOCALE in Demo_Types)`, and ENTER searches for the resolved name.
13. Type `zzz_nothing` and press ENTER: `no usages of zzz_nothing` — passed
    through unchanged rather than swallowed.

![the name field, completion and the kind-aware Find menu](media/04-search-by-name.gif)

### 7.4 Quick-open, peek, gestures, navigation

14. **CTRL+ALT+SHIFT+N** (`CAS+n`, go to symbol) anywhere: type `ledger` — no
    match, it is a step, not an entry — then `account`, which offers both
    `account` (RECORD) and `account_settlement` (LEMMA) and
    `account_balance_update`. Enter jumps.
15. **ALT+click** a result row → **peek**: a popup with the declaration that
    does **not** move the editor. Try it on the `Demo_Sites:93` sublocale row.
16. **Double-click** the same row → opens in the current pane. **Shift-click** →
    a new pane. **Middle-click** → a new view. **Single-click** does nothing, by
    default. All five are configurable in Plugin Options → Isabelle Query.
17. **CTRL+ALT+LEFT / CTRL+ALT+RIGHT** — navigate back and forward. These drive
    Isabelle's own `Isabelle_Navigator` jump stacks, which ship with no default
    keybinding and no toolbar exposure; the plugin exposes them rather than
    re-implementing them. Jump from `Demo_Proofs` to `Demo_Ops` to `Demo_Types`
    with double-clicks, then walk back.
18. Successive result sets stay as **siblings** in one tree, following jEdit's
    HyperSearch Results idiom. Run steps 1, 5 and 7 in a row and the tree holds
    three sets.

![quick-open, peek, and the navigator](media/05-navigation.gif)

### 7.5 SideKick, and the outline

19. Open `demo/Demo_Core/Demo_Types.thy` and show the **SideKick** dockable:
    the theory's structure, `chapter` → `section` → `subsection` →
    `subsubsection`, with the entries under each. Compare it with
    `isabelle query -R demo outline Demo_Types` (§1.3) — the same tree, one in
    the editor and one on the command line.
20. Open `demo/Demo_Core/Demo_Ops.thy` and look at lines 108–120: jEdit renders
    the `(* … *)` note as a comment and strikes through the `\<^cancel>` block.
    Then run step 1's **Find usages** on `total`: neither region contributes a
    hit, and the panel and the editor agree about what is live.

![SideKick beside the query outline](media/06-sidekick.gif)

### 7.6 The refusals

21. Put the caret on **`total`** and invoke the action
    `isabelle-project-query.find-instantiations` from Plugins → Isabelle Project
    Query (the actions are deliberately **not** gated on kind, unlike the
    menu): the caption reads `'total' is a DEF in Demo_Ops, not a locale or
    class`, and the tree does **not** gain an empty result set. A refusal is not
    an empty answer.
22. Same action on **`idem_op`**: `no instantiations of idem_op` — an honest
    zero. The two must look different, and they do.

![a refusal is not an empty answer](media/07-refusals.gif)

---

## Appendix: what was verified, and how

| check | result |
|---|---|
| `isabelle build -d demo Demo_Core Demo_Extras` | green, both sessions |
| `dev/difftest.sh demo/Demo_Core` | 298 cases, 294 clean, 4 pinned, 0 failing, 0 stale |
| `dev/difftest.sh demo/Demo_Extras` | 298 cases, 60 failing — all D2, by construction |
| `dev/entrydiff.sh demo/Demo_Core demo/Demo_Extras` | `Demo_Core` byte-identical on all four variants; `Demo_Extras` differs by exactly one line |

Every command in this file was run against the tree and every excerpt is trimmed
from its real output, never composed.

## Credit

By David Wang, with Claude Fable 5 and Claude Opus 5. (The tool it
demonstrates forks András Salamon's isabelle-query — see the repository
README for the full attribution.)
