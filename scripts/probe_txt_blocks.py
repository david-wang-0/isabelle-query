#!/usr/bin/env python3
r"""Corpus probe: is `txt \<open>...\<close>` prose being scanned as code?

`TEXT_OPEN_RE` matches `text` and `text_raw`.  Isabelle's *in-proof* document
command is `txt` (with `txt_raw` beside it) — the one that appears between proof
steps rather than between declarations — and it is not in that pattern, so its
body is not blanked from the live view.

Found while judging the residue of `probe_method_coverage.py`: tokens like `the`,
`a`, `an`, `means`, `moving`, `replacing` appeared after a `by`/`apply`, which is
English, not Isar.  They come from lines like

    txt \<open>An assumption is made that must be justified by the current proof
      context. In this case the corresponding fact had been generated
      by a rule automatically invoked by the inner ...\<close>

This measures the leak directly: `txt` block spans found with the parser's own
balanced-cartouche scanner, then how many of them the live view still shows, and
how many analysed proof *steps* land inside one.  A step inside a prose block is
a phantom — it inflates n_steps, and its text is mined for methods and citations.

Usage:  probe_txt_blocks.py [N_ENTRIES] [--show N]
"""
import os
import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if not os.environ.get("PYTHONPATH"):
    sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, shape  # noqa: E402
from isabelle_query.parsing import _scan_balanced_blocks  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
_args = sys.argv[1:]
SHOW = 0
if "--show" in _args:
    i = _args.index("--show")
    SHOW = int(_args[i + 1])
    del _args[i:i + 2]
LIMIT = int(_args[0]) if _args else 40

# Same shape as TEXT_OPEN_RE, for the commands it omits.
_TXT_OPEN_RE = re.compile(r"^\s*(txt|txt_raw)\s*\\<open>")
_DISCHARGE_RE = re.compile(r"\b(?:by|apply)\b\s*\(?\s*([\w']+)")

tot: Counter = Counter()
entries_hit: set[str] = set()
samples: list[str] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            sec = cli._parse_one(thy_path.stem, thy_path)
        except Exception:  # noqa: BLE001
            continue
        raw = sec.source()
        blocks = _scan_balanced_blocks(raw, lambda ln: bool(_TXT_OPEN_RE.match(ln)))
        if not blocks:
            continue
        tot["theories_with_txt"] += 1
        entries_hit.add(ent.name)
        live = sec.live_source()
        txt_lines = set()
        for a, b in blocks:
            tot["blocks"] += 1
            tot["block_lines"] += b - a + 1
            for ln in range(a, b + 1):
                txt_lines.add(ln)
                # Live means "not blanked": the scanners will read this text.
                if live[ln - 1].strip():
                    tot["live_lines"] += 1

        # Phantom steps: an analysed step whose line sits inside a txt block.
        for entry in sec.entries:
            pm = shape.analyze_proof(sec, entry)
            if pm is None:
                continue
            for s in pm.steps:
                if s.line in txt_lines:
                    tot["phantom_steps"] += 1
                    if _DISCHARGE_RE.search(live[s.line - 1]):
                        tot["phantom_discharge"] += 1
                    if len(samples) < SHOW:
                        samples.append(
                            f"  {sec.theory}:{s.line}  kw={s.kw!r} "
                            f"kind={s.kind!r} method={s.method!r}\n"
                            f"      {raw[s.line - 1].strip()[:120]}")

print(f"entries scanned: {LIMIT} (…up to)   entries containing `txt`: "
      f"{len(entries_hit)}")
print(f"theories with a `txt` block: {tot['theories_with_txt']}")
print(f"`txt` blocks: {tot['blocks']}   spanning {tot['block_lines']} lines")
print(f"  lines still LIVE (not blanked — read as code): {tot['live_lines']} "
      f"({100 * tot['live_lines'] / max(tot['block_lines'], 1):.1f}%)")
print(f"\nphantom proof steps inside a `txt` block: {tot['phantom_steps']}")
print(f"  …carrying a by/apply introducer (prose mined as a method): "
      f"{tot['phantom_discharge']}")
if samples:
    print("\nsamples:")
    print("\n".join(samples))
