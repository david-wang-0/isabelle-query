"""Shared helpers for the bin/ Python tooling.

Python analogue of bin/common.sh: parse the project's Isabelle ROOT file
to enumerate the declared theories and the .thy files on disk that
correspond to them.  This is the *single source of truth* for "which
theory files belong to the build" — scripts must not glob
t/**/*.thy independently, because:

  - WIP / orphan / archived .thy files on disk are then silently
    included.
  - Adding a new theory subdirectory (e.g. t/generic/) requires
    editing every script that hard-codes the glob list.

Public API
----------

PROJECT_ROOT, T_DIR
    Absolute paths.  PROJECT_ROOT is the repo root (one level up from
    bin/); T_DIR is the Isabelle session directory (PROJECT_ROOT / "t").

parse_root_theories(root_path)
    Return the ordered list of theory names declared under the ROOT
    file's `theories` block.

parse_root_directories(root_path)
    Return the ordered list of subdirectory names declared under the
    ROOT file's `directories` clause.

iter_thy_files(t_dir=T_DIR)
    Return the ordered list of absolute Path objects for .thy files
    declared in t_dir/ROOT.  Each theory is resolved at the session
    root first, then in each declared subdirectory.  Returns []
    gracefully if t_dir/ROOT is missing.

resolve_thy_file(name, t_dir=T_DIR)
    Resolve a single declared theory name to its on-disk path.
    Returns None if not found.

parse_thy_imports(thy_path)
    Return the ordered list of theory names from a .thy file's
    `imports ... begin` clause.  Handles plain names and quoted
    qualified names like "HOL-Library.FuncSet".

Layout assumption: this module sits in PROJECT_ROOT/bin/ and the
default session directory is PROJECT_ROOT/t/.  Callers needing a
different layout pass an explicit t_dir.
"""

from __future__ import annotations

import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
T_DIR = PROJECT_ROOT / "t"

# Keywords that terminate a `theories` or `directories` block in a ROOT
# file (i.e. names of sibling clauses at the same nesting level).
_ROOT_BLOCK_TERMINATORS = (
    "theories", "document_files", "document_theories",
    "sessions", "options", "description", "chapter", "session",
    "directories",
)


def _is_terminator(stripped: str, *, exclude: str) -> bool:
    """True iff `stripped` starts with one of the ROOT block terminators
    other than `exclude` (the block we're currently parsing)."""
    for kw in _ROOT_BLOCK_TERMINATORS:
        if kw == exclude:
            continue
        if re.match(rf"{kw}\b", stripped):
            return True
    return False


def parse_root_theories(root_path: Path) -> list[str]:
    """Return the ordered list of theory names from ROOT's `theories`
    block.  Returns [] if the file doesn't exist or has no block."""
    if not root_path.exists():
        return []
    theories: list[str] = []
    in_theories = False
    for line in root_path.read_text().splitlines():
        stripped = line.strip()
        if re.match(r"theories\b", stripped):
            in_theories = True
            continue
        if _is_terminator(stripped, exclude="theories"):
            in_theories = False
            continue
        # Theory entries are bare identifiers (one per line).  Skip
        # blank lines, comments, and option-decorated forms ("Foo (in
        # Bar)").
        if in_theories and re.match(r"[A-Za-z_][A-Za-z0-9_]*$", stripped):
            theories.append(stripped)
    return theories


def parse_root_directories(root_path: Path) -> list[str]:
    """Return the ordered list of subdirectory names from ROOT's
    `directories` clause.  Each clause entry is a quoted token; this
    accepts both single-line (`directories "a" "b"`) and multi-line
    forms.  Returns [] if absent."""
    if not root_path.exists():
        return []
    subdirs: list[str] = []
    in_dirs = False
    for line in root_path.read_text().splitlines():
        stripped = line.strip()
        if stripped.startswith("directories"):
            in_dirs = True
            stripped = stripped[len("directories"):].strip()
        elif _is_terminator(stripped, exclude="directories"):
            in_dirs = False
            continue
        if in_dirs:
            # Pull every quoted token off the line.
            rest = stripped
            while '"' in rest:
                a = rest.index('"')
                try:
                    b = rest.index('"', a + 1)
                except ValueError:
                    break
                name = rest[a + 1:b]
                if name:
                    subdirs.append(name)
                rest = rest[b + 1:]
    return subdirs


def resolve_thy_file(name: str, t_dir: Path = T_DIR) -> Path | None:
    """Resolve a declared theory `name` to its .thy file on disk.

    Searches the session root first, then each subdirectory declared
    under ROOT's `directories` clause.  Returns None if not found.
    """
    candidate = t_dir / f"{name}.thy"
    if candidate.exists():
        return candidate
    root_path = t_dir / "ROOT"
    for sub in parse_root_directories(root_path):
        candidate = t_dir / sub / f"{name}.thy"
        if candidate.exists():
            return candidate
    return None


_IMPORTS_RE = re.compile(r'\bimports\b(.*?)\bbegin\b', re.DOTALL)


def parse_thy_imports(thy_path: Path) -> list[str]:
    """Return the ordered list of theory names from a .thy file's
    `imports ... begin` clause.

    Handles plain names (`Substrate`) and quoted qualified names
    (`"HOL-Library.FuncSet"`).  Returns the raw import names; callers
    decide whether each is in-project or external (cross-session) by
    cross-referencing against a session's `parse_root_theories` list.
    Returns [] if the file is missing or has no imports clause.
    """
    if not thy_path.exists():
        return []
    # The imports clause lives near the top of every .thy file
    # (between `theory X` and `begin`); reading the head is enough.
    head = '\n'.join(thy_path.read_text().splitlines()[:50])
    m = _IMPORTS_RE.search(head)
    if not m:
        return []
    raw = m.group(1)
    tokens = re.findall(r'"([^"]+)"|(\S+)', raw)
    return [a or b for a, b in tokens]


def iter_thy_files(t_dir: Path = T_DIR) -> list[Path]:
    """Return the ordered list of .thy files declared in t_dir/ROOT.

    Order matches ROOT's `theories` block.  Each theory is resolved at
    the session root first, then in declared subdirectories.  Theory
    names with no matching file on disk are silently skipped (mirrors
    bin/common.sh's get_build_files behaviour, so callers can run
    against partial trees during a refactor).
    """
    root_path = t_dir / "ROOT"
    out: list[Path] = []
    for name in parse_root_theories(root_path):
        resolved = resolve_thy_file(name, t_dir=t_dir)
        if resolved is not None:
            out.append(resolved)
    return out
