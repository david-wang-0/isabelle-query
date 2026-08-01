#!/usr/bin/env python3
r"""Validation-delta harness for adopting the runtime-dumped base namespace.

Adopting the dump changes the router's reject-set (`graph._NON_CITATION`), which
is a module-load-time constant — so the faithful way to measure the effect is to
regenerate the committed `_isabelle_namespace.py`, run the real suite / corpus
count against it, then `git checkout` to restore.  This script provides the two
process-level modes that swap straddles:

    --emit PATH        dump the base session table (methods/attributes, base-
                       folded) and render it into PATH in the committed format,
                       keeping the current KEYWORDS (declarative, unchanged).
    --count DIR...     parse each corpus dir and tally citation edges under the
                       *currently loaded* namespace, printing the active table
                       sizes so before/after runs are self-labelling.

Orchestrate: count (old) -> emit -> pytest + count (new) -> git checkout.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dump_isabelle_tables import _base, dump  # noqa: E402


def emit(out_path: str, session: str = "HOL") -> None:
    import extract_isabelle_namespace as ext          # sibling script
    from isabelle_query._isabelle_namespace import KEYWORDS
    methods, attribs, theory, _proc = dump(session)
    if not methods:
        raise SystemExit("dump produced no methods — is the heap built?")
    methods = sorted(_base(methods))
    attribs = sorted(_base(attribs))
    content = ext._HEADER.format(
        version=f"runtime dump (ML_process -l {session})",
        src=f"loaded theory {theory}", date="VALIDATION-PROTOTYPE",
        nmeth=len(methods), nattr=len(attribs), nkw=len(KEYWORDS),
        methods=ext._fmt_set(methods), attributes=ext._fmt_set(attribs),
        keywords=ext._fmt_set(KEYWORDS))
    Path(out_path).write_text(content, encoding="utf-8")
    print(f"emitted {out_path}: {len(methods)} methods, {len(attribs)} attributes")


def _apply_runtime(runtime: bool, session: str) -> None:
    """Optionally rebind `graph`'s tables to the runtime-dumped table before a
    count/names pass — the in-process equivalent of the emit→checkout straddle,
    so committed-vs-runtime is a single `--runtime` flag flip rather than a file
    swap.  `runtime` augments base with `session` when it is not the base."""
    if not runtime:
        return
    from isabelle_query import _namespace_resolve as nr, graph
    r = (nr.resolve_namespace(session) if session == "HOL"
         else nr.resolve_augmented(session, base="HOL"))
    from isabelle_query._isabelle_namespace import KEYWORDS
    graph.configure_namespace(r["methods"], r["attributes"], KEYWORDS)


def count(dirs: list[str], *, runtime: bool = False,
          session: str = "HOL") -> None:
    # Build the *position-blind* call graph — the path that consults
    # `_NON_CITATION` (via `_is_citation_name`), so it is what actually moves with
    # the table.  An entry NAMED after a method/attribute is dropped from the
    # citable set, and edges form only to citable names; report both.
    from isabelle_query import graph, parsing
    _apply_runtime(runtime, session)
    sections: list = []
    for d in dirs:
        try:
            parsing._sections_from_dir(Path(d), set(), sections)
        except Exception as e:                          # noqa: BLE001
            print(f"  ! {d}: {e}", file=sys.stderr)
    cg = graph._build_call_graph(sections)
    edges = sum(len(v) for v in cg.callees.values())
    nm = len(graph._PROOF_METHODS)          # the *active* table, not committed
    na = len(graph._ATTRIBUTES)
    label = f"runtime({session})" if runtime else "committed"
    print(f"namespace[{label}]: methods={nm} attributes={na} | "
          f"sections={len(sections)} citable_names={len(cg.all_names)} "
          f"edges={edges}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--emit", metavar="PATH")
    g.add_argument("--count", nargs="+", metavar="DIR")
    g.add_argument("--names", nargs="+", metavar="DIR",
                   help="print the sorted citable name set (for superset diffs)")
    ap.add_argument("--session", default="HOL")
    ap.add_argument("--runtime", action="store_true",
                    help="build under the runtime-dumped table (base HOL, or "
                         "base augmented with --session) instead of the committed "
                         "static table — the committed-vs-runtime straddle")
    ns = ap.parse_args(argv)
    if ns.emit:
        emit(ns.emit, ns.session)
    elif ns.names:
        from isabelle_query import graph, parsing
        _apply_runtime(ns.runtime, ns.session)
        sections: list = []
        for d in ns.names:
            try:
                parsing._sections_from_dir(Path(d), set(), sections)
            except Exception:                           # noqa: BLE001
                pass
        for n in sorted(graph._build_call_graph(sections).all_names):
            print(n)
    else:
        count(ns.count, runtime=ns.runtime, session=ns.session)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
