# P10 — what P9 handed forward

**Status: IN PROGRESS** (started 2026-09-02). This is the task list for the
four items `dev/P9-STATUS.md` §"What the next phase inherits" left open, in
the order they are taken. Per-step evidence goes into commit messages under
the tags below; the closing measurements go into `dev/P10-STATUS.md`.

Every step is judged the way P9's were: hand-compute a fixture first, make
the code match, and let the harnesses say whether anything else moved.

## Ground rules for every step

- Work on the main tree, one step at a time, in the order below. Never edit
  `query_base/src` while a difftest is running.
- Dev loop is the scratch home: `USER_HOME="$FORK/.dev"`. Never register into
  the real `$ISABELLE_HOME_USER`, never touch `~/.isabelle`, never launch
  jEdit or build heaps. Another session is using the real installation, and
  jEdit is running against it — and the fork IS registered there, so a
  compile error left in the tree breaks every `isabelle` command that session
  runs. (Parking the registration for the duration of P10, as P8 did, was
  blocked by the permission classifier; instead:) edit, build, fix, and commit
  compiling states only. Never leave the tree broken while you go and think.
- Corpora come from `.dev/corpora.env`; the oracle is
  `QUERY_ORACLE=.dev/oracle/bin/query`. The matrix is
  `QUERY_ORACLE=.dev/oracle/bin/query dev/difftest.sh`; a targeted subset is
  `QUERY_CORPORA="…"` on the same command.
- Every probe refuses (exit 2) without its corpora; a refusal is not a green.
- A step may leave the gate red only in cases it names as belonging to a LATER
  step; the failing count must not go up, and the commit message records the
  counts. A pin needs a `dev/DIVERGENCES.md` entry, and a pin that agrees is a
  stale pin and fails the run.
- Small single-concern commits; body via a `.commit-msg` file in the working
  tree, removed after; trailer names the model that did the work. No absolute
  personal paths in anything committed — grep the diff for `/home/`, `~/`,
  `file://`.
- If a PreToolUse hook blocks a command, adapt or report. Never tunnel around
  it.

## The steps

### S1 — the version string carries the port's own counter `[p10-version]` — **DONE**

`CLI.version` is `UPSTREAM-scala.MINOR.PATCH`: `0.8.1-scala.0.1`. The number
in front of `-scala` names the oracle release and moves only when the oracle
moves — `dev/difftest.sh` now refuses (exit 2) if it differs from
`$ORACLE_VERSION`, so the P9 policy is a checked invariant. The `MINOR.PATCH`
after it is the port's own release history, monotone across oracle bumps; a
breaking port-side change takes the minor slot, an additive one the patch
slot. Dots rather than a second hyphen so semver reads the identifiers
numerically. `dev/p7probe.py` accepts the optional counter. Recorded in
`CLAUDE.md` §"Release status", `PLAN.md`, `MIGRATING.md`.

### S2 — `instances` / `codeqs` print a qualified locus `[p10-sites-locus]` — **DONE**

P9's S4 qualified the nine oracle-shared emitters through
`Render.locus_labels` (`[disambig-loci]`): on a corpus where two theories
share a name, every located row prints the shortest distinguishing path
suffix, `alpha/Examples:4`. The two rewrite-only verbs were left printing a
bare `site.theory` (`sites.scala`, the `names` mode and the table's `loc`
column), so on a collision corpus their locus is ambiguous where every other
verb's is not — and the round trip through `enclosing`/`at` is lost.

- [x] `Sites.cmd_instances` / `cmd_codeqs` compute `Render.locus_labels`
      over the sections once and print `Render.theory_locus(labels, path)`
      for each site — the `methods` spelling (label, no suffix), because a
      site is reported at a theory the way a caller is. `Site` carries what
      it needs to do that (the section's stored path); the plugin's grouping
      (`jedit_query`) must keep resolving the FILE by path, never by name —
      check it, and check `--sorts` and the name column are untouched.
      **`Sites.Site` gained `path`, `theory` stayed** (it is the panel's
      display key). The plugin was NOT resolving by path: `Query_Search.sites`
      grouped on `site.theory` and called `snapshot.path_of(site.theory)`,
      the last-wins name map [name-is-not-identity], so on a collision corpus
      two files' sites collapsed into one node opening at one file. It now
      keys on `site.path`, as the usages `group` already did; P6d's directory
      level is untouched (it was always path arithmetic).
- [x] Hand-computed fixture in `dev/p6bprobe.sh`: a root with two theories
      of one name in different directories, each holding an instantiation
      site and a code-equation site of the same subject. Derive the expected
      rows from the rule (label = shortest distinguishing suffix), then run.
      Pin that the old spelling is NOT printed. Pin the round trip: the
      printed locus fed to `enclosing` names the site's declaration.
      §7, 14 checks; the rows, the widths and the exit contract all matched
      the hand computation first time.
- [x] Gates: `dev/p6bprobe.sh`, `dev/p9probe.sh` (its fixture B is a
      collision corpus), `dev/p6probe.sh`, `dev/p5probe.sh`; difftest is
      unaffected by construction (no oracle for these verbs) but run the
      three smallest corpora as a canary.
- [x] Docs: the `instances`/`codeqs` rows in `README.md` / `SCANNING.md` if
      they show a locus; `dev/P9-STATUS.md` is history and stays.
      README's three sample rows carried loci the tool did not print
      (`Category3/DualCategory:66`, `HOL/List:3249`); they are now one real
      listing. `SCANNING.md` gains the clause that puts the two verbs on the
      theory side of the file/theory split.

### S3 — the namespace table is a value, not process state `[p10-namespace-value]` — **DONE**

`Namespace` (`namespace.scala`) holds four `@volatile var`s bound by
`configure` / `use_census_namespace` / `use_pure_namespace`, and read at
`usage_graph.scala:177,480`, `usage.scala:367,588`, `shape.scala:996`. It
decides whether `auto` is a proof method or a fact, so every resident host
has had to serialise around it: `Query_Server.run` restores the default
before every request under one lock, and `Query_Index.with_namespace`
rebinds under `Query_Index.synchronized`. P9 removed the other global
(`Reach.enabled`); this is the last one, and the reason two projects cannot
be queried at once in one JVM.

- [x] Introduce an immutable `Namespace.Table` (methods, attributes,
      keywords, and the derived `non_citation`), with `Namespace.census` and
      `Namespace.pure` as the two named tables and `configure` returning a
      table instead of binding one. Delete the `var`s and the three
      binders. The seam that reads `$ISABELLE_QUERY_NAMESPACE` and the built
      heaps (`CLI.configure_namespace`, D11) becomes a function from a
      session to a table.
      **`non_citation` is a `lazy val`**, so resolving a table for a `find`
      or a `grep` — a verb that never asks the router anything — still costs
      nothing, which is what the old "nothing binds at class-init" note was
      protecting. `keywords` stays readable in its own right: the shape
      width classifier's question (methods ∪ attributes ∪ keywords) is not
      the router's reject-set, which also carries `ARG_MODIFIERS`.
      `CLI.configure_namespace` → `CLI.resolve_namespace`, same policy to
      the letter.
- [x] Thread the table by parameter to the five readers and up through
      their callers: `Usage_Graph.build`, the `Usage` entry points, the
      `Shape` step scanner, and whatever `CLI.Session` / `CLI.run_result`
      need so that ONE request binds one table. The corpus-wide shape view
      (the `census` subcommand) binds the broad union for its own run and
      nothing else sees it.
      `CLI.Session.namespace` is the store; `dispatch` / `dispatch_shape`
      hand it to the six usage verbs and the five shape views, and below
      that it is an ordinary parameter defaulting to `Namespace.census`.
      The shape side carries it in **`Classify_Ctx`** rather than as one
      more argument on every metric — the context is exactly "the inputs to
      the classifier", and `build_ctx` / `analyze_proof` /
      `analyze_sections` thread it the way they already thread
      `corpus_consts`. `refs` and `graph` are not in `namespace_commands`,
      so they get `census` — which is precisely what the old dispatch left
      bound for them, and why this step moved no bytes.
- [x] Take out what the global made necessary: the restore in
      `Query_Server.run` and the rebind in `Query_Index.with_namespace`.
      Keep the server's lock only if something else still needs it (the
      index cache does — say so at the lock), and keep the plugin's single
      worker thread for the reasons `query_index.scala` gives that are not
      this one. Re-read the `Namespace` paragraph of `CLAUDE.md`
      §"Architecture" and `SCANNING.md`'s method-vs-fact section and make
      them true.
      The lock now guards **`refresh` then `provide` as one step** — `Index`
      has its own monitor for its own fields, but a second request between
      the two could hand this run sections whose fingerprint it never
      checked, and `refresh_ms` / `used` are written across the pair — plus
      a ceiling of one whole-corpus analysis in flight.
      `with_namespace` → **`with_table[A](body: Table => A)`**, which keeps
      the captured non-HOL stderr note (the panel shows it) and loses the
      `Query_Index.synchronized`. `SCANNING.md` needed nothing: its
      method-vs-fact section never mentioned binding. `METRICS.md` did — it
      named `use_pure_namespace` / `configure` — and `CONTRIBUTING.md`'s
      "one process-global fewer than P7c shipped" is now "none left".
- [x] Prove the point: a probe check (in `dev/p7probe.py`, which already
      holds a server) that two `query_run`s against two roots with DIFFERENT
      tables, issued back-to-back, each answer as they would cold — the
      case that used to need the restore. Hand-compute both answers.
      §7, four checks, on `methods sos` — the sharpest table-sensitive
      probe there is, because `Usage.cmd_methods`' refusal is a function of
      the table alone. `sos` ∈ `CENSUS_METHODS`, ∉ `PURE_METHODS`, unused by
      either corpus, so ZF (Pure floor) must answer exit 1 with empty stdout
      and a diagnostic, and the AFP entry (census union) exit 0 with "No
      uses of method 'sos' found." Both hand-computed values are pinned as
      their own checks; the parity comparisons then run in BOTH orders,
      because a leak is directional. Confirmed against the cold tool before
      the checks were written.
- [x] Gates: the FULL difftest (0 failing, no new pins — this step must be
      byte-neutral), `dev/entrydiff.sh` on the seven standard corpora,
      `dev/p9probe.sh`, `dev/p7cprobe.sh`, `dev/p7probe.sh`,
      `dev/p5probe.sh`, `dev/p6probe.sh`, `dev/p6bprobe.sh`. `scala_build`
      builds both jars; the plugin's dynamic shim needs the `isabelle scala
      -e` line in `CLAUDE.md`.
      Difftest **2149 cases: 2146 clean, 3 pinned, 0 failing, 0 stale
      pins** — identical, field for field, to the run taken before the first
      edit. entrydiff clean on all seven. p9probe 140/0, p7cprobe 45/0,
      p6probe OK, p6bprobe 45/0, p5probe OK, p7probe 91/0 (was 87).
- [x] `todo.md`: delete `[namespace-by-value]`. `CLAUDE.md`: the hazard
      paragraph becomes a statement of the invariant that replaced it.

Found on the way: `Usage_Graph.is_citation_name` has no caller in the tree
— it is public API the router inlined past. Left in place, now taking a
table like everything else; deleting it is a separate question from this
step. And `CLI.resolve_namespace` is still called with the literal verb
`"callers"` by the plugin, which is right (it wants the per-project table)
but reads as a magic string; a named constant for "a verb that reads the
table" would be an improvement nobody needs yet.

### S4 — a theory is named by its leaf `[p10-theory-leaf]`

A ROOT may address a theory in a subdirectory by path —
`theories "Nested/Nested_Fix"` — and both this engine and the reference then
call the theory `Nested/Nested_Fix` (`Discovery.session_theories`, the name
`offer`ed is the declared string). Isabelle does not: `Thy_Header.import_name`
takes the last path segment, and `Sessions`' own `global_theories` check spells
it `Path.explode(thy).file_name`. So the name in a `summary` row, in a locus
and in `theory`'s "Known theories" list is one Isabelle would not recognise,
`theory Nested/Nested_Fix` then fails to find a theory it has just listed (on
both implementations), and the label tuple doubles the directory
(`two/ex/ex/Foo`, upstream defect 1 in `dev/P9-STATUS.md`). The reachability
half is closed (`Reach.leaf_index`, `dev/p7cprobe.sh` §8b); the NAME is not.
This is the one step that moves oracle-shared output, so it is last and it
earns every record it moves.

- [ ] `Discovery.session_theories` offers the leaf: the last `/`-separated
      segment of the declared name, exactly `Thy_Header.import_name`'s rule
      (read `$ISABELLE_HOME/src/Pure/Thy/thy_header.scala` and cite the
      line). Resolution to a path is unchanged. Then follow the name through:
      the label tuple no longer doubles the directory; `Reach.leaf_index`'s
      alias for slash-spelled names may become an identity — leave it if it
      is still load-bearing for IMPORT spellings (`imports "ex/Foo"` in a
      header), delete it only if the probe proves it dead.
- [ ] `dev/DIVERGENCES.md`: a new entry, D15, with the Isabelle evidence
      above, the before/after on the fixture, and the oracle's spelling —
      and the note that upstream defect 1 closes with it on this side.
- [ ] Fixtures: `dev/p9probe.sh` fixture B addresses a theory by path — its
      hand-computed expectations were derived under the OLD naming, so
      re-derive them from the new rule (the ones that change are exactly the
      records this step claims). Add the round trip: `summary` names it,
      `theory <name>` finds it, `enclosing <locus>` returns.
- [ ] Gates: the FULL difftest. The corpora that contain a path-spelled
      ROOT theory (`FOL`: `Locale_Test/Locale_Test`; `Sequents`:
      `LK/Propositional` and three more; `CTT`: `ex/Typechecking` and three
      more) will move; pin each differing case under D15 with a glob that
      does not over-reach, and list every pinned case in the commit message.
      `dev/entrydiff.sh` over the seven standard corpora: every moved record
      must be a path-spelled ROOT theory and nothing else — print the
      moved set and say so. `dev/p7cprobe.sh` §8b must still pass.
- [ ] `todo.md`: delete `[theory-name-leaf]`. `MIGRATING.md`: a line under
      "What is deliberately different". `SCANNING.md` §session discovery:
      how a theory is named.

### Close — `dev/P10-STATUS.md` `[p10-status]`

- [ ] The closing measurements: difftest clean/pinned/failing, entrydiff,
      every probe's count. What each step established and what it left.
- [ ] `CLAUDE.md`: the verification table's case and check counts; the
      status-doc row; `todo.md` reconciled.
