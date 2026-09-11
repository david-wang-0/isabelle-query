package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_txt_prose.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_txt_prose {
private def case_TxtIsAProseBlock_test_txt_block_is_recorded(): Unit = TestSupport.test("test_txt_prose.TxtIsAProseBlock.test_txt_block_is_recorded") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(e("REAL_PROSE")).plus(e("FOOT"))), Map())
e("blocks") = ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("blocks")), Map()), V(1), V("blocks: " + e("blocks").str).str)
val unpack1 = e("blocks").at(V(0)); TestSupport.equal(unpack1.seq.size, 2, "unpack arity"); e("start") = unpack1.at(V(0)); e("end") = unpack1.at(V(1))
TestSupport.check(e("sec").invoke("source", Vector(), Map()).at(e("start").minus(V(1))).invoke("strip", Vector(), Map()).invoke("startswith", Vector(V("txt")), Map()).truth, "tests/test_txt_prose.py:61")
TestSupport.check(e("sec").invoke("source", Vector(), Map()).at(e("end").minus(V(1))).has(V("\\<close>")), "tests/test_txt_prose.py:62" + " actual=" + e("sec").invoke("source", Vector(), Map()).at(e("end").minus(V(1))))
}
private def case_TxtIsAProseBlock_test_no_proof_step_lands_in_the_prose(): Unit = TestSupport.test("test_txt_prose.TxtIsAProseBlock.test_no_proof_step_lands_in_the_prose") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(e("REAL_PROSE")).plus(e("FOOT"))), Map())
e("entry") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (V((c2("e").field("name").cmp("Eq", V("phiArg_nontrivial")))).truth) Vector(c2("e")) else Vector.empty })), Map())
e("pm") = ParserBridge.call("shape.analyze_proof", Vector(e("sec"), e("entry")), Map())
e("prose") = ParserBridge.call("range", Vector() ++ V.seq(ParserBridge.call("enumerate", Vector(ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map()).at(V(0))), Map()).seq.flatMap { item => val c3 = e.clone(); val unpack4 = item; TestSupport.equal(unpack4.seq.size, 2, "unpack arity"); c3("i") = unpack4.at(V(0)); c3("b") = unpack4.at(V(1)); if (true) Vector(c3("b").plus(vs(V(0), V(1)).at(c3("i")))) else Vector.empty }).seq, Map())
e("inside") = V.seq(e("pm").field("steps").seq.flatMap { item => val c5 = e.clone(); c5("s") = item; if (V((c5("prose").has(c5("s").field("line")))).truth) Vector(vs(c5("s").field("line"), c5("s").field("kw"))) else Vector.empty })
TestSupport.equal(e("inside"), vs(), V("phantom steps: " + e("inside").str).str)
}
private def case_TxtIsAProseBlock_test_the_english_by_registers_no_method(): Unit = TestSupport.test("test_txt_prose.TxtIsAProseBlock.test_the_english_by_registers_no_method") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

val unpack6 = ParserBridge.call("cli._scan_methods", Vector(vs(ParserBridge.call("section_from", Vector(e("HEAD").plus(e("REAL_PROSE")).plus(e("FOOT"))), Map()))), Map()); TestSupport.equal(unpack6.seq.size, 2, "unpack arity"); e("counts") = unpack6.at(V(0)); e("_") = unpack6.at(V(1))
TestSupport.check(!e("counts").has(V("at")), "tests/test_txt_prose.py:85" + " actual=" + e("counts"))
TestSupport.check(!e("counts").has(V("the")), "tests/test_txt_prose.py:86" + " actual=" + e("counts"))
TestSupport.equal(e("counts").invoke("get", Vector(V("simp")), Map()), V(2), "tests/test_txt_prose.py:87")
}
private def case_TheOtherSpellings_test_text_block_still_recognised(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_text_block_still_recognised") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext \\<open>Between declarations.\\<close>\n")).plus(V("lemma a: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())), Map()), V(1), "tests/test_txt_prose.py:96")
}
private def case_TheOtherSpellings_test_text_raw_still_recognised(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_text_raw_still_recognised") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext_raw \\<open>\\\\clearpage\\<close>\n")).plus(V("lemma a: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())), Map()), V(1), "tests/test_txt_prose.py:101")
}
private def case_TheOtherSpellings_test_bare_cartouche_spelling(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_bare_cartouche_spelling") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nlemma a: \"True\"\nproof -\n")).plus(V("  txt ‹proved by hand›\n")).plus(V("  show \"True\" by simp\nqed\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())), Map()), V(1), "tests/test_txt_prose.py:111")
}
private def case_TheOtherSpellings_test_split_opener(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_split_opener") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext\n  \\<open>We have developed two points of view on freeness:\n\\<^item> being generated by a code.\n\\<close>\nlemma a: \"True\" by simp\n")).plus(e("FOOT"))), Map())
e("blocks") = ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("blocks")), Map()), V(1), V("blocks: " + e("blocks").str).str)
val unpack7 = e("blocks").at(V(0)); TestSupport.equal(unpack7.seq.size, 2, "unpack arity"); e("start") = unpack7.at(V(0)); e("end") = unpack7.at(V(1))
TestSupport.equal(e("sec").invoke("source", Vector(), Map()).at(e("start").minus(V(1))).invoke("strip", Vector(), Map()), V("text"), "tests/test_txt_prose.py:129")
TestSupport.equal(e("sec").invoke("source", Vector(), Map()).at(e("end").minus(V(1))).invoke("strip", Vector(), Map()), V("\\<close>"), "tests/test_txt_prose.py:130")
}
private def case_TheOtherSpellings_test_a_bare_text_line_does_not_annex_a_later_block(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_a_bare_text_line_does_not_annex_a_later_block") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nlemma a: \"True\" by simp\ntext\nlemma b: \"True\" by simp\ntext \\<open>A real block, much later.\\<close>\n")).plus(e("FOOT"))), Map())
e("blocks") = ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("blocks")), Map()), V(1), V("blocks: " + e("blocks").str).str)
TestSupport.check(e("sec").invoke("source", Vector(), Map()).at(e("blocks").at(V(0)).at(V(0)).minus(V(1))).has(V("A real block")), "tests/test_txt_prose.py:144" + " actual=" + e("sec").invoke("source", Vector(), Map()).at(e("blocks").at(V(0)).at(V(0)).minus(V(1))))
}
private def case_TheOtherSpellings_test_multiline_cartouche_glyph_block_is_fully_covered(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_multiline_cartouche_glyph_block_is_fully_covered") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext ‹First line of prose,\n  second line mentioning by simp,\n  and a third.›\nlemma a: \"True\" by simp\n")).plus(e("FOOT"))), Map())
e("blocks") = ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("blocks")), Map()), V(1), V("blocks: " + e("blocks").str).str)
val unpack8 = e("blocks").at(V(0)); TestSupport.equal(unpack8.seq.size, 2, "unpack arity"); e("start") = unpack8.at(V(0)); e("end") = unpack8.at(V(1))
TestSupport.equal(e("end").minus(e("start")), V(2), V("span " + e("start").str + ".." + e("end").str + " is not 3 lines").str)
}
private def case_TheOtherSpellings_test_bodyless_comment_stays_one_line(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_bodyless_comment_stays_one_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nlemma a: \"True\" by simp \\<comment> tag-only\nlemma b: \"True\" by simp\ntext \\<open>Unrelated prose.\\<close>\n")).plus(e("FOOT"))), Map())
e("ranges") = ParserBridge.call("parsing.extract_comment_ranges", Vector(e("sec").invoke("source", Vector(), Map())), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("ranges")), Map()), V(1), V("ranges: " + e("ranges").str).str)
TestSupport.equal(e("ranges").at(V(0)).at(V(0)), e("ranges").at(V(0)).at(V(1)), "tests/test_txt_prose.py:172")
}
private def case_TheOtherSpellings_test_txt_raw_is_not_a_command(): Unit = TestSupport.test("test_txt_prose.TheOtherSpellings.test_txt_raw_is_not_a_command") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("REAL_PROSE") = V("\nlemma phiArg_nontrivial: \"True\"\nproof -\n  have step: \"True\" by simp\n  txt \\<open>They can either be:\n    \\<^item> The result of a direct assignment to v.\n    \\<^item> The result of a necessary $\\phi$ function r' . This however means\n      that r' was reachable by at least two different direct assignments to v.\n    \\<^item> Another unnecessary $\\phi$ function, apply the same argument.\\<close>\n  show \"True\" by simp\nqed\n")

TestSupport.check(ParserBridge.constant("isa_ns.KEYWORDS").has(V("txt")), "tests/test_txt_prose.py:181" + " actual=" + ParserBridge.constant("isa_ns.KEYWORDS"))
TestSupport.check(!ParserBridge.constant("isa_ns.KEYWORDS").has(V("txt_raw")), "tests/test_txt_prose.py:182" + " actual=" + ParserBridge.constant("isa_ns.KEYWORDS"))
e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntxt_raw \\<open>not a command\\<close>\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("parsing.extract_text_blocks", Vector(e("sec").invoke("source", Vector(), Map())), Map()), vs(), "tests/test_txt_prose.py:185")
}
def run(): Unit = { case_TxtIsAProseBlock_test_txt_block_is_recorded(); case_TxtIsAProseBlock_test_no_proof_step_lands_in_the_prose(); case_TxtIsAProseBlock_test_the_english_by_registers_no_method(); case_TheOtherSpellings_test_text_block_still_recognised(); case_TheOtherSpellings_test_text_raw_still_recognised(); case_TheOtherSpellings_test_bare_cartouche_spelling(); case_TheOtherSpellings_test_split_opener(); case_TheOtherSpellings_test_a_bare_text_line_does_not_annex_a_later_block(); case_TheOtherSpellings_test_multiline_cartouche_glyph_block_is_fully_covered(); case_TheOtherSpellings_test_bodyless_comment_stays_one_line(); case_TheOtherSpellings_test_txt_raw_is_not_a_command() }
}
