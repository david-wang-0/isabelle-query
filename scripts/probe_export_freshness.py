#!/usr/bin/env python3
r"""How ossified is the export snapshot, and can we tell?

The objection to using a built heap as a reference is that it is a snapshot:
it records what Isabelle thought when the session was last built, and the
`.thy` files have moved on.  Comparing today's parse against yesterday's truth
manufactures disagreements that are nobody's bug.

The session database answers this exactly.  Alongside `isabelle_exports` it
carries `isabelle_sources`, one row per source file with

    digest   SHA-1 of the file as built  (plain sha1 of the bytes)
    body     the file's TEXT, Zstd-compressed

So staleness is not estimated, it is *decided*: sha1 the file on disk and
compare.  And because the snapshot carries its own inputs, a (source, answer)
pair extracted from it stays internally consistent no matter what the tree
does afterwards -- which is what a test fixture is.

This reports, per session, how much of the snapshot still matches the tree.

Usage:  probe_export_freshness.py [SESSION...]     (default: every built one)
"""
from __future__ import annotations

import hashlib
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from compression import zstd  # noqa: E402

from probe_export_oracle import (  # noqa: E402
    _available_sessions, _find_db, _resolve_source,
)


def source_rows(db: Path):
    """`(name, digest, body)` for every source file the build recorded."""
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        rows = list(con.execute(
            "SELECT name, digest, compressed, body FROM isabelle_sources"))
    finally:
        con.close()
    for name, digest, compressed, body in rows:
        yield name, digest, (zstd.decompress(body) if compressed else body)


def freshness(db: Path):
    """`(fresh, stale, missing)` counts for one session's `.thy` sources."""
    fresh = stale = missing = 0
    stale_names: list[str] = []
    for name, digest, body in source_rows(db):
        if not name.endswith(".thy"):
            continue
        path = _resolve_source(name, {})
        if path is None:
            missing += 1
            continue
        if hashlib.sha1(path.read_bytes()).hexdigest() == digest:
            fresh += 1
        else:
            stale += 1
            if len(stale_names) < 5:
                stale_names.append(Path(name).name)
    return fresh, stale, missing, stale_names


def main() -> None:
    sessions = sys.argv[1:] or [s for s, _ in _available_sessions()]
    tf = ts = tm = 0
    print(f"{'session':<38} {'fresh':>7} {'STALE':>7} {'gone':>6}")
    for s in sessions:
        db = _find_db(s)
        if db is None:
            continue
        f, st, m, names = freshness(db)
        if f + st + m == 0:
            continue
        tf, ts, tm = tf + f, ts + st, tm + m
        flag = "  <- " + ", ".join(names) if names else ""
        print(f"{s:<38} {f:>7,} {st:>7,} {m:>6,}{flag}")
    total = tf + ts + tm
    print(f"\n{'TOTAL':<38} {tf:>7,} {ts:>7,} {tm:>6,}")
    if total:
        print(f"\n{100.0 * tf / total:.1f}% of recorded .thy sources are "
              f"byte-identical to the tree today.")
    print("\nA digest-gated comparison never reports a stale theory as a "
          "parser\ndisagreement: it skips it, with a reason.")


if __name__ == "__main__":
    main()
