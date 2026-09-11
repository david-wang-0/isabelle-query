package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_proof_extent_view.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_proof_extent_view {
private def h_body_end(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("src") = kw.getOrElse("src", args.lift(0).getOrElse(throw new IllegalArgumentException("missing src")))
e("theory") = kw.getOrElse("theory", args.lift(1).getOrElse(throw new IllegalArgumentException("missing theory")))
e("name") = kw.getOrElse("name", args.lift(2).getOrElse(throw new IllegalArgumentException("missing name")))
e("sec") = ParserBridge.call("section_from", Vector(e("src"), e("theory")), Map())
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map()).field("body_end_line")
V.none
}
private def case_ACommentedBoundaryDoesNotStopTheProof_test_a_commented_out_declaration(): Unit = TestSupport.test("test_proof_extent_view.ACommentedBoundaryDoesNotStopTheProof.test_a_commented_out_declaration") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("COMMENTED"), V("Commented"), V("first")), Map()), V(12), "tests/test_proof_extent_view.py:128")
}
private def case_ACommentedBoundaryDoesNotStopTheProof_test_a_commented_out_heading(): Unit = TestSupport.test("test_proof_extent_view.ACommentedBoundaryDoesNotStopTheProof.test_a_commented_out_heading") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("COMMENTED_HEADING"), V("Head"), V("first")), Map()), V(10), "tests/test_proof_extent_view.py:131")
}
private def case_ACommentedBoundaryDoesNotStopTheProof_test_a_commented_out_text_block(): Unit = TestSupport.test("test_proof_extent_view.ACommentedBoundaryDoesNotStopTheProof.test_a_commented_out_text_block") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("COMMENTED_TEXT"), V("Txt"), V("first")), Map()), V(10), "tests/test_proof_extent_view.py:134")
}
private def case_ACommentedBoundaryDoesNotStopTheProof_test_the_commented_lemma_declares_nothing(): Unit = TestSupport.test("test_proof_extent_view.ACommentedBoundaryDoesNotStopTheProof.test_the_commented_lemma_declares_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("COMMENTED"), V("Commented")), Map())
TestSupport.equal(V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (true) Vector(c2("e").field("name")) else Vector.empty }), vs(V("first"), V("second")), "tests/test_proof_extent_view.py:140")
}
private def case_TheRealBoundariesStillStop_test_a_real_declaration_stops_it(): Unit = TestSupport.test("test_proof_extent_view.TheRealBoundariesStillStop.test_a_real_declaration_stops_it") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("LIVE"), V("Live"), V("stops_at_decl")), Map()), V(6), "tests/test_proof_extent_view.py:147")
}
private def case_TheRealBoundariesStillStop_test_a_real_text_block_stops_it(): Unit = TestSupport.test("test_proof_extent_view.TheRealBoundariesStillStop.test_a_real_text_block_stops_it") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("LIVE"), V("Live"), V("stops_at_text")), Map()), V(10), "tests/test_proof_extent_view.py:150")
}
private def case_TheRealBoundariesStillStop_test_a_real_heading_stops_it(): Unit = TestSupport.test("test_proof_extent_view.TheRealBoundariesStillStop.test_a_real_heading_stops_it") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("LIVE"), V("Live"), V("stops_at_heading")), Map()), V(14), "tests/test_proof_extent_view.py:153")
}
private def case_TheRealBoundariesStillStop_test_a_live_line_that_merely_ends_in_a_comment_still_stops_it(): Unit = TestSupport.test("test_proof_extent_view.TheRealBoundariesStillStop.test_a_live_line_that_merely_ends_in_a_comment_still_stops_it") {
val e: Env = scala.collection.mutable.Map.empty
e("COMMENTED") = V("theory Commented\nimports Main\nbegin\n\nlemma first: \"True\"\n  using TrueI\n  by simp\n\n(* superseded, kept for reference\nlemma old_version: \"True\"\n  by auto\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_HEADING") = V("theory Head\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* not ready\nsubsection \\<open>Retracts and intervals\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("COMMENTED_TEXT") = V("theory Txt\nimports Main\nbegin\n\nlemma first: \"True\"\n  by simp\n\n(* draft\ntext \\<open>Some prose that is not live.\\<close>\n*)\n\nlemma second: \"True\" by simp\n\nend\n")
e("LIVE") = V("theory Live\nimports Main\nbegin\n\nlemma stops_at_decl: \"True\"\n  by simp\nlemma next_decl: \"True\" by simp\n\nlemma stops_at_text: \"True\"\n  by simp\ntext \\<open>Real prose.\\<close>\n\nlemma stops_at_heading: \"True\"\n  by simp\nsubsection \\<open>Real heading\\<close>\n\nlemma trailing_comment: \"True\"\n  by simp\nlemma after_it: \"P\" (* a note on a live declaration *)\n  by simp\n\nend\n")

TestSupport.equal(h_body_end(e, Vector(e("LIVE"), V("Live"), V("trailing_comment")), Map()), V(18), "tests/test_proof_extent_view.py:159")
}
def run(): Unit = { case_ACommentedBoundaryDoesNotStopTheProof_test_a_commented_out_declaration(); case_ACommentedBoundaryDoesNotStopTheProof_test_a_commented_out_heading(); case_ACommentedBoundaryDoesNotStopTheProof_test_a_commented_out_text_block(); case_ACommentedBoundaryDoesNotStopTheProof_test_the_commented_lemma_declares_nothing(); case_TheRealBoundariesStillStop_test_a_real_declaration_stops_it(); case_TheRealBoundariesStillStop_test_a_real_text_block_stops_it(); case_TheRealBoundariesStillStop_test_a_real_heading_stops_it(); case_TheRealBoundariesStillStop_test_a_live_line_that_merely_ends_in_a_comment_still_stops_it() }
}
