# P10 — what P9 handed forward, closed

**Status: DONE** (2026-09-02, one day; plan in `dev/P10-PLAN.md`). P9 left
four items to the next phase. This phase took all four, in the order the
plan gives, and closed each with its harness. The tool is `0.8.1-scala.0.1`.

Method as in P9: the orchestrating session wrote the plan and one brief per
step, an Opus agent implemented each step with the harnesses as its gate,
and every step's evidence is in its commits under the tag named below.

## The gate, step by step

| step | tag | difftest (cases / clean / pinned / failing) | what else moved |
|---|---|---|---|
| S1 version | `[p10-version]` | 2,149 / 2,146 / 3 / 0 | p7probe 87 → 87 |
| S2 sites locus | `[p10-sites-locus]` | canary on 3 corpora: 921 / 918 / 3 / 0 | p6bprobe 31 → 45 shell checks |
| S3 namespace value | `[p10-namespace-value]` | 2,149 / 2,146 / 3 / 0 — **identical** | p7probe 87 → 91 |
| S4 leaf names | `[p10-theory-leaf]` | 2,149 / 1,968 / **181** / 0, 0 stale | p9probe 140 → 153; entrydiff 28/28, moved set **empty** |

Every probe refuses without its corpora; every count above is a run that
had them.

## What each step established

### S1 — the version string carries the port's own counter

`CLI.version` is `UPSTREAM-scala.MINOR.PATCH`, now `0.8.1-scala.0.1`. The
number in front of `-scala` names the oracle release and moves only when the
oracle moves; `dev/difftest.sh` refuses (exit 2) if it differs from
`$ORACLE_VERSION`, so the P9 policy is a checked invariant. The counter after
`-scala.` is the port's own release history — a breaking port-side change
takes the minor slot, an additive one the patch slot — and it is monotone,
never reset when the oracle number moves. Dots, not a second hyphen, because
semver reads dot-separated numeric identifiers numerically.

### S2 — `instances` / `codeqs` print a qualified locus

`Sites.Site` carries the section's stored path, and the two verbs print
`Render.theory_locus` of it — the `methods` spelling, label without suffix,
because a site is reported at a theory the way a caller is. Labels are
computed once per run. On the fixture (two `Examples` in `alpha/` and
`beta/`, sites on the same line numbers) every expected row, including column
widths, was written down first and matched first run.

**Found on the way, and fixed:** the jEdit dockable grouped site results by
theory NAME and resolved the file by name (`snapshot.path_of(site.theory)`,
last-wins). With two same-named theories the two files' sites collapsed into
one node and every row navigated into whichever file the name mapped to. It
now keys on the path, as the usages view has since `[disambig-loci]`.

Also: `README.md`'s three sample site rows carried loci no invocation ever
printed; replaced with a real listing. And a harness trap: a RELATIVE
`$QUERY_ORACLE` breaks the nine cwd-discovery / marker-file cases with exit
127, since they run the oracle from inside a corpus — `dev/difftest.sh` now
makes the path absolute up front.

### S3 — the namespace table is a value, not process state

`Namespace.Table(methods, attributes, keywords)` with a lazy `non_citation`;
`Namespace.census` and `Namespace.pure` as the two named tables. The four
`@volatile var`s and the three binders are gone. `CLI.resolve_namespace`
returns a table under the same policy `configure_namespace` bound one under
(same verb set, same unconditional broad union for the corpus-wide shape
view, same `$ISABELLE_QUERY_NAMESPACE=committed` read through the session's
environment, same stderr note), and it reaches the five readers by parameter:
`Usage_Graph.build_call_graph`'s shadowed set, `is_citation_name`,
`Usage.find_callers`, `Usage.cmd_methods`, and `Shape.Classify_Ctx`. `refs`
and `graph` receive `census`, which is what the old dispatch left bound for
them — the structural reason the step moved no bytes.

The server's restore-before-every-request is deleted; `engine_lock` stays and
now says what it guards (`refresh` then `provide` as one step, and one
whole-corpus analysis in flight). The plugin's `with_namespace` became
`with_table` and lost its `synchronized`; the single worker thread stays for
the reasons that were never the namespace. `dev/p7probe.py` §7 proves the
point: `methods sos` on ZF (Pure floor: exit 1) and on an AFP entry (census:
exit 0, "No uses"), back-to-back on one connection, in both orders, each equal
to its cold answer. Two projects can now be queried at once in one JVM.

### S4 — a theory is named by its leaf

`Discovery.import_name` is Isabelle's own rule, cited: `Sessions.Base` names
every ROOT-declared theory `Thy_Header.import_name(thy)`
(`src/Pure/Build/sessions.scala:650`), `import_name` is `Url.get_base_name`
(`src/Pure/Thy/thy_header.scala:78-82`), and that is the substring after the
last of `:/\` (`src/Pure/General/url.scala:117,122-125`). A `.` is not a
separator, so `HOL.List` stays one name; `.thy` is not stripped, Isabelle
errors instead, and the two degenerate spellings keep the declared string
here. Resolution to a file is untouched. `Reach.leaf_index` was provably dead
after this and is deleted; `Reach.theory_leaf` delegates to `import_name`, so
the rule has one definition.

This is **D15**, the one P10 step that moves oracle-shared output. 178 cases
are pinned, all by explicit id (FOL 33, Sequents 47, CTT 98) and all verified
mechanically: 134 are the oracle's stdout with the leaf substituted byte for
byte, 41 identical after collapsing space runs (the locus column is sized to
the widest locus), 2 identical as line sets, 1 a `shape` resume from a prefix
the oracle wrote. entrydiff's moved set is empty because both dumps key by
path. Upstream defect 1 from P9 (`two/ex/ex/Foo`, the doubled directory)
closes on this side: p9probe §3c pins `one/ex/Foo` / `two/ex/Foo`.

Side finding: `SCANNING.md`'s theory-name collision figures had been leaf
figures all along, describing a tool that did not yet exist; they are true
now. Four `src/HOL` names newly collide by leaf, widening the name-keyed
import adjacency — `Reach`'s documented approximation, which may only widen;
`callers rev` over `src/HOL` is 670 with the filter, without it, and on the
oracle.

## Decisions this phase made

- **Version format** `0.8.1-scala.0.1` (S1) — a policy, checked by the harness.
- **D15** — the port diverges from the oracle on theory naming, on Isabelle's
  authority. The pins are explicit ids, never family globs.
- **`Reach.leaf_index` deleted**, not kept "in case": §8b of `dev/p7cprobe.sh`
  proves the import spellings never went through it.
- **Registration parking rejected.** P8's workflow — comment the fork out of
  the real `etc/components` for the duration — was blocked by the permission
  classifier and not retried. The rule that replaced it: compiling states
  only, never leave the tree broken while thinking. Four agents held to it.

## Also this day, outside the plan

- `dev/ide-bloop.sh` — a Bloop build definition for the language server,
  derived from the settings environment. Metals otherwise falls back to a
  classpath-less scala-cli build and reports every Isabelle symbol missing.
- `todo.md` `[pide-mcp-tools]` — the design for offering the engine through
  the PIDE MCP server's service hook, opt-in, now that S3 makes a resident
  host trivial. That is the next phase.

## What the next phase inherits

- **`[pide-mcp-tools]`** — the design is in `todo.md`; S3 was its
  precondition.
- **`Usage_Graph.is_citation_name` has no caller** in the tree; public API the
  router inlined past. Deleting it is a separate decision.
- **The plugin names the namespace policy by the literal `"callers"`** when it
  asks `CLI.resolve_namespace`. Correct — it wants the per-project table — but
  it reads as a magic constant.
- **Upstream defects 2 and 3** from `dev/P9-STATUS.md` are still shared
  behaviour; defect 1 is closed here and the report to upstream should say so.
