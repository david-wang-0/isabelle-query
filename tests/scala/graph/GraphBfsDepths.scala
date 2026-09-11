package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphBfsDepths {
  def run(): Unit = {
    test("test_bfs_depths.BfsDepthsUnit.test_callback_is_lazy_and_called_once_per_node") {
      val graph = Map("a" -> Set("b", "c"), "b" -> Set("d"), "c" -> Set("d"), "d" -> Set.empty[String])
      def nb(n: String) = graph.getOrElse(n, Set.empty[String])
      val calls = scala.collection.mutable.ListBuffer.empty[String]
      val adj = Map("a" -> List("b"), "b" -> List("c"), "c" -> Nil)
      val d = Usage_Graph.bfs_depths(n => { calls += n; adj.getOrElse(n, Nil) }, List("a"), -1)
      equal(d, Map("a" -> -1, "b" -> 0, "c" -> 1)); equal(calls.toList.sorted, List("a", "b", "c"))
    }
    test("test_bfs_depths.BfsDepthsUnit.test_cycle_is_safe") {
      val graph = Map("a" -> Set("b", "c"), "b" -> Set("d"), "c" -> Set("d"), "d" -> Set.empty[String])
      def nb(n: String) = graph.getOrElse(n, Set.empty[String])
      val cycle = Map("a" -> Set("b"), "b" -> Set("a")); equal(Usage_Graph.bfs_depths(cycle, Set("a")), Map("a" -> 0, "b" -> 1))
    }
    test("test_bfs_depths.BfsDepthsUnit.test_multi_seed_all_seeds_at_seed_depth") {
      val graph = Map("a" -> Set("b", "c"), "b" -> Set("d"), "c" -> Set("d"), "d" -> Set.empty[String])
      def nb(n: String) = graph.getOrElse(n, Set.empty[String])
      equal(Usage_Graph.bfs_depths(nb, Set("b", "c")), Map("b" -> 0, "c" -> 0, "d" -> 1))
    }
    test("test_bfs_depths.BfsDepthsUnit.test_seed_depth_minus_one_puts_direct_at_zero") {
      val graph = Map("a" -> Set("b", "c"), "b" -> Set("d"), "c" -> Set("d"), "d" -> Set.empty[String])
      def nb(n: String) = graph.getOrElse(n, Set.empty[String])
      equal(Usage_Graph.bfs_depths(nb, List("a"), -1), Map("a" -> -1, "b" -> 0, "c" -> 0, "d" -> 1))
    }
    test("test_bfs_depths.BfsDepthsUnit.test_seed_depth_zero_counts_the_seed") {
      val graph = Map("a" -> Set("b", "c"), "b" -> Set("d"), "c" -> Set("d"), "d" -> Set.empty[String])
      def nb(n: String) = graph.getOrElse(n, Set.empty[String])
      equal(Usage_Graph.bfs_depths(nb, Set("a")), Map("a" -> 0, "b" -> 1, "c" -> 1, "d" -> 2))
    }
    test("test_bfs_depths.BfsDepthsUnit.test_shortest_path_on_a_diamond") {
      val graph = Map("a" -> Set("b", "c"), "b" -> Set("d"), "c" -> Set("d"), "d" -> Set.empty[String])
      def nb(n: String) = graph.getOrElse(n, Set.empty[String])
      equal(Usage_Graph.bfs_depths(nb, Set("a"))("d"), 2)
    }
    test("test_bfs_depths.CallGraphTransitiveDepth.test_callees_depths_through_the_chain") {
      val g = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_bfs_depths.CallGraphTransitiveDepth.SRC"))))
      val d = Usage_Graph.bfs_depths(n => g.callees.getOrElse(n, Set.empty), Set("top"))
      equal(d.get("top"), Some(0))
      equal(d.get("mid"), Some(1))
      equal(d.get("base"), Some(2))
    }
    test("test_bfs_depths.CallGraphTransitiveDepth.test_callers_depths_through_the_chain") {
      val g = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_bfs_depths.CallGraphTransitiveDepth.SRC"))))
      val d = Usage_Graph.bfs_depths(n => g.callers.getOrElse(n, Set.empty), Set("base"))
      equal(d.get("base"), Some(0))
      equal(d.get("mid"), Some(1))
      equal(d.get("top"), Some(2))
    }
    test("test_bfs_depths.DepsDepthLabels.test_forward_non_recursive_is_direct_only") {
      withProject(GraphFixtures.files("test_bfs_depths._CHAIN")) { root =>
      val out = cli(List("deps", "A"), root).out
      contains(lineFor(out, "B"), "[direct]"); equal(lineFor(out, "C"), "")
      }
    }
    test("test_bfs_depths.DepsDepthLabels.test_forward_recursive_depth_labels") {
      withProject(GraphFixtures.files("test_bfs_depths._CHAIN")) { root =>
      val out = cli(List("deps", "A", "-r"), root).out
      contains(lineFor(out, "B"), "[direct]"); contains(lineFor(out, "C"), "[depth 1]")
      }
    }
    test("test_bfs_depths.DepsDepthLabels.test_reverse_recursive_depth_labels") {
      withProject(GraphFixtures.files("test_bfs_depths._CHAIN")) { root =>
      val out = cli(List("uses", "C", "-r"), root).out
      contains(lineFor(out, "B"), "[direct]"); contains(lineFor(out, "A"), "[depth 1]")
      }
    }
  }
}
