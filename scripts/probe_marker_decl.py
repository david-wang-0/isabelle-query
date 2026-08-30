#!/usr/bin/env python3
r"""Record-level entry dump for a root, for before/after diffing a parser change.

Why not `dump_entries.py`: that one calls `cli._parse_one` per theory, which
sees each header alone.  `_CUSTOM_COMMANDS` is a union built by a ROOT-WIDE
pre-scan inside `load_index`, so a per-theory probe measures a different parser
than the CLI runs — the mistake that once reported a change as "+0 records"
when it was +27.  This goes through `load_index`, so the measurement and the
tool agree by construction.

The record is `theory:line:tag:name` plus every computed extent, because a
change to name extraction moves spans as well as membership, and a span change
is invisible to a membership count.  Headings are dumped too (`SECTION`
records): `[marker-decl]` is one defect with two recognition sites.

    python scripts/probe_marker_decl.py <root> > .after.txt
    PYTHONPATH=<worktree>/src python <worktree>/scripts/probe_marker_decl.py \
        <root> > .before.txt
    diff .before.txt .after.txt

Roots worth running: the AFP (`~/repos/afp/thys`) and `HOL/Analysis` from the
Isabelle2025-2 distribution, which tags its declarations for the document build
throughout and is where the defect concentrates.
"""

import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    cli._ROOT_OVERRIDE = Path(sys.argv[1]).expanduser().resolve()
    for sec in cli.load_index():
        for e in sec.entries:
            print(f"{sec.theory}:{e.thy_line}:{e.tag}:{e.name}"
                  f":src={e.src_start}-{e.thy_end}"
                  f":decl_end={e.decl_end_line}:proof={e.proof_line}"
                  f":body_end={e.body_end_line}")
        for level, title, line in parsing.extract_sections(sec.source()):
            print(f"{sec.theory}:{line}:SECTION:{level}:{title}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
