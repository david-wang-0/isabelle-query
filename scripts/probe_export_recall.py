#!/usr/bin/env python3
r"""Probe: is RECALL checkable, i.e. can the oracle catch a MISSING entry?

#8 decided to check precision only -- "every `query` entry is an export
entity, and the converse must not be required" -- because ~12k entities per
session have no source declaration of their own.  The stated reason was that
they "cannot be filtered by position either, since a derived fact's position
points at the command that generated it".

That is the right observation and the wrong conclusion.  Pointing at the
generating command is exactly what makes a derived entity *recognisable*:

    inv_begin1        offset..end_offset spans the text `inv_begin1`
                      -> the name is WRITTEN there; a real declaration
    map_pre_abc_inst  offset..end_offset spans the text `datatype`
                      -> not written there; minted by that command

So the discriminator is one string compare that `--verify` already performs:
does `source[offset:end_offset] == xname`?  This measures how cleanly that
separates the two populations, and what is left over -- the entities Isabelle
says are declared, at a position where the name really is written, that
`query` does not report.

Usage:  probe_export_recall.py [SESSION...]      (default: every built session)
"""
from __future__ import annotations

import sys
from bisect import bisect_right
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from probe_export_oracle import (  # noqa: E402
    _ENTITY_RE, _attrs, _available_sessions, _find_db, _read_export,
    _resolve_source, _symbol_maps, _theories,
)

_KINDS = ("theory/consts", "theory/types", "theory/classes", "theory/locales",
          "theory/thms", "theory/other/fact")


def _by_kind(db: Path, theory: str
             ) -> tuple[dict[str, dict[str, tuple[int, int]]], str]:
    """`({kind: {xname: (offset, end_offset)}}, source_file)`."""
    out: dict[str, dict[str, tuple[int, int]]] = {}
    src = ""
    for kind in _KINDS:
        body = _read_export(db, theory, kind)
        if not body:
            continue
        d = out.setdefault(kind, {})
        for m in _ENTITY_RE.finditer(body):
            a = _attrs(m.group(1))
            xname = a.get("xname")
            try:
                span = (int(a["offset"]), int(a["end_offset"]))
            except (KeyError, ValueError):
                continue
            if not xname:
                continue
            src = src or a.get("file", "")
            d.setdefault(xname, span)
    return out, src


def _run(session: str, examples: dict[str, list[str]]) -> Counter[str] | None:
    from isabelle_query import cli

    db = _find_db(session)
    if db is None:
        return None
    tot: Counter[str] = Counter()
    for t in _theories(db, session):
        kinds, src = _by_kind(db, t)
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
        line_starts, sym_to_char = _symbol_maps(text)
        n_sym = len(sym_to_char) - 1

        ours: set[str] = set()
        for e in sec.entries:
            if not e.name or e.name == "?":
                continue
            ours.add(e.name)
            if e.target:
                ours.add(f"{e.target}.{e.name}")
        ours |= {c for e in sec.entries for c in e.bound_names}

        # `theory/thms` and `theory/other/fact` overlap heavily (a fact is
        # filed under both when it is a singleton thm list), so a per-kind
        # total double-counts.  Dedupe by name for the headline figure.
        seen_missing: set[str] = set()
        for kind, ents in kinds.items():
            for n, (off, end) in ents.items():
                tot[f"{kind}|export"] += 1
                if n in ours:
                    tot[f"{kind}|matched"] += 1
                    continue
                if not (0 < off <= end <= n_sym):
                    tot[f"{kind}|nopos"] += 1
                    continue
                # The discriminator: is the name written at its own position?
                written = text[sym_to_char[off]:sym_to_char[end]]
                # Isabelle's xname drops the target qualifier the source may
                # carry, and vice versa; compare the last component.
                if written == n or written == n.rsplit(".", 1)[-1]:
                    tot[f"{kind}|MISSING"] += 1
                    if n not in seen_missing:
                        seen_missing.add(n)
                        tot["DEDUPED"] += 1
                    ex = examples.setdefault(kind, [])
                    if len(ex) < 12:
                        ex.append(f"{sec.theory}:"
                                  f"{bisect_right(line_starts, off)}  {n}")
                else:
                    tot[f"{kind}|derived"] += 1
    return tot


def main() -> None:
    sessions = sys.argv[1:] or [s for s, _ in _available_sessions()]
    grand: Counter[str] = Counter()
    examples: dict[str, list[str]] = {}
    done: list[str] = []
    for s in sessions:
        r = _run(s, examples)
        if r is None:
            continue
        done.append(s)
        grand.update(r)
    print(f"=== recall via the position discriminator, "
          f"{len(done)} session(s) ===")
    print(f"    {', '.join(done)}\n")
    print(f"{'kind':<20} {'export':>9} {'matched':>9} {'derived':>9} "
          f"{'MISSING':>9} {'nopos':>7}")
    tm = td = tx = 0
    for kind in _KINDS:
        e = grand[f"{kind}|export"]
        if not e:
            continue
        m, d, x = (grand[f"{kind}|matched"], grand[f"{kind}|derived"],
                   grand[f"{kind}|MISSING"])
        tm, td, tx = tm + m, td + d, tx + x
        print(f"{kind:<20} {e:>9,} {m:>9,} {d:>9,} {x:>9,} "
              f"{grand[f'{kind}|nopos']:>7,}")
    print(f"{'TOTAL':<20} {sum(grand[f'{k}|export'] for k in _KINDS):>9,} "
          f"{tm:>9,} {td:>9,} {tx:>9,}")
    print(f"\nname written at its own position but absent from query: "
          f"{grand['DEDUPED']:,} distinct ({tx:,} before deduping the "
          f"thms/other-fact overlap)\n  <- a recall check would flag these; "
          f"the precision-only oracle cannot see any of them")
    for kind in _KINDS:
        if examples.get(kind):
            print(f"\n  {kind}:")
            for s in examples[kind]:
                print(f"    {s}")


if __name__ == "__main__":
    main()
