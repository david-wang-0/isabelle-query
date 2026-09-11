package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_locus_format {
  def run(): Unit = {
    test("test_locus_format.CallersFormat.test_context_line_keeps_marker_and_round_trips") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val marked=printed_loci(_callers("foo",context=2),context=true).filter(_.startsWith("Calls:"));check(marked.nonEmpty);for(t<-marked)check(locus(t).nonEmpty)
    }
    }
    test("test_locus_format.CallersFormat.test_match_locus_is_clean_and_round_trips") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val bar=_entry("bar");val ls=printed_loci(_callers("foo")).filter(_.startsWith("Calls:"));equal(ls.size,1);val loc=ls.head;check(!loc.endsWith(":"));val (file,lo,hi)=locus(loc).get;equal(file,"Calls");equal(Some(lo),hi);check(bar.thy_line<=lo && lo<=bar.thy_end)
    }
    }
    test("test_locus_format.CallersFormat.test_no_dangling_match_colon_anywhere") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
check(!matches(_callers("foo"),"Calls:\\d+:"))
    }
    }
    test("test_locus_format.CallersFormat.test_old_bracket_form_gone") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
check(!_callers("foo").contains("[in "))
    }
    }
    test("test_locus_format.CallersFormat.test_owner_field_carries_tag_and_span") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val bar = _entry("bar")
val out = _callers("foo")
check(out.contains(("bar (LEMMA) " + bar.thy_line.toString + ".." + bar.thy_end.toString)))
    }
    }
    test("test_locus_format.GrepFormat.test_default_owner_has_no_span") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val (foo,bar) = (_entry("foo"),_entry("bar"))
val out = _grep("foo")
check(out.contains("foo (LEMMA)"))
check(out.contains("bar (LEMMA)"))
check(!out.contains(("foo (LEMMA) " + foo.thy_line.toString + "..")))
check(!out.contains(("bar (LEMMA) " + bar.thy_line.toString + "..")))
    }
    }
    test("test_locus_format.GrepFormat.test_names_mode_owner_has_no_span") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val bar = _entry("bar")
val out = _grep("foo",mode = "names")
check(out.contains("bar (LEMMA)"))
check(!out.contains(("bar (LEMMA) " + bar.thy_line.toString + "..")))
    }
    }
    test("test_locus_format.MethodsFormat.test_default_mode_owner_field") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val foo=_entry("foo");val out=_methods("simp");contains(out,s"foo (LEMMA) ${foo.thy_line}..${foo.thy_end}");notContains(out,"[in ");check(!matches(out,"Calls:\\d+:"))
    }
    }
    test("test_locus_format.MethodsFormat.test_names_mode_uses_same_owner_field") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val bar = _entry("bar")
val out = _methods("simp",mode = "names")
check(out.contains(("bar (LEMMA) " + bar.thy_line.toString + ".." + bar.thy_end.toString)))
    }
    }
    test("test_locus_format.OwnerFieldSpan.test_no_owner_is_dash_either_way") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
equal(Commands.owner_field(None),"—");equal(Commands.owner_field(None,span=false),"—")
    }
    }
    test("test_locus_format.OwnerFieldSpan.test_span_default_on") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
val foo=_entry("foo");equal(Commands.owner_field(Some(foo)),s"foo (LEMMA) ${foo.thy_line}..${foo.thy_end}")
    }
    }
    test("test_locus_format.OwnerFieldSpan.test_span_off_drops_extent") {
val sec=parse(CliFixtures.test_locus_format_CALLS,"Calls","Calls.thy")
def _entry(n:String):Entry=entry(sec,n)
def _callers(n:String,context:Int=0):String=capture((o,e)=>Usage.cmd_callers(o,e,List(sec),n,Flags(context=context))).out
def _methods(n:String,mode:String="first"):String=capture((o,e)=>Usage.cmd_methods(o,e,List(sec),Some(n),Flags(mode=mode))).out
def _grep(p:String,mode:String="first"):String=capture((o,e)=>Commands.cmd_grep(o,e,List(sec),p,Flags(mode=mode))).out
    locally {
equal(Commands.owner_field(Some(_entry("foo")),span=false),"foo (LEMMA)")
    }
    }
  }
}
