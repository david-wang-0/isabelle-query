#!/usr/bin/env python3
r"""Corpus probe: what a batch (single-process) census would cost (issue #6).

A whole-AFP census is currently driven by spawning `query` once per entry, so it
pays interpreter + process startup ~962 times.  Doing it in one process is the
obvious saving, but the issue asks for two things to be measured BEFORE the
design is fixed, and both can bite:

1. **Memory.**  Holding every AFP section at once is a different program from
   holding one entry at a time.  This probe measures both shapes —
   ``--mode all`` (load the whole sample, then analyse) and ``--mode session``
   (load one session, analyse, release) — so the design can be chosen on numbers
   rather than on the assumption that streaming is obviously fine.

2. **Leakage between sessions.**  Per-session iteration only bounds memory if a
   session's data is actually released.  `parsing` keeps module-global state
   (``_CUSTOM_COMMANDS``) and `TheorySection` memoises lazily (``_source_cache``,
   ``_live_cache``, ``_outer_cache``, ``_prose_line_set``), so this reports the
   residual after each session is dropped and garbage-collected.  A residual that
   climbs is the design constraint.

It also reports the correctness reason batch mode cannot simply be
"load the whole tree in one go": ``_CUSTOM_COMMANDS`` is a session-wide union
(mirroring Isabelle's ``Keywords.++``), and unioning it across every AFP entry
lets one entry's custom command change how another entry parses.  ``--check
keywords`` counts how many entries declare any, and how many distinct names
collide across entries with DIFFERENT kinds — a collision is a parse that
changes depending on who else is loaded.

Usage:
    probe_census_batch.py [N_ENTRIES] [--mode all|session|both] [--check keywords]
"""
import argparse
import gc
import resource
import sys
import time
import tracemalloc
from collections import Counter, defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import parsing, shape  # noqa: E402
from isabelle_layout import iter_sessions, session_theories  # noqa: E402
from isabelle_query.model import TheorySection  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"


def _mb(n: float) -> str:
    return f"{n / (1024 * 1024):8.1f} MB"


def _peak_rss() -> int:
    """Peak RSS of this process.  macOS reports bytes, Linux kibibytes."""
    v = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return v if sys.platform == "darwin" else v * 1024


def load_session(session, seen: "set[Path] | None" = None
                 ) -> list[TheorySection]:
    """Parse one session exactly as `load_index` would, but scoped to it —
    including resetting the custom-command union, which is what keeps a batch
    run's parse identical to the per-entry runs it replaces.

    `seen` is the dedup set and must be **shared across sessions**, not fresh per
    session: 47 AFP theory files are claimed by two sessions (AutoCorres2 and
    CParser), and a per-session set would parse and emit each of them twice.
    Sharing it reproduces `_sections_from_dir`'s "first session to reference a
    theory owns it"."""
    parsing._CUSTOM_COMMANDS.clear()
    pairs = list(session_theories(session))
    parsing._populate_custom_commands(pairs)
    sections: list[TheorySection] = []
    for name, thy_path in pairs:
        parsing._add_one_section(name, thy_path,
                                 seen if seen is not None else set(),
                                 sections, session=session.name)
    return sections


def _analyse(sections) -> int:
    """The census workload: one summary record per measurable proof."""
    n = 0
    for pm in shape.analyze_sections(sections):
        shape.summary_record(shape.summarize(pm))
        n += 1
    return n


def mode_session(sessions) -> None:
    """Load / analyse / release one session at a time."""
    tracemalloc.start()
    gc.collect()
    base = tracemalloc.get_traced_memory()[0]
    print(f"{'session':<40} {'thys':>5} {'recs':>7} {'time':>8} "
          f"{'peak':>11} {'residual':>11}")
    worst_peak = 0
    t0 = time.perf_counter()
    total = 0
    seen: set[Path] = set()   # shared across sessions — see load_session
    for s in sessions:
        gc.collect()
        tracemalloc.reset_peak()
        t = time.perf_counter()
        try:
            sections = load_session(s, seen)
            recs = _analyse(sections)
        except Exception as exc:  # noqa: BLE001
            print(f"{s.name:<40} !! {type(exc).__name__}: {exc}"[:110])
            continue
        dt = time.perf_counter() - t
        n_thy = len(sections)
        cur, peak = tracemalloc.get_traced_memory()
        del sections
        gc.collect()
        residual = tracemalloc.get_traced_memory()[0] - base
        worst_peak = max(worst_peak, peak)
        total += recs
        print(f"{s.name:<40} {n_thy:>5} {recs:>7} {dt:>7.2f}s "
              f"{_mb(peak)} {_mb(residual)}")
    print(f"\nsessions={len(sessions)}  records={total:,}  "
          f"wall={time.perf_counter() - t0:.1f}s")
    print(f"worst single-session traced peak: {_mb(worst_peak)}")
    print(f"process peak RSS:                 {_mb(_peak_rss())}")
    tracemalloc.stop()


def load_all(sessions, seen: "set[Path] | None" = None
             ) -> list[TheorySection]:
    """Load every session's theories into ONE list — the alternative shape:
    list all sessions up front, parse the lot, then analyse.

    One pass over `session_theories` per session, feeding both the pair list and
    the owner map, so this is not handicapped against `load_session` by walking
    each session twice.  Sharing `seen` gives the same first-claimant dedup.
    """
    if seen is None:
        seen = set()
    parsing._CUSTOM_COMMANDS.clear()
    pairs: list[tuple[str, Path]] = []
    owner: dict[Path, str] = {}
    for s in sessions:
        for n, p in session_theories(s):
            pairs.append((n, p))
            try:
                owner.setdefault(p.resolve(), s.name)
            except OSError:
                pass
    parsing._populate_custom_commands(pairs)
    sections: list[TheorySection] = []
    for name, thy_path in pairs:
        try:
            who = owner.get(thy_path.resolve())
        except OSError:
            who = None
        parsing._add_one_section(name, thy_path, seen, sections, session=who)
    return sections


def mode_all(sessions) -> None:
    """Load every session first, then analyse — the naive single process."""
    tracemalloc.start()
    gc.collect()
    t = time.perf_counter()
    sections = load_all(sessions)
    load_done = time.perf_counter() - t
    after_load = tracemalloc.get_traced_memory()[0]
    recs = _analyse(sections)
    cur, peak = tracemalloc.get_traced_memory()
    print(f"sections={len(sections):,}  records={recs:,}  "
          f"load={load_done:.1f}s  wall={time.perf_counter() - t:.1f}s")
    print(f"traced after load: {_mb(after_load)}")
    print(f"traced peak:       {_mb(peak)}")
    print(f"process peak RSS:  {_mb(_peak_rss())}")
    print(f"custom commands unioned across the whole sample: "
          f"{len(parsing._CUSTOM_COMMANDS):,}")
    tracemalloc.stop()


def mode_compare(sessions, rounds: int = 2) -> None:
    """Interleaved A/B: does iterating sessions cost anything against loading
    them all at once?

    Both run in ONE process, alternating, so neither pays interpreter startup
    and neither gets a cold filesystem cache to itself.  Best-of-`rounds` per
    mode, because the machine drifts several percent between identical runs.
    Record counts are compared: a mode that is faster because it measured fewer
    proofs has not won anything.
    """
    def run_session():
        gc.collect()
        tracemalloc.reset_peak()
        seen: set[Path] = set()
        t = time.perf_counter()
        parse = 0.0
        recs = 0
        for s in sessions:
            tl = time.perf_counter()
            sections = load_session(s, seen)
            parse += time.perf_counter() - tl
            recs += _analyse(sections)
            del sections
        return time.perf_counter() - t, parse, recs, \
            tracemalloc.get_traced_memory()[1]

    def run_all():
        gc.collect()
        tracemalloc.reset_peak()
        t = time.perf_counter()
        sections = load_all(sessions)
        parse = time.perf_counter() - t
        recs = _analyse(sections)
        total = time.perf_counter() - t
        peak = tracemalloc.get_traced_memory()[1]
        del sections
        return total, parse, recs, peak

    tracemalloc.start()
    results: dict[str, list] = {"per-session": [], "all-at-once": []}
    for i in range(rounds):
        results["all-at-once"].append(run_all())
        results["per-session"].append(run_session())
    tracemalloc.stop()

    print(f"{'mode':<14} {'total':>8} {'parse':>8} {'analyse':>8} "
          f"{'peak':>12} {'records':>9}")
    best = {}
    for mode, runs in results.items():
        r = min(runs, key=lambda x: x[0])
        best[mode] = r
        total, parse, recs, peak = r
        print(f"{mode:<14} {total:7.2f}s {parse:7.2f}s {total - parse:7.2f}s "
              f"{_mb(peak)} {recs:>9,}")
    ps, al = best["per-session"], best["all-at-once"]
    if ps[2] != al[2]:
        print(f"\n!! record counts DIFFER ({ps[2]:,} vs {al[2]:,}) — the modes "
              f"are not measuring the same corpus")
    delta = 100 * (ps[0] - al[0]) / al[0]
    sign = "slower" if delta > 0 else "FASTER"
    print(f"\nper-session is {abs(delta):.1f}% {sign} than all-at-once"
          f"   (peak memory {al[3] / max(ps[3], 1):.1f}x lower)")


def check_keywords(sessions) -> None:
    """Do custom commands actually COLLIDE across entries?  A name declared with
    two different kinds parses differently depending on who else is loaded — the
    correctness reason a batch run must scope the table per session rather than
    union the corpus."""
    kinds: dict[str, set[str]] = defaultdict(set)
    declarers: dict[str, set[str]] = defaultdict(set)
    n_with = 0
    per_session: Counter = Counter()
    for s in sessions:
        tbl: dict[str, str] = {}
        for _n, p in session_theories(s):
            if p.suffix == ".thy" and p.exists():
                tbl.update(parsing._scan_header_file(p))
        if tbl:
            n_with += 1
            per_session[s.name] = len(tbl)
        for name, kind in tbl.items():
            kinds[name].add(kind)
            declarers[name].add(s.name)
    clashes = {n: k for n, k in kinds.items() if len(k) > 1}
    shared = {n: d for n, d in declarers.items() if len(d) > 1}
    print(f"sessions scanned                 {len(sessions):>7,}")
    print(f"sessions declaring any command   {n_with:>7,}")
    print(f"distinct custom command names    {len(kinds):>7,}")
    print(f"names declared by >1 session     {len(shared):>7,}")
    print(f"names with CONFLICTING kinds     {len(clashes):>7,}"
          f"   <- a corpus-wide union changes these entries' parse")
    for name, ks in sorted(clashes.items())[:12]:
        who = ", ".join(sorted(declarers[name])[:3])
        print(f"    {name:<28} {sorted(ks)}  ({who}...)")
    print("\nlargest per-session tables:")
    for name, c in per_session.most_common(5):
        print(f"    {name:<40} {c:>5}")


def check_overlap(sessions) -> None:
    """Do two sessions claim the same theory file?

    `_sections_from_dir` dedups by resolved path across the WHOLE root and gives
    the theory to the first session that references it.  Iterating sessions with
    a fresh dedup set per session would drop that rule and emit a shared theory's
    proofs once per claiming session — silent duplicate census records, which
    inflate every corpus aggregate.  This counts the exposure.
    """
    owners: dict[Path, list[str]] = defaultdict(list)
    for s in sessions:
        for _n, p in session_theories(s):
            try:
                owners[p.resolve()].append(s.name)
            except OSError:
                continue
    shared = {p: o for p, o in owners.items() if len(o) > 1}
    dup_records = sum(len(o) - 1 for o in shared.values())
    print(f"sessions scanned                 {len(sessions):>7,}")
    print(f"distinct theory files            {len(owners):>7,}")
    print(f"files claimed by >1 session      {len(shared):>7,}")
    print(f"extra parses a naive loop makes  {dup_records:>7,}"
          f"   <- duplicate census records unless dedup is GLOBAL")
    pairs: Counter = Counter()
    for o in shared.values():
        pairs[" + ".join(sorted(set(o)))] += 1
    print("\nmost-shared session pairs:")
    for who, c in pairs.most_common(8):
        print(f"    {c:>5}  {who[:88]}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("entries", nargs="?", type=int, default=40)
    ap.add_argument("--mode", choices=("all", "session", "both", "compare"),
                    default="session")
    ap.add_argument("--rounds", type=int, default=2,
                    help="A/B rounds for --mode compare (best-of)")
    ap.add_argument("--check", choices=("keywords", "overlap"))
    args = ap.parse_args()

    dirs = sorted(d for d in AFP.iterdir() if d.is_dir())[:args.entries]
    sessions = [s for d in dirs for s in iter_sessions(d)]
    print(f"# {len(dirs)} entry dirs -> {len(sessions)} sessions\n")

    if args.check == "keywords":
        check_keywords(sessions)
        return
    if args.check == "overlap":
        check_overlap(sessions)
        return
    if args.mode == "compare":
        mode_compare(sessions, args.rounds)
        return
    if args.mode in ("session", "both"):
        mode_session(sessions)
    if args.mode == "both":
        print()
    if args.mode in ("all", "both"):
        mode_all(sessions)


if __name__ == "__main__":
    main()
