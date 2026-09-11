package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_root_flag {
  def run(): Unit = {
    test("test_cli_root_flag.RootFlagDiscoverable.test_help_wording_is_position_agnostic") {

    locally {
notContains(capture((o,e)=>CLI.top_help(o)).out,"precede the subcommand")
    }
    }
    test("test_cli_root_flag.RootFlagDiscoverable.test_listed_in_each_subcommand_help") {

    locally {
for(c<-List("methods","summary","deps","show","grep","shape")) {val cmd=command(c); val h=capture((o,e)=>if(cmd.subs.isEmpty) CLI.cmd_help(o,cmd) else CLI.group_help(o,cmd)).out; contains(h,"--root"); contains(h,"-R") }
    }
    }
    test("test_cli_root_flag.RootFlagDiscoverable.test_listed_in_nested_shape_verb_help") {

    locally {
contains(capture((o,e)=>CLI.cmd_help(o,command("shape").subs.find(_.names.contains("census")).get,"shape ")).out,"--root")
    }
    }
    test("test_cli_root_flag.RootFlagPositions.test_absent_is_none") {

    locally {
withProject(Seq("T.thy" -> "theory T imports Pure begin\nend\n")) { root =>
 for(a <- List(List("methods"))) {
 var observed: Option[JPath]=None
 val result=capture((out,err)=>{
  val rc=CLI.run_result(a.map(x=>if(x=="ROOT")root.toString else x),out,err,s=>{s.env=_=>None; s.ambient_root=()=>root; s.validate_explicit_root=p=>observed=Some(p)})
  equal(rc,0)
 })
 equal(observed,None)
 }
}
    }
    }
    test("test_cli_root_flag.RootFlagPositions.test_before_and_after_agree") {

    locally {
withProject(Seq("T.thy" -> "theory T imports Pure begin\nend\n")) { root =>
 for(a <- List(List("-R", "ROOT", "methods"), List("methods", "-R", "ROOT"))) {
 var observed: Option[JPath]=None
 val result=capture((out,err)=>{
  val rc=CLI.run_result(a.map(x=>if(x=="ROOT")root.toString else x),out,err,s=>{s.env=_=>None; s.ambient_root=()=>root; s.validate_explicit_root=p=>observed=Some(p)})
  equal(rc,0)
 })
 equal(observed,Some(Discovery.real(root)))
 }
}
    }
    }
    test("test_cli_root_flag.RootFlagPositions.test_nested_shape_both_positions") {

    locally {
withProject(Seq("T.thy" -> "theory T imports Pure begin\nend\n")) { root =>
 for(a <- List(List("-R", "ROOT", "shape", "census"), List("shape", "census", "-R", "ROOT"))) {
 var observed: Option[JPath]=None
 val result=capture((out,err)=>{
  val rc=CLI.run_result(a.map(x=>if(x=="ROOT")root.toString else x),out,err,s=>{s.env=_=>None; s.ambient_root=()=>root; s.validate_explicit_root=p=>observed=Some(p)})
  equal(rc,0)
 })
 equal(observed,Some(Discovery.real(root)))
 }
}
    }
    }
    test("test_cli_root_flag.RootFlagPositions.test_top_position_survives_suppress_default") {

    locally {
withProject(Seq("T.thy" -> "theory T imports Pure begin\nend\n")) { root =>
 for(a <- List(List("-R", "ROOT", "shape", "census"))) {
 var observed: Option[JPath]=None
 val result=capture((out,err)=>{
  val rc=CLI.run_result(a.map(x=>if(x=="ROOT")root.toString else x),out,err,s=>{s.env=_=>None; s.ambient_root=()=>root; s.validate_explicit_root=p=>observed=Some(p)})
  equal(rc,0)
 })
 equal(observed,Some(Discovery.real(root)))
 }
}
    }
    }
  }
}
