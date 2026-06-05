#!/usr/bin/env python3
r"""Extract Isabelle's method / attribute / keyword namespaces from the
Isabelle distribution source, for isabelle_query's token router.

WHY THIS EXISTS
    query approximates Isabelle's parser by tokenising `.thy` source.  To tell
    a *fact citation* (which becomes a call-graph edge) apart from a *proof
    method* (`by simp` — not a citation, but useful as method-usage data), an
    *attribute* (`[OF g]`, `simp del:`) and a structural *keyword* (`proof`,
    `qed`, `and`), it needs Isabelle's own namespaces.  Those evolve release to
    release, so rather than hand-maintain a banlist we read them from the
    source and regenerate a data module.  Re-run this against a newer Isabelle
    to refresh the data — that is the whole point of scripting it.

SOURCES READ (read-only)
    methods     `method_setup NAME ...`                            (Isar, *.thy)
                `Method.setup`/`Method.local_setup \<^binding>\<open>NAME\<close>`  (*.ML)
    attributes  `attribute_setup NAME ...`                         (Isar, *.thy)
                `Attrib.setup \<^binding>\<open>NAME\<close>`                   (*.ML)
    keywords    the `Pure/Pure.thy` `keywords ... begin` block, plus the
                `bootstrap_keywords` of `Pure/Thy/thy_header.ML` (which is where
                `and`, `begin`, `imports`, the document headings, ... live).

    The `Method.setup name` / `Attrib.setup (Binding ...)` forms bind a
    *variable*, not a literal name (generic helpers), so they carry no
    extractable name and are correctly skipped.  A few argument-modifier tokens
    (`add`, `del`, `OF`, `THEN`) have no declaration site at all — they are
    parsed inline by individual methods — and so are intentionally NOT here;
    the router keeps that short, auditable tier-2 list itself.

USAGE
    python3 scripts/extract_isabelle_namespace.py \
        [--src /path/to/Isabelle/src] [--version LABEL] [--out PATH] \
        [--date YYYY-MM-DD]

    Defaults: --src from $ISABELLE_SRC, else ~/projects/ndtht/isabelle-src;
              --out src/isabelle_query/_isabelle_namespace.py.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

_NAME = r'"?([A-Za-z][\w\']*)"?'
# Per namespace: (Isar `*_setup NAME` in .thy, ML `*.setup \<^binding>\<open>NAME\<close>`).
_DECL_RES = {
    "method": (
        re.compile(r"^\s*method_setup\s+" + _NAME),
        re.compile(r"Method\.(?:local_)?setup\s+"
                   r"\\<\^binding>\\<open>([A-Za-z][\w']*)\\<close>")),
    "attribute": (
        re.compile(r"^\s*attribute_setup\s+" + _NAME),
        re.compile(r"Attrib\.setup\s+"
                   r"\\<\^binding>\\<open>([A-Za-z][\w']*)\\<close>")),
}


def scan_decls(src: Path) -> dict[str, dict[str, str]]:
    """One pass over the tree -> {kind: {name: first_source_path}}."""
    out: dict[str, dict[str, str]] = {k: {} for k in _DECL_RES}
    for root, _dirs, files in os.walk(src):
        for fn in files:
            thy, ml = fn.endswith(".thy"), fn.endswith(".ML")
            if not (thy or ml):
                continue
            path = Path(root) / fn
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            rel = str(path.relative_to(src))
            for kind, (thy_re, ml_re) in _DECL_RES.items():
                if thy:
                    for line in text.splitlines():
                        for m in thy_re.finditer(line):
                            out[kind].setdefault(m.group(1), rel)
                else:
                    for m in ml_re.finditer(text):
                        out[kind].setdefault(m.group(1), rel)
    return out


def _pure_block_keywords(src: Path) -> dict[str, str]:
    """{keyword: kind} from Pure/Pure.thy's `keywords ... begin` block.

    Only alphabetic names are kept (symbolic keywords such as `!!`, `==`, `+`
    are never tokenised as identifiers, so they cannot collide with a fact
    name).  Kind is the group's `:: <kind>`, or ``"minor"`` for the leading
    un-kinded group."""
    pure = src / "Pure" / "Pure.thy"
    m = re.search(r"(?ms)^keywords\b(.*?)^begin\b",
                  pure.read_text(encoding="utf-8", errors="replace"))
    if not m:
        raise SystemExit(f"could not find the keywords block in {pure}")
    out: dict[str, str] = {}
    # Groups are separated by a line-leading `and ` (the leading group has
    # none); splitting on that avoids tripping over a quoted name like "and".
    for piece in re.split(r"\n\s+and\s", m.group(1)):
        if "::" in piece:
            names_part, kind_part = piece.split("::", 1)
            km = re.match(r"\s*([A-Za-z_]+)", kind_part)
            kind = km.group(1) if km else ""
        else:
            names_part, kind = piece, "minor"
        for nm in re.findall(r'"([^"]+)"', names_part):
            if nm[:1].isalpha():
                out[nm] = kind
    return out


def _bootstrap_keywords(src: Path) -> dict[str, str]:
    """The alphabetic `bootstrap_keywords` of Pure/Thy/thy_header.ML — `and`,
    `begin`, `imports`, the document headings, etc.  Entries name either a
    quoted literal (`"and"`) or a `val xN = "..."` constant, which we resolve."""
    th = (src / "Pure" / "Thy" / "thy_header.ML").read_text(
        encoding="utf-8", errors="replace")
    consts = dict(re.findall(r'val\s+(\w+)\s*=\s*"([^"]+)"', th))
    m = re.search(r"bootstrap_keywords\b.*?add_keywords\s*\[(.*?)\]", th, re.S)
    out: dict[str, str] = {}
    if m:
        for tok in re.findall(r'\(\(\s*("?[\w]+"?)\s*,', m.group(1)):
            nm = tok.strip('"') if tok.startswith('"') else consts.get(tok, "")
            if nm[:1].isalpha():
                out[nm] = "bootstrap"
    return out


def extract_keywords(src: Path) -> dict[str, str]:
    kws = _pure_block_keywords(src)
    for nm, kind in _bootstrap_keywords(src).items():
        kws.setdefault(nm, kind)
    return kws


def infer_version(src: Path) -> str:
    m = re.search(r"Isabelle[0-9]{4}(?:-[0-9]+)?", str(src.resolve()))
    return m.group(0) if m else "unknown"


_HEADER = '''\
"""Isabelle method / attribute / keyword namespaces — GENERATED, do not edit.

Regenerate with::

    python3 scripts/extract_isabelle_namespace.py

Provenance:
    Isabelle:    {version}
    Source:      {src}
    Extracted:   {date}
    Methods:     {nmeth} (method_setup + Method.setup)
    Attributes:  {nattr} (attribute_setup + Attrib.setup)
    Keywords:    {nkw} (Pure.thy keyword table + thy_header bootstrap)

These power isabelle_query's token *router*: a proof-body token is a fact
citation (a call-graph edge) only if it is none of a proof method, an
attribute, a keyword, or a numeral.  Method occurrences are not discarded —
they feed the `methods` query.
"""

# proof methods (`by simp`, `apply (rule r)`) — never a fact citation, but a
# method-usage datum.
PROOF_METHODS = frozenset({{
{methods}
}})

# attributes (`[OF g]`, `simp del:`, `[simp]`) — modifiers, never citations.
ATTRIBUTES = frozenset({{
{attributes}
}})

# outer-syntax keywords (commands, proof language, quasi-commands, bootstrap):
# structural, never a fact citation.
KEYWORDS = frozenset({{
{keywords}
}})
'''


def _fmt_set(names) -> str:
    out, line = [], "    "
    for nm in sorted(names):
        tok = repr(nm) + ", "
        if len(line) + len(tok) > 78:
            out.append(line.rstrip())
            line = "    "
        line += tok
    if line.strip():
        out.append(line.rstrip())
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Extract Isabelle namespaces for isabelle_query.")
    ap.add_argument("--src", default=os.environ.get(
        "ISABELLE_SRC",
        str(Path.home() / "projects" / "ndtht" / "isabelle-src")))
    ap.add_argument("--version", default=None)
    ap.add_argument("--date", default=None, help="YYYY-MM-DD; default today")
    ap.add_argument("--out", default=str(
        Path(__file__).resolve().parent.parent
        / "src" / "isabelle_query" / "_isabelle_namespace.py"))
    ns = ap.parse_args()

    src = Path(ns.src)
    if not (src / "Pure" / "Pure.thy").exists():
        raise SystemExit(f"--src {src} is not an Isabelle src dir "
                         f"(no Pure/Pure.thy)")
    version = ns.version or infer_version(src)
    if ns.date:
        date = ns.date
    else:
        import datetime
        date = datetime.date.today().isoformat()

    decls = scan_decls(src)
    methods, attributes = decls["method"], decls["attribute"]
    keywords = extract_keywords(src)

    Path(ns.out).write_text(_HEADER.format(
        version=version, src=src, date=date,
        nmeth=len(methods), nattr=len(attributes), nkw=len(keywords),
        methods=_fmt_set(methods), attributes=_fmt_set(attributes),
        keywords=_fmt_set(keywords)), encoding="utf-8")
    print(f"wrote {ns.out}")
    print(f"  Isabelle {version}: {len(methods)} methods, "
          f"{len(attributes)} attributes, {len(keywords)} keywords")
    for staple, group in [("simp", methods), ("auto", methods),
                          ("blast", methods), ("OF", attributes),
                          ("and", keywords), ("proof", keywords)]:
        print(f"  {staple!r}: {'ok' if staple in group else 'MISSING'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
