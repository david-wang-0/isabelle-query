#!/usr/bin/env python3
"""Scratch: does the two-entry `Examples` fixture reach every emitter?

Checks that the citation survives the `closure` visibility filter and that
`callers` really does read a hit's owner out of the wrong file when the two
theories share a name.  Kept because it is the shortest end-to-end run of
every `theory:line` emitter over a colliding corpus [disambig-loci].
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli  # noqa: E402
from isabelle_query.render import theory_labels  # noqa: E402

# Two AFP-shaped entries, each its own ROOT, each declaring a theory called
# `Examples` — which is what makes the names collide.  A ROOT that spells a
# theory with a directory (`"alpha/Examples"`) gives the SECTION that spelling
# as its name, so there is no collision to demonstrate; the AFP's collisions
# come from entries declaring a bare `Examples` in their own directory.
#
# Alpha's `Examples` is 5 lines; Beta's citation is on line 6.  So a hit read
# out of the wrong file lands PAST THE END of the file it was read from, and
# the wrongness is visible rather than merely possible.
FILES = {
    "alpha/ROOT": "session Alpha = HOL +\n  theories\n    Base\n    Examples\n",
    "alpha/Base.thy": (
        "theory Base\nimports Main\nbegin\n"
        'lemma shared: "True" by simp\nend\n'),
    "alpha/Examples.thy": (
        "theory Examples\nimports Base\nbegin\n"
        'lemma a_owner: "True" using shared by simp\nend\n'),
    "beta/ROOT": "session Beta = HOL +\n  theories\n    Base\n    Examples\n",
    "beta/Base.thy": (
        "theory Base\nimports Main\nbegin\n"
        'lemma shared: "True" by simp\nend\n'),
    "beta/Examples.thy": (
        "theory Examples\nimports Base\nbegin\n"
        'lemma b_pad: "True" by simp\n\n'
        'lemma b_owner: "True" using shared by simp\n\n'
        'lemma b_after: "True" sorry\nend\n'),
}


def main() -> int:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        for rel, text in FILES.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
        cli._ROOT_OVERRIDE = root
        root = root.resolve()
        sections = cli.load_index()
        print("loaded:", [(s.theory, str(s.path.relative_to(root)))
                          for s in sections])
        labels = theory_labels(sections)
        print("labels:", {str(p.relative_to(root.resolve())): v
                          for p, v in labels.items()})

        hits = cli._find_callers(sections, "shared")
        print("callers shared:", [(s.theory, str(s.path.name), ln)
                                  for s, ln, _t in hits])

        f = cli.CmdFlags()
        f.context = 1
        print("\n--- callers shared -U 1 ---")
        cli.cmd_callers(sections, "shared", f)
        print("\n--- sorry ---")
        cli.cmd_sorry(sections, False)
        print("\n--- grep b_owner ---")
        cli.cmd_grep(sections, "b_owner", cli.CmdFlags())
        print("\n--- enclosing Examples:6 ---")
        cli.cmd_enclosing(sections, ["Examples:6"])
        print("\n--- enclosing beta/Examples:6 ---")
        cli.cmd_enclosing(sections, ["beta/Examples:6"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
