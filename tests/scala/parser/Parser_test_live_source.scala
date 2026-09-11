package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_live_source.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_live_source {
private def h_live(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
e("theory") = kw.getOrElse("theory", args.lift(1).getOrElse(V("A")))
return ParserBridge.call("section_from", Vector(e("snippet"), e("theory")), Map()).invoke("live_source", Vector(), Map())
V.none
}
private def h_BlankSpans_check(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("line") = kw.getOrElse("line", args.lift(0).getOrElse(throw new IllegalArgumentException("missing line")))
e("spans") = kw.getOrElse("spans", args.lift(1).getOrElse(throw new IllegalArgumentException("missing spans")))
e("expected") = kw.getOrElse("expected", args.lift(2).getOrElse(throw new IllegalArgumentException("missing expected")))
e("got") = ParserBridge.call("model._blank_spans", Vector(e("line"), e("spans")), Map())
TestSupport.equal(e("got"), e("expected"), "tests/test_live_source.py:178")
TestSupport.equal(ParserBridge.call("len", Vector(e("got")), Map()), ParserBridge.call("len", Vector(e("line")), Map()), "tests/test_live_source.py:179")
V.none
}
private def case_Shape_test_line_count_is_preserved(): Unit = TestSupport.test("test_live_source.Shape.test_line_count_is_preserved") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
TestSupport.equal(ParserBridge.call("len", Vector(e("sec").invoke("live_source", Vector(), Map())), Map()), ParserBridge.call("len", Vector(e("sec").invoke("source", Vector(), Map())), Map()), "tests/test_live_source.py:58")
}
private def case_Shape_test_every_line_keeps_its_length(): Unit = TestSupport.test("test_live_source.Shape.test_every_line_keeps_its_length") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
TestSupport.equal(V.seq(e("sec").invoke("live_source", Vector(), Map()).seq.flatMap { item => val c1 = e.clone(); c1("ln") = item; if (true) Vector(ParserBridge.call("len", Vector(c1("ln")), Map())) else Vector.empty }), V.seq(e("sec").invoke("source", Vector(), Map()).seq.flatMap { item => val c2 = e.clone(); c2("ln") = item; if (true) Vector(ParserBridge.call("len", Vector(c2("ln")), Map())) else Vector.empty }), "tests/test_live_source.py:62")
}
private def case_Shape_test_redaction_only_ever_writes_spaces(): Unit = TestSupport.test("test_live_source.Shape.test_redaction_only_ever_writes_spaces") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
ParserBridge.call("zip", Vector(e("sec").invoke("source", Vector(), Map()), e("sec").invoke("live_source", Vector(), Map())), Map()).seq.foreach { item => val unpack3 = item; TestSupport.equal(unpack3.seq.size, 2, "unpack arity"); e("raw") = unpack3.at(V(0)); e("red") = unpack3.at(V(1));
ParserBridge.call("zip", Vector(e("raw"), e("red")), Map()).seq.foreach { item => val unpack4 = item; TestSupport.equal(unpack4.seq.size, 2, "unpack arity"); e("a") = unpack4.at(V(0)); e("b") = unpack4.at(V(1));
TestSupport.check(vs(e("a"), V(" ")).has(e("b")), "tests/test_live_source.py:71" + " actual=" + vs(e("a"), V(" ")))
}
}
}
private def case_Shape_test_source_is_not_mutated(): Unit = TestSupport.test("test_live_source.Shape.test_source_is_not_mutated") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
e("before") = ParserBridge.call("list", Vector(e("sec").invoke("source", Vector(), Map())), Map())
e("sec").invoke("live_source", Vector(), Map())
TestSupport.equal(e("sec").invoke("source", Vector(), Map()), e("before"), "tests/test_live_source.py:77")
TestSupport.check(V("\n").invoke("join", Vector(e("sec").invoke("source", Vector(), Map())), Map()).has(V("trailing prose")), "tests/test_live_source.py:78" + " actual=" + V("\n").invoke("join", Vector(e("sec").invoke("source", Vector(), Map())), Map()))
}
private def case_WhatIsBlanked_test_trailing_comment_goes_live_text_stays(): Unit = TestSupport.test("test_live_source.WhatIsBlanked.test_trailing_comment_goes_live_text_stays") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"True\" by (simp add: foo) (* not baz *)\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).has(V("by (simp add: foo)")), "tests/test_live_source.py:98" + " actual=" + e("out").at(V(4)))
TestSupport.check(!e("out").at(V(4)).has(V("baz")), "tests/test_live_source.py:99" + " actual=" + e("out").at(V(4)))
}
private def case_WhatIsBlanked_test_multi_line_comment_body_is_blank(): Unit = TestSupport.test("test_live_source.WhatIsBlanked.test_multi_line_comment_body_is_blank") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"True\"\n  (* first\n     second *)\n  by simp\n\nend\n")), Map())
TestSupport.equal(e("out").at(V(5)).invoke("strip", Vector(), Map()), V(""), "tests/test_live_source.py:107")
TestSupport.equal(e("out").at(V(6)).invoke("strip", Vector(), Map()), V(""), "tests/test_live_source.py:108")
TestSupport.equal(e("out").at(V(7)).invoke("strip", Vector(), Map()), V("by simp"), "tests/test_live_source.py:109")
}
private def case_WhatIsBlanked_test_nested_comment_is_blank_throughout(): Unit = TestSupport.test("test_live_source.WhatIsBlanked.test_nested_comment_is_blank_throughout") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"True\"\n  (* outer (* inner *) still outer *)\n  by simp\n\nend\n")), Map())
TestSupport.equal(e("out").at(V(5)).invoke("strip", Vector(), Map()), V(""), "tests/test_live_source.py:116")
}
private def case_WhatIsBlanked_test_cancel_region_goes(): Unit = TestSupport.test("test_live_source.WhatIsBlanked.test_cancel_region_goes") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"True\"\n  using helper \\<^cancel>\\<open>and other\\<close> by simp\n\nend\n")), Map())
TestSupport.check(e("out").at(V(5)).has(V("using helper")), "tests/test_live_source.py:123" + " actual=" + e("out").at(V(5)))
TestSupport.check(!e("out").at(V(5)).has(V("other")), "tests/test_live_source.py:124" + " actual=" + e("out").at(V(5)))
TestSupport.check(e("out").at(V(5)).has(V("by simp")), "tests/test_live_source.py:125" + " actual=" + e("out").at(V(5)))
}
private def case_WhatIsBlanked_test_ml_body_goes_but_the_command_stays(): Unit = TestSupport.test("test_live_source.WhatIsBlanked.test_ml_body_goes_but_the_command_stays") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nML \\<open>\n  val secret = 1;\n\\<close>\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).invoke("startswith", Vector(V("ML")), Map()).truth, "tests/test_live_source.py:130")
TestSupport.equal(e("out").at(V(5)).invoke("strip", Vector(), Map()), V(""), "tests/test_live_source.py:131")
TestSupport.equal(e("out").at(V(6)).invoke("strip", Vector(), Map()), V(""), "tests/test_live_source.py:132")
}
private def case_WhatIsBlanked_test_legacy_verbatim_goes(): Unit = TestSupport.test("test_live_source.WhatIsBlanked.test_legacy_verbatim_goes") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"True\" by simp {* legacy foo *}\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).has(V("by simp")), "tests/test_live_source.py:137" + " actual=" + e("out").at(V(4)))
TestSupport.check(!e("out").at(V(4)).has(V("legacy")), "tests/test_live_source.py:138" + " actual=" + e("out").at(V(4)))
ParserLegacy.inlineGuards()
}
private def case_WhatIsKeptLive_test_quoted_term_is_untouched(): Unit = TestSupport.test("test_live_source.WhatIsKeptLive.test_quoted_term_is_untouched") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"mono f \\<and> True\" by simp\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).has(V("\"mono f \\<and> True\"")), "tests/test_live_source.py:147" + " actual=" + e("out").at(V(4)))
}
private def case_WhatIsKeptLive_test_comment_opener_inside_a_string_is_untouched(): Unit = TestSupport.test("test_live_source.WhatIsKeptLive.test_comment_opener_inside_a_string_is_untouched") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \"fold (*) [1, 2] x = y\" by simp\n\nlemma baz: \"True\" by simp\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).has(V("fold (*) [1, 2] x = y")), "tests/test_live_source.py:155" + " actual=" + e("out").at(V(4)))
TestSupport.check(e("out").at(V(6)).has(V("baz")), "tests/test_live_source.py:156" + " actual=" + e("out").at(V(6)))
}
private def case_WhatIsKeptLive_test_bare_cartouche_is_untouched(): Unit = TestSupport.test("test_live_source.WhatIsKeptLive.test_bare_cartouche_is_untouched") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \\<open>fold (*) xs = y\\<close> by simp\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).has(V("\\<open>fold (*) xs = y\\<close>")), "tests/test_live_source.py:162" + " actual=" + e("out").at(V(4)))
}
private def case_WhatIsKeptLive_test_text_block_prose_is_untouched(): Unit = TestSupport.test("test_live_source.WhatIsKeptLive.test_text_block_prose_is_untouched") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("out") = h_live(e, Vector(V("theory A\nimports Main\nbegin\n\ntext \\<open>Prose mentioning foo.\\<close>\n\nlemma bar: \"True\" by simp\n\nend\n")), Map())
TestSupport.check(e("out").at(V(4)).has(V("Prose mentioning foo.")), "tests/test_live_source.py:170" + " actual=" + e("out").at(V(4)))
}
private def case_BlankSpans_test_single_span(): Unit = TestSupport.test("test_live_source.BlankSpans.test_single_span") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

h_BlankSpans_check(e, Vector(V("abcdef"), vs(vs(V(2), V(4))), V("ab  ef")), Map())
}
private def case_BlankSpans_test_two_spans(): Unit = TestSupport.test("test_live_source.BlankSpans.test_two_spans") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

h_BlankSpans_check(e, Vector(V("abcdef"), vs(vs(V(0), V(1)), vs(V(4), V(6))), V(" bcd  ")), Map())
}
private def case_BlankSpans_test_span_to_end_of_line(): Unit = TestSupport.test("test_live_source.BlankSpans.test_span_to_end_of_line") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

h_BlankSpans_check(e, Vector(V("by simp (*x*)"), vs(vs(V(8), V(13))), V("by simp").plus(V(" ").times(V(6)))), Map())
}
private def case_BlankSpans_test_span_past_the_end_is_clamped(): Unit = TestSupport.test("test_live_source.BlankSpans.test_span_past_the_end_is_clamped") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

h_BlankSpans_check(e, Vector(V("abc"), vs(vs(V(1), V(99))), V("a  ")), Map())
}
private def case_BlankSpans_test_no_spans_is_identity(): Unit = TestSupport.test("test_live_source.BlankSpans.test_no_spans_is_identity") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

h_BlankSpans_check(e, Vector(V("abc"), vs(), V("abc")), Map())
}
private def case_BlankSpans_test_overlapping_spans_do_not_shift_the_line(): Unit = TestSupport.test("test_live_source.BlankSpans.test_overlapping_spans_do_not_shift_the_line") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

h_BlankSpans_check(e, Vector(V("abcdef"), vs(vs(V(1), V(4)), vs(V(2), V(5))), V("a    f")), Map())
}
private def case_Parity_test_fully_blank_lines_are_exactly_the_reported_ranges(): Unit = TestSupport.test("test_live_source.Parity.test_fully_blank_lines_are_exactly_the_reported_ranges") {
val e: Env = scala.collection.mutable.Map.empty

e("Shape.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp   (* trailing prose *)\n\nlemma bar: \"True\"\n  (* a comment\n     running over\n     three lines *)\n  by simp\n\nML \\<open>\n  val x = 1;\n\\<close>\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("Shape.SNIPPET"), V("A")), Map())
e("from_cols") = V.set(ParserBridge.call("enumerate", Vector(e("sec").invoke("live_source", Vector(), Map()), V(1)), Map()).seq.flatMap { item => val c5 = e.clone(); val unpack6 = item; TestSupport.equal(unpack6.seq.size, 2, "unpack arity"); c5("i") = unpack6.at(V(0)); c5("ln") = unpack6.at(V(1)); if (V((c5("sec").invoke("source", Vector(), Map()).at(c5("i").minus(V(1))).invoke("strip", Vector(), Map()).truth) && (V(!c5("ln").invoke("strip", Vector(), Map()).truth).truth)).truth) Vector(c5("i")) else Vector.empty })
e("from_ranges") = V.set(e("sec").field("nonisar_ranges").seq.flatMap { item => val c7 = e.clone(); val unpack8 = item; TestSupport.equal(unpack8.seq.size, 2, "unpack arity"); c7("lo") = unpack8.at(V(0)); c7("hi") = unpack8.at(V(1)); if (true) ParserBridge.call("range", Vector(c7("lo"), c7("hi").plus(V(1))), Map()).seq.flatMap { item => val c9 = c7.clone(); c9("i") = item; if (c9("sec").invoke("source", Vector(), Map()).at(c9("i").minus(V(1))).invoke("strip", Vector(), Map()).truth) Vector(c9("i")) else Vector.empty } else Vector.empty })
TestSupport.equal(e("from_cols"), e("from_ranges"), "tests/test_live_source.py:212")
}
def run(): Unit = { case_Shape_test_line_count_is_preserved(); case_Shape_test_every_line_keeps_its_length(); case_Shape_test_redaction_only_ever_writes_spaces(); case_Shape_test_source_is_not_mutated(); case_WhatIsBlanked_test_trailing_comment_goes_live_text_stays(); case_WhatIsBlanked_test_multi_line_comment_body_is_blank(); case_WhatIsBlanked_test_nested_comment_is_blank_throughout(); case_WhatIsBlanked_test_cancel_region_goes(); case_WhatIsBlanked_test_ml_body_goes_but_the_command_stays(); case_WhatIsBlanked_test_legacy_verbatim_goes(); case_WhatIsKeptLive_test_quoted_term_is_untouched(); case_WhatIsKeptLive_test_comment_opener_inside_a_string_is_untouched(); case_WhatIsKeptLive_test_bare_cartouche_is_untouched(); case_WhatIsKeptLive_test_text_block_prose_is_untouched(); case_BlankSpans_test_single_span(); case_BlankSpans_test_two_spans(); case_BlankSpans_test_span_to_end_of_line(); case_BlankSpans_test_span_past_the_end_is_clamped(); case_BlankSpans_test_no_spans_is_identity(); case_BlankSpans_test_overlapping_spans_do_not_shift_the_line(); case_Parity_test_fully_blank_lines_are_exactly_the_reported_ranges() }
}
