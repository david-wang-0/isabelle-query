#!/usr/bin/env python3
r"""Corpus probe: can a `begin`/`end` stack be NAMED, and what would that buy?

`probe_block_structure.py` established that `begin`/`end` is a sound block model
(100.00% balanced, max depth 5).  It counted depth only.  The open question is
the next one: can each block be attributed to the *command that opened it*, so
`enclosing` can say "inside locale `ast_domain`" rather than just "depth 2"?

THE ATTRIBUTION RULE under test
    A `begin` does not name itself, and the command that opens the block may sit
    several lines above it (`locale foo =` / `fixes ...` / `assumes ...` /
    `begin`).  So: remember the most recent *target-opening* command seen at
    command position; the next `begin` consumes it.  Overwriting on each opener
    is what makes a merely-*declared* locale (`locale A = fixes x`, never opened)
    harmless — the next real opener replaces it before any `begin` arrives.

    That rule can fail exactly one way: a `begin` arriving with no unconsumed
    opener, which this counts as UNATTRIBUTED.  A high count would sink the
    approach; the point of the probe is to find out.

WHAT IT WOULD BUY
    Also reports how many entries actually sit inside a named block beyond the
    theory's own `begin` — the population `enclosing` would gain an answer for —
    and how many declarations instead carry an explicit `(in foo)` target, which
    `_strip_decl_prefix` currently discards.

Reads `TheorySection.outer_source()` (command position), like its predecessor.

Usage:  probe_locale_naming.py [N_ENTRIES]
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

# `@` joins the left boundary class: auto2 spells its proof closer `@end`.
_BLOCK_RE = re.compile(r"(?<![A-Za-z_0-9'@])(begin|end)(?![A-Za-z_0-9'])")

# Commands that open a target block terminated by `end`.  `interpretation` and
# `sublocale` are NOT here: they take a proof, not a block.
_OPENERS = ("theory", "locale", "class", "context", "instantiation",
            "overloading", "bundle", "open_bundle", "experiment", "notepad")
_OPENER_RE = re.compile(r"^(" + "|".join(_OPENERS) + r")(?![A-Za-z_0-9'])\s*(.*)$")

# A *custom* opener declares itself in a theory header with Isabelle's own
# "this command opens a block" kind:  `keywords "foo" :: thy_decl_block`.
# The declaring theory need not be the using one (AutoCorres2 declares
# `if_architecture_context` in Target_Architecture and uses it in importers), so
# these are unioned per entry — the probe's stand-in for the session-wide
# `_CUSTOM_COMMANDS` table `_populate_custom_commands` already maintains.
_QUOTED_RE = re.compile(r'"([^"]+)"')


def _custom_openers(entry_dir: Path) -> set[str]:
    out: set[str] = set()
    for thy_path in entry_dir.rglob("*.thy"):
        try:
            head: list[str] = []
            with open(thy_path, encoding="utf-8", errors="replace") as f:
                for n, line in enumerate(f):
                    head.append(line.rstrip("\n"))
                    if n >= 400 or re.match(r"^\s*begin\b", line):
                        break
        except OSError:
            continue
        text = " ".join(head)
        if "thy_decl_block" not in text:
            continue
        for group in text.split(" and "):     # the keyword-decl separator
            if "thy_decl_block" in group:
                out.update(_QUOTED_RE.findall(group.split("::")[0]))
    return out

# The name is the first identifier after the keyword.  `overloading`,
# `experiment` and `notepad` never carry one; `context` may or may not
# (`context fixes x begin` is anonymous).
_IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z_0-9'.]*")
_ANON = {"overloading", "experiment", "notepad"}
# `context begin` / `context fixes ...` — the word after `context` is not a name
# if it opens the block or starts an element.
_NOT_A_NAME = {"begin", "fixes", "assumes", "notes", "defines", "includes",
               "constrains", "obtains"}

_IN_TARGET_RE = re.compile(r"\(\s*in\s+([A-Za-z_][A-Za-z_0-9'.]*)\s*\)")


def _opener_at(outer_line: str,
               custom: set[str]) -> tuple[str, str | None] | None:
    """`(kind, name)` if this line opens a target block, else None."""
    stripped = outer_line.lstrip()
    m = _OPENER_RE.match(stripped)
    if not m:
        mc = _IDENT_RE.match(stripped)
        if mc and mc.group(0) in custom:
            return mc.group(0), None      # custom blocks carry no locale name
        return None
    kind, rest = m.group(1), m.group(2)
    if kind in _ANON:
        return kind, None
    mi = _IDENT_RE.match(rest)
    if not mi or mi.group(0) in _NOT_A_NAME:
        return kind, None
    return kind, mi.group(0)


def main() -> None:
    n_thy = n_begin = n_unattributed = 0
    kinds: Counter = Counter()
    named_depth: Counter = Counter()     # innermost named block per entry
    n_entries = n_in_named = n_in_target = 0
    unattributed_samples: list[str] = []

    for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
        custom = _custom_openers(ent)
        for thy_path in sorted(ent.rglob("*.thy")):
            try:
                sec = cli._parse_one(thy_path.stem, thy_path)
                outer = sec.outer_source()
            except Exception:  # noqa: BLE001
                continue
            n_thy += 1

            # line -> stack of (kind, name) in force when that line is read
            stack: list[tuple[str, str | None]] = []
            per_line: list[list[tuple[str, str | None]]] = []
            pending: tuple[str, str | None] | None = None

            for line in outer:
                per_line.append(list(stack))
                # Openers and `begin`/`end` are read in POSITIONAL order, not
                # line-at-a-time: `context srules begin context begin` puts two
                # of each on one line, and a line-granular scan would attribute
                # the second block to the first opener.
                pos = 0
                for m in _BLOCK_RE.finditer(line):
                    op = _opener_at(line[pos:m.start()], custom)
                    if op is not None:
                        pending = op
                    pos = m.end()
                    if m.group(1) == "begin":
                        n_begin += 1
                        if pending is None:
                            n_unattributed += 1
                            if len(unattributed_samples) < 10:
                                unattributed_samples.append(
                                    f"  {thy_path.stem}: {line.strip()[:60]}")
                            stack.append(("?", None))
                        else:
                            kinds[pending[0]] += 1
                            stack.append(pending)
                            pending = None
                    elif stack:
                        stack.pop()
                op = _opener_at(line[pos:], custom)
                if op is not None:
                    pending = op

            for e in sec.entries:
                n_entries += 1
                idx = e.thy_line - 1
                if not (0 <= idx < len(per_line)):
                    continue
                # skip the theory's own block: anything deeper is a real target
                inner = [b for b in per_line[idx][1:] if b[1]]
                if inner:
                    n_in_named += 1
                    named_depth[inner[-1][0]] += 1
                raw = outer[idx] if idx < len(outer) else ""
                if _IN_TARGET_RE.search(raw):
                    n_in_target += 1

    print(f"theories={n_thy:,}   entries={n_entries:,}")
    print(f"\n`begin` blocks: {n_begin:,}")
    print(f"  UNATTRIBUTED (no opener found): {n_unattributed:,} "
          f"({100.0 * n_unattributed / max(n_begin, 1):.2f}%)")
    print("\n  attributed by opening command:")
    for k, c in kinds.most_common():
        print(f"    {k:<16} {c:>7,}")

    print(f"\nentries inside a NAMED block (beyond the theory): {n_in_named:,} "
          f"({100.0 * n_in_named / max(n_entries, 1):.1f}%)")
    for k, c in named_depth.most_common():
        print(f"    innermost is a {k:<14} {c:>7,}")
    print(f"\nentries carrying an explicit `(in foo)` target: {n_in_target:,} "
          f"({100.0 * n_in_target / max(n_entries, 1):.1f}%)")
    if unattributed_samples:
        print("\nunattributed samples:")
        for s in unattributed_samples:
            print(s)


if __name__ == "__main__":
    main()
