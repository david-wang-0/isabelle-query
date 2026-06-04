"""Corpus-scale robustness checks — skipped unless ``ISABELLE_QUERY_CORPUS``
points at a directory tree of ``.thy`` files (e.g. an AFP checkout or your
ndtht/afp/thys).  These are the "intricate" tests: they re-run the full-tree
measurements that motivated the parser and call-graph work.

    ISABELLE_QUERY_CORPUS=/path/to/afp/thys python -m unittest tests.test_corpus

They are intentionally not part of the default run, since the corpus is large
and not vendored into this repository.
"""

import glob
import os
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, brute_force_call_graph  # noqa: E402

CORPUS = os.environ.get("ISABELLE_QUERY_CORPUS")

# Keep the slow oracle comparison to a bounded slice of the corpus.
_ORACLE_SUBSET = 120
# Allowed share of caller-edges the fast builder may drop vs the oracle.
# The fast builder never *invents* an edge (asserted separately); it may drop
# a tiny fraction where the per-name oracle over-matches a short symbolic name
# inside a longer identifier (e.g. `\<gamma>` within `\<gamma>\<^sub>1`) — a
# spurious oracle hit the token model correctly ignores.
_MAX_DROP_FRACTION = 0.005
# Robustness target: the `?` (unparsed-name) rate.  The bulk of the residual
# is genuinely-anonymous lemmas (`lemma "P"`, `lemma [simp]:`), which are
# *correctly* nameless; the recoverable corner cases are catalogued as
# expected-failures in tests/test_known_failures.py.
_MAX_UNPARSED_FRACTION = 0.07
# A leaked `(in locale)` modifier would appear verbatim at the start of a name.
_LOCALE_LEAK_RE = re.compile(r"\(in\s")


@unittest.skipUnless(CORPUS and os.path.isdir(CORPUS),
                     "set ISABELLE_QUERY_CORPUS to a .thy tree to run corpus tests")
class Corpus(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = sorted(glob.glob(os.path.join(CORPUS, "**", "*.thy"),
                                     recursive=True))
        if not cls.files:
            raise unittest.SkipTest(f"no .thy files under {CORPUS}")

    def _iter_entries(self, paths):
        for p in paths:
            try:
                with open(p, encoding="utf-8", errors="replace") as fh:
                    lines = fh.read().splitlines()
            except OSError:
                continue
            yield p, cli.extract_entries(lines)

    def test_unparsed_name_rate_is_low(self):
        total = unparsed = 0
        for _p, entries in self._iter_entries(self.files):
            for e in entries:
                total += 1
                if e.name == "?":
                    unparsed += 1
        self.assertGreater(total, 0)
        frac = unparsed / total
        self.assertLess(
            frac, _MAX_UNPARSED_FRACTION,
            f"unparsed-name rate {frac:.2%} exceeds {_MAX_UNPARSED_FRACTION:.0%} "
            f"({unparsed:,}/{total:,})")

    def test_no_locale_prefix_leaks(self):
        # the `(in locale)` qualifier must never end up *in* the parsed name.
        # (A double-quoted name may legitimately start with '(', e.g.
        # "(inv upOne) n = n - 1", so match the leak pattern, not just '('.)
        for _p, entries in self._iter_entries(self.files):
            for e in entries:
                if e.name != "?":
                    self.assertIsNone(
                        _LOCALE_LEAK_RE.match(e.name),
                        f"locale prefix leaked into name {e.name!r} in {_p}")

    def test_fast_call_graph_matches_oracle_on_subset(self):
        subset = self.files[:_ORACLE_SUBSET]
        secs = [cli._parse_one(Path(p).stem, Path(p)) for p in subset]
        fast = cli._build_call_graph(secs)
        ref = brute_force_call_graph(secs)

        # The fast builder must never invent an edge the oracle lacks.
        for name, callers in fast.callers.items():
            self.assertLessEqual(
                callers, ref.callers.get(name, set()),
                f"fast builder invented callers for {name!r}")

        # It may drop only a tiny, bounded number of true edges.
        total = sum(len(v) for v in ref.callers.values())
        dropped = sum(len(ref.callers[n] - fast.callers.get(n, set()))
                      for n in ref.callers)
        self.assertLessEqual(
            dropped, max(2, int(total * _MAX_DROP_FRACTION)),
            f"fast builder dropped {dropped}/{total} caller-edges")


if __name__ == "__main__":
    unittest.main()
