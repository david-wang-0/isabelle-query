#!/usr/bin/env python3
"""Corpus probe: how often does a discharge step name a method we cannot?

`trivial_frac`'s denominator is "steps carrying an extracted method", which is a
*table-dependent* notion — unlike every other shape axis, whose denominators are
positional.  So a method the bound table does not carry does not merely go
unclassified: it leaves the denominator, and a proof with no recognised method at
all returns `None`, indistinguishable from a genuinely structural body.

Whether that matters is a question about size and distribution, not about API
taste, so this measures both under the DEFAULT table (the broad union):

  * `discharge`     — lines carrying a `by` / `apply` introducer.  Positional and
    table-free: both are Isar keywords.  (`proof` is excluded on purpose — it
    opens a block, it does not close a goal.)
  * `named`         — of those, the ones whose token is in the bound table.
  * `unrecognised`  — the difference: what `[introducer-no-table]` would recover.

It also reports the per-entry spread, because a uniform 1% is noise a consumer
can ignore while the same 1% concentrated in a few entries is a bias correlated
with proof style — the shape of every measurement fault this tool has had.

THE ORACLE.  The proposed change rests on "in introducer position the token IS a
method", so the thing to disprove is a token the regex grabbed that is not one.
Eyeballing does not scale past a few dozen, so unrecognised tokens are checked
against **method declarations harvested from the corpus itself** — `method_setup
NAME` (ML) and `method NAME` (Eisbach), read in command position off the outer
view.  A token that some entry declares is confirmed, not guessed.  The residue
is printed in full: it is the only part needing judgement, and it is where a
false positive would have to hide.

Usage:  probe_method_coverage.py [N_ENTRIES] [--show TOK,TOK,...]

``--show`` prints the source line behind every occurrence of the named tokens,
which is how a residue entry is judged: a real tactic reads as one, and a regex
false positive reads as English.
"""
import os
import re
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if not os.environ.get("PYTHONPATH"):
    sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph, shape  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
_args = sys.argv[1:]
SHOW: set[str] = set()
if "--show" in _args:
    i = _args.index("--show")
    SHOW = set(_args[i + 1].split(","))
    del _args[i:i + 2]
LIMIT = int(_args[0]) if _args else 40
shown: list[tuple[str, str, int, str]] = []   # token, theory, line, source

# `by`/`apply` only — a discharge.
_DISCHARGE_RE = re.compile(r"\b(?:by|apply)\b\s*\(?\s*([\w']+)")
# Method declarations, in command position (outer view): Eisbach `method foo =`
# / `method foo uses r =`, and ML `method_setup foo = \<open>...\<close>`.
_DECL_RE = re.compile(r"^\s*(?:method_setup|method)\s+([\w']+)")

n_entries = 0
tot: Counter = Counter()
unnamed_tokens: Counter = Counter()
declared: set[str] = set()          # methods some corpus entry declares
per_entry: list[tuple[float, str, int, int]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    secs = []
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            secs.append(cli._parse_one(thy_path.stem, thy_path))
        except Exception:  # noqa: BLE001
            pass
    if not secs:
        continue
    n_entries += 1
    e_discharge = e_unrec = 0

    for sec in secs:
        # Declarations: command position, so a `method` inside a term or a
        # comment cannot register.
        for line in sec.outer_source():
            md = _DECL_RE.match(line)
            if md:
                declared.add(md.group(1))

        live = sec.live_source()
        for entry in sec.entries:
            pm = shape.analyze_proof(sec, entry)
            if pm is None:
                continue
            proof_discharge = 0
            for s in pm.steps:
                line = live[s.line - 1] if s.line - 1 < len(live) else ""
                m = _DISCHARGE_RE.search(line)
                if not m:
                    continue
                tok = m.group(1)
                proof_discharge += 1
                e_discharge += 1
                tot["discharge"] += 1
                # Exactly the proposed change: is the token in the table, or
                # would dropping the membership check recover it?
                if tok in graph._PROOF_METHODS:
                    tot["named"] += 1
                else:
                    tot["unrecognised"] += 1
                    e_unrec += 1
                    unnamed_tokens[tok] += 1
                    if tok in SHOW:
                        shown.append((tok, sec.theory, s.line,
                                      sec.source()[s.line - 1].strip()))
            tot["proofs"] += 1
            if shape.trivial_frac(pm.steps) is None:
                tot["none_proofs"] += 1
                if proof_discharge:      # discharges, but we cannot name how
                    tot["none_but_discharges"] += 1

    if e_discharge:
        per_entry.append((100 * e_unrec / e_discharge, ent.name,
                          e_unrec, e_discharge))

d, n, u = tot["discharge"], tot["named"], tot["unrecognised"]
print(f"entries={n_entries}  proofs={tot['proofs']}  "
      f"table={len(graph._PROOF_METHODS)} methods (the default union)")
print(f"discharge steps (by/apply, positional): {d}")
print(f"  named by the table:   {n} ({100 * n / max(d, 1):.2f}%)")
print(f"  UNRECOGNISED:         {u} ({100 * u / max(d, 1):.2f}%)")
print(f"\nproofs with trivial_frac is None: {tot['none_proofs']} "
      f"({100 * tot['none_proofs'] / max(tot['proofs'], 1):.2f}%)")
print(f"  …of which actually discharge something: "
      f"{tot['none_but_discharges']} "
      f"({100 * tot['none_but_discharges'] / max(tot['proofs'], 1):.2f}% of "
      f"proofs) — these are the misreports")

print(f"\nper-entry unrecognised rate (worst 15 of {len(per_entry)}):")
for pct, name, u_, d_ in sorted(per_entry, reverse=True)[:15]:
    print(f"  {pct:6.2f}%  {name:<40} {u_}/{d_}")
rates = sorted(p for p, *_ in per_entry)
if rates:
    print(f"  median {rates[len(rates) // 2]:.2f}%   "
          f"p90 {rates[min(len(rates) - 1, int(0.9 * len(rates)))]:.2f}%   "
          f"entries at 0.00%: {sum(1 for r in rates if r == 0)}/{len(rates)}")

# --- the oracle -------------------------------------------------------------
print(f"\ncorpus-declared methods harvested (method_setup / method): "
      f"{len(declared)}")
confirmed = {t: c for t, c in unnamed_tokens.items() if t in declared}
residue = {t: c for t, c in unnamed_tokens.items() if t not in declared}
print(f"unrecognised tokens: {len(unnamed_tokens)} distinct / {u} occurrences")
print(f"  CONFIRMED a declared method somewhere in the corpus: "
      f"{len(confirmed)} distinct / {sum(confirmed.values())} occurrences "
      f"({100 * sum(confirmed.values()) / max(u, 1):.2f}%)")
print(f"  residue (needs judgement — a distribution method outside the union, "
      f"or a false positive): {len(residue)} distinct / "
      f"{sum(residue.values())} occurrences "
      f"({100 * sum(residue.values()) / max(u, 1):.2f}%)")
print("\n  residue in full, most frequent first:")
for t, c in sorted(residue.items(), key=lambda kv: (-kv[1], kv[0])):
    print(f"    {t:<34} {c}")

if SHOW:
    print(f"\nsource lines behind {sorted(SHOW)} ({len(shown)} occurrences):")
    for tok, thy, ln, src in sorted(shown):
        print(f"  [{tok}] {thy}:{ln}\n      {src[:150]}")
