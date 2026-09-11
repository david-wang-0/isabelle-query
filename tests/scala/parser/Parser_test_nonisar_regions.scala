package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_nonisar_regions.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_nonisar_regions {
private def h_ranges(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return ParserBridge.call("parsing.extract_nonisar_ranges", Vector(e("snippet").invoke("split", Vector(V("\n")), Map())), Map())
V.none
}
private def h_graph_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
return ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map())
V.none
}
private def h_entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def h_enclosing(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("line_no") = kw.getOrElse("line_no", args.lift(1).getOrElse(throw new IllegalArgumentException("missing line_no")))
e("idx") = ParserBridge.call("cli._build_line_index", Vector(vs(e("sec"))), Map()).invoke("get", Vector(e("sec").field("path"), vs()), Map())
return ParserBridge.call("cli._entry_at_line", Vector(e("idx"), e("line_no")), Map())
V.none
}
private def h_PartialLines_callers_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("body") = kw.getOrElse("body", args.lift(0).getOrElse(throw new IllegalArgumentException("missing body")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma helper: \"True\" by simp\n\nlemma other: \"True\" by simp\n\n").plus(e("body")).plus(V("\n\nend\n")), V("A")), Map())
return h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(e("name"))
V.none
}
private def h_NoPhantomOnAPartialLine_graph_of_body(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("body") = kw.getOrElse("body", args.lift(0).getOrElse(throw new IllegalArgumentException("missing body")))
return h_graph_of(e, Vector(ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma helper: \"True\" by simp\n\nlemma other: \"True\" by simp\n\n").plus(e("body")).plus(V("\n\nend\n")), V("A")), Map())), Map())
V.none
}
private def h_NoPhantomDeclarations_names(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return V.seq(ParserBridge.call("section_from", Vector(e("snippet"), V("A")), Map()).field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (true) Vector(c2("e").field("name")) else Vector.empty })
V.none
}
private def case_Reproductions_test_r1_comment_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r1_comment_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R1"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:120")
}
private def case_Reproductions_test_r1_comment_does_not_hide_a_dead_lemma(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r1_comment_does_not_hide_a_dead_lemma") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R1"), V("A")), Map())
e("g") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map("derived" -> V(true)))
TestSupport.equal(ParserBridge.call("cli._compute_unused", Vector(e("g")), Map()), vset(V("foo"), V("bar")), "tests/test_nonisar_regions.py:127")
}
private def case_Reproductions_test_r2_ml_body_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r2_ml_body_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R2"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:133")
}
private def case_Reproductions_test_r2_ml_body_ends_the_span_above_it(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r2_ml_body_ends_the_span_above_it") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R2"), V("A")), Map())
e("bar") = h_entry(e, Vector(e("sec"), V("bar")), Map())
TestSupport.equal(vs(e("bar").field("src_start"), e("bar").field("thy_end")), vs(V(7), V(7)), "tests/test_nonisar_regions.py:140")
}
private def case_Reproductions_test_r3_cancel_region_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r3_cancel_region_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R3"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:146")
}
private def case_Reproductions_test_r4_commented_end_does_not_cut_the_span(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r4_commented_end_does_not_cut_the_span") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R4"), V("A")), Map())
e("bar") = h_entry(e, Vector(e("sec"), V("bar")), Map())
TestSupport.equal(vs(e("bar").field("src_start"), e("bar").field("thy_end")), vs(V(5), V(13)), "tests/test_nonisar_regions.py:151")
}
private def case_Reproductions_test_r4_live_proof_line_has_an_enclosing_entry(): Unit = TestSupport.test("test_nonisar_regions.Reproductions.test_r4_live_proof_line_has_an_enclosing_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("R4"), V("A")), Map())
e("e") = h_enclosing(e, Vector(e("sec"), V(12)), Map())
TestSupport.check(!e("e").isNone, "tests/test_nonisar_regions.py:157")
TestSupport.equal(e("e").field("name"), V("bar"), "tests/test_nonisar_regions.py:158")
}
private def case_MLBodyFamily_test_ml_body_with_no_entry_above_it(): Unit = TestSupport.test("test_nonisar_regions.MLBodyFamily.test_ml_body_with_no_entry_above_it") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nlemma foo: \"True\" by simp\n\nend\n"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:179")
}
private def case_MLBodyFamily_test_ml_body_below_the_only_entry(): Unit = TestSupport.test("test_nonisar_regions.MLBodyFamily.test_ml_body_below_the_only_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:199")
}
private def case_MLBodyFamily_test_ml_body_between_two_entries(): Unit = TestSupport.test("test_nonisar_regions.MLBodyFamily.test_ml_body_between_two_entries") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo\";\n\\<close>\n\nlemma baz: \"True\" by simp\n\nend\n"), V("A")), Map())
e("g") = h_graph_of(e, Vector(e("sec")), Map())
TestSupport.equal(e("g").field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:219")
TestSupport.equal(vs(h_entry(e, Vector(e("sec"), V("bar")), Map()).field("src_start"), h_entry(e, Vector(e("sec"), V("bar")), Map()).field("thy_end")), vs(V(7), V(7)), "tests/test_nonisar_regions.py:220")
TestSupport.check(V.set(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (true) Vector(c3("e").field("name")) else Vector.empty }).has(V("baz")), "tests/test_nonisar_regions.py:222" + " actual=" + V.set(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (true) Vector(c3("e").field("name")) else Vector.empty }))
}
private def case_MLBodyFamily_test_ml_file_ends_the_span_above_it(): Unit = TestSupport.test("test_nonisar_regions.MLBodyFamily.test_ml_file_ends_the_span_above_it") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nML_file \"helper.ML\"\n\nend\n"), V("A")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("thy_end"), V(5), "tests/test_nonisar_regions.py:237")
}
private def case_NestedAndInline_test_nested_comment_two_levels_deep(): Unit = TestSupport.test("test_nonisar_regions.NestedAndInline.test_nested_comment_two_levels_deep") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* outer\n     (* inner *)\n     foo is still inside the comment\n  *)\n  by simp\n\nend\n"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:259")
TestSupport.equal(h_ranges(e, Vector(V("(* outer\n   (* inner *)\n   still comment\n*)\nlive")), Map()), vs(vs(V(1), V(4))), "tests/test_nonisar_regions.py:260")
}
private def case_NestedAndInline_test_comment_alone_on_one_line_inside_a_proof(): Unit = TestSupport.test("test_nonisar_regions.NestedAndInline.test_comment_alone_on_one_line_inside_a_proof") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  proof -\n  (* foo would do here *)\n    show \"True\" by simp\n  qed\n\nend\n"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("foo")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:281")
}
private def case_NestedAndInline_test_legacy_verbatim_is_not_live(): Unit = TestSupport.test("test_nonisar_regions.NestedAndInline.test_legacy_verbatim_is_not_live") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("{* legacy foo *}")), Map()), vs(vs(V(1), V(1))), "tests/test_nonisar_regions.py:284")
ParserLegacy.regionGuards()
}
private def case_KeptLive_test_fact_named_in_a_term_is_still_a_citation(): Unit = TestSupport.test("test_nonisar_regions.KeptLive.test_fact_named_in_a_term_is_still_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\ndefinition foo :: \"nat\" where \"foo = 0\"\n\nlemma bar: \"foo = foo\" by simp\n\nend\n"), V("A")), Map())
e("g") = h_graph_of(e, Vector(e("sec")), Map())
TestSupport.equal(e("g").field("callers").at(V("foo")), vset(V("bar")), "tests/test_nonisar_regions.py:307")
TestSupport.equal(e("g").field("callers"), ParserBridge.call("brute_force_call_graph", Vector(vs(e("sec"))), Map()).field("callers"), "tests/test_nonisar_regions.py:308")
}
private def case_KeptLive_test_operator_section_in_a_term_opens_no_comment(): Unit = TestSupport.test("test_nonisar_regions.KeptLive.test_operator_section_in_a_term_opens_no_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\ndefinition foo :: \"nat\" where \"foo = 0\"\n\nlemma bar: \"fold (*) [1, 2] foo = foo\" by simp\n\nlemma baz: \"True\" by simp\n\nend\n"), V("A")), Map())
e("g") = h_graph_of(e, Vector(e("sec")), Map())
TestSupport.equal(e("g").field("callers").at(V("foo")), vset(V("bar")), "tests/test_nonisar_regions.py:327")
TestSupport.equal(V.set(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (true) Vector(c4("e").field("name")) else Vector.empty }), vset(V("foo"), V("bar"), V("baz")), "tests/test_nonisar_regions.py:328")
TestSupport.equal(h_ranges(e, Vector(V("lemma bar: \"fold (*) [1, 2] x = y\" by simp")), Map()), vs(), "tests/test_nonisar_regions.py:329")
}
private def case_KeptLive_test_operator_section_in_a_cartouche_opens_no_comment(): Unit = TestSupport.test("test_nonisar_regions.KeptLive.test_operator_section_in_a_cartouche_opens_no_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("have \\<open>fold (*) [1, 2] x = y\\<close> by simp")), Map()), vs(), "tests/test_nonisar_regions.py:334")
}
private def case_KeptLive_test_comment_open_inside_a_string_is_not_a_comment(): Unit = TestSupport.test("test_nonisar_regions.KeptLive.test_comment_open_inside_a_string_is_not_a_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("lemma bar: \"x = (*) 1 2\" by simp")), Map()), vs(), "tests/test_nonisar_regions.py:338")
}
private def case_KeptLive_test_trailing_comment_keeps_its_line_live(): Unit = TestSupport.test("test_nonisar_regions.KeptLive.test_trailing_comment_keeps_its_line_live") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("  by (simp add: foo) (* not bar *)")), Map()), vs(), "tests/test_nonisar_regions.py:345")
}
private def case_PartialLines_test_citation_before_a_trailing_comment(): Unit = TestSupport.test("test_nonisar_regions.PartialLines.test_citation_before_a_trailing_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_PartialLines_callers_of(e, Vector(V("lemma user: \"True\" using helper by simp (* not other *)"), V("helper")), Map()), vset(V("user")), "tests/test_nonisar_regions.py:367")
}
private def case_PartialLines_test_citation_before_a_comment_that_runs_on(): Unit = TestSupport.test("test_nonisar_regions.PartialLines.test_citation_before_a_comment_that_runs_on") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_PartialLines_callers_of(e, Vector(V("lemma user: \"True\" using helper by simp (* why\n   other would not do\n*)"), V("helper")), Map()), vset(V("user")), "tests/test_nonisar_regions.py:373")
}
private def case_PartialLines_test_citation_after_a_comment_closes(): Unit = TestSupport.test("test_nonisar_regions.PartialLines.test_citation_after_a_comment_closes") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_PartialLines_callers_of(e, Vector(V("lemma user: \"True\"\n(* other would not do\n*) using helper by simp"), V("helper")), Map()), vset(V("user")), "tests/test_nonisar_regions.py:383")
}
private def case_PartialLines_test_citation_beside_an_inline_cancel(): Unit = TestSupport.test("test_nonisar_regions.PartialLines.test_citation_beside_an_inline_cancel") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_PartialLines_callers_of(e, Vector(V("lemma user: \"True\" using helper \\<^cancel>\\<open>and other\\<close> by simp"), V("helper")), Map()), vset(V("user")), "tests/test_nonisar_regions.py:389")
}
private def case_PartialLines_test_citation_in_a_term_beside_a_comment(): Unit = TestSupport.test("test_nonisar_regions.PartialLines.test_citation_in_a_term_beside_a_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\ndefinition helper :: \"nat\" where \"helper = 0\"\n\nlemma user: \"helper = 0\" by simp (* other would work too *)\n\nend\n"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers").at(V("helper")), vset(V("user")), "tests/test_nonisar_regions.py:405")
}
private def case_NoPhantomOnAPartialLine_test_trailing_comment_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomOnAPartialLine.test_trailing_comment_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("g") = h_NoPhantomOnAPartialLine_graph_of_body(e, Vector(V("lemma user: \"True\" using helper by (simp) (* not other *)")), Map())
TestSupport.equal(e("g").field("callers").at(V("other")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:435")
TestSupport.equal(e("g").field("callers").at(V("helper")), vset(V("user")), "tests/test_nonisar_regions.py:436")
}
private def case_NoPhantomOnAPartialLine_test_inline_cancel_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomOnAPartialLine.test_inline_cancel_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("g") = h_NoPhantomOnAPartialLine_graph_of_body(e, Vector(V("lemma user: \"True\" using helper \\<^cancel>\\<open>other\\<close> by simp")), Map())
TestSupport.equal(e("g").field("callers").at(V("other")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:442")
TestSupport.equal(e("g").field("callers").at(V("helper")), vset(V("user")), "tests/test_nonisar_regions.py:443")
}
private def case_NoPhantomOnAPartialLine_test_inline_ml_body_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomOnAPartialLine.test_inline_ml_body_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("g") = h_NoPhantomOnAPartialLine_graph_of_body(e, Vector(V("lemma user: \"True\" using helper by simp\nML \\<open>val msg = \"other\";\\<close>")), Map())
TestSupport.equal(e("g").field("callers").at(V("other")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:452")
TestSupport.equal(e("g").field("callers").at(V("helper")), vset(V("user")), "tests/test_nonisar_regions.py:453")
}
private def case_NoPhantomOnAPartialLine_test_comment_that_opens_and_runs_on_is_not_a_citation(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomOnAPartialLine.test_comment_that_opens_and_runs_on_is_not_a_citation") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("g") = h_NoPhantomOnAPartialLine_graph_of_body(e, Vector(V("lemma user: \"True\" using helper by simp (* why\n   other would not do\n*)")), Map())
TestSupport.equal(e("g").field("callers").at(V("other")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:460")
TestSupport.equal(e("g").field("callers").at(V("helper")), vset(V("user")), "tests/test_nonisar_regions.py:461")
}
private def case_NoPhantomOnAPartialLine_test_oracle_agrees_on_a_partial_line(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomOnAPartialLine.test_oracle_agrees_on_a_partial_line") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma helper: \"True\" by simp\n\nlemma other: \"True\" by simp\n\nlemma user: \"True\" using helper by simp (* not other *)\n\nend\n"), V("A")), Map())
TestSupport.equal(h_graph_of(e, Vector(e("sec")), Map()).field("callers"), ParserBridge.call("brute_force_call_graph", Vector(vs(e("sec"))), Map()).field("callers"), "tests/test_nonisar_regions.py:471")
}
private def case_MarginalComments_test_note_does_not_take_the_step_with_it(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_note_does_not_take_the_step_with_it") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma helper: \"True\" by simp\n\nlemma other: \"True\" by simp\n\nlemma user: \"True\" using helper by simp \\<comment> \\<open>other is weaker\\<close>\n\nend\n"), V("A")), Map())
e("g") = h_graph_of(e, Vector(e("sec")), Map())
TestSupport.equal(e("g").field("callers").at(V("helper")), vset(V("user")), "tests/test_nonisar_regions.py:500")
TestSupport.equal(e("g").field("callers").at(V("other")), ParserBridge.call("set", Vector(), Map()), "tests/test_nonisar_regions.py:501")
}
private def case_MarginalComments_test_note_line_still_counts_its_method(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_note_line_still_counts_its_method") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma user: \"True\" by simp \\<comment> \\<open>could use auto\\<close>\n\nend\n"), V("A")), Map())
val unpack5 = ParserBridge.call("cli._scan_methods", Vector(vs(e("sec"))), Map()); TestSupport.equal(unpack5.seq.size, 2, "unpack arity"); e("counts") = unpack5.at(V(0)); e("_") = unpack5.at(V(1))
TestSupport.equal(e("counts").at(V("simp")), V(1), "tests/test_nonisar_regions.py:514")
TestSupport.equal(e("counts").at(V("auto")), V(0), "tests/test_nonisar_regions.py:515")
}
private def case_MarginalComments_test_note_alone_on_its_line_is_wholly_non_isar(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_note_alone_on_its_line_is_wholly_non_isar") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("  \\<comment> \\<open>round 1\\<close>")), Map()), vs(vs(V(1), V(1))), "tests/test_nonisar_regions.py:518")
}
private def case_MarginalComments_test_multi_line_note_body_is_covered(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_multi_line_note_body_is_covered") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("lemma a: \"True\"\n  \\<comment> \\<open>a note that\n     runs over two lines\\<close>\n  by simp")), Map()), vs(vs(V(2), V(3))), "tests/test_nonisar_regions.py:521")
}
private def case_MarginalComments_test_note_inside_a_quoted_term_is_covered(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_note_inside_a_quoted_term_is_covered") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("definition d where\n  \"d x = (do {\n     let y = f x;\n     \\<comment> \\<open>round 1\\<close>\n     return y\n  })\"")), Map()), vs(vs(V(4), V(4))), "tests/test_nonisar_regions.py:531")
}
private def case_MarginalComments_test_note_inside_a_cartouche_term_is_covered(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_note_inside_a_cartouche_term_is_covered") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("definition d :: \\<open>bool\\<close> where\n  \\<open>d \\<equiv>\n    \\<comment> \\<open>\\<open>M1\\<close> has a non-empty domain\\<close>\n    W \\<noteq> {}\\<close>")), Map()), vs(vs(V(3), V(3))), "tests/test_nonisar_regions.py:542")
}
private def case_MarginalComments_test_term_resumes_after_a_note_at_the_right_depth(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_term_resumes_after_a_note_at_the_right_depth") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\ndefinition d :: \"nat\" where\n  \\<open>d = \\<open>inner\n    \\<comment> \\<open>a note\\<close>\n    xs\\<close> then fold (*) ys\\<close>\n\nlemma baz: \"True\" by simp\n\nend\n"), V("A")), Map())
TestSupport.equal(V.set(e("sec").field("entries").seq.flatMap { item => val c6 = e.clone(); c6("e") = item; if (true) Vector(c6("e").field("name")) else Vector.empty }), vset(V("d"), V("baz")), "tests/test_nonisar_regions.py:569")
}
private def case_MarginalComments_test_note_nested_in_a_live_cartouche_does_not_end_it(): Unit = TestSupport.test("test_nonisar_regions.MarginalComments.test_note_nested_in_a_live_cartouche_does_not_end_it") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma bar: \\<open>x \\<comment> \\<open>note\\<close> = fold (*) xs\\<close> by simp\n\nlemma baz: \"True\" by simp\n\nend\n"), V("A")), Map())
TestSupport.equal(V.set(e("sec").field("entries").seq.flatMap { item => val c7 = e.clone(); c7("e") = item; if (true) Vector(c7("e").field("name")) else Vector.empty }), vset(V("bar"), V("baz")), "tests/test_nonisar_regions.py:586")
}
private def case_NoPhantomDeclarations_test_commented_out_definition_is_not_an_entry(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomDeclarations.test_commented_out_definition_is_not_an_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_NoPhantomDeclarations_names(e, Vector(V("theory A\nimports Main\nbegin\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n*)\n\ndefinition live :: \"nat\" where \"live = 1\"\n\nend\n")), Map()), vs(V("live")), "tests/test_nonisar_regions.py:609")
}
private def case_NoPhantomDeclarations_test_ml_fun_is_not_an_entry(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomDeclarations.test_ml_fun_is_not_an_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_NoPhantomDeclarations_names(e, Vector(V("theory A\nimports Main\nbegin\n\nML \\<open>\nfun helper ctxt = ctxt\n\\<close>\n\nfun real_fun :: \"nat \\<Rightarrow> nat\" where \"real_fun n = n\"\n\nend\n")), Map()), vs(V("real_fun")), "tests/test_nonisar_regions.py:625")
}
private def case_NoPhantomDeclarations_test_superseded_declaration_resolves_to_the_live_one(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomDeclarations.test_superseded_declaration_resolves_to_the_live_one") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\n(*\ndefinition thing :: \"nat\" where \"thing = 0\"\n*)\n\ndefinition thing :: \"nat\" where \"thing = 1\"\n\nend\n"), V("A")), Map())
TestSupport.equal(V.seq(e("sec").field("entries").seq.flatMap { item => val c8 = e.clone(); c8("e") = item; if (true) Vector(c8("e").field("name")) else Vector.empty }), vs(V("thing")), "tests/test_nonisar_regions.py:654")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("thing")), Map()).field("thy_line"), V(9), "tests/test_nonisar_regions.py:655")
}
private def case_NoPhantomDeclarations_test_comment_opening_mid_line_still_hides_what_follows(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomDeclarations.test_comment_opening_mid_line_still_hides_what_follows") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_NoPhantomDeclarations_names(e, Vector(V("theory A\nimports Main\nbegin\n\nlemma stub: \"True\" oops (* TODO: revisit\nlemma hidden: \"True\" by simp\n*)\n\nlemma live: \"True\" by simp\n\nend\n")), Map()), vs(V("stub"), V("live")), "tests/test_nonisar_regions.py:660")
}
private def case_NoPhantomDeclarations_test_declaration_with_a_trailing_comment_still_declares(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomDeclarations.test_declaration_with_a_trailing_comment_still_declares") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_NoPhantomDeclarations_names(e, Vector(V("theory A\nimports Main\nbegin\n\ndefinition kept :: \"nat\" where \"kept = 0\" (* still a definition *)\n\nend\n")), Map()), vs(V("kept")), "tests/test_nonisar_regions.py:677")
}
private def case_NoPhantomDeclarations_test_declaration_after_a_closed_comment_still_declares(): Unit = TestSupport.test("test_nonisar_regions.NoPhantomDeclarations.test_declaration_after_a_closed_comment_still_declares") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_NoPhantomDeclarations_names(e, Vector(V("theory A\nimports Main\nbegin\n\n(* prose about the next one *)\ndefinition kept :: \"nat\" where \"kept = 0\"\n\nend\n")), Map()), vs(V("kept")), "tests/test_nonisar_regions.py:687")
}
private def case_Attribution_test_preceding_entry_absorbs_the_commented_out_region(): Unit = TestSupport.test("test_nonisar_regions.Attribution.test_preceding_entry_absorbs_the_commented_out_region") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
e("first") = h_entry(e, Vector(e("sec"), V("first")), Map())
TestSupport.equal(e("first").field("thy_end"), V(14), "tests/test_nonisar_regions.py:745")
}
private def case_Attribution_test_the_next_entry_keeps_its_forward_attributed_doc(): Unit = TestSupport.test("test_nonisar_regions.Attribution.test_the_next_entry_keeps_its_forward_attributed_doc") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
e("second") = h_entry(e, Vector(e("sec"), V("second")), Map())
TestSupport.equal(e("second").field("preamble"), vs(V(15), V(15)), "tests/test_nonisar_regions.py:752")
TestSupport.equal(e("second").field("src_start"), V(15), "tests/test_nonisar_regions.py:753")
}
private def case_Attribution_test_note_in_a_live_proof_is_a_roadmap_step(): Unit = TestSupport.test("test_nonisar_regions.Attribution.test_note_in_a_live_proof_is_a_roadmap_step") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
TestSupport.equal(V.seq(h_entry(e, Vector(e("sec"), V("first")), Map()).field("roadmap").seq.flatMap { item => val c9 = e.clone(); val unpack10 = item; TestSupport.equal(unpack10.seq.size, 2, "unpack arity"); c9("_") = unpack10.at(V(0)); c9("c") = unpack10.at(V(1)); if (true) Vector(c9("c")) else Vector.empty }), vs(V("a real note")), "tests/test_nonisar_regions.py:757")
}
private def case_Attribution_test_note_inside_a_commented_out_block_is_not(): Unit = TestSupport.test("test_nonisar_regions.Attribution.test_note_inside_a_commented_out_block_is_not") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
TestSupport.check(!V.seq(h_entry(e, Vector(e("sec"), V("first")), Map()).field("roadmap").seq.flatMap { item => val c11 = e.clone(); val unpack12 = item; TestSupport.equal(unpack12.seq.size, 2, "unpack arity"); c11("_") = unpack12.at(V(0)); c11("c") = unpack12.at(V(1)); if (true) Vector(c11("c")) else Vector.empty }).has(V("a note about deleted text")), "tests/test_nonisar_regions.py:765" + " actual=" + V.seq(h_entry(e, Vector(e("sec"), V("first")), Map()).field("roadmap").seq.flatMap { item => val c11 = e.clone(); val unpack12 = item; TestSupport.equal(unpack12.seq.size, 2, "unpack arity"); c11("_") = unpack12.at(V(0)); c11("c") = unpack12.at(V(1)); if (true) Vector(c11("c")) else Vector.empty }))
}
private def case_Attribution_test_grep_classifies_a_match_not_a_line(): Unit = TestSupport.test("test_nonisar_regions.Attribution.test_grep_classifies_a_match_not_a_line") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(V("theory A\nimports Main\nbegin\n\nlemma one: \"True\" by simp \\<comment> \\<open>could use helper\\<close>\n\nlemma two: \"True\" using helper by simp\n\nend\n"), V("A")), Map())
e("hits") = V.dict(ParserBridge.call("cli._grep_sections", Vector(vs(e("sec")), ParserBridge.call("re.compile", Vector(V("helper")), Map())), Map()).seq.flatMap { item => val c13 = e.clone(); val unpack14 = item; TestSupport.equal(unpack14.seq.size, 6, "unpack arity"); c13("_f") = unpack14.at(V(0)); c13("ln") = unpack14.at(V(1)); c13("_t") = unpack14.at(V(2)); c13("_o") = unpack14.at(V(3)); c13("live") = unpack14.at(V(4)); c13("_") = unpack14.at(V(5)); if (true) Vector((c13("ln"), c13("live"))) else Vector.empty })
TestSupport.equal(e("hits"), vm((V(5), V(false)), (V(7), V(true))), "tests/test_nonisar_regions.py:783")
}
private def case_Attribution_test_enclosing_a_commented_out_line_gives_the_entry_above(): Unit = TestSupport.test("test_nonisar_regions.Attribution.test_enclosing_a_commented_out_line_gives_the_entry_above") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET"), V("A")), Map())
e("e") = h_enclosing(e, Vector(e("sec"), V(9)), Map())
TestSupport.check(!e("e").isNone, "tests/test_nonisar_regions.py:791")
TestSupport.equal(e("e").field("name"), V("first"), "tests/test_nonisar_regions.py:792")
}
private def case_Ranges_test_full_line_comment(): Unit = TestSupport.test("test_nonisar_regions.Ranges.test_full_line_comment") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("lemma a\n  (* prose *)\n  by simp")), Map()), vs(vs(V(2), V(2))), "tests/test_nonisar_regions.py:799")
}
private def case_Ranges_test_multi_line_comment_is_one_range(): Unit = TestSupport.test("test_nonisar_regions.Ranges.test_multi_line_comment_is_one_range") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("a\n(* one\ntwo\nthree *)\nb")), Map()), vs(vs(V(2), V(4))), "tests/test_nonisar_regions.py:802")
}
private def case_Ranges_test_cancel_region(): Unit = TestSupport.test("test_nonisar_regions.Ranges.test_cancel_region") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("a\n  \\<^cancel>\\<open>using foo\\<close>\nb")), Map()), vs(vs(V(2), V(2))), "tests/test_nonisar_regions.py:805")
}
private def case_Ranges_test_ml_body(): Unit = TestSupport.test("test_nonisar_regions.Ranges.test_ml_body") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("ML \\<open>\n  val x = 1;\n\\<close>\nlemma a")), Map()), vs(vs(V(2), V(3))), "tests/test_nonisar_regions.py:809")
}
private def case_Ranges_test_ml_command_line_itself_stays_live(): Unit = TestSupport.test("test_nonisar_regions.Ranges.test_ml_command_line_itself_stays_live") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.check(!V.seq(h_ranges(e, Vector(V("ML \\<open>\n  val x = 1;\n\\<close>")), Map()).seq.flatMap { item => val c15 = e.clone(); val unpack16 = item; TestSupport.equal(unpack16.seq.size, 2, "unpack arity"); c15("lo") = unpack16.at(V(0)); c15("_") = unpack16.at(V(1)); if (true) Vector(c15("lo")) else Vector.empty }).has(V(1)), "tests/test_nonisar_regions.py:814" + " actual=" + V.seq(h_ranges(e, Vector(V("ML \\<open>\n  val x = 1;\n\\<close>")), Map()).seq.flatMap { item => val c15 = e.clone(); val unpack16 = item; TestSupport.equal(unpack16.seq.size, 2, "unpack arity"); c15("lo") = unpack16.at(V(0)); c15("_") = unpack16.at(V(1)); if (true) Vector(c15("lo")) else Vector.empty }))
}
private def case_Ranges_test_blank_source_has_no_ranges(): Unit = TestSupport.test("test_nonisar_regions.Ranges.test_blank_source_has_no_ranges") {
val e: Env = scala.collection.mutable.Map.empty
e("R1") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  (* could have used foo here, but did not *)\n  by simp\n\nend\n")
e("R2") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\" by simp\n\nML \\<open>\n  val msg = \"foo is not a fact citation here\";\n\\<close>\n\nend\n")
e("R3") = V("theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nlemma bar: \"True\"\n  \\<^cancel>\\<open>using foo\\<close>\n  by simp\n\nend\n")
e("R4") = V("theory A\nimports Main\nbegin\n\nlemma bar: \"True \\<and> True\"\nproof\n(* superseded:\nend\n  show True by simp\n*)\n  show True by simp\n  show True by simp\nqed\n\nend\n")
e("Attribution.SNIPPET") = V("theory A\nimports Main\nbegin\n\nlemma first: \"True\"\n  proof -\n    show \"True\" by simp \\<comment> \\<open>a real note\\<close>\n  qed\n\n(*\ndefinition old :: \"nat\" where \"old = 0\"\n  \\<comment> \\<open>a note about deleted text\\<close>\n*)\n\ntext \\<open>Documents second.\\<close>\nlemma second: \"True\" by simp\n\nend\n")

TestSupport.equal(h_ranges(e, Vector(V("lemma a: \"True\" by simp")), Map()), vs(), "tests/test_nonisar_regions.py:818")
}
def run(): Unit = { case_Reproductions_test_r1_comment_is_not_a_citation(); case_Reproductions_test_r1_comment_does_not_hide_a_dead_lemma(); case_Reproductions_test_r2_ml_body_is_not_a_citation(); case_Reproductions_test_r2_ml_body_ends_the_span_above_it(); case_Reproductions_test_r3_cancel_region_is_not_a_citation(); case_Reproductions_test_r4_commented_end_does_not_cut_the_span(); case_Reproductions_test_r4_live_proof_line_has_an_enclosing_entry(); case_MLBodyFamily_test_ml_body_with_no_entry_above_it(); case_MLBodyFamily_test_ml_body_below_the_only_entry(); case_MLBodyFamily_test_ml_body_between_two_entries(); case_MLBodyFamily_test_ml_file_ends_the_span_above_it(); case_NestedAndInline_test_nested_comment_two_levels_deep(); case_NestedAndInline_test_comment_alone_on_one_line_inside_a_proof(); case_NestedAndInline_test_legacy_verbatim_is_not_live(); case_KeptLive_test_fact_named_in_a_term_is_still_a_citation(); case_KeptLive_test_operator_section_in_a_term_opens_no_comment(); case_KeptLive_test_operator_section_in_a_cartouche_opens_no_comment(); case_KeptLive_test_comment_open_inside_a_string_is_not_a_comment(); case_KeptLive_test_trailing_comment_keeps_its_line_live(); case_PartialLines_test_citation_before_a_trailing_comment(); case_PartialLines_test_citation_before_a_comment_that_runs_on(); case_PartialLines_test_citation_after_a_comment_closes(); case_PartialLines_test_citation_beside_an_inline_cancel(); case_PartialLines_test_citation_in_a_term_beside_a_comment(); case_NoPhantomOnAPartialLine_test_trailing_comment_is_not_a_citation(); case_NoPhantomOnAPartialLine_test_inline_cancel_is_not_a_citation(); case_NoPhantomOnAPartialLine_test_inline_ml_body_is_not_a_citation(); case_NoPhantomOnAPartialLine_test_comment_that_opens_and_runs_on_is_not_a_citation(); case_NoPhantomOnAPartialLine_test_oracle_agrees_on_a_partial_line(); case_MarginalComments_test_note_does_not_take_the_step_with_it(); case_MarginalComments_test_note_line_still_counts_its_method(); case_MarginalComments_test_note_alone_on_its_line_is_wholly_non_isar(); case_MarginalComments_test_multi_line_note_body_is_covered(); case_MarginalComments_test_note_inside_a_quoted_term_is_covered(); case_MarginalComments_test_note_inside_a_cartouche_term_is_covered(); case_MarginalComments_test_term_resumes_after_a_note_at_the_right_depth(); case_MarginalComments_test_note_nested_in_a_live_cartouche_does_not_end_it(); case_NoPhantomDeclarations_test_commented_out_definition_is_not_an_entry(); case_NoPhantomDeclarations_test_ml_fun_is_not_an_entry(); case_NoPhantomDeclarations_test_superseded_declaration_resolves_to_the_live_one(); case_NoPhantomDeclarations_test_comment_opening_mid_line_still_hides_what_follows(); case_NoPhantomDeclarations_test_declaration_with_a_trailing_comment_still_declares(); case_NoPhantomDeclarations_test_declaration_after_a_closed_comment_still_declares(); case_Attribution_test_preceding_entry_absorbs_the_commented_out_region(); case_Attribution_test_the_next_entry_keeps_its_forward_attributed_doc(); case_Attribution_test_note_in_a_live_proof_is_a_roadmap_step(); case_Attribution_test_note_inside_a_commented_out_block_is_not(); case_Attribution_test_grep_classifies_a_match_not_a_line(); case_Attribution_test_enclosing_a_commented_out_line_gives_the_entry_above(); case_Ranges_test_full_line_comment(); case_Ranges_test_multi_line_comment_is_one_range(); case_Ranges_test_cancel_region(); case_Ranges_test_ml_body(); case_Ranges_test_ml_command_line_itself_stays_live(); case_Ranges_test_blank_source_has_no_ranges() }
}
