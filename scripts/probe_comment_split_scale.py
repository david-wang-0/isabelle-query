#!/usr/bin/env python3
r"""How often do the two remaining comment flaws actually occur?

Both are cheap to *describe* and expensive to *fix* (a tokenizer state change),
so the decision rests on scale, not on the shapes being real.  This counts
them corpus-wide.

**A. `[comment-newline]` — a marker split from its cartouche.**

    shows \<open>... \<close>              Substitutions_Lambda_Free:62
    \<comment>                                                      :63
    \<open>                                                         :64
      This lemma could easily be generalized ...                    :65
    \<close>                                                        :67

Isabelle's `comment_prefix` allows any blanks — newlines included — between a
formal comment marker and the cartouche it owns, so that is ONE comment.
`_MARKER_OPEN_RE` requires both on one line, so the scanner sees a bare marker
and then a separate LIVE cartouche.

Detected NON-CIRCULARLY, by the scanner's own failure rather than by a text
pattern that guesses at the same thing: if the scanner had consumed the marker
the line would be blank in `live_source()`.  A line whose live text is still
exactly a marker is one it did not consume.  That also excludes a marker
written inside a string or an outer comment, which a raw regex would count.

Cost is then measured, not assumed: the prose lines left live, and how many of
their tokens name an entry the corpus actually declares — i.e. how many
phantom citations the leak can manufacture.

**B. the `body_end_line` collapse.**

A comment between a keyword and its name also truncates the recorded body:
`WFair`'s `transient` reports `body 35..35` for a declaration running to 43.
`body_end_line` is documented as the safe relocation cut, so a cut there
leaves the declaration behind.  Counted as: the keyword stands alone (the name
is not on the keyword's own live line), a redacted line follows, and
`body_end_line` never gets past the keyword line.

    python scripts/probe_comment_split_scale.py [ROOT ...]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.parsing import FORMAL_COMMENTS, ISA_WORD_CHAR  # noqa: E402

_DEFAULTS = ("/Applications/Isabelle2025-2.app/src/HOL",
             "/Applications/Isabelle2025-2.app/src/FOL",
             "/Applications/Isabelle2025-2.app/src/ZF")

_TOKENS = re.compile(rf"{ISA_WORD_CHAR}+").findall
# Command modifiers -- `(input)`, `(in foo)`, `(overloaded)` -- which sit
# between a keyword and its name and are not names.
_PARENS = re.compile(r"\([^()]*\)")
_OPENERS = ("\\<open>", "‹")
_MARKERS = tuple(FORMAL_COMMENTS)
# A cartouche body rarely runs past this; only used to bound the prose count.
_MAX_PROSE = 60


def _split_sites(live: list[str], raw: list[str]) -> list[tuple[int, int]]:
    r"""[(marker_line, close_line)] 1-indexed, for every marker the scanner
    failed to consume whose cartouche opens on a LATER line."""
    out = []
    for i, ln in enumerate(live):
        s = ln.strip()
        if s not in _MARKERS:
            continue
        # The scanner left the marker live.  Does a cartouche open below it?
        j = i + 1
        while j < len(raw) and not raw[j].strip():
            j += 1
        if j >= len(raw) or not raw[j].lstrip().startswith(_OPENERS):
            continue
        # Walk to the matching close, bounded.
        depth, k = 0, j
        while k < min(len(raw), j + _MAX_PROSE):
            depth += raw[k].count("\\<open>") - raw[k].count("\\<close>")
            if depth <= 0 and k > j:
                break
            if depth <= 0 and k == j and raw[k].count("\\<close>"):
                break
            k += 1
        out.append((i + 1, min(k + 1, len(raw))))
    return out


def scan(root: Path) -> dict:
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    declared = {e.name for s in sections for e in s.entries
                if e.name not in ("?", "")}

    sites, prose_lines, phantom_toks = [], 0, set()
    entries_hit, collapsed = set(), []
    for sec in sections:
        raw, live = sec.source(), sec.live_source()
        found = _split_sites(live, raw)
        for a, b in found:
            sites.append((sec.theory, a, b))
            prose_lines += b - a
            for ln in live[a - 1:b]:
                phantom_toks.update(t for t in _TOKENS(ln) if t in declared)
            for e in sec.entries:
                if e.src_start <= a <= e.thy_end:
                    entries_hit.add((sec.theory, e.name, e.thy_line))

        # B: the body_end collapse.
        for e in sec.entries:
            if not e.thy_line or e.body_end_line != e.thy_line:
                continue
            if e.thy_end - e.thy_line < 1:
                continue
            kw = live[e.thy_line - 1] if e.thy_line - 1 < len(live) else ""
            # The keyword must STAND ALONE.  Testing `e.name not in kw` instead
            # passes vacuously for every anonymous entry, which counted
            # `Merkle_Interface:64` -- a complete one-line `type_synonym` whose
            # span merely happens to reach a later `(* ... *)`.
            #
            # Modifiers are stripped first, as the parser's own
            # `_strip_decl_prefix` does: `abbreviation (input)` IS a bare
            # keyword, and counting its tokens raw dropped `ZF/Multiset:14`.
            if len(_TOKENS(_PARENS.sub("", kw))) > 1:
                continue
            # ...and the very next thing must be a redacted line, i.e. the
            # comment sits BETWEEN the keyword and the name.
            nxt = live[e.thy_line:e.thy_end]
            src = raw[e.thy_line:e.thy_end]
            first = next(((a, b) for a, b in zip(nxt, src) if b.strip()), None)
            if first and not first[0].strip():
                collapsed.append((sec.theory, e.name, e.thy_line, e.thy_end))

    return {"sites": sites, "prose_lines": prose_lines,
            "phantoms": phantom_toks, "entries": entries_hit,
            "collapsed": collapsed, "n_theories": len(sections)}


def main() -> int:
    roots = [Path(a).expanduser() for a in sys.argv[1:]] or [
        Path(p) for p in _DEFAULTS]
    for root in roots:
        if not root.is_dir():
            print(f"{root}: absent, skipped")
            continue
        r = scan(root)
        thys = {t for t, _, _ in r["sites"]}
        print(f"\n=== {root}  ({r['n_theories']} theories) ===")
        print(f"A  [comment-newline] split sites : {len(r['sites'])} "
              f"in {len(thys)} theories")
        print(f"   prose lines wrongly live      : {r['prose_lines']}")
        print(f"   of those, tokens naming a real declaration: "
              f"{len(r['phantoms'])}")
        print(f"   entries whose span contains one: {len(r['entries'])}")
        for t, a, b in r["sites"][:10]:
            print(f"     {t}:{a}..{b}")
        if len(r["sites"]) > 10:
            print(f"     ... and {len(r['sites']) - 10} more")
        print(f"B  body_end collapsed onto keyword: {len(r['collapsed'])}")
        for t, n, a, b in r["collapsed"][:10]:
            print(f"     {t}:{a:<6} {n:24} span {a}..{b}, body {a}..{a}")
        if len(r["collapsed"]) > 10:
            print(f"     ... and {len(r['collapsed']) - 10} more")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
