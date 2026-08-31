#!/usr/bin/env python3
r"""Where exactly does the split `\<comment>` leak, on the REAL record?

`callers generalized` reports a hit at Substitutions_Lambda_Free:65, but a
synthetic of the same apparent shape does not leak through `live_source()`.
One of those two instruments is measuring the wrong thing.  This checks every
view on the real file so the answer is not a guess.

Run:  python scripts/probe_comment_newline_real.py
"""

from pathlib import Path

from isabelle_query.api import parse_theory

THY = Path.home() / ("repos/afp/thys/Substitutions_Lambda_Free/"
                     "Substitutions_Lambda_Free.thy")
WORD = "generalized"
LINE = 65          # where the word is written, 1-indexed


def main() -> None:
    if not THY.is_file():
        raise SystemExit(f"{THY}: not found")
    sec = parse_theory("Substitutions_Lambda_Free", THY)

    # NOTE: these views are LISTS OF LINES, not strings.  `WORD in view` on a
    # list asks whether some line equals WORD, which is never true — a check
    # written that way passes vacuously.
    live = sec.live_source()
    outer = sec.outer_source()
    raw = sec.source()

    print(f"line {LINE} in each view:")
    for label, view in (("raw  ", raw), ("live ", live), ("outer", outer)):
        text = view[LINE - 1] if LINE - 1 < len(view) else "<past end>"
        print(f"  {label}: {text.rstrip()!r}")

    print(f"\n{WORD!r} present in:")
    print(f"  live_source()  : {any(WORD in ln for ln in live)}")
    print(f"  outer_source() : {any(WORD in ln for ln in outer)}")

    print("\ncomment_ranges covering the split comment (63..67):")
    for a, b in sec.comment_ranges:
        if a <= 67 and b >= 63:
            print(f"  ({a}, {b})")
    else:
        pass
    if not any(a <= 67 and b >= 63 for a, b in sec.comment_ranges):
        print("  NONE — the scanner does not see one comment here")

    print("\nnonisar_ranges covering 63..67:")
    hits = [(a, b) for a, b in sec.nonisar_ranges if a <= 67 and b >= 63]
    print(f"  {hits or 'NONE'}")

    e = next((e for e in sec.entries if e.name == "set_decr_chain_empty"), None)
    if e:
        print(f"\nset_decr_chain_empty: thy_line={e.thy_line} "
              f"decl_end={e.decl_end_line} proof_line={e.proof_line} "
              f"body_end={e.body_end_line}")
        print("  the declaration really ends at 62 (`shows ...`), and the "
              "proof starts at 68 (`proof-`)")


if __name__ == "__main__":
    main()
