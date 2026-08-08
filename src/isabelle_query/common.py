"""Shared helpers for the bin/ Python tooling.

Python analogue of bin/common.sh: parse the project's Isabelle ROOT file
to enumerate the declared theories and the .thy files on disk that
correspond to them.  This is the *single source of truth* for "which
theory files belong to the build" — scripts must not glob
t/**/*.thy independently, because:

  - WIP / orphan / archived .thy files on disk are then silently
    included.
  - Adding a new theory subdirectory (e.g. t/sub/) requires
    editing every script that hard-codes the glob list.

Public API
----------

default_t_dir(start=None)
    Resolve the Isabelle session directory to index: ``$ISABELLE_QUERY_ROOT`` if
    set, else the nearest ``.isabelle-query`` marker file at or above
    ``start`` (default: cwd), else the nearest directory containing a
    ``ROOT`` file, else ``start``.  This is the project-root entry point
    used whenever a caller doesn't pass an explicit ``t_dir``.

run_guarded(label, thunk)  — DEPRECATED (unused in this repo)
    Run thunk() for a best-effort side task that must never break its
    caller; on exception, warn `<label>: skipped (...)` to stderr and
    return None.  Used by the build-trajectory capture.

parse_root_theories(root_path)  — DEPRECATED (single-ROOT API)
    Return the ordered list of theory names declared under the ROOT
    file's `theories` block.

parse_root_directories(root_path)  — DEPRECATED (single-ROOT API)
    Return the ordered list of subdirectory names declared under the
    ROOT file's `directories` clause.

iter_thy_files(t_dir=None)  — DEPRECATED (single-ROOT API)
    Return the ordered list of absolute Path objects for .thy files
    declared in t_dir/ROOT.  Each theory is resolved at the session
    root first, then in each declared subdirectory.  Returns []
    gracefully if t_dir/ROOT is missing.

resolve_thy_file(name, t_dir=None)  — DEPRECATED (single-ROOT API)
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

classify_import(name, session, stem_index=None)
    Classify one `imports` target as `in_entry` (a bare import resolving
    inside the session's directory), `cross_entry` (a dot-qualified theory
    in another session — another AFP entry), or `infra` (Isabelle
    distribution / base library).  Name-only; no prover.

session_theories(session, follow_imports=True)
    The `(name, path)` theories belonging to a session: the ROOT-declared
    theories, plus (by default) the transitive closure of their *in-entry*
    imports — so an entry that declares leaf theories and imports the rest
    is counted in full, without pulling in other entries or base library.

discover_roots(root_dir)
    Walk a directory tree and return every ROOT file found.

iter_sessions(root_dir)
    Convenience: discover_roots ∘ parse_root_sessions, flattened.

Default project root: callers that don't pass an explicit ``t_dir``
get :func:`default_t_dir`'s result — ``$ISABELLE_QUERY_ROOT``, else the nearest
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


MARKER_NAME = ".isabelle-query"


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

    1. ``$ISABELLE_QUERY_ROOT``, if set (``~`` expanded, resolved to absolute).
    2. The nearest project marker file (``.isabelle-query``) at or above
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
    the marker file (or ``--root`` / ``$ISABELLE_QUERY_ROOT``) resolves.  This
    replaces the old hard-coded ``PROJECT_ROOT/t``: the tool assumes no
    particular directory name, and no longer expects to live in a sibling
    ``bin/`` of the project it queries.
    """
    env = os.environ.get("ISABELLE_QUERY_ROOT")
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
    build_record cannot).

    DEPRECATED — unused in this repository; its build-trajectory callers
    live in the upstream ``bin/`` tooling, not here.  Retained pending that
    tooling's review.  Do not add new callers.
    """
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
    other than `exclude` (the block we're currently parsing).

    Supports the DEPRECATED single-ROOT parsers (:func:`parse_root_theories`,
    :func:`parse_root_directories`)."""
    for kw in _ROOT_BLOCK_TERMINATORS:
        if kw == exclude:
            continue
        if re.match(rf"{kw}\b", stripped):
            return True
    return False


def parse_root_theories(root_path: Path) -> list[str]:
    """Return the ordered list of theory names from ROOT's `theories`
    block.  Returns [] if the file doesn't exist or has no block.

    DEPRECATED — legacy single-ROOT API, superseded by the multi-session
    API (:func:`parse_root_sessions` / :func:`resolve_session_theory` /
    :func:`discover_roots`).  No in-repo callers remain; retained only for
    the upstream ``bin/`` tooling pending its review.  Do not add new callers.
    """
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
    forms.  Returns [] if absent.

    DEPRECATED — legacy single-ROOT API, superseded by the multi-session
    API (:func:`parse_root_sessions` / :func:`resolve_session_theory` /
    :func:`discover_roots`).  No in-repo callers remain; retained only for
    the upstream ``bin/`` tooling pending its review.  Do not add new callers.
    """
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

    DEPRECATED — legacy single-ROOT API, superseded by the multi-session
    API (:func:`resolve_session_theory` / :func:`discover_roots`).  No
    in-repo callers remain; retained only for the upstream ``bin/`` tooling
    pending its review.  Do not add new callers.
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


# The theory header, per Pure's grammar:
#
#     theory NAME imports NAME+ [keywords ...] [abbrevs ...] begin
#
# Anchoring on `theory NAME imports` rather than on a bare `imports` matters
# twice over.  It stops the clause at `keywords`/`abbrevs`, which are part of
# the header and not imports — 105 AFP theories declare a `keywords` block, and
# scanning to `begin` swallowed it whole (`AutoCorres` "imported" `keywords`,
# `autocorres`, `::`, `thy_decl`, `and`).  And it refuses to match a file with
# no imports clause at all (`theory Pure begin`), where a lone `imports`
# further down the file would otherwise pair with some locale's `begin`.
# The optional `%tag` is a document-preparation marker Isar allows after any
# command keyword — `theory %invisible All` (AFP's AODV/All.thy).  Anchoring on
# `theory NAME imports` without allowing it silently loses the whole header:
# AODV declares only `All` in its ROOT, so the 72 theories it imports fell out
# of discovery entirely.
_THY_HEADER_RE = re.compile(
    r'\btheory\b(?:\s*%\s*(?:"[^"\n]*"|[\w\'.]+))*'
    r'\s+(?:"[^"\n]*"|[^\s"]+)\s+imports\b(.*?)'
    r'\b(?:begin|keywords|abbrevs)\b', re.DOTALL)


def parse_thy_imports(thy_path: Path) -> list[str]:
    r"""Return the ordered list of theory names from a .thy file's
    `imports ... begin` clause.

    Handles plain names (`Main`) and quoted qualified names
    (`"HOL-Library.FuncSet"`).  Returns the raw import names; callers
    decide whether each is in-project or external (cross-session) by
    cross-referencing against a session's `parse_root_theories` list.
    Returns [] if the file is missing or has no imports clause.

    The whole file is scanned, not a fixed head window.  A window is the
    wrong shape for this: `section`/`text` blocks may legally precede the
    `theory` command, and an AFP title-and-history block routinely pushes the
    header past any constant one would pick — at 50 lines it lost the clause
    on 62 of the AFP's 9,604 theories (worst: `Cook_Levin/Basics.thy` at line
    199), which is a wrong `deps` answer and, worse, silently drops theories
    from the import closure `session_theories` builds discovery from.

    Comments and cartouches are stripped first, so prose in a leading
    `text \<open>...\<close>` cannot contribute a phantom `imports` or an
    early `begin`.
    """
    if not thy_path.exists():
        return []
    try:
        text = thy_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    m = _THY_HEADER_RE.search(_strip_comments(text))
    if not m:
        return []
    tokens = re.findall(r'"([^"]+)"|(\S+)', m.group(1))
    return [a or b for a, b in tokens]


def iter_thy_files(t_dir: Path | None = None) -> list[Path]:
    """Return the ordered list of .thy files declared by ROOT(s) under t_dir.

    Two layouts are supported transparently:

    * **Single ROOT** (``t_dir/ROOT`` exists): order matches that ROOT's
      `theories` block; each theory is resolved at the session root first,
      then in declared subdirectories.
    * **Multi-ROOT** (no ``t_dir/ROOT``, but ROOTs in subdirectories,
      e.g. ``t_dir/a/ROOT`` and ``t_dir/b/ROOT``): every
      session declared by every ROOT under ``t_dir`` is enumerated (via
      ``iter_sessions``) and its theories resolved against the declaring
      session's directory.  Results are deduplicated by resolved path so
      a theory reached through more than one session appears once.

    Theory names with no matching file on disk are silently skipped
    (mirrors bin/common.sh's get_build_files behaviour, so callers can
    run against partial trees during a refactor).

    DEPRECATED — legacy single-ROOT API, superseded by the multi-session
    API (:func:`iter_sessions` / :func:`resolve_session_theory`).  No
    in-repo callers remain; retained only for the upstream ``bin/`` tooling
    pending its review.  Do not add new callers.
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


_CARTOUCHE_RE = re.compile(r'\\<open>|\\<close>')


def _strip_cartouches(text: str) -> str:
    """Remove Isabelle cartouches `\\<open>...\\<close>` (nestable).

    Content inside a cartouche is descriptive text, not ROOT syntax,
    so it must not be tokenised — otherwise a comment like
    `\\<comment> \\<open>...session... \\<close>` spawns phantom
    sessions from words inside the prose.

    Driven by the delimiter positions rather than by a per-character walk:
    the two are equivalent (the output is exactly the depth-0 text between
    delimiters, delimiters themselves dropped), but a ROOT file has a handful
    of cartouches where a `.thy` corpus has megabytes of characters, and
    `parse_thy_imports` now runs this over whole theory files.
    """
    if _OPEN_CARTOUCHE not in text and _CLOSE_CARTOUCHE not in text:
        return text
    out: list[str] = []
    depth, pos = 0, 0
    for m in _CARTOUCHE_RE.finditer(text):
        if depth == 0:
            out.append(text[pos:m.start()])
        if m.group() == _OPEN_CARTOUCHE:
            depth += 1
        elif depth > 0:
            depth -= 1
        pos = m.end()
    if depth == 0:
        out.append(text[pos:])
    return "".join(out)


_COMMENT_TOKEN_RE = re.compile(r'\(\*|\*\)')


def _strip_block_comments(text: str) -> str:
    r"""Remove `(* ... *)` comments, which **nest** in Isabelle.

    `_COMMENT_RE` (non-greedy `\(\*.*?\*\)`) stops at the first `*)`, so a
    nested comment leaves the outer closer behind as live text.  In
    `Universal_Turing_Machine.GeneratedCode` the header

        (*   "HOL-Library.Code_Target_Numeral" (* see codegen.pdf *) *)

    left a stray `*)` inside the `imports` clause, and `parse_thy_imports`
    duly reported `*)` as an imported theory.  Comments are replaced by a
    space, not deleted, so `imports(*c*)Bar` still tokenises as two words; an
    unterminated comment swallows the remainder, as Isabelle's lexer does.
    """
    if "(*" not in text:
        return text
    out: list[str] = []
    depth, pos = 0, 0
    for m in _COMMENT_TOKEN_RE.finditer(text):
        if m.group() == "(*":
            if depth == 0:
                out.append(text[pos:m.start()])
                out.append(" ")
            depth += 1
        elif depth > 0:
            depth -= 1
            if depth == 0:
                pos = m.end()
    if depth == 0:
        out.append(text[pos:])
    return "".join(out)


def _strip_comments(text: str) -> str:
    text = _strip_block_comments(text)
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


# Isabelle *distribution* session names (and unqualified base theories) — an
# import qualified with one of these, or the bare `Main`/`Pure`/`Complex_Main`,
# names base-library *infrastructure*, not a corpus theory.  Everything else
# that is dot-qualified names another *session* (in an AFP census, another
# entry).  The `HOL` prefix covers the whole `HOL-Library` / `HOL-Analysis` /
# … family without listing each.
_INFRA_ROOTS = frozenset({
    "Pure", "Main", "Complex_Main", "Tools", "Doc",
    "FOL", "FOLP", "ZF", "CTT", "LCF", "CCL", "Cube", "Sequents",
})


def _import_session(name: str) -> "str | None":
    """The session prefix of a dot-qualified theory import
    (``HOL-Library.FuncSet`` -> ``HOL-Library``), or None for a bare,
    unqualified import (``AODV_Basic``, ``Main``).  Isabelle qualifies a
    theory as ``<session>.<theory>`` and neither part contains a dot, so the
    single dot is the split point."""
    raw = name.strip().strip('"')
    return raw.rsplit(".", 1)[0] if "." in raw else None


def classify_import(name: str, session: SessionInfo,
                    stem_index: "dict[str, Path] | None" = None,
                    importer: "Path | None" = None,
                    ) -> "tuple[str, Path | None]":
    """Classify an ``imports`` target relative to the session that imports it.

    Returns ``(kind, path)`` where ``kind`` is:

    * ``"in_entry"`` — a theory inside the entry's own ROOT directory,
      reached as a *bare* name (``Aodv``), a *self-qualified* name
      (``AODV.Aodv``), or a *relative path* (``"variants/a/Foo"``,
      ``"../../Aodv_Basic"``); ``path`` is the resolved ``.thy``.
    * ``"infra"`` — Isabelle distribution / base library (``Pure``, ``Main``,
      ``Complex_Main``, the ``HOL*`` family, ``FOL``/``ZF``/…), or a bare /
      path import that resolves nowhere in the entry (an external base).
    * ``"cross_entry"`` — a dot-qualified theory in *another* (non-distribution)
      session — in an AFP walk, another entry, which a per-entry census must
      not double-count (AODV's ``AWN.OClosed_Transfer``, from its parent
      session).

    Isabelle addresses a *same-entry* theory three ways (bare, self-qualified,
    or by relative path) and every *cross-session* reference by a
    ``<session>.<theory>`` qualifier — so the name (plus, for a path import,
    the importing file's location) classifies without a prover.  ``stem_index``
    (theory-stem -> path for the entry's directory) resolves bare / qualified
    names in O(1) when supplied; ``importer`` is the importing theory's path,
    used to resolve relative-path imports and confined to the entry's ROOT
    directory so a ``../`` cannot escape into a sibling entry.
    """
    raw = name.strip().strip('"')
    entry_root = session.root_path.parent

    if "/" in raw:  # relative-path import (quoted), e.g. "../JVM/JVMExec"
        base_dir = importer.parent if importer is not None else session.session_dir
        cand = (base_dir / f"{raw}.thy").resolve()
        if cand.exists() and entry_root in cand.parents:
            return ("in_entry", cand)
        # Fall back to the leaf theory name within the entry's own tree.
        leaf = Path(raw).name
        q = stem_index.get(leaf) if stem_index is not None else None
        return ("in_entry", q) if q is not None else ("infra", None)

    sess = _import_session(raw)
    if sess is None:  # bare: an in-entry theory, or an unqualified base
        if raw in _INFRA_ROOTS:
            return ("infra", None)
        p = (stem_index.get(raw) if stem_index is not None
             else resolve_session_theory(session, raw))
        return ("in_entry", p) if p is not None else ("infra", None)

    if sess == session.name:  # self-qualified sibling (AODV.Aodv within AODV)
        thy = raw.rsplit(".", 1)[1]
        p = (stem_index.get(thy) if stem_index is not None
             else resolve_session_theory(session, thy))
        return ("in_entry", p) if p is not None else ("infra", None)
    if sess in _INFRA_ROOTS or sess.startswith("HOL"):
        return ("infra", None)
    return ("cross_entry", None)


def session_theories(session: SessionInfo, *,
                     follow_imports: bool = True,
                     ) -> list[tuple[str, Path]]:
    """The ``(name, path)`` theories that belong to a session.

    Always includes the theories declared in the session's ROOT ``theories``
    block (resolved via :func:`resolve_session_theory`).  When
    ``follow_imports`` (the default), also includes the transitive closure of
    their **in-entry** imports — bare-name imports that resolve to a ``.thy``
    within the session's own directory — so a session that declares a few
    leaf theories and pulls the rest in via ``imports`` (common in the AFP:
    ``AODV`` declares 1, builds 73) is counted in full.

    Cross-entry (dot-qualified, another session) and infrastructure
    (``HOL*``/``Pure``/…) imports are **not** followed: over the whole AFP
    each entry then contributes its own theories exactly once, with no
    double-counting of shared dependency entries or the base library.

    Import-reachability, not a bare ``*.thy`` glob, bounds the set — so
    orphan / scratch / WIP theories that no declared root imports are
    excluded, matching what ``isabelle build`` actually compiles.  BFS order
    from the declared roots; deduplicated by resolved path.
    """
    stem_index: dict[str, Path] = {}
    base = session.session_dir
    if follow_imports and base.is_dir():
        # One directory scan → O(1) in-entry resolution for every bare import.
        for p in base.rglob("*.thy"):
            stem_index.setdefault(p.stem, p)

    out: list[tuple[str, Path]] = []
    seen: set[Path] = set()
    frontier: list[Path] = []
    for entry in session.theories:
        p = resolve_session_theory(session, entry)
        if p is None:
            continue
        rp = p.resolve()
        if rp in seen:
            continue
        seen.add(rp)
        out.append((entry[0], p))
        frontier.append(p)

    if follow_imports:
        while frontier:
            p = frontier.pop()
            for imp in parse_thy_imports(p):
                kind, q = classify_import(imp, session, stem_index, importer=p)
                if kind != "in_entry" or q is None:
                    continue
                rq = q.resolve()
                if rq in seen:
                    continue
                seen.add(rq)
                out.append((q.stem, q))
                frontier.append(q)
    return out


def discover_roots(root_dir: Path) -> list[Path]:
    """Find the ROOT files under `root_dir`, matching `isabelle build -D`.

    When `root_dir` carries an Isabelle `ROOTS` index, descend **only**
    into the subdirectories it lists (one entry per line, relative to the
    index), exactly as `isabelle build -D <root_dir>` does.  This keeps
    scan scope identical to build scope: a sibling session directory
    deliberately omitted from `ROOTS` is excluded here just as the build
    excludes it.  Without this, a bare recursive glob silently widens
    scope to ROOT files the build never compiles.

    When `root_dir` has no `ROOTS` index, fall back to a recursive walk
    (skipping hidden *sub*directories such as `.git`) so single-ROOT and
    ad-hoc layouts still resolve.  Results sorted for stable ordering.
    ROOT files that don't declare any sessions (e.g. AFP's `afp/thys/ROOT`
    chapter-definition file) are still returned — `parse_root_sessions`
    returns [] for them, so they're harmless.
    """
    if not root_dir.exists():
        return []
    out: list[Path] = []
    seen_roots: set[Path] = set()

    def add_root(d: Path) -> None:
        root = d / "ROOT"
        if root.is_file():
            rp = root.resolve()
            if rp not in seen_roots:
                seen_roots.add(rp)
                out.append(rp)

    if not (root_dir / "ROOTS").is_file():
        # No ROOTS index: recursive walk, skipping hidden *sub*directories.
        # The hidden test is judged relative to root_dir, so a hidden ancestor
        # of root_dir — or a `..` in the path as given — does not suppress the
        # whole walk (the old absolute-`parts` test did: a relative root like
        # `../proj` made every "`..`/..." hit look hidden and found nothing).
        for path in sorted(root_dir.rglob("ROOT")):
            if any(part.startswith(".")
                   for part in path.relative_to(root_dir).parts):
                continue
            if path.is_file():
                rp = path.resolve()
                if rp not in seen_roots:
                    seen_roots.add(rp)
                    out.append(rp)
        return sorted(out)

    # ROOTS index present: descend only into listed subdirectories.
    visited_dirs: set[Path] = set()

    def visit(d: Path) -> None:
        rd = d.resolve()
        if rd in visited_dirs:
            return
        visited_dirs.add(rd)
        add_root(d)
        index = d / "ROOTS"
        if index.is_file():
            for line in index.read_text().splitlines():
                entry = line.strip()
                if not entry or entry.startswith("#"):
                    continue
                sub = d / entry
                if sub.is_dir():
                    visit(sub)

    visit(root_dir)
    return sorted(out)


def iter_sessions(root_dir: Path) -> list[SessionInfo]:
    """Return every session declared by any ROOT under `root_dir`.

    Order: ROOTs in `discover_roots` order; sessions within each
    ROOT in declaration order.
    """
    out: list[SessionInfo] = []
    for root_path in discover_roots(root_dir):
        out.extend(parse_root_sessions(root_path))
    return out


def resolve_base_logic(name: str, parents: dict[str, str]) -> str:
    """Follow `name`'s parent chain in `parents` (session → parent session) to
    its **root** — the first ancestor that is not itself a key, i.e. a
    distribution session not defined in the corpus (`HOL`, `HOL-Analysis`,
    `ZF-Constructible`, `Pure`, …).

    Corpus-wide: `parents` must map every corpus session to its declared parent
    (build it once from `iter_sessions`), so a session two hops from its base
    (`Forcing` → `ZF-Constructible`, `Independence_CH` → `Transitive_Models` →
    … → `ZF-Constructible`) resolves to the *root*, not the immediate parent —
    the classification an immediate-parent test gets wrong.  Cycle-guarded.
    """
    seen: set[str] = set()
    cur = name
    while cur in parents and parents[cur] and parents[cur] not in seen:
        seen.add(cur)
        cur = parents[cur]
    return cur


def is_hol_base(base: str) -> bool:
    """Whether a base logic (a `resolve_base_logic` root) is HOL-family.

    Stated as a HOL **allowlist**: every HOL session name begins `HOL` (`HOL`,
    `HOL-Library`, `HOL-Analysis`, and HOL-with-a-ZF-flavour like `HOL-ZF`);
    `Pure`, `ZF`/`ZF-*`, `FOL`, `CTT`, … are other object logics.  An
    unrecognised base is therefore treated as **non-HOL** — flagged, never
    silently HOL — which is the safe direction when the answer gates a
    HOL-specific table (the notation table behind `const_canon_est`, the census
    union): over-restrict rather than mis-apply.
    """
    return base.startswith("HOL")


# The Isabelle-distribution object logics that are *not* HOL — the non-HOL bases a
# `resolve_base_logic` root can name.  Recognisable by name even from a single
# session's scope (their names are stable distribution ids, unlike an arbitrary
# in-corpus parent session), which is what makes `is_known_nonhol_base` robust
# where `is_hol_base` cannot reach the root (a cross-session parent out of scope).
# These are the exact non-HOL sessions declared `= Pure +` in the Isabelle2025-2
# src tree (FOL, ZF, HOL, … are all siblings on the Pure meta-logic; FOL is *not*
# HOL-based); `Pure` itself is the meta-logic.  ZF and FOL variants (`ZF-*`,
# `FOLP`) are caught by prefix in `is_known_nonhol_base`.
_NONHOL_DISTRIBUTION_BASES = frozenset({
    "Pure", "FOL", "FOLP", "CTT", "Sequents", "CCL", "Cube", "LCF"})


def is_known_nonhol_base(base: str) -> bool:
    """Whether a base logic is a **positively identified** non-HOL object logic.

    The complement of :func:`is_hol_base`'s question, and it defaults the *other*
    way: `is_hol_base` requires positive HOL evidence (for a HOL-only table that
    must never mis-apply), whereas this requires positive *non-HOL* evidence — so
    an unknown base (e.g. an out-of-scope parent *session* name like
    `Multitape_TM_Substrate`, reached under ``-R <sub-session>``) is **not** flagged
    non-HOL.  Use this where HOL is the sensible default and only a known non-HOL
    logic should opt out (the interactive namespace fallback): matches `Pure`,
    `ZF`/`ZF-*`, `FOL`/`FOLP`, `CTT`, `Sequents`, …; leaves everything else to the
    HOL default."""
    return (base in _NONHOL_DISTRIBUTION_BASES
            or base.startswith("ZF") or base.startswith("FOL"))
