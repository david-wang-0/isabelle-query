"""Compatibility shim over `isabelle-layout`.  Almost no code lives here.

ROOT files, session declarations and theory headers are parsed by
**`isabelle-layout`**, a separate package on PyPI and this tool's one runtime
dependency.  Nothing is vendored: every name below is an `import` resolving to
site-packages, and this module exists only so that callers written against the
older `isabelle_query.common` keep working.

**New code should import `isabelle_layout` directly**:

    from isabelle_layout import iter_sessions, session_theories
    from isabelle_layout.distribution import is_hol_base

Two names are this module's own rather than a straight re-export:

* `classify_import` — upstream's is private (`_classify_import`), because
  deciding what counts as "infrastructure" is an analysis judgement about a
  corpus rather than a reading of a file.  Aliased back to its old public
  spelling here, where that judgement is wanted.
* `run_guarded` — defined below, deprecated, and called by nothing in this
  repository.  See `[watchdog-guard]` in `todo.md`.

Why the parser lives in a separate package at all is recorded where this
project records design decisions — in the commit, not in a comment that drifts
from it: `git log --grep='common.py becomes a re-export'`.
"""

from __future__ import annotations

import sys
from typing import Callable, TypeVar

# --- re-exports from the isabelle-layout package --------------------------
#
# Public names first: exactly the surface this module documents.
from isabelle_layout import (  # noqa: F401
    SessionInfo,
    default_t_dir,
    discover_roots,
    iter_sessions,
    iter_thy_files,
    parse_root_sessions,
    parse_thy_imports,
    resolve_base_logic,
    resolve_session_theory,
    session_theories,
)
from isabelle_layout.distribution import (  # noqa: F401
    _NONHOL_DISTRIBUTION_BASES,
    is_hol_base,
    is_known_nonhol_base,
)

# Private names that callers in this repository actually reach for.  Listed
# individually rather than star-imported, so the list is a statement of what
# the deprecation window covers rather than an accident of what happens to
# exist.  `tests/test_thy_header.py` exercises the two strippers directly, and
# `_classify_import` is re-exposed under its old public spelling.
from isabelle_layout._lexer import (  # noqa: F401
    strip_block_comments as _strip_block_comments,
    strip_cartouches as _strip_cartouches,
    strip_comments as _strip_comments,
)
from isabelle_layout.project import _read_marker  # noqa: F401
from isabelle_layout.roots import (  # noqa: F401
    _parse_root_directories as parse_root_directories,
    _parse_root_theories as parse_root_theories,
    _resolve_thy_file as resolve_thy_file,
    _tokenize_root,
)
from isabelle_layout.theories import (  # noqa: F401
    _classify_import as classify_import,
    _INFRA_ROOTS,
    _THY_HEADER_RE,
)

# The marker this tool reads.  `isabelle_layout.MARKER_NAME` is the neutral
# `.isabelle-layout`, and re-exporting *that* here would silently change the
# value of a constant this tool documents.  `default_t_dir` reads both, so
# behaviour is the same either way; the constant keeps its own meaning.
from isabelle_layout.project import LEGACY_MARKER_NAME as MARKER_NAME  # noqa: F401


_T = TypeVar("_T")


def run_guarded(label: str, thunk: Callable[[], _T]) -> "_T | None":
    """Run `thunk()` for a best-effort side task that must never break
    its caller.  On any exception, print `<label>: skipped (Type: msg)`
    to stderr and return None; on success return thunk()'s result.

    DEPRECATED, and the only function this module defines.  Nothing in this
    repository calls it; its callers are the build-trajectory capture in the
    upstream ``bin/`` tooling, and `isabelle_watchdog.guard` carries its own
    copy.  Do not add new callers — see `[watchdog-guard]` in `todo.md`, which
    is about deleting this.
    """
    try:
        return thunk()
    except Exception as exc:  # noqa: BLE001 — best-effort by contract
        print(f"{label}: skipped ({type(exc).__name__}: {exc})",
              file=sys.stderr)
        return None
