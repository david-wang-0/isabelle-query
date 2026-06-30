#!/usr/bin/env python3
r"""Probe entry indexing and src/body span attribution on synthetic snippets.

WHY THIS EXISTS
    Two parser behaviours are easy to regress and awkward to eyeball from the
    unit tests alone, because the interesting signal is the *numbers* — which
    command introduces an entry, and where each entry's `src` / `body` / span
    boundaries land relative to a neighbouring `text` docstring:

      * `function` (and `fun` / `primrec` / `inductive` / `inductive_set`) must
        register their principal constant as an entry  (todo `[function-defs]`);
      * a leading `text` doc block must be charged to the entry it documents,
        so the preceding entry's `src` does not over-reach and `enclosing` on a
        doc line names the *following* lemma  (todo `[src-doc-attribution]`).

    Rather than re-type `python3 -c '...'` with `\<...>` escaping each time, this
    script parses a handful of representative snippets and prints the spans and
    per-line `enclosing` attribution.  Re-run it after any change to the entry
    parser / `compute_spans` / preamble attachment to confirm both fixes still
    hold, or add a snippet to characterise a new construct.

USAGE
    python3 scripts/probe_entry_spans.py            # all snippets
    python3 scripts/probe_entry_spans.py function   # only matching snippets

    (run from the repo root; uses the working-tree src/ via tests/support).
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), os.pardir, "tests"))
from support import cli, section_from  # noqa: E402


# Each snippet is (label, theory-source).  Keep them small and self-contained.
SNIPPETS = {
    "function": r'''theory T imports Main begin

function (sequential) wrap_enc :: "nat \<Rightarrow> nat" where
  "wrap_enc 0 = 0"
| "wrap_enc (Suc n) = wrap_enc n"
  by pat_completeness auto
termination by lexicographic_order

primrec plen :: "nat list \<Rightarrow> nat" where
  "plen [] = 0"

inductive even2 :: "nat \<Rightarrow> bool" where
  "even2 0"

end
''',
    "preamble": r'''theory T imports Main begin

lemma back_loop:
  "P x"
  by simp

text \<open>
  This block documents the forward lemma below.
  It is fwd_loop_gen's docstring, not back_loop's trailing text.
\<close>

lemma fwd_loop_gen:
  "Q x"
  by simp

end
''',
}


def probe(label: str, source: str) -> None:
    sec = section_from(source, "T")
    print(f"\n=== {label} " + "=" * (60 - len(label)))
    print(f"{'name':16} {'tag':8} {'src_start':>9} {'thy_line':>8} "
          f"{'body_end':>8} {'thy_end':>7}  preamble")
    for e in sec.entries:
        print(f"{e.name:16} {e.tag:8} {e.src_start:>9} {e.thy_line:>8} "
              f"{e.body_end_line:>8} {e.thy_end:>7}  {e.preamble}")
        print(f"{'':16} extent: {cli._format_extent(e)}")
    # Per-line enclosing attribution across the whole theory body.
    print("  line -> owner (role):")
    for ln in range(1, len(sec.source()) + 1):
        owner = cli._enclosing_entry(sec, ln)
        if owner is None:
            continue
        role = cli._locus_role(owner, ln)
        print(f"    {ln:3} -> {owner.name:16} {role}")


def main() -> None:
    wanted = sys.argv[1:]
    for label, source in SNIPPETS.items():
        if wanted and not any(w in label for w in wanted):
            continue
        probe(label, source)


if __name__ == "__main__":
    main()
