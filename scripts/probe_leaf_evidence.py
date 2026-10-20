#!/usr/bin/env python3
r"""Probe: what evidence is there that an uncited entry should be kept?
[leaf-evidence]

Issue #22 asks for the author's keyword (`corollary`) to survive into the tag,
so that a dead-code audit can keep the results a development is *for*.  But a
keyword is one weak signal: some `theorem`s are scaffolding, and a `lemma`
proved "for independent interest" is not dead.  So rather than one more tag,
`unused` could report evidence per leaf.  Before building that, this counts
how often each kind of evidence fires on the leaves `unused` reports — a
column that marks almost nothing is not worth adding.

For every entry `unused` lists (zero callers, `derived` spellings counted):

    keyword   what the source wrote: lemma / theorem / corollary / ...
    implicit  a declaring attribute on its own header (`[simp]`; 0.9.9)
    doc       its name appears in a document antiquotation anywhere in the
              tree: `@{thm foo}`, `@{thm [attrs] foo bar}`, `\<^thm>‹foo›`,
              and the `thms` / `thm_style` / `named_thms` variants.  The
              call graph deliberately does not count these as citations.
    marker    a `\<^marker>‹tag X›` written on the declaration's own lines

Usage:  probe_leaf_evidence.py [ROOT] [--samples N]    (default: the AFP)
"""
from __future__ import annotations

import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, commands, graph  # noqa: E402
from isabelle_query.model import _CITABLE_TAGS  # noqa: E402
from isabelle_query.parsing import implicitly_used  # noqa: E402

# `@{thm foo}`, `@{thm [display] foo bar}`, `@{thms foo}` ...
_AT_ANTIQ_RE = re.compile(
    r"@\{\s*(thm|thms|thm_style|named_thms|lemma)\b([^}]*)\}")
# `\<^thm>\<open>foo bar\<close>` and the `‹›` spelling.
_CART_ANTIQ_RE = re.compile(
    r"\\<\^(thm|thms)>\s*(?:\\<open>|‹)(.*?)(?:\\<close>|›)", re.S)
_NAME_RE = re.compile(r"[A-Za-z][\w'.]*")
_MARKER_RE = re.compile(
    r"\\<\^marker>\s*(?:\\<open>|‹)\s*tag\s+([\w-]+)")
_KEYWORD_RE = re.compile(r"\s*(?:(?:private|qualified)\s+)?([A-Za-z_]+)")


def doc_names(sections) -> Counter[str]:
    """Every fact name a document antiquotation mentions, with counts."""
    out: Counter[str] = Counter()
    for sec in sections:
        text = "\n".join(sec.source())
        if "thm" not in text:
            continue
        for m in _AT_ANTIQ_RE.finditer(text):
            if m.group(1) == "lemma":
                continue            # `@{lemma "P" by simp}` states, not cites
            body = re.sub(r"\[[^\]]*\]", " ", m.group(2))
            out.update(_bare(n) for n in _NAME_RE.findall(body))
        for m in _CART_ANTIQ_RE.finditer(text):
            body = re.sub(r"\[[^\]]*\]", " ", m.group(2))
            out.update(_bare(n) for n in _NAME_RE.findall(body))
    return out


def _bare(name: str) -> str:
    """`foo(2)` / `Thy.foo` -> `foo`: the entry a mention points at."""
    return name.split("(", 1)[0].rsplit(".", 1)[-1]


def main() -> None:
    args = sys.argv[1:]
    k = 6
    if "--samples" in args:
        i = args.index("--samples")
        k = int(args[i + 1])
        del args[i:i + 2]
    root = Path(args[0]).expanduser() if args else (
        Path.home() / "repos" / "afp" / "thys")
    cli._ROOT_OVERRIDE = root.resolve()
    sections = cli.load_index()
    g = graph._build_call_graph(sections, derived=True)
    dead = commands._compute_unused(g)
    docs = doc_names(sections)

    rows = []
    for sec in sections:
        src = sec.source()
        for e in sec.entries:
            if e.tag not in _CITABLE_TAGS or e.name == "?" \
                    or e.name not in dead or not e.thy_line:
                continue
            m = _KEYWORD_RE.match(src[e.thy_line - 1])
            kw = m.group(1) if m else "?"
            if e.tag == "LEMMAS" and kw == "and":
                kw = "lemmas"
            head = "\n".join(src[e.thy_line - 1:max(e.decl_end_line,
                                                    e.thy_line)])
            ev = {}
            if kw in ("theorem", "corollary", "proposition"):
                ev["keyword"] = kw
            if implicitly_used(e):
                ev["implicit"] = ",".join(implicitly_used(e))
            if docs.get(e.name):
                ev["doc"] = str(docs[e.name])
            mk = _MARKER_RE.findall(head)
            if mk:
                ev["marker"] = ",".join(mk)
            rows.append((sec, e, kw, ev))

    n = len(rows)
    print(f"=== {root}: {len(sections):,} theories, {n:,} unused entries ===")
    kws = Counter(kw for _s, _e, kw, _v in rows)
    print("keyword of the leaf:")
    for kw, c in kws.most_common(12):
        print(f"  {kw:<14} {c:>8,}  {100 * c / n:5.1f}%")
    print("evidence (an entry may have several):")
    for kind in ("keyword", "implicit", "doc", "marker"):
        c = sum(1 for *_x, ev in rows if kind in ev)
        print(f"  {kind:<14} {c:>8,}  {100 * c / n:5.1f}%")
    combos = Counter("+".join(sorted(ev)) or "(none)" for *_x, ev in rows)
    print("combinations:")
    for combo, c in combos.most_common(10):
        print(f"  {combo:<30} {c:>8,}  {100 * c / n:5.1f}%")
    only = Counter(next(iter(ev)) for *_x, ev in rows if len(ev) == 1)
    print("the ONLY evidence on a leaf (what each kind adds alone):")
    for kind, c in only.most_common():
        print(f"  {kind:<14} {c:>8,}")
    tags = Counter(t for *_x, ev in rows if "marker" in ev
                   for t in ev["marker"].split(","))
    if tags:
        print("marker tags:", ", ".join(f"{t} {c:,}"
                                        for t, c in tags.most_common(8)))
    by_kw_doc = defaultdict(int)
    for _s, _e, kw, ev in rows:
        if "doc" in ev:
            by_kw_doc[kw] += 1
    print("doc-cited leaves by keyword:",
          ", ".join(f"{kw} {c:,}" for kw, c in
                    sorted(by_kw_doc.items(), key=lambda x: -x[1])[:6]))
    rnd = random.Random(0)
    for kind in ("doc", "marker"):
        xs = [(s, e, ev) for s, e, _kw, ev in rows if kind in ev
              and len(ev) == 1]
        if xs:
            print(f"\n{kind} only (random sample):")
            for s, e, ev in rnd.sample(xs, min(k, len(xs))):
                print(f"  {s.theory}:{e.thy_line}  {e.name} ({e.tag})  "
                      f"{kind}={ev[kind]}")


if __name__ == "__main__":
    main()
