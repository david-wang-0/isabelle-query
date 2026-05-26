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

Multi-root / multi-session API (for trees with multiple ROOT files
and/or multi-session ROOTs like Flyspeck-Tame or HOL):

SessionInfo
    Dataclass capturing one parsed session declaration: name,
    declaring ROOT path, `in <subdir>` clause, parent (after `=`),
    `sessions` clause, `directories` clause, `theories` clause.

parse_root_sessions(root_path)
    Parse all `session ...` declarations from a ROOT file.  A single
    ROOT may declare multiple sessions (HOL/ROOT declares dozens;
    Flyspeck-Tame/ROOT declares two).

resolve_session_theory(session, name)
    Resolve a theory name to its on-disk path within a session,
    honouring the session's `in <subdir>` and `directories` clauses.

discover_roots(root_dir)
    Walk a directory tree and return every ROOT file found.

iter_sessions(root_dir)
    Convenience: discover_roots ∘ parse_root_sessions, flattened.

Layout assumption: this module sits in PROJECT_ROOT/bin/ and the
default session directory is PROJECT_ROOT/t/.  Callers needing a
different layout pass an explicit t_dir.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
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


# ---------------------------------------------------------------------------
# Multi-root / multi-session API
# ---------------------------------------------------------------------------

@dataclass
class SessionInfo:
    """One session declaration parsed from a ROOT file.

    A ROOT may declare multiple sessions; each is captured separately
    so tools can enumerate theories and follow cross-session references
    accurately (e.g. resolving a theory that lives under the session's
    `in <subdir>` clause rather than alongside the ROOT file).
    """
    name: str
    root_path: Path             # absolute path of the declaring ROOT file
    in_subdir: str | None       # `in <subdir>` clause; None = same dir as ROOT
    parent: str | None          # session name after `=` (heap inherited from)
    used_sessions: list[str] = field(default_factory=list)
        # `sessions` clause: cross-session deps without heap inheritance
    directories: list[str] = field(default_factory=list)
        # `directories` clause: extra subdirs to search for theory files
    theories: list[str] = field(default_factory=list)
        # `theories` clause: declared theory names (bare identifiers)

    @property
    def session_dir(self) -> Path:
        """Directory containing this session's .thy files."""
        if self.in_subdir:
            return self.root_path.parent / self.in_subdir
        return self.root_path.parent


# Regex matching a `session ...` header line.  Handles:
#   * quoted or bare session name
#   * optional `(slow timing)`-style options
#   * optional `in DIR` (quoted or bare)
#   * `= PARENT +`
_SESSION_HEADER = re.compile(
    r'''
    ^\s*session\s+                          # `session` keyword
    (?:"([^"]+)"|(\S+))                     # session name
    (?:\s+\([^)]*\))?                       # optional `(options)`
    (?:\s+in\s+(?:"([^"]+)"|(\S+)))?        # optional `in DIR`
    \s*=\s*                                 # `=`
    (?:"([^"]+)"|(\S+))                     # parent
    \s*\+                                   # `+`
    ''',
    re.MULTILINE | re.VERBOSE)


def parse_root_sessions(root_path: Path) -> list[SessionInfo]:
    """Parse every `session ...` declaration from a ROOT file.

    Each session's body extends from its header to the next session
    header (or EOF).  Within each body, the `sessions`, `directories`,
    and `theories` clauses are extracted via `_extract_clause`.

    Returns sessions in declaration order.  Empty if the file is
    missing or declares no sessions (e.g. `afp/thys/ROOT`, which is
    a `chapter_definition` file).
    """
    if not root_path.exists():
        return []
    text = root_path.read_text()
    matches = list(_SESSION_HEADER.finditer(text))
    out: list[SessionInfo] = []
    abs_root = root_path.resolve()
    for i, m in enumerate(matches):
        name = m.group(1) or m.group(2)
        in_dir = m.group(3) or m.group(4)
        parent = m.group(5) or m.group(6)
        body_start = m.end()
        body_end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        body = text[body_start:body_end]
        out.append(SessionInfo(
            name=name,
            root_path=abs_root,
            in_subdir=in_dir,
            parent=parent,
            used_sessions=_extract_clause(body, "sessions"),
            directories=_extract_clause(body, "directories"),
            theories=_extract_clause(body, "theories", names_only=True),
        ))
    return out


def _extract_clause(body: str, clause: str,
                    names_only: bool = False) -> list[str]:
    """Extract entries from a named clause inside a session body.

    `clause` is the keyword (`theories`, `directories`, `sessions`).
    Tokens may be bare identifiers or `"quoted"`; with
    `names_only=True`, only bare-identifier matches are returned (used
    for `theories`, which are always plain Isabelle names).
    """
    out: list[str] = []
    in_clause = False
    for line in body.splitlines():
        stripped = line.strip()
        if re.match(rf"{clause}\b", stripped):
            in_clause = True
            stripped = stripped[len(clause):].strip()
        elif _is_terminator(stripped, exclude=clause):
            in_clause = False
            continue
        if in_clause and stripped:
            if names_only:
                if re.match(r"[A-Za-z_][A-Za-z0-9_]*$", stripped):
                    out.append(stripped)
            else:
                for a, b in re.findall(r'"([^"]+)"|(\S+)', stripped):
                    token = a or b
                    if token:
                        out.append(token)
    return out


def resolve_session_theory(session: SessionInfo, name: str) -> Path | None:
    """Resolve a theory name to its .thy file within a session.

    Checks the session's directory first (`session.session_dir`), then
    each subdirectory in `session.directories`.  Returns None if not
    found — the theory may be missing or declared by a parent session.
    """
    base = session.session_dir
    candidate = base / f"{name}.thy"
    if candidate.exists():
        return candidate
    for sub in session.directories:
        candidate = base / sub / f"{name}.thy"
        if candidate.exists():
            return candidate
    return None


def discover_roots(root_dir: Path) -> list[Path]:
    """Find every ROOT file under `root_dir`.

    Recursive walk; results sorted for stable ordering.  Skips hidden
    directories (anything starting with `.`).  ROOT files that don't
    declare any sessions (e.g. AFP's `afp/thys/ROOT`
    chapter-definition file) are still returned — `parse_root_sessions`
    will return [] for them, so they're harmless.
    """
    if not root_dir.exists():
        return []
    out: list[Path] = []
    for path in sorted(root_dir.rglob("ROOT")):
        if any(part.startswith(".") for part in path.parts):
            continue
        if path.is_file():
            out.append(path.resolve())
    return out


def iter_sessions(root_dir: Path) -> list[SessionInfo]:
    """Return every session declared by any ROOT under `root_dir`.

    Order: ROOTs in `discover_roots` order; sessions within each
    ROOT in declaration order.
    """
    out: list[SessionInfo] = []
    for root_path in discover_roots(root_dir):
        out.extend(parse_root_sessions(root_path))
    return out
