package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphUnusedCascadeDepth {
  def run(): Unit = {
    test("test_unused_cascade_depth.CascadeDepth.test_a_chain_gets_one_level_per_link") {
      val g = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_unused_cascade_depth.CHAIN"), "Cascade")))
      val d = Usage.compute_unused_recursive(g, Set.empty)
      equal(d, Map("a_top" -> 0, "b_mid" -> 1, "c_leaf" -> 2))
    }
    test("test_unused_cascade_depth.CascadeDepth.test_a_fork_levels_both_arms_together") {
      val g = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_unused_cascade_depth.CHAIN"), "Cascade")))
      val d = Usage.compute_unused_recursive(g, Set.empty)
      equal(Usage.compute_unused_recursive(Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_unused_cascade_depth.FORK"), "Fork"))), Set.empty), Map("root" -> 0, "joint" -> 1, "left_leaf" -> 2, "right_leaf" -> 2))
    }
    test("test_unused_cascade_depth.CascadeDepth.test_depth_is_one_more_than_the_deepest_caller") {
      val g = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_unused_cascade_depth.CHAIN"), "Cascade")))
      val d = Usage.compute_unused_recursive(g, Set.empty)
      d.foreach { case (name, depth) =>
      val callers = g.callers.getOrElse(name, Set.empty)
      if (callers.isEmpty) equal(depth, 0, name) else equal(depth, 1 + callers.map(d).max, name)
      }
    }
    test("test_unused_cascade_depth.CascadeDepth.test_the_unused_set_is_unchanged_by_levelling") {
      val g = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_unused_cascade_depth.CHAIN"), "Cascade")))
      val d = Usage.compute_unused_recursive(g, Set.empty)
      equal(d.keySet, Set("a_top", "b_mid", "c_leaf"))
    }
  }
}
