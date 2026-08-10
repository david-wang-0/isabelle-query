#!/usr/bin/env python3
r"""Probe: is the discovered load set CLOSED under in-entry imports?

CLAUDE.md's guarantee is that discovery loads what `isabelle build` compiles:
each session's ROOT-declared theories plus the transitive closure of their
in-entry `imports`.  That gives a checkable invariant, over the whole corpus,
with no Isabelle:

    if theory T is in the load set, and T imports a bare name that resolves
    to a `.thy` beside it, that file must be in the load set too.

A violation is a silent hole — the theory is compiled by Isabelle and invisible
to `query`, so `summary` undercounts, `deps` is wrong, and any name declared
there is missing from `find` and from the call graph, with no error anywhere.

This is the check that would have caught `[thy-header]`'s `%tag` regression
directly: AODV's ROOT declares only `All`, so mis-parsing that one header took
72 theories out of the set while every count still looked plausible.

Reports each hole as `importer -> missing`, with the session that reached the
importer, since that is what localises the cause.

Usage:  probe_discovery_closure.py [ROOT]

## Why this is not the import-window probe it replaces

It was that probe, which measured how much `parse_thy_imports`' 50-line head
window missed.  v0.6.4 removed the window, so the question is closed and its
answer is pinned in `tests/test_thy_header.py`.  The probe had in fact been
dead since that same commit: it imported `_IMPORTS_RE`, a private regex the
commit deleted.  Reaching past a module's public surface is what made it
breakable, so this one uses only `iter_sessions` / `session_theories` /
`parse_thy_imports` — which survived both that rewrite and the move of the
parser out to `isabelle-layout`.

The half worth keeping was the second one, which asked whether discovery had
lost anything.  That question is still live, and still finds things.
"""
from __future__ import annotations

import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from isabelle_query.common import (  # noqa: E402
    iter_sessions, parse_thy_imports, session_theories,
)


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = Path(args[0]).expanduser() if args else Path.home() / "repos/afp/thys"

    # The load set, and which session(s) reached each theory.
    loaded: set[Path] = set()
    reached_by: dict[Path, set[str]] = defaultdict(set)
    n_sessions = 0
    for session in iter_sessions(root):
        n_sessions += 1
        for _name, path in session_theories(session):
            if path.is_file():
                rp = path.resolve()
                loaded.add(rp)
                reached_by[rp].add(session.name)

    holes: list[tuple[Path, str, Path]] = []
    checked = 0
    for p in sorted(loaded):
        for tok in parse_thy_imports(p):
            # A dot-qualified name addresses another session, and a name that
            # resolves to no sibling file is the base library or another entry
            # — neither is followed, by design.  What must hold is the bare,
            # resolves-beside-me case.
            if "." in tok and "/" not in tok:
                continue
            cand = (p.parent / f"{tok}.thy").resolve()
            if not cand.is_file():
                continue
            checked += 1
            if cand not in loaded:
                holes.append((p, tok, cand))

    print(f"{len(loaded):,} theories discovered over {n_sessions:,} sessions "
          f"under {root}")
    print(f"{checked:,} in-entry imports checked for closure")
    if not holes:
        print("\n  CLOSED — every in-entry import resolves into the load set.")
        return

    def rel(q: Path) -> str:
        return str(q.relative_to(root)) if q.is_relative_to(root) else str(q)

    by_dir: Counter[str] = Counter(imp.parent.name for imp, _, _ in holes)
    print(f"\n  {len(holes)} HOLE(S) — compiled by Isabelle, absent from query:")
    for importer, tok, miss in holes:
        print(f"    {rel(importer)} imports {tok}")
        print(f"      importer reached by: "
              f"{', '.join(sorted(reached_by[importer.resolve()]))}")
        print(f"      missing:             {rel(miss)}")
    print(f"\n  by directory: {dict(by_dir.most_common())}")


if __name__ == "__main__":
    main()
