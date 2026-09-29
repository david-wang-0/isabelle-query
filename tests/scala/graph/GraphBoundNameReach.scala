package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

/** Upstream `tests/test_bound_name_reach.py` (0.9.1): a name an entry BINDS
  * is a declaration of it, for reachability [bound-name-reach] (D14 here,
  * adopted upstream).  The single-name text scan counts an entry's bound names
  * as declarations; the bulk call graph does not.  Upstream's `_Visibility`
  * is `Reach` here: `declared_in` without bound names is the entry index
  * (`Usage_Graph.entry_by_name`), and with them `Reach.declaring_theories`. */
object GraphBoundNameReach {
  val X: String = "theory X imports Main begin\n  datatype colour = Bar | Baz\n  locale rev = fixes r :: nat\nend\n"
  // Z imports X, so its `Bar` can be X's constructor.
  val Z: String = "theory Z imports X begin\n  lemma z_mentions_bar: \"Bar = Bar\" by simp\nend\n"
  // W does not import X: its `Bar` is something else of the same spelling.
  val W: String = "theory W imports Main begin\n  lemma w_mentions_bar: \"Bar = Bar\" by simp\nend\n"

  def fixture(body: List[Theory_Section] => Unit): Unit =
    withProject(Seq("X.thy" -> X, "Z.thy" -> Z, "W.thy" -> W,
      "ROOT" -> "session Demo = HOL +\n  theories\n    X\n    Z\n    W\n")) { root => body(load(root)) }
  def theories_citing(ss: List[Theory_Section], name: String, reach: String = "closure"): Set[String] =
    Usage.find_callers(ss, name, reach = reach).map(_._1.theory).toSet

  def run(): Unit = {
    test("test_bound_name_reach.ABoundNameIsADeclaration.test_the_fixture_binds_bar_without_an_entry") {
      // Non-vacuity: `Bar` must be a BOUND name, not an entry.
      fixture { ss =>
        val names = ss.flatMap(_.entries).map(_.name).toSet
        val bound = ss.flatMap(_.entries).flatMap(_.bound_names).toSet
        check(!names("Bar"))
        check(bound("Bar"))
      }
    }
    test("test_bound_name_reach.ABoundNameIsADeclaration.test_callers_of_a_constructor_are_scoped") {
      fixture { ss => equal(theories_citing(ss, "Bar"), Set("Z")) }
    }
    test("test_bound_name_reach.ABoundNameIsADeclaration.test_name_mode_restores_the_unscoped_answer") {
      fixture { ss => equal(theories_citing(ss, "Bar", reach = "name"), Set("Z", "W")) }
    }
    test("test_bound_name_reach.ABoundNameIsADeclaration.test_declared_in_knows_the_binder_only_when_asked") {
      fixture { ss =>
        check(!Usage_Graph.entry_by_name(ss).contains("Bar"))
        equal(Reach.declaring_theories(ss, "Bar"), List("X"))
        // An entry is declared where it was before, either way.
        equal(Usage_Graph.entry_by_name(ss).get("colour").map(_._1), Some("X"))
        equal(Reach.declaring_theories(ss, "colour"), List("X"))
      }
    }
    test("test_bound_name_reach.TheFacadeDecidesPerTheory.test_a_bound_name_is_admitted_where_it_is_visible") {
      fixture { ss =>
        val admits = Reach.site_filter(ss, "Bar")
        check(admits("X")); check(admits("Z")); check(!admits("W"))
      }
    }
    test("test_bound_name_reach.TheFacadeDecidesPerTheory.test_an_entry_is_admitted_the_same_way") {
      fixture { ss =>
        val admits = Reach.site_filter(ss, "colour")
        check(admits("Z")); check(!admits("W"))
      }
    }
    test("test_bound_name_reach.TheFacadeDecidesPerTheory.test_name_mode_admits_everything") {
      fixture { ss => check(Reach.site_filter(ss, "Bar", reach = "name")("W")) }
    }
    test("test_bound_name_reach.TheFacadeDecidesPerTheory.test_a_name_declared_nowhere_admits_everything") {
      // Nothing to scope to, so nothing is dropped -- and no closure is built
      // to find that out.
      fixture { ss =>
        Reach.clear_cache()
        val admits = Reach.site_filter(ss, "nothing_declares_this")
        check(admits("W"))
        check(admits("No_Such_Theory"))
        equal(Reach.cached_closures, 0)
      }
    }
    test("test_bound_name_reach.TheBulkGraphIsUntouched.test_a_bound_name_is_not_a_node") {
      fixture { ss =>
        val g = Usage_Graph.build_call_graph(ss)
        check(!g.all_names("Bar"))
        check(!g.callers.contains("Bar"))
      }
    }
    test("test_bound_name_reach.TheBulkGraphIsUntouched.test_the_edge_set_is_the_hand_computed_one") {
      // Nothing in the fixture cites an ENTRY by name: `Bar = Bar` names a
      // constructor, and `colour` / `rev` are written nowhere but their own
      // declarations.  So the graph has no edges at all, under both modes.
      fixture { ss =>
        for (reach <- List("closure", "name"))
          subcase(s"reach=$reach") { equal(edges(Usage_Graph.build_call_graph(ss, reach = reach)), Set.empty[(String, String)]) }
      }
    }
  }
}
