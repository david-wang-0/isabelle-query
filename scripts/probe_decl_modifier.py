#!/usr/bin/env python3
r"""Probe: entries a `private` / `qualified` modifier hid  [decl-modifier].

Issue #19: `private lemma helper` did not match the declaration grammar, so it
was no entry and its lines went to the entry above.  This counts entries and
modified entries over a corpus, so the fix can be measured against the parse
before it — the entry delta should equal the modified count, and nothing else
should move:

    probe_decl_modifier.py [ROOT] [--src DIR]

``--src`` points at another checkout's `src` (e.g. `git archive HEAD src`
unpacked under `.stage/old`) to run the same count on the old parser.
"""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
args = sys.argv[1:]
src = _ROOT / "src"
if "--src" in args:
    k = args.index("--src")
    src = Path(args[k + 1]).resolve()
    del args[k:k + 2]
sys.path.insert(0, str(src))

from isabelle_query import cli  # noqa: E402


def main() -> None:
    root = Path(args[0]).expanduser() if args else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    entries = [e for sec in sections for e in sec.entries]
    mods = Counter((getattr(e, "visibility", ""), e.tag) for e in entries
                   if getattr(e, "visibility", ""))
    print(f"src: {src}")
    print(f"{len(sections):,} theories, {len(entries):,} entries")
    print(f"modified entries: {sum(mods.values()):,}")
    for (mod, tag), n in mods.most_common():
        print(f"  {mod:<10} {tag:<9} {n:>6,}")


if __name__ == "__main__":
    main()
