#!/usr/bin/env python3
r"""Corpus probe: how much ground does column-accurate redaction gain?

`extract_nonisar_ranges` reports only lines with NO live text, so a line whose
comment merely TRAILS real proof text has always been scanned in full — the
phantom citation in `by simp (* see foo *)` is exactly the residual issue #3
closes.  This measures the size of that residual, and the cost of closing it:

  * fully-noise lines   — what whole-line skipping already handled;
  * partial lines       — live text AND a region on one line: the new ground;
  * memory              — the sparse span map, against the source it describes.

The partial figure is the one to watch.  If it were negligible, `live_source`
would be ceremony; if it is large, every one of those lines was contributing
citations the source does not support.

Usage:  probe_live_source.py [N_ENTRIES]
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

n_thy = n_lines = n_full = n_partial = n_span_entries = 0
src_bytes = 0
examples: list[tuple[str, int, str]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
            lines = sec.source()
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        n_lines += len(lines)
        src_bytes += sum(len(ln) for ln in lines)
        full = {i for lo, hi in sec.nonisar_ranges for i in range(lo, hi + 1)}
        n_full += len(full)
        for ln_no, spans in sec.nonisar_spans.items():
            n_span_entries += len(spans)
            if ln_no in full:
                continue
            n_partial += 1
            # A partial line only matters if the redacted half held a word:
            # `(* *)` alone contributes nothing either way.
            if len(examples) < 12:
                raw = lines[ln_no - 1]
                cut = "".join(raw[a:b] for a, b in spans)
                if any(c.isalpha() for c in cut):
                    examples.append((f"{thy_path.stem}:{ln_no}",
                                     len(cut), raw.strip()[:88]))

pct = (100.0 * n_full / n_lines) if n_lines else 0.0
ppct = (100.0 * n_partial / n_lines) if n_lines else 0.0
print(f"theories={n_thy}  lines={n_lines:,}")
print(f"  fully non-Isar (already skipped):  {n_full:>9,}  {pct:5.2f}%")
print(f"  partial (live text + a region):    {n_partial:>9,}  {ppct:5.2f}%"
      "   <- the new ground")
print(f"  span records held:                 {n_span_entries:>9,}")
# 2 ints + a tuple + list overhead, ~120 bytes per span record, vs the source.
print(f"  approx span memory ~{n_span_entries * 120 / 1e6:.1f} MB "
      f"against {src_bytes / 1e6:.1f} MB of source")
print("\nsample partial lines (the redacted part held a word):")
for loc, cut_len, text in examples:
    print(f"  {loc:<34} -{cut_len:>4}c  {text}")
