#!/usr/bin/env python3
r"""Probe: the three name classes `[declared-names]` has NOT indexed.

Rule labels and `and`-siblings are done.  What is left are three classes that
each need a design call first, because `query` has no `Entry` for the thing
that would own them:

  * **locale / class names.**  `locale hpk = fixes ... begin` declares `hpk`,
    which `find hpk` cannot see.  A locale IS known to the parser — every
    entry inside one carries it in `Entry.blocks` — but it is not an entry.
  * **locale assumption names.**  `locale L = assumes a: "P"` binds `L.a`,
    cited constantly inside the locale.  With no entry for `L`, there is
    nothing for a binding to hang on.
  * **datatype constructors and discriminators.**  `datatype t = A | B`
    declares `A`/`B`; `datatype t = disc: A` also declares `disc`.

The constructor figure is a LOWER BOUND, and knowing why matters more than the
number: the `typedecl` route sets `decl_end_line = decl_line` unconditionally
and never reads the declaration body, so every multi-line `datatype` / `record`
is one line wide (all 153 over 40 AFP entries) and only the constructors that
fit on the first line are visible here.  See todo's `[typedecl-extent]`.

Counts each class, and — the figure that decides whether it matters — how
often the names are CITED in live text, using `query`'s own
`graph._is_citation_name` rather than an invented cutoff.

Usage:  probe_locale_names.py [N_ENTRIES]
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
from isabelle_query.graph import _is_citation_name  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"

# `locale NAME =` / `class NAME =` / `locale NAME begin`, at command position.
_LOCALE_RE = re.compile(r"^\s*(locale|class|experiment|bundle)\s+"
                        r"([A-Za-z][\w']*)")
# `assumes a: "P"` / `and b: "Q"` inside a locale head.
_ASSUME_RE = re.compile(r"(?:(?<![\w'])assumes(?![\w'])|(?<![\w'])and(?![\w']))"
                        r"\s+([A-Za-z][\w']*)\s*(?:\[[^\]]*\])?\s*:(?!:)")
# `datatype ... = A ... | B ...`: a constructor is the head token of each
# alternative; a `disc:` prefix names its discriminator.
_CTOR_RE = re.compile(r"(?:=|\|)\s*(?:([A-Za-z][\w']*)\s*:\s*)?"
                      r"([A-Za-z][\w']*)")


def main() -> None:
    limit = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 120
    seen: set[Path] = set()
    done: set[str] = set()
    tally: Counter[str] = Counter()
    names: dict[str, set[str]] = {}
    examples: dict[str, list[str]] = {}

    for session in iter_sessions(AFP):
        entry_dir = session.root_path.parent.name
        if entry_dir not in done and len(done) >= limit:
            continue
        done.add(entry_dir)
        for _n, path in session_theories(session):
            if path in seen or not path.is_file():
                continue
            seen.add(path)
            try:
                sec = cli._parse_one(path.stem, path)
            except Exception:  # noqa: BLE001
                continue
            outer = sec.outer_source()
            known = {e.name for e in sec.entries if e.name and e.name != "?"}
            known |= {n for e in sec.entries for n in e.bound_names}

            def note(kind: str, name: str, where: str) -> None:
                if name in known:
                    return
                tally[kind] += 1
                names.setdefault(kind, set()).add(name)
                ex = examples.setdefault(kind, [])
                if len(ex) < 6:
                    ex.append(where)

            # --- locale / class names, and their assumption names
            for ln, text in enumerate(outer, 1):
                m = _LOCALE_RE.match(text)
                if not m:
                    continue
                kw, lname = m.group(1), m.group(2)
                note("locale/class name", lname,
                     f"{sec.theory}:{ln}  {kw} {lname}")
                # The head runs to `begin`; assumption labels live in it.
                head, j = [], ln - 1
                while j < len(outer) and not re.match(r"^\s*begin\b",
                                                      outer[j]):
                    head.append(outer[j])
                    j += 1
                    if j - ln > 60:
                        break
                for a in _ASSUME_RE.finditer("\n".join(head)):
                    note("locale assumption", a.group(1),
                         f"{sec.theory}:{ln}  {lname}.{a.group(1)}")

            # --- datatype constructors / discriminators
            for e in sec.entries:
                if e.tag != "DATATYPE":
                    continue
                end = e.decl_end_line or e.thy_line
                if e.thy_line < 1 or end > len(outer):
                    continue
                body = "\n".join(outer[e.thy_line - 1:end])
                body = body.split("=", 1)[-1] if "=" in body else ""
                for c in _CTOR_RE.finditer("=" + body):
                    if c.group(1):
                        note("datatype discriminator", c.group(1),
                             f"{sec.theory}:{e.thy_line}  {e.name} "
                             f"{c.group(1)}: {c.group(2)}")
                    note("datatype constructor", c.group(2),
                         f"{sec.theory}:{e.thy_line}  {e.name} = "
                         f"{c.group(2)}")

    # Citations, counted with query's own predicate at each flag setting.
    levels = (0, 1, 2)
    print(f"{len(done)} AFP entries, {len(seen):,} theories\n")
    print(f"  {'class':<26} {'sites':>8} {'distinct':>9} {'cited':>8} "
          f"{'occurrences':>12}")
    word = re.compile(r"[\w']+")
    for kind, count in tally.most_common():
        pool = {n for n in names[kind] if _is_citation_name(n, 1)}
        hits: Counter[str] = Counter()
        for path in seen:
            try:
                sec = cli._parse_one(path.stem, path)
            except Exception:  # noqa: BLE001
                continue
            for line in sec.live_source():
                for tok in word.findall(line):
                    if tok in pool:
                        hits[tok] += 1
        print(f"  {kind:<26} {count:>8,} {len(names[kind]):>9,} "
              f"{len(hits):>8,} {sum(hits.values()):>12,}")
    for kind in tally:
        print(f"\n  {kind}:")
        for s in examples.get(kind, []):
            print(f"    {s}")
    print(f"\n  (citations at --drop-names-upto 1, query's default; "
          f"levels {levels} available via graph._is_citation_name)")


if __name__ == "__main__":
    main()
