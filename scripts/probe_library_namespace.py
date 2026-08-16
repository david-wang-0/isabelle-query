#!/usr/bin/env python3
"""Corpus probe: what does an unconfigured namespace cost a *library* caller?

`shape.analyze_proof` reads `graph._PROOF_METHODS` late-bound.  The CLI binds a
table at dispatch (`cli._configure_namespace`: the broad census union for
`shape census`, and for a HOL-base project with no built heap too).  A caller
that imports the package and calls `analyze_proof` directly binds nothing, so it
keeps the import-time minimal Pure floor — under which `simp` and `rule` are
recognised but `auto` / `blast` / `force` / `metis` / `induct` are not.

So the failure is not "no method is ever extracted"; it is *selective*, biased
toward the automation methods, and silent.  This measures the size and the
direction of the bias: per proof, `Step.method` extraction and `trivial_frac`
under the Pure floor vs the census union the CLI would have bound.

Usage:  probe_library_namespace.py [N_ENTRIES]
"""
import os
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if not os.environ.get("PYTHONPATH"):
    sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, graph, shape        # noqa: E402
from isabelle_query import _census_namespace as census    # noqa: E402
from isabelle_query import _isabelle_namespace as isa_ns  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 40

PURE = (isa_ns.PROOF_METHODS, isa_ns.ATTRIBUTES, isa_ns.KEYWORDS)
BROAD = (census.PROOF_METHODS, census.ATTRIBUTES, isa_ns.KEYWORDS)


def measure(secs):
    """(per-proof trivial_frac, per-step method) under the currently bound table."""
    fracs, methods = [], []
    for sec in secs:
        for ent in sec.entries:
            pm = shape.analyze_proof(sec, ent)
            if pm is None:
                continue
            fracs.append(shape.trivial_frac(pm.steps))
            methods.append(tuple(s.method for s in pm.steps))
    return fracs, methods


n_entries = n_proofs = 0
steps_total = steps_pure = steps_broad = 0
frac_same = frac_none_to_value = frac_moved = 0
lost_methods: Counter = Counter()

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

    graph.configure_namespace(*PURE)
    pure_fracs, pure_methods = measure(secs)
    graph.configure_namespace(*BROAD)
    broad_fracs, broad_methods = measure(secs)

    n_proofs += len(pure_fracs)
    for pf, bf in zip(pure_fracs, broad_fracs):
        if pf == bf:
            frac_same += 1
        elif pf is None:
            frac_none_to_value += 1
        else:
            frac_moved += 1
    for pm_, bm_ in zip(pure_methods, broad_methods):
        steps_total += len(pm_)
        steps_pure += sum(1 for m in pm_ if m)
        steps_broad += sum(1 for m in bm_ if m)
        for p, b in zip(pm_, bm_):
            if b and not p:
                lost_methods[b] += 1

graph.configure_namespace(*BROAD)

print(f"entries={n_entries}  proofs={n_proofs}  steps={steps_total}")
print(f"steps carrying a method:  Pure floor {steps_pure} "
      f"({100 * steps_pure / max(steps_total, 1):.1f}%)   "
      f"census union {steps_broad} "
      f"({100 * steps_broad / max(steps_total, 1):.1f}%)")
print(f"steps whose method the Pure floor drops: "
      f"{steps_broad - steps_pure} "
      f"({100 * (steps_broad - steps_pure) / max(steps_broad, 1):.1f}% of "
      f"extracted)")
print(f"\ntrivial_frac per proof:  unchanged {frac_same}  "
      f"None->value {frac_none_to_value}  value->other value {frac_moved}  "
      f"= {100 * (frac_none_to_value + frac_moved) / max(n_proofs, 1):.1f}% "
      f"of proofs disagree")
print("\nmost-dropped methods (recognised by the census table, not by Pure):")
for name, c in lost_methods.most_common(20):
    print(f"  {name:<20} {c}")
