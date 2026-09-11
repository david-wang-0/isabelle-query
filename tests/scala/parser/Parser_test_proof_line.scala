package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_proof_line.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_proof_line {
private def h_entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def h_names(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
return V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (true) Vector(c2("e").field("name")) else Vector.empty })
V.none
}
private def h_RoadmapBoundary_contents(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return V.seq(h_entry(e, Vector(e("sec"), e("name")), Map()).field("roadmap").seq.flatMap { item => val c3 = e.clone(); val unpack4 = item; TestSupport.equal(unpack4.seq.size, 2, "unpack arity"); c3("_") = unpack4.at(V(0)); c3("c") = unpack4.at(V(1)); if (true) Vector(c3("c")) else Vector.empty })
V.none
}
private def case_OneLiner_test_proof_on_the_declaration_line(): Unit = TestSupport.test("test_proof_line.OneLiner.test_proof_on_the_declaration_line") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo [simp]: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(2), "tests/test_proof_line.py:51")
}
private def case_OneLiner_test_proof_on_the_statements_continuation_line(): Unit = TestSupport.test("test_proof_line.OneLiner.test_proof_on_the_statements_continuation_line") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"True\" by auto\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(3), "tests/test_proof_line.py:60")
}
private def case_OneLiner_test_proof_after_the_term_closes(): Unit = TestSupport.test("test_proof_line.OneLiner.test_proof_after_the_term_closes") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"True \\<Longrightarrow>\n   True\" by (simp_all)\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(4), "tests/test_proof_line.py:70")
}
private def case_OneLiner_test_dot_proof(): Unit = TestSupport.test("test_proof_line.OneLiner.test_dot_proof") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\" ..\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(2), "tests/test_proof_line.py:77")
}
private def case_BlankLineBeforeTheProof_test_blank_between_statement_and_proof(): Unit = TestSupport.test("test_proof_line.BlankLineBeforeTheProof.test_blank_between_statement_and_proof") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"True\"\n\nproof -\n  show \"True\" by simp\nqed\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(5), "tests/test_proof_line.py:93")
}
private def case_BlankLineBeforeTheProof_test_blank_inside_a_multi_line_term(): Unit = TestSupport.test("test_proof_line.BlankLineBeforeTheProof.test_blank_inside_a_multi_line_term") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"x = (do {\n     let n = 1;\n\n     return n\n  })\"\n\n  unfolding foo_def by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(9), "tests/test_proof_line.py:108")
}
private def case_BlankLineBeforeTheProof_test_blank_between_assumptions(): Unit = TestSupport.test("test_proof_line.BlankLineBeforeTheProof.test_blank_between_assumptions") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  assumes a1: \"True\"\n\n  and a2: \"True\"\n  shows \"True\"\n  proof -\n    show \"True\" by simp\n  qed\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(7), "tests/test_proof_line.py:123")
}
private def case_DoesNotOverrun_test_a_following_lemma_is_still_its_own_entry(): Unit = TestSupport.test("test_proof_line.DoesNotOverrun.test_a_following_lemma_is_still_its_own_entry") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma one:\n  \"True\"\n\nlemma two: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("one"), V("two")), "tests/test_proof_line.py:136")
}
private def case_DoesNotOverrun_test_a_note_carrying_the_terms_closing_quote(): Unit = TestSupport.test("test_proof_line.DoesNotOverrun.test_a_note_carrying_the_terms_closing_quote") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem one:\n  shows \"True\"\n    and \"True\n      \\<comment> \\<open>a note\\<close>\"\nproof -\n  show \"True\" by simp\n  show \"True\" by simp\nqed\n\nlemma two: \"True\" by simp\nlemma three: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_names(e, Vector(e("sec")), Map()), vs(V("one"), V("two"), V("three")), "tests/test_proof_line.py:165")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("one")), Map()).field("proof_line"), V(6), "tests/test_proof_line.py:166")
}
private def case_DoesNotOverrun_test_a_proof_keyword_inside_a_term_is_not_the_proof(): Unit = TestSupport.test("test_proof_line.DoesNotOverrun.test_a_proof_keyword_inside_a_term_is_not_the_proof") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"apply f x = using g y\"\n  by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(4), "tests/test_proof_line.py:175")
}
private def case_DoesNotOverrun_test_statement_rendering_still_stops_at_the_blank(): Unit = TestSupport.test("test_proof_line.DoesNotOverrun.test_statement_rendering_still_stops_at_the_blank") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"True\"\n\nproof -\n  show \"True\" by simp\nqed\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("decl_end_line"), V(3), "tests/test_proof_line.py:188")
}
private def case_RoadmapBoundary_test_note_on_a_one_line_proof(): Unit = TestSupport.test("test_proof_line.RoadmapBoundary.test_note_on_a_one_line_proof") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  \"True\"\n  by (simp) \\<comment> \\<open>two simps is faster\\<close>\nend\n")), Map())
TestSupport.equal(h_RoadmapBoundary_contents(e, Vector(e("sec"), V("foo")), Map()), vs(V("two simps is faster")), "tests/test_proof_line.py:210")
}
private def case_RoadmapBoundary_test_note_deeper_in_the_proof_still_attaches(): Unit = TestSupport.test("test_proof_line.RoadmapBoundary.test_note_deeper_in_the_proof_still_attaches") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\"\nproof -\n  show \"True\" by simp \\<comment> \\<open>the real work\\<close>\nqed\nend\n")), Map())
TestSupport.equal(h_RoadmapBoundary_contents(e, Vector(e("sec"), V("foo")), Map()), vs(V("the real work")), "tests/test_proof_line.py:220")
}
private def case_RoadmapBoundary_test_note_on_the_statement_is_not_a_roadmap_step(): Unit = TestSupport.test("test_proof_line.RoadmapBoundary.test_note_on_the_statement_is_not_a_roadmap_step") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  assumes a: \"True\" \\<comment> \\<open>for a sound system\\<close>\n  shows \"True\"\n  by simp\nend\n")), Map())
TestSupport.equal(h_RoadmapBoundary_contents(e, Vector(e("sec"), V("foo")), Map()), vs(), "tests/test_proof_line.py:232")
}
private def case_TermTracking_test_proof_on_the_line_the_term_closes(): Unit = TestSupport.test("test_proof_line.TermTracking.test_proof_on_the_line_the_term_closes") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo:\n  shows \"True \\<and>\n     True\" proof -\n  show \"True \\<and> True\"\n  proof (induct x)\n  qed auto\nqed\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(4), "tests/test_proof_line.py:260")
}
private def case_TermTracking_test_a_cartouche_body_with_a_blank_line(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_cartouche_body_with_a_blank_line") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nfun f :: \\<open>nat \\<Rightarrow> nat\\<close> where\n  \\<open>f a =\n    do {\n      let x = a;\n\n      return x\n    }\\<close>\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("f")), Map()).field("decl_end_line"), V(8), "tests/test_proof_line.py:278")
}
private def case_TermTracking_test_a_rule_list_spaced_out_with_blank_lines(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_rule_list_spaced_out_with_blank_lines") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ninductive_set S :: \"nat set\"\nwhere\n    a: \"0 \\<in> S\"\n\n  | b: \"1 \\<in> S\"\n\n\n  | c: \"2 \\<in> S\"\n\nlemma later: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("S")), Map()).field("decl_end_line"), V(9), "tests/test_proof_line.py:303")
}
private def case_TermTracking_test_a_type_declaration_reads_its_body(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_type_declaration_reads_its_body") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nrecord state =\n  ip :: \"nat\"\n  sn :: \"nat\"\n\nlemma later: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("state")), Map()).field("decl_end_line"), V(4), "tests/test_proof_line.py:323")
}
private def case_TermTracking_test_a_datatype_body_stops_at_the_next_command(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_datatype_body_stops_at_the_next_command") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndatatype t =\n    A nat\n  | B bool\nlemma later: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("t")), Map()).field("decl_end_line"), V(4), "tests/test_proof_line.py:332")
}
private def case_TermTracking_test_a_blank_still_ends_a_declaration_no_bar_follows(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_blank_still_ends_a_declaration_no_bar_follows") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndefinition d :: \"nat\" where\n  \"d = 0\"\n\nadhoc_overloading Monad_Syntax.bind \\<rightleftharpoons> d\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("d")), Map()).field("decl_end_line"), V(3), "tests/test_proof_line.py:347")
}
private def case_TermTracking_test_a_comment_between_rules_is_stepped_over(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_comment_between_rules_is_stepped_over") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ninductive_set S :: \"nat set\"\nwhere a: \"0 \\<in> S\"\n\n  (* justified in the closed-system proof *)\n  | b: \"1 \\<in> S\"\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("S")), Map()).field("decl_end_line"), V(6), "tests/test_proof_line.py:367")
}
private def case_TermTracking_test_the_lookahead_stops_at_outer_syntax(): Unit = TestSupport.test("test_proof_line.TermTracking.test_the_lookahead_stops_at_outer_syntax") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nabbreviation h :: \"nat\" where\n  \"h \\<equiv> 0\"\n\n(**********)\nsubsubsection \\<open>Later\\<close>\n\ninductive_set S :: \"nat set\"\nwhere a: \"0 \\<in> S\"\n  | b: \"1 \\<in> S\"\nend\n")), Map())
e("e") = h_entry(e, Vector(e("sec"), V("h")), Map())
TestSupport.equal(e("e").field("decl_end_line"), V(3), "tests/test_proof_line.py:390")
TestSupport.check(e("e").field("decl_end_line").cmp("LtE", e("e").field("thy_end")), "tests/test_proof_line.py:391" + " actual=" + e("e").field("decl_end_line") + " expected=" + e("e").field("thy_end"))
}
private def case_TermTracking_test_a_blank_then_a_real_command_still_ends_it(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_blank_then_a_real_command_still_ends_it") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ninductive_set S :: \"nat set\"\nwhere a: \"0 \\<in> S\"\n\nlemma later: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("S")), Map()).field("decl_end_line"), V(3), "tests/test_proof_line.py:402")
}
private def case_TermTracking_test_a_text_block_between_rules_still_ends_it(): Unit = TestSupport.test("test_proof_line.TermTracking.test_a_text_block_between_rules_still_ends_it") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ninductive_set S :: \"nat set\"\nwhere a: \"0 \\<in> S\"\n\ntext \\<open>an aside\\<close>\n\n  | b: \"1 \\<in> S\"\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("S")), Map()).field("decl_end_line"), V(3), "tests/test_proof_line.py:415")
}
private def case_TermTracking_test_an_escaped_quote_does_not_open_a_term(): Unit = TestSupport.test("test_proof_line.TermTracking.test_an_escaped_quote_does_not_open_a_term") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"s = CHR 0x22 @ \\\"x\\\"\"\n  by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("proof_line"), V(3), "tests/test_proof_line.py:424")
}
def run(): Unit = { case_OneLiner_test_proof_on_the_declaration_line(); case_OneLiner_test_proof_on_the_statements_continuation_line(); case_OneLiner_test_proof_after_the_term_closes(); case_OneLiner_test_dot_proof(); case_BlankLineBeforeTheProof_test_blank_between_statement_and_proof(); case_BlankLineBeforeTheProof_test_blank_inside_a_multi_line_term(); case_BlankLineBeforeTheProof_test_blank_between_assumptions(); case_DoesNotOverrun_test_a_following_lemma_is_still_its_own_entry(); case_DoesNotOverrun_test_a_note_carrying_the_terms_closing_quote(); case_DoesNotOverrun_test_a_proof_keyword_inside_a_term_is_not_the_proof(); case_DoesNotOverrun_test_statement_rendering_still_stops_at_the_blank(); case_RoadmapBoundary_test_note_on_a_one_line_proof(); case_RoadmapBoundary_test_note_deeper_in_the_proof_still_attaches(); case_RoadmapBoundary_test_note_on_the_statement_is_not_a_roadmap_step(); case_TermTracking_test_proof_on_the_line_the_term_closes(); case_TermTracking_test_a_cartouche_body_with_a_blank_line(); case_TermTracking_test_a_rule_list_spaced_out_with_blank_lines(); case_TermTracking_test_a_type_declaration_reads_its_body(); case_TermTracking_test_a_datatype_body_stops_at_the_next_command(); case_TermTracking_test_a_blank_still_ends_a_declaration_no_bar_follows(); case_TermTracking_test_a_comment_between_rules_is_stepped_over(); case_TermTracking_test_the_lookahead_stops_at_outer_syntax(); case_TermTracking_test_a_blank_then_a_real_command_still_ends_it(); case_TermTracking_test_a_text_block_between_rules_still_ends_it(); case_TermTracking_test_an_escaped_quote_does_not_open_a_term() }
}
