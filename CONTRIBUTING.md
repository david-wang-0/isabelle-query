# Contributing to isabelle-query

Normative rules for changing the tool. Orientation (what `query` is, how the
package is laid out) is in `CLAUDE.md`. User-facing docs are `README.md` (the
CLI surface), `SCANNING.md` (what counts as a declaration, a citation and a
session) and `METRICS.md` (the `query shape` reference).

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

## Verification

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

`pytest -q` stays green after every change.
