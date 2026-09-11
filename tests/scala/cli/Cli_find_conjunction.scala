package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_find_conjunction {
  def run(): Unit = {
    test("test_find_conjunction.ComposesWithTheOtherFlags.test_a_theory_scope_narrows_the_conjunction") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
val n=new CLI.Ns;n.appended("theory_scope")=scala.collection.mutable.ListBuffer("U");val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));val scoped=CLI.scope_to_theories(s,n,ss);val out=capture((o,e)=>Commands.cmd_find_and(o,e,scoped,patterns,Flags(mode="names"))).out;contains(out,"length_of_encode_entry_too");notContains(out,"— T ")
    }
    }
    test("test_find_conjunction.ComposesWithTheOtherFlags.test_count") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
equal(both(patterns,mode="count").trim,"2")
    }
    }
    test("test_find_conjunction.ComposesWithTheOtherFlags.test_names") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
equal(lines(both(patterns,mode="names").trim).size,2)
    }
    }
    test("test_find_conjunction.PatternsMayMatchDifferentParts.test_a_name_and_a_bound_name_intersect") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
contains(both(List("state","rreqs"),mode="names"),"state")
    }
    }
    test("test_find_conjunction.PatternsMayMatchDifferentParts.test_the_statement_slice_is_searched_when_asked") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
equal(lines(both(patterns,mode="names",statement=true).trim).map(_.split(" ",2).head).toSet,Set("stated","length_of_encode_entry","length_of_encode_entry_too"))
    }
    }
    test("test_find_conjunction.ProseObeysTheConjunctionToo.test_a_note_needs_every_pattern_on_it") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
val cs=List(parse("theory C imports Main begin\ntext \\<open>the length of an encode_entry\\<close>\ntext \\<open>the length of nothing\\<close>\nlemma both_words: \"True\" by simp\nend\n","C","C.thy"));val out=capture((o,e)=>Commands.cmd_find_and(o,e,cs,patterns,Flags(mode="names",with_comments=true))).out;contains(out,"encode_entry");notContains(out,"length of nothing")
    }
    }
    test("test_find_conjunction.TheDefaultIsStillDisjunction.test_one_pattern_with_the_flag_is_an_ordinary_find") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
val n=args("find","length","--and");check(n.bool("conjunction"));equal(n.pos("pattern").size,1)
    }
    }
    test("test_find_conjunction.TheDefaultIsStillDisjunction.test_without_the_flag_each_pattern_reports_separately") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
check(!args("find","a","b").bool("conjunction"))
    }
    }
    test("test_find_conjunction.TheFlagSpelling.test_the_dest_is_not_the_python_keyword") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
val n=args("find","x","--and");check(n.bool("conjunction"));check(!n.flags("and"));check(!n.values.contains("and"))
    }
    }
    test("test_find_conjunction.TheFlagSpelling.test_there_is_no_short_A_flag") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
raises(args("find","x","-A"))
    }
    }
    test("test_find_conjunction.TheIntersection.test_an_entry_matching_both_is_kept") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
contains(both(patterns,mode="names"),"length_of_encode_entry")
    }
    }
    test("test_find_conjunction.TheIntersection.test_an_entry_matching_only_one_is_dropped") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
val out=both(patterns,mode="names");notContains(out,"length_alone");notContains(out,"encode_entry_alone")
    }
    }
    test("test_find_conjunction.TheIntersection.test_no_common_entry_says_so_once") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
val out=both(List("length","zzzznope"));equal(countOf(out,"No entries matching"),1);contains(out,"length AND zzzznope")
    }
    }
    test("test_find_conjunction.TheIntersection.test_order_does_not_matter") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
equal(both(patterns,mode="names"),both(patterns.reverse,mode="names"))
    }
    }
    test("test_find_conjunction.TheIntersection.test_the_result_is_the_intersection_of_the_two_searches") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
equal(both(patterns,mode="names").split("\n").toSet-"",(either("length").split("\n").toSet intersect either("encode_entry").split("\n").toSet)-"")
    }
    }
    test("test_find_conjunction.TheIntersection.test_three_patterns_narrow_further") {
val ss=CliFixtures.test_find_conjunction_SRC.toList.map((n,t)=>parse(t,n,n+".thy"))
def both(ps:List[String],mode:String="first",statement:Boolean=false):String=capture((o,e)=>Commands.cmd_find_and(o,e,ss,ps,Flags(mode=mode,statement=statement))).out
def either(p:String):String=capture((o,e)=>Commands.cmd_find(o,e,ss,p,Flags(mode="names"))).out
val patterns=List("length","encode_entry")
    locally {
equal((both(patterns,mode="count").trim,both(patterns :+ "too",mode="count").trim),("2","1"))
    }
    }
  }
}
