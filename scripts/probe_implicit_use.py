#!/usr/bin/env python3
r"""Probe: the attributes on declaration headers  [implicit-use].

Issue #21: `unused` lists `lemma foo [simp]:` although the simplifier uses it
without a citation.  This tallies the attribute heads `Entry.attrs` reads off
fact headers over a corpus — a parse that picked up junk would show here as
heads no Isabelle attribute is spelled like — and how many entries carry a
declaring one (anything outside `TRANSFORMING_ATTRS`).

Usage:  probe_implicit_use.py [ROOT] [--top N]    (default: the AFP)
"""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.parsing import implicitly_used  # noqa: E402


def main() -> None:
    args = sys.argv[1:]
    top = 40
    if "--top" in args:
        k = args.index("--top")
        top = int(args[k + 1])
        del args[k:k + 2]
    root = Path(args[0]).expanduser() if args else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    heads: Counter[str] = Counter()
    with_attrs = declaring = 0
    for sec in sections:
        for e in sec.entries:
            if e.attrs:
                with_attrs += 1
                heads.update(e.attrs)
            if implicitly_used(e):
                declaring += 1
    n = sum(len(s.entries) for s in sections)
    print(f"{n:,} entries; {with_attrs:,} with header attributes; "
          f"{declaring:,} with a declaring one")
    for h, c in heads.most_common(top):
        print(f"  {c:>7,}  {h}")


if __name__ == "__main__":
    main()
