#!/usr/bin/env python3
r"""Precision check on the command-prefix fix (issue #9a).

Recall says the fix found 27,119 more goal steps corpus-wide.  Recall cannot
say whether any of them is wrong, and a scanner that *invents* goal steps is
worse than one that loses them — every width measure reduces over this
population, so a spurious step is a spurious measurement.

So this compares the OLD prefix rule (stop at the first `"` / `\<open>`) with
the new one line by line, and buckets every line whose classification changed,
with examples.  A reclassification is expected only where a delimiter in
COMMAND position precedes a goal keyword; anything else is a finding.

    python scripts/probe_prefix_reclass.py [N_ENTRIES] [--examples N]

N takes an alphabetical PREFIX, not a sample.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, shape  # noqa: E402
from isabelle_query.graph import _noise_spans  # noqa: E402
from isabelle_layout import iter_sessions, session_theories  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"


def _old_prefix(stripped: str) -> str:
    """The pre-fix rule, verbatim: truncate at the first delimiter."""
    m = shape._PROP_START_RE.search(stripped)
    return stripped[:m.start()] if m else stripped


def _classify_with(prefix: str) -> str:
    """`_classify_step_line`'s body, applied to a prefix computed either way."""
    tokens = shape._CMD_TOKEN_RE.findall(prefix)
    if not tokens:
        return "other"
    if any(t in shape._GOAL_KEYWORDS for t in tokens):
        return "goal"
    head = tokens[0]
    if head in shape._CONTEXT_KEYWORDS:
        return "context"
    if head in shape._PLUMBING_KEYWORDS:
        return "plumbing"
    if head in shape._CLOSING_KEYWORDS or head in (".", ".."):
        return "closing"
    if any(t in shape._CLOSING_KEYWORDS or t == ".." for t in tokens):
        return "closing"
    return "other"


# What the fix is *for*: a delimiter in command position before a goal keyword.
_EXPECTED_RE = re.compile(
    r'^[^"\\]*(?:"|\\<open>).*?\b(?:have|show|hence|thus|obtain|consider'
    r"|also|finally|interpret)\b")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("entries", nargs="?", type=int)
    ap.add_argument("--examples", type=int, default=4)
    args = ap.parse_args()

    dirs = sorted(p for p in AFP.iterdir() if p.is_dir())
    if args.entries:
        dirs = dirs[:args.entries]
        print(f"# alphabetical PREFIX of {args.entries} entries", file=sys.stderr)

    moves: Counter[tuple[str, str]] = Counter()
    samples: dict[tuple[str, str], list[str]] = defaultdict(list)
    unexpected: list[str] = []
    lines_seen = 0

    for n, entry in enumerate(dirs, 1):
        if n % 50 == 0:
            print(f"  ...{n}/{len(dirs)}", file=sys.stderr)
        try:
            sessions = list(iter_sessions(entry))
        except Exception:
            continue
        seen: set[Path] = set()
        for sess in sessions:
            try:
                thys = session_theories(sess)
            except Exception:
                continue
            for name, path in thys:
                if path in seen or not path.exists():
                    continue
                seen.add(path)
                try:
                    sec = cli._parse_one(name, path)
                    src = sec.live_source()
                except Exception:
                    continue
                prose = set()
                for lo, hi in _noise_spans(sec):
                    prose.update(range(lo, hi + 1))
                for i, raw in enumerate(src, 1):
                    if i in prose:
                        continue
                    stripped = raw.strip()
                    if not stripped:
                        continue
                    lines_seen += 1
                    old = _classify_with(_old_prefix(stripped))
                    new = _classify_with(shape._command_prefix(stripped))
                    if old == new:
                        continue
                    moves[(old, new)] += 1
                    if len(samples[(old, new)]) < args.examples:
                        samples[(old, new)].append(f"{name}:{i}  {stripped}")
                    if not _EXPECTED_RE.match(stripped):
                        if len(unexpected) < 40:
                            unexpected.append(f"{name}:{i}  {stripped}")

    print(f"live proof-ish lines examined: {lines_seen:,}")
    print(f"reclassified: {sum(moves.values()):,}\n")
    for (old, new), count in moves.most_common():
        print(f"  {old:>9} -> {new:<9} {count:>8,}")
        for ex in samples[(old, new)]:
            print(f"        {ex[:150]}")
    print(f"\nlines NOT matching the expected shape "
          f"(delimiter in command position before a goal keyword): "
          f"{len(unexpected)}{' (capped)' if len(unexpected) >= 40 else ''}")
    for ex in unexpected[:20]:
        print(f"    {ex[:150]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
