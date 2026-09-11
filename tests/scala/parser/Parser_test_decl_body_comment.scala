package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_decl_body_comment.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_decl_body_comment {
private def h_entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("body") = kw.getOrElse("body", args.lift(0).getOrElse(throw new IllegalArgumentException("missing body")))
return V(ParserBridge.section(e("HEAD").str + e("body").str + e("TAIL").str, "Probe").entries.head)
V.none
}
private def h_entries(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("body") = kw.getOrElse("body", args.lift(0).getOrElse(throw new IllegalArgumentException("missing body")))
return V(ParserBridge.section(e("HEAD").str + e("body").str + e("TAIL").str, "Probe").entries)
V.none
}
private def h_ACommentDoesNotEndADeclaration_body_end_with(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("note") = kw.getOrElse("note", args.lift(0).getOrElse(throw new IllegalArgumentException("missing note")))
return h_entry(e, Vector(V("definition\n" + e("note").str + "\n  rel :: \"nat set\"\n  where \"rel = {}\"")), Map()).field("body_end_line")
V.none
}
private def case_ACommentDoesNotEndADeclaration_test_marginal_note(): Unit = TestSupport.test("test_decl_body_comment.ACommentDoesNotEndADeclaration.test_marginal_note") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

TestSupport.equal(h_ACommentDoesNotEndADeclaration_body_end_with(e, Vector(V("  \\<comment> \\<open>Relations.\\<close>")), Map()), V(7), "tests/test_decl_body_comment.py:63")
}
private def case_ACommentDoesNotEndADeclaration_test_a_wrapping_note(): Unit = TestSupport.test("test_decl_body_comment.ACommentDoesNotEndADeclaration.test_a_wrapping_note") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

TestSupport.equal(h_entry(e, Vector(V("definition\n  \\<comment> \\<open>Relations induced\n      by a mapping.\\<close>\n  rel :: \"nat set\"\n  where \"rel = {}\"")), Map()).field("body_end_line"), V(8), "tests/test_decl_body_comment.py:69")
}
private def case_ACommentDoesNotEndADeclaration_test_a_document_marker(): Unit = TestSupport.test("test_decl_body_comment.ACommentDoesNotEndADeclaration.test_a_document_marker") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

TestSupport.equal(h_ACommentDoesNotEndADeclaration_body_end_with(e, Vector(V("  \\<^marker>\\<open>tag important\\<close>")), Map()), V(7), "tests/test_decl_body_comment.py:77")
}
private def case_ACommentDoesNotEndADeclaration_test_an_ml_style_block_comment(): Unit = TestSupport.test("test_decl_body_comment.ACommentDoesNotEndADeclaration.test_an_ml_style_block_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

TestSupport.equal(h_ACommentDoesNotEndADeclaration_body_end_with(e, Vector(V("  (* Relations. *)")), Map()), V(7), "tests/test_decl_body_comment.py:81")
}
private def case_ACommentDoesNotEndADeclaration_test_the_name_and_the_body_agree(): Unit = TestSupport.test("test_decl_body_comment.ACommentDoesNotEndADeclaration.test_the_name_and_the_body_agree") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

e("e") = h_entry(e, Vector(V("definition\n  \\<comment> \\<open>Relations.\\<close>\n  rel :: \"nat set\"\n  where \"rel = {}\"")), Map())
TestSupport.equal(e("e").field("name"), V("rel"), "tests/test_decl_body_comment.py:89")
TestSupport.check(e("e").field("body_end_line").cmp("GtE", V(6)), "tests/test_decl_body_comment.py:90" + " actual=" + e("e").field("body_end_line") + " expected=" + V(6))
}
private def case_ACommentIsNotPartOfADeclaration_test_a_trailing_block_comment_is_not_body(): Unit = TestSupport.test("test_decl_body_comment.ACommentIsNotPartOfADeclaration.test_a_trailing_block_comment_is_not_body") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

e("e") = h_entry(e, Vector(V("definition emptyPost :: nat where\n\"emptyPost = 0\"\n(* initially set to the lowest value *)")), Map())
TestSupport.equal(e("e").field("body_end_line"), V(5), "tests/test_decl_body_comment.py:106")
}
private def case_ACommentIsNotPartOfADeclaration_test_a_trailing_marginal_note_is_not_body(): Unit = TestSupport.test("test_decl_body_comment.ACommentIsNotPartOfADeclaration.test_a_trailing_marginal_note_is_not_body") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

e("e") = h_entry(e, Vector(V("definition emptyPost :: nat where\n\"emptyPost = 0\"\n\\<comment> \\<open>a note\\<close>")), Map())
TestSupport.equal(e("e").field("body_end_line"), V(5), "tests/test_decl_body_comment.py:113")
}
private def case_TheBodyStaysInsideItsOwnSpan_test_a_heading_bounds_the_body(): Unit = TestSupport.test("test_decl_body_comment.TheBodyStaysInsideItsOwnSpan.test_a_heading_bounds_the_body") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

e("es") = h_entries(e, Vector(V("datatype tree = ET | MKT nat\n\nsubsection \\<open>Invariants and auxiliary functions\\<close>\n\nprimrec height :: \"nat\" where \"height = 0\"")), Map())
TestSupport.check(e("es").truth, "tests/test_decl_body_comment.py:146")
e("es").seq.foreach { item => e("e") = item;
TestSupport.check(e("e").field("body_end_line").cmp("LtE", e("e").field("thy_end")), V(e("e").field("name").str + ": body_end " + e("e").field("body_end_line").str + " > thy_end " + e("e").field("thy_end").str).str + " actual=" + e("e").field("body_end_line") + " expected=" + e("e").field("thy_end"))
}
}
private def case_TheBodyStaysInsideItsOwnSpan_test_two_declarations_do_not_overlap(): Unit = TestSupport.test("test_decl_body_comment.TheBodyStaysInsideItsOwnSpan.test_two_declarations_do_not_overlap") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

e("es") = h_entries(e, Vector(V("definition\n  \\<comment> \\<open>first\\<close>\n  a :: \"nat\" where \"a = 0\"\ndefinition\n  \\<comment> \\<open>second\\<close>\n  b :: \"nat\" where \"b = 1\"")), Map())
TestSupport.equal(V.seq(e("es").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty }), vs(V("a"), V("b")), "tests/test_decl_body_comment.py:160")
e("es").seq.foreach { item => e("e") = item;
TestSupport.check(e("e").field("body_end_line").cmp("LtE", e("e").field("thy_end")), "tests/test_decl_body_comment.py:162" + " actual=" + e("e").field("body_end_line") + " expected=" + e("e").field("thy_end"))
}
TestSupport.check(e("es").at(V(0)).field("thy_end").cmp("Lt", e("es").at(V(1)).field("thy_line")), "tests/test_decl_body_comment.py:163" + " actual=" + e("es").at(V(0)).field("thy_end") + " expected=" + e("es").at(V(1)).field("thy_line"))
}
private def case_TheBlankLineVariantIsStillOpen_test_a_blank_before_the_note(): Unit = TestSupport.expectedFailure("test_decl_body_comment.TheBlankLineVariantIsStillOpen.test_a_blank_before_the_note", "Original upstream @expectedFailure: blank-before-note declaration body remains truncated; preserve the desired body-end assertion and fail on XPASS; broad span fix previously regressed containment.") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory Probe\nimports Main\nbegin\n")
e("TAIL") = V("\nend\n")

e("e") = h_entry(e, Vector(V("definition\n\n  \\<comment> \\<open>Specifies conditional fairness.\\<close>\n  transient :: \"nat set\"\n  where \"transient = {}\"")), Map())
TestSupport.equal(e("e").field("name"), V("transient"), "tests/test_decl_body_comment.py:190")
TestSupport.check(e("e").field("body_end_line").cmp("GtE", V(7)), "tests/test_decl_body_comment.py:191" + " actual=" + e("e").field("body_end_line") + " expected=" + V(7))
}
def run(): Unit = { case_ACommentDoesNotEndADeclaration_test_marginal_note(); case_ACommentDoesNotEndADeclaration_test_a_wrapping_note(); case_ACommentDoesNotEndADeclaration_test_a_document_marker(); case_ACommentDoesNotEndADeclaration_test_an_ml_style_block_comment(); case_ACommentDoesNotEndADeclaration_test_the_name_and_the_body_agree(); case_ACommentIsNotPartOfADeclaration_test_a_trailing_block_comment_is_not_body(); case_ACommentIsNotPartOfADeclaration_test_a_trailing_marginal_note_is_not_body(); case_TheBodyStaysInsideItsOwnSpan_test_a_heading_bounds_the_body(); case_TheBodyStaysInsideItsOwnSpan_test_two_declarations_do_not_overlap(); case_TheBlankLineVariantIsStillOpen_test_a_blank_before_the_note() }
}
