#!/usr/bin/env python3
r"""Corpus probe: which target openers does `_target_opener` fail to NAME?

`probe_locale_naming.py` asked whether a `begin` block can be attributed to the
command that opened it, and answered yes — 4,003/4,003, nothing unattributed.
That is a question about *kinds*, and it is settled.  This asks the next one,
which that probe cannot see: given the right kind, is the NAME read?

The failure is silent by construction.  `_target_opener` returns `(kind, '')`
when `_TARGET_NAME_RE = [A-Za-z_][A-Za-z_0-9'.]*` cannot match, and
`_block_stacks` then drops the block with `if b[1] and ...` — so a locale whose
name is spelled `\<Z>` or `"functor"` is neither unattributed nor named.  It
vanishes.  Every declaration inside it reports no target, and `enclosing` names
no scope, with nothing in any existing count to show for it.

So this reports two populations:

  NAMELESS OPENERS   openers of a naming kind that yield ''.  Split into the
                     legitimately anonymous (`context begin`, `context fixes x`
                     — a name `_target_opener` is RIGHT to decline) and the
                     genuine misses, classified by how the name is spelled.

  ORPHANED LINES     lines whose innermost enclosing block is a nameless one of
                     a naming kind.  This is the blast radius: the population
                     that loses an answer it should have.  Reported as lines
                     rather than entries so it does not depend on which
                     declarations the parser currently finds inside them.
                     Split the same way — an anonymous `context` also leaves
                     its lines with no target, but correctly, and folding the
                     two together inflates the number roughly threefold.

`--show N` prints N examples of each miss class, since the point of the exercise
is to fix the grammar and the spellings are the specification.

Usage:  probe_target_names.py [N_ENTRIES] [--show N]
"""
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.parsing import (  # noqa: E402
    _ANON_OPENERS, _BLOCK_TOKEN_RE, _NOT_A_TARGET_NAME, _TARGET_KINDS,
    _TARGET_OPEN_RE, _target_opener,
)

AFP = Path.home() / "repos" / "afp" / "thys"

_args = [a for a in sys.argv[1:] if not a.startswith("--")]
LIMIT = int(_args[0]) if _args and _args[0].isdigit() else 120
SHOW = 0
if "--show" in sys.argv:
    _i = sys.argv.index("--show")
    SHOW = int(sys.argv[_i + 1]) if _i + 1 < len(sys.argv) else 5

# How the name that `_TARGET_NAME_RE` could not read is actually spelled.
_SYMBOL_RE = re.compile(r"\\<\^?\w+>")
_QUOTED_RE = re.compile(r'"')


def classify(kind: str, rest: str) -> str:
    """Why did this opener yield no name?"""
    head = rest.lstrip()
    if not head:
        # `context` alone on a line opens an ANONYMOUS context whose elements
        # follow (`context\n  fixes h ...\nbegin`).  `context\n  foo\nbegin`
        # would reopen locale `foo` and be a name worth chasing across the line
        # break — but over 120 AFP entries all 288 bare `context` openers are
        # followed by an element or `begin`, never by a name.  So no lookahead.
        return f"anonymous: {kind} alone (elements follow)"
    first = head.split()[0].rstrip("=")
    if first in _NOT_A_TARGET_NAME:
        # `context begin`, `context fixes x` — anonymous by Isar, not a miss.
        return f"anonymous: {kind} {first}"
    if _SYMBOL_RE.match(head):
        return "symbol-spelled (\\<...>)"
    if _QUOTED_RE.match(head):
        return "quoted"
    return "other"


def main() -> None:
    misses: Counter[str] = Counter()
    anon: Counter[str] = Counter()
    examples: dict[str, list[str]] = defaultdict(list)
    orphan_lines = 0   # nameless because the grammar failed — recoverable
    anon_lines = 0     # nameless because Isar gave no name — correct
    named_lines = 0
    n_thy = 0

    for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
        for thy_path in sorted(ent.rglob("*.thy")):
            try:
                sec = cli._parse_one(thy_path.stem, thy_path)
            except Exception:  # noqa: BLE001
                continue
            n_thy += 1
            outer = sec.outer_source()
            live = sec.live_source()

            # Same walk as `_block_stacks`, but keeping the nameless blocks
            # instead of filtering them out — that filter is the blind spot.
            # `(kind, name, recoverable)` — the third field is what the
            # existing `_block_stacks` cannot represent: nameless because the
            # grammar failed, as against nameless because Isar said so.
            stack: list[tuple[str, str, bool]] = []
            pending: tuple[str, str, bool] | None = None

            def note(seg: str, lseg: "str | None" = None) -> None:
                nonlocal pending
                op = _target_opener(seg, lseg)
                if op is None:
                    return
                kind, name = op
                if name or kind in _ANON_OPENERS or kind not in _TARGET_KINDS:
                    pending = (kind, name, False)
                    return
                # Classify from LIVE where available: outer blanks a quoted
                # name, so classifying on outer reports "other" for the very
                # spelling the live view can read.
                base = lseg if lseg is not None else seg
                m = _TARGET_OPEN_RE.match(seg.lstrip())
                off = len(seg) - len(seg.lstrip())
                why = classify(kind, base[off + m.end(1):] if m else "")
                recoverable = not why.startswith("anonymous")
                pending = (kind, name, recoverable)
                (misses if recoverable else anon)[f"{kind}: {why}"] += 1
                if SHOW and len(examples[f"{kind}: {why}"]) < SHOW:
                    examples[f"{kind}: {why}"].append(
                        f"{ent.name}/{thy_path.stem}: {base.strip()[:72]}")

            for li, line in enumerate(outer):
                lv = live[li] if li < len(live) else line
                inner = stack[-1] if stack else None
                if inner and inner[0] in _TARGET_KINDS:
                    if inner[1]:
                        named_lines += 1
                    elif inner[2]:
                        orphan_lines += 1
                    else:
                        anon_lines += 1
                if "begin" in line or "end" in line:
                    pos = 0
                    for m in _BLOCK_TOKEN_RE.finditer(line):
                        note(line[pos:m.start()], lv[pos:m.start()])
                        pos = m.end()
                        if m.group(1) == "begin":
                            stack.append(pending or ("?", "", False))
                            pending = None
                        elif stack:
                            stack.pop()
                    note(line[pos:], lv[pos:])
                    continue
                note(line, lv)

    print(f"theories={n_thy:,}   entries over {LIMIT} AFP entries\n")

    total_miss = sum(misses.values())
    print(f"NAMELESS OPENERS of a naming kind {sorted(_TARGET_KINDS)}")
    print(f"  genuine misses: {total_miss}")
    for k, n in misses.most_common():
        print(f"    {n:5,}  {k}")
        for ex in examples.get(k, []):
            print(f"             {ex}")
    print(f"  correctly anonymous: {sum(anon.values()):,}")
    for k, n in anon.most_common(8):
        print(f"    {n:5,}  {k}")

    inner_total = named_lines + orphan_lines + anon_lines
    pct = 100.0 * orphan_lines / inner_total if inner_total else 0.0
    print("\nBLAST RADIUS — lines by what their innermost target block reports")
    print(f"  {named_lines:>7,}  named        — a target, correctly")
    print(f"  {anon_lines:>7,}  anonymous    — no target, correctly "
          f"(nothing to name)")
    print(f"  {orphan_lines:>7,}  RECOVERABLE  — no target, wrongly "
          f"({pct:.2f}% of {inner_total:,})")
    print("  the last group reports no `target`, and `enclosing` names no "
          "scope for it")


if __name__ == "__main__":
    main()
