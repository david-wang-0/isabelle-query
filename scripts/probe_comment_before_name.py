#!/usr/bin/env python3
r"""A formal comment between a declaration keyword and its name.
[comment-before-name]

    definition                                      -- HOL/UNITY/WFair.thy:35

      \<comment> \<open>This definition specifies conditional fairness.  The rest
          is generic to all forms of fairness. ...\<close>
      transient :: "'a set => 'a program set" where

The name lookahead after a bare `definition` walks forward to the next thing
that looks like a name and lands in the COMMENT's prose, so the entry is
indexed as `is` (from "is generic to all forms") and the real `transient` is
never declared.  Distinct from D5 `[comment-newline]`, where the marker and
its cartouche are on different lines: here they are on one line and the scan
still reads through them.

Found while checking whether `WFair`'s `is` was the entry D13 says is
"reported dead and not dead" — it is not; it is a phantom.

    python scripts/probe_comment_before_name.py [ROOT ...]

Prints, per corpus, every entry whose NAME is not a TOKEN of the live view of
its own declaration (`thy_line .. decl_end_line`).  The live view blanks
formal comments and document blocks, so a name that is nowhere in it was read
out of prose.

Two things this has to get right, both learned by getting them wrong:

* the span is the DECLARATION, not `src_start..thy_end` — `src_start` reaches
  back over an attached preamble, so the wider span sweeps in the `text`
  block above and every entry looks fine;
* membership is by TOKEN, not substring.  `is` occurs inside `\<exists>`, so a
  substring test says the phantom `is` was found on a live line and hides the
  very case this exists to count.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.parsing import ISA_WORD_CHAR  # noqa: E402

_DEFAULTS = ("/Applications/Isabelle2025-2.app/src/HOL",
             "/Applications/Isabelle2025-2.app/src/FOL",
             "/Applications/Isabelle2025-2.app/src/ZF")

_TOKENS = re.compile(rf"{ISA_WORD_CHAR}+").findall
_WINDOW = 20   # lines to look ahead for the name (see `scan`)


def scan(root: Path) -> list[tuple[str, str, int, str]]:
    cli._ROOT_OVERRIDE = root.resolve()
    out: list[tuple[str, str, int, str]] = []
    for sec in cli.load_index():
        live = sec.live_source()
        starts = sorted(e.thy_line for e in sec.entries if e.thy_line)
        for e in sec.entries:
            if e.name in ("?", "") or not e.thy_line:
                continue
            # Only names that ARE a single token can be looked for as one.
            # AOT spells entries `existence:2[1]` and ZF declares `list(A)`;
            # both would be reported by every token test and neither is a
            # phantom.  A name read out of English prose is always one word.
            if _TOKENS(e.name) != [e.name]:
                continue
            # The window, and why it is a window rather than the recorded
            # span: `decl_end_line` is the KEYWORD line for a declaration
            # whose name is on a later line (`Comp/Alloc:23` is 23..23 with
            # `non_dummy` on 26), which is the very shape being counted, so
            # the recorded span would report every one of them.  Forward to
            # the next declaration, capped, is approximate on the permissive
            # side: a name found late still counts as found.
            lo = e.thy_line
            nxt = next((s for s in starts if s > lo), lo + _WINDOW)
            hi = min(len(live), max(e.decl_end_line, min(nxt - 1,
                                                         lo + _WINDOW)))
            toks: set[str] = set()
            for ln in live[lo - 1:hi]:
                toks.update(_TOKENS(ln))
            if e.name in toks:
                continue
            out.append((sec.theory, e.name, e.thy_line, f"{lo}..{hi}"))
    return out


def main() -> int:
    roots = [Path(a).expanduser() for a in sys.argv[1:]] or [
        Path(p) for p in _DEFAULTS]
    total = 0
    for root in roots:
        if not root.is_dir():
            print(f"{root}: absent, skipped")
            continue
        hits = scan(root)
        total += len(hits)
        print(f"\n{root}: {len(hits)} entries named out of non-live text")
        for theory, name, line, text in hits[:20]:
            print(f"    {theory}:{line:<6} {name:24} decl {text}")
        if len(hits) > 20:
            print(f"    ... and {len(hits) - 20} more")
    print(f"\n{total} total")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
