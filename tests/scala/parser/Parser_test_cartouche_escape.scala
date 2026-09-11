package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_cartouche_escape.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_cartouche_escape {
private def case_BackslashCartouche_test_declarations_below_the_operator_are_indexed(): Unit = TestSupport.test("test_cartouche_escape.BackslashCartouche.test_declarations_below_the_operator_are_indexed") {
val e: Env = scala.collection.mutable.Map.empty
e("BACKSLASH_CARTOUCHE") = V("theory Esc\nimports Main\nbegin\n\nfun resid  (infix \\<open>\\\\<close> 70)\n  where \\<open>resid x = x\\<close>\n\nlemma below_operator: \\<open>True\\<close> by simp\n\ndefinition marker :: \\<open>bool\\<close> where \\<open>marker = True\\<close>\n\nend\n")
e("ESCAPES") = V("theory Guard\nimports Main\nbegin\n\nlemma escaped_quote: \"a \\\" b = c\" by simp\n\nlemma after_escaped_quote: \"True\" by simp\n\nlemma escaped_backslash: \"a \\\\\" by simp\n\nlemma after_escaped_backslash: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("BACKSLASH_CARTOUCHE"), V("Esc")), Map())
e("names") = V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty })
TestSupport.check(e("names").has(V("below_operator")), "tests/test_cartouche_escape.py:81" + " actual=" + e("names"))
TestSupport.check(e("names").has(V("marker")), "tests/test_cartouche_escape.py:82" + " actual=" + e("names"))
}
private def case_BackslashCartouche_test_the_operator_declaration_does_not_swallow_the_file(): Unit = TestSupport.test("test_cartouche_escape.BackslashCartouche.test_the_operator_declaration_does_not_swallow_the_file") {
val e: Env = scala.collection.mutable.Map.empty
e("BACKSLASH_CARTOUCHE") = V("theory Esc\nimports Main\nbegin\n\nfun resid  (infix \\<open>\\\\<close> 70)\n  where \\<open>resid x = x\\<close>\n\nlemma below_operator: \\<open>True\\<close> by simp\n\ndefinition marker :: \\<open>bool\\<close> where \\<open>marker = True\\<close>\n\nend\n")
e("ESCAPES") = V("theory Guard\nimports Main\nbegin\n\nlemma escaped_quote: \"a \\\" b = c\" by simp\n\nlemma after_escaped_quote: \"True\" by simp\n\nlemma escaped_backslash: \"a \\\\\" by simp\n\nlemma after_escaped_backslash: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("BACKSLASH_CARTOUCHE"), V("Esc")), Map())
e("names") = V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (true) Vector(c2("e").field("name")) else Vector.empty })
e("resid") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (V((c3("e").field("name").cmp("Eq", V("resid")))).truth) Vector(c3("e")) else Vector.empty })), Map())
TestSupport.check(e("resid").field("decl_end_line").cmp("Lt", V(8)), V("declaration ran past its own `where` clause").str + " actual=" + e("resid").field("decl_end_line") + " expected=" + V(8))
}
private def case_BackslashCartouche_test_every_declaration_is_found(): Unit = TestSupport.test("test_cartouche_escape.BackslashCartouche.test_every_declaration_is_found") {
val e: Env = scala.collection.mutable.Map.empty
e("BACKSLASH_CARTOUCHE") = V("theory Esc\nimports Main\nbegin\n\nfun resid  (infix \\<open>\\\\<close> 70)\n  where \\<open>resid x = x\\<close>\n\nlemma below_operator: \\<open>True\\<close> by simp\n\ndefinition marker :: \\<open>bool\\<close> where \\<open>marker = True\\<close>\n\nend\n")
e("ESCAPES") = V("theory Guard\nimports Main\nbegin\n\nlemma escaped_quote: \"a \\\" b = c\" by simp\n\nlemma after_escaped_quote: \"True\" by simp\n\nlemma escaped_backslash: \"a \\\\\" by simp\n\nlemma after_escaped_backslash: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("BACKSLASH_CARTOUCHE"), V("Esc")), Map())
e("names") = V.seq(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (true) Vector(c4("e").field("name")) else Vector.empty })
TestSupport.equal(e("names"), vs(V("resid"), V("below_operator"), V("marker")), "tests/test_cartouche_escape.py:91")
}
private def case_EscapesStillWork_test_an_escaped_quote_does_not_close_its_term(): Unit = TestSupport.test("test_cartouche_escape.EscapesStillWork.test_an_escaped_quote_does_not_close_its_term") {
val e: Env = scala.collection.mutable.Map.empty
e("BACKSLASH_CARTOUCHE") = V("theory Esc\nimports Main\nbegin\n\nfun resid  (infix \\<open>\\\\<close> 70)\n  where \\<open>resid x = x\\<close>\n\nlemma below_operator: \\<open>True\\<close> by simp\n\ndefinition marker :: \\<open>bool\\<close> where \\<open>marker = True\\<close>\n\nend\n")
e("ESCAPES") = V("theory Guard\nimports Main\nbegin\n\nlemma escaped_quote: \"a \\\" b = c\" by simp\n\nlemma after_escaped_quote: \"True\" by simp\n\nlemma escaped_backslash: \"a \\\\\" by simp\n\nlemma after_escaped_backslash: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("ESCAPES"), V("Guard")), Map())
e("names") = V.seq(e("sec").field("entries").seq.flatMap { item => val c5 = e.clone(); c5("e") = item; if (true) Vector(c5("e").field("name")) else Vector.empty })
TestSupport.check(e("names").has(V("after_escaped_quote")), "tests/test_cartouche_escape.py:104" + " actual=" + e("names"))
}
private def case_EscapesStillWork_test_an_escaped_backslash_is_still_consumed(): Unit = TestSupport.test("test_cartouche_escape.EscapesStillWork.test_an_escaped_backslash_is_still_consumed") {
val e: Env = scala.collection.mutable.Map.empty
e("BACKSLASH_CARTOUCHE") = V("theory Esc\nimports Main\nbegin\n\nfun resid  (infix \\<open>\\\\<close> 70)\n  where \\<open>resid x = x\\<close>\n\nlemma below_operator: \\<open>True\\<close> by simp\n\ndefinition marker :: \\<open>bool\\<close> where \\<open>marker = True\\<close>\n\nend\n")
e("ESCAPES") = V("theory Guard\nimports Main\nbegin\n\nlemma escaped_quote: \"a \\\" b = c\" by simp\n\nlemma after_escaped_quote: \"True\" by simp\n\nlemma escaped_backslash: \"a \\\\\" by simp\n\nlemma after_escaped_backslash: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("ESCAPES"), V("Guard")), Map())
e("names") = V.seq(e("sec").field("entries").seq.flatMap { item => val c6 = e.clone(); c6("e") = item; if (true) Vector(c6("e").field("name")) else Vector.empty })
TestSupport.check(e("names").has(V("after_escaped_backslash")), "tests/test_cartouche_escape.py:107" + " actual=" + e("names"))
}
private def case_EscapesStillWork_test_every_declaration_is_found(): Unit = TestSupport.test("test_cartouche_escape.EscapesStillWork.test_every_declaration_is_found") {
val e: Env = scala.collection.mutable.Map.empty
e("BACKSLASH_CARTOUCHE") = V("theory Esc\nimports Main\nbegin\n\nfun resid  (infix \\<open>\\\\<close> 70)\n  where \\<open>resid x = x\\<close>\n\nlemma below_operator: \\<open>True\\<close> by simp\n\ndefinition marker :: \\<open>bool\\<close> where \\<open>marker = True\\<close>\n\nend\n")
e("ESCAPES") = V("theory Guard\nimports Main\nbegin\n\nlemma escaped_quote: \"a \\\" b = c\" by simp\n\nlemma after_escaped_quote: \"True\" by simp\n\nlemma escaped_backslash: \"a \\\\\" by simp\n\nlemma after_escaped_backslash: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("ESCAPES"), V("Guard")), Map())
e("names") = V.seq(e("sec").field("entries").seq.flatMap { item => val c7 = e.clone(); c7("e") = item; if (true) Vector(c7("e").field("name")) else Vector.empty })
TestSupport.equal(e("names"), vs(V("escaped_quote"), V("after_escaped_quote"), V("escaped_backslash"), V("after_escaped_backslash")), "tests/test_cartouche_escape.py:110")
}
def run(): Unit = { case_BackslashCartouche_test_declarations_below_the_operator_are_indexed(); case_BackslashCartouche_test_the_operator_declaration_does_not_swallow_the_file(); case_BackslashCartouche_test_every_declaration_is_found(); case_EscapesStillWork_test_an_escaped_quote_does_not_close_its_term(); case_EscapesStillWork_test_an_escaped_backslash_is_still_consumed(); case_EscapesStillWork_test_every_declaration_is_found() }
}
