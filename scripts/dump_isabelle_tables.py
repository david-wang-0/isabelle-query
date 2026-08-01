#!/usr/bin/env python3
r"""Offline diagnostic: enumerate Isabelle's method / attribute tables from a
*running* logic image, and diff them against the committed static-scan namespace.

The dump logic itself now lives in the package (``isabelle_query._namespace_resolve``)
because the runtime consumes it too; this script is the thin offline front-end
that reports the diff (what the runtime table adds over / drops from the committed
static scan).  ``extract_isabelle_namespace.py`` remains the source-scan
counterpart this exists to check.

    python3 scripts/dump_isabelle_tables.py [--session HOL] [--theory NAME]
"""
from __future__ import annotations

import argparse
import sys

# The resolver is the single home of dump/_base/_isabelle_bin; re-export the two
# this front-end and scripts/namespace_delta.py use.
from isabelle_query._namespace_resolve import _base, _isabelle_bin, dump  # noqa: F401


def _sample(names: set[str], k: int = 12) -> str:
    xs = sorted(names)
    return ", ".join(xs[:k]) + (f", ... (+{len(xs) - k} more)" if len(xs) > k
                                else "")


def _report(kind: str, runtime: set[str], committed: frozenset) -> None:
    base = _base(runtime)
    added = base - committed           # in the live table, missed by the scan
    removed = committed - base         # in the scan, absent from this image
    print(f"\n{kind}: runtime {len(runtime)} raw / {len(base)} base "
          f"vs committed {len(committed)}")
    print(f"  only in runtime  (+{len(added)}): {_sample(added)}")
    print(f"  only in committed (-{len(removed)}): {_sample(removed)}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--session", default="HOL",
                    help="logic session heap to load (default HOL)")
    ap.add_argument("--theory", default=None,
                    help="namespace scope theory (default: heap's top theory)")
    ns = ap.parse_args(argv)

    methods, attribs, scope, proc = dump(ns.session, ns.theory)
    if not methods and not attribs:
        sys.stderr.write("no tables dumped — ML process output follows:\n")
        sys.stderr.write((proc.stdout + proc.stderr) if proc else
                         "(dump timed out or Isabelle not found)\n")
        return 1

    from isabelle_query._isabelle_namespace import ATTRIBUTES, PROOF_METHODS
    print(f"session {ns.session!r}, scope theory {scope!r}")
    print(f"induction in method table: "
          f"{'YES' if 'induction' in methods else 'NO'} "
          f"(no special case — it is just another table entry)")
    _report("methods", methods, PROOF_METHODS)
    _report("attributes", attribs, ATTRIBUTES)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
