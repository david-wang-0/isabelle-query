"""How this program refers to itself in user-facing text [cli-alias].

A leaf of the module DAG: it imports nothing from the package, so every layer
can reach it.  That is the reason it is a module of its own rather than a
helper in `cli`.  The name is needed both in `cli` (the usage line, `--version`,
the root diagnostics) and in `shape_cmds` (the per-session census skip notice),
and `shape_cmds` sits *below* `cli` — importing upward would close a cycle, and
copying the four lines would give the tool two answers to one question, which is
exactly what this accessor exists to prevent.
"""

from __future__ import annotations

import os
import sys

# The canonical short name, used when the invocation cannot supply one.  Also
# what the docs call the tool: `isabelle-query` is the *distribution* name and
# the discoverable alias, `query` is what you type.
PROG_FALLBACK = "query"


def prog_name() -> str:
    """The name this run was invoked as.

    The distribution installs two console scripts pointing at the same entry
    point (`query`, `isabelle-query`), so a fixed literal is guaranteed to be
    wrong for one of them — and wrong in the least helpful way: `usage: query
    ...` in answer to `isabelle-query -h` names a command the reader may not
    have on PATH, and makes every example in that help output uncopyable.
    Reflecting the invocation is both argparse's own default for ``prog`` and
    the Unix norm, so a wrapper or a renamed shim gets it right for free.

    Falls back to `query` when argv[0] is absent or is a *module* path
    (`python -m isabelle_query.cli`, or a checkout run as `python cli.py`),
    where the basename is an implementation detail rather than a command
    anyone could retype.

    Deliberately not cached: `sys.argv` is writable, and setting argv[0] is
    the only honest way to test that the reflection happens at all.
    """
    name = os.path.basename(sys.argv[0]) if sys.argv else ""
    if not name or name.endswith(".py"):
        return PROG_FALLBACK
    return name
