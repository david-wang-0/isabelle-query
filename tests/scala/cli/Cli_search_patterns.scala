package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_search_patterns {
  def run(): Unit = {
    test("test_search_patterns.FindBySymbolName.test_a_markup_prefix_finds_every_entry_sharing_it") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val out = _find(secs,"split\\<^sub>i")
for (name <- List("split\\<^sub>i_tree","split\\<^sub>i_list","split\\<^sub>i_tree_smeq")) {
check(out.contains(name))
}
    }
    }
    test("test_search_patterns.FindBySymbolName.test_a_non_caret_symbol_still_works") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
check(_find(secs,"alpha\\<alpha>").contains("alpha\\<alpha>"))
    }
    }
    test("test_search_patterns.FindBySymbolName.test_plain_patterns_are_unaffected") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val out = _find(secs,"plain")
check(out.contains("plain_name"))
check(!out.contains("split\\<^sub>i_tree ("))
    }
    }
    test("test_search_patterns.FindBySymbolName.test_regex_still_applies_around_the_markup") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val out = _find(secs,"split\\<^sub>i.*_smeq")
check(out.contains("split\\<^sub>i_tree_smeq"))
check(!out.contains("split\\<^sub>i_list ("))
    }
    }
    test("test_search_patterns.FindBySymbolName.test_the_printed_name_finds_the_entry") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val out = _find(secs,"split\\<^sub>i_tree")
check(out.contains("split\\<^sub>i_tree"))
    }
    }
    test("test_search_patterns.InvalidPattern.test_find_reports_a_bad_regex_and_exits_2") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
equal(capture((o,e)=>Commands.cmd_find(o,e,secs,"(",Flags(mode="names"))).exit,2)
    }
    }
    test("test_search_patterns.UserPattern.test_a_pattern_without_markup_is_untouched") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
for (p <- List("foo.*bar","^anchored","a|b","[A-Z]\\w+")) {
equal(Commands.user_pattern(p),p)
}
    }
    }
    test("test_search_patterns.UserPattern.test_both_rewrites_compose") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val p=Commands.user_pattern("split\\<^sub>i_tree\\|plain");check(matches("split\\<^sub>i_tree",p));check(matches("plain_name",p))
    }
    }
    test("test_search_patterns.UserPattern.test_grep_style_alternation_is_still_rewritten") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
equal(Commands.user_pattern("a\\|b"),"a|b")
    }
    }
    test("test_search_patterns.UserPattern.test_markup_is_escaped_so_the_caret_is_not_an_anchor") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val out=Commands.user_pattern("split\\<^sub>i"); notContains(out.replace("\\^",""),"^");check(matches("split\\<^sub>i_tree",out))
    }
    }
    test("test_search_patterns.UserPattern.test_regex_syntax_around_the_markup_still_works") {
val secs=List(parse(CliFixtures.test_search_patterns_THY,"Sym","Sym.thy"))
def _find(secs:List[Theory_Section],pattern:String,mode:String="names"):String=capture((o,e)=>Commands.cmd_find(o,e,secs,pattern,Flags(mode=mode))).out
    locally {
val p=Commands.user_pattern("split\\<^sub>i.*_smeq");check(matches("split\\<^sub>i_tree_smeq",p));check(!matches("split\\<^sub>i_tree",p))
    }
    }
  }
}
