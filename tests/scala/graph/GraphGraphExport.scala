package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphGraphExport {
  def run(): Unit = {
    test("test_graph_export.Determinism.test_edges_are_sorted") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val edges = jsonEdges(data("imports")); equal(edges, edges.sortBy(e => (e.head, e(1))))
      }
    }
    test("test_graph_export.Determinism.test_two_runs_are_byte_identical") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      for (kind <- List("citation", "imports"); format <- List("json", "dot"))
      equal(output(kind, format), output(kind, format), kind + "/" + format)
      }
    }
    test("test_graph_export.DotEscaping.test_a_markup_name_round_trips") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val raw = "\\<Gamma>\\<^sub>x"; equal(dotUnquote(dotToken(raw)), raw)
      }
    }
    test("test_graph_export.DotEscaping.test_a_quote_in_a_name_round_trips") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val raw = "a\"b"; equal(dotUnquote(dotToken(raw)), raw)
      }
    }
    test("test_graph_export.DotEscaping.test_the_dot_body_is_a_balanced_digraph") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val lines = output("imports", "dot").trim.linesIterator.toList
      check(lines.head.startsWith("digraph ")); equal(lines.head.trim.last, '{'); equal(lines.last, "}")
      lines.slice(1, lines.size - 1).foreach(line => check(line.trim.endsWith(";"), line))
      }
    }
    test("test_graph_export.DotEscaping.test_the_emitted_dot_carries_the_escaped_name") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val line = output("citation", "dot").linesIterator.find(_.contains("Gamma")).get; equal(dotUnquote(line.trim.stripSuffix(";")), "\\<Gamma>\\<^sub>x")
      }
    }
    test("test_graph_export.Scoping.test_a_theory_scope_narrows_the_export") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val d = jsonObject(cli(List("graph", "imports", "--theory", "Base"), root).out)
      equal(jsonNodes(d).map(_("name")), List("Base", "Main")); equal(jsonEdges(d), List(List("Base", "Main")))
      }
    }
    test("test_graph_export.Scoping.test_kind_defaults_to_citation") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      equal(cli(List("graph"), root).out, output("citation", "json")); equal(data()("kind"), "citation")
      }
    }
    test("test_graph_export.Scoping.test_the_format_choices_are_constrained") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      equal(cli(List("graph", "--format", "yaml"), root).exit, 2)
      }
    }
    test("test_graph_export.TheJsonShape.test_a_citation_edge_is_caller_then_callee") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      check(jsonEdges(data()).contains(List("mid_fact", "helper")))
      }
    }
    test("test_graph_export.TheJsonShape.test_an_import_edge_is_importer_then_imported") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      check(jsonEdges(data("imports")).contains(List("Mid", "Base")))
      }
    }
    test("test_graph_export.TheJsonShape.test_an_out_of_project_import_is_a_flagged_node_not_a_dropped_one") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val d = data("imports")
      equal(jsonNodes(d).filter(_.get("external").contains(true)).map(_("name")), List("HOL-Library.FuncSet", "Main"))
      check(jsonEdges(d).contains(List("Mid", "HOL-Library.FuncSet"))); check(jsonEdges(d).contains(List("Base", "Main")))
      }
    }
    test("test_graph_export.TheJsonShape.test_citation_nodes_carry_their_locus") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      val node = jsonNodes(data()).find(_("name") == "helper").get; equal((node("theory"), node("tag")), ("Base", "LEMMA"))
      }
    }
    test("test_graph_export.TheJsonShape.test_citation_output_is_valid_json") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      equal(data()("kind"), "citation")
      }
    }
    test("test_graph_export.TheJsonShape.test_every_edge_endpoint_is_a_declared_node") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      List("citation", "imports").foreach { kind =>
      note(s"kind=$kind")
      val d = data(kind); val names = jsonNodes(d).map(_("name")).toSet
      equal(jsonEdges(d).flatten.filterNot(names).toSet, Set.empty[String], kind)
      }
      }
    }
    test("test_graph_export.TheJsonShape.test_imports_output_is_valid_json") {
      withProject(GraphFixtures.files("test_graph_export.TREE")) { root =>
      def output(kind: String = "citation", format: String = "json") = cli(List("graph", kind, "--format", format), root).out
      def data(kind: String = "citation") = jsonObject(output(kind))
      equal(data("imports")("kind"), "imports")
      }
    }
  }
}
