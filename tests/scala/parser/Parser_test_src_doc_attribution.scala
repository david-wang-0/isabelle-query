package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_src_doc_attribution.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_src_doc_attribution {
private def h__sec(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
return ParserBridge.call("section_from", Vector(e("THY"), V("T")), Map())
V.none
}
private def h__entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def h_EnclosingAttribution__owner(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("line_no") = kw.getOrElse("line_no", args.lift(1).getOrElse(throw new IllegalArgumentException("missing line_no")))
return ParserBridge.call("cli._enclosing_entry", Vector(e("sec"), e("line_no")), Map())
V.none
}
private def h_EndToEndEnclosing__run(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("locus") = kw.getOrElse("locus", args.lift(0).getOrElse(throw new IllegalArgumentException("missing locus")))
return V(ParserBridge.enclosing(List(h__sec(e, Vector.empty, Map.empty).section),List(e("locus").str)))
V.none
}
private def case_PreambleOwnership_test_doc_block_is_the_following_entry_preamble(): Unit = TestSupport.test("test_src_doc_attribution.PreambleOwnership.test_doc_block_is_the_following_entry_preamble") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("fwd") = h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())
TestSupport.check(!e("fwd").field("preamble").isNone, "tests/test_src_doc_attribution.py:63")
TestSupport.equal(e("fwd").field("src_start"), e("fwd").field("preamble").at(V(0)), "tests/test_src_doc_attribution.py:65")
TestSupport.check(e("fwd").field("src_start").cmp("Lt", e("fwd").field("thy_line")), "tests/test_src_doc_attribution.py:66" + " actual=" + e("fwd").field("src_start") + " expected=" + e("fwd").field("thy_line"))
}
private def case_PreambleOwnership_test_preceding_entry_src_stops_before_the_doc(): Unit = TestSupport.test("test_src_doc_attribution.PreambleOwnership.test_preceding_entry_src_stops_before_the_doc") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
val unpack2 = vs(h__entry(e, Vector(e("sec"), V("back_loop")), Map()), h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())); TestSupport.equal(unpack2.seq.size, 2, "unpack arity"); e("back") = unpack2.at(V(0)); e("fwd") = unpack2.at(V(1))
TestSupport.check(e("back").field("thy_end").cmp("Lt", e("fwd").field("preamble").at(V(0))), "tests/test_src_doc_attribution.py:72" + " actual=" + e("back").field("thy_end") + " expected=" + e("fwd").field("preamble").at(V(0)))
TestSupport.check(e("back").field("thy_end").cmp("GtE", e("back").field("body_end_line")), "tests/test_src_doc_attribution.py:74" + " actual=" + e("back").field("thy_end") + " expected=" + e("back").field("body_end_line"))
}
private def case_PreambleOwnership_test_body_span_is_unchanged_and_inside_src(): Unit = TestSupport.test("test_src_doc_attribution.PreambleOwnership.test_body_span_is_unchanged_and_inside_src") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("fwd") = h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())
TestSupport.equal(e("fwd").field("body_end_line"), e("fwd").field("thy_end"), "tests/test_src_doc_attribution.py:80")
TestSupport.check(e("fwd").field("thy_line").cmp("Gt", e("fwd").field("src_start")), "tests/test_src_doc_attribution.py:81" + " actual=" + e("fwd").field("thy_line") + " expected=" + e("fwd").field("src_start"))
}
private def case_EnclosingAttribution_test_doc_line_resolves_to_following_entry(): Unit = TestSupport.test("test_src_doc_attribution.EnclosingAttribution.test_doc_line_resolves_to_following_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("fwd") = h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())
e("doc_mid") = e("fwd").field("preamble").at(V(0)).plus(V(1))
e("owner") = h_EnclosingAttribution__owner(e, Vector(e("sec"), e("doc_mid")), Map())
TestSupport.check(!e("owner").isNone, "tests/test_src_doc_attribution.py:96")
TestSupport.equal(e("owner").field("name"), V("fwd_loop_gen"), "tests/test_src_doc_attribution.py:97")
TestSupport.equal(ParserBridge.call("cli._locus_role", Vector(e("owner"), e("doc_mid")), Map()), V("in preamble"), "tests/test_src_doc_attribution.py:98")
}
private def case_EnclosingAttribution_test_back_loop_still_owns_its_proof(): Unit = TestSupport.test("test_src_doc_attribution.EnclosingAttribution.test_back_loop_still_owns_its_proof") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("back") = h__entry(e, Vector(e("sec"), V("back_loop")), Map())
e("owner") = h_EnclosingAttribution__owner(e, Vector(e("sec"), e("back").field("proof_line")), Map())
TestSupport.equal(e("owner").field("name"), V("back_loop"), "tests/test_src_doc_attribution.py:104")
TestSupport.equal(ParserBridge.call("cli._locus_role", Vector(e("owner"), e("back").field("proof_line")), Map()), V("in proof"), "tests/test_src_doc_attribution.py:105")
}
private def case_EnclosingAttribution_test_binary_search_index_agrees(): Unit = TestSupport.test("test_src_doc_attribution.EnclosingAttribution.test_binary_search_index_agrees") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("fwd") = h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())
e("idx") = ParserBridge.call("cli._build_line_index", Vector(vs(e("sec"))), Map()).at(e("sec").field("path"))
e("owner") = ParserBridge.call("cli._entry_at_line", Vector(e("idx"), e("fwd").field("preamble").at(V(0)).plus(V(1))), Map())
TestSupport.equal(e("owner").field("name"), V("fwd_loop_gen"), "tests/test_src_doc_attribution.py:114")
}
private def case_ExtentRendering_test_following_entry_extent_shows_preamble_and_body(): Unit = TestSupport.test("test_src_doc_attribution.ExtentRendering.test_following_entry_extent_shows_preamble_and_body") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("fwd") = h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())
e("ext") = ParserBridge.call("cli._format_extent", Vector(e("fwd")), Map())
TestSupport.check(e("ext").has(V("src " + e("fwd").field("src_start").str + ".." + e("fwd").field("thy_end").str)), "tests/test_src_doc_attribution.py:125" + " actual=" + e("ext"))
TestSupport.check(e("ext").has(V("body " + e("fwd").field("thy_line").str + ".." + e("fwd").field("body_end_line").str)), "tests/test_src_doc_attribution.py:126" + " actual=" + e("ext"))
}
private def case_ExtentRendering_test_preceding_entry_extent_does_not_reach_the_doc(): Unit = TestSupport.test("test_src_doc_attribution.ExtentRendering.test_preceding_entry_extent_does_not_reach_the_doc") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
val unpack3 = vs(h__entry(e, Vector(e("sec"), V("back_loop")), Map()), h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())); TestSupport.equal(unpack3.seq.size, 2, "unpack arity"); e("back") = unpack3.at(V(0)); e("fwd") = unpack3.at(V(1))
e("ext") = ParserBridge.call("cli._format_extent", Vector(e("back")), Map())
TestSupport.check(e("ext").has(V("src " + e("back").field("src_start").str + ".." + e("back").field("thy_end").str)), "tests/test_src_doc_attribution.py:132" + " actual=" + e("ext"))
TestSupport.check(!e("ext").invoke("split", Vector(V("body")), Map()).at(V(0)).has(ParserBridge.call("str", Vector(e("fwd").field("preamble").at(V(0))), Map())), "tests/test_src_doc_attribution.py:134" + " actual=" + e("ext").invoke("split", Vector(V("body")), Map()).at(V(0)))
}
private def case_EndToEndEnclosing_test_doc_line_names_the_documented_lemma(): Unit = TestSupport.test("test_src_doc_attribution.EndToEndEnclosing.test_doc_line_names_the_documented_lemma") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory T imports Main begin\n\nlemma back_loop:\n  \"P x\"\n  by simp\n\ntext \\<open>\n  This block documents the forward lemma below.\n  It is fwd_loop_gen's docstring, not back_loop's trailing text.\n\\<close>\n\nlemma fwd_loop_gen:\n  \"Q x\"\n  by simp\n\nend\n")

e("sec") = h__sec(e, Vector(), Map())
e("fwd") = h__entry(e, Vector(e("sec"), V("fwd_loop_gen")), Map())
val unpack4 = h_EndToEndEnclosing__run(e, Vector(V("T:" + e("fwd").field("preamble").at(V(0)).plus(V(1)).str)), Map()); TestSupport.equal(unpack4.seq.size, 2, "unpack arity"); e("out") = unpack4.at(V(0)); e("err") = unpack4.at(V(1))
TestSupport.check(e("out").has(V("fwd_loop_gen (LEMMA)")), "tests/test_src_doc_attribution.py:150" + " actual=" + e("out"))
TestSupport.check(e("out").has(V("(in preamble)")), "tests/test_src_doc_attribution.py:151" + " actual=" + e("out"))
TestSupport.check(!e("out").has(V("back_loop")), "tests/test_src_doc_attribution.py:152" + " actual=" + e("out"))
TestSupport.equal(e("err"), V(""), "tests/test_src_doc_attribution.py:153")
}
def run(): Unit = { case_PreambleOwnership_test_doc_block_is_the_following_entry_preamble(); case_PreambleOwnership_test_preceding_entry_src_stops_before_the_doc(); case_PreambleOwnership_test_body_span_is_unchanged_and_inside_src(); case_EnclosingAttribution_test_doc_line_resolves_to_following_entry(); case_EnclosingAttribution_test_back_loop_still_owns_its_proof(); case_EnclosingAttribution_test_binary_search_index_agrees(); case_ExtentRendering_test_following_entry_extent_shows_preamble_and_body(); case_ExtentRendering_test_preceding_entry_extent_does_not_reach_the_doc(); case_EndToEndEnclosing_test_doc_line_names_the_documented_lemma() }
}
