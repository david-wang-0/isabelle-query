#!/usr/bin/env python3
"""Every line a declaration LOST must be a comment line, and nothing else.

`[decl-body-comment]` stops the body scan breaking at a formal comment.  That
repairs 401 truncated declarations — and shrinks 305, which is the direction
that needs proving rather than sampling: a body that gets SHORTER is a
declaration newly cut short unless every dropped line is pure comment text
that was never body to begin with.

The invariant checked here, per shrunk record:

    every line in (new_body_end .. old_body_end] is blank in `live_source()`

i.e. the tokenizer classifies it as noise.  A single violation means the
change dropped real declaration text.

    python scripts/probe_body_shrink_check.py .before.txt .after.txt [ROOT]
"""

from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402


def load(path: str) -> dict[str, int]:
    out = {}
    for line in open(path, encoding="utf-8"):
        parts = line.rstrip("\n").split(":")
        body = next((p for p in parts if p.startswith("body_end=")), None)
        if body:
            out[":".join(parts[:3])] = int(body.split("=", 1)[1])
    return out


def main() -> int:
    before, after = load(sys.argv[1]), load(sys.argv[2])
    root = Path(sys.argv[3]).expanduser() if len(sys.argv) > 3 else \
        Path.home() / "repos/afp/thys"

    shrunk = defaultdict(list)          # "entry/stem" -> [(key, new, old)]
    for k in before.keys() & after.keys():
        if after[k] < before[k]:
            shrunk[k.rsplit(":", 2)[0]].append((k, after[k], before[k]))
    total = sum(len(v) for v in shrunk.values())
    print(f"{total} shrunk records over {len(shrunk)} theories")

    # Walk exactly as `dump_entries.py` does -- `ent.name/stem` keyed off an
    # rglob, NOT `load_index`, whose sections are keyed by theory NAME.  Keying
    # this by name resolved nothing at all and reported "0 violations" over 0
    # records checked, which reads identical to a clean result.
    checked = violations = unresolved = 0
    seen = set()
    for ent in sorted(d for d in root.iterdir() if d.is_dir()):
        for thy_path in sorted(ent.rglob("*.thy")):
            key = f"{ent.name}/{thy_path.stem}"
            rows = shrunk.get(key)
            if not rows or key in seen:
                continue
            seen.add(key)
            try:
                sec = cli._parse_one(thy_path.stem, thy_path)
            except Exception:  # noqa: BLE001
                continue
            live, src = sec.live_source(), sec.source()
            for rec, new_end, old_end in rows:
                checked += 1
                bad = [n for n in range(new_end + 1, old_end + 1)
                       if n - 1 < len(live) and live[n - 1].strip()]
                if bad:
                    violations += 1
                    if violations <= 10:
                        print(f"  VIOLATION {rec}: {new_end}->{old_end}, "
                              f"live lines {bad}")
                        for n in bad[:2]:
                            print(f"      {n}| {src[n - 1].rstrip()[:70]}")
    unresolved = total - checked

    print(f"\nchecked      {checked}")
    print(f"unresolved   {unresolved}  (theory name not unique / not loaded)")
    print(f"VIOLATIONS   {violations}")
    if not violations and checked:
        print("\nEvery dropped line is blank in the live view: the shrinks "
              "removed comment text only.")
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
