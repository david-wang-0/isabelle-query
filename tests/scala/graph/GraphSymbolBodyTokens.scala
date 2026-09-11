package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphSymbolBodyTokens {
  def run(): Unit = {
    test("test_symbol_body_tokens.ASymbolBodyIsNotACitation.test_close_is_not_cited_by_a_cartouche_delimiter") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.SYMBOLS")))).callers
      equal(callers("close"), Set.empty[String])
    }
    test("test_symbol_body_tokens.ASymbolBodyIsNotACitation.test_lambda_is_not_cited_by_a_lambda_symbol") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.SYMBOLS")))).callers
      equal(callers("lambda"), Set.empty[String])
    }
    test("test_symbol_body_tokens.ASymbolBodyIsNotACitation.test_le_is_not_cited_by_a_le_symbol") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.SYMBOLS")))).callers
      equal(callers("le"), Set.empty[String])
    }
    test("test_symbol_body_tokens.ASymbolBodyIsNotACitation.test_sub_is_not_cited_by_a_subscript_control") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.SYMBOLS")))).callers
      equal(callers("sub"), Set.empty[String])
    }
    test("test_symbol_body_tokens.TheGuardsThatWordScanningExistsFor.test_a_bare_name_abutting_a_symbol_is_still_found") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.GUARDS")))).callers
      check(callers("iso_transaction").contains("abuts"))
    }
    test("test_symbol_body_tokens.TheGuardsThatWordScanningExistsFor.test_a_name_inside_a_cartouche_is_still_cited") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.GUARDS")))).callers
      check(callers("inside").contains("in_cartouche"))
    }
    test("test_symbol_body_tokens.TheGuardsThatWordScanningExistsFor.test_a_symbolic_name_is_still_one_token") {
      val callers = Usage_Graph.build_call_graph(List(parse(GraphFixtures.text("test_symbol_body_tokens.GUARDS")))).callers
      check(callers("merge_rt_F\\<^sub>m").contains("symbolic"))
    }
    test("test_symbol_body_tokens.TheSingleNameScanAgrees.test_a_name_abutting_a_symbol_still_matches") {
      check(matches("iso_transaction","have \"iso_transaction\\<^sub>h\""))
    }
    test("test_symbol_body_tokens.TheSingleNameScanAgrees.test_a_name_inside_a_cartouche_still_matches") {
      check(matches("foo","using \\<open>foo\\<close>"))
    }
    test("test_symbol_body_tokens.TheSingleNameScanAgrees.test_a_symbol_body_does_not_match") {
      check(!matches("lambda","have \"\\<lambda>x. x\""))
      check(!matches("le","have \"a \\<le> b\""))
      check(!matches("sub","have \"x\\<^sub>1 = y\""))
      check(!matches("open","using \\<open>foo\\<close>"))
    }
    test("test_symbol_body_tokens.TheSingleNameScanAgrees.test_a_symbolic_name_is_unaffected") {
      check(matches("\\<gamma>","using \\<gamma> by simp"))
      check(!matches("\\<gamma>","using \\<gamma>\\<^sub>1 by simp"))
    }
    test("test_symbol_body_tokens.TheSingleNameScanAgrees.test_the_real_name_still_matches") {
      check(matches("lambda","using lambda by simp"))
      check(matches("le","by (rule le)"))
    }
  }
}
