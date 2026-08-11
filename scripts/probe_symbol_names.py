#!/usr/bin/env python3
r"""Probe: how many declared names carry Isabelle markup, and can `find` reach them?

`find` takes a REGEX, and an Isabelle name is not regex-safe.  `split\<^sub>i`
compiles without error and matches nothing: `\<` is a literal `<`, and the `^`
that follows is a start-of-string ANCHOR, which cannot hold mid-pattern.  So the
user who copies a name out of query's own output gets a confident "no entries
matching", which is the worst of the three possible answers.

That is the same failure `cmd_find` already preprocesses away for grep-style
`\|` ("in Python's re, '\|' is the literal '|' character, which would silently
match nothing") — same cause, same silence, same fix shape.

This measures the size of the population before choosing between fixes:

  * how many entry names contain `\<...>` markup at all;
  * how many of those a naive `re.compile(name)` would fail to find, which is
    the "typed what I saw" case;
  * which markup tokens actually occur, since a fix that escapes them needs to
    know whether `\<^sub>` is representative or just the one people quote.

Usage:  probe_symbol_names.py [N_ENTRIES]
"""
from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

_MARKUP = re.compile(r"\\<\^?\w+>")


def main() -> None:
    total = with_markup = unreachable = 0
    tokens: Counter[str] = Counter()
    tags: Counter[str] = Counter()
    examples: list[str] = []

    for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
        for thy in sorted(ent.rglob("*.thy")):
            try:
                sec = cli._parse_one(thy.stem, thy)
            except Exception:  # noqa: BLE001
                continue
            for e in sec.entries:
                for name in [e.name, *e.bound_names]:
                    total += 1
                    found = _MARKUP.findall(name)
                    if not found:
                        continue
                    with_markup += 1
                    tokens.update(found)
                    tags[e.tag] += 1
                    # "I typed the name as query printed it."
                    try:
                        if not re.search(name, name):
                            unreachable += 1
                            if len(examples) < 6:
                                examples.append(f"{ent.name}/{thy.stem}: {name}")
                    except re.error:
                        unreachable += 1
                        if len(examples) < 6:
                            examples.append(
                                f"{ent.name}/{thy.stem}: {name}  (re.error)")

    pct = 100.0 * with_markup / total if total else 0.0
    print(f"{total:,} names over {LIMIT} AFP entries")
    print(f"  carrying \\<...> markup: {with_markup:,} ({pct:.2f}%)")
    bad = 100.0 * unreachable / with_markup if with_markup else 0.0
    print(f"  of those, NOT found by searching for the name as printed: "
          f"{unreachable:,} ({bad:.1f}%)")
    for ex in examples:
        print(f"      {ex}")

    print("\n  markup tokens used, most common first:")
    for tok, n in tokens.most_common(12):
        anchor = "  <- contains a regex anchor" if "^" in tok else ""
        print(f"    {n:6,}  {tok}{anchor}")
    print(f"    ({len(tokens)} distinct tokens; "
          f"{sum(n for t, n in tokens.items() if '^' in t):,} uses contain `^`)")

    print("\n  by entry kind:")
    for t, n in tags.most_common(8):
        print(f"    {n:6,}  {t}")


if __name__ == "__main__":
    main()
