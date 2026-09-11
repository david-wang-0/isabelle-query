package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_comment_before_name.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_comment_before_name {
private def h_parse(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("body") = kw.getOrElse("body", args.lift(0).getOrElse(throw new IllegalArgumentException("missing body")))
return V(ParserBridge.section(e("HEAD").str + e("body").str + e("TAIL").str, "Probe"))
V.none
}
private def h_names(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("body") = kw.getOrElse("body", args.lift(0).getOrElse(throw new IllegalArgumentException("missing body")))
return V.seq(h_parse(e, Vector(e("body")), Map()).field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty })
V.none
}
private def case_AKeywordAloneStillFindsItsName_test_no_comment_at_all(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_no_comment_at_all") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:58" + " actual=" + h_names(e, Vector(V("definition\n" + e("DECL").str)), Map()))
}
private def case_AKeywordAloneStillFindsItsName_test_comment_on_the_keyword_line(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_comment_on_the_keyword_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition \\<comment> \\<open>Generic to all forms.\\<close>\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:62" + " actual=" + h_names(e, Vector(V("definition \\<comment> \\<open>Generic to all forms.\\<close>\n" + e("DECL").str)), Map()))
}
private def case_AKeywordAloneStillFindsItsName_test_comment_alone_on_one_line(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_comment_alone_on_one_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition\n  \\<comment> \\<open>Generic to all forms.\\<close>\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:67" + " actual=" + h_names(e, Vector(V("definition\n  \\<comment> \\<open>Generic to all forms.\\<close>\n" + e("DECL").str)), Map()))
}
private def case_AKeywordAloneStillFindsItsName_test_comment_wrapping_over_two_lines(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_comment_wrapping_over_two_lines") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition\n  \\<comment> \\<open>This specifies conditional fairness.  The rest\n      is generic to all forms of fairness.\\<close>\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:74" + " actual=" + h_names(e, Vector(V("definition\n  \\<comment> \\<open>This specifies conditional fairness.  The rest\n      is generic to all forms of fairness.\\<close>\n" + e("DECL").str)), Map()))
}
private def case_AKeywordAloneStillFindsItsName_test_a_marker_is_a_formal_comment_too(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_a_marker_is_a_formal_comment_too") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition\n  \\<^marker>\\<open>tag important\\<close>\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:81" + " actual=" + h_names(e, Vector(V("definition\n  \\<^marker>\\<open>tag important\\<close>\n" + e("DECL").str)), Map()))
}
private def case_AKeywordAloneStillFindsItsName_test_a_wrapping_marker(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_a_wrapping_marker") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition\n  \\<^marker>\\<open>tag important\n      and more\\<close>\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:87" + " actual=" + h_names(e, Vector(V("definition\n  \\<^marker>\\<open>tag important\n      and more\\<close>\n" + e("DECL").str)), Map()))
}
private def case_AKeywordAloneStillFindsItsName_test_an_ml_style_block_comment(): Unit = TestSupport.test("test_comment_before_name.AKeywordAloneStillFindsItsName.test_an_ml_style_block_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(h_names(e, Vector(V("definition\n  (* Adjustment to a clock *)\n" + e("DECL").str)), Map()).has(V("transient")), "tests/test_comment_before_name.py:96" + " actual=" + h_names(e, Vector(V("definition\n  (* Adjustment to a clock *)\n" + e("DECL").str)), Map()))
}
private def case_ThePhantomIsGone_test_the_prose_word_is_not_an_entry(): Unit = TestSupport.test("test_comment_before_name.ThePhantomIsGone.test_the_prose_word_is_not_an_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

e("got") = h_names(e, Vector(V("definition\n  \\<comment> \\<open>This specifies conditional fairness.  The rest\n      is generic to all forms of fairness.\\<close>\n" + e("DECL").str)), Map())
TestSupport.check(!e("got").has(V("is")), "tests/test_comment_before_name.py:111" + " actual=" + e("got"))
TestSupport.equal(e("got"), vs(V("transient")), "tests/test_comment_before_name.py:112")
}
private def case_TheEntrySpanCoversItsOwnName_test_the_span_contains_the_line_the_name_is_written_on(): Unit = TestSupport.test("test_comment_before_name.TheEntrySpanCoversItsOwnName.test_the_span_contains_the_line_the_name_is_written_on") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

e("body") = V("definition\n  \\<comment> \\<open>One\n      two.\\<close>\n" + e("DECL").str)
e("e") = h_parse(e, Vector(e("body")), Map()).field("entries").at(V(0))
e("name_line") = V(4).plus(V(3))
TestSupport.equal(e("e").field("name"), V("transient"), "tests/test_comment_before_name.py:134")
TestSupport.check(e("e").field("src_start").cmp("LtE", e("name_line")), "tests/test_comment_before_name.py:135" + " actual=" + e("e").field("src_start") + " expected=" + e("name_line"))
TestSupport.check(e("e").field("thy_end").cmp("GtE", e("name_line")), "tests/test_comment_before_name.py:136" + " actual=" + e("e").field("thy_end") + " expected=" + e("name_line"))
}
private def case_TheEntrySpanCoversItsOwnName_test_the_name_is_the_same_with_and_without_the_comment(): Unit = TestSupport.test("test_comment_before_name.TheEntrySpanCoversItsOwnName.test_the_name_is_the_same_with_and_without_the_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

e("plain") = h_parse(e, Vector(V("definition\n" + e("DECL").str)), Map()).field("entries").at(V(0))
e("commented") = h_parse(e, Vector(V("definition\n  \\<comment> \\<open>One\n      two.\\<close>\n" + e("DECL").str)), Map()).field("entries").at(V(0))
TestSupport.equal(e("plain").field("name"), e("commented").field("name"), "tests/test_comment_before_name.py:145")
TestSupport.equal(e("commented").field("thy_line"), e("plain").field("thy_line"), "tests/test_comment_before_name.py:146")
}
private def case_TheGuardStillGuards_test_an_unterminated_comment_does_not_invent_a_name(): Unit = TestSupport.test("test_comment_before_name.TheGuardStillGuards.test_an_unterminated_comment_does_not_invent_a_name") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

e("body") = V("definition\n  \\<comment> \\<open>this cartouche never closes\n").plus(V("\n").invoke("join", Vector(V.seq(ParserBridge.call("range", Vector(V(60)), Map()).seq.flatMap { item => val c2 = e.clone(); c2("k") = item; if (true) Vector(V("  filler line " + c2("k").str)) else Vector.empty })), Map()))
e("got") = h_names(e, Vector(e("body")), Map())
TestSupport.check(!e("got").has(V("filler")), "tests/test_comment_before_name.py:164" + " actual=" + e("got"))
TestSupport.check(!e("got").has(V("line")), "tests/test_comment_before_name.py:165" + " actual=" + e("got"))
}
private def case_TheGuardStillGuards_test_blank_lines_still_spend_the_budget(): Unit = TestSupport.test("test_comment_before_name.TheGuardStillGuards.test_blank_lines_still_spend_the_budget") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")
e("DECL") = V("  transient :: \"nat set\" where \"transient = {}\"")

TestSupport.check(!h_names(e, Vector(V("definition\n").plus(V("\n").times(V(5))).plus(e("DECL"))), Map()).has(V("transient")), "tests/test_comment_before_name.py:171" + " actual=" + h_names(e, Vector(V("definition\n").plus(V("\n").times(V(5))).plus(e("DECL"))), Map()))
}
def run(): Unit = { case_AKeywordAloneStillFindsItsName_test_no_comment_at_all(); case_AKeywordAloneStillFindsItsName_test_comment_on_the_keyword_line(); case_AKeywordAloneStillFindsItsName_test_comment_alone_on_one_line(); case_AKeywordAloneStillFindsItsName_test_comment_wrapping_over_two_lines(); case_AKeywordAloneStillFindsItsName_test_a_marker_is_a_formal_comment_too(); case_AKeywordAloneStillFindsItsName_test_a_wrapping_marker(); case_AKeywordAloneStillFindsItsName_test_an_ml_style_block_comment(); case_ThePhantomIsGone_test_the_prose_word_is_not_an_entry(); case_TheEntrySpanCoversItsOwnName_test_the_span_contains_the_line_the_name_is_written_on(); case_TheEntrySpanCoversItsOwnName_test_the_name_is_the_same_with_and_without_the_comment(); case_TheGuardStillGuards_test_an_unterminated_comment_does_not_invent_a_name(); case_TheGuardStillGuards_test_blank_lines_still_spend_the_budget() }
}
