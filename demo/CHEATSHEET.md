# CHEATSHEET — `demo/`, one line per thing

`alias Q='isabelle query -R demo'` from the repo root; jEdit panel = Plugins → Isabelle Project Query. Output shown: [DEMO.md](DEMO.md).

## Sessions and discovery
- `demo/ROOTS` — `Q summary --by-session` → 2 sessions, 6 theories, 683 lines
- `demo/.isabelle-query` — one project across both sessions; jEdit needs it
- `Demo_Core/ROOT:15` `quick_and_dirty` — the `sorry` needs it, batch build else refuses
- `Demo_Core/ROOT:17` declares one theory — `Q deps -r Demo_Proofs` → 3 more, via imports
- `Demo_Sketch.thy:18` sketch_double — `Q find sketch_` → nothing; `dump-theories demo` → 6 files, not 7

## Demo_Types.thy — one of every declaration kind
- `Demo_Types:13` chapter — `Q outline Demo_Types` → four heading depths, entries under each
- `Demo_Types:31` tree, `:32` is_leaf, `:33` left — `Q show left` → "is a selector of tree"
- `Demo_Types:49` account — `Q show balance` → "is a field of account"
- `Demo_Types:57` weight, `:62` assoc_op — right-click → Find instantiations → 3 arity, 8 site rows
- `Demo_Types:66` idem_op — `Q instances idem_op` → honest zero, exit 0
- `Demo_Types:77` "functor" — `Q instances functor` → quoted name kept at the site
- `Demo_Types:85` f\<^sub>1 — type `f\<^sub>1` in the Name field → markup symbol resolves
- `Demo_Types:98` cost, fuel — `Q find 'cost|fuel' --names` → four AXIOM entries, four lines

## Demo_Ops.thy — bound names, decoys, dead code
- `Demo_Ops:23` evens, `:24` odds — `Q show odds` → "is declared together with evens"
- `Demo_Ops:42` start, `:43` hop — `Q show hop` → "is an introduction rule of reachable"
- `Demo_Ops:50` total_eq — right-click → Find definition → the definition and its body
- `Demo_Ops:52` total_append — `Q show total_append --comments-only` → declaration + proof notes
- `Demo_Ops:60` total_nil, `:61` total_cons — `Q show total_nil` → "named conjunct of total_facts"
- `Demo_Ops:72` mono — `Q callers mono` → three rows: chained, `rule` arg, `simp add:` arg
- `Demo_Ops:84` simp — `Q methods` → simp 25, rule 12; a method is never a citation
- `Demo_Ops:88` assoc_right — `Q enclosing Demo_Ops:88` → `▸ target assoc_op`, via `(in …)`
- `Demo_Ops:94` g_absorb — `Q enclosing Demo_Ops:95` → `▸ context idem_op`, via nesting
- `Demo_Ops:108` decoy_total, `:119` cancelled_total — `Q grep --with-comments decoy` → 0 live, 7 in comments
- `Demo_Ops:124` dead_helper — `Q unused --by-theory` → listed; deliberately dead

## Demo_Legacy.thy — the uncited import
- `Demo_Legacy:15` paragraph, `:26` subparagraph — `Q outline Demo_Legacy` → exit 1, oracle parity
- `Demo_Legacy:28` legacy_scale — `Q deps Demo_Proofs` lists it, `Q refs Demo_Proofs` never reaches it
- `Demo_Legacy:48` legacy_twice — `Q callers twice` → 3 rows; `ISABELLE_QUERY_REACHABILITY=off` → 4.
  The arrow runs `Demo_Extras` → `Demo_Core`, so this `twice` is a bound variable and cannot cite `Demo_Code`'s

## Demo_Proofs.thy — proofs, chain, wide step, open goal
- `Demo_Proofs:26` `apply (rule hop)` — `Q shape summary` → apply scripts counted beside the Isar
- `Demo_Proofs:32` reachable_suc — right-click → Find usages → two rows, both in reachable_four
- `Demo_Proofs:45` total_shift, `:49` total_snoc_ge — `Q callers total_append [-r]` → 1, then 5 deep
- `Demo_Proofs:65` total_grows — `Q shape lemma total_grows` → per-step table plus M6 curve
- `Demo_Proofs:95` account_settlement — `Q grep total Demo_Proofs:95..109` → span-scoped live search
- `Demo_Proofs:100` ledger — `Q shape widest -N 4` → w2 91, next is 21; paste into `Q lines`
- `Demo_Proofs:118` — `Q enclosing -b Demo_Proofs:118` → `▸ have 118..122` inside tree_depth_pos
- `Demo_Proofs:144` sorry — `Q sorry` → one hit, `reachable_le`

## Demo_Sites.thy — the `instances` naming chain
- `Demo_Sites:21` nat — `Q instances weight` → row named by the type constructor
- `Demo_Sites:39` prod — `--sorts` / panel Sorts box → `prod :: (weight, weight) weight`, no re-query
- `Demo_Sites:58` — `Q instances heavy` → one `instance` arity row; the `class … +` is not a site
- `Demo_Sites:70` nat_max, `:73` nat_min — named by the written qualifier; kinds differ
- `Demo_Sites:76` — bare `interpretation` at top level → row named `?`
- `Demo_Sites:81` rev_functor — `Q instances functor` → the quoted locale, at the site
- `Demo_Sites:93` — `sublocale idem_op ⊆ assoc_op` → site of assoc_op, named idem_op
- `Demo_Sites:103` sub — `sublocale sub:` → qualifier beats the enclosing block
- `Demo_Sites:108` — bare `interpretation` inside a locale → named holder, the target
- `Demo_Sites:122` times_sg, `:129` — in-proof `interpret`: qualifier, else the lemma

## Demo_Code.thy — every code-equation kind
- `Demo_Code:24` twice — `Q codeqs twice` → `default` row; `--sorts` → only this row gains `::`
- `Demo_Code:34` twice_add — right-click `twice` → Find code equations → the `[code]` row
- `Demo_Code:40` thrice_def — `Q codeqs thrice` → `declare` row, named by its label
- `Demo_Code:45` thrice_code — same listing, `lemmas … [code] = …` row
- `Demo_Code:52` sumlist.simps — `Q codeqs sumlist` → `declare f.simps [code]`
- `Demo_Code:62` twice.simps — `Q codeqs twice` → `[code del]`, retraction shown and marked
- `Demo_Code:75` shift, `:78` shift_code — `Q codeqs shift` → one row of two; mixfix head missed
- `Demo_Code:101` elems_NDlist — `Q codeqs NDlist` → `[code abstract]`; `codeqs elems` → exit 1
- `Demo_Code:115` marked_const — `Q find marked_const` → found here, invisible to the oracle
- `Demo_Code:115` — `dev/entrydiff.sh demo/Demo_Extras` → one line, `+…:DEF:marked_const` (D2)

## Corpus-wide
- `Q shape summary` → per-theory maxes; `Q shape census` → 31 JSONL records
- `Q unused -c` → 24; `--by-theory` groups, `--keep a,b` prunes, `-r` cascades
- `Q largest -N 6` → 21 lines down to 12; `Q graph imports` → cross-session import absent
- `Q instances total` → exit 1, "is a DEF …, not a locale or class"
- `python3 query_base/lib/scripts/query_client.py -R demo callers total_append -r` → 40 ms vs 1100 ms
- `dev/difftest.sh demo/Demo_Core` → 298 cases, 294 clean, 4 pinned, 0 failing

## jEdit, panel only
- Name field, type `tot` → completion popup, tag and locus per row; ESC keeps the text
- Name field, ENTER → Find usages of the resolved name
- Name field, `assoc_o` → fuzzy resolves and says so; `zzz_nothing` → "no usages", not silence
- Name field + Find button (CTRL+ENTER) → menu gated by kind; locale ≠ constant
- `CAS+n` → quick-open; type `account` → three entries offered
- Result row: double → current pane, shift → new pane, middle → new view, single → nothing
- Result row: ALT+click → peek popup; the editor does not move
- `CA+LEFT` / `CA+RIGHT` → Isabelle's own navigate back / forward stacks
- SideKick on `Demo_Types.thy` → the same tree as `Q outline Demo_Types`
- Caret on `total`, action find-instantiations → refusal caption, no empty result set
