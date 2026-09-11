package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_summary_aggregate {
  def run(): Unit = {
    test("test_summary_aggregate.AggregateRender.test_by_session_table_and_total") {
def sec(n:String,s:Option[String],l:Int,d:Int=0,lem:Int=0,t:Int=0):Theory_Section={
 val es=(0 until d).map(i=>Entry("DEF",s"${n}_d$i","",0,theory=n))++(0 until lem).map(i=>Entry("LEMMA",s"${n}_l$i","",0,theory=n))++(0 until t).map(i=>Entry("THEOREM",s"${n}_t$i","",0,theory=n))
 new Theory_Section(n,Paths.get(n+".thy"),es.toList,Array.fill(l)(""),parse("").regions,session=s)
}
val sections=List(sec("A1",Some("Alpha"),10,d=2,lem=1),sec("A2",Some("Alpha"),20,lem=3,t=1),sec("B1",Some("Beta"),5,d=1))
def _render(ss:List[Theory_Section],by_session:Boolean=false,verbose:Boolean=false,totals_only:Boolean=false):String=capture((o,e)=>Commands.cmd_summary(o,ss,by_session,verbose,totals_only)).out
    locally {
val out = _render(sections,by_session = true)
check(out.contains("# Corpus summary"))
check(out.contains("8 entries"))
check(out.contains("35 source lines"))
check(out.contains("3 theories"))
check(out.contains("2 sessions"))
check(out.contains("| Alpha | 2 | 30 | 2 | 4 | 1 |"))
check(out.contains("| Beta | 1 | 5 | 1 | 0 | 0 |"))
check(out.contains("| **TOTAL** | 3 | 35 | 3 | 4 | 1 |"))
    }
    }
    test("test_summary_aggregate.AggregateRender.test_default_is_still_per_theory_table") {
def sec(n:String,s:Option[String],l:Int,d:Int=0,lem:Int=0,t:Int=0):Theory_Section={
 val es=(0 until d).map(i=>Entry("DEF",s"${n}_d$i","",0,theory=n))++(0 until lem).map(i=>Entry("LEMMA",s"${n}_l$i","",0,theory=n))++(0 until t).map(i=>Entry("THEOREM",s"${n}_t$i","",0,theory=n))
 new Theory_Section(n,Paths.get(n+".thy"),es.toList,Array.fill(l)(""),parse("").regions,session=s)
}
val sections=List(sec("A1",Some("Alpha"),10,d=2,lem=1),sec("A2",Some("Alpha"),20,lem=3,t=1),sec("B1",Some("Beta"),5,d=1))
def _render(ss:List[Theory_Section],by_session:Boolean=false,verbose:Boolean=false,totals_only:Boolean=false):String=capture((o,e)=>Commands.cmd_summary(o,ss,by_session,verbose,totals_only)).out
    locally {
val out = _render(sections)
check(out.contains("# Theory Index"))
check(out.contains("| Theory | Src | D | L | T | Key Exports |"))
    }
    }
    test("test_summary_aggregate.AggregateRender.test_no_session_bucket_labelled") {
def sec(n:String,s:Option[String],l:Int,d:Int=0,lem:Int=0,t:Int=0):Theory_Section={
 val es=(0 until d).map(i=>Entry("DEF",s"${n}_d$i","",0,theory=n))++(0 until lem).map(i=>Entry("LEMMA",s"${n}_l$i","",0,theory=n))++(0 until t).map(i=>Entry("THEOREM",s"${n}_t$i","",0,theory=n))
 new Theory_Section(n,Paths.get(n+".thy"),es.toList,Array.fill(l)(""),parse("").regions,session=s)
}
val sections=List(sec("A1",Some("Alpha"),10,d=2,lem=1),sec("A2",Some("Alpha"),20,lem=3,t=1),sec("B1",Some("Beta"),5,d=1))
def _render(ss:List[Theory_Section],by_session:Boolean=false,verbose:Boolean=false,totals_only:Boolean=false):String=capture((o,e)=>Commands.cmd_summary(o,ss,by_session,verbose,totals_only)).out
    locally {
contains(_render(List(sec("Lone",None,7,d=1)),by_session=true),"(no session)")
    }
    }
    test("test_summary_aggregate.AggregateRender.test_totals_only_suppresses_table") {
def sec(n:String,s:Option[String],l:Int,d:Int=0,lem:Int=0,t:Int=0):Theory_Section={
 val es=(0 until d).map(i=>Entry("DEF",s"${n}_d$i","",0,theory=n))++(0 until lem).map(i=>Entry("LEMMA",s"${n}_l$i","",0,theory=n))++(0 until t).map(i=>Entry("THEOREM",s"${n}_t$i","",0,theory=n))
 new Theory_Section(n,Paths.get(n+".thy"),es.toList,Array.fill(l)(""),parse("").regions,session=s)
}
val sections=List(sec("A1",Some("Alpha"),10,d=2,lem=1),sec("A2",Some("Alpha"),20,lem=3,t=1),sec("B1",Some("Beta"),5,d=1))
def _render(ss:List[Theory_Section],by_session:Boolean=false,verbose:Boolean=false,totals_only:Boolean=false):String=capture((o,e)=>Commands.cmd_summary(o,ss,by_session,verbose,totals_only)).out
    locally {
val out = _render(sections,totals_only = true)
check(out.contains("8 entries"))
check(!out.contains("| Session |"))
check(!out.contains("**TOTAL**"))
    }
    }
    test("test_summary_aggregate.AggregateRender.test_verbose_expands_theories") {
def sec(n:String,s:Option[String],l:Int,d:Int=0,lem:Int=0,t:Int=0):Theory_Section={
 val es=(0 until d).map(i=>Entry("DEF",s"${n}_d$i","",0,theory=n))++(0 until lem).map(i=>Entry("LEMMA",s"${n}_l$i","",0,theory=n))++(0 until t).map(i=>Entry("THEOREM",s"${n}_t$i","",0,theory=n))
 new Theory_Section(n,Paths.get(n+".thy"),es.toList,Array.fill(l)(""),parse("").regions,session=s)
}
val sections=List(sec("A1",Some("Alpha"),10,d=2,lem=1),sec("A2",Some("Alpha"),20,lem=3,t=1),sec("B1",Some("Beta"),5,d=1))
def _render(ss:List[Theory_Section],by_session:Boolean=false,verbose:Boolean=false,totals_only:Boolean=false):String=capture((o,e)=>Commands.cmd_summary(o,ss,by_session,verbose,totals_only)).out
    locally {
val out = _render(sections,by_session = true,verbose = true)
check(out.contains("## Alpha"))
check(out.contains("## Beta"))
check(out.contains("| A1 | 10 | 2 | 1 | 0 |"))
check(out.contains("| A2 | 20 | 0 | 3 | 1 |"))
check(!out.contains("**TOTAL**"))
    }
    }
    test("test_summary_aggregate.SessionAttribution.test_loader_tags_each_theory_with_its_session") {
def sec(n:String,s:Option[String],l:Int,d:Int=0,lem:Int=0,t:Int=0):Theory_Section={
 val es=(0 until d).map(i=>Entry("DEF",s"${n}_d$i","",0,theory=n))++(0 until lem).map(i=>Entry("LEMMA",s"${n}_l$i","",0,theory=n))++(0 until t).map(i=>Entry("THEOREM",s"${n}_t$i","",0,theory=n))
 new Theory_Section(n,Paths.get(n+".thy"),es.toList,Array.fill(l)(""),parse("").regions,session=s)
}
val sections=List(sec("A1",Some("Alpha"),10,d=2,lem=1),sec("A2",Some("Alpha"),20,lem=3,t=1),sec("B1",Some("Beta"),5,d=1))
def _render(ss:List[Theory_Section],by_session:Boolean=false,verbose:Boolean=false,totals_only:Boolean=false):String=capture((o,e)=>Commands.cmd_summary(o,ss,by_session,verbose,totals_only)).out
    locally {
withProject(Seq("ROOT"->"session Alpha = HOL +\n  theories\n    A1\n    A2\nsession Beta = HOL +\n  theories\n    B1\n","A1.thy"->"theory A1 imports Main begin\nend\n","A2.thy"->"theory A2 imports A1 begin\nend\n","B1.thy"->"theory B1 imports Main begin\nend\n")){root=>equal(load(root).map(s=>s.theory->s.session).toMap,Map("A1"->Some("Alpha"),"A2"->Some("Alpha"),"B1"->Some("Beta")))}
    }
    }
  }
}
