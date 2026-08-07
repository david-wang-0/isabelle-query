#!/usr/bin/env python3
r"""Probe: how much does `parse_thy_imports`' 50-line head window miss?

`common.parse_thy_imports` reads only the first 50 lines of a `.thy` and
searches them for `imports ... begin`.  A file whose header comment is longer
than that -- AFP entries routinely carry a title/author/history block -- has
its `theory` command past the window, so the function returns `[]`.

That is not only a wrong `deps` answer.  `session_theories` builds the load
set from the transitive closure of in-entry imports, so a missed clause can
silently drop theories from DISCOVERY, which is the guarantee in CLAUDE.md
("discovery loads what `isabelle build` compiles").

This counts, over a corpus: files with an imports clause inside the window,
past it (MISSED), and none at all.

Usage:  probe_import_window.py [ROOT] [--window N]
"""
from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from isabelle_query.common import (  # noqa: E402
    _IMPORTS_RE, iter_sessions, session_theories,
)

_THEORY_RE = re.compile(r'^\s*theory\b', re.M)


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    window = 50
    for a in sys.argv[1:]:
        if a.startswith("--window"):
            window = int(a.partition("=")[2] or 50)
    root = Path(args[0]).expanduser() if args else Path.home() / "repos/afp/thys"

    seen: set[Path] = set()
    tally: Counter[str] = Counter()
    missed: list[tuple[int, str]] = []
    lost: list[tuple[str, str, Path]] = []
    for session in iter_sessions(root):
        for name, path in session_theories(session):
            if path in seen or not path.is_file():
                continue
            seen.add(path)
            try:
                lines = path.read_text(encoding="utf-8",
                                       errors="replace").splitlines()
            except OSError:
                continue
            head = "\n".join(lines[:window])
            if _IMPORTS_RE.search(head):
                tally["in window"] += 1
                continue
            whole = "\n".join(lines)
            m = _IMPORTS_RE.search(whole)
            if m is None:
                tally["no imports clause"] += 1
                continue
            line_no = whole[:m.start()].count("\n") + 1
            tally["MISSED (past the window)"] += 1
            missed.append((line_no, f"{path.parent.name}/{path.name}"))
            # Would discovery have lost anything?  An in-entry import is a
            # bare name resolving to a sibling `.thy`; if ROOT does not also
            # declare it, the closure would never have reached it.
            for a, b in re.findall(r'"([^"]+)"|(\S+)', m.group(1)):
                target = a or b
                if "." in target or "/" in target:
                    continue
                sib = path.parent / f"{target}.thy"
                if sib.is_file():
                    lost.append((f"{path.parent.name}/{path.name}", target,
                                 sib))

    print(f"{len(seen):,} theory files under {root}  (window = {window} lines)")
    for k, v in tally.most_common():
        print(f"  {k:<26} {v:>7,}  {100.0 * v / max(len(seen), 1):5.2f}%")
    if missed:
        missed.sort(reverse=True)
        print(f"\n  imports clause found at line ... (worst 12 of "
              f"{len(missed):,}):")
        for line_no, where in missed[:12]:
            print(f"    line {line_no:>4}  {where}")
        need = max(l for l, _ in missed)
        print(f"\n  a window of {need} lines would cover every one of them")
    absent = [(w, t) for w, t, sib in lost if sib not in seen]
    print(f"\n  in-entry imports hidden by the window: {len(lost)}"
          f"; of those NOT otherwise discovered: {len(absent)}")
    for w, t in absent[:10]:
        print(f"    {w} imports {t} — never loaded")


if __name__ == "__main__":
    main()
