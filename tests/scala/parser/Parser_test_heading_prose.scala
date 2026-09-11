package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_heading_prose.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_heading_prose {
private def case_HeadingsAreNotIsar_test_a_heading_does_not_cite(): Unit = TestSupport.test("test_heading_prose.HeadingsAreNotIsar.test_a_heading_does_not_cite") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nlemma helper: \"True\" by simp\n\nsubsection \\<open>Consequences proved using helper below\\<close>\n\nlemma user: \"True\" by simp\n")).plus(e("FOOT"))), Map())
e("cg") = ParserBridge.call("graph._build_call_graph", Vector(vs(e("sec"))), Map())
TestSupport.equal(ParserBridge.call("dict", Vector(e("cg").field("callers")), Map()).invoke("get", Vector(V("helper"), ParserBridge.call("set", Vector(), Map())), Map()), ParserBridge.call("set", Vector(), Map()), "tests/test_heading_prose.py:50")
}
private def case_HeadingsAreNotIsar_test_a_heading_does_not_name_a_method(): Unit = TestSupport.test("test_heading_prose.HeadingsAreNotIsar.test_a_heading_does_not_name_a_method") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

val unpack1 = ParserBridge.call("cli._scan_methods", Vector(vs(ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nsubsection \\<open>Mapping defined by a set of key-value pairs\\<close>\n\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map()))), Map()); TestSupport.equal(unpack1.seq.size, 2, "unpack arity"); e("counts") = unpack1.at(V(0)); e("_") = unpack1.at(V(1))
TestSupport.check(!e("counts").has(V("a")), "tests/test_heading_prose.py:62" + " actual=" + e("counts"))
TestSupport.equal(e("counts").invoke("get", Vector(V("simp")), Map()), V(1), "tests/test_heading_prose.py:63")
}
private def case_HeadingsAreNotIsar_test_every_heading_level_and_both_spellings(): Unit = TestSupport.test("test_heading_prose.HeadingsAreNotIsar.test_every_heading_level_and_both_spellings") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nchapter \\<open>One\\<close>\nsection \\<open>Two\\<close>\nsubsection ‹Three›\nsubsubsection\\<open>Four, with no space before the cartouche\\<close>\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("sec").field("heading_spans")), Map()), V(4), V("spans: " + e("sec").field("heading_spans").str).str)
}
private def case_HeadingsAreNotIsar_test_a_wrapped_heading_carries_its_continuation_lines(): Unit = TestSupport.test("test_heading_prose.HeadingsAreNotIsar.test_a_wrapped_heading_carries_its_continuation_lines") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nsubsection\\<open>Implementation and formal proof\n  of the matrices $P$ and $Q$ which transform the input matrix by means\n  of elementary operations.\\<close>\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("sec").field("heading_spans")), Map()), V(1), V("spans: " + e("sec").field("heading_spans").str).str)
val unpack2 = e("sec").field("heading_spans").at(V(0)); TestSupport.equal(unpack2.seq.size, 2, "unpack arity"); e("start") = unpack2.at(V(0)); e("end") = unpack2.at(V(1))
TestSupport.equal(e("end").minus(e("start")), V(2), V("span " + e("start").str + ".." + e("end").str + " is not 3 lines").str)
val unpack3 = ParserBridge.call("cli._scan_methods", Vector(vs(e("sec"))), Map()); TestSupport.equal(unpack3.seq.size, 2, "unpack arity"); e("counts") = unpack3.at(V(0)); e("_") = unpack3.at(V(1))
TestSupport.check(!e("counts").has(V("means")), "tests/test_heading_prose.py:91" + " actual=" + e("counts"))
}
private def case_EveryHeadingSpelling_test_a_quoted_title_is_prose(): Unit = TestSupport.test("test_heading_prose.EveryHeadingSpelling.test_a_quoted_title_is_prose") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nsection \"Existence of a chain cover proved by induction\"\n\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("sec").field("heading_spans")), Map()), V(1), V(e("sec").field("heading_spans").str).str)
val unpack4 = ParserBridge.call("cli._scan_methods", Vector(vs(e("sec"))), Map()); TestSupport.equal(unpack4.seq.size, 2, "unpack arity"); e("counts") = unpack4.at(V(0)); e("_") = unpack4.at(V(1))
TestSupport.check(!e("counts").has(V("induction")), "tests/test_heading_prose.py:114" + " actual=" + e("counts"))
TestSupport.equal(e("counts").invoke("get", Vector(V("simp")), Map()), V(1), "tests/test_heading_prose.py:115")
}
private def case_EveryHeadingSpelling_test_paragraph_and_subparagraph_are_headings(): Unit = TestSupport.test("test_heading_prose.EveryHeadingSpelling.test_paragraph_and_subparagraph_are_headings") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nparagraph \\<open>Proved by induction on the list\\<close>\nsubparagraph \"And by cases on the head\"\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("sec").field("heading_spans")), Map()), V(2), V(e("sec").field("heading_spans").str).str)
val unpack5 = ParserBridge.call("cli._scan_methods", Vector(vs(e("sec"))), Map()); TestSupport.equal(unpack5.seq.size, 2, "unpack arity"); e("counts") = unpack5.at(V(0)); e("_") = unpack5.at(V(1))
TestSupport.check(!e("counts").has(V("induction")), "tests/test_heading_prose.py:127" + " actual=" + e("counts"))
TestSupport.check(!e("counts").has(V("cases")), "tests/test_heading_prose.py:128" + " actual=" + e("counts"))
}
private def case_EveryHeadingSpelling_test_a_split_opener_carries_its_title_line(): Unit = TestSupport.test("test_heading_prose.EveryHeadingSpelling.test_a_split_opener_carries_its_title_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nsection\n  \\<open>Sufficient criteria for being a morphism\\<close>\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(e("sec").field("heading_spans"), vs(vs(V(3), V(4))), "tests/test_heading_prose.py:138")
}
private def case_EveryHeadingSpelling_test_a_wrapped_quoted_title_carries_its_continuation(): Unit = TestSupport.test("test_heading_prose.EveryHeadingSpelling.test_a_wrapped_quoted_title_carries_its_continuation") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nsection \"Size of an antichain is less than or equal to the\nsize of a chain cover proved by induction\"\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(e("sec").field("heading_spans"), vs(vs(V(3), V(4))), "tests/test_heading_prose.py:149")
val unpack6 = ParserBridge.call("cli._scan_methods", Vector(vs(e("sec"))), Map()); TestSupport.equal(unpack6.seq.size, 2, "unpack arity"); e("counts") = unpack6.at(V(0)); e("_") = unpack6.at(V(1))
TestSupport.check(!e("counts").has(V("induction")), "tests/test_heading_prose.py:151" + " actual=" + e("counts"))
}
private def case_EveryHeadingSpelling_test_a_heading_keyword_inside_prose_is_not_a_heading(): Unit = TestSupport.test("test_heading_prose.EveryHeadingSpelling.test_a_heading_keyword_inside_prose_is_not_a_heading") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext \\<open>\n  The proof follows Kleinberg and Tardos: \"Algorithm Design\",\n  chapter \"Dynamic Programming\".\n\\<close>\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(e("sec").field("heading_spans"), vs(), "tests/test_heading_prose.py:165")
TestSupport.equal(ParserBridge.call("parsing.extract_sections", Vector(e("sec").invoke("source", Vector(), Map()), e("sec").field("text_blocks")), Map()), vs(), "tests/test_heading_prose.py:166")
}
private def case_EveryHeadingSpelling_test_an_unbalanced_quote_in_prose_cannot_mask_live_code(): Unit = TestSupport.test("test_heading_prose.EveryHeadingSpelling.test_an_unbalanced_quote_in_prose_cannot_mask_live_code") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext \\<open>\n  See Kleinberg's chapter \"Dynamic Programming\n\\<close>\nlemma m: \"True\" by simp\n")).plus(e("FOOT"))), Map())
TestSupport.equal(e("sec").field("heading_spans"), vs(), "tests/test_heading_prose.py:179")
val unpack7 = ParserBridge.call("cli._scan_methods", Vector(vs(e("sec"))), Map()); TestSupport.equal(unpack7.seq.size, 2, "unpack arity"); e("counts") = unpack7.at(V(0)); e("_") = unpack7.at(V(1))
TestSupport.equal(e("counts").invoke("get", Vector(V("simp")), Map()), V(1), "tests/test_heading_prose.py:181")
}
private def case_HeadingsAreNotDocstrings_test_a_heading_is_masked_but_is_not_a_preamble(): Unit = TestSupport.test("test_heading_prose.HeadingsAreNotDocstrings.test_a_heading_is_masked_but_is_not_a_preamble") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nsubsection \\<open>About the helper\\<close>\nlemma helper: \"True\" by simp\n")).plus(e("FOOT"))), Map())
e("entry") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c8 = e.clone(); c8("e") = item; if (V((c8("e").field("name").cmp("Eq", V("helper")))).truth) Vector(c8("e")) else Vector.empty })), Map())
e("noise") = ParserBridge.call("graph._noise_spans", Vector(e("sec")), Map())
TestSupport.check(e("noise").has(e("sec").field("heading_spans").at(V(0))), "tests/test_heading_prose.py:195" + " actual=" + e("noise"))
TestSupport.check(e("entry").field("preamble").isNone, "tests/test_heading_prose.py:199")
TestSupport.equal(e("sec").field("text_blocks"), vs(), "tests/test_heading_prose.py:200")
}
private def case_HeadingsAreNotDocstrings_test_a_text_block_still_is_a_preamble(): Unit = TestSupport.test("test_heading_prose.HeadingsAreNotDocstrings.test_a_text_block_still_is_a_preamble") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext \\<open>Why the helper exists.\\<close>\nlemma helper: \"True\" by simp\n")).plus(e("FOOT"))), Map())
e("entry") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c9 = e.clone(); c9("e") = item; if (V((c9("e").field("name").cmp("Eq", V("helper")))).truth) Vector(c9("e")) else Vector.empty })), Map())
TestSupport.check(!e("entry").field("preamble").isNone, "tests/test_heading_prose.py:209")
}
def run(): Unit = { case_HeadingsAreNotIsar_test_a_heading_does_not_cite(); case_HeadingsAreNotIsar_test_a_heading_does_not_name_a_method(); case_HeadingsAreNotIsar_test_every_heading_level_and_both_spellings(); case_HeadingsAreNotIsar_test_a_wrapped_heading_carries_its_continuation_lines(); case_EveryHeadingSpelling_test_a_quoted_title_is_prose(); case_EveryHeadingSpelling_test_paragraph_and_subparagraph_are_headings(); case_EveryHeadingSpelling_test_a_split_opener_carries_its_title_line(); case_EveryHeadingSpelling_test_a_wrapped_quoted_title_carries_its_continuation(); case_EveryHeadingSpelling_test_a_heading_keyword_inside_prose_is_not_a_heading(); case_EveryHeadingSpelling_test_an_unbalanced_quote_in_prose_cannot_mask_live_code(); case_HeadingsAreNotDocstrings_test_a_heading_is_masked_but_is_not_a_preamble(); case_HeadingsAreNotDocstrings_test_a_text_block_still_is_a_preamble() }
}
