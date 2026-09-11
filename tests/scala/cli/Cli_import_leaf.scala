package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_import_leaf {
  def run(): Unit = {
    test("test_import_leaf.APathSpelledImportResolves.test_a_bare_name_finds_a_path_spelled_theory") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("Leaf",idx),Some("Leaf"))}
    }
    }
    disposition("test_import_leaf.APathSpelledImportResolves.test_a_bare_name_finds_a_path_spelled_theory", "known-difference", "D15 in dev/DIVERGENCES.md: ROOT path spellings name theories by their Isabelle leaf; assert leaf identity and preserve path resolution/import reachability instead of Python Sub/Leaf identity.")
    test("test_import_leaf.APathSpelledImportResolves.test_a_deep_path_finds_its_leaf") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("a/b/c/Base",idx),Some("Base"))}
    }
    }
    test("test_import_leaf.APathSpelledImportResolves.test_a_relative_path_finds_its_leaf") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("../Base",idx),Some("Base"))}
    }
    }
    test("test_import_leaf.APathSpelledImportResolves.test_a_session_qualified_import_still_resolves_by_its_tail") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("Other.Base",idx),Some("Base"))}
    }
    }
    test("test_import_leaf.APathSpelledImportResolves.test_the_exact_name_still_wins") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("Sub/Leaf",idx),Some("Leaf"))}
    }
    }
    disposition("test_import_leaf.APathSpelledImportResolves.test_the_exact_name_still_wins", "known-difference", "D15 in dev/DIVERGENCES.md: ROOT path spellings name theories by their Isabelle leaf; assert leaf identity and preserve path resolution/import reachability instead of Python Sub/Leaf identity.")
    test("test_import_leaf.ASharedNameUnionsItsEdges.test_both_citations_survive") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(callers("a_target").contains("cites_a"));check(callers("b_target").contains("cites_b"))}
    }
    }
    test("test_import_leaf.ASharedNameUnionsItsEdges.test_the_closure_reaches_both_targets") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(closure("Cite"),Set("Cite","Dup","A_Target","B_Target"))}
    }
    }
    test("test_import_leaf.ASharedNameUnionsItsEdges.test_the_union_is_deterministic") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal((closure("Dup")-"Dup").toList.sorted,List("A_Target","B_Target"));equal((Reach.closure(ss.reverse).visible("Dup","A_Target"),Reach.closure(ss.reverse).visible("Dup","B_Target")),(true,true))}
    }
    }
    test("test_import_leaf.ExternalStaysExternal.test_a_library_import_is_still_none") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("HOL-Library.FuncSet",idx),None)}
    }
    }
    test("test_import_leaf.ExternalStaysExternal.test_a_path_naming_nothing_loaded_is_still_none") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("../nowhere/Absent",idx),None)}
    }
    }
    test("test_import_leaf.ExternalStaysExternal.test_a_theory_reaching_nothing_in_project_is_still_filtered") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(!callers("base").contains("alien_uses"))}
    }
    }
    test("test_import_leaf.ExternalStaysExternal.test_main_is_still_none") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(Usage.resolve_import("Main",idx),None)}
    }
    }
    test("test_import_leaf.TheClosureCrossesTheEdge.test_the_bare_importer_reaches_transitively") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(closure("Bare"),Set("Bare","Leaf","Base"))}
    }
    }
    disposition("test_import_leaf.TheClosureCrossesTheEdge.test_the_bare_importer_reaches_transitively", "known-difference", "D15 in dev/DIVERGENCES.md: ROOT path spellings name theories by their Isabelle leaf; assert leaf identity and preserve path resolution/import reachability instead of Python Sub/Leaf identity.")
    test("test_import_leaf.TheClosureCrossesTheEdge.test_the_bare_importers_citation_survives") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(callers("base").contains("bare_uses"))}
    }
    }
    test("test_import_leaf.TheClosureCrossesTheEdge.test_the_filter_now_drops_nothing_it_should_not") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(callers("base"),Set("leaf_uses","bare_uses"))}
    }
    }
    test("test_import_leaf.TheClosureCrossesTheEdge.test_the_path_importer_reaches_its_target") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(closure("Sub/Leaf").contains("Base"))}
    }
    }
    test("test_import_leaf.TheClosureCrossesTheEdge.test_the_path_importers_citation_survives") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(callers("base").contains("leaf_uses"))}
    }
    }
    test("test_import_leaf.TheFixtureReallyCollides.test_the_last_wins_index_keeps_only_one") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(idx.keys.count(_=="Dup"),1)}
    }
    }
    test("test_import_leaf.TheFixtureReallyCollides.test_two_sections_share_the_name_Dup") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;val ds=ss.filter(_.theory=="Dup");equal(ds.size,2);check(ds(0).path!=ds(1).path)}
    }
    }
    test("test_import_leaf.TheFixtureReallyUsesBothSpellings.test_the_root_path_becomes_the_theory_name") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(idx.contains("Leaf"));check(!idx.contains("Sub/Leaf"));equal(idx("Leaf").real_path,Discovery.real(root.resolve("Sub/Leaf.thy")))}
    }
    }
    disposition("test_import_leaf.TheFixtureReallyUsesBothSpellings.test_the_root_path_becomes_the_theory_name", "known-difference", "D15 in dev/DIVERGENCES.md: ROOT path spellings name theories by their Isabelle leaf; assert leaf identity and preserve path resolution/import reachability instead of Python Sub/Leaf identity.")
    test("test_import_leaf.TheFixtureReallyUsesBothSpellings.test_the_subdirectory_theory_is_loaded") {

    locally {
withProject(CliFixtures.test_import_leaf_LEAF_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(idx.keySet,Set("Base","Leaf","Bare","Alien"))}
    }
    }
    disposition("test_import_leaf.TheFixtureReallyUsesBothSpellings.test_the_subdirectory_theory_is_loaded", "known-difference", "D15 in dev/DIVERGENCES.md: ROOT path spellings name theories by their Isabelle leaf; assert leaf identity and preserve path resolution/import reachability instead of Python Sub/Leaf identity.")
    test("test_import_leaf.UnionOnlyWidens.test_an_unrelated_name_is_still_filtered") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;equal(closure("A_Target"),Set("A_Target"))}
    }
    }
    test("test_import_leaf.UnionOnlyWidens.test_name_mode_is_unchanged") {

    locally {
withProject(CliFixtures.test_import_leaf_UNION_FILES.toSeq){root=>val ss=load(root);val idx=Usage_Graph.sections_by_theory(ss);def callers(n:String,reach:String="closure"):Set[String]=Usage_Graph.build_call_graph(ss,reach=reach).callers.getOrElse(n,Set.empty);def closure(n:String):Set[String]=ss.map(_.theory).distinct.filter(Reach.closure(ss).visible(n,_)).toSet;check(callers("a_target",reach="name").contains("cites_a"));check(callers("b_target",reach="name").contains("cites_b"))}
    }
    }
  }
}
