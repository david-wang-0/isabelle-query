# P5 — the jEdit plugin: status

`PLAN.md`'s P5 phase: a `jedit_query` component holding a **Query dockable**
modelled on jEdit's HyperSearch Results window, a right-click **Find Usages**
via `DynamicContextMenuService`, and a **warm per-project index** with
dirty-buffer overlay — the seed of the CLI's future server mode.

`query_base/` is **untouched**.  No engine source changed in this phase, so the
P1–P3 gates (`dev/entrydiff.sh`, `dev/difftest.sh`) stand exactly as P3 left
them and were not re-run: there is nothing for them to catch.  The CLI was
smoke-tested after registering the new component (`isabelle query -R … summary`,
`… callers R -c`) to confirm the extra classpath service changes nothing.

## What is implemented

### Two modules, as the linter template prescribes

| module | jar | built by |
|---|---|---|
| `jedit_query/etc/build.props` | `$JEDIT_QUERY_HOME/lib/classes/isabelle_query_plugin.jar` | `isabelle scala_build` |
| `jedit_query/jedit_query_plugin/build.props` | `$JEDIT_SETTINGS/jars/isabelle_jedit_query.jar` | `isabelle.jedit.JEdit_Main` at start-up |

Only the second is scanned by jEdit's `PluginJAR` loader, so `dockables.xml`,
`services.xml`, `actions.xml` and `plugin.props` live there and the classes they
name are one-line subclasses of the component's — the `jedit_main/services.scala`
idiom.  `JEdit_Query_Plugin` (a classpath service in `etc/build.props`) is how
this component joins `Scala_Project.plugins`, which is the walk `JEdit_Main`
makes.  Offline, that build is reachable without a display:

```sh
isabelle scala -e '{ isabelle.Isabelle_System.init();
  isabelle.Scala_Project.plugins.foreach(p => println(p.context().build())) }'
```

The dockable is **`isabelle-project-query`**, not `isabelle-query`:
Isabelle/jEdit already owns that name for its prover Query panel (find_theorems
/ find_consts / print_context).

### Sources

| file | what |
|---|---|
| `src/query_word.scala` | the identifier under the caret, from buffer text, on the engine's own word grammar |
| `src/query_index.scala` | project discovery from a path, the warm index, the worker thread, the namespace seam |
| `src/query_search.scala` | the result model (`Result_Kind` / `Result` / `Group` / `Hit`) and the two searches |
| `src/query_editor.scala` | `Open_Policy`, the gesture table, and the jump |
| `src/query_dockable.scala` | the results tree |
| `src/query_context_menu.scala` | `DynamicContextMenuService` |
| `src/query_actions.scala` | the keyboard front door |
| `src/query_plugin.scala` | lifecycle (deliberately thin) |
| `src/jedit_query_plugin.scala` | where the shim submodule lives |

### The panel, as implemented

* Tree: invisible root → one **bold** node per query (`usages of R (locale in
  Abstract_Completeness) -- 23 hits in 1 theory`) → one **bold** node per theory
  with its own count → one leaf per line, `273: …` with the cited name in bold.
* Counts are computed by walking the subtree at render time
  (`HyperSearchResults.traverseNodes`), so deleting a node leaves the captions
  right.
* Previews are `Symbol.decode`d — the panel shows what the editor shows.
* Successive queries **stack as siblings** (a `Stack` toggle turns that off);
  appending uses `nodesWereInserted`, so earlier sets keep their expansion.
* Toolbar: status caption (clipping, in a `BorderLayout` so it cannot push the
  buttons out), `Refresh`, `Expand`, `Collapse`, `Clear`, `Stack`.
* Right-click popup: Open / Open in new pane / Open in new view / Expand /
  Collapse / Remove / Clear all.  Keys: ENTER, SPACE, DELETE.
* Selection is discontiguous (several nodes can be removed at once) but every
  jump uses the **lead** path, so a multiple selection cannot open several files.
* Status line reports the index: `indexing ... 34/73`, then
  `AODV: 73 theories, 2467 entries (812 ms)`, or the failure.

### Right-click, and the same on a key

`Query_Context_Menu` contributes three items on an identifier in a `.thy`
buffer — *Find usages of X*, *Find external usages of X*, *Show declaration of
X* — and `null` everywhere else, which is the service's contract for "nothing to
contribute".  A null `MouseEvent` (keyboard-raised menu) falls back to the
caret, and an offset under the mouse is only used when the event came from the
text area's painter: both follow `isabelle.jedit.Context_Menu`.

Actions (`actions.xml`, no default keybindings — bind them yourself):

| action | label |
|---|---|
| `isabelle-project-query.find-usages` | Find usages at caret |
| `isabelle-project-query.find-external-usages` | Find external usages at caret |
| `isabelle-project-query.show-declaration` | Show declaration at caret |
| `isabelle-project-query.show-panel` | Show Project Query panel |
| `isabelle-project-query.refresh-index` | Refresh project index |

### The index API (this is what P6 calls)

```scala
object Query_Index {
  def root_of(file: JPath): Option[JPath]     // which project a buffer belongs to
  def for_file(file: JPath): Option[Query_Index]
  def apply(root: JPath): Query_Index          // one per project, memoised
  def background(body: => Unit): Unit          // the single worker thread

  sealed abstract class Status                 // Idle | Indexing | Ready | Failed
  final class Snapshot {
    def sections: List[Theory_Section]         // what every engine verb takes
    def definition(name: String): Option[(String, Entry)]   // find-definition, peek
    def entry_names: List[String]              // quick-open / go-to-symbol
    def theory_names: List[String]
    def section(theory: String): Option[Theory_Section]
    def path_of(theory: String): Option[JPath]
    def entry_by_name / by_theory              // the maps behind the above
  }
}

final class Query_Index {
  def refreshed(overlay: Map[JPath, String],
                progress: Status => Unit = _ => ()): Snapshot   // worker thread only
  def with_namespace[A](body: => A): A         // binds the citation table for THIS project
  def invalidate(): Unit
  def status: Status ; def snapshot: Option[Snapshot] ; def note: String
}
```

`definition` and `entry_names` are direct lookups on already-parsed data — a
find-definition or a quick-open picker is a map read, not a scan.

## What the headless probe proves

`dev/p5probe.sh` (57 checks, all green).  There is **no X server and no
`xvfb-run`** on this machine — `command -v xvfb-run` and `command -v Xvfb` both
come back empty — so the runtime smoke test in the P5 brief was **skipped
entirely**, as the brief directs, and jEdit was never launched.  What could be
checked without a display was, and the probe is the record of it:

1. **The caret-word grammar** — 17 cases: mid-word, first/last character, just
   past the end (both after punctuation and after a name), a prime, a
   `\<^sub>` name from an encoded file *and* from a decoded buffer (`bar` +
   U+21E9 + `1`), a qualified `List.map_ident` → `map_ident`, a numeral, a bare
   `\<Longrightarrow>` (rejected — syntax, not a name), blank and empty lines.
2. **Project discovery** — a theory resolves to its session directory; a path
   under no session resolves to nothing.
3. **The index** — build (2 theories, 81 entries, 192 ms cold), warm refresh
   reuses *every* parsed section by identity (12 ms), `invalidate` forces a
   reparse, the theory set equals `Discovery.theories`.
4. **Usages** — hit count equals `Usage.find_callers`, grouping is non-empty and
   path-resolvable, every hit line lies inside its theory and its text is the
   source line it points at, the declaration site is found.
5. **The dirty-buffer overlay** — hand back one theory's text with an extra
   `using R` line as if unsaved: exactly one more hit (24 vs 23), the new entry
   is visible, every *other* section is the same object (the cache worked), the
   file on disk is untouched, and dropping the overlay restores 23.
6. **The per-project namespace** — a HOL index and a CTT index queried
   alternately give 211 methods, then 37, then 211 again.  That is P3's
   watch-out ("two open sessions disagreeing about `auto`") as a test.
7. **The jEdit resources** — the BeanShell expressions in `dockables.xml`,
   `services.xml` and `actions.xml` are read back out of the **built jar** and
   resolved against it: every constructed class exists with the arity the
   expression passes, every action target is a static method of the arity the
   action passes, `dockables.xml`'s NAME equals `Query_Dockable.NAME`, every
   action has a label, `plugin.props` ships the gesture table, and the plugin
   class it names extends `EditPlugin`.  This is the closest a display-less
   machine gets to loading the plugin.

Finally the loop closes against the CLI: the probe prints its subject and hit
count and the script compares them with `isabelle query callers <subject> -c` —
`callers R -- plugin 23, isabelle query 23`.  The probe alone proves the plugin
agrees with the engine call it makes; this proves the whole path agrees with the
gate-verified verb.

**What the probe cannot reach**: everything Swing.  Tree rendering, the popup,
the gestures, `PIDE.editor.goto_file`, dockable registration, and plugin
load-time behaviour are all in the manual checklist below.

## Manual test checklist (David)

Nothing below has been run.  The plugin has never been loaded by a jEdit.

### Registering it for real

The scratch home exists precisely because **a registered component that fails to
build breaks `isabelle jedit` start-up for every session** — the tool runs
`isabelle scala_build` before it launches, and a compile error there is fatal.
So: build green in the scratch home first (that is the state this branch is in),
then register.

```sh
FORK=<this checkout>

# 1. build in the scratch home and confirm both jars appear
USER_HOME="$FORK/.dev" isabelle scala_build
USER_HOME="$FORK/.dev" isabelle scala -e '{ isabelle.Isabelle_System.init();
  isabelle.Scala_Project.plugins.foreach(p => println(p.context().build())) }'
ls "$FORK/.dev/.isabelle"/*/jedit/jars/isabelle_jedit_query.jar

# 2. register into your real home (release step)
isabelle components -u "$FORK"

# 3. restart jEdit when you choose.  The plugin jar is built at start-up.
```

To remove it again:

```sh
isabelle components -x "$FORK"      # then restart jEdit
rm -f "$ISABELLE_HOME_USER/jedit/jars/isabelle_jedit_query.jar"
```

The second line matters: `$JEDIT_SETTINGS/jars` is *not* cleaned when a
component is deregistered, and a stale plugin jar whose library jar has gone
away will fail to load noisily on every start-up.

### The checks

Open an AFP entry (say `AODV/Aodv.thy`) after a restart.

1. **Plugin loaded.** Plugins → Plugin Manager lists *Isabelle Project Query*
   with no error.  `$ISABELLE_HOME_USER/jedit/activity.log` has no exception
   mentioning `isabelle.jedit_query`.
2. **Panel opens.** Plugins → Isabelle Project Query → *Project Query panel*.
   The status line names the project and either `no index yet` or a count.
3. **Right-click.** Right-click on a lemma name in a proof: three items appear
   (*Find usages of …*, *Find external usages of …*, *Show declaration of …*).
   Right-click in whitespace or on `\<Longrightarrow>`: **no** Query items.
   Right-click in a non-theory buffer (a `ROOT`, a `.scala`): **no** Query items.
4. **First query.** *Find usages*: the caption goes to `indexing ... n/m`, the
   UI stays responsive throughout (this is the EDT check), and a result set
   lands with its per-theory nodes **collapsed**.
5. **Expansion.** The theory node's arrow expands it — the same arrow the
   HyperSearch Results panel uses.  Toolbar `Expand` / `Collapse` do all of them.
6. **Navigation.** Double-click a hit → the file opens in the **active** pane at
   the right line.  Shift-click → a **new pane** (split).  Middle-click → a new
   view.  Single click only selects.  After a jump, the *navigate-backwards*
   action returns you — the jump is recorded on `Isabelle_Navigator`.
7. **Stacking.** Run a second query: it appears as a sibling and the first one
   keeps its expansion state.  Untick `Stack`: the next query replaces.
8. **Dirty buffer.** Type `using <some_fact>` into a theory **without saving**,
   then find usages of that fact: the unsaved line is in the results and clicking
   it lands on it.
9. **A second project.** Open a theory from a *different* entry (or from
   `src/CTT`) in the same jEdit and query it.  Both projects must keep their own
   answers; watch for `auto`/`iff` being classified differently in a non-HOL
   session — that is the per-index namespace working.
10. **Warmth.** Re-run the same query: it should be near-instant (the sections
    are cached; only changed files reparse).
11. **Keyboard.** Bind a key to `isabelle-project-query.find-usages`
    (Global Options → Shortcuts) and check it does the same as the menu item.
12. **Declaration view.** *Show declaration of X* opens a result set **expanded**
    on the declaration line.
13. **Teardown.** Close the panel and reopen it; close the view; unload the
    plugin from the Plugin Manager.  No exceptions in `activity.log`.

## Deferred to P6 — precise handoff

David's mid-phase requirements landed as follows.

**Landed in P5**

* One node model for every result kind, with per-kind default expansion
  (`Query_Search.Result_Kind.expand_groups`): usages collapsed, definition
  expanded.  Both kinds share `target_of` / `goto_selected`, so navigation is
  written once.
* The tree's own expand arrows (`setShowsRootHandles` + jEdit's angled line
  style), plus per-node Expand/Collapse in the popup and Expand-all /
  Collapse-all on the toolbar.
* One configurable gesture indirection: `Open_Policy.of_click` reads
  `isabelle-project-query.gesture.<gesture>` from jEdit properties, defaults in
  `plugin.props`.  Defaults: single-click = none, **double-click = current
  pane**, **shift-click = new pane**, middle-click = new view, enter = current
  pane.  Adding a policy is one `case object` plus one `case` in
  `Query_Editor.goto`.

**Deferred, and how to land it**

1. **Peek preview.** `Open_Policy` has three policies plus `Nothing`; a
   `case object Peek` and a matching `case` in `Query_Editor.goto` is the whole
   wiring, and `plugin.props` then documents `peek` as a gesture value.  The
   rendering is the tooltip machinery (`Pretty_Tooltip`), which is P6's job.
2. **Find-definition as a real view.** `Query_Search.definition` currently
   yields the declaration *line*.  P6 replaces the leaf's preview with the
   engine's `show` output (declaration + body) — a change to `hit_html` and to
   `Hit`, not to the tree, the navigation or the kind machinery.
3. **Isabelle options rather than jEdit properties.** The gesture table is jEdit
   properties because the panel must work before the prover is up. If P6 wants
   them in Isabelle's Plugin Options page, add `jedit_query/etc/options` and read
   through `PIDE.options` **with a jEdit-property fallback** — the panel is
   usable while `PIDE._plugin` is still null and must stay so.
4. **Quick-open / go-to-symbol.** `Snapshot.entry_names` is the candidate list
   and `Snapshot.definition` the resolver; what is missing is a fuzzy matcher
   and a dialog.  No index work is needed.
5. **Back/forward exposure.** `Isabelle_Navigator` already records our jumps
   (via `goto_file`).  P6 only has to give `navigate-backwards` /
   `navigate-forwards` a default keybinding and a toolbar affordance.

## Watch-outs for P6

1. **Never call an engine verb off `Query_Index.background`.**  The single
   worker thread is what makes the per-project `Namespace` binding safe; a second
   thread reintroduces exactly the bug `with_namespace` exists to prevent.  If
   P6 genuinely needs parallelism (a fuzzy matcher over a big index), keep it out
   of the engine or give the index a read-only copy of the table.
2. **`with_namespace` re-runs `CLI.configure_namespace` on every call**, which
   walks the project's ROOT files (~10 ms for a session dir).  That is deliberate
   — one definition of the policy — but it is the wrong shape for a per-keystroke
   quick-open filter.  Filter on `entry_names`, which needs no table.
3. **The keyword union is the one thing that is not incremental.**  A `keywords`
   clause typed into an unsaved header is not seen until the file is saved; the
   union is part of the cache key, so when it does change *every* section
   reparses.  A P6 feature that reindexes per keystroke will pay that whenever a
   header is edited.
4. **`live_source` / `outer_source` are recomputed per call** (P3's note).  A
   view that redraws from them per keystroke needs its own buffer-level cache;
   do not put a `lazy val` back on `Theory_Section`.
5. **Theory-name keying is last-wins.**  Per session that is invisible, but an
   index rooted at a whole AFP checkout has several `Misc`es and
   `Snapshot.by_theory` will keep one of them.  `root_of` stops at the nearest
   ROOT, so this only bites a project that really does declare duplicates.
6. **A big root indexes eagerly.**  `root_of` walks up to the nearest ROOT, so a
   buffer under `src/HOL/Library` resolves to the whole of `HOL` (~500
   theories).  It is a background thread and the panel shows progress, but there
   is no cap; if that proves annoying, cap it in `refreshed` rather than in
   `root_of` (the root is the right answer, the eagerness is not).
7. **`Query_Dockable.instances` is keyed by `View` and cleared in `exit()`.**  If
   P6 adds a second dockable, share the registry rather than adding a parallel
   one.
8. **`plugin.props` gesture values are validated by falling back**, not by
   erroring: an unknown value silently means the built-in default.  If P6 adds
   an options page, validate there.
