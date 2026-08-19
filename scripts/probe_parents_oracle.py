#!/usr/bin/env python3
r"""Oracle: `query`'s `imports` clause against Isabelle's `theory/parents`.

The cheapest ground truth in the session database.  `theory/parents` is a
plain newline-separated list of fully-qualified parent theories -- no YXML, no
offsets, no symbol arithmetic -- and it is exactly what `deps` reports and
what `isabelle_layout.session_theories` builds the discovery closure from.

Compared on the LAST COMPONENT of each name, in order: `query` reads the
import as written (`Turing_Hoare`, `"HOL-Library.FuncSet"`) while Isabelle
qualifies it (`Universal_Turing_Machine.Turing_Hoare`, `HOL-Library.FuncSet`).

Usage:  probe_parents_oracle.py [SESSION...]      (default: every built session)
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from probe_export_oracle import (  # noqa: E402
    _ENTITY_RE, _attrs, _available_sessions, _find_db, _read_export,
    _resolve_source, _theories,
)


def _tail(name: str) -> str:
    """The bare theory name, from either spelling.

    Isabelle qualifies by session (`HOL-Library.FuncSet`); a `.thy` may import
    by relative path (`"../FSM"`, legal and used in FSM_Tests).  Strip the
    directory first -- splitting a path on `.` turns `../FSM` into `/FSM`.
    """
    return name.strip().rsplit("/", 1)[-1].rsplit(".", 1)[-1]


def _source_of(db: Path, theory: str) -> Path | None:
    """The `.thy` a theory's exports point at."""
    for kind in ("theory/thms", "theory/consts", "theory/other/fact",
                 "theory/types", "theory/locales"):
        body = _read_export(db, theory, kind)
        if not body:
            continue
        for m in _ENTITY_RE.finditer(body):
            src = _attrs(m.group(1)).get("file")
            if src:
                return _resolve_source(src, {})
    return None


def main() -> None:
    from isabelle_layout import parse_thy_imports

    sessions = sys.argv[1:] or [s for s, _ in _available_sessions()]
    agree = differ = noresolve = 0
    diffs: list[str] = []
    done: list[str] = []
    for session in sessions:
        db = _find_db(session)
        if db is None:
            continue
        done.append(session)
        for t in _theories(db, session):
            body = _read_export(db, t, "theory/parents")
            if body is None:
                continue
            path = _source_of(db, t)
            if path is None:
                noresolve += 1
                continue
            want = [_tail(x) for x in body.split("\n") if x.strip()]
            got = [_tail(x) for x in parse_thy_imports(path)]
            # `theory/parents` is the TRANSITIVELY REDUCED parent list of the
            # theory value: `imports Turing_Hoare Abacus_Mopup ...` exports as
            # just `Abacus_Mopup ...` when Turing_Hoare is already one of its
            # ancestors.  `query` reports the syntactic clause.  So the oracle
            # relation is containment, not equality -- Isabelle may name fewer,
            # never more.  A parent query does NOT list is a real miss.
            if set(want) <= set(got):
                agree += 1
            else:
                differ += 1
                if len(diffs) < 20:
                    diffs.append(f"{t}\n      Isabelle: {want}\n"
                                 f"      query   : {got}\n"
                                 f"      missing : "
                                 f"{sorted(set(want) - set(got))}")
    total = agree + differ
    print(f"=== theory/parents oracle, {len(done)} session(s) ===")
    print(f"theories compared      {total:>7,}")
    print(f"  agree                {agree:>7,}  "
          f"({100.0 * agree / max(total, 1):.2f}%)")
    print(f"  DIFFER               {differ:>7,}")
    print(f"  source not resolved  {noresolve:>7,}   (not compared)")
    for d in diffs:
        print(f"\n    {d}")


if __name__ == "__main__":
    main()
