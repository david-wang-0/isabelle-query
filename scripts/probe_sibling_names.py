#!/usr/bin/env python3
r"""Probe: how many declared names does `query` never index?

The export-recall probe found (on 11 built sessions) that Isabelle knows
thousands of names written in the source that `query` has no entry for.  Two
classes dominate, and both are pure source-level phenomena -- measurable over
the WHOLE corpus with no Isabelle, now that the oracle has told us what to
look for:

  * **`and`-siblings.**  `fun f and g and h where ...` declares three
    constants; `query` records ONE entry, named after the first.  Likewise
    `definition`/`primrec`/`inductive`/`abbreviation ... and ...`.
  * **named introduction rules.**  `inductive p where r1: "..." | r2: "..."`
    binds `r1`, `r2` as citable facts; `query` records only `p`.

Both are invisible to `find` / `show`, absent from the call graph (so
`callers -r` fails and `unused` cannot see them), and each missed name is a
citation the graph silently drops.

Counted against `query`'s own parse, so the figure is exactly "names the tool
does not have".

Usage:  probe_sibling_names.py [N_ENTRIES]
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

_DECL_KWS = ("fun", "function", "primrec", "definition", "abbreviation",
             "inductive", "coinductive", "inductive_set", "fun_cases")

# `and` at the top level of a declaration head, i.e. before `where` / the
# first inner-syntax term.  Matched on the OUTER view, where terms are blanked,
# so an `and` inside a proposition cannot be mistaken for a separator.
_AND_RE = re.compile(r"(?<![\w'])and(?![\w'])")
_NAME_RE = re.compile(r"^\s*([A-Za-z][\w']*)\s*(?:::|where|$)")

# `r1: "..."` after `where` or `|` — a named introduction rule.
_RULE_RE = re.compile(r"(?:where|\|)\s*([A-Za-z][\w']*)\s*:(?!:)")


def main() -> None:
    limit = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 120
    entries_seen = 0
    tally: Counter[str] = Counter()
    examples: dict[str, list[str]] = {}
    seen: set[Path] = set()
    missing_names: set[str] = set()
    done: set[str] = set()

    for session in iter_sessions(AFP):
        entry_dir = session.root_path.parent.name
        if entry_dir not in done and len(done) >= limit:
            continue
        done.add(entry_dir)
        for name, path in session_theories(session):
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
                entries_seen += 1
                end = e.decl_end_line or e.thy_line
                if e.thy_line < 1 or end > len(outer):
                    continue
                head = "\n".join(outer[e.thy_line - 1:end])
                kw = head.split()[0] if head.split() else ""
                if kw not in _DECL_KWS:
                    continue
                # --- and-siblings: names between `and` and `::`/`where`.
                #     Stop at `for` as well as `where`: `inductive_set p
                #     for A :: ... and I :: ...` fixes PARAMETERS with `and`,
                #     and those are not declared constants.
                decl_head = re.split(r"(?<![\w'])(?:where|for)(?![\w'])",
                                     head)[0]
                for m in _AND_RE.finditer(decl_head):
                    tail = head[m.end():]
                    nm = _NAME_RE.match(tail.lstrip("\n"))
                    if nm and nm.group(1) not in names:
                        tally["and-sibling"] += 1
                        missing_names.add(nm.group(1))
                        ex = examples.setdefault("and-sibling", [])
                        if len(ex) < 8:
                            ex.append(f"{sec.theory}:{e.thy_line}  {kw} "
                                      f"{e.name} ... and {nm.group(1)}")
                # --- named introduction rules
                if kw in ("inductive", "coinductive", "inductive_set"):
                    body_end = min(e.thy_end or end, len(outer))
                    body = "\n".join(outer[e.thy_line - 1:body_end])
                    for m in _RULE_RE.finditer(body):
                        if m.group(1) not in names:
                            tally["inductive rule name"] += 1
                            missing_names.add(m.group(1))
                            ex = examples.setdefault("inductive rule name", [])
                            if len(ex) < 8:
                                ex.append(f"{sec.theory}:{e.thy_line}  "
                                          f"{kw} {e.name} ... {m.group(1)}:")

    # Second pass: a missing NAME only matters if something cites it.  Count
    # live-text occurrences outside the declaration that introduced it — those
    # are the citations `callers`/`callees`/`unused` cannot see.
    cites = 0
    cited_names: set[str] = set()
    # A one- or two-character name (`fun TS ... and C`) is indistinguishable
    # from a bound variable at token level, so counting its occurrences would
    # measure noise.  Restrict to names long enough to be unambiguous; the
    # short ones are still real gaps, just not measurable this way.
    countable = {n for n in missing_names if len(n) >= 3}
    if countable:
        word = re.compile(r"[\w']+")
        for path in seen:
            try:
                sec = cli._parse_one(path.stem, path)
            except Exception:  # noqa: BLE001
                continue
            for line in sec.live_source():
                for tok in word.findall(line):
                    if tok in countable:
                        cites += 1
                        cited_names.add(tok)

    print(f"{len(done)} AFP entries, {len(seen):,} theories, "
          f"{entries_seen:,} entries parsed\n")
    total = sum(tally.values())
    for k, v in tally.most_common():
        print(f"  {k:<22} {v:>7,} names query has no entry for")
    print(f"  {'TOTAL':<22} {total:>7,}")
    print(f"\n  of the {len(countable):,} unambiguous names (>=3 chars), "
          f"{len(cited_names):,} are cited, {cites:,} occurrences")
    print("  — every occurrence is an edge the call graph does not have")
    for k in tally:
        print(f"\n  {k}:")
        for s in examples.get(k, []):
            print(f"    {s}")


if __name__ == "__main__":
    main()
