package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_deps_qualified {
  def run(): Unit = {
    test("test_deps_qualified.DepsQualified.test_external_import_alone_is_out_of_project") {

    locally {
withProject(CliFixtures.test_deps_qualified_TREE.toSeq){root=>val ss=load(root);def deps(n:String,reverse:Boolean=false,recursive:Boolean=false):String=capture((o,e)=>Usage.cmd_deps(o,e,ss,n,reverse,recursive)).out;val oop=lines(deps("EncodingWrap")).filter(_.contains("[out-of-project]"));equal(oop.size,1);contains(oop.head,"HOL-Library.FuncSet")}
    }
    }
    test("test_deps_qualified.DepsQualified.test_fixture_loads_both_theories") {

    locally {
withProject(CliFixtures.test_deps_qualified_TREE.toSeq){root=>val ss=load(root);def deps(n:String,reverse:Boolean=false,recursive:Boolean=false):String=capture((o,e)=>Usage.cmd_deps(o,e,ss,n,reverse,recursive)).out;equal(ss.map(_.theory).toSet,Set("Substrate","EncodingWrap"))}
    }
    }
    test("test_deps_qualified.DepsQualified.test_qualified_import_is_direct") {

    locally {
withProject(CliFixtures.test_deps_qualified_TREE.toSeq){root=>val ss=load(root);def deps(n:String,reverse:Boolean=false,recursive:Boolean=false):String=capture((o,e)=>Usage.cmd_deps(o,e,ss,n,reverse,recursive)).out;val out=deps("EncodingWrap");contains(out,"Substrate");contains(out,"[direct]");notContains(out,"Proj_Base.Substrate")}
    }
    }
    test("test_deps_qualified.DepsQualified.test_recursive_forward_reaches_qualified_child") {

    locally {
withProject(CliFixtures.test_deps_qualified_TREE.toSeq){root=>val ss=load(root);def deps(n:String,reverse:Boolean=false,recursive:Boolean=false):String=capture((o,e)=>Usage.cmd_deps(o,e,ss,n,reverse,recursive)).out;val out=deps("EncodingWrap",recursive=true);contains(out,"Substrate");contains(out,"[direct]")}
    }
    }
    test("test_deps_qualified.DepsQualified.test_reverse_lists_qualified_importer") {

    locally {
withProject(CliFixtures.test_deps_qualified_TREE.toSeq){root=>val ss=load(root);def deps(n:String,reverse:Boolean=false,recursive:Boolean=false):String=capture((o,e)=>Usage.cmd_deps(o,e,ss,n,reverse,recursive)).out;val out=deps("Substrate",reverse=true);contains(out,"EncodingWrap");notContains(out,"No in-project theory imports")}
    }
    }
    test("test_deps_qualified.DepsQualified.test_reverse_recursive_lists_importer") {

    locally {
withProject(CliFixtures.test_deps_qualified_TREE.toSeq){root=>val ss=load(root);def deps(n:String,reverse:Boolean=false,recursive:Boolean=false):String=capture((o,e)=>Usage.cmd_deps(o,e,ss,n,reverse,recursive)).out;contains(deps("Substrate",reverse=true,recursive=true),"EncodingWrap")}
    }
    }
    test("test_deps_qualified.ResolveImportUnit.test_bare_same_session") {

    locally {
equal(Reach.resolve_import("Substrate",Set("Substrate","EncodingWrap")), Some("Substrate"))
    }
    }
    test("test_deps_qualified.ResolveImportUnit.test_external_bare_is_none") {

    locally {
equal(Reach.resolve_import("Main",Set("Substrate","EncodingWrap")), None)
    }
    }
    test("test_deps_qualified.ResolveImportUnit.test_external_qualified_is_none") {

    locally {
equal(Reach.resolve_import("HOL-Library.FuncSet",Set("Substrate","EncodingWrap")), None)
    }
    }
    test("test_deps_qualified.ResolveImportUnit.test_qualified_cross_session_resolves_by_tail") {

    locally {
equal(Reach.resolve_import("Proj_Base.Substrate",Set("Substrate","EncodingWrap")), Some("Substrate"))
    }
    }
  }
}
