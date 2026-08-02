#!/usr/bin/env python3
"""Corpus probe: what does the non-Isar region scan actually change, and does
it ever run away?

Parses AFP theories twice — once with `extract_nonisar_ranges` live, once with
it stubbed out (0.5.0 behaviour) — and reports:

  * how much source the scan marks as non-Isar (the expected effect), and
  * every theory where it marks an implausible share of the file (the failure
    mode that matters: an unbalanced cartouche or `(*` leaves the state machine
    stuck and it redacts the rest of the theory, silently deleting live source).
  * entry-count and span deltas, so a span change is visible rather than
    inferred.

Usage:  probe_nonisar_impact.py [N_ENTRIES] [--verbose]
"""
import re
import sys
import time
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 60
VERBOSE = "--verbose" in sys.argv
RUNAWAY = 0.40  # share of a theory's lines marked non-Isar that looks wrong
_DECL_RE = re.compile(r"^(lemma|theorem|corollary|definition|fun|primrec"
                      r"|inductive|datatype|abbreviation|instance"
                      r"|instantiation)\b")

_real = parsing.extract_nonisar_ranges
_real_spans = parsing.extract_nonisar_spans


def parse(path, thy, with_fix):
    """Parse with the non-Isar scan on, or with BOTH of its outputs stubbed out.

    Both, because they now feed different consumers: the ranges drive the
    line-granular masks and the span-boundary set, the columns drive
    `live_source` (and so the citation scan).  Stubbing only one would compare
    against a state the tool has never been in.
    """
    parsing.extract_nonisar_ranges = _real if with_fix else (
        lambda lines, spans=None: [])
    parsing.extract_nonisar_spans = _real_spans if with_fix else (
        lambda lines: {})
    return cli._parse_one(thy, path)


def spans(sec):
    return {e.name: (e.src_start, e.thy_end) for e in sec.entries
            if e.name != "?"}


entries = sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]
tot_lines = tot_marked = n_thy = 0
span_changed = entry_count_changed = 0
runaways: list[tuple[float, str, int]] = []
swallowed: list[tuple[str, int, str]] = []
ml_fun = 0
t_fix = t_base = 0.0

for ent in entries:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            lines = thy_path.read_text(errors="replace").splitlines()
        except OSError:
            continue
        thy = thy_path.stem
        n_thy += 1

        t0 = time.perf_counter()
        sec_fix = parse(thy_path, thy, True)
        t_fix += time.perf_counter() - t0
        t0 = time.perf_counter()
        sec_base = parse(thy_path, thy, False)
        t_base += time.perf_counter() - t0

        marked = sum(hi - lo + 1 for lo, hi in sec_fix.nonisar_ranges)
        tot_lines += len(lines)
        tot_marked += marked
        share = marked / len(lines) if lines else 0.0
        if share > RUNAWAY:
            runaways.append((share, f"{ent.name}/{thy}.thy", len(lines)))
        # Sharper signature than share-of-lines: a non-Isar range must never
        # begin on a line that OPENS A DECLARATION.  If it does, the state
        # machine was still inside a region when live source resumed, and it is
        # swallowing proofs — the failure that matters.
        #
        # With one exclusion, or the signal is all noise: `fun` is a keyword in
        # BOTH Isabelle and ML, so an ML body whose first redacted line is
        # `fun sep_select_tacs s ctxt =` matches while being exactly right.
        # The discriminator is the line above the range — an ML body is opened
        # by its command — so an ML `fun` is counted separately, not reported.
        for lo, _hi in sec_fix.nonisar_ranges:
            if not _DECL_RE.match(lines[lo - 1]):
                continue
            k = lo - 2  # nearest preceding non-blank line (0-indexed)
            while k >= 0 and not lines[k].strip():
                k -= 1
            if k >= 0 and parsing._leads_with_ml(lines[k]):
                ml_fun += 1
            else:
                swallowed.append((f"{ent.name}/{thy}.thy", lo,
                                  lines[lo - 1].strip()[:70]))

        if len(sec_fix.entries) != len(sec_base.entries):
            entry_count_changed += 1
        a, b = spans(sec_fix), spans(sec_base)
        if a != b:
            span_changed += 1
            if VERBOSE:
                for k in sorted(set(a) & set(b)):
                    if a[k] != b[k]:
                        print(f"  span {ent.name}/{thy}.thy {k}: "
                              f"{b[k]} -> {a[k]}")

parsing.extract_nonisar_ranges = _real

print(f"entries={len(entries)}  theories={n_thy}  lines={tot_lines:,}")
print(f"lines marked non-Isar: {tot_marked:,} "
      f"({100 * tot_marked / max(tot_lines, 1):.2f}%)")
print(f"theories with changed spans:  {span_changed}")
print(f"theories with changed entry counts: {entry_count_changed} "
      "(expected 0 — the scan does not gate entry extraction)")
print(f"parse time: with fix {t_fix:.2f}s   without {t_base:.2f}s   "
      f"({100 * (t_fix - t_base) / max(t_base, 1e-9):+.1f}%)")
print(f"\nranges starting on an ML `fun` (normal — an ML body): {ml_fun}")
print(f"RANGES STARTING ON A DECLARATION (swallowed live source): {len(swallowed)}")
for name, lo, text in swallowed[:15]:
    print(f"  {name}:{lo}  {text}")
print(f"\ntheories marking >{RUNAWAY:.0%} of their lines: {len(runaways)}")
for share, name, n in sorted(runaways, reverse=True)[:15]:
    print(f"  {share:6.1%}  {name}  ({n} lines)")
