#!/usr/bin/env python3
r"""Corpus probe: what does `scan_regions`' fast path save?

`scan_regions` returns early when a theory holds no `(*`, `{*`, `\<^cancel>`,
`\<comment>` and no ML command — there is then nothing to redact, so the state
machine never runs.

That shortcut is safe only while the scan's output is about NOISE.  Outer-syntax
spans are different: every theory has terms, so a theory that takes the fast
path would report "no inner syntax" and hand back its `"..."` terms as outer
syntax — the exact false positive the anchor was protecting against.

So the fast path has to narrow, and the question is what that costs.  Measures
how many theories take it today, and what the tokenizer costs on those.

Usage:  probe_fastpath.py [N_ENTRIES]
"""
import sys
import time
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 60

srcs = []
for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for p in sorted(ent.rglob("*.thy")):
        try:
            srcs.append(p.read_text().splitlines())
        except Exception:  # noqa: BLE001
            pass


def takes_fast_path(lines):
    return not any(parsing._ANY_REGION_RE.search(ln) for ln in lines) \
        and not any(parsing._leads_with_ml(ln) for ln in lines)


fast = [s for s in srcs if takes_fast_path(s)]
slow = [s for s in srcs if not takes_fast_path(s)]
n_fast_lines = sum(len(s) for s in fast)
n_all_lines = sum(len(s) for s in srcs)

t0 = time.perf_counter()
for s in srcs:
    parsing.scan_regions(s)
t_today = time.perf_counter() - t0

t0 = time.perf_counter()
for s in srcs:
    parsing._scan_nonisar_spans(s, False)   # the fast path forced OFF
t_always = time.perf_counter() - t0

# The cost that actually matters: recording inner spans, which are far denser
# than noise spans (every `"..."` term in every statement, not just comments).
t0 = time.perf_counter()
n_inner = 0
for s in srcs:
    n_inner += len(parsing.scan_regions(s, True)[2])
t_inner = time.perf_counter() - t0

print(f"theories={len(srcs):,}  lines={n_all_lines:,}")
print(f"  take the fast path today: {len(fast):,} "
      f"({100.0 * len(fast) / max(len(srcs), 1):.1f}% of theories, "
      f"{100.0 * n_fast_lines / max(n_all_lines, 1):.1f}% of lines)")
print(f"  scan_regions as-is        {t_today:6.3f}s")
print(f"  scan always (no fast path){t_always:6.3f}s  "
      f"({t_always - t_today:+.3f}s, "
      f"{100.0 * (t_always - t_today) / max(t_today, 1e-9):+.0f}%)")
print(f"  scan + inner spans        {t_inner:6.3f}s  "
      f"({t_inner - t_today:+.3f}s, "
      f"{100.0 * (t_inner - t_today) / max(t_today, 1e-9):+.0f}% on the scan)")
print(f"    lines carrying inner syntax: {n_inner:,} "
      f"({100.0 * n_inner / max(n_all_lines, 1):.0f}% of lines)")
