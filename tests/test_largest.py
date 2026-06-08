"""`largest` ranks entries by source span, scoped by the trailing PATH
positionals (shared with grep/callers via `_load_sections`).

Three concerns:
  * `cmd_largest` ranking + the `-N/--top` cap and the empty case;
  * argparse wiring of `-N/--top` and the `files` positionals;
  * the file/dir/name union semantics `_load_sections` gives the command.
"""

import argparse
import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, sections_from  # noqa: E402

BIG = """theory Big imports Main begin
lemma huge:
  "P x = P x"
proof -
  have "P x = P x" by simp
  then show ?thesis by simp
qed
end
"""

SMALL = """theory Small imports Main begin
lemma tiny: "True" by simp
end
"""


def _run_largest(sections, top=20):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        cli.cmd_largest(sections, top)
    return buf.getvalue()


def _ranked_names(output):
    """Entry names from `largest` output rows, in printed (ranked) order.
    A data row starts with the integer span; header/rule rows do not."""
    names = []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[0].isdigit():
            names.append(parts[2])
    return names


def _write_tree(base: Path, files: dict) -> None:
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def _thy(name: str) -> str:
    return f"theory {name} imports Main begin\nlemma {name}_l: \"True\" by simp\nend\n"


class CmdLargest(unittest.TestCase):
    def test_ranks_by_span_descending(self):
        secs = sections_from({"Big": BIG, "Small": SMALL})
        names = _ranked_names(_run_largest(secs))
        self.assertIn("huge", names)
        self.assertIn("tiny", names)
        self.assertLess(names.index("huge"), names.index("tiny"))

    def test_top_caps_the_row_count(self):
        secs = sections_from({"Big": BIG, "Small": SMALL})
        out = _run_largest(secs, top=1)
        self.assertEqual(_ranked_names(out), ["huge"])
        self.assertIn("Top 1 largest entries", out)

    def test_empty_sections_report_nothing(self):
        self.assertIn("No entries found.", _run_largest([], top=20))


class LargestParser(unittest.TestCase):
    def setUp(self):
        self.parser = cli._build_parser()

    def test_top_flag_and_files(self):
        ns = self.parser.parse_args(["largest", "-N", "5", "a.thy", "b.thy"])
        self.assertEqual(ns.top, 5)
        self.assertEqual(ns.files, ["a.thy", "b.thy"])
        self.assertIs(ns.func, cli._run_largest)

    def test_long_top_flag(self):
        ns = self.parser.parse_args(["largest", "--top", "3"])
        self.assertEqual(ns.top, 3)
        self.assertEqual(ns.files, [])

    def test_defaults(self):
        ns = self.parser.parse_args(["largest"])
        self.assertEqual(ns.top, 20)
        self.assertEqual(ns.files, [])


class LargestFileSemantics(unittest.TestCase):
    """`_load_sections` is the scoping layer `largest` now rides on."""

    def _load(self, files):
        return cli._load_sections(argparse.Namespace(files=files))

    def test_union_of_explicit_files(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _write_tree(d, {"A.thy": _thy("A"), "B.thy": _thy("B")})
            secs = self._load([str(d / "A.thy"), str(d / "B.thy")])
            self.assertEqual({s.theory for s in secs}, {"A", "B"})

    def test_directory_with_root_uses_root_theories(self):
        # T3 sits on disk but is omitted from ROOT's `theories` -> excluded,
        # the indirect ROOT-scoped semantics.
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _write_tree(d, {
                "ROOT": "session S = HOL +\n  theories\n    T1\n    T2\n",
                "T1.thy": _thy("T1"),
                "T2.thy": _thy("T2"),
                "T3.thy": _thy("T3"),
            })
            secs = self._load([str(d)])
            self.assertEqual({s.theory for s in secs}, {"T1", "T2"})

    def test_directory_without_root_recursive_glob(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _write_tree(d, {
                "sub/One.thy": _thy("One"),
                "sub/deep/Two.thy": _thy("Two"),
            })
            secs = self._load([str(d)])
            self.assertEqual({s.theory for s in secs}, {"One", "Two"})

    def test_files_are_deduped_by_resolved_path(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            _write_tree(d, {"A.thy": _thy("A")})
            secs = self._load([str(d / "A.thy"), str(d / "A.thy")])
            self.assertEqual([s.theory for s in secs], ["A"])


if __name__ == "__main__":
    unittest.main()
