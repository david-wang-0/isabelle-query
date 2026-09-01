#!/usr/bin/env python3
r"""Imports and ROOT entries spelled with a PATH resolve to nothing.
[import-leaf]

`_resolve_import` maps an `imports` token to an in-project theory by exact
name, else by its tail after the last `.`.  Neither rule can see a token
spelled as a path:

    imports "../WFair"      -- HOL/UNITY/Simple/Token.thy:10

`"." in imp` is true — of `..` — so the tail rule yields `/WFair`, which
names nothing, and the token is classified out-of-project.  The same hole
opens from the theory end: a ROOT that spells a theory `"Simple/Reach"`
gives the SECTION that spelling as its name, so a sibling importing `Reach`
bare finds no such key.  Isabelle takes the LAST SEGMENT on both sides
(`Thy_Header.import_name`), so both spellings denote the same theory.

For `deps` that is a cosmetic `[out-of-project]` line.  For `_Visibility` it
is a HOLE, and a hole PRUNES: the closure is a necessary condition on
visibility, so an edge it cannot see silently deletes every citation across
it.  Found by reviewing the Scala port's `dev/DIVERGENCES.md` D13, which
fixed it there (`Reach.import_target`'s leaf rule) and measured the cost as
`callers rev` 608 against 668 over the distribution.

    python scripts/probe_import_leaf.py [ROOT]

Reports, in order: the unresolvable tokens whose leaf DOES name a loaded
theory (the hole), the sections whose closure grows once they resolve, and
how many citation attributions the hole currently deletes.
"""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_layout import parse_thy_imports  # noqa: E402

from isabelle_query import cli, graph  # noqa: E402
from isabelle_query.parsing import ISA_WORD_CHAR  # noqa: E402


def leaf_of(imp: str) -> str:
    """What `Thy_Header.import_name` would take: the last path segment,
    then the tail after a session qualifier."""
    return imp.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[-1]


def main() -> int:
    root = (Path(sys.argv[1]).expanduser() if len(sys.argv) > 1
            else Path("/Applications/Isabelle2025-2.app/src/HOL"))
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    by_theory = graph._sections_by_theory(sections)
    print(f"{len(sections)} theories under {root}\n")

    # Every loaded theory, indexed by the leaf Isabelle would call it.
    by_leaf: dict[str, set[str]] = {}
    for name in by_theory:
        by_leaf.setdefault(leaf_of(name), set()).add(name)
    pathy = sorted(n for n in by_theory if "/" in n)
    print(f"{len(pathy):6}  loaded theories carry a ROOT path in their NAME"
          + (f"  e.g. {pathy[:3]}" if pathy else ""))

    # --- 1. the hole: tokens that resolve to None but name a real leaf ----
    holes: list[tuple[str, str, str]] = []   # (importer, token, target)
    external = 0
    for sec in sections:
        if not sec.path.is_file():
            continue
        for imp in parse_thy_imports(sec.path):
            if graph._resolve_import(imp, by_theory) is not None:
                continue
            hits = by_leaf.get(leaf_of(imp), set())
            if hits:
                holes.append((sec.theory, imp, sorted(hits)[0]))
            else:
                external += 1
    print(f"{external:6}  unresolvable tokens name nothing loaded (external, "
          f"correct)")
    print(f"{len(holes):6}  unresolvable tokens DO name a loaded theory by "
          f"their leaf\n")
    for importer, imp, target in holes[:8]:
        print(f"    {importer:28} imports {imp!r:24} -> {target}")
    if len(holes) > 8:
        print(f"    ... and {len(holes) - 8} more")
    if not holes:
        print("No hole on this corpus.")
        return 0

    # --- 2. whose closure grows? -----------------------------------------
    extra_edges: dict[str, set[str]] = {}
    for importer, imp, target in holes:
        extra_edges.setdefault(importer, set()).add(target)

    vis = graph._Visibility(sections, "closure")

    def patched_closure(theory: str) -> frozenset[str] | None:
        """The closure with the leaf rule applied at every hop."""
        unknown = False

        def children(name: str) -> list[str]:
            nonlocal unknown
            got = vis._read_imports(name)
            if got is None:
                unknown = True
                return []
            return list(got) + sorted(extra_edges.get(name, ()))

        depths = graph._bfs_depths(children, [theory], seed_depth=-1)
        return None if unknown else frozenset(depths) | {theory}

    grew: list[tuple[str, frozenset[str], frozenset[str]]] = []
    for sec in sections:
        now = vis.closure(sec.theory)
        then = patched_closure(sec.theory)
        if now is None or then is None or now == then:
            continue
        grew.append((sec.theory, now, then))
    print(f"\n{len(grew):6}  theories reach further once the leaf resolves")
    for name, now, then in sorted(grew, key=lambda r: -(len(r[2]) - len(r[1])))[:6]:
        print(f"    {name:28} {len(now):4} -> {len(then):4} theories")

    # --- 3. what does the hole delete? ------------------------------------
    #
    # Tokenise each section once and intersect with the indexed names, as
    # `_build_call_graph` does; testing every declared name against the text
    # does not finish on a corpus this size.
    sym_findall = re.compile(rf"{ISA_WORD_CHAR}+").findall
    word_findall = re.compile(r"[\w']+").findall
    indexed = set(vis.declared_in)
    by_name = {s.theory: s for s in sections}

    restored = 0
    checked = 0
    per_sec: Counter[str] = Counter()
    for name, now, then in grew:
        sec = by_name[name]
        declared_here = {e.name for e in sec.entries}
        toks: set[str] = set()
        for line in sec.live_source():
            toks.update(word_findall(line))
            if "\\<" in line:
                toks.update(sym_findall(line))
        for tok in (toks & indexed) - declared_here:
            decl = vis.declared_in[tok]
            if not decl:
                continue
            checked += 1
            if decl.isdisjoint(now) and not decl.isdisjoint(then):
                restored += 1
                per_sec[name] += 1
    print(f"\n{checked:8}  (section, name) decisions consulted a grown closure")
    print(f"{restored:8}  citations the hole currently DELETES")
    for n, c in per_sec.most_common(8):
        print(f"    {n:32} {c}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
