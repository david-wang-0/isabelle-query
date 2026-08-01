"""`summary --by-session` rolls the per-theory counts up to the session /
corpus level, and the loader tags each `TheorySection` with its owning
session so it can.

Two concerns pinned here: (1) the loader (`_sections_from_dir`) attaches the
right `session` to every theory across a multi-session tree; (2) the
aggregate renderer (`cmd_summary(..., by_session=/verbose=/totals_only=)`)
emits per-session rows, a grand total, the verbose per-theory expansion, and
the terse totals-only headline.
"""

import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import support  # noqa: F401,E402  (side effect: puts src/ on sys.path)
from isabelle_query.commands import cmd_summary  # noqa: E402
from isabelle_query.model import Entry, TheorySection  # noqa: E402
from isabelle_query.parsing import _sections_from_dir  # noqa: E402


def _build(base: Path, files: dict) -> None:
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def _sec(theory, session, thy_lines, defs=0, lemmas=0, thms=0) -> TheorySection:
    entries = ([Entry("DEF", f"{theory}_d{i}", "", theory) for i in range(defs)]
               + [Entry("LEMMA", f"{theory}_l{i}", "", theory) for i in range(lemmas)]
               + [Entry("THEOREM", f"{theory}_t{i}", "", theory) for i in range(thms)])
    return TheorySection(theory, Path(f"{theory}.thy"), entries,
                         thy_lines=thy_lines, session=session)


def _render(sections, **kw) -> str:
    buf = io.StringIO()
    with redirect_stdout(buf):
        cmd_summary(sections, **kw)
    return buf.getvalue()


# Alpha: 2 theories (30 lines, D2 L4 T1); Beta: 1 theory (5 lines, D1).
_SECTIONS = [
    _sec("A1", "Alpha", 10, defs=2, lemmas=1),
    _sec("A2", "Alpha", 20, lemmas=3, thms=1),
    _sec("B1", "Beta", 5, defs=1),
]


class AggregateRender(unittest.TestCase):
    def test_by_session_table_and_total(self):
        out = _render(_SECTIONS, by_session=True)
        self.assertIn("# Corpus summary", out)
        # Headline: 8 entries, 35 lines, 3 theories, 2 sessions.
        self.assertIn("8 entries", out)
        self.assertIn("35 source lines", out)
        self.assertIn("3 theories", out)
        self.assertIn("2 sessions", out)
        # Per-session rows (Session | Thy | Src | D | L | T).
        self.assertIn("| Alpha | 2 | 30 | 2 | 4 | 1 |", out)
        self.assertIn("| Beta | 1 | 5 | 1 | 0 | 0 |", out)
        # Grand-total row.
        self.assertIn("| **TOTAL** | 3 | 35 | 3 | 4 | 1 |", out)

    def test_totals_only_suppresses_table(self):
        out = _render(_SECTIONS, totals_only=True)
        self.assertIn("8 entries", out)
        self.assertNotIn("| Session |", out)
        self.assertNotIn("**TOTAL**", out)

    def test_verbose_expands_theories(self):
        out = _render(_SECTIONS, by_session=True, verbose=True)
        self.assertIn("## Alpha", out)
        self.assertIn("## Beta", out)
        # Per-theory rows appear; the flat per-session row does not.
        self.assertIn("| A1 | 10 | 2 | 1 | 0 |", out)
        self.assertIn("| A2 | 20 | 0 | 3 | 1 |", out)
        self.assertNotIn("**TOTAL**", out)

    def test_default_is_still_per_theory_table(self):
        out = _render(_SECTIONS)
        self.assertIn("# Theory Index", out)
        self.assertIn("| Theory | Src | D | L | T | Key Exports |", out)

    def test_no_session_bucket_labelled(self):
        # A section with session=None lands under the "(no session)" bucket.
        out = _render([_sec("Lone", None, 7, defs=1)], by_session=True)
        self.assertIn("(no session)", out)


class SessionAttribution(unittest.TestCase):
    def test_loader_tags_each_theory_with_its_session(self):
        root = (
            "session Alpha = HOL +\n  theories\n    A1\n    A2\n"
            "session Beta = HOL +\n  theories\n    B1\n"
        )
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _build(d, {
                "ROOT": root,
                "A1.thy": "theory A1 imports Main begin\nend\n",
                "A2.thy": "theory A2 imports A1 begin\nend\n",
                "B1.thy": "theory B1 imports Main begin\nend\n",
            })
            sections: list = []
            _sections_from_dir(d, set(), sections)
            got = {s.theory: s.session for s in sections}
            self.assertEqual(got, {"A1": "Alpha", "A2": "Alpha", "B1": "Beta"})


if __name__ == "__main__":
    unittest.main()
