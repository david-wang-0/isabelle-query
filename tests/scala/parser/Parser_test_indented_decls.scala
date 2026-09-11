package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_indented_decls.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_indented_decls {
private def h_names(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
return V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty })
V.none
}
private def h_SpanBoundaries_span(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
e("sec") = ParserBridge.call("section_from", Vector(e("snippet")), Map())
e("e") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("x") = item; if (V((c2("x").field("name").cmp("Eq", c2("name")))).truth) Vector(c2("x")) else Vector.empty })), Map())
return vs(e("e").field("src_start"), e("e").field("thy_end"))
V.none
}
private def case_Indented_test_inside_a_context_block(): Unit = TestSupport.test("test_indented_decls.Indented.test_inside_a_context_block") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ncontext foo begin\n  definition g where \"g x = x\"\n  lemma bar: \"g x = x\" by (simp add: g_def)\nend\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("g"), V("bar")), "tests/test_indented_decls.py:45")
}
private def case_Indented_test_at_theory_top_level_with_no_locale(): Unit = TestSupport.test("test_indented_decls.Indented.test_at_theory_top_level_with_no_locale") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\n  abbreviation \"assert_opt P \\<equiv> P\"\n  lemma foo: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("assert_opt"), V("foo")), "tests/test_indented_decls.py:54")
}
private def case_Indented_test_deeply_indented(): Unit = TestSupport.test("test_indented_decls.Indented.test_deeply_indented") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlocale L begin\ncontext begin\n        lemma deep: \"True\" by simp\nend\nend\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("L"), V("deep")), "tests/test_indented_decls.py:68")
}
private def case_Indented_test_after_an_inline_comment_on_the_same_line(): Unit = TestSupport.test("test_indented_decls.Indented.test_after_an_inline_comment_on_the_same_line") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\n(* TODO: rename *)definition g where \"g x = x\"\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("g")), "tests/test_indented_decls.py:80")
}
private def case_Indented_test_the_name_is_read_from_the_raw_line(): Unit = TestSupport.test("test_indented_decls.Indented.test_the_name_is_read_from_the_raw_line") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\n  definition \"lift_opt m e \\<equiv> m\"\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("lift_opt")), "tests/test_indented_decls.py:92")
}
private def case_NotACommand_test_a_keyword_inside_a_term_is_not_a_declaration(): Unit = TestSupport.test("test_indented_decls.NotACommand.test_a_keyword_inside_a_term_is_not_a_declaration") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"P x =\n   lemma_like y\"\n  by simp\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("foo")), "tests/test_indented_decls.py:105")
}
private def case_NotACommand_test_a_declaration_inside_a_comment_is_not_a_declaration(): Unit = TestSupport.test("test_indented_decls.NotACommand.test_a_declaration_inside_a_comment_is_not_a_declaration") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\" by simp\n(* superseded:\n   definition old where \"old = 1\"\n*)\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("foo")), "tests/test_indented_decls.py:114")
}
private def case_NotACommand_test_an_ml_fun_is_not_a_declaration(): Unit = TestSupport.test("test_indented_decls.NotACommand.test_an_ml_fun_is_not_a_declaration") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nML \\<open>\n  fun mk_thm ctxt = ctxt\n\\<close>\nlemma foo: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("foo")), "tests/test_indented_decls.py:125")
}
private def case_NotACommand_test_prose_in_a_text_block_is_not_a_declaration(): Unit = TestSupport.test("test_indented_decls.NotACommand.test_prose_in_a_text_block_is_not_a_declaration") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntext \\<open>\nlemma of the day: be careful\n\\<close>\nlemma foo: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("foo")), "tests/test_indented_decls.py:134")
}
private def case_DoesNotOverrun_test_an_indented_declaration_ends_the_previous_statement(): Unit = TestSupport.test("test_indented_decls.DoesNotOverrun.test_an_indented_declaration_ends_the_previous_statement") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ncontext foo begin\n  lemma one:\n    \"True\"\n\n  lemma two: \"True\" by simp\n  lemma three: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("one"), V("two"), V("three")), "tests/test_indented_decls.py:161")
}
private def case_DoesNotOverrun_test_an_indented_definition_ends_the_previous_one(): Unit = TestSupport.test("test_indented_decls.DoesNotOverrun.test_an_indented_definition_ends_the_previous_one") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ncontext foo begin\n  definition g :: \"nat \\<Rightarrow> nat\"\n    where \"g x = x\"\n  definition h :: \"nat \\<Rightarrow> nat\"\n    where \"h x = x\"\nend\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("g"), V("h")), "tests/test_indented_decls.py:172")
}
private def case_SpanBoundaries_test_an_indented_end_closes_the_span(): Unit = TestSupport.test("test_indented_decls.SpanBoundaries.test_an_indented_end_closes_the_span") {
val e: Env = scala.collection.mutable.Map.empty


val unpack3 = h_SpanBoundaries_span(e, Vector(V("theory A imports Main begin\nlocale L begin\n  context begin\n    lemma foo: \"True\" by simp\n  end\n  lemma bar: \"True\" by simp\nend\nend\n"), V("foo")), Map()); TestSupport.equal(unpack3.seq.size, 2, "unpack arity"); e("_start") = unpack3.at(V(0)); e("end") = unpack3.at(V(1))
TestSupport.equal(e("end"), V(4), "tests/test_indented_decls.py:204")
}
private def case_SpanBoundaries_test_prose_beginning_with_a_command_word_is_not_a_boundary(): Unit = TestSupport.test("test_indented_decls.SpanBoundaries.test_prose_beginning_with_a_command_word_is_not_a_boundary") {
val e: Env = scala.collection.mutable.Map.empty


val unpack4 = h_SpanBoundaries_span(e, Vector(V("theory A imports Main begin\ndefinition g where \"g x = x\"\ntext \\<open>\ncontext is discharged in the proof below, and\nlemmas are stated without complex inferences\n\\<close>\nend\n"), V("g")), Map()); TestSupport.equal(unpack4.seq.size, 2, "unpack arity"); e("_start") = unpack4.at(V(0)); e("end") = unpack4.at(V(1))
TestSupport.check(e("end").cmp("GtE", V(5)), "tests/test_indented_decls.py:217" + " actual=" + e("end") + " expected=" + V(5))
}
private def case_SpanBoundaries_test_verbatim_code_is_not_a_boundary(): Unit = TestSupport.test("test_indented_decls.SpanBoundaries.test_verbatim_code_is_not_a_boundary") {
val e: Env = scala.collection.mutable.Map.empty


val unpack5 = h_SpanBoundaries_span(e, Vector(V("theory A imports Main begin\ndefinition g where \"g x = x\"\ntext \\<open>\n\\<^verbatim>\\<open>\ntypedef struct foo { int x; };\n\\<close>\n\\<close>\nend\n"), V("g")), Map()); TestSupport.equal(unpack5.seq.size, 2, "unpack arity"); e("_start") = unpack5.at(V(0)); e("end") = unpack5.at(V(1))
TestSupport.check(e("end").cmp("GtE", V(5)), "tests/test_indented_decls.py:231" + " actual=" + e("end") + " expected=" + V(5))
}
def run(): Unit = { case_Indented_test_inside_a_context_block(); case_Indented_test_at_theory_top_level_with_no_locale(); case_Indented_test_deeply_indented(); case_Indented_test_after_an_inline_comment_on_the_same_line(); case_Indented_test_the_name_is_read_from_the_raw_line(); case_NotACommand_test_a_keyword_inside_a_term_is_not_a_declaration(); case_NotACommand_test_a_declaration_inside_a_comment_is_not_a_declaration(); case_NotACommand_test_an_ml_fun_is_not_a_declaration(); case_NotACommand_test_prose_in_a_text_block_is_not_a_declaration(); case_DoesNotOverrun_test_an_indented_declaration_ends_the_previous_statement(); case_DoesNotOverrun_test_an_indented_definition_ends_the_previous_one(); case_SpanBoundaries_test_an_indented_end_closes_the_span(); case_SpanBoundaries_test_prose_beginning_with_a_command_word_is_not_a_boundary(); case_SpanBoundaries_test_verbatim_code_is_not_a_boundary() }
}
