package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_keyword_kind_quoted.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_keyword_kind_quoted {
private def h_table(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("clause") = kw.getOrElse("clause", args.lift(0).getOrElse(throw new IllegalArgumentException("missing clause")))
return ParserBridge.call("parsing.scan_keywords", Vector(vs(V("theory T"), V("  imports Main"), V("  " + e("clause").str), V("begin"))), Map())
V.none
}
private def case_QuotedKind_test_quoted_kind_registers_the_commands(): Unit = TestSupport.test("test_keyword_kind_quoted.QuotedKind.test_quoted_kind_registers_the_commands") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"alphabet\" \"statespace\" :: \"thy_defn\"")), Map()), vm((V("alphabet"), V("DEF")), (V("statespace"), V("DEF"))), "tests/test_keyword_kind_quoted.py:44")
}
private def case_QuotedKind_test_unquoted_kind_still_registers(): Unit = TestSupport.test("test_keyword_kind_quoted.QuotedKind.test_unquoted_kind_still_registers") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"alphabet\" \"statespace\" :: thy_defn")), Map()), vm((V("alphabet"), V("DEF")), (V("statespace"), V("DEF"))), "tests/test_keyword_kind_quoted.py:48")
}
private def case_QuotedKind_test_the_two_spellings_agree(): Unit = TestSupport.test("test_keyword_kind_quoted.QuotedKind.test_the_two_spellings_agree") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"expr_constructor\" :: \"thy_decl_block\"")), Map()), h_table(e, Vector(V("keywords \"expr_constructor\" :: thy_decl_block")), Map()), "tests/test_keyword_kind_quoted.py:52")
}
private def case_QuotedKind_test_glued_quoted_kind(): Unit = TestSupport.test("test_keyword_kind_quoted.QuotedKind.test_glued_quoted_kind") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"alphabet\" ::\"thy_defn\"")), Map()), vm((V("alphabet"), V("DEF"))), "tests/test_keyword_kind_quoted.py:56")
}
private def case_TheWideningStaysNarrow_test_a_group_with_no_kind_is_a_minor_keyword(): Unit = TestSupport.test("test_keyword_kind_quoted.TheWideningStaysNarrow.test_a_group_with_no_kind_is_a_minor_keyword") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"edefinition\" :: \"thy_decl_block\" and \"over\"")), Map()), vm((V("edefinition"), V("DEF"))), "tests/test_keyword_kind_quoted.py:65")
}
private def case_TheWideningStaysNarrow_test_a_quoted_tag_value_is_not_read_as_the_kind(): Unit = TestSupport.test("test_keyword_kind_quoted.TheWideningStaysNarrow.test_a_quoted_tag_value_is_not_read_as_the_kind") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"alphabet\" :: thy_defn % \"proof\"")), Map()), vm((V("alphabet"), V("DEF"))), "tests/test_keyword_kind_quoted.py:71")
}
private def case_TheWideningStaysNarrow_test_an_unknown_kind_registers_nothing(): Unit = TestSupport.test("test_keyword_kind_quoted.TheWideningStaysNarrow.test_an_unknown_kind_registers_nothing") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")

TestSupport.equal(h_table(e, Vector(V("keywords \"alphabet\" :: \"quasi_command\"")), Map()), vm(), "tests/test_keyword_kind_quoted.py:75")
}
private def case_ThePipelineSeesIt_test_the_custom_command_declares_an_entry(): Unit = TestSupport.test("test_keyword_kind_quoted.ThePipelineSeesIt.test_the_custom_command_declares_an_entry") {
val e: Env = scala.collection.mutable.Map.empty

e("ThePipelineSeesIt.SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")
e("SOURCE") = V("theory Lens\nimports Main\n  keywords \"alphabet\" :: \"thy_defn\"\nbegin\n\nalphabet mystate =\n  x :: nat\n  y :: nat\n\nlemma after: \\<open>True\\<close> by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SOURCE"), V("Lens")), Map())
TestSupport.check(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty }).has(V("mystate")), "tests/test_keyword_kind_quoted.py:97" + " actual=" + V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty }))
}
def run(): Unit = { case_QuotedKind_test_quoted_kind_registers_the_commands(); case_QuotedKind_test_unquoted_kind_still_registers(); case_QuotedKind_test_the_two_spellings_agree(); case_QuotedKind_test_glued_quoted_kind(); case_TheWideningStaysNarrow_test_a_group_with_no_kind_is_a_minor_keyword(); case_TheWideningStaysNarrow_test_a_quoted_tag_value_is_not_read_as_the_kind(); case_TheWideningStaysNarrow_test_an_unknown_kind_registers_nothing(); case_ThePipelineSeesIt_test_the_custom_command_declares_an_entry() }
}
