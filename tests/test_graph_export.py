r"""`graph` — the whole citation or import graph, as JSON or DOT [graph-export].

One whole-graph verb rather than a `--json` flag on each of
`callers`/`callees`/`deps`/`uses`.  The consumers this is for — `jq`, Graphviz,
external analysis — want the adjacency in full, which those verbs structurally
cannot give: each answers about ONE subject, so rebuilding the graph from them
is N invocations and a merge.  The flag route would also multiply the output
contract by six, across renderers that already differ, when scripted
single-subject answers are what `--names` / `-c` are already for.

Two properties matter more than the field names, and both are pinned below.

**Determinism.**  Two runs over an unchanged tree must produce byte-identical
output, or the export cannot be committed, diffed, or used as a baseline —
which is most of what makes one worth having.  Hence sorted keys and sorted
adjacency everywhere.

**Escaping.**  Isabelle names carry backslashes (`\<Gamma>\<^sub>A`), and DOT
treats `\` as an escape introducer inside a quoted ID.  Emitting a name raw is
not merely ugly: `\<` is consumed, and the graph then carries a different name
than the corpus does.  The round-trip is asserted, not the spelling.

NOTE: Graphviz is not a test dependency, so what is checked here is the DOT
*structure* this module emits, not that `dot(1)` accepts it.
"""

import argparse
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
from support import cli  # noqa: E402

TREE = {
    "base/Base.thy":
        'theory Base imports Main begin\n'
        'lemma helper: "True" by simp\n'
        'lemma \\<Gamma>\\<^sub>x: "True" by simp\n'
        'end\n',
    "mid/Mid.thy":
        'theory Mid imports Base "HOL-Library.FuncSet" begin\n'
        'lemma mid_fact: "True" using helper by simp\n'
        'end\n',
}


def _write_tree(base, files):
    for rel, content in files.items():
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


def _dot_unquote(tok: str) -> str:
    """Inverse of `_dot_quote`, for the round-trip assertion."""
    assert tok.startswith('"') and tok.endswith('"'), tok
    out, body, i = [], tok[1:-1], 0
    while i < len(body):
        if body[i] == "\\" and i + 1 < len(body):
            out.append(body[i + 1])
            i += 2
        else:
            out.append(body[i])
            i += 1
    return "".join(out)


class GraphFixture(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        _write_tree(self.root, TREE)
        self.sections = cli._load_sections(
            argparse.Namespace(files=[str(self.root)]))

    def tearDown(self):
        self._tmp.cleanup()

    def _graph(self, kind="citation", fmt="json", **kw):
        return _capture(cli.cmd_graph, self.sections, kind, fmt,
                        cli.CmdFlags(**kw))

    def _json(self, kind="citation", **kw):
        return json.loads(self._graph(kind, "json", **kw))


class TheJsonShape(GraphFixture):

    def test_citation_output_is_valid_json(self):
        self.assertEqual(self._json()["kind"], "citation")

    def test_imports_output_is_valid_json(self):
        self.assertEqual(self._json("imports")["kind"], "imports")

    def test_a_citation_edge_is_caller_then_callee(self):
        self.assertIn(["mid_fact", "helper"], self._json()["edges"])

    def test_citation_nodes_carry_their_locus(self):
        node = next(n for n in self._json()["nodes"] if n["name"] == "helper")
        self.assertEqual((node["theory"], node["tag"]), ("Base", "LEMMA"))

    def test_an_import_edge_is_importer_then_imported(self):
        self.assertIn(["Mid", "Base"], self._json("imports")["edges"])

    def test_an_out_of_project_import_is_a_flagged_node_not_a_dropped_one(self):
        # `Main` counts as much as `HOL-Library.FuncSet` does: both are
        # imports query does not load sources for, and both are real edges.
        data = self._json("imports")
        ext = [n for n in data["nodes"] if n.get("external")]
        self.assertEqual([n["name"] for n in ext],
                         ["HOL-Library.FuncSet", "Main"])
        # ...and they keep their edges: a dependency diagram that hides the
        # library dependency is not the picture anyone wanted.
        self.assertIn(["Mid", "HOL-Library.FuncSet"], data["edges"])
        self.assertIn(["Base", "Main"], data["edges"])

    def test_every_edge_endpoint_is_a_declared_node(self):
        for kind in ("citation", "imports"):
            with self.subTest(kind=kind):
                data = self._json(kind)
                names = {n["name"] for n in data["nodes"]}
                dangling = {e for edge in data["edges"] for e in edge
                            if e not in names}
                self.assertEqual(dangling, set())


class Determinism(GraphFixture):

    def test_two_runs_are_byte_identical(self):
        for kind in ("citation", "imports"):
            for fmt in ("json", "dot"):
                with self.subTest(kind=kind, format=fmt):
                    self.assertEqual(self._graph(kind, fmt),
                                     self._graph(kind, fmt))

    def test_edges_are_sorted(self):
        edges = self._json("imports")["edges"]
        self.assertEqual(edges, sorted(edges))


class DotEscaping(GraphFixture):

    def test_a_markup_name_round_trips(self):
        # The property, not the spelling: whatever escaping is used, unquoting
        # must give back exactly the name the corpus declares.
        raw = "\\<Gamma>\\<^sub>x"
        self.assertEqual(_dot_unquote(cli._dot_quote(raw)), raw)

    def test_a_quote_in_a_name_round_trips(self):
        self.assertEqual(_dot_unquote(cli._dot_quote('a"b')), 'a"b')

    def test_the_emitted_dot_carries_the_escaped_name(self):
        out = self._graph("citation", "dot")
        line = next(ln for ln in out.splitlines() if "Gamma" in ln)
        tok = line.strip().rstrip(";")
        self.assertEqual(_dot_unquote(tok), "\\<Gamma>\\<^sub>x")

    def test_the_dot_body_is_a_balanced_digraph(self):
        lines = self._graph("imports", "dot").strip().splitlines()
        self.assertTrue(lines[0].startswith("digraph "))
        self.assertEqual(lines[0].rstrip()[-1], "{")
        self.assertEqual(lines[-1], "}")
        for ln in lines[1:-1]:
            self.assertTrue(ln.rstrip().endswith(";"), ln)


class Scoping(GraphFixture):

    def test_a_theory_scope_narrows_the_export(self):
        ns = argparse.Namespace(theory_scope=["Base"])
        scoped = cli._scope_to_theories(ns, self.sections)
        data = json.loads(_capture(cli.cmd_graph, scoped, "imports", "json",
                                   cli.CmdFlags()))
        # Base and its own external import; Mid is out of scope, so neither
        # it nor the FuncSet edge it owns appears.
        self.assertEqual([n["name"] for n in data["nodes"]], ["Base", "Main"])
        self.assertEqual(data["edges"], [["Base", "Main"]])

    def test_kind_defaults_to_citation(self):
        ns = cli._build_parser().parse_args(["graph"])
        self.assertEqual((ns.kind, ns.format), ("citation", "json"))

    def test_the_format_choices_are_constrained(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                cli._build_parser().parse_args(["graph", "--format", "yaml"])


if __name__ == "__main__":
    unittest.main()
