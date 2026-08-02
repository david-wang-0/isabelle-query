#!/usr/bin/env python3
r"""Corpus probe: how much does `query` miss inside `locale` / `context`?

`DECL_RE` is anchored at column 0, deliberately — that anchor is what stops the
declaration grammar minting entries out of ML bodies, inner syntax and prose,
and removing it was measured to be a bad trade (see the phantom-entry work in
`probe_ml_phantom_entries.py`).

The cost of the anchor is that a declaration INDENTED inside a locale or
context block is not an entry at all.  `AI_Planning_Languages_Semantics`'s
`PDDL_STRIPS_Checker.thy` is 860 lines and yields 31 entries, because at line
137 it opens `context ast_domain begin` and indents everything after it.

This sizes that: how many declarations are invisible, in how many theories, and
how they sit relative to a `locale`/`context` block — so the fix can be aimed at
the structure rather than at the anchor.

Counts are candidates, not certainties: a keyword is only evidence.  The
`sus` column flags candidates that are NOT inside a tracked context block, which
is where a false positive (an ML `fun`, a keyword inside inner syntax) would
show up — read those samples before trusting the headline.

Usage:  probe_indented_decls.py [N_ENTRIES]
"""
import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

# The same keywords as DECL_RE, but requiring leading whitespace.
_INDENTED_DECL_RE = re.compile(
    r"^[ \t]+(definition|abbreviation|function|fun|primrec|inductive_set"
    r"|inductive|lemma|corollary|theorem|axiomatization|datatype"
    r"|type_synonym|record)(?=\s|$)")
# Column-0 block openers/closers, for a crude nesting depth.
_OPENS_RE = re.compile(r"^(locale|context|sublocale|instantiation|bundle)\b")
_BEGIN_RE = re.compile(r"^\s*begin\s*$|\bbegin\s*$")
_END_RE = re.compile(r"^end\b")

by_kw: Counter = Counter()
per_theory: list[tuple[int, str, int, int]] = []
samples: list[str] = []
suspicious: list[str] = []
n_thy = n_entries = n_missed = n_sus = 0
thys_affected = 0

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
            live = sec.live_source()
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        n_entries += len(sec.entries)
        entry_lines = {e.thy_line for e in sec.entries}
        noise = set()
        for lo, hi in sec.nonisar_ranges:
            noise.update(range(lo, hi + 1))

        depth = 0
        missed_here = 0
        for i, line in enumerate(live, 1):
            if _OPENS_RE.match(line):
                depth += 1
            elif _END_RE.match(line) and depth > 0:
                depth -= 1
            m = _INDENTED_DECL_RE.match(line)
            if not m or i in entry_lines or i in noise:
                continue
            n_missed += 1
            missed_here += 1
            by_kw[m.group(1)] += 1
            if depth == 0:
                n_sus += 1
                if len(suspicious) < 12:
                    suspicious.append(f"  {thy_path.stem}:{i}\n"
                                      f"      {line.strip()[:96]}")
            elif len(samples) < 8:
                samples.append(f"  {thy_path.stem}:{i}  (context depth {depth})"
                               f"\n      {line.strip()[:96]}")
        if missed_here:
            thys_affected += 1
            per_theory.append((missed_here, thy_path.stem, len(sec.entries),
                               len(live)))

print(f"theories={n_thy:,}   entries found={n_entries:,}")
print(f"indented declarations NOT recognised as entries: {n_missed:,}")
print(f"  → would be {100.0 * n_missed / max(n_entries + n_missed, 1):.1f}% "
      f"of all declarations")
print(f"theories affected: {thys_affected:,} "
      f"({100.0 * thys_affected / max(n_thy, 1):.1f}%)")
print(f"outside any tracked block (possible false positives): {n_sus:,} "
      f"({100.0 * n_sus / max(n_missed, 1):.1f}%)")

print("\nby keyword:")
for kw, c in by_kw.most_common():
    print(f"  {kw:<16} {c:>6}")

print("\nworst theories (missed / found / lines):")
for missed, thy, found, nlines in sorted(per_theory, reverse=True)[:12]:
    print(f"  {thy:<44} {missed:>5} / {found:>4} / {nlines:>5}")

print("\nsamples inside a context block:")
for s in samples:
    print(s)
print("\nsamples OUTSIDE any block — read these for false positives:")
for s in suspicious:
    print(s)
