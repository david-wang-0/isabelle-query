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
- **Check a new test can fail.** Patch the behaviour it pins, run it, restore.
  Several tests have been written that could not fail.

`pytest -q` stays green after every change.
