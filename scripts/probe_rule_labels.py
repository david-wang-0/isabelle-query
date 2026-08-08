#!/usr/bin/env python3
r"""Probe: which declaration commands carry NAMED rules, and how many?

`inductive p where r1: "..." | r2: "..."` binds `r1`/`r2` as citable facts and
`query` records only `p` (`[declared-names]`).  The label grammar — an
identifier, optional `[attributes]`, then a single `:` — is not special to
`inductive`: `definition c where c_def: "c = ..."` and a labelled `fun`
equation use the same shape.  So before scoping the fix to `inductive`, count
what the corpus actually writes, per keyword.

Scanned on `query`'s OUTER view (inner syntax blanked), so a `|` or an `x:`
inside a term cannot be mistaken for a rule separator or a label.

Reports, per declaration keyword: labels found, how many are ALREADY indexed
somewhere in the theory (a label matching a real entry name is not a missing
name — it usually means the label restates a `_def` the tool already has), and
how many are genuinely new.

Usage:  probe_rule_labels.py [N_ENTRIES]
"""
from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.common import iter_sessions, session_theories  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"

# A named rule/equation: after `where` or a top-level `|`, an identifier with
# optional attributes, then a single `:` (never the `::` of a type ascription).
_LABEL_RE = re.compile(
    r"(?:(?<![\w'])where(?![\w'])|\|)\s*"
    r"([A-Za-z][\w']*)\s*(?:\[[^\]]*\])?\s*:(?!:)")


def main() -> None:
    limit = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 120
    per_kw: Counter[str] = Counter()
    known_kw: Counter[str] = Counter()
    examples: dict[str, list[str]] = {}
    seen: set[Path] = set()
    done: set[str] = set()

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
            outer = sec.outer_source()
            names = {e.name for e in sec.entries if e.name and e.name != "?"}
            for e in sec.entries:
                end = e.decl_end_line or e.thy_line
                if e.thy_line < 1 or end > len(outer):
                    continue
                body = "\n".join(outer[e.thy_line - 1:end])
                kw = body.split()[0] if body.split() else ""
                if not kw:
                    continue
                for m in _LABEL_RE.finditer(body):
                    label = m.group(1)
                    if label in names:
                        known_kw[kw] += 1
                        continue
                    per_kw[kw] += 1
                    ex = examples.setdefault(kw, [])
                    if len(ex) < 4:
                        ex.append(f"{sec.theory}:{e.thy_line}  {kw} {e.name}"
                                  f" ... {label}:")

    print(f"{len(done)} AFP entries, {len(seen):,} theories\n")
    print(f"  {'keyword':<18} {'new labels':>11} {'already indexed':>16}")
    for kw, v in per_kw.most_common():
        print(f"  {kw:<18} {v:>11,} {known_kw.get(kw, 0):>16,}")
    for kw in known_kw:
        if kw not in per_kw:
            print(f"  {kw:<18} {0:>11,} {known_kw[kw]:>16,}")
    print(f"  {'TOTAL':<18} {sum(per_kw.values()):>11,} "
          f"{sum(known_kw.values()):>16,}")
    for kw, _ in per_kw.most_common(8):
        print(f"\n  {kw}:")
        for s in examples.get(kw, []):
            print(f"    {s}")


if __name__ == "__main__":
    main()
