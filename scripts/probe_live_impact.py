#!/usr/bin/env python3
r"""Corpus probe: what does reading `live_source()` change in the call graph?

Issue #3 names this as the dangerous step.  Switching the scan loops to the
redacted view can only REMOVE edges, and the suite is built around asserting
edges are absent — so an over-redaction that deletes true citations passes every
test and shows up only here.  The probe builds each entry's graph twice, once
against `live_source()` and once with it monkeypatched back to `source()`, and
diffs.

What to look for:

  * every dropped edge should quote a line where the cited name really does sit
    inside a comment / `\<^cancel>` / inline ML body — read the samples;
  * NO edge should be gained (redaction cannot add one); a gain means the two
    views disagree about line numbering, which would break every span in the
    tool;
  * `entries` and `names` must be identical in both runs — redaction must not
    reach declaration parsing, which happens before this and reads `source()`.

Usage:  probe_live_impact.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.model import TheorySection  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

_LIVE = TheorySection.live_source


def edges(secs, redact):
    TheorySection.live_source = _LIVE if redact else TheorySection.source
    try:
        g = cli._build_call_graph(secs, derived=True)
        return ({(callee, caller)
                 for callee, callers in g.callers.items() for caller in callers},
                {n for n, c in g.callers.items() if c})
    finally:
        TheorySection.live_source = _LIVE


def region_kind(raw: str, spans: list[tuple[int, int]], name: str) -> str:
    """Which construct swallowed `name` on this line, read off the raw text.

    The tokenizer emits spans without a tag, but the opening delimiter is right
    there at the span start, so the kind is recoverable — and worth recovering:
    a comment and an ML body are dropped for different reasons, and only one of
    them is arguably a citation (an `@{thm foo}` antiquotation names a real
    fact, though the tool has never counted antiquotations anywhere).
    """
    for lo, hi in spans:
        if name not in raw[lo:hi]:
            continue
        head = raw[lo:lo + 12]
        if head.startswith("(*"):
            return "comment"
        if head.startswith("{*"):
            return "verbatim"
        if head.startswith("\\<^cancel>"):
            return "cancel"
        return "ml-antiq" if "@{" in raw[lo:hi] else "ml-body"
    return "?"


n_entries = n_edges_live = n_edges_raw = 0
dropped_by_name: Counter = Counter()
dropped_by_kind: Counter = Counter()
gained: list[tuple[str, str, str]] = []
newly_dead: list[tuple[str, str]] = []
samples: list[tuple[str, str, str, str]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    secs = []
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            secs.append(cli._parse_one(thy_path.stem, thy_path))
        except Exception:  # noqa: BLE001
            pass
    if not secs:
        continue
    n_entries += 1
    (live, cited_live), (raw, cited_raw) = edges(secs, True), edges(secs, False)
    n_edges_live += len(live)
    n_edges_raw += len(raw)
    # The consequence that actually reaches a user: a name whose ONLY citation
    # was redacted now has none, so `unused` starts reporting it as dead.
    for n in cited_raw - cited_live:
        newly_dead.append((ent.name, n))
    for callee, caller in raw - live:
        dropped_by_name[callee] += 1
        # Find the line that used to carry the edge, to show WHY it went.
        for sec in secs:
            for i, (r, lv) in enumerate(zip(sec.source(),
                                            sec.live_source()), 1):
                if callee in r and callee not in lv:
                    kind = region_kind(r, sec.nonisar_spans.get(i, []), callee)
                    dropped_by_kind[kind] += 1
                    if len(samples) < 25:
                        samples.append((kind, callee, f"{sec.theory}:{i}",
                                        r.strip()[:92]))
                    break
            else:
                continue
            break
    for callee, caller in live - raw:
        gained.append((ent.name, callee, caller))

print(f"entries={n_entries}")
print(f"  edges with live_source: {n_edges_live:,}")
print(f"  edges with source():    {n_edges_raw:,}")
print(f"  dropped: {n_edges_raw - n_edges_live:,} "
      f"({100.0 * (n_edges_raw - n_edges_live) / max(n_edges_raw, 1):.3f}%) "
      f"over {len(dropped_by_name)} distinct names")
print(f"  GAINED (must be 0): {len(gained)}")
for g in gained[:10]:
    print(f"    !! {g}")
print(f"  newly reported unused (lost their ONLY citation): {len(newly_dead)}")
for ent, n in newly_dead[:15]:
    print(f"    {ent}/{n}")
print("\ndropped by region kind:")
for kind, c in dropped_by_kind.most_common():
    print(f"  {kind:<12} {c}")
print("\nmost-dropped names:")
for name, c in dropped_by_name.most_common(12):
    print(f"  {name:<28} {c}")
print("\nsample dropped edges — each line should show the name inside a region:")
for kind, name, loc, text in samples:
    print(f"  {kind:<9} [{name}] {loc}")
    print(f"      {text}")
