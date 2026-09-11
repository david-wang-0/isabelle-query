package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_line_index_ties.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_line_index_ties {
private def case_LineIndexTies_test_the_fixture_really_produces_a_tie(): Unit = TestSupport.test("test_line_index_ties.LineIndexTies.test_the_fixture_really_produces_a_tie") {
val e: Env = scala.collection.mutable.Map.empty
e("AXIOMATIZATION") = V("theory Ties\nimports Main\nbegin\n\naxiomatization\n  gle :: \\<open>'a => 'a => o\\<close> and gless :: \\<open>'a => 'a => o\\<close> and\n  gle' :: \\<open>'a => 'a => o\\<close> and gless' :: \\<open>'a => 'a => o\\<close>\nwhere\n  grefl: \\<open>gle(x, x)\\<close> and gless_def: \\<open>gless(x, y)\\<close> and\n  grefl': \\<open>gle'(x, x)\\<close> and gless'_def: \\<open>gless'(x, y)\\<close>\n\nlemma below: \\<open>True\\<close> by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("AXIOMATIZATION"), V("Ties")), Map())
e("spans") = V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("thy_line").cmp("Gt", V(0)))).truth) Vector(vs(c1("e").field("src_start"), c1("e").field("thy_end"))) else Vector.empty })
TestSupport.check(ParserBridge.call("len", Vector(e("spans")), Map()).cmp("Gt", ParserBridge.call("len", Vector(ParserBridge.call("set", Vector(e("spans")), Map())), Map())), V("fixture produced no duplicate span: " + e("spans").str).str + " actual=" + ParserBridge.call("len", Vector(e("spans")), Map()) + " expected=" + ParserBridge.call("len", Vector(ParserBridge.call("set", Vector(e("spans")), Map())), Map()))
}
private def case_LineIndexTies_test_the_index_builds(): Unit = TestSupport.test("test_line_index_ties.LineIndexTies.test_the_index_builds") {
val e: Env = scala.collection.mutable.Map.empty
e("AXIOMATIZATION") = V("theory Ties\nimports Main\nbegin\n\naxiomatization\n  gle :: \\<open>'a => 'a => o\\<close> and gless :: \\<open>'a => 'a => o\\<close> and\n  gle' :: \\<open>'a => 'a => o\\<close> and gless' :: \\<open>'a => 'a => o\\<close>\nwhere\n  grefl: \\<open>gle(x, x)\\<close> and gless_def: \\<open>gless(x, y)\\<close> and\n  grefl': \\<open>gle'(x, x)\\<close> and gless'_def: \\<open>gless'(x, y)\\<close>\n\nlemma below: \\<open>True\\<close> by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("AXIOMATIZATION"), V("Ties")), Map())
e("index") = ParserBridge.call("cli._build_line_index", Vector(vs(e("sec"))), Map())
TestSupport.check(e("index").has(e("sec").field("path")), "tests/test_line_index_ties.py:65" + " actual=" + e("index"))
}
private def case_LineIndexTies_test_tied_entries_keep_source_order(): Unit = TestSupport.test("test_line_index_ties.LineIndexTies.test_tied_entries_keep_source_order") {
val e: Env = scala.collection.mutable.Map.empty
e("AXIOMATIZATION") = V("theory Ties\nimports Main\nbegin\n\naxiomatization\n  gle :: \\<open>'a => 'a => o\\<close> and gless :: \\<open>'a => 'a => o\\<close> and\n  gle' :: \\<open>'a => 'a => o\\<close> and gless' :: \\<open>'a => 'a => o\\<close>\nwhere\n  grefl: \\<open>gle(x, x)\\<close> and gless_def: \\<open>gless(x, y)\\<close> and\n  grefl': \\<open>gle'(x, x)\\<close> and gless'_def: \\<open>gless'(x, y)\\<close>\n\nlemma below: \\<open>True\\<close> by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("AXIOMATIZATION"), V("Ties")), Map())
e("index") = ParserBridge.call("cli._build_line_index", Vector(vs(e("sec"))), Map()).at(e("sec").field("path"))
e("ordered") = V.seq(e("index").seq.flatMap { item => val c2 = e.clone(); val unpack3 = item; TestSupport.equal(unpack3.seq.size, 3, "unpack arity"); c2("_") = unpack3.at(V(0)); c2("_") = unpack3.at(V(1)); c2("e") = unpack3.at(V(2)); if (true) Vector(c2("e").field("name")) else Vector.empty })
e("source_order") = V.seq(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (V((c4("e").field("thy_line").cmp("Gt", V(0)))).truth) Vector(c4("e").field("name")) else Vector.empty })
TestSupport.equal(V.seq(e("source_order").seq.flatMap { item => val c5 = e.clone(); c5("n") = item; if (V((c5("ordered").has(c5("n")))).truth) Vector(c5("n")) else Vector.empty }), e("ordered"), V("a stable sort must leave equal spans in source order").str)
}
private def case_LineIndexTies_test_the_index_is_sorted_by_span(): Unit = TestSupport.test("test_line_index_ties.LineIndexTies.test_the_index_is_sorted_by_span") {
val e: Env = scala.collection.mutable.Map.empty
e("AXIOMATIZATION") = V("theory Ties\nimports Main\nbegin\n\naxiomatization\n  gle :: \\<open>'a => 'a => o\\<close> and gless :: \\<open>'a => 'a => o\\<close> and\n  gle' :: \\<open>'a => 'a => o\\<close> and gless' :: \\<open>'a => 'a => o\\<close>\nwhere\n  grefl: \\<open>gle(x, x)\\<close> and gless_def: \\<open>gless(x, y)\\<close> and\n  grefl': \\<open>gle'(x, x)\\<close> and gless'_def: \\<open>gless'(x, y)\\<close>\n\nlemma below: \\<open>True\\<close> by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("AXIOMATIZATION"), V("Ties")), Map())
e("index") = ParserBridge.call("cli._build_line_index", Vector(vs(e("sec"))), Map()).at(e("sec").field("path"))
e("keys") = V.seq(e("index").seq.flatMap { item => val c6 = e.clone(); val unpack7 = item; TestSupport.equal(unpack7.seq.size, 3, "unpack arity"); c6("lo") = unpack7.at(V(0)); c6("hi") = unpack7.at(V(1)); c6("_") = unpack7.at(V(2)); if (true) Vector(vs(c6("lo"), c6("hi"))) else Vector.empty })
TestSupport.equal(e("keys"), ParserBridge.call("sorted", Vector(e("keys")), Map()), "tests/test_line_index_ties.py:78")
}
private def case_LineIndexTies_test_enclosing_still_answers_below_the_tie(): Unit = TestSupport.test("test_line_index_ties.LineIndexTies.test_enclosing_still_answers_below_the_tie") {
val e: Env = scala.collection.mutable.Map.empty
e("AXIOMATIZATION") = V("theory Ties\nimports Main\nbegin\n\naxiomatization\n  gle :: \\<open>'a => 'a => o\\<close> and gless :: \\<open>'a => 'a => o\\<close> and\n  gle' :: \\<open>'a => 'a => o\\<close> and gless' :: \\<open>'a => 'a => o\\<close>\nwhere\n  grefl: \\<open>gle(x, x)\\<close> and gless_def: \\<open>gless(x, y)\\<close> and\n  grefl': \\<open>gle'(x, x)\\<close> and gless'_def: \\<open>gless'(x, y)\\<close>\n\nlemma below: \\<open>True\\<close> by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("AXIOMATIZATION"), V("Ties")), Map())
e("index") = ParserBridge.call("cli._build_line_index", Vector(vs(e("sec"))), Map()).at(e("sec").field("path"))
e("line") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c8 = e.clone(); c8("e") = item; if (V((c8("e").field("name").cmp("Eq", V("below")))).truth) Vector(c8("e").field("thy_line")) else Vector.empty })), Map())
e("owners") = V.seq(e("index").seq.flatMap { item => val c9 = e.clone(); val unpack10 = item; TestSupport.equal(unpack10.seq.size, 3, "unpack arity"); c9("lo") = unpack10.at(V(0)); c9("hi") = unpack10.at(V(1)); c9("e") = unpack10.at(V(2)); if (V((c9("lo").cmp("LtE", c9("line"))) && (c9("line").cmp("LtE", c9("hi")))).truth) Vector(c9("e").field("name")) else Vector.empty })
TestSupport.check(e("owners").has(V("below")), "tests/test_line_index_ties.py:85" + " actual=" + e("owners"))
}
def run(): Unit = { case_LineIndexTies_test_the_fixture_really_produces_a_tie(); case_LineIndexTies_test_the_index_builds(); case_LineIndexTies_test_tied_entries_keep_source_order(); case_LineIndexTies_test_the_index_is_sorted_by_span(); case_LineIndexTies_test_enclosing_still_answers_below_the_tie() }
}
