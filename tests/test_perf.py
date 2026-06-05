"""Performance *regression* guards for the call-graph build.

The build's two worst historical traps were both O(n^2): `_entry_at_line`
rebuilt a keys list on every call (O(lines x entries)), and the prose-skip
test rescanned every range per line (O(lines x ranges)).  Each is invisible
on a small fixture and ruinous on the AFP.  So the guard here is not an
absolute wall-clock floor (which flakes on slow CI for reasons unrelated to
the code) but a *scaling ratio*: build a synthetic corpus at size S and at
4*S and assert the time ratio stays near linear (~4), nowhere near quadratic
(~16).  A reintroduced O(n^2) term blows the ratio long before it would trip
a fragile absolute threshold.

Crucially the corpus scales by *per-theory size*, not theory count.  Both
build traps are per-theory quadratics (`_entry_at_line` is O(lines x entries)
*within one theory*); scaling theory count alone keeps per-theory size fixed,
so the regression would stay linear in corpus size and hide.  Growing one
theory's definitions, lemmas AND interspersed text blocks together is what
makes a reintroduced per-line O(n) factor show up as a near-quadratic ratio.

These are opt-in: timing has no place in the fast default suite.  Run with

    ISABELLE_QUERY_PERF=1 python -m unittest tests.test_perf -v
"""

import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

_RUN = os.environ.get("ISABELLE_QUERY_PERF")

_BASE_DEFS = 150        # citable definitions at scale 1
_BASE_LEMMAS = 2000     # lemmas at scale 1, each citing two defs (real edges)
_SCALE = 4              # large theory is this many times the small one
_TEXTBLOCK_EVERY = 12   # a prose block every N lemmas, so #blocks scales too


def _theory_text(scale: int) -> str:
    """One theory whose definition, lemma and text-block counts are all
    multiplied by `scale`: definitions first, then lemmas whose proof bodies
    cite two of them each, prose blocks interspersed (text-mask skip), and a
    \\<...> symbol on every lemma (the sym_re branch)."""
    n_defs, n_lemmas = _BASE_DEFS * scale, _BASE_LEMMAS * scale
    out = ["theory Perf imports Main begin"]
    for i in range(n_defs):
        out.append(f'definition d{i} :: "nat" where "d{i} = 0"')
    for j in range(n_lemmas):
        a, b = j % n_defs, (j + 1) % n_defs
        if j % _TEXTBLOCK_EVERY == 0:
            out.append(rf'text \<open>prose discussing d{a} here.\<close>')
        out.append(
            f'lemma l{j}: "d{a} = d{b} \\<Longrightarrow> d{a} = d{b}" '
            f'by (simp add: d{a}_def d{b}_def)')
    out.append("end")
    return "\n".join(out)


def _corpus(scale: int):
    """A single parsed TheorySection of the given per-theory `scale`."""
    return [section_from(_theory_text(scale), "Perf")]


def _best_build_time(sections, repeat: int = 5) -> float:
    """Minimum build time over `repeat` runs — the min is the cleanest signal,
    least perturbed by scheduling noise on a shared machine."""
    best = float("inf")
    for _ in range(repeat):
        t0 = time.perf_counter()
        cli._build_call_graph(sections)
        best = min(best, time.perf_counter() - t0)
    return best


@unittest.skipUnless(_RUN, "set ISABELLE_QUERY_PERF=1 to run perf checks")
class BuildScaling(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Parse each corpus once; both tests reuse them (parsing is incidental
        # to what we measure, and the large parse is the slow part).
        cls.small = _corpus(1)
        cls.large = _corpus(_SCALE)

    def test_build_scales_near_linearly(self):
        t_small = _best_build_time(self.small)
        t_large = _best_build_time(self.large)
        ratio = t_large / t_small
        # Linear -> ~_SCALE; quadratic -> ~_SCALE**2.  Allow 2x headroom over
        # linear for noise/log-factors; still far below the quadratic wall.
        self.assertLess(
            ratio, _SCALE * 2.0,
            f"build time scaled {ratio:.1f}x for a {_SCALE}x corpus "
            f"({t_small*1e3:.0f}ms -> {t_large*1e3:.0f}ms); "
            f"near-quadratic ({_SCALE**2}x) suggests a per-line O(n) factor "
            f"crept back into the build loop")

    def test_build_throughput_is_sane(self):
        # A deliberately loose absolute floor: we measure hundreds of thousands
        # of lines/s, so this fires only on an order-of-magnitude regression,
        # never on ordinary CI slowness.
        n_lines = sum(len(s.source()) for s in self.large)
        rate = n_lines / _best_build_time(self.large)
        self.assertGreater(
            rate, 20_000,
            f"build throughput {rate:,.0f} lines/s is below the 20k/s floor "
            f"— an order-of-magnitude regression, not mere machine slowness")


if __name__ == "__main__":
    unittest.main()
