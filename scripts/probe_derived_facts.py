#!/usr/bin/env python3
r"""Probe: what the export's DISCARDED half is worth.

The #8 oracle design checks PRECISION on named entries and writes the ~12k
"only in export" entities off as expected noise.  Those are Isabelle's
**derived** facts -- `foo.simps`, `foo.induct`, `K0.cong`, `foo_def` -- and
they are the names proofs actually cite.  `query`'s call graph has to map a
citation of `foo.simps` back to `foo`; it does that with a `[\w']+` tokeniser
plus a two-suffix table (`_def`, `_defs`) built in `graph.build_call_graph`.

A derived fact's export position points at the command that GENERATED it, so
the export is a ground-truth `derived name -> generating entry` map.  This
measures what that map says about the tokeniser:

  * how many derived names exist, and of what shape;
  * how many are actually cited in the session's own proof text;
  * for each citation, whether query's decomposition reaches the generating
    entry (a MISS silently under-counts `callers` and over-reports `unused`)
    and whether it also reaches something else (a SPURIOUS edge).

Usage:  probe_derived_facts.py [SESSION] [--examples N]
"""
from __future__ import annotations

import re
import sys
from bisect import bisect_right
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from probe_export_oracle import (  # noqa: E402
    _entities, _find_db, _resolve_source, _symbol_maps, _theories,
)

# The graph's own tokeniser and derived-suffix table, mirrored so the answer
# describes what `query` really does rather than what this probe imagines.
_WORD_RE = re.compile(r"[\w']+")
_QUALIFIED_RE = re.compile(r"[\w'](?:[\w'.]*[\w'])?")
_DERIVED_SUFFIXES = ("_def", "_defs")


def _load(session: str):
    """`(sections, export_lines)` for every theory of a built session."""
    from isabelle_query import cli

    db = _find_db(session)
    if db is None:
        sys.exit(f"no export database for {session!r} — it has never been built")
    print(f"database: {db}  (read-only)")
    sections, export_lines = [], {}
    for t in _theories(db, session):
        ents, src = _entities(db, t)
        if not src:
            continue
        path = _resolve_source(src, {})
        if path is None:
            continue
        try:
            sec = cli._parse_one(path.stem, path)
        except Exception:  # noqa: BLE001
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        line_starts, _ = _symbol_maps(text)
        sections.append(sec)
        export_lines[sec.theory] = {
            n: bisect_right(line_starts, o) for n, o in ents.items()}
    return sections, export_lines


def _shape(name: str) -> str:
    if "." in name:
        return "dotted (foo.simps)"
    if name.endswith(_DERIVED_SUFFIXES):
        return "underscore (foo_def)"
    return "other"


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    n_ex = 8
    for a in sys.argv[1:]:
        if a.startswith("--examples"):
            n_ex = int(a.partition("=")[2] or 8)
    session = args[0] if args else "Universal_Turing_Machine"

    from isabelle_query.graph import _build_line_index, _entry_at_line
    from isabelle_query.model import _CITABLE_TAGS

    sections, export_lines = _load(session)
    print(f"theories: {len(sections)}")

    # The name set the call graph would build: citable, named entries.
    name_set = {e.name for sec in sections for e in sec.entries
                if e.tag in _CITABLE_TAGS and e.name and e.name != "?"}
    derived_base = {n + s: n for n in name_set for s in _DERIVED_SUFFIXES
                    if n + s not in name_set}
    line_index = _build_line_index(sections)

    # A derived name is an export entity with no query entry of that name.
    # Its position points at the generating command, so the entry owning that
    # line IS the fact it belongs to.
    derived: dict[str, tuple[str, str | None, str]] = {}   # name -> (thy, gen, tag)
    shapes: Counter[str] = Counter()
    for sec in sections:
        ours = {e.name for e in sec.entries if e.name and e.name != "?"}
        ours |= {f"{e.target}.{e.name}" for e in sec.entries
                 if e.target and e.name and e.name != "?"}
        for n, line in export_lines.get(sec.theory, {}).items():
            if n in ours:
                continue
            gen = _entry_at_line(line_index.get(sec.theory, []), line)
            derived[n] = (sec.theory,
                          gen.name if gen and gen.name != "?" else None,
                          gen.tag if gen else "-")
            shapes[_shape(n)] += 1

    print(f"\nderived entities (only in export): {len(derived):,}")
    for k, v in shapes.most_common():
        print(f"  {k:<22} {v:>7,}")
    gen_tags = Counter(t for _, _, t in derived.values())
    print("  generating command:  "
          + ", ".join(f"{t}={c:,}" for t, c in gen_tags.most_common(6)))

    # --- what happens when one is cited ------------------------------------
    cited: Counter[str] = Counter()
    miss: Counter[str] = Counter()
    spurious: Counter[str] = Counter()
    ok = missed = extra_hits = uncitable = 0
    miss_ex: list[str] = []
    spur_ex: list[str] = []
    for sec in sections:
        for line_no, line in enumerate(sec.live_source(), 1):
            for tok in _QUALIFIED_RE.findall(line):
                info = derived.get(tok)
                if info is None:
                    continue
                _, gen, tag = info
                cited[tok] += 1
                if gen is None or tag not in _CITABLE_TAGS:
                    uncitable += 1          # e.g. nat.induct from a datatype
                    continue
                words = _WORD_RE.findall(tok)
                cand = set(words) & name_set
                cand |= {derived_base[w] for w in words if w in derived_base}
                if gen in cand:
                    ok += 1
                else:
                    missed += 1
                    miss[tok] += 1
                    if len(miss_ex) < n_ex:
                        miss_ex.append(f"{sec.theory}:{line_no}  cites {tok!r}"
                                       f" -> should reach {gen!r}, reaches "
                                       f"{sorted(cand) or 'nothing'}")
                for x in cand - {gen}:
                    spurious[x] += 1
                    extra_hits += 1
                    if len(spur_ex) < n_ex:
                        spur_ex.append(f"{sec.theory}:{line_no}  {tok!r} also "
                                       f"mints an edge to {x!r}")

    total = ok + missed + uncitable
    print(f"\nderived names cited in this session's own text: "
          f"{len(cited):,} distinct, {total:,} occurrences")
    print(f"  generating entry is citable   {ok + missed:>7,}")
    print(f"    query reaches it            {ok:>7,}")
    print(f"    query MISSES it             {missed:>7,}")
    print(f"  generated by a non-fact cmd   {uncitable:>7,}   "
          f"(datatype/locale — no edge is correct)")
    print(f"  extra edges from the same token {extra_hits:>6,}")

    if miss_ex:
        print("\n  missed (a real citation the call graph cannot see):")
        for s in miss_ex:
            print(f"    {s}")
        print("  most-missed derived names: "
              + ", ".join(f"{n}({c})" for n, c in miss.most_common(6)))
    if spur_ex:
        print("\n  spurious (an edge to something the citation never named):")
        for s in spur_ex:
            print(f"    {s}")
        print("  most-invented targets: "
              + ", ".join(f"{n}({c})" for n, c in spurious.most_common(6)))


if __name__ == "__main__":
    main()
