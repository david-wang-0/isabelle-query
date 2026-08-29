# Contributing to isabelle-query

Normative rules for changing the tool. Orientation (what it is, how the tree is
laid out) is in `CLAUDE.md`; `PLAN.md` is normative for the rewrite itself.
User-facing docs are `README.md` (the CLI surface), `MIGRATING.md` (coming from
the Python tool), `SCANNING.md` (what counts as a declaration, a citation and a
session) and `METRICS.md` (the `shape` reference).

**Two trees, one contract.** `query_base/` and `jedit_query/` are the live
Isabelle/Scala component. `src/isabelle_query/` and `tests/` are the **frozen**
Python reference implementation and its suite — read them, never edit them;
they are the oracle the Scala side is checked against, and an oracle that moves
is not one. Rules below marked *(Python)* describe that frozen tree and are
kept because they explain why the contract is what it is; rules marked *(Scala)*
are what a change to the live tree must satisfy.

## Where design decisions are recorded

**In commit messages.** They carry the reasoning, the rejected alternatives and
the before/after, and they are the only record that cannot drift from the code.
Cross-reference a body of work with a stable `[tag]` handle, and recover it with

    git log --grep='\[grep-owner-span\]'

`todo.md` holds only *open* work — things not yet done. It is not a changelog.
If you want to know why something is the way it is, read the commits.

**Cite only what a reader of this repo can open.** Comments, docstrings and
config headers must not reference paths outside it — a pointer the reader cannot
resolve is worse than no pointer, and the design material it names may be
unpublished. For the proof-shape metrics the public authority is
`src/isabelle_query/shape.py` itself (definitions at each metric) plus the
`M1`–`M6` table in `METRICS.md`. Check with:

    grep -rn 'docs/' --include='*.py' --include='*.toml' src tests scripts configs

## CLI contract (follow when adding or changing commands)

The contract below is stated in the Python implementation's vocabulary because
that is where it was written down and where it is still enforced by the
oracle. Every clause **binds the Scala tool identically** — the differential
harness is what checks it, and a divergence needs an entry in
`dev/DIVERGENCES.md` with evidence, not a shrug.

Two families, each matching an external convention; a command's primary
positional decides which one it is.

- **lookup** (git/brew: `git show REF...`, `brew deps FORMULA`) — the
  primary positional is a **subject** (entry/theory name), one-or-more,
  reported in turn. Add it with `_add_subject_list_arg`. **No trailing
  PATH positionals**: "who calls X" is corpus-global, so scope with the
  global `-R/--root` and narrow with *semantic* flags (`--external`,
  `-r/--recursive`), never a file subset. Members: `show`, `callers`,
  `callees`, `deps`, `uses`, `theory`, `defs`, `outline`, `methods`.
- **search** (grep/rg: `grep PAT PATH...`) — the primary positional is a
  pattern (or nothing), and **paths are the trailing positionals**, added
  with `_add_path_files_arg` (resolved by `_load_sections`). Members:
  `grep`, `largest`, `sorry` (and `find` once it gains PATH/`--theory`
  scope under `[theory-refs]`).

**The two rewrite-only verbs sit in the lookup family, and their exit contract
is the reason they were allowed to exist.** *(Scala)* `instances` and `codeqs`
take a subject list and no PATH positionals. Because they have no oracle, they
carry the discipline the oracle would otherwise have supplied:

- A subject that is **not a locale/class** (resp. **not a constant**) declared
  in the project exits `1` with a diagnostic on stderr — never `0` with zero
  sites. "No instantiations of `foo`" and "`foo` is not a locale" are different
  answers and a script must be able to tell them apart. This is the same
  never-empty-success rule one level in.
- A subject that IS declared and has no sites exits `0`. That is an honest
  zero.
- Their scope is stated in `README.md`, not buried: declared source sites only,
  the complement of `print_interps` / `print_codesetup`; and `codeqs`
  under-reports where mixfix notation hides the head symbol. A verb that leans
  the unsafe way says so where the user reads about it.

A third rewrite-only verb would need the same three things before it ships.

**Never return an empty success for a question you could not ask.** A silent
zero is indistinguishable from an honest zero, so a caller cannot tell a broken
run from a real one — `query -R /typo shape census` once printed nothing and
exited 0, and a shell path-expansion bug turned that into a run of plausible
zero-record censuses. A root that cannot be read reports on stderr and exits
`2`, a code deliberately distinct from `1`.

**A user-typed pattern goes through `commands._user_pattern`, never straight to
`re.compile`.** This is the same rule one level down: a pattern that cannot
match is a silent zero the caller has no reason to doubt. Two rewrites live
there — shell-grep `\|` to `|`, and Isabelle markup (`\<^sub>`) escaped so the
`^` is not read as a start-of-string anchor. The second is what makes `query
find 'split\<^sub>i'` work, and it is a case of the tool accepting its own
output as input: `show` and `callers` already took that name, `find` did not.
Add a third rewrite here rather than at a call site, and route new
pattern-taking verbs through `_compile_user_pattern`, which also reports a bad
regex on stderr and exits `2` instead of raising.

**A configurable global that moves a measurement gets ONE default, and the
library caller gets the same one as the CLI.** Third instance of the silent-zero
family, and the worst-behaved: `graph`'s method table is late-bound, the CLI
bound it at dispatch, and `import isabelle_query` left the minimal Pure floor —
so `shape.analyze_proof` called directly returned numbers no `query` run would
ever print. It failed *selectively* (`simp` and `rule` are in the floor; `auto`,
`blast`, `metis` are not), which is why it read as data: a spot-checked `by simp`
proof agreed with the census exactly while 62.3% of proofs' `trivial_frac`
silently became `None` — "discharges nothing" for proofs that discharge
everything. The default is now the broad committed union both paths use
(`graph.use_census_namespace`), and stepping *down* is an explicit call
(`graph.use_pure_namespace`), never an inherited default. When a global like this
has to differ by context, make each context bind what it wants — a branch that
relies on "the default is already right" stops being right the moment the default
moves, and nothing fails when it does.

Shared-feature help text comes from one helper each, so wording can't
drift command-to-command — always add a feature through its helper, never
inline:

| helper | feature |
|---|---|
| `_add_subject_list_arg` | subject list |
| `_add_path_files_arg` | trailing `PATH` |
| `_add_names_flag` | `--names` (**no `-n`** — reserved for grep's line-number meaning) |
| `_add_count_flag` | `-c/--count` |
| `_add_with_comments_flag` | `--with-comments` — the *only* prose-search toggle on `find`/`grep` (**no `-a`**, which is `_add_mode_flags`' show-all) |
| `_add_mode_flags` | the `-a` / `--names` / `-c` bundle |
| `_add_verbatim_flag` | `-V/--verbatim` |
| `_add_comment_flags` | `--comments-off` / `--comments-only` |
| `_add_context_flag` | `-U/--context` — one short flag everywhere, default per-command |
| `_add_drop_names_flag` | `--drop-names-upto` |
| `_add_line_number_noop_flag` | `-n/--line-number` — accepted and ignored on the search verbs |

The **program name is not a literal**. The tool installs under two console
script names (`query`, `isabelle-query`) and reports whichever was invoked, via
`_prog.prog_name()` (re-exported as `cli._prog_name`). Anything a user reads —
the `prog=`, `--version`, a stderr diagnostic prefix, an example embedded in
help prose — goes through that accessor. A hardcoded `"query"` is guaranteed
wrong for one of the two callers, and wrong in the direction that names a
command the reader may not have on PATH. Internal comments and docstrings are
exempt: nobody retypes those. `tests/test_cli_prog_name.py` greps `src/` for the
literal, because the failure mode is a *new* message, not an old one.

`_prog` is a leaf module — it imports nothing from the package — precisely so
that `shape_cmds`, which sits below `cli`, can use it without closing a cycle.

## Verification — the Scala tree

There is no unit suite here, and that is a decision rather than an omission: the
tool has an **oracle**, and a differential run says more about a scanner change
than any number of hand-written assertions could. Four harnesses, in the order
a change should meet them. All read their corpora from `$QUERY_TEST_AFP` /
`$QUERY_TEST_DISTRO` (see `.dev/corpora.env`) and none from a hard-coded path.

| harness | what it establishes | when |
|---|---|---|
| `dev/entrydiff.sh` | the entry set and the theory set, over the whole AFP and the whole distribution `src` | any change to parsing, discovery, or the entry grammar |
| `dev/difftest.sh` | 2,086 (corpus × invocation) cases: stdout byte-for-byte, exit statuses, stderr presence | any change to a command, a flag, or a renderer |
| `dev/p5probe.sh`, `p6probe.sh`, `p6bprobe.sh` | the jEdit plugin, without a display | any change under `jedit_query/` |
| `dev/p7probe.sh` | the warm server, the thin client, and the auto-delegating CLI (§15) | any change to `server.scala`, `delegate.scala`, `cli.scala`, or the client |

`isabelle query` delegates to the warm server by default, so every harness that
compares the ENGINE with something else pins `--no-server` (or exports
`$ISABELLE_QUERY_NO_SERVER=1`): a run that quietly used a resident index would
be testing the transport too, would depend on whether a server happened to be
up, and would leave a corpus-sized JVM behind it. `QUERY_DIFFTEST_DELEGATE=1`
turns it back on for the whole matrix, which is a different and equally
necessary question — does the delegated path give the user what the oracle
gives — and is recorded in `dev/P7B-STATUS.md`.

Four habits around them, each of which has caught something here:

- **Diff the entry set as a SET, not a count.** A count hides a simultaneous
  gain and loss; when a header change silently dropped 72 theories it reported
  an identical entry total.
- **A probe must REFUSE without its corpora**, never skip into a green. An `OK`
  that did not look at the corpus it claims to cover is worse than a failure,
  because it is invisible. Every probe here exits `2` when a corpus is absent.
- **A probe must be able to fail.** Each ends with a failability section: one
  expectation is deliberately perturbed and the run must go red. A probe that
  has never failed has not been tested.
- **Correct an expectation against the tool, not the tool against the
  expectation** — but only after establishing which one Isabelle agrees with.
  Two of `p7probe`'s expectations were wrong (an empty *search* result is exit
  `0`; exit `1` belongs to the *lookup* family), and the fix was to the probe.
  Three others were real defects, and the fix was to the server.

**Never edit `query_base/src` while a differential run is in flight** — the
harness builds, and a mid-run edit tests a tree that no longer exists.

**A resident host must rebind `Namespace`.** It is process-global mutable state
that decides whether `auto` is a proof method or a fact, and `shape census`
binds the broad union unconditionally by design. The jEdit plugin serialises
through one worker thread; the server restores the committed default before
every request under one lock. A new long-lived caller that does neither will
read whatever the last one left, and will do it *selectively* — which is how
this class of bug reads as data rather than as a failure (see the
configurable-global rule above).

## Verification — the frozen Python tree *(reference only)*

The suite is not sufficient on its own for parser changes; unit tests cannot
see a scanner failing at scale. Two habits catch what they miss:

- **Diff the entry set** after any parser change — `scripts/dump_entries.py`
  (add `--spans` when extents may move; a change can leave entries identical
  while moving a thousand declaration ends).
- **Diff the discovered theory set** after any change to session or `imports`
  parsing — and diff it **as a set, not a count**. `dump_entries.py` walks
  `ent.rglob("*.thy")`, so it never calls `parse_thy_imports` and cannot see a
  discovery regression: when `[thy-header]` silently dropped 72 theories it
  reported an identical 55,838 entries. Compare
  `{p for s in iter_sessions(root) for _, p in session_theories(s)}` against
  the same set from `git archive <ref> | tar -x -C .scratch-head`. A count
  alone hides a simultaneous gain and loss, which is exactly what happened.
- **Check a new test can fail.** Patch the behaviour it pins, run it, restore.
  Several tests have been written that could not fail. Two traps in the loop
  itself, both of which have produced a wrong verdict here:
  - **Run the mutation with `PYTHONDONTWRITEBYTECODE=1`.** A mutation harness
    rewrites the source several times a second, and CPython invalidates a
    `.pyc` on `(mtime, size)` — so a same-second rewrite can leave the previous
    bytecode in place and the subprocess runs code that is not on disk. That
    reports a *live* mutation as SURVIVED. `CAUGHT` is always trustworthy;
    `SURVIVED` is not, until the cache is off.
  - **A survivor may be masked, not dead.** A unit test can be shadowed by a
    downstream guard that ends the scan anyway. Before concluding a branch is
    unreachable, diff the entry set with the mutation applied: two branches
    that survived every unit test moved 706 and 330 entries.
  When a branch really is unreachable from valid input, say so in the comment
  and pin it at the helper/regex level, rather than leaving it looking tested.

`pytest -q` stays green after every change **to that tree** — which, since it is
frozen, means it should never need running for a change made here. It is listed
so that anyone who does unfreeze it knows what the bar was.

## The program name

*(Python)* The tool installs under two console script names and reports
whichever was invoked, via `_prog.prog_name()`; a hardcoded `"query"` is
guaranteed wrong for one of the two callers, and `tests/test_cli_prog_name.py`
greps `src/` for the literal.

*(Scala)* There is one name, `isabelle query`, and `CLI.prog` holds it.
Diagnostics compose it as `"isabelle query: …"`. The warm client is a transport
and prints the tool's bytes unchanged, so it inherits the name rather than
introducing a third one.

