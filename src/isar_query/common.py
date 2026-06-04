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

default_t_dir(start=None)
    Resolve the Isabelle session directory to index: ``$ISAR_ROOT`` if
    set, else the nearest ``.isar-query`` marker file at or above
    ``start`` (default: cwd), else the nearest directory containing a
    ``ROOT`` file, else ``start``.  This is the project-root entry point
    used whenever a caller doesn't pass an explicit ``t_dir``.

run_guarded(label, thunk)
    Run thunk() for a best-effort side task that must never break its
    caller; on exception, warn `<label>: skipped (...)` to stderr and
    return None.  Used by the build-trajectory capture.

parse_root_theories(root_path)
    Return the ordered list of theory names declared under the ROOT
    file's `theories` block.

parse_root_directories(root_path)
    Return the ordered list of subdirectory names declared under the
    ROOT file's `directories` clause.

iter_thy_files(t_dir=None)
    Return the ordered list of absolute Path objects for .thy files
    declared in t_dir/ROOT.  Each theory is resolved at the session
    root first, then in each declared subdirectory.  Returns []
    gracefully if t_dir/ROOT is missing.

resolve_thy_file(name, t_dir=None)
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

Default project root: callers that don't pass an explicit ``t_dir``
get :func:`default_t_dir`'s result — ``$ISAR_ROOT``, else the nearest
session directory at or above the current working directory.  Callers
needing a specific layout pass an explicit ``t_dir``.
"""

from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, TypeVar


MARKER_NAME = ".isar-query"


def _read_marker(marker: Path) -> Path | None:
    """Return the session directory named by a project marker file,
    resolved relative to the marker's own location.

    The first non-blank, non-comment (``#``) line is taken as the path;
    ``~`` is expanded and a relative path is resolved against the
    marker's directory.  Returns None if the file names nothing usable
    (empty or all comments), in which case the caller treats the marker's
    own directory as the root.
    """
    try:
        lines = marker.read_text().splitlines()
    except OSError:
        return None
    for line in lines:
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        p = Path(s).expanduser()
        if not p.is_absolute():
            p = marker.parent / p
        return p.resolve()
    return None


def default_t_dir(start: Path | None = None) -> Path:
    """Resolve the Isabelle session directory to index.

    Resolution order:

    1. ``$ISAR_ROOT``, if set (``~`` expanded, resolved to absolute).
    2. The nearest project marker file (``.isar-query``) at or above
       ``start`` (default: the current working directory).  Drop one at a
       project's root, committed to the repo, so the tool resolves the
       right session directory from anywhere in the tree with no flags.
       Its first non-blank, non-comment line names the session dir
       (relative to the marker); an empty marker means "the root is my
       own directory".
    3. The nearest directory that holds a ``ROOT`` file directly — an
       unambiguous single session, so most projects need no marker.
    4. Fall back to ``start`` itself; its theories are then discovered by
       scanning for ROOT files beneath it.

    Discovery deliberately does *not* infer a multi-session parent from
    child ``ROOT`` files: a directory holding several ROOTs may be one
    project's sessions or several unrelated projects (e.g. vendored
    copies), and a structural scan can't tell them apart — that is what
    the marker file (or ``--root`` / ``$ISAR_ROOT``) resolves.  This
    replaces the old hard-coded ``PROJECT_ROOT/t``: the tool assumes no
    particular directory name, and no longer expects to live in a sibling
    ``bin/`` of the project it queries.
    """
    env = os.environ.get("ISAR_ROOT")
    if env:
        return Path(env).expanduser().resolve()
    here = (start or Path.cwd()).resolve()
    for d in (here, *here.parents):
        marker = d / MARKER_NAME
        if marker.is_file():
            named = _read_marker(marker)
            return named if named is not None else d
    for d in (here, *here.parents):
        if (d / "ROOT").is_file():
            return d
    return here

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
    build_record cannot)."""
    try:
        return thunk()
    except Exception as exc:  # noqa: BLE001 — best-effort by contract
        print(f"{label}: skipped ({type(exc).__name__}: {exc})",
              file=sys.stderr)
        return None

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


def resolve_thy_file(name: str, t_dir: Path | None = None) -> Path | None:
    """Resolve a declared theory `name` to its .thy file on disk.

    Searches the session root first, then each subdirectory declared
    under ROOT's `directories` clause.  Returns None if not found.
    ``t_dir`` defaults to :func:`default_t_dir`.
    """
    if t_dir is None:
        t_dir = default_t_dir()
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


def iter_thy_files(t_dir: Path | None = None) -> list[Path]:
    """Return the ordered list of .thy files declared by ROOT(s) under t_dir.

    Two layouts are supported transparently:

    * **Single ROOT** (``t_dir/ROOT`` exists): order matches that ROOT's
      `theories` block; each theory is resolved at the session root first,
      then in declared subdirectories.
    * **Multi-ROOT** (no ``t_dir/ROOT``, e.g. the post-[t-layout-split]
      tree with ``t/base/ROOT``, ``t/ae/ROOT``, ``t/ar/ROOT``): every
      session declared by every ROOT under ``t_dir`` is enumerated (via
      ``iter_sessions``) and its theories resolved against the declaring
      session's directory.  Results are deduplicated by resolved path so
      a theory reached through more than one session appears once.

    Theory names with no matching file on disk are silently skipped
    (mirrors bin/common.sh's get_build_files behaviour, so callers can
    run against partial trees during a refactor).
    """
    if t_dir is None:
        t_dir = default_t_dir()
    out: list[Path] = []
    root_path = t_dir / "ROOT"
    if root_path.exists():
        for name in parse_root_theories(root_path):
            resolved = resolve_thy_file(name, t_dir=t_dir)
            if resolved is not None:
                out.append(resolved)
        return out
    # Multi-ROOT: union theories across every session under t_dir.
    seen: set[Path] = set()
    for session in iter_sessions(t_dir):
        for theory_entry in session.theories:
            resolved = resolve_session_theory(session, theory_entry)
            if resolved is not None and resolved not in seen:
                seen.add(resolved)
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
    theories: list[tuple[str, str | None]] = field(default_factory=list)
        # `theories` clause: list of (name, dir_override).
        # dir_override is the per-theory `in <subdir>` overlaid on
        # the session-level `in_subdir`; almost always None.

    @property
    def session_dir(self) -> Path:
        """Directory containing this session's .thy files."""
        if self.in_subdir:
            return self.root_path.parent / self.in_subdir
        return self.root_path.parent


# ROOT tokeniser.  Hoisted from `bin/afp-metrics.py` (which had the
# more robust parser) and extended to capture session header context
# (parent, session-level `in <subdir>`) the metrics tool didn't need.

# Keywords recognised by the ROOT tokeniser.  Encountering one closes
# the previous stanza and opens a new one.
_ROOT_KEYWORDS = {
    "chapter", "session", "options", "sessions", "directories",
    "theories", "document_files", "document_theories",
    "export_files", "export_classpath", "global",
    "description", "in",
}

_COMMENT_RE = re.compile(r"\(\*.*?\*\)", re.DOTALL)
_OLD_DESC_RE = re.compile(r"\{\*.*?\*\}", re.DOTALL)
_ID_RE = re.compile(r"[A-Za-z0-9_./\-]+")
_OPEN_CARTOUCHE, _CLOSE_CARTOUCHE = r"\<open>", r"\<close>"
_TAG_RE = re.compile(r"\\<[A-Za-z_^]+>")


def _strip_cartouches(text: str) -> str:
    """Remove Isabelle cartouches `\\<open>...\\<close>` (nestable).

    Content inside a cartouche is descriptive text, not ROOT syntax,
    so it must not be tokenised — otherwise a comment like
    `\\<comment> \\<open>...session... \\<close>` spawns phantom
    sessions from words inside the prose.
    """
    out: list[str] = []
    i, n = 0, len(text)
    depth = 0
    while i < n:
        if text.startswith(_OPEN_CARTOUCHE, i):
            depth += 1
            i += len(_OPEN_CARTOUCHE)
            continue
        if text.startswith(_CLOSE_CARTOUCHE, i):
            if depth > 0:
                depth -= 1
            i += len(_CLOSE_CARTOUCHE)
            continue
        if depth == 0:
            out.append(text[i])
        i += 1
    return "".join(out)


def _strip_comments(text: str) -> str:
    text = _COMMENT_RE.sub(" ", text)
    text = _strip_cartouches(text)
    text = _OLD_DESC_RE.sub(" ", text)  # legacy `{* ... *}` description
    text = _TAG_RE.sub(" ", text)  # lone `\<comment>` etc. (after cartouches)
    return text


def _tokenize_root(text: str):
    """Yield (kind, value) tokens from a ROOT file source.

    kind ∈ {"kw", "id", "str"}.  Both `[...]` and `(...)` are skipped
    wholesale — they hold options (`[document = false]`,
    `(slow very_slow)`) and theory annotations (`Main (global)`) that
    shouldn't leak their internal identifiers into the token stream.
    `=` and `+` are dropped (structural session-header punctuation).
    """
    text = _strip_comments(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c.isspace():
            i += 1
            continue
        if c == '"':
            j = text.find('"', i + 1)
            if j < 0:
                return
            yield ("str", text[i + 1:j])
            i = j + 1
            continue
        if c in "[(":
            close = "]" if c == "[" else ")"
            opener = c
            depth = 1
            j = i + 1
            while j < n and depth:
                if text[j] == opener:
                    depth += 1
                elif text[j] == close:
                    depth -= 1
                j += 1
            i = j
            continue
        if c in "=+)]":
            i += 1
            continue
        m = _ID_RE.match(text, i)
        if not m:
            i += 1
            continue
        tok = m.group()
        i = m.end()
        yield ("kw" if tok in _ROOT_KEYWORDS else "id", tok)


def parse_root_sessions(root_path: Path) -> list[SessionInfo]:
    """Parse every `session ...` declaration from a ROOT file.

    Returns sessions in declaration order.  Empty if the file is
    missing or declares no sessions (e.g. `afp/thys/ROOT`, which is
    a `chapter_definition` file).

    The parser handles:

    * Session-level `in <subdir>` and parent (after `=`).
    * Per-theory `in <subdir>` overrides (`theory Foo in "sub"`).
    * `(...)`-wrapped options (`session NAME (timing)`,
      `theory Main (global)`) — content inside parens is dropped
      wholesale, so `(global)` doesn't spawn a phantom `global`
      theory name.
    * `(* comments *)`, `\\<open>...\\<close>` cartouches, and
      `{* legacy descriptions *}` — all stripped before tokenising,
      so a session declaration inside a comment isn't mis-parsed
      as real syntax.
    """
    if not root_path.exists():
        return []
    text = root_path.read_text(errors="replace")
    toks = list(_tokenize_root(text))
    out: list[SessionInfo] = []
    abs_root = root_path.resolve()

    cur: SessionInfo | None = None
    state: str | None = None
    pending_theory: tuple[str, str | None] | None = None

    def flush_pending() -> None:
        nonlocal pending_theory
        if pending_theory is not None and cur is not None:
            cur.theories.append(pending_theory)
            pending_theory = None

    i = 0
    while i < len(toks):
        kind, val = toks[i]
        if kind == "kw":
            flush_pending()
            if val == "session":
                if cur is not None:
                    out.append(cur)
                # New session: next id/str is its name.
                cur = SessionInfo(
                    name="<anon>", root_path=abs_root,
                    in_subdir=None, parent=None)
                if i + 1 < len(toks) and toks[i + 1][0] in ("id", "str"):
                    cur.name = toks[i + 1][1]
                    i += 2
                else:
                    i += 1
                state = "session_header"
                continue
            if val == "in":
                # Either session-level (right after `session NAME`) or
                # per-theory (right after a pending theory name).
                target_arg = (i + 1 < len(toks)
                              and toks[i + 1][0] in ("id", "str"))
                if state == "session_header" and target_arg and cur is not None:
                    cur.in_subdir = toks[i + 1][1]
                    i += 2
                    continue
                if pending_theory is not None and target_arg:
                    pending_theory = (pending_theory[0], toks[i + 1][1])
                    i += 2
                    continue
                i += 1
                continue
            # Any other keyword changes the active stanza.
            state = val
            i += 1
            continue
        # id / str token
        if cur is not None:
            if state == "session_header":
                # First id/str after `session NAME [in DIR]?` is parent.
                cur.parent = val
                state = None
            elif state == "theories":
                flush_pending()
                pending_theory = (val, None)
            elif state == "directories":
                cur.directories.append(val)
            elif state == "sessions":
                cur.used_sessions.append(val)
            # Other states (options, description, ...) — ignore values.
        i += 1
    flush_pending()
    if cur is not None:
        out.append(cur)
    return out


def resolve_session_theory(session: SessionInfo,
                           theory_entry: tuple[str, str | None] | str,
                           ) -> Path | None:
    """Resolve a session-owned theory to its `.thy` file on disk.

    ``theory_entry`` is either a ``(name, dir_override)`` tuple (the
    shape `SessionInfo.theories` produces) or a bare ``name`` string
    (for ad-hoc callers).  Search order:

    1. ``session_dir / dir_override / NAME.thy``  (if dir_override set)
    2. ``session_dir / NAME.thy``
    3. ``session_dir / D / NAME.thy`` for each ``D`` in
       ``session.directories``
    4. ``rglob("NAME.thy")`` under ``session_dir`` — last-resort
       fallback for unusual AFP layouts (only succeeds on a unique
       match).
    """
    if isinstance(theory_entry, tuple):
        name, dir_override = theory_entry
    else:
        name, dir_override = theory_entry, None
    base = session.session_dir
    candidates: list[Path] = []
    if dir_override is not None:
        candidates.append(base / dir_override / f"{name}.thy")
    candidates.append(base / f"{name}.thy")
    for d in session.directories:
        candidates.append(base / d / f"{name}.thy")
    for c in candidates:
        if c.exists():
            return c
    leaf = Path(name).name + ".thy"
    matches = list(base.rglob(leaf))
    return matches[0] if len(matches) == 1 else None


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
