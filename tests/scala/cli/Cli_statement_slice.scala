package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_statement_slice {
  def run(): Unit = {
    test("test_statement_slice.CliSurface.test_both_spellings_set_statement") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
for(s<-List("--statement","--stmt")) check(args("find","X",s).bool("statement"))
    }
    }
    test("test_statement_slice.CliSurface.test_find_allows_statement_with_verbatim") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val n=args("find","X","--statement","-V");check(n.bool("statement"));check(n.bool("verbatim"))
    }
    }
    test("test_statement_slice.CliSurface.test_show_rejects_statement_with_verbatim") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
withProject(Nil){root=>equal(query(root,"show","X","-V","--statement").exit,2)}
    }
    }
    test("test_statement_slice.CliSurface.test_show_statement_default_is_false") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
check(!args("show","X").bool("statement"))
    }
    }
    test("test_statement_slice.FindStatementMatch.test_count_mode_reflects_statement_matches") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
equal(_find("widget",true,mode = "count").trim,"2")
equal(_find("widget",false,mode = "count").trim,"1")
    }
    }
    test("test_statement_slice.FindStatementMatch.test_name_search_misses_statement_only_reference") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val out = _find("widget",statement = false)
check(names(out).contains("widget"))
check(!names(out).contains("size_bound"))
    }
    }
    test("test_statement_slice.FindStatementMatch.test_statement_is_match_only_not_render") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val out = _find("widget",statement = true,mode = "all")
check(out.contains(PROOF_LINE))
    }
    }
    test("test_statement_slice.FindStatementMatch.test_statement_search_does_not_match_proof_body") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
equal(names(_find("assms",statement = true)),List())
    }
    }
    test("test_statement_slice.FindStatementMatch.test_statement_search_finds_statement_only_reference") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val out = _find("widget",statement = true)
check(names(out).contains("size_bound"))
check(names(out).contains("widget"))
check(!names(out).contains("comm_add"))
    }
    }
    test("test_statement_slice.RenderEntryPrecedence.test_statement_narrower_than_verbatim_if_both_set") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val out = Render.render_entry(sec,entry0,statement = true,verbatim = true)
check(!out.contains(PROOF_LINE))
    }
    }
    test("test_statement_slice.RenderEntryPrecedence.test_statement_text_helper_is_the_declaration") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val text = Render.statement_text(sec,entry0)
check(text.contains("lemma size_bound:"))
check(!text.contains(PROOF_LINE))
    }
    }
    test("test_statement_slice.ShowStatementRender.test_default_render_keeps_the_proof") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
check(_show("size_bound").contains(PROOF_LINE))
    }
    }
    test("test_statement_slice.ShowStatementRender.test_statement_render_does_not_change_matching") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
equal(names(_show("nonesuch",statement = true)),List())
    }
    }
    test("test_statement_slice.ShowStatementRender.test_statement_render_drops_the_proof") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
val out = _show("size_bound",statement = true)
check(out.contains("lemma size_bound:"))
check(out.contains("shows \"x \\<noteq> 0\""))
check(!out.contains(PROOF_LINE))
    }
    }
    test("test_statement_slice.ShowStatementRender.test_verbatim_keeps_the_proof") {
val sec=parse(CliFixtures.test_statement_slice_THY,"Slice","Slice.thy")
val secs=List(sec)
val entry0=entry(sec,"size_bound")
val PROOF_LINE="using assms by auto"
def _find(pat:String,statement:Boolean,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pat,Flags(mode=mode,statement=statement))).out
def _show(name:String,statement:Boolean=false,verbatim:Boolean=false):String=capture((o,e)=>Commands.cmd_show(o,secs,name,Flags(statement=statement,verbatim=verbatim))).out
    locally {
check(_show("size_bound",verbatim = true).contains(PROOF_LINE))
    }
    }
  }
}
