#!/usr/bin/env python3
"""Dump every entry as `theory:line:tag:name`, for diffing two parser states.

Deliberately dumb and stable, so `diff` between two runs shows exactly which
declarations a parser change added or lost.  Used with the checkout-restore
dance (git stash is permission-gated here):

    python3 scripts/dump_entries.py 60 > .after.txt
    git checkout src/isabelle_query/parsing.py
    python3 scripts/dump_entries.py 60 > .before.txt
    git checkout HEAD -- . ; diff .before.txt .after.txt

`--spans` widens each record with the computed extents, for changes that move
where entries END rather than which entries exist — a span change is invisible
to the default record, and spans drive `enclosing`, `largest`, `outline` and
the call graph's def-site ranges.

Usage:  dump_entries.py [N_ENTRIES] [--spans]
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 60
SPANS = "--spans" in sys.argv

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception:  # noqa: BLE001
            continue
        for e in sec.entries:
            rec = f"{ent.name}/{thy_path.stem}:{e.thy_line}:{e.tag}:{e.name}"
            if SPANS:
                rec += (f":src={e.src_start}-{e.thy_end}"
                        f":decl_end={e.decl_end_line}:proof={e.proof_line}"
                        f":body_end={e.body_end_line}")
            print(rec)
