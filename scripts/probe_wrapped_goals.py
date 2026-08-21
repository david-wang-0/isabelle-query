#!/usr/bin/env python3
r"""Corpus effect of the two step-scanner faults in issue #9.

Both are goal steps `shape` never emits, or emits without the proposition it
carries, so every width measure that reduces over goal steps is measuring a
smaller population than the source has.

  (a) a cartouche fact reference in command position hides the goal keyword
      behind it: `from \<open>P\<close> have "Q"` booked as `plumbing`;
  (b) a proposition wrapped to the next line is discarded, and the goal is
      recorded as bare — indistinguishable from `show ?thesis`.

Run it against the SAME corpus on two trees (a materialised parent and the
working tree) and diff the totals; that is what turns "the fix works on a
fixture" into a rate.  Counts come from `shape`'s own `_scan_steps`, so they
are the scanner's verdicts rather than an independent parse — the same
discipline the issue used.

    python scripts/probe_wrapped_goals.py [N_ENTRIES]

With no N the whole corpus is read.  N takes an alphabetical PREFIX, not a
sample, so a rate from a partial run is not the corpus rate — say which you
quote.
"""

from __future__ import annotations

import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, shape  # noqa: E402
from isabelle_layout import iter_sessions, session_theories  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"


def main() -> int:
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else None
    entries = sorted(p for p in AFP.iterdir() if p.is_dir())
    if limit:
        entries = entries[:limit]
        print(f"# alphabetical PREFIX of {limit} entries, not a sample",
              file=sys.stderr)

    goals = bare = with_stmt = 0
    proofs = theories = 0
    for n, entry in enumerate(entries, 1):
        if n % 100 == 0:
            print(f"  ...{n}/{len(entries)} entries", file=sys.stderr)
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
            # `session_theories` yields (theory_name, path) pairs.
            for name, path in thys:
                if path in seen or not path.exists():
                    continue
                seen.add(path)
                try:
                    sec = cli._parse_one(name, path)
                except Exception:
                    continue
                theories += 1
                for e in sec.entries:
                    try:
                        steps = shape._scan_steps(sec, e)
                    except Exception:
                        continue
                    g = [s for s in steps if s.kind == "goal"]
                    if not g:
                        continue
                    proofs += 1
                    goals += len(g)
                    for s in g:
                        if s.stmt_text.strip():
                            with_stmt += 1
                        else:
                            bare += 1

    print(f"theories      {theories:>10,}")
    print(f"proofs        {proofs:>10,}")
    print(f"goal steps    {goals:>10,}")
    print(f"  with stmt   {with_stmt:>10,}"
          f"   ({with_stmt / goals:.2%})" if goals else "")
    print(f"  bare        {bare:>10,}"
          f"   ({bare / goals:.2%})" if goals else "")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
