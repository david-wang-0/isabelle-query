#!/usr/bin/env python3
"""Dump the Python implementation's entry set / theory set for a root dir.

The oracle side of the P1 differential harness (`dev/entrydiff.sh`).  It runs
the FROZEN reference implementation in this checkout's `src/` — not the
installed wheel — so a diff always compares the Scala engine against the code
that is in the tree.

    dump_oracle.py entries ROOT_DIR [--spans] [--bindings]
    dump_oracle.py theories ROOT_DIR

Record format for `entries` is `scripts/dump_entries.py`'s, with the theory key
generalised from `<afp entry>/<stem>` to the theory's path relative to
ROOT_DIR, extension dropped:

    Foo/Bar:12:LEMMA:baz
    Foo/Bar:12:LEMMA:baz:src=10-30:decl_end=13:proof=14:body_end=20

`--bindings` adds the extra names one declaration binds and the entry's target,
which no user-facing command prints but P2/P3 depend on:

    ...:bind=r1/rule,r2/rule:target=hpk

INTERPRETER.  `isabelle_layout` is a runtime dependency of the reference
implementation and lives in the venv the installed `query` command runs from,
so this script re-executes itself under the interpreter named by that command's
shebang when it is not already importable.  Nothing about that path is written
down here; it is read from `query` at run time.

The discovery + parse loop below is `parsing._sections_from_dir` written out,
with one difference: a theory whose parse raises is skipped rather than
aborting the sweep, which is what the Scala side does and what a whole-corpus
run needs.
"""
import importlib.util
import os
import shutil
import subprocess
import sys
from pathlib import Path

_REPO = Path(__file__).resolve().parent.parent


def _query_interpreter():
    """The interpreter the installed `query` console script runs under."""
    exe = shutil.which("query") or shutil.which("isabelle-query")
    if not exe:
        return None
    try:
        with open(exe, "rb") as f:
            first = f.readline().decode("utf-8", "replace").strip()
    except OSError:
        return None
    if not first.startswith("#!"):
        return None
    words = first[2:].strip().split()
    if not words:
        return None
    if words[0].endswith("env"):
        return shutil.which(words[1]) if len(words) > 1 else None
    return words[0]


def _ensure_layout():
    if importlib.util.find_spec("isabelle_layout") is not None:
        return
    # A venv's `bin/python3` is a symlink to the base interpreter, so realpath
    # equality would say "same interpreter" and skip the exec that supplies the
    # venv's site-packages.  Compare the literal paths, and guard re-entry with
    # a marker instead.
    interp = _query_interpreter()
    if interp and not os.environ.get("QUERY_ORACLE_REEXEC"):
        os.environ["QUERY_ORACLE_REEXEC"] = "1"
        os.execv(interp, [interp, os.path.abspath(__file__)] + sys.argv[1:])
    sys.exit("dump_oracle: isabelle_layout not importable, and no `query` "
             "console script on PATH to borrow an interpreter from")


_ensure_layout()
sys.path.insert(0, str(_REPO / "src"))

from isabelle_layout import (  # noqa: E402
    discover_roots,
    parse_root_sessions,
    session_theories,
)
from isabelle_query import parsing  # noqa: E402


def discovered(root: Path):
    """(name, path) for every theory the build would compile, in ROOT-walk
    order — `_sections_from_dir`'s first phase."""
    pairs = []
    session_of = {}
    roots = discover_roots(root)
    if roots:
        for root_path in roots:
            for session in parse_root_sessions(root_path):
                for name, thy_path in session_theories(session):
                    pairs.append((name, thy_path))
                    session_of.setdefault(thy_path.resolve(), session.name)
    else:
        for thy_path in sorted(root.rglob("*.thy")):
            pairs.append((thy_path.stem, thy_path))
    return pairs, session_of


def sections_for(root: Path):
    pairs, session_of = discovered(root)
    parsing._populate_custom_commands(pairs)
    seen = set()
    sections = []
    for name, thy_path in pairs:
        try:
            parsing._add_one_section(name, thy_path, seen, sections,
                                     session=session_of.get(thy_path.resolve()))
        except Exception:  # noqa: BLE001  (a corpus sweep must not stop here)
            continue
    return sections


def rel_key(path: Path, base: Path) -> str:
    return os.path.relpath(str(Path(path).resolve()), str(base))


def cmd_entries(root: Path, spans: bool, bindings: bool) -> None:
    base = root.resolve()
    keyed = []
    for sec in sections_for(root):
        rel = rel_key(sec.path, base)
        keyed.append((rel[:-4] if rel.endswith(".thy") else rel, sec))
    keyed.sort(key=lambda kv: kv[0])
    out = sys.stdout
    for key, sec in keyed:
        for e in sec.entries:
            rec = f"{key}:{e.thy_line}:{e.tag}:{e.name}"
            if spans:
                rec += (f":src={e.src_start}-{e.thy_end}"
                        f":decl_end={e.decl_end_line}:proof={e.proof_line}"
                        f":body_end={e.body_end_line}")
            if bindings:
                rec += (":bind=" + ",".join(f"{n}/{k}" for n, k in e.bindings)
                        + f":target={e.target}")
            out.write(rec + "\n")


def cmd_theories(root: Path) -> None:
    base = root.resolve()
    pairs, _ = discovered(root)
    seen = set()
    out = []
    for _name, thy_path in pairs:
        if not thy_path.exists():
            continue
        resolved = thy_path.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        out.append(rel_key(thy_path, base))
    for rel in sorted(out):
        sys.stdout.write(rel + "\n")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        sys.stderr.write(__doc__.split("\n\n")[2] + "\n")
        return 2
    cmd, root = argv[0], Path(argv[1])
    if cmd == "entries":
        cmd_entries(root, "--spans" in argv, "--bindings" in argv)
    elif cmd == "theories":
        cmd_theories(root)
    else:
        sys.stderr.write(f"dump_oracle: unknown command {cmd!r}\n")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
