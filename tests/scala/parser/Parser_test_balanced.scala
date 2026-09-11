package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_balanced.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_balanced {
private def case_BalancedEnd_test_flat_paren_returns_index_past_close(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_flat_paren_returns_index_past_close") {
val e: Env = scala.collection.mutable.Map.empty


TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(V("(a b) tail"), V("("), V(")")), Map()), V(5), "tests/test_balanced.py:24")
}
private def case_BalancedEnd_test_nested_paren_matches_the_outermost(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_nested_paren_matches_the_outermost") {
val e: Env = scala.collection.mutable.Map.empty


TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(V("((a) b)"), V("("), V(")")), Map()), V(7), "tests/test_balanced.py:29")
}
private def case_BalancedEnd_test_unbalanced_returns_minus_one(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_unbalanced_returns_minus_one") {
val e: Env = scala.collection.mutable.Map.empty


TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(V("(a b"), V("("), V(")")), Map()), V(-V(1).int), "tests/test_balanced.py:32")
}
private def case_BalancedEnd_test_start_offset_begins_at_the_opener(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_start_offset_begins_at_the_opener") {
val e: Env = scala.collection.mutable.Map.empty


TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(V("xx(a)"), V("("), V(")")), Map("start" -> V(2))), V(5), "tests/test_balanced.py:36")
}
private def case_BalancedEnd_test_multichar_cartouche_token(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_multichar_cartouche_token") {
val e: Env = scala.collection.mutable.Map.empty


e("s") = V("\\<open>x\\<close>")
TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(e("s"), V("\\<open>"), V("\\<close>")), Map()), ParserBridge.call("len", Vector(e("s")), Map()), "tests/test_balanced.py:41")
}
private def case_BalancedEnd_test_nested_cartouche(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_nested_cartouche") {
val e: Env = scala.collection.mutable.Map.empty


e("s") = V("\\<open>a\\<open>b\\<close>c\\<close>")
TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(e("s"), V("\\<open>"), V("\\<close>")), Map()), ParserBridge.call("len", Vector(e("s")), Map()), "tests/test_balanced.py:45")
}
private def case_BalancedEnd_test_quote_unaware_closes_at_a_quoted_paren(): Unit = TestSupport.test("test_balanced.BalancedEnd.test_quote_unaware_closes_at_a_quoted_paren") {
val e: Env = scala.collection.mutable.Map.empty


TestSupport.equal(ParserBridge.call("parsing._balanced_end", Vector(V("(\"a)b\")"), V("("), V(")")), Map()), V(4), "tests/test_balanced.py:50")
}
private def case_BalancedFacades_test_paren_facade(): Unit = TestSupport.test("test_balanced.BalancedFacades.test_paren_facade") {
val e: Env = scala.collection.mutable.Map.empty


TestSupport.equal(ParserBridge.call("parsing._balanced_paren_end", Vector(V("(a(b)c)")), Map()), V(7), "tests/test_balanced.py:63")
TestSupport.equal(ParserBridge.call("parsing._balanced_paren_end", Vector(V("(a")), Map()), V(-V(1).int), "tests/test_balanced.py:64")
}
private def case_BalancedFacades_test_cartouche_facade(): Unit = TestSupport.test("test_balanced.BalancedFacades.test_cartouche_facade") {
val e: Env = scala.collection.mutable.Map.empty


e("s") = V("\\<open>a\\<open>b\\<close>c\\<close>")
TestSupport.equal(ParserBridge.call("parsing._balanced_cartouche_end", Vector(e("s")), Map()), ParserBridge.call("len", Vector(e("s")), Map()), "tests/test_balanced.py:68")
}
def run(): Unit = { case_BalancedEnd_test_flat_paren_returns_index_past_close(); case_BalancedEnd_test_nested_paren_matches_the_outermost(); case_BalancedEnd_test_unbalanced_returns_minus_one(); case_BalancedEnd_test_start_offset_begins_at_the_opener(); case_BalancedEnd_test_multichar_cartouche_token(); case_BalancedEnd_test_nested_cartouche(); case_BalancedEnd_test_quote_unaware_closes_at_a_quoted_paren(); case_BalancedFacades_test_paren_facade(); case_BalancedFacades_test_cartouche_facade() }
}
