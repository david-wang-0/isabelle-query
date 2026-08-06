#!/usr/bin/env python3
r"""Feasibility probe: can `export_theory` serve as a ground-truth oracle?

THE QUESTION
    `query` recognises declarations by tokenising `.thy` source.  Isabelle knows
    the real answer.  If that answer can be read cheaply, the corpus diff that
    currently validates parser changes ("did the entry set move?") gains an
    absolute reference ("is the entry set RIGHT?").

    Two things have to hold for that to be worth building:
      1. the data must be reachable WITHOUT running a build, and
      2. it must carry source POSITIONS, not just names -- a name list alone
         cannot check spans, and spans are where the parser actually errs.

WHAT THIS DOES
    Reads the session database directly -- `<heaps>/<platform>/log/<SESSION>.db`,
    table `isabelle_exports` -- opened **read-only** through a
    `file:...?mode=ro` URI.  This is the whole point: `isabelle export` is NOT
    read-only (it builds the session first if anything is stale, which on a
    mid-size AFP entry is minutes), whereas the sqlite file cannot build
    anything.  A session that has never been built simply has no row here, and
    the probe says so and stops.

    Nothing about the installation is hardcoded.  Heap directories come from
    `_namespace_resolve._heaps_dirs` (user AND distribution, per issue #4), and
    the `$AFP/...` paths the exports carry are resolved through Isabelle's own
    component registry -- `etc/components`, then each component's
    `etc/settings`.  Run with no argument to list the sessions that actually
    have a database here.

    Export bodies are Zstd-compressed YXML.  This decodes them far enough to
    count entities, resolve their offsets to lines, and diff the result against
    `query`'s own entry set.

    DO NOT reach for `isabelle export` to do this instead.  Despite reading like
    a query, it builds the session first if anything is stale — on a mid-size
    AFP entry that is minutes of CPU, and it is how this probe came to exist in
    read-the-database form.

WHAT IT FOUND  (Universal_Turing_Machine, 34 theories, an already-built session)
    Both preconditions hold.  Every entity carries `offset` / `end_offset` /
    `file`, and many carry `label=command.definition` — the declaring command.
    Offsets resolve exactly: on `DitherTM` the span lands on the declared name
    for 9/10 entities (the tenth, `tm_dither_def`, points at the `tm_dither`
    the definition names, which is Isabelle being right).

    Against `query` (re-measured at v0.6.3): of 2,262 named entries, **2,258
    (99.8%) match by name**, and 2,148 of those agree on the line too.  Three
    residual classes, none of them a parser bug:

    * 98 ANONYMOUS entries (`lemma "P" by simp`) have no entity at all — they
      are invisible to this oracle by construction, and no filtering fixes it.
    * 110 line disagreements are `query` = command line, export = *name* line,
      from the split style `definition\n  foo :: ...`.  Two defensible
      conventions, so a comparison has to reconcile rather than diff them.
    * 4 "only in query".  This was 41 before locale targets landed: Isabelle
      qualifies an entity by its enclosing target (`K0` in `locale hpk` exports
      as `hpk.K0`), and `query` now knows the target, so the comparison tries
      both spellings.

    The 12,083 "only in export" are Isabelle's derived facts (`foo.simps`,
    `foo.induct`, `K0.cong`) with no source declaration of their own — expected,
    and the reason a usable oracle checks PRECISION, not recall: every `query`
    entry should be an export entity, and the converse must not be required.
    They cannot be filtered by position either, since a derived fact's position
    points at the command that generated it.

Usage:
    probe_export_oracle.py                       # list sessions with a database
    probe_export_oracle.py SESSION [--theory N] [--verify] [--compare] [--all]
                                    [--root VAR=DIR]
"""
from __future__ import annotations

import argparse
import os
import re
import sqlite3
import sys
from pathlib import Path

# Isabelle compresses export bodies with **Zstandard** (magic `28 B5 2F FD`),
# not XZ.  Python 3.14 carries zstd in the stdlib (PEP 784), so reading these
# needs no third-party package — the tool stays dependency-free.
from compression import zstd

# YXML delimiters (Pure/PIDE/yxml.ML): X marks a markup boundary, Y separates
# the element name from its attributes.
X, Y = "\x05", "\x06"


def _heap_log_dirs() -> list[Path]:
    """Every `<heaps>/<platform>/log` directory a session database can live in.

    Delegates to `_namespace_resolve._heaps_dirs`, which is the tool's own
    answer to "where does Isabelle keep heaps" and searches BOTH
    `$ISABELLE_HEAPS` and `$ISABELLE_HEAPS_SYSTEM` (issue #4).  This used to
    glob `$ISABELLE_HOME_USER/heaps/*/log` with `Isabelle2025-2` hardcoded as
    the fallback, which broke on any other release and could not see a session
    shipped prebuilt with the distribution.
    """
    from isabelle_query import _namespace_resolve as nr
    isabelle = nr._isabelle_bin()
    out: list[Path] = []
    for d in nr._heaps_dirs(nr._version_id(isabelle), isabelle):
        out.extend(p for p in Path(d).glob("*/log") if p.is_dir())
    return sorted(set(out))


def _available_sessions() -> list[tuple[str, Path]]:
    """`(session, database)` for every session that has been built, so a run
    with no argument can say what there IS instead of failing on a guess."""
    out: dict[str, Path] = {}
    for d in _heap_log_dirs():
        for db in sorted(d.glob("*.db")):
            out.setdefault(db.stem, db)
    return sorted(out.items())


def _find_db(session: str) -> Path | None:
    for d in _heap_log_dirs():
        cand = d / f"{session}.db"
        if cand.is_file():
            return cand
    return None


# --- locating the sources an export refers to ------------------------------
#
# Isabelle records an entity's source as its OWN path variable — `$AFP/Foo/
# Bar.thy`, not an absolute path — because a component's location is a property
# of the installation, not of the export.  Resolving that back to a file is
# therefore a question about THIS installation, and Isabelle already stores the
# answer: `etc/components` lists the registered component directories, and each
# component's `etc/settings` defines its variables (the AFP's says
# `AFP_BASE="$COMPONENT"` then `AFP="$AFP_BASE/thys"`).
#
# Reading those two files resolves `$AFP` wherever the AFP happens to be
# checked out, needs no spawn, and keeps working across releases — which a
# hardcoded `~/repos/afp/thys` did not.

_SETTING_RE = re.compile(r'^\s*([A-Z][A-Z0-9_]*)=(.*)$')


def _component_dirs() -> list[Path]:
    """Component directories registered with this Isabelle, user list first."""
    from isabelle_query import _namespace_resolve as nr
    isabelle = nr._isabelle_bin()
    home_user = Path(os.environ.get("ISABELLE_HOME_USER") or
                     Path.home() / ".isabelle" / nr._version_id(isabelle))
    lists = [home_user / "etc" / "components",
             nr._isabelle_home(isabelle) / "etc" / "components"]
    out: list[Path] = []
    for lst in lists:
        try:
            lines = lst.read_text(encoding="utf-8").splitlines()
        except OSError:
            continue
        for line in lines:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = Path(line).expanduser()
            if p.is_dir():
                out.append(p)
    return out


def _component_vars() -> dict[str, Path]:
    """Isabelle path variables defined by registered components.

    A deliberately small reader of `etc/settings`: plain `VAR=value` lines with
    `$COMPONENT` and already-seen variables expanded.  Anything conditional,
    computed, or exported through shell logic is skipped rather than guessed
    at — this only has to find component roots like `$AFP`, and a wrong answer
    would be worse than no answer.
    """
    out: dict[str, Path] = {}
    for comp in _component_dirs():
        local = {"COMPONENT": str(comp)}
        try:
            lines = (comp / "etc" / "settings").read_text(
                encoding="utf-8").splitlines()
        except OSError:
            continue
        for line in lines:
            m = _SETTING_RE.match(line)
            if not m:
                continue
            name, raw = m.group(1), m.group(2).strip()
            if raw[:1] in ("'", '"') and raw[-1:] == raw[:1]:
                raw = raw[1:-1]
            if "$(" in raw or "`" in raw:
                continue                      # command substitution: skip
            def sub(mm, local=local):
                return local.get(mm.group(1) or mm.group(2), "\0")
            value = re.sub(r"\$\{([A-Z0-9_]+)\}|\$([A-Z0-9_]+)", sub, raw)
            if "\0" in value or not value:
                continue                      # unresolved reference: skip
            local[name] = value
            p = Path(value)
            if p.is_dir():
                out.setdefault(name, p)
    return out


def _resolve_source(src: str, overrides: dict[str, Path]) -> Path | None:
    """Turn an export's `file` attribute into a real path, or None."""
    m = re.match(r"\$\{?([A-Z][A-Z0-9_]*)\}?/(.*)$", src)
    if not m:
        p = Path(src).expanduser()
        return p if p.is_file() else None
    var, rest = m.group(1), m.group(2)
    root = overrides.get(var)
    if root is None:
        env = os.environ.get(var)
        root = Path(env) if env else _component_vars().get(var)
    if root is None:
        return None
    p = root / rest
    return p if p.is_file() else None


def _read_export(db: Path, theory: str, name: str) -> str | None:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        row = con.execute(
            "SELECT compressed, body FROM isabelle_exports "
            "WHERE theory_name = ? AND name = ?", (theory, name)).fetchone()
    finally:
        con.close()
    if row is None:
        return None
    compressed, body = row
    if compressed:
        body = zstd.decompress(body)
    return body.decode("utf-8", errors="replace")


def _theories(db: Path, session: str) -> list[str]:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        return [r[0] for r in con.execute(
            "SELECT DISTINCT theory_name FROM isabelle_exports "
            "WHERE theory_name LIKE ? ORDER BY 1", (f"{session}.%",))]
    finally:
        con.close()


# An entity's position is carried as attributes on its markup: `offset`,
# `end_offset`, `file`, `id`.  Their presence is exactly question 2.
_POS_RE = re.compile(r"(offset|end_offset|line|file|name)=")

# One entity record, as YXML attribute runs.  The leading `X` count differs
# between the first record and the rest, so match a single one.
_ENTITY_RE = re.compile(
    X + Y + r"entity" + Y + r"(.*?)" + X, re.S)


def _attrs(blob: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for field in blob.split(Y):
        k, _, v = field.partition("=")
        if _:
            out[k] = v
    return out


# Isabelle counts positions in SYMBOLS, not characters: `\<alpha>` is written as
# eight ASCII bytes in the `.thy` but is one symbol to Isabelle.  Converting an
# export offset to a `theory:line` locus therefore needs a symbol-aware walk,
# which is the one real piece of work an oracle comparison has to get right.
_SYM_RE = re.compile(r"\\<\^?[A-Za-z][A-Za-z0-9_']*>")


def _symbol_maps(text: str) -> tuple[list[int], list[int]]:
    """`(line_starts, sym_to_char)` in 1-based symbol coordinates."""
    line_starts = [1]
    sym_to_char = [0, 0]          # index 1 == first symbol
    i, off, n = 0, 1, len(text)
    while i < n:
        sym_to_char[off] = i
        m = _SYM_RE.match(text, i)
        if m:
            i = m.end()
        else:
            if text[i] == "\n":
                line_starts.append(off + 1)
            i += 1
        off += 1
        sym_to_char.append(i)
    return line_starts, sym_to_char


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", nargs="?",
                    help="a session with a built heap; omit to list them")
    ap.add_argument("--theory", default=None)
    ap.add_argument("--root", action="append", metavar="VAR=DIR", default=[],
                    help="override an Isabelle path variable an export refers "
                         "to, e.g. --root AFP=~/repos/afp/thys.  Only needed "
                         "when the component is not registered with this "
                         "Isabelle (it usually is, via etc/components)")
    ap.add_argument("--dump", action="store_true",
                    help="print a decoded sample of the export body")
    ap.add_argument("--verify", action="store_true",
                    help="resolve offsets to lines and check them against source")
    ap.add_argument("--compare", action="store_true",
                    help="diff the export's entity set against query's entries")
    ap.add_argument("--all", action="store_true",
                    help="compare every theory in the session, not just one")
    ns = ap.parse_args()

    roots: dict[str, Path] = {}
    for spec in ns.root:
        var, _, val = spec.partition("=")
        if not val:
            ap.error(f"--root wants VAR=DIR, got {spec!r}")
        roots[var.strip()] = Path(val).expanduser()

    available = _available_sessions()
    if not ns.session:
        # No default session: the old one named a session that happened to be
        # built on the machine this was written on.  Say what is actually here.
        print(f"{len(available)} session(s) with a readable export database:\n")
        for name, db in available:
            print(f"  {name:<40} {db.stat().st_size / 1e6:>8.1f} MB")
        if not available:
            print("  (none — no session in these directories has been built)")
            for d in _heap_log_dirs():
                print(f"    looked in {d}")
        print("\nPass one as the argument.  Never run `isabelle export` to make "
              "one:\nit BUILDS the session first.")
        sys.exit(0 if available else 1)

    db = _find_db(ns.session)
    if db is None:
        print(f"no session database for {ns.session!r} — "
              f"it has never been built.  Looked in:")
        for d in _heap_log_dirs():
            print(f"  {d}")
        if available:
            print("built sessions here: "
                  + ", ".join(n for n, _ in available[:8])
                  + (", ..." if len(available) > 8 else ""))
        sys.exit(1)
    print(f"database: {db}  ({db.stat().st_size / 1e6:.1f} MB, read-only)")

    thys = _theories(db, ns.session)
    print(f"theories with exports: {len(thys)}")
    if not thys:
        sys.exit(1)
    theory = ns.theory or thys[0]
    if "." not in theory:
        theory = f"{ns.session}.{theory}"
    print(f"sampling: {theory}\n")

    for kind in ("theory/thms", "theory/consts", "theory/locales",
                 "theory/types", "theory/classes"):
        body = _read_export(db, theory, kind)
        if body is None:
            print(f"  {kind:<18} (absent)")
            continue
        attrs = set(_POS_RE.findall(body))
        print(f"  {kind:<18} {len(body):>9,} chars   "
              f"position attrs: {sorted(attrs) or 'NONE'}")

    if ns.verify:
        _verify(db, theory, roots)

    if ns.compare:
        targets = thys if ns.all else [theory]
        tot = dict(named=0, matched=0, only_export=0, only_query=0,
                   anon=0, line_ok=0, thys=0)
        samples: dict[str, list[str]] = {"only_query": [], "line_off": []}
        for t in targets:
            r = _compare(db, t, samples, roots)
            if r is None:
                continue
            tot["thys"] += 1
            for k, v in r.items():
                tot[k] += v
        print(f"\n=== {tot['thys']} theories ===")
        print(f"query named entries      {tot['named']:>7,}")
        print(f"query ANONYMOUS entries  {tot['anon']:>7,}"
              f"   (no entity exists — invisible to the oracle)")
        print(f"  matched by name        {tot['matched']:>7,}")
        print(f"    of those, line agrees{tot['line_ok']:>7,}")
        print(f"  only in the export     {tot['only_export']:>7,}")
        print(f"  only in query          {tot['only_query']:>7,}")
        for label, key in (("names query reports and Isabelle does not",
                            "only_query"),
                           ("line disagreements", "line_off")):
            if samples[key]:
                print(f"\n  {label}:")
                for s in samples[key][:12]:
                    print(f"    {s}")

    if ns.dump:
        body = _read_export(db, theory, "theory/thms") or ""
        print("\n--- theory/thms, first 1500 chars (X/Y shown as <X>/<Y>) ---")
        print(body[:1500].replace(X, "<X>").replace(Y, "<Y>"))


# `theory/thms` holds single theorems; a fact bound to SEVERAL propositions
# (`lemma ariths: "P" "Q"`) is a thm *list* and is filed under
# `theory/other/fact` instead.  Omitting it makes every multi-statement lemma
# look like a query invention.
_KINDS = ("theory/thms", "theory/other/fact", "theory/consts", "theory/types",
          "theory/classes", "theory/locales")


def _entities(db: Path, theory: str) -> tuple[dict[str, int], str]:
    """`({xname: symbol_offset}, source_file)` across every entity kind."""
    out: dict[str, int] = {}
    src = ""
    for kind in _KINDS:
        body = _read_export(db, theory, kind)
        if not body:
            continue
        for m in _ENTITY_RE.finditer(body):
            a = _attrs(m.group(1))
            xname, off = a.get("xname"), a.get("offset")
            if not xname or not off:
                continue
            src = src or a.get("file", "")
            out.setdefault(xname, int(off))
    return out, src


def _compare(db: Path, theory: str,
             samples: dict[str, list[str]] | None = None,
             roots: dict[str, Path] | None = None) -> dict[str, int] | None:
    """Diff the export's entity names against `query`'s entries for one theory."""
    from bisect import bisect_right

    from isabelle_query import cli

    ents, src = _entities(db, theory)
    if not src:
        return None
    path = _resolve_source(src, roots or {})
    if path is None:
        return None
    try:
        sec = cli._parse_one(path.stem, path)
    except Exception:  # noqa: BLE001
        return None

    text = path.read_text(encoding="utf-8", errors="replace")
    line_starts, _ = _symbol_maps(text)
    export_lines = {n: bisect_right(line_starts, o) for n, o in ents.items()}

    # Isabelle qualifies an entity by its enclosing target: `K0` declared in
    # `locale hpk` exports as `hpk.K0`.  Since [locale-naming] `query` knows the
    # target too, so a name matches under either spelling.
    ours: dict[str, int] = {}
    for e in sec.entries:
        if not e.name or e.name == "?":
            continue
        key = e.name
        if e.target and f"{e.target}.{e.name}" in ents:
            key = f"{e.target}.{e.name}"
        ours[key] = e.thy_line
    anon = sum(1 for e in sec.entries if not e.name or e.name == "?")
    matched = set(ours) & set(export_lines)
    line_ok = sum(1 for n in matched if ours[n] == export_lines[n])
    if samples is not None:
        thy = path.stem
        for n in sorted(set(ours) - set(export_lines))[:4]:
            samples["only_query"].append(f"{thy}:{ours[n]}  {n}")
        for n in sorted(n for n in matched if ours[n] != export_lines[n])[:4]:
            samples["line_off"].append(
                f"{thy}  {n}: query says :{ours[n]}, "
                f"export says :{export_lines[n]}")
    return dict(named=len(ours), anon=anon, matched=len(matched),
                line_ok=line_ok,
                only_export=len(set(export_lines) - set(ours)),
                only_query=len(set(ours) - set(export_lines)))


def _verify(db: Path, theory: str,
            roots: dict[str, Path] | None = None) -> None:
    """Resolve each entity's offset to a `theory:line` locus and show the source
    text it lands on — the check that the oracle is actually usable."""
    from bisect import bisect_right

    body = _read_export(db, theory, "theory/thms")
    if not body:
        print("\nno theory/thms export to verify")
        return
    ents = [_attrs(m.group(1)) for m in _ENTITY_RE.finditer(body)]
    if not ents:
        print("\nno entities parsed")
        return

    src = ents[0].get("file", "")
    path = _resolve_source(src, roots or {})
    if path is None:
        print(f"\nsource not found for {src!r} — "
              f"pass --root VAR=DIR (e.g. --root AFP=~/repos/afp/thys) if that "
              f"component is not registered with this Isabelle")
        return
    text = path.read_text(encoding="utf-8", errors="replace")
    line_starts, sym_to_char = _symbol_maps(text)

    print(f"\n--- {len(ents)} entities resolved against {path.name} ---")
    print(f"{'locus':<22} {'xname':<28} source text at the offset")
    ok = 0
    for e in ents[:12]:
        try:
            off, end = int(e["offset"]), int(e["end_offset"])
        except (KeyError, ValueError):
            continue
        line = bisect_right(line_starts, off)
        got = text[sym_to_char[off]:sym_to_char[end]]
        xname = e.get("xname", "?")
        if got == xname:
            ok += 1
        flag = " " if got == xname else "  <-- MISMATCH"
        print(f"{theory.split('.')[-1]}:{line:<16} {xname:<28} {got!r}{flag}")
    total = sum(1 for e in ents if "offset" in e and "end_offset" in e)
    hits = 0
    for e in ents:
        try:
            off, end = int(e["offset"]), int(e["end_offset"])
        except (KeyError, ValueError):
            continue
        if text[sym_to_char[off]:sym_to_char[end]] == e.get("xname"):
            hits += 1
    print(f"\noffset lands exactly on the declared name: "
          f"{hits}/{total} ({100.0 * hits / max(total, 1):.1f}%)")


if __name__ == "__main__":
    main()
