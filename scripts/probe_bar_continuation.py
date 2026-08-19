#!/usr/bin/env python3
r"""Probe: how many declarations end early at a blank line that a `|`
continues?

A `def`-route declaration ends at the first blank line outside a term.  But a
rule list is routinely spaced out:

    inductive_set seqp_sos ... where
        broadcastT: "..."
      | assignT:    "..."
                                    <- blank, and the scan stops here
      | callT:      "..."
      | guardT:     "..."

`AWN_SOS:14` runs to line 34 and `query` ends it at 26, so `show` renders two
thirds of it, `largest` under-measures it, and four rule names sit outside the
extent `[declared-names]` scans.  A line starting with `|` cannot begin a new
command, so it can only continue the current one.

Reports how many entries would grow, by how many lines, and whether any
NON-`|` continuation shape is common enough to matter.

Usage:  probe_bar_continuation.py [N_ENTRIES]
"""
from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_layout import iter_sessions, session_theories  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"

_DEF_TAGS = {"DEF", "ABBREV", "FUN", "IND", "INDSET"}
_BAR_RE = re.compile(r"^\s*\|")


def main() -> None:
    limit = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 120
    seen: set[Path] = set()
    done: set[str] = set()
    grew: Counter[str] = Counter()
    lines_gained = 0
    examples: list[str] = []

    for session in iter_sessions(AFP):
        entry_dir = session.root_path.parent.name
        if entry_dir not in done and len(done) >= limit:
            continue
        done.add(entry_dir)
        for _name, path in session_theories(session):
            if path in seen or not path.is_file():
                continue
            seen.add(path)
            try:
                sec = cli._parse_one(path.stem, path)
            except Exception:  # noqa: BLE001
                continue
            src = sec.source()
            for e in sec.entries:
                if e.tag not in _DEF_TAGS:
                    continue
                end = e.decl_end_line or e.thy_line
                # Walk past blank lines after the recorded end; does a `|`
                # continue the declaration?
                j = end
                while j < len(src) and not src[j].strip():
                    j += 1
                if j >= len(src) or not _BAR_RE.match(src[j]):
                    continue
                grew[e.tag] += 1
                # How far would it run, allowing further blank-then-`|` gaps?
                k = j
                while k < len(src):
                    if src[k].strip():
                        k += 1
                        continue
                    m = k
                    while m < len(src) and not src[m].strip():
                        m += 1
                    if m < len(src) and _BAR_RE.match(src[m]):
                        k = m
                        continue
                    break
                lines_gained += k - end
                if len(examples) < 10:
                    examples.append(f"{sec.theory}:{e.thy_line} {e.tag} "
                                    f"{e.name}  {end} -> {k}")

    print(f"{len(done)} AFP entries, {len(seen):,} theories\n")
    print(f"  declarations ending early at a blank a `|` continues: "
          f"{sum(grew.values()):,}")
    for t, v in grew.most_common():
        print(f"    {t:<8} {v:>6,}")
    print(f"  declaration lines gained: {lines_gained:,}")
    print()
    for s in examples:
        print(f"    {s}")


if __name__ == "__main__":
    main()
