package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_outer_source.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_outer_source {
private def h_outer(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return ParserBridge.call("section_from", Vector(e("snippet")), Map()).invoke("outer_source", Vector(), Map())
V.none
}
private def h_BlockBalance_depth(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
e("d") = V(0)
h_outer(e, Vector(e("snippet")), Map()).seq.foreach { item => e("line") = item;
e("line").invoke("split", Vector(), Map()).seq.foreach { item => e("tok") = item;
e("tok") = e("tok").invoke("strip", Vector(V("()[],")), Map())
if (V((e("tok").cmp("Eq", V("begin")))).truth) {
e("d") = e("d").plus(V(1))
} else {
if (V((e("tok").cmp("Eq", V("end")))).truth) {
e("d") = e("d").minus(V(1))
}
}
}
}
return e("d")
V.none
}
private def case_Shape_test_line_count_is_preserved(): Unit = TestSupport.test("test_outer_source.Shape.test_line_count_is_preserved") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")
e("SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIP")), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("sec").invoke("outer_source", Vector(), Map())), Map()), ParserBridge.call("len", Vector(e("sec").invoke("source", Vector(), Map())), Map()), "tests/test_outer_source.py:43")
}
private def case_Shape_test_every_column_is_preserved(): Unit = TestSupport.test("test_outer_source.Shape.test_every_column_is_preserved") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")
e("SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIP")), Map())
TestSupport.equal(V.seq(e("sec").invoke("outer_source", Vector(), Map()).seq.flatMap { item => val c1 = e.clone(); c1("l") = item; if (true) Vector(ParserBridge.call("len", Vector(c1("l")), Map())) else Vector.empty }), V.seq(e("sec").invoke("source", Vector(), Map()).seq.flatMap { item => val c2 = e.clone(); c2("l") = item; if (true) Vector(ParserBridge.call("len", Vector(c2("l")), Map())) else Vector.empty }), "tests/test_outer_source.py:47")
}
private def case_Shape_test_outer_is_a_subset_of_live(): Unit = TestSupport.test("test_outer_source.Shape.test_outer_is_a_subset_of_live") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")
e("SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIP")), Map())
ParserBridge.call("zip", Vector(e("sec").invoke("source", Vector(), Map()), e("sec").invoke("live_source", Vector(), Map()), e("sec").invoke("outer_source", Vector(), Map())), Map()).seq.foreach { item => val unpack3 = item; TestSupport.equal(unpack3.seq.size, 3, "unpack arity"); e("raw") = unpack3.at(V(0)); e("live") = unpack3.at(V(1)); e("out") = unpack3.at(V(2));
ParserBridge.call("zip", Vector(e("raw"), e("live"), e("out")), Map()).seq.foreach { item => val unpack4 = item; TestSupport.equal(unpack4.seq.size, 3, "unpack arity"); e("c_raw") = unpack4.at(V(0)); e("c_live") = unpack4.at(V(1)); e("c_out") = unpack4.at(V(2));
if (V((V((e("c_live").cmp("Eq", V(" ")))).truth) && (V((e("c_raw").cmp("NotEq", V(" ")))).truth)).truth) {
TestSupport.equal(e("c_out"), V(" "), "tests/test_outer_source.py:57")
}
}
}
}
private def case_WhatIsBlanked_test_a_quoted_term_goes(): Unit = TestSupport.test("test_outer_source.WhatIsBlanked.test_a_quoted_term_goes") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\nlemma foo: \"mono f\"\n  by simp\nend\n")), Map())
TestSupport.equal(e("got").at(V(1)), V("lemma foo: ").plus(V(" ").times(V(8))), "tests/test_outer_source.py:66")
}
private def case_WhatIsBlanked_test_a_cartouche_term_goes(): Unit = TestSupport.test("test_outer_source.WhatIsBlanked.test_a_cartouche_term_goes") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\nlemma foo: \\<open>mono f\\<close>\n  by simp\nend\n")), Map())
TestSupport.equal(e("got").at(V(1)).invoke("rstrip", Vector(), Map()), V("lemma foo:"), "tests/test_outer_source.py:73")
}
private def case_WhatIsBlanked_test_a_comment_goes_too(): Unit = TestSupport.test("test_outer_source.WhatIsBlanked.test_a_comment_goes_too") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\nlemma foo: \"x\"  (* why *)\n  by simp\nend\n")), Map())
TestSupport.check(!e("got").at(V(1)).has(V("why")), "tests/test_outer_source.py:81" + " actual=" + e("got").at(V(1)))
}
private def case_WhatIsBlanked_test_a_multi_line_term_is_blanked_on_every_line(): Unit = TestSupport.test("test_outer_source.WhatIsBlanked.test_a_multi_line_term_is_blanked_on_every_line") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\nlemma foo:\n  \"mono f \\<Longrightarrow>\n   mono g\"\n  by simp\nend\n")), Map())
TestSupport.equal(e("got").at(V(2)).invoke("strip", Vector(), Map()), V(""), "tests/test_outer_source.py:90")
TestSupport.equal(e("got").at(V(3)).invoke("strip", Vector(), Map()), V(""), "tests/test_outer_source.py:91")
}
private def case_WhatIsBlanked_test_a_keyword_inside_a_term_is_blanked(): Unit = TestSupport.test("test_outer_source.WhatIsBlanked.test_a_keyword_inside_a_term_is_blanked") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\nlemma foo: \"the lemma begin end thing\"\n  by simp\nend\n")), Map())
TestSupport.equal(e("got").at(V(1)).invoke("split", Vector(V("\"")), Map()).at(V(0)).invoke("strip", Vector(), Map()), V("lemma foo:"), "tests/test_outer_source.py:100")
TestSupport.check(!e("got").at(V(1)).slice(V(11), V.none).has(V("begin")), "tests/test_outer_source.py:101" + " actual=" + e("got").at(V(1)).slice(V(11), V.none))
}
private def case_WhatSurvives_test_the_command_keyword_survives(): Unit = TestSupport.test("test_outer_source.WhatSurvives.test_the_command_keyword_survives") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\nlemma foo: \"x\" by simp\nend\n")), Map())
TestSupport.check(e("got").at(V(1)).has(V("lemma foo:")), "tests/test_outer_source.py:111" + " actual=" + e("got").at(V(1)))
TestSupport.check(e("got").at(V(1)).has(V("by simp")), "tests/test_outer_source.py:112" + " actual=" + e("got").at(V(1)))
}
private def case_WhatSurvives_test_an_indented_declaration_survives(): Unit = TestSupport.test("test_outer_source.WhatSurvives.test_an_indented_declaration_survives") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\n  definition f where \"f x = x\"\n  lemma foo: \"f x = x\" by (simp add: f_def)\nend\n")), Map())
TestSupport.equal(e("got").at(V(1)).invoke("strip", Vector(), Map()).invoke("split", Vector(V("\"")), Map()).at(V(0)).invoke("strip", Vector(), Map()), V("definition f where"), "tests/test_outer_source.py:121")
TestSupport.check(e("got").at(V(2)).has(V("lemma foo:")), "tests/test_outer_source.py:123" + " actual=" + e("got").at(V(2)))
}
private def case_WhatSurvives_test_block_tokens_survive_outside_terms(): Unit = TestSupport.test("test_outer_source.WhatSurvives.test_block_tokens_survive_outside_terms") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

e("got") = h_outer(e, Vector(V("theory A imports Main begin\ncontext foo begin\nlemma bar: \"x\" by simp\nend\nend\n")), Map())
TestSupport.check(e("got").at(V(1)).has(V("begin")), "tests/test_outer_source.py:131" + " actual=" + e("got").at(V(1)))
TestSupport.check(e("got").at(V(3)).has(V("end")), "tests/test_outer_source.py:132" + " actual=" + e("got").at(V(3)))
}
private def case_BlockBalance_test_a_plain_theory_balances(): Unit = TestSupport.test("test_outer_source.BlockBalance.test_a_plain_theory_balances") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

TestSupport.equal(h_BlockBalance_depth(e, Vector(V("theory A imports Main begin\nlemma foo: \"x\" by simp\nend\n")), Map()), V(0), "tests/test_outer_source.py:158")
}
private def case_BlockBalance_test_a_nested_context_balances(): Unit = TestSupport.test("test_outer_source.BlockBalance.test_a_nested_context_balances") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

TestSupport.equal(h_BlockBalance_depth(e, Vector(V("theory A imports Main begin\nlocale L begin\ncontext fixes y begin\n  lemma foo: \"x\" by simp\nend\nend\nend\n")), Map()), V(0), "tests/test_outer_source.py:163")
}
private def case_BlockBalance_test_a_term_mentioning_end_does_not_unbalance_it(): Unit = TestSupport.test("test_outer_source.BlockBalance.test_a_term_mentioning_end_does_not_unbalance_it") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

TestSupport.equal(h_BlockBalance_depth(e, Vector(V("theory A imports Main begin\nlemma foo: \"front_end = back_end\"\n  by simp\nend\n")), Map()), V(0), "tests/test_outer_source.py:173")
}
private def case_BlockBalance_test_a_comment_mentioning_begin_does_not_unbalance_it(): Unit = TestSupport.test("test_outer_source.BlockBalance.test_a_comment_mentioning_begin_does_not_unbalance_it") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIP") = V("theory A imports Main begin\nlemma foo: \"mono f\"  (* a note *)\n  by simp\nend\n")

TestSupport.equal(h_BlockBalance_depth(e, Vector(V("theory A imports Main begin\n(* begin the interesting part *)\nlemma foo: \"x\" by simp\nend\n")), Map()), V(0), "tests/test_outer_source.py:179")
}
def run(): Unit = { case_Shape_test_line_count_is_preserved(); case_Shape_test_every_column_is_preserved(); case_Shape_test_outer_is_a_subset_of_live(); case_WhatIsBlanked_test_a_quoted_term_goes(); case_WhatIsBlanked_test_a_cartouche_term_goes(); case_WhatIsBlanked_test_a_comment_goes_too(); case_WhatIsBlanked_test_a_multi_line_term_is_blanked_on_every_line(); case_WhatIsBlanked_test_a_keyword_inside_a_term_is_blanked(); case_WhatSurvives_test_the_command_keyword_survives(); case_WhatSurvives_test_an_indented_declaration_survives(); case_WhatSurvives_test_block_tokens_survive_outside_terms(); case_BlockBalance_test_a_plain_theory_balances(); case_BlockBalance_test_a_nested_context_balances(); case_BlockBalance_test_a_term_mentioning_end_does_not_unbalance_it(); case_BlockBalance_test_a_comment_mentioning_begin_does_not_unbalance_it() }
}
