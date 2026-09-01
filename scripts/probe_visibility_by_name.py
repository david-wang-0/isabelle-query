#!/usr/bin/env python3
r"""Does the name-keyed import closure decide anything wrongly?
[visibility-by-name]

`graph._Visibility` keys its closure by theory NAME, so two theories sharing a
name share one closure — and the shared one is built from whichever section
`_sections_by_theory` kept.  The last of the five name-as-identity collapses;
the other four shipped under [disambig-loci] and [name-is-not-identity].

Measured in three tightening steps, so a cheap answer can close the item
without building the per-entry import resolution the real fix would need:

1. **Necessary condition.**  A shadowed section's closure can only differ from
   its winner's if the two files' `imports` clauses differ.  Same imports,
   same closure, nothing to fix — and this is exact, no approximation.
2. **Reach.**  Where they do differ, how far apart are the two closures?
   Computed with the shipped name-keyed adjacency for the transitive hops,
   which is itself collapsed — so this is a LOWER bound on the divergence,
   and reported as one.
3. **Effect.**  How many citation attributions `sees()` currently decides for
   those sections would flip.  Only the candidates where the closure is
   consulted at all count: `sees` short-circuits when the citing theory
   declares the name itself, which is the common case.

    python scripts/probe_visibility_by_name.py [ROOT]
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_layout import parse_thy_imports  # noqa: E402

from isabelle_query import cli, graph  # noqa: E402


def main() -> int:
    root = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    by_theory = graph._sections_by_theory(sections)
    shadowed = [s for s in sections if by_theory.get(s.theory) is not s]
    print(f"{len(sections)} theories under {root}")
    print(f"{len(shadowed)} shadowed in the name-keyed closure map\n")

    # --- 1. necessary condition: do the two files import the same things? --
    def imports_of(sec):
        if not sec.path.is_file():
            return None
        return frozenset(
            c for c in (graph._resolve_import(i, by_theory)
                        for i in parse_thy_imports(sec.path))
            if c is not None)

    differing = []
    same = unreadable = 0
    for sec in shadowed:
        mine = imports_of(sec)
        theirs = imports_of(by_theory[sec.theory])
        if mine is None or theirs is None:
            unreadable += 1
        elif mine == theirs:
            same += 1
        else:
            differing.append((sec, mine, theirs))
    print(f"{same:6}  shadowed sections import exactly what their winner does")
    print(f"{unreadable:6}  had an unreadable header on one side")
    print(f"{len(differing):6}  import something different — the only ones "
          f"whose closure CAN differ\n")
    if not differing:
        print("Nothing to fix: the shared closure is the right closure.")
        return 0

    # --- 2. how far apart are the two closures? ---------------------------
    vis = graph._Visibility(sections, "closure")

    def closure_from(seeds):
        """Closure over the shipped name adjacency, seeded by a real file's
        own imports.  The transitive hops are still name-keyed, so this
        UNDERSTATES the divergence — reported as a lower bound."""
        unknown = False

        def children(name):
            nonlocal unknown
            got = vis._read_imports(name)
            if got is None:
                unknown = True
                return []
            return got

        depths = graph._bfs_depths(children, list(seeds), seed_depth=-1)
        return None if unknown else frozenset(depths) | set(seeds)

    spread: Counter[int] = Counter()
    pairs = []
    for sec, mine, _theirs in differing:
        own = closure_from(mine | {sec.theory})
        shared = vis.closure(sec.theory)
        if own is None or shared is None:
            spread[-1] += 1
            continue
        spread[len(own ^ shared)] += 1
        pairs.append((sec, own, shared))
    unknown_pairs = spread.pop(-1, 0)
    identical = spread.pop(0, 0)
    print(f"{identical:6}  of those still reach the same set of theories")
    print(f"{unknown_pairs:6}  have an unknown closure on one side "
          f"(never filtered either way)")
    print(f"{sum(spread.values()):6}  genuinely differ "
          f"(median symmetric difference "
          f"{sorted(spread.elements())[len(list(spread.elements())) // 2] if spread else 0})\n")

    # --- 3. would any attribution actually flip? --------------------------
    #
    # Tokenise each section once and intersect with the indexed names, the way
    # `_build_call_graph` does — testing 314,292 names against each section's
    # text with `in` is 2x10^8 substring scans and does not finish.
    import re
    from isabelle_query.parsing import ISA_WORD_CHAR
    sym_findall = re.compile(rf"{ISA_WORD_CHAR}+").findall
    word_findall = re.compile(r"[\w']+").findall
    indexed = set(vis.declared_in)

    flips = 0
    checked = 0
    per_sec: Counter[str] = Counter()
    for sec, own, shared in pairs:
        if own == shared:
            continue
        # `sees` short-circuits on names the section declares itself, so those
        # cannot flip and are skipped.
        declared_here = {e.name for e in sec.entries}
        toks: set[str] = set()
        for line in sec.live_source():
            toks.update(word_findall(line))
            if "\\<" in line:
                toks.update(sym_findall(line))
        for name in (toks & indexed) - declared_here:
            decl = vis.declared_in[name]
            if not decl:
                continue
            checked += 1
            if decl.isdisjoint(shared) != decl.isdisjoint(own):
                flips += 1
                per_sec[sec.theory] += 1
    print(f"{checked:8}  (section, name) visibility decisions consulted the "
          f"closure")
    print(f"{flips:8}  would flip under the section's own imports")
    for n, c in per_sec.most_common(8):
        print(f"    {n:32} {c}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
