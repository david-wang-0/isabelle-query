#!/usr/bin/env python3
r"""Corpus probe: is `begin` / `end` a sound block model for Isar?

The question behind it: can declaration recognition stop using INDENTATION as
evidence?  Isar is whitespace-insensitive, so a column-0 anchor is a proxy for
something real (being at command position) and a bad one — `Error_Monad_Add`
indents its whole body and vanishes from the index.

The replacement would be block structure.  Isar makes that unusually cheap:
every *target* block — `theory`, `locale`, `class`, `context`, `instantiation`,
`overloading`, `bundle`, `experiment`, `notepad` — is opened by the token
`begin` and closed by `end`, whatever command introduced it.  So there is no
opener→closer table to maintain: ONE pair, counted at outer-syntax position.
(`proof`/`qed` and `{`/`}` nest separately and are not counted here.)

This checks that claim where it matters — against 962 entries of real Isabelle
— by asking whether the count balances, and how often it goes negative (which
would mean the model is wrong, not merely incomplete).

APPROXIMATE: inner syntax is blanked with a local scan over `"..."` and
cartouches rather than by the parser's tokenizer, which does not yet expose
term spans.  Unbalanced files are dumped so the failures can be read — a
failure here is either a bad approximation or a bad model, and the samples say
which.

Usage:  probe_block_structure.py [N_ENTRIES]
"""
import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

# `@` joins the left boundary class: auto2 spells its proof-block closer
# `@end`, which is its own token and not Isar's `end`.
_BLOCK_RE = re.compile(r"(?<![A-Za-z_0-9'@])(begin|end)(?![A-Za-z_0-9'])")
_TOK_RE = re.compile(r'\\\\|\\"|"|\\<open>|‹|\\<close>|›')


def outer_only(live: list[str]) -> list[str]:
    """`live` with `"..."` strings and cartouches blanked, across lines."""
    out, state, depth = [], "text", 0
    for line in live:
        buf, keep = list(line), (state == "text")
        prev = 0
        for m in _TOK_RE.finditer(line):
            tok, pos = m.group(), m.start()
            if tok in ("\\\\", '\\"'):
                continue
            if state == "text":
                if tok == '"':
                    state = "string"
                elif tok in ("\\<open>", "‹"):
                    state, depth = "cart", 1
                else:
                    continue
                prev = pos
            elif state == "string":
                if tok == '"':
                    buf[prev:m.end()] = " " * (m.end() - prev)
                    state = "text"
            else:
                if tok in ("\\<open>", "‹"):
                    depth += 1
                elif tok in ("\\<close>", "›"):
                    depth -= 1
                    if depth == 0:
                        buf[prev:m.end()] = " " * (m.end() - prev)
                        state = "text"
        if state != "text":                    # runs on past this line
            buf[prev:] = " " * (len(line) - prev)
        out.append("".join(buf) if keep or state == "text" else "")
    return out


def main() -> None:
    depths: Counter = Counter()
    unbalanced: list[str] = []
    negative: list[str] = []
    n_thy = n_bal = 0

    for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
        for thy_path in sorted(ent.rglob("*.thy")):
            try:
                sec = cli._parse_one(thy_path.stem, thy_path)
                live = outer_only(sec.live_source())
            except Exception:  # noqa: BLE001
                continue
            n_thy += 1
            depth, lo, maxd = 0, 0, 0
            for line in live:
                for m in _BLOCK_RE.finditer(line):
                    depth += 1 if m.group(1) == "begin" else -1
                    lo, maxd = min(lo, depth), max(maxd, depth)
            depths[maxd] += 1
            if depth == 0:
                n_bal += 1
            elif len(unbalanced) < 10:
                unbalanced.append(f"  {thy_path.stem:<44} closes at depth {depth}")
            if lo < 0 and len(negative) < 10:
                negative.append(f"  {thy_path.stem:<44} went to {lo}")

    print(f"theories={n_thy:,}")
    print(f"  begin/end balanced at EOF: {n_bal:,} "
          f"({100.0 * n_bal / max(n_thy, 1):.2f}%)")
    print(f"  went NEGATIVE (model is wrong, not just incomplete): "
          f"{len(negative)}")
    print("\nmax nesting depth reached  (1 = the theory's own begin/end only):")
    for d in sorted(depths):
        print(f"  depth {d:<3} {depths[d]:>6} theories")
    if unbalanced:
        print("\nunbalanced samples:")
        for u in unbalanced:
            print(u)
    if negative:
        print("\nnegative samples:")
        for u in negative:
            print(u)


if __name__ == "__main__":
    main()
