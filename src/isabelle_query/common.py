"""Shared ROOT / session parsing — now a re-export of `isabelle-layout`.

The parsing that used to live here is a *library*, and it was shipped inside
an *application*.  Every consumer that wanted a ROOT parser had to install a
query CLI to get one, and inherit its release cadence, its version floor and
its userbase's compatibility constraints.  `isabelle-watchdog` is the case
that made the cost concrete: it runs beside an Isabelle build and takes no
runtime dependencies on purpose, so rather than depend on this module it wrote
its own 15-line ROOT reader — which truncated a quoted session name at its
first space and a bare one at its first `.` or `-`, so a session built as
`Probe (AFP)` was recorded against a session named `Probe`, with nothing
downstream able to tell.

That is a distribution problem, not a code problem.  The code moved, unchanged
and verified unchanged (68362 comparisons over the AFP and the Isabelle
distribution, 0 disagreements), to `isabelle-layout`, which is now on PyPI and
is this package's one runtime dependency.  This module re-exports it.

The case that prompted it has since taken the offer: `isabelle-watchdog` 0.3.1
declares `isabelle-layout>=0.2.2`, having previously declared no runtime
dependencies at all.  It could not depend on this module without taking a CLI
it does not use; it can depend on a parser.

**Nothing changes for callers today.**  A dozen or so scripts in downstream
projects import `isabelle_query.common` directly, as do `cli`, `parsing` and
`commands` in this package.  They all keep working, so there is no flag day;
consumers move to `isabelle_layout` individually, at whatever pace suits — and
can now `pip install isabelle-layout` to do it.  New code should import from
`isabelle_layout` directly:

    from isabelle_layout import iter_sessions, session_theories
    from isabelle_layout.distribution import is_hol_base

Two things did not move, deliberately:

* `run_guarded` is still defined below.  It is six lines, not Isabelle-specific
  at all, and already copied into `isabelle_watchdog.guard`; a third home is
  how one utility becomes three subtly different utilities.
* `classify_import` moved *private*.  Deciding what counts as "infrastructure"
  is an analysis judgement about a corpus rather than a reading of a file, and
  no consumer outside this repository ever imported it.  It is aliased below
  so this module's surface is unchanged.
"""

from __future__ import annotations

import sys
from typing import Callable, TypeVar

# --- the moved API, re-exported ------------------------------------------
#
# Public names first: exactly what this module documented before the split.
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

# The marker this tool has always read.  `isabelle_layout.MARKER_NAME` is now
# the neutral `.isabelle-layout`, and re-exporting *that* here would silently
# change the value of a documented constant.  `default_t_dir` reads both, so
# behaviour is unchanged either way; the constant keeps its old meaning.
from isabelle_layout.project import LEGACY_MARKER_NAME as MARKER_NAME  # noqa: F401


_T = TypeVar("_T")


def run_guarded(label: str, thunk: Callable[[], _T]) -> "_T | None":
    """Run `thunk()` for a best-effort side task that must never break
    its caller.  On any exception, print `<label>: skipped (Type: msg)`
    to stderr and return None; on success return thunk()'s result.

    Used by the build-trajectory capture (`bin/build_record.py` and the
    `bin/isabelle-watchdog.py` invocation of it), where a failure in the
    optional logging must never change the build's exit code.  Both call
    sites route through here so the swallow-and-warn message has a single
    definition; they remain two distinct guards because they cover
    different scopes (build_record guards its record logic, the watchdog
    additionally guards the `import build_record` that a guard inside
    build_record cannot).

    DEPRECATED — unused in this repository; its build-trajectory callers
    live in the upstream ``bin/`` tooling, not here.  Retained pending that
    tooling's review.  Do not add new callers.

    Deliberately *not* moved to `isabelle-layout`: it is not Isabelle-specific,
    and `isabelle_watchdog.guard` already carries a copy for the callers that
    actually use it.
    """
    try:
        return thunk()
    except Exception as exc:  # noqa: BLE001 — best-effort by contract
        print(f"{label}: skipped ({type(exc).__name__}: {exc})",
              file=sys.stderr)
        return None
