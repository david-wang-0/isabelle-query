package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_largest {
  def run(): Unit = {
    test("test_largest.CmdLargest.test_empty_sections_report_nothing") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
check(_run_largest(List(),top = 20).contains("No entries found."))
    }
    }
    test("test_largest.CmdLargest.test_ranks_by_span_descending") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
val secs=sections_from(Map("Big"->BIG,"Small"->SMALL));val ns=_ranked_names(_run_largest(secs));check(ns.contains("huge"));check(ns.contains("tiny"));check(ns.indexOf("huge")<ns.indexOf("tiny"))
    }
    }
    test("test_largest.CmdLargest.test_top_caps_the_row_count") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
val out=_run_largest(sections_from(Map("Big"->BIG,"Small"->SMALL)),top=1);equal(_ranked_names(out),List("huge"));contains(out,"Top 1 largest entries")
    }
    }
    test("test_largest.LargestFileSemantics.test_directory_with_root_uses_root_theories") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
withProject(Map("T1.thy" -> "theory T1 imports Main begin\nlemma T1_l: \"True\" by simp\nend\n", "T2.thy" -> "theory T2 imports Main begin\nlemma T2_l: \"True\" by simp\nend\n", "T3.thy" -> "theory T3 imports Main begin\nlemma T3_l: \"True\" by simp\nend\n", "ROOT" -> "session S = HOL +\n  theories\n    T1\n    T2\n").toSeq){root=>val ss=fileSections(root,List("DIR").map(x=>if(x=="DIR")root.toString else root.resolve(x).toString));equal(ss.map(_.theory).toSet,List("T1", "T2").toSet)}
    }
    }
    test("test_largest.LargestFileSemantics.test_directory_without_root_recursive_glob") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
withProject(Map("sub/One.thy" -> "theory One imports Main begin\nlemma One_l: \"True\" by simp\nend\n", "sub/deep/Two.thy" -> "theory Two imports Main begin\nlemma Two_l: \"True\" by simp\nend\n").toSeq){root=>val ss=fileSections(root,List("DIR").map(x=>if(x=="DIR")root.toString else root.resolve(x).toString));equal(ss.map(_.theory).toSet,List("One", "Two").toSet)}
    }
    }
    test("test_largest.LargestFileSemantics.test_files_are_deduped_by_resolved_path") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
withProject(Map("A.thy" -> "theory A imports Main begin\nlemma A_l: \"True\" by simp\nend\n").toSeq){root=>val ss=fileSections(root,List("A.thy", "A.thy").map(x=>if(x=="DIR")root.toString else root.resolve(x).toString));equal(ss.map(_.theory),List("A"))}
    }
    }
    test("test_largest.LargestFileSemantics.test_union_of_explicit_files") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
withProject(Map("A.thy" -> "theory A imports Main begin\nlemma A_l: \"True\" by simp\nend\n", "B.thy" -> "theory B imports Main begin\nlemma B_l: \"True\" by simp\nend\n").toSeq){root=>val ss=fileSections(root,List("A.thy", "B.thy").map(x=>if(x=="DIR")root.toString else root.resolve(x).toString));equal(ss.map(_.theory).toSet,List("A", "B").toSet)}
    }
    }
    test("test_largest.LargestParser.test_defaults") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
equal(args("largest").pos("files"),Nil)
val text="theory Many imports Main begin\n"+(1 to 21).map(i=>s"lemma item$i: \"True\" by simp\n").mkString+"end\n"
withProject(Seq("Many.thy"->text)){root=>val r=query(root,"largest");equal(r.exit,0);equal(_ranked_names(r.out).size,20);contains(r.out,"Top 20 largest entries")}
    }
    }
    test("test_largest.LargestParser.test_long_top_flag") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
val n=args("largest","--top","3");equal(n.str("top"),Some("3"));equal(n.pos("files"),Nil)
    }
    }
    test("test_largest.LargestParser.test_top_flag_and_files") {
def _run_largest(sections:List[Theory_Section],top:Int=20):String=capture((o,e)=>Commands.cmd_largest(o,sections,top)).out
def _ranked_names(output:String):List[String]=lines(output).map(_.trim.split("\\s+").toList).collect {case n::tag::name::rest if n.matches("[0-9]+")=>name}
def sections_from(src:Map[String,String]):List[Theory_Section]=src.toList.map((n,s)=>parse(s,n,n+".thy"))
val BIG=CliFixtures.test_largest_BIG
val SMALL=CliFixtures.test_largest_SMALL
    locally {
val n=args("largest","-N","5","a.thy","b.thy");equal(n.str("top"),Some("5"));equal(n.pos("files"),List("a.thy","b.thy"));equal(command("largest").names.head,"largest")
    }
    }
  }
}
