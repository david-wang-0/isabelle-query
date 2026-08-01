#!/usr/bin/env python3
r"""Harvest a corpus constant-name list for the width var/const classifier
(bucket c), sampled from the AFP rather than from Isabelle's own infrastructure.

WHY AFP, NOT ISABELLE SOURCE
    The classifier must decide, for a free identifier in a proposition, whether
    it is a variable or a constant.  Isabelle's source tells us what is
    *defined*; the AFP tells us what is *used as a shared constant in practice* —
    including HOL/Main constants (`map`, `Suc`) that AFP imports but never
    redefines, and common library constants, all at their real frequency.  The
    AFP is also the deployment corpus for the shape census, so calibrating on it
    matches the distribution the estimator will actually see.

THE SIGNAL (docs/shape-measures.md's corpus-frequency prior, sharpened)
    Document frequency alone cannot separate a constant (`map`) from a
    conventional free variable (`xs`): both recur across hundreds of entries.
    The discriminator is *binding behaviour*: a constant is essentially never
    bound, whereas a conventional variable is frequently bound (`\<forall>xs`,
    `fix xs`, `for xs`).  So a name is harvested as a corpus constant iff it

        * appears FREE in at least --min-df distinct entries, AND
        * is BOUND in at most --max-bound-frac of the entries that involve it.

    "Free" and "bound" are read straight from query's own pipeline: every lemma's
    goal-step propositions (via `shape._scan_steps` + `_analyze_statement`) — the
    same population w1_est measures — plus the context binders `shape.build_ctx`
    extracts.  There is no second parser.  DF is counted per AFP entry (session
    directory) — "unrelated entries" in the prompt's sense — so a name confined to
    one entry never qualifies (it is that entry's own local constant/variable,
    handled by bucket (a)/(b) live).

USAGE
    python3 scripts/harvest_corpus_constants.py [--stats-only] \
        [--afp ~/repos/afp/thys] [--min-lines 10000] \
        [--min-df 20] [--max-bound-frac 0.15] [--version LABEL] [--date D]

    --stats-only prints sample size and the borderline candidates without
    writing the module, for threshold tuning.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

# Reuse query's own machinery end to end: `cli._parse_one` for parsing a theory
# (entries, spans, prose skipping), and the shape step scanner / context builder /
# statement analyser for the propositions and their free/bound identifiers — the
# harvest thus reads exactly what the estimator reads, with no second parser.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from isabelle_query import cli, shape                  # noqa: E402
from isabelle_query._isabelle_namespace import (       # noqa: E402
    ATTRIBUTES, KEYWORDS, PROOF_METHODS,
)

_SYNTAX = KEYWORDS | PROOF_METHODS | ATTRIBUTES
# Term-level inner syntax not in the outer-keyword table, and the CONST/TYPE
# antiquotation heads — never term constants.
_INNER_SYNTAX = frozenset({"else", "then", "CONST", "TYPE", "case", "of"})
# Conventional Isabelle *variable* spellings, never constants:
#   `P2`, `k2`, `A1`  — a letter followed by digits (enumerated variables);
#   `x'`, `ys'`       — a trailing prime (variant variables);
#   `xs`, `bs`, `cs`  — a single lowercase letter + `s` (the list convention).
_VAR_SHAPE_RE = re.compile(r"(?:[A-Za-z]\d+|[a-z]s)\Z")


def _is_var_shaped(nm: str) -> bool:
    return bool(_VAR_SHAPE_RE.match(nm)) or nm.endswith("'")


def _entry_dirs(thys: Path, min_lines: int) -> list[Path]:
    """AFP entry directories whose total .thy line count is >= min_lines."""
    out = []
    for d in sorted(thys.iterdir()):
        if not d.is_dir():
            continue
        total = 0
        for thy in d.rglob("*.thy"):
            try:
                total += thy.read_text(encoding="utf-8", errors="replace").count("\n")
            except OSError:
                pass
        if total >= min_lines:
            out.append(d)
    return out


def _scan_entry(entry: Path) -> tuple[set[str], set[str]]:
    """One AFP entry -> (names seen FREE, names seen BOUND) over the goal-step
    propositions of all its lemmas — the same population w1_est measures.

    Free/bound come from `shape._analyze_statement` on each goal step's
    proposition, plus the context-bound variables (`fixes`/`for`/`fix`/`obtain`)
    that `shape.build_ctx` already extracts.  Parsing is `cli._parse_one`, so
    prose/comments and entry structure are handled by query, not re-derived.
    """
    free: set[str] = set()
    bound: set[str] = set()
    for thy in entry.rglob("*.thy"):
        try:
            sec = cli._parse_one(thy.stem, thy)
            sec.source()
        except (OSError, ValueError, IndexError):
            continue
        for e in sec.entries:
            steps = shape._scan_steps(sec, e)
            bound |= shape.build_ctx(sec, e, steps).context_vars
            for s in steps:
                if s.kind == "goal" and s.stmt_text:
                    sv = shape._analyze_statement(s.stmt_text)
                    free.update(sv.free)
                    bound.update(sv.bound)
    return free, bound


def harvest(thys: Path, min_lines: int) -> tuple[dict, dict, int]:
    """Return (free_df, bound_df, n_entries): per-name count of distinct entries
    in which the name appears free / bound, over the sampled large entries."""
    free_df: dict[str, int] = defaultdict(int)
    bound_df: dict[str, int] = defaultdict(int)
    entries = _entry_dirs(thys, min_lines)
    for i, entry in enumerate(entries, 1):
        free, bound = _scan_entry(entry)
        for nm in free:
            free_df[nm] += 1
        for nm in bound:
            bound_df[nm] += 1
        print(f"  [{i}/{len(entries)}] {entry.name}", file=sys.stderr)
    return free_df, bound_df, len(entries)


def select(free_df, bound_df, min_df: int, max_bound_frac: float) -> set[str]:
    """Names that are free across >= min_df entries and bound in <= max_bound_frac
    of the entries involving them, and are not Isabelle syntax."""
    out = set()
    for nm, fdf in free_df.items():
        if fdf < min_df or nm in _SYNTAX or nm in _INNER_SYNTAX \
                or _is_var_shaped(nm):
            continue
        bdf = bound_df.get(nm, 0)
        if bdf / (fdf + bdf) <= max_bound_frac:
            out.add(nm)
    return out


_HEADER = '''\
"""Corpus constant names for the width var/const classifier (bucket c).
GENERATED by scripts/harvest_corpus_constants.py — do not edit by hand.

Provenance:
    Corpus:       AFP ({version})
    Sample:       {n_entries} entries >= {min_lines} lines
    Thresholds:   free-DF >= {min_df}, bound-fraction <= {max_bound_frac}
    Extracted:    {date}
    Constants:    {n_consts}

A name lands here when it appears FREE across many unrelated AFP entries yet is
almost never BOUND — the signature of a shared constant (HOL/Main, common
libraries) as opposed to a conventional free variable (`xs`, `n`), which recurs
just as widely but is frequently bound.  See the harvester for the rationale.
"""

CORPUS_CONSTANTS = frozenset({{
{body}
}})
'''


def _fmt(names) -> str:
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
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--afp", default=str(Path.home() / "repos" / "afp" / "thys"))
    ap.add_argument("--min-lines", type=int, default=10000)
    ap.add_argument("--min-df", type=int, default=20)
    ap.add_argument("--max-bound-frac", type=float, default=0.15)
    ap.add_argument("--version", default="Isabelle2025-2")
    ap.add_argument("--date", default=None)
    ap.add_argument("--stats-only", action="store_true")
    ap.add_argument("--out", default=str(
        Path(__file__).resolve().parent.parent
        / "src" / "isabelle_query" / "_corpus_constants.py"))
    ns = ap.parse_args()

    thys = Path(ns.afp).expanduser()
    if not thys.is_dir():
        raise SystemExit(f"--afp {thys} is not a directory")
    free_df, bound_df, n_entries = harvest(thys, ns.min_lines)
    consts = select(free_df, bound_df, ns.min_df, ns.max_bound_frac)
    print(f"sampled {n_entries} entries; "
          f"{len(free_df)} distinct free names; {len(consts)} constants")
    staples = ("Suc", "map", "rev", "length", "insert", "finite", "set", "None")
    print("staples:", {s: (s in consts) for s in staples})
    for s in staples:
        f, b = free_df.get(s, 0), bound_df.get(s, 0)
        frac = b / (f + b) if (f + b) else 0
        print(f"    {s:8} free-DF={f:4d} bound-DF={b:4d} frac={frac:.3f}"
              f" syntax={s in _SYNTAX}")
    if ns.stats_only:
        border = sorted(
            (nm for nm in free_df if ns.min_df <= free_df[nm] < ns.min_df + 15),
            key=lambda n: -free_df[n])[:30]
        print("borderline (free-DF just over threshold):", border)
        # Fixture-protection: conventional prop variables must NOT be constants.
        fx = ("A", "B", "C", "P", "Q", "x", "n", "f", "g", "k", "xs")
        print("fixture vars in consts:", {v: (v in consts) for v in fx})
        return 0

    if ns.date:
        date = ns.date
    else:
        import datetime
        date = datetime.date.today().isoformat()
    Path(ns.out).write_text(_HEADER.format(
        version=ns.version, n_entries=n_entries, min_lines=ns.min_lines,
        min_df=ns.min_df, max_bound_frac=ns.max_bound_frac, date=date,
        n_consts=len(consts), body=_fmt(consts)), encoding="utf-8")
    print(f"wrote {ns.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
