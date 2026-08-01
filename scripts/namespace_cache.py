#!/usr/bin/env python3
r"""Offline timing demo for the version-keyed namespace cache.

The cache + resolver now live in the package (``isabelle_query._namespace_resolve``)
since the runtime resolves the table at CLI dispatch; this script is the thin
front-end that demonstrates the cold-vs-warm timing and the two-tier augmentation.
Re-exports the resolver names so ``scripts/namespace_delta.py`` and any ad-hoc
probe keep importing them from here.

    python3 scripts/namespace_cache.py [--session HOL]   # timing demo
"""
from __future__ import annotations

import argparse
import sys
import time

from isabelle_query._namespace_resolve import (  # noqa: F401  (re-exported)
    _base,
    _cache_path,
    _committed,
    _heap_file,
    _isabelle_bin,
    _version_id,
    dump,
    isabelle_fingerprint,
    load_cache,
    resolve_augmented,
    resolve_namespace,
    save_cache,
)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--session", default="HOL")
    ns = ap.parse_args(argv)

    fp = isabelle_fingerprint(ns.session)
    print(f"fingerprint({ns.session!r}) = {fp or '(no Isabelle)'}")

    # Cold: drop any cache so the first resolve must hit Isabelle.
    try:
        _cache_path(ns.session).unlink()
    except OSError:
        pass
    t0 = time.perf_counter()
    cold = resolve_namespace(ns.session)
    t_cold = time.perf_counter() - t0

    # Warm: the cache we just wrote should now satisfy the same fingerprint.
    t0 = time.perf_counter()
    warm = resolve_namespace(ns.session)
    t_warm = time.perf_counter() - t0

    for label, r, dt in [("cold", cold, t_cold), ("warm", warm, t_warm)]:
        print(f"  {label}: {dt*1000:8.1f} ms  source={r['source']:9} "
              f"methods={len(r['methods'])} attributes={len(r['attributes'])}")
    speedup = t_cold / t_warm if t_warm else float("inf")
    print(f"  warm cache is ~{speedup:.0f}x faster; "
          f"steady state spawns no Isabelle")

    # Two-tier demo: base unioned with a richer session's active table.
    aug = "HOL-Analysis"
    if _heap_file(_version_id(_isabelle_bin()), aug) is not None:
        a = resolve_augmented(aug, base=ns.session)
        add_m = sorted(a["methods"] - cold["methods"])
        add_a = sorted(a["attributes"] - cold["attributes"])
        print(f"augment base {ns.session!r} with active table of {aug!r} "
              f"(source={a['source']}):")
        print(f"  methods {len(cold['methods'])} -> {len(a['methods'])} "
              f"(+{len(add_m)}): {', '.join(add_m[:6])}")
        print(f"  attributes {len(cold['attributes'])} -> {len(a['attributes'])} "
              f"(+{len(add_a)}): {', '.join(add_a[:6])}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
