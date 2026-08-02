r"""Corpus probe: does `scan_keywords` read commented-out header clauses?

Every other scan in `query` reads a redacted view, so a commented-out command
is not a command.  `scan_keywords` — which reads a theory header's `keywords`
clause to learn that entry's OWN custom outer-syntax commands — still reads the
raw source, so a superseded clause left in a `(* ... *)` would register phantom
commands, and a phantom command mints phantom entries wherever its name occurs.

The right view here is `live_source`, NOT `outer_source`: a `keywords` clause
names its commands in `"..."` strings, which the outer view blanks.  Live text
with the noise gone is exactly the distinction wanted.

Reports theories where the two disagree, so the gap can be sized before it is
fixed (or left alone).

Usage:  probe_keyword_scan.py [N_ENTRIES]
"""
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import parsing  # noqa: E402
from isabelle_query.model import blank_all  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

n_thy = n_with = n_diff = 0
diffs: list[str] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            lines = thy_path.read_text().splitlines()
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        raw_table = parsing.scan_keywords(lines)
        spans, _notes, _inner, _open = parsing.scan_regions(lines)
        live_table = parsing.scan_keywords(blank_all(lines, spans))
        if raw_table:
            n_with += 1
        if raw_table != live_table:
            n_diff += 1
            if len(diffs) < 10:
                extra = set(raw_table) - set(live_table)
                missing = set(live_table) - set(raw_table)
                diffs.append(f"  {ent.name}/{thy_path.stem}\n"
                             f"      only in RAW (phantom): {sorted(extra)}\n"
                             f"      only in LIVE:          {sorted(missing)}")

print(f"theories={n_thy:,}   with a `keywords` clause: {n_with:,}")
print(f"raw vs live disagree: {n_diff}")
for d in diffs:
    print(d)
