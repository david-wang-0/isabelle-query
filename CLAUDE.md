# CLAUDE.md — orientation for isabelle-query

This is the every-session story: what `query` is, how it is laid out, and where
everything else is written down. It is deliberately short. Granular working
rules live in `.claude/memory/` (auto-loaded); the current session's in-flight
state lives in the gitignored `prompt.md` handoff.

## What query is

`query` is a fast, syntax-aware, **pure-Python** command-line tool for querying
an Isabelle/Isar project. It parses the project's `.thy` sources on every
invocation — **no Isabelle build, no proof replay** — so results always match
the current tree, and even the whole AFP parses in ~1–2 minutes. (The one
Isabelle touch-point is the method/attribute *table* the table verbs use to tell
a proof method from a citation: resolved once from a loaded heap and cached, or
the committed table when Isabelle is absent — never a build; see
[[reuse-infrastructure-not-reinvent]].) It answers three kinds of question:

- **Structure** — *what is declared, and where*: `summary`, `theory`, `find`,
  `show`, `largest`, `outline`, and its inverse `enclosing`.
- **Usage** — *which facts cite which*: `callers`, `callees`, `deps`, `unused`
  (built on a call graph constructed only when needed).
- **Shape** — *the proof-complexity shape of individual steps*: `query shape`
  (a nested view family — `summary`/`steps`/`lemma`/`widest`/`census`).

`query -h` lists all subcommands; each takes `-h`. Locations and spans share one
grammar (`theory:line`, `theory:A..B`), so the tool's output is valid input.

## Architecture

The package is a strict module DAG — each imports only from earlier links:

    model → parsing → graph → render → commands → cli

with `common` (session/ROOT discovery, import closure), `shape` + `shape_cmds`
(the proof-shape family), and `scripts/` (offline table-generation + dev
utilities) as siblings.

- `model` — dataclasses (`Entry`, `TheorySection`, `CallGraph`).
- `parsing` — `.thy` → entry DB; the `_sections_from_dir` loader.
- `graph` — usage analysis + the shared step-scanner primitives.
- `render` / `commands` / `cli` — formatting, the `cmd_*` handlers, the argparse
  facade (which re-exports lower-layer names so tests reach `cli.X`).
- `shape` — the per-step shape engine; `shape_cmds` — its CLI verbs.

**Discovery loads what `isabelle build` compiles:** each session's ROOT-declared
theories *plus the transitive closure of their in-entry `imports`* (via
`common.session_theories`/`classify_import`). Imports of *other* AFP entries and
of the base library (`HOL-*`, `Pure`) are not followed; orphan `.thy` files are
excluded.

## The document map

| doc | what |
|---|---|
| `README.md` | user-facing: the subcommands, the shape metric table, examples |
| `src/isabelle_query/shape.py` | **authoritative** metric definitions and approximations |
| `.claude/memory/` | granular working rules + project facts (auto-loaded index) |
| `prompt.md` | **gitignored** end-of-session handoff (survives compaction; not the design) |

## Working essentials

The full rules are in `.claude/memory/` — start from `MEMORY.md`. The few that
matter every session:

- **Environment.** The `.venv` is already active (Python 3.14). Run `pytest` /
  `query` / `pip` **by bare name**; do **not** `cd` into the working dir (it
  trips the permission gate). `ruff` is not installed.
- **Corpora.** AFP checkout: `~/repos/afp/thys` (Isabelle2025-2); enumerate a
  corpus's sessions via `common.iter_sessions`, never a bare `rglob`.
- **Correctness.** Verify against Isabelle semantics, not prior behavior;
  hand-compute a fixture value first, then make the code match. `pytest -q` stays
  green after every change.
- **Commits.** Small, single-concern, frequent — commit often, don't push unless
  asked. Trailer, verbatim:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
  Write commit bodies to a `.commit-msg` scratch file in the working tree (not
  `/tmp`, which is gated); `rm` it after.
- **Reuse over re-roll.** Corpus/tooling scans reuse `cli._parse_one` +
  `shape`'s primitives; don't write a second parser.

## Release status

The released version tracks `pyproject.toml` (currently **0.5.1**). Versioning is
alpha: breaking CLI changes ship as patch bumps for now.

## Credit

Artifacts are credited "By András Salamon, with Claude Opus 4.6, 4.7, 4.8, and 5."
