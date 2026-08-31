#!/usr/bin/env python3
r"""What does query name, for each shape of formal comment near a declaration?

Six shapes, hand-computed expectations, so the two open items
([comment-newline], [comment-before-name]) are separated from what already
works.  Uses the supported `isabelle_query.api` surface deliberately — this is
exactly the "spans rather than a report" consumer that issue #10 described.

Run:  python scripts/probe_comment_shapes.py
"""

import tempfile
from pathlib import Path

from isabelle_query.api import parse_theory

# (label, expected_name, source-after-`begin`)
CASES = [
    ("plain",
     "transient", r'''definition
  transient :: "nat set" where "transient = {}"'''),

    ("same-line comment before name  [already fixed]",
     "transient", r'''definition \<comment> \<open>This definition is generic to all forms.\<close>
  transient :: "nat set" where "transient = {}"'''),

    ("ONE-LINE comment on its own line",
     "transient", r'''definition
  \<comment> \<open>This definition is generic to all forms.\<close>
  transient :: "nat set" where "transient = {}"'''),

    ("MULTI-LINE comment on its own lines  [comment-before-name]",
     "transient", r'''definition
  \<comment> \<open>This definition specifies conditional fairness.  The rest
      is generic to all forms of fairness.\<close>
  transient :: "nat set" where "transient = {}"'''),

    ("marker, multi-line, before the name",
     "transient", r'''definition
  \<^marker>\<open>tag important
      and more\<close>
  transient :: "nat set" where "transient = {}"'''),

    ("ONE-LINE marker on its own line",
     "transient", r'''definition
  \<^marker>\<open>tag important\<close>
  transient :: "nat set" where "transient = {}"'''),

    ("marker+cartouche split by a newline  [comment-newline]",
     "foo", r'''lemma foo: "True"
  \<comment>
  \<open>
    This lemma could easily be generalized.
  \<close>
  by simp'''),
]

# The OTHER symptom: a formal comment's prose must not be live source.  If it
# is, every word in it is a citation candidate and the declaration's extent
# swallows it.  `PROSE_WORD` is a plausible fact name written only in prose.
PROSE_WORD = "generalized"

LIVENESS = [
    ("comment on ONE line",
     r'''lemma foo: "True"
  \<comment> \<open>This could easily be generalized.\<close>
  by simp'''),

    ("comment split, body on the opener's line",
     r'''lemma foo: "True"
\<comment>
\<open>This could easily be generalized.\<close>
  by simp'''),

    # The real shape, Substitutions_Lambda_Free:63-67: the marker is alone on
    # its line AND the cartouche opener is alone on the next.  This is the one
    # that leaks -- `callers generalized` reports a hit at :65.
    ("comment split, opener alone too  [comment-newline]",
     r'''lemma foo: "True"
\<comment>
\<open>
  This could easily be generalized.
\<close>
  by simp'''),
]

HEAD = "theory Probe\nimports Main\nbegin\n"
TAIL = "\nend\n"


def section_of(body: str, td: str):
    p = Path(td) / "Probe.thy"
    p.write_text(HEAD + body + TAIL, encoding="utf-8")
    return parse_theory("Probe", p)


def names_of(body: str) -> list[tuple[str, int, int]]:
    with tempfile.TemporaryDirectory() as td:
        sec = section_of(body, td)
    return [(e.name, e.thy_line, e.decl_end_line) for e in sec.entries]


def prose_is_live(body: str) -> bool:
    """Does `PROSE_WORD`, written only inside a formal comment, survive into
    the live view?  If it does, it is a citation candidate.

    `live_source()` returns a LIST OF LINES, not a string: `WORD in view` asks
    whether some line *equals* WORD, which is never true.  Written that way
    this check passed vacuously on every shape, including the ones that leak.
    """
    with tempfile.TemporaryDirectory() as td:
        sec = section_of(body, td)
        return any(PROSE_WORD in ln for ln in sec.live_source())


def main() -> None:
    width = max(len(lbl) for lbl, _, _ in CASES)
    bad = 0
    print("== the NAME a declaration gets ==")
    for label, want, body in CASES:
        got = names_of(body)
        ok = want in [n for n, _, _ in got]
        bad += not ok
        print(f"{'ok ' if ok else 'BAD'} {label:<{width}}  "
              f"want {want!r:<12} got {got}")

    print("\n== is a formal comment's PROSE live source? (want: no) ==")
    w2 = max(len(lbl) for lbl, _ in LIVENESS)
    for label, body in LIVENESS:
        live = prose_is_live(body)
        bad += live
        print(f"{'BAD' if live else 'ok '} {label:<{w2}}  "
              f"{PROSE_WORD!r} in live_source(): {live}")

    # The proposed fix for the name cases is for `_lookahead_name` to skip a
    # line that is BLANK IN THE LIVE VIEW rather than one that textually starts
    # with `\<comment>`.  That only works if the tokenizer already blanks these
    # lines.  Print the live view so the premise is checked, not assumed.
    print("\n== live view of each name case (fix premise: comment lines blank) ==")
    for label, _want, body in CASES:
        with tempfile.TemporaryDirectory() as td:
            live = section_of(body, td).live_source()
        print(f"\n  {label}")
        for k, ln in enumerate(live[3:], start=4):   # after `begin`
            if not ln.strip() and not (HEAD + body + TAIL).splitlines()[k - 1].strip():
                continue                              # genuinely blank source
            mark = "BLANK" if not ln.strip() else "live "
            print(f"    {k:>3} {mark} {ln.rstrip()[:60]!r}")

    print(f"\n{bad} of {len(CASES) + len(LIVENESS)} shapes wrong.")


if __name__ == "__main__":
    main()
