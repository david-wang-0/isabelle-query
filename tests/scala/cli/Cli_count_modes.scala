package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_count_modes {
  def run(): Unit = {
    test("test_count_modes.AnHonestZeroPrintsNothingInNamesMode.test_find_names_with_no_match") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("find","zzz","names").out.trim,"")
    }
    }
    test("test_count_modes.AnHonestZeroPrintsNothingInNamesMode.test_show_names_with_no_match") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("show","zzz","names").out.trim,"")
    }
    }
    test("test_count_modes.AnHonestZeroPrintsZero.test_find_with_no_match") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("find","zzz","count").out.trim,"0")
    }
    }
    test("test_count_modes.AnHonestZeroPrintsZero.test_show_with_no_match") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("show","zzz","count").out.trim,"0")
    }
    }
    test("test_count_modes.AnHonestZeroPrintsZero.test_the_count_is_parseable_as_a_number") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
for(p<-List("zzz","d")){val out=emit("find",p,"count").out.trim;equal(out.toInt.toString,out)}
    }
    }
    test("test_count_modes.AnHonestZeroPrintsZero.test_unused_when_nothing_is_unused") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("unused","zzz","count").out.trim,"0")
    }
    }
    test("test_count_modes.AnUnknownSubjectIsNotZero.test_callees_of_a_nonexistent_entry") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
val r=emit("callees","zzz","count");equal(r.exit,1);equal(r.out,"");contains(r.err,"not in the entry index")
    }
    }
    test("test_count_modes.AnUnknownSubjectIsNotZero.test_callers_scans_and_so_may_truthfully_be_zero") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("callers","zzz","count").out.trim,"0")
    }
    }
    test("test_count_modes.AnUnknownSubjectIsNotZero.test_methods_for_a_name_that_is_no_method") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
val r=emit("methods","zzz","count");equal(r.exit,1);equal(r.out,"");contains(r.err,"proof method")
    }
    }
    test("test_count_modes.AnUnknownSubjectIsNotZero.test_refs_of_a_nonexistent_theory") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
val r=emit("refs","zzz","count");equal(r.exit,1);equal(r.out,"");contains(r.err,"no theory 'zzz'")
    }
    }
    test("test_count_modes.TheHumanModesStillSaySo.test_a_real_count_is_unchanged") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("find","d","count").out.trim,"1")
    }
    }
    test("test_count_modes.TheHumanModesStillSaySo.test_the_all_mode_still_explains") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("find","zzz","all").out.trim,"No entries matching 'zzz'.")
    }
    }
    test("test_count_modes.TheHumanModesStillSaySo.test_the_default_mode_still_explains") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("find","zzz","first").out.trim,"No entries matching 'zzz'.")
    }
    }
    test("test_count_modes.TheHumanModesStillSaySo.test_unused_still_explains") {
val ss=List(parse(CliFixtures.test_count_modes_THY))
def emit(c:String,p:String="zzz",mode:String="first"):CliResult=capture((o,e)=>c match {
case "find"=>Commands.cmd_find(o,e,ss,p,Flags(mode=mode))
case "show"=>Commands.cmd_show(o,ss,p,Flags(mode=mode))
case "unused"=>Usage.cmd_unused(o,e,Nil,Flags(mode=mode))
case "callees"=>Usage.cmd_callees(o,e,ss,p,Flags(mode=mode))
case "refs"=>Usage.cmd_refs(o,e,ss,p,Flags(mode=mode))
case "methods"=>Usage.cmd_methods(o,e,ss,Some(p),Flags(mode=mode))
case "callers"=>Usage.cmd_callers(o,e,ss,p,Flags(mode=mode))
})
    locally {
equal(emit("unused","zzz","first").out.trim,"No unused entries found.")
    }
    }
  }
}
