# P6 — IDE features: status

`PLAN.md`'s P6 slate, built on P5's plugin: the navigator exposed, find
definition rendered in the sidebar, a peek popup, a settings surface for the
gesture table, go-to-symbol, and the outline question answered.

`query_base/` is **untouched** — `git diff 43b6976..HEAD -- query_base/` is
empty — so the P1–P3 gates (`dev/entrydiff.sh`, `dev/difftest.sh`) stand
exactly as P3 left them and were not re-run: there is nothing for them to
catch. The one file that leaves the plugin's own tree is
`jedit_query/etc/options`, which every Isabelle process reads; the CLI was
spot-checked against the oracle after adding it (`summary`, `find`, `callers
-c`, `show` on the P5 corpus — identical stdout and exit codes).

## The slate, item by item

### 1. Navigate back / forward — **landed**

`isabelle.jedit.Isabelle_Navigator` is a complete browser-style history already
(500-entry cap, a `_bypass` flag so a back-jump is not re-recorded, a
`BufferListener` per buffer re-mapping offsets as the user types before them),
fed by `Main_Plugin` from `EditPaneUpdate.PositionChanging` — so every jump in
the editor lands in it, including a Project Query result, because P5's jump
goes through `PIDE.editor.goto_file`. Nothing about the mechanism was missing;
the affordance was.

* `src/query_navigate.scala` — a thin façade with graceful degradation.
* Two toolbar buttons (U+25C0 / U+25B6) at the left of the panel's button row.
* Actions `isabelle-project-query.navigate-backwards` / `-forwards` with
  default shortcuts **CA+LEFT / CA+RIGHT** (the IDE convention; jEdit's default
  keymap binds no `CA+` chord at all, so nothing is displaced).
* The actions are **ours**, driving Isabelle's navigator. Writing a
  `.shortcut` property for `isabelle.jedit_main`'s own action names would be a
  property collision with the next Isabelle release.
* Button state follows `current.defined` / `recurrent.defined` — `History.top`
  is `Pos.none` when empty and `push` never pushes an undefined position, so
  that is exact without touching private state. Updated from the plugin's
  EditBus handler on `EditPaneUpdate`, deferred with `GUI_Thread.later`
  because two plugins handling one message have no defined order.
* No `PIDE._plugin` (the pre-prover window the panel exists to work in) ⇒
  buttons disabled, tooltips say why, actions report on the status line.

### 2. Find definition in the sidebar — **landed**

P5's `Query_Search.definition` yielded one line. It now yields the declaration
and its body as **real source lines**, one leaf each, each with its own line
number so every row navigates.

* Leaves are `thy_line .. body_end`. Deliberately **not** the engine's rendered
  `show` output: `Render.render_entry` interleaves synthetic rows (a header,
  `[+N more proof lines]`) with no line to jump to, and a tree where every row
  navigates cannot afford rows that do not.
* Deliberately **not** from `src_start`: a leading `text \<open>…\<close>`
  block is documentation and is regularly longer than the lemma under it. The
  group caption still names the full extent.
* The group caption **is** the engine's `Render.format_name_line` — extent
  annotation and all — so the panel and `isabelle query find --names` describe
  a declaration in the same words.
* Capped at `Query_Search.BODY_LIMIT` = 40 lines with a tail note row saying
  how many were left and where they end. The note still carries a line, so
  nothing in the body is unreachable.
* A name the entry map misses is resolved through `Entry.bindings` using the
  engine's own `Commands.binding_kinds` phrasing, so *find definition of
  `foo.simps`* answers with the declaration Isabelle minted it from.
* The result-set caption counts by kind: `19 lines`, not `19 hits in 1 theory`.
* Renamed to the PLAN's vocabulary: action `…find-definition`, menu item *Find
  definition of X*. Nothing had ever bound the old name (the plugin has still
  never been loaded by a jEdit).

**Result-view contract** (P5 built it, P6 completes it): one node model, one
navigation, `Result_Kind.expand_groups` decides the initial state — usages
collapsed, definition expanded. Per-node Expand/Collapse in the popup,
Expand-all / Collapse-all on the toolbar, the tree's own HyperSearch-style
arrows. All of it unchanged by this phase, which was the point of the seam.

### 3. Peek preview — **landed**

`Open_Policy.Peek`, the fifth policy and the only one that does not move the
editor.

* `src/query_peek.scala`. **`Pretty_Tooltip` is not reused**: it takes a
  `JEdit_Rendering` and a `Command.Results`, i.e. prover output for a position
  in a checked document. This popup shows *source*, from the index, and must
  work before the prover is up. What **is** reused is the layer under both and
  the part that is general — `isabelle.Popup` over `GUI.layered_pane`, placed
  by `GUI.screen_location(...).relative(...)` so a popup near the bottom of the
  screen opens upwards. Those are the three calls `Pretty_Tooltip` and
  `Completion_Popup` make.
* Content is a volatile snapshot read, a map lookup and a linear scan of one
  theory's entries (`Commands.enclosing_entry`). **None of it enters the
  citation router**, so none of it needs the per-project `Namespace` binding —
  which is exactly why a peek can exist at all. A cold index still hops to the
  worker thread first; nothing parses on the EDT.
* Peeking a result row shows the declaration the row is **inside** (a citation
  halfway down a proof is best explained by its lemma); a line no declaration
  owns falls back to a ±3-line window.
* Default gesture **ALT+click**, not CTRL+click: ctrl+click is how a `JTree`
  extends a discontiguous selection, which this panel uses for multi-node
  removal.
* Also an action (`…peek-definition`, no default shortcut), a context-menu item
  and a *Peek* entry in the results popup menu. The context-menu item peeks the
  **offset the label was built from**, not the caret — a right-click in jEdit
  does not move the caret.
* ENTER or a double click inside the popup opens the target for real; focus
  leaving it closes it.

### 4. Gesture options surface — **landed**

The verifier's red flag was that an unknown property value silently became the
built-in default, which is indistinguishable from the setting having no effect.

* `src/query_options.scala` resolves in a stated order: an **Isabelle option
  that has been changed from its own shipped default**, then the **jEdit
  property**, then the compiled-in default. An illegal value at any level is
  logged (activity log + status line, deduplicated by the exact mistake,
  because `of_click` runs on every mouse press) and resolution moves to the
  **next store**, not to the bottom — a typo in an Isabelle option must not
  also discard a good jEdit property.
* The order is **not** "Isabelle wins", deliberately. The panel answers before
  `PIDE._plugin` exists and Isabelle options are unreadable in that window.
  Making an *unchanged* Isabelle option silent is what stops the two stores
  fighting: they ship identical defaults, so before either is touched every
  layer agrees. `dev/p6probe.sh` §6/§7 fails if the three tables
  (`Open_Policy.gestures`, `plugin.props`, `etc/options`) ever drift.
* `jedit_query/etc/options` ships the table on the Isabelle side
  (`jedit_query_gesture_*`, `jedit_query_index_limit`), so `isabelle options`
  lists it and Plugin Options → Isabelle → General edits it.
* The **editable page** is a jEdit `AbstractOptionPane` (Plugin Options →
  Project Query), not an Isabelle one: `JEdit_Options.Isabelle_Options` reads
  `PIDE.options`. It cannot produce an illegal value (combo boxes over
  `Open_Policy.names`, a spinner for the limit), it names which gestures an
  Isabelle option is currently overriding, and it shows the last few rejected
  settings.

### 5. Go-to-symbol / quick-open — **landed**

* Action `…go-to-symbol`, default shortcut **CAS+n** (IntelliJ's; `CS+n` is
  jEdit's `new-file-in-mode`, which is why the plain chord is not used).
* `src/query_fuzzy.scala` is a **pure function depending on nothing** — no
  jEdit, no engine, no index — which is what makes it testable here. The rule
  is written down rather than tuned: subsequence match, positions chosen
  greedily (beatable by a cleverer alignment, but total, linear and always the
  same answer, which a ranking a user is learning has to be), then a score
  rewarding word starts, contiguity and prefixes and charging a little for
  surplus length. Ties break by shorter name then lexicographically.
* `src/query_quick_open.scala` filters `Snapshot.entry_names` **only** —
  P5-STATUS watch-out 2 — behind a 50 ms coalescing `Delay`, on the EDT. The
  index build, and only the index build, goes to the worker thread, together
  with forcing `entry_by_name` (a `lazy val` over every entry in the project,
  which must not be forced first by a cell renderer).
* Measured: 46 ms for a three-character query over 100 000 synthetic names.
* Selection jumps through `Open_Policy`, so ENTER here and ENTER in the results
  tree do the same configurable thing.

### 6. Outline — **nothing implemented, and that is the finding**

The bundled SideKick already covers the per-buffer outline of a `.thy`, and
duplicating it would be strictly worse.

* `SideKick.jar` is in the distribution's jEdit component
  (`contrib/jedit-*/jedit5.7.0-patched/jars/`), and Isabelle/jEdit **depends**
  on it: `plugin.isabelle.jedit_main.Plugin.depend.4=plugin
  sidekick.SideKickPlugin 1.8`. It is therefore always present.
* `jedit_main/plugin.props` sets `mode.isabelle.sidekick.parser=isabelle`, so a
  `.thy` buffer already gets `Isabelle_Sidekick_Default` with no configuration.
* That parser is `Isabelle_Sidekick_Structure` over
  `Document_Structure.parse_sections`: `chapter` / `section` / `subsection` /
  `subsubsection` / `paragraph` / `subparagraph` nest at levels 0–5, and every
  **theory command** (`Keyword.theory` minus `theory_end`) is a node at level 6
  — so `lemma`, `definition`, `datatype`, `fun`, … all appear, nested under
  the headings they fall in. Proof commands are `Atom`s and do not clutter it.
* Node text is `Library.first_line(command.source)` with the command keyword
  bolded (`Keyword_Asset.getShortString`); clicking navigates, via the asset's
  `Position`. `mode.isabelle.folding=isabelle` means folding uses Isabelle's
  own handler rather than the outline.
* Isabelle also registers `isabelle-context` (`parse_blocks` — nesting by
  `begin`/`end` context blocks), `isabelle-markup`, `isabelle-ml`,
  `isabelle-sml`, `isabelle-root`, `isabelle-options`, `isabelle-news`,
  `bibtex`. A user picks among them in SideKick's own parser selector.

**Gaps, and why none is worth filling:**

| gap | verdict |
|---|---|
| Node labels are the command's first source line, not the declared name | usually the same text; changing it is a SideKick-parser tweak in Isabelle's tree, not ours |
| Bound names (`.simps`, constructors, `shows` conjuncts) are absent | the *lookup* direction is covered — find-definition and go-to-symbol both resolve them through `Entry.bindings` |
| It is per-buffer, never project-wide | that is the real gap, and **item 5 fills it**: go-to-symbol is the project-wide outline |
| It needs a `Document_Model`, so it is unavailable before the Isabelle plugin starts | the plugin starts at jEdit start-up and models appear on buffer load, well before any build; a second outline for that window is not worth a second tree |

So: **no outline code was written.** If a future phase wants one, the
non-duplicating shape is a *SideKick parser of ours* registered as a service
(`sidekick.SideKickParser`), so it appears in the existing dockable's selector
rather than as a rival panel.

### 7. P5 verifier red flags — **both fixed, first**

* **`dev/p5probe.sh` §7 skipped and still printed OK.** The shim jar it reads
  is a *dynamic* module built by `JEdit_Main` at start-up, not by
  `scala_build`. The script now builds it (the documented
  `Scala_Project.plugins` one-liner) and fails if it is still missing; the
  probe counts its absence as a FAILURE.
* **`Query_Index.refreshed` had no size guard** (P5-STATUS watch-out 6). See
  below.

## The index cap — the policy, written down

`root_of` stops at the nearest ROOT, which is the right answer to "which
project is this buffer in" — but an AFP checkout carries a `thys/ROOT` of its
own, so one buffer can resolve to 10 336 theories. Discovery *alone* reads
every one of their headers, serially, in the import closure, before a body is
parsed.

* The guard runs **before discovery**, on the only measure that costs no reads:
  how many `.thy` files lie under the root. It over-counts (an orphan is never
  loaded), which is the safe direction for an upper bound. The discovered set
  is checked again afterwards, for a ROOT that reaches outside the directory.
* Over the limit the index **refuses** rather than truncating. A partial index
  answers "no usages" for a name that *is* used, and a panel that silently
  under-reports is worse than one that says it will not answer. Both ways out
  are in the message (raise the property, or drop a `.isabelle-query` marker in
  the directory you actually mean), because the caption is the only place the
  user will look.
* Default **2000**, set against the two corpora that decide it: the
  distribution's `src/HOL` is 1451 theories and ≈4 s here and must keep
  working; an AFP checkout's own ROOT is 10 336 and must not. `0` or less means
  no limit.
* Configurable as `isabelle-project-query.index-limit` (jEdit) /
  `jedit_query_index_limit` (Isabelle) / the option pane's spinner.

## Sources

New in P6:

| file | what |
|---|---|
| `src/query_options.scala` | the settings layer: two stores, precedence, validation, the warning channel |
| `src/query_navigate.scala` | the façade over `Isabelle_Navigator` |
| `src/query_peek.scala` | peek content resolution and the popup |
| `src/query_fuzzy.scala` | the ranking (pure, no dependencies) |
| `src/query_quick_open.scala` | the go-to-symbol dialog |
| `src/query_option_pane.scala` | Plugin Options → Project Query |
| `jedit_query_plugin/options.scala` | the shim subclass jEdit's BeanShell names |
| `jedit_query/etc/options` | the same table, on the Isabelle side |
| `dev/p6probe.{sh,scala}` | the headless harness |

Changed: `query_search.scala` (`Hit.note`, `Group.label`, `resolve`,
`body_hits`, the richer `definition`), `query_editor.scala` (`Peek`, the
`alt-click` gesture, resolution through `Query_Options`),
`query_dockable.scala` (nav buttons, note rendering, kind-aware counts, the
peek anchor), `query_index.scala` (the cap), `query_actions.scala`,
`query_context_menu.scala`, `query_plugin.scala` (EditBus, peek teardown), and
the plugin resources.

## What the probes prove

* `dev/p5probe.sh` — **62 checks + CLI parity, green** (P5's 57, plus the four
  new action targets it picks up automatically and the skip that became a
  check).
* `dev/p6probe.sh` — **103 checks + CLI parity, green.** Fuzzy ranking pinned
  outright; find-definition's rows checked against the source they quote *and*
  against `isabelle query show NAME -V`; peek content including the
  unowned-line fallback; the gesture table's precedence, its modifier
  precedence, and — the red flag as a test — a typo being reported once and
  falling through rather than silently defaulting; the index cap refusing and
  then recovering; the three default tables agreeing (read through
  `Options.init0()`, which is also the check that a component's `etc/options`
  is picked up at all); the option pane's BeanShell expression resolved against
  the built jar; every shipped `.shortcut` naming an action that exists and
  belonging to our own namespace; every menu entry naming something real.
* Failability confirmed: changing one gesture default in `plugin.props` gives
  one FAIL and exit 1.
* Both jars rebuild clean from deleted (`scala_build` + the dynamic-plugin
  one-liner).

**What the probes cannot reach**: every pixel. The popup, the quick-open
dialog, the toolbar buttons, the option pane's layout, the tree — and anything
that needs a real `PIDE.plugin` (the navigator's actual stacks, `goto_file`,
the Isabelle-option branch of the settings precedence). Those are below.

## Manual test checklist (David)

Nothing below has been run. The plugin has still never been loaded by a jEdit.
Register it exactly as `dev/P5-STATUS.md` describes (build green in the scratch
home first — a registered component that fails to build breaks `isabelle jedit`
start-up for every session).

P5's 13 checks still apply, with two renames: item 3's third context item is
now *Find definition of …* (plus a fourth, *Peek definition of …*), and item
12's action is `isabelle-project-query.find-definition`.

New for P6:

14. **Keymap merge.** First start after registering: Isabelle's keymap-merge
    dialog should offer `CA+LEFT`, `CA+RIGHT` and `CAS+n`. Accept them. No
    existing binding should be reported as displaced.
15. **Back / forward buttons.** The panel's first two buttons are ◀ ▶. With a
    fresh jEdit they are **disabled**. Jump to a result, then ◀ returns and ▶
    goes forward again — and the buttons enable and disable as the history
    fills and empties. `CA+LEFT` / `CA+RIGHT` do the same from the editor.
16. **The same history as Isabelle's.** Ctrl-click a PIDE hyperlink, then press
    ◀ in the panel: it should return from the hyperlink jump. One history, not
    two.
17. **Find definition, expanded.** *Find definition of X* on a lemma with a
    ten-line proof: the set opens **expanded**, the group caption reads
    `X (LEMMA) — Theory [src A..B, N lines]`, and the leaves are the lemma's
    own lines with their real numbers. Click one: it lands on that line.
18. **A long proof is capped.** Same on a 100-line proof: 40 rows then an
    italic `[+N more lines, to B]`. Click the note: it lands on the first
    omitted line.
19. **A bound name.** *Find definition of* a `.simps` or a datatype
    constructor: the label says *— a … of PARENT* and the body is the parent
    declaration's.
20. **Peek from the panel.** ALT+click a result row: a popup appears **under
    the pointer**, showing the enclosing declaration, and the editor does not
    move. ESC closes it; clicking elsewhere closes it; ENTER inside it opens
    the target in the active pane.
21. **Peek from the buffer.** Right-click an identifier → *Peek definition of
    X*: the popup appears under **that identifier**, not under the caret (put
    the caret somewhere else first to check).
22. **Peek near the bottom edge.** Peek a row with the panel at the bottom of
    the screen: the popup must open **upwards**, fully on screen.
23. **Go to symbol.** `CAS+n`: a dialog with a text field. Type three or four
    letters of a lemma name; matched characters are **bold**, the tag and
    `Theory:line` are greyed at the right. ↑/↓ move, ENTER jumps, ESC closes,
    clicking away closes. Typing fast must not stutter.
24. **Go to symbol, cold.** Close the panel, restart jEdit, and press `CAS+n`
    before any query: the caption shows `indexing … n/m` and the list fills
    when it finishes. The dialog stays responsive throughout.
25. **Option pane.** Plugins → Plugin Options → **Project Query**: six combo
    boxes and a spinner. Change *Single click* to `peek`, OK, then single-click
    a result: a popup, not a selection. Change it back.
26. **Option pane before the prover.** The same page must open and save with
    the Isabelle plugin **not** started (unload it in the Plugin Manager, or
    open the page during start-up).
27. **A typo is reported.** In `$ISABELLE_HOME_USER/jedit/properties`, set
    `isabelle-project-query.gesture.double-click=curent`, restart, and
    double-click a result: the status line and
    `$ISABELLE_HOME_USER/jedit/activity.log` must both name the property and
    the value. The gesture falls back to `current`. Repeat clicks must not
    repeat the message.
28. **An Isabelle option wins when changed.** `isabelle options -x
    jedit_query_gesture_middle_click=peek` (or the Isabelle General options
    page), restart, middle-click a result: a popup. Set it back to `new-view`
    and it stops winning — the jEdit property decides again.
29. **The index cap.** Open a buffer under `src/HOL/Library` (which resolves to
    all of `HOL`, ~1450 theories) and query it: it must **work**, with visible
    progress. Then set `isabelle-project-query.index-limit=100`, refresh, and
    the caption must read `project too large: … limit 100 …` naming both ways
    out — and nothing must hang.
30. **Peek and the panel do not leak.** Open a peek, then close the panel; open
    a peek, then unload the plugin from the Plugin Manager. No stray popup, no
    exception in `activity.log`.
31. **SideKick.** Open the SideKick dockable on a `.thy` and confirm the finding
    above holds for a real file: headings nest, every `lemma` / `definition`
    appears, clicking navigates. If it does **not**, the finding is wrong and
    the outline item reopens.

## Watch-outs

### For P4 (the shape family)

1. **`Result_Kind` is the extension point.** A `shape` view in the panel is a
   third `Result_Kind` plus a `Group`/`Hit` producer — `expand_groups`, the
   count caption (`Query_Dockable.count_caption`) and the leaf renderer are the
   three places a new kind touches, and nothing else. Do not add a second tree.
2. **`Hit.note` already exists** for a row that is *about* the source rather
   than of it. A shape summary row (`M3 = 7`) is that shape; reuse it rather
   than adding a parallel flag.
3. **Everything shape needs is on the worker thread's side of the fence.** The
   metrics read `live_source` / `outer_source`, which P3's note says are
   recomputed per call; a panel that renders them per keystroke needs its own
   cache. The peek popup deliberately reads only `sec.lines`.
4. **The CLI must say "not yet ported"** (PLAN P4) — and if a `shape` verb is
   surfaced in the panel before the CLI verb exists, the panel must say the
   same thing rather than showing an empty result set.

### For P7 (server + polish)

5. **The index is the server's seed and now has a refusal path.**
   `Query_Index.refreshed` can `error` (empty root, over the limit). A server
   command must map that to a protocol error, not to an empty answer — the
   whole point of refusing rather than truncating is lost if it becomes "no
   results" over a socket.
6. **`Query_Options` reads jEdit properties.** It is in the plugin module, not
   the engine, so nothing on the CLI/server side depends on it — keep it that
   way. If the server ever needs a limit, it needs its own, from an Isabelle
   option or the command line.
7. **The component now ships `etc/options`.** Every Isabelle process reads it,
   including `isabelle build`. A malformed line there breaks far more than this
   plugin; treat it like `etc/settings`.
8. **`Query_Index.background` is still a single thread.** Quick-open and peek
   both queue on it, so a peek raised while a 1400-theory index is building
   waits for it. That is correct (the `Namespace` binding demands one thread)
   but it is the thing a warm server would fix, and the reason to fix it.
9. **The shortcut defaults will meet a real keymap.** `CA+LEFT`, `CA+RIGHT`,
   `CAS+n` are free in jEdit's default keymap, not necessarily in a user's.
   Isabelle's `keymap_merge` handles it by asking; if P7 ships more, check the
   same way (`contrib/jedit-*/keymaps/jEdit_keys.props`).
10. **`README.md` / `CLAUDE.md` still describe the Python tool.** P7 rewrites
    them; the plugin's surface to document is `dev/P5-STATUS.md` §"Registering
    it for real" plus this file's slate.
