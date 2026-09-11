package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_record_fields.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_record_fields {
private def h__fields(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(e("snippet")).plus(e("FOOT"))), Map())
e("rec") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("tag").cmp("Eq", V("RECORD")))).truth) Vector(c1("e")) else Vector.empty })), Map())
return V.seq(e("rec").field("bindings").seq.flatMap { item => val c2 = e.clone(); val unpack3 = item; TestSupport.equal(unpack3.seq.size, 2, "unpack arity"); c2("n") = unpack3.at(V(0)); c2("kind") = unpack3.at(V(1)); if (V((c2("kind").cmp("Eq", V("field")))).truth) Vector(c2("n")) else Vector.empty })
V.none
}
private def case_TheBodyScanReachesEveryField_test_a_blank_line_does_not_end_the_field_list(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_a_blank_line_does_not_end_the_field_list") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

TestSupport.equal(h__fields(e, Vector(V("\nrecord 'a NumNegate_class=\n\n  numNegate_method ::\" 'a \\<Rightarrow> 'a \"\n\n")), Map()), vs(V("numNegate_method")), "tests/test_record_fields.py:55")
}
private def case_TheBodyScanReachesEveryField_test_a_comment_between_fields_does_not_end_the_list(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_a_comment_between_fields_does_not_end_the_list") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

TestSupport.equal(h__fields(e, Vector(V("\nrecord 'S while_algo =\n  \\<comment> \\<open>Termination condition\\<close>\n  wa_cond :: \"'S set\"\n  \\<comment> \\<open>Step relation\\<close>\n  wa_step :: \"('S \\<times> 'S) set\"\n")), Map()), vs(V("wa_cond"), V("wa_step")), "tests/test_record_fields.py:66")
}
private def case_TheBodyScanReachesEveryField_test_a_quoted_field_name_is_read_from_the_live_view(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_a_quoted_field_name_is_read_from_the_live_view") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

TestSupport.equal(h__fields(e, Vector(V("\nrecord \"globals\" =\n  \"G_'\"::\"nat\"\n  \"H_'\"::\"nat\"\n")), Map()), vs(V("G_'"), V("H_'")), "tests/test_record_fields.py:79")
}
private def case_TheBodyScanReachesEveryField_test_a_comment_before_a_quoted_field(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_a_comment_before_a_quoted_field") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

TestSupport.equal(h__fields(e, Vector(V("\nrecord State =\n  \\<comment> \\<open>Satisfiability flag\\<close>\n\"getSATFlag\" :: ExtendedBool\n  \\<comment> \\<open>Formula\\<close>\n\"getF\"       :: Formula\n")), Map()), vs(V("getSATFlag"), V("getF")), "tests/test_record_fields.py:89")
}
private def case_TheBodyScanReachesEveryField_test_a_field_may_share_the_records_own_name(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_a_field_may_share_the_records_own_name") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

TestSupport.equal(h__fields(e, Vector(V("\nrecord 'a carrier =\n  carrier :: \"'a set\"\n")), Map()), vs(V("carrier")), "tests/test_record_fields.py:101")
}
private def case_TheBodyScanReachesEveryField_test_the_span_covers_the_whole_record(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_the_span_covers_the_whole_record") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nrecord point =\n  x :: nat\n\n  \\<comment> \\<open>note\\<close>\n  y :: nat\n")).plus(e("FOOT"))), Map())
e("rec") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (V((c4("e").field("tag").cmp("Eq", V("RECORD")))).truth) Vector(c4("e")) else Vector.empty })), Map())
TestSupport.equal(vs(e("rec").field("thy_line"), e("rec").field("decl_end_line")), vs(V(3), V(7)), "tests/test_record_fields.py:118")
}
private def case_TheBodyScanReachesEveryField_test_a_type_at_the_end_of_a_line_is_not_the_next_fields_name(): Unit = TestSupport.test("test_record_fields.TheBodyScanReachesEveryField.test_a_type_at_the_end_of_a_line_is_not_the_next_fields_name") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

TestSupport.equal(h__fields(e, Vector(V("\nrecord State =\n\"getSATFlag\" :: ExtendedBool\n  \\<comment> \\<open>Formula\\<close>\n\"getF\" :: Formula\n")), Map()), vs(V("getSATFlag"), V("getF")), "tests/test_record_fields.py:125")
}
private def case_FieldsAreCitableAndDeclared_test_a_field_resolves_to_the_record_that_declares_it(): Unit = TestSupport.test("test_record_fields.FieldsAreCitableAndDeclared.test_a_field_resolves_to_the_record_that_declares_it") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
TestSupport.equal(ParserBridge.call("commands._resolve_binding", Vector(vs(e("sec")), V("rreqs")), Map()), vs(V("state"), V("a field of")), "tests/test_record_fields.py:146")
}
private def case_FieldsAreCitableAndDeclared_test_the_declaration_is_not_a_caller_of_itself(): Unit = TestSupport.test("test_record_fields.FieldsAreCitableAndDeclared.test_the_declaration_is_not_a_caller_of_itself") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("lines") = V.seq(ParserBridge.call("commands._find_callers", Vector(vs(e("sec")), V("rreqs")), Map()).seq.flatMap { item => val c5 = e.clone(); val unpack6 = item; TestSupport.equal(unpack6.seq.size, 3, "unpack arity"); c5("_thy") = unpack6.at(V(0)); c5("ln") = unpack6.at(V(1)); c5("_text") = unpack6.at(V(2)); if (true) Vector(c5("ln")) else Vector.empty })
TestSupport.check(!e("lines").has(V(5)), V("the field's own declaration line").str + " actual=" + e("lines"))
TestSupport.equal(e("lines"), vs(V(7)), "tests/test_record_fields.py:159")
}
private def case_TheRecordGrammarIsNotTheDatatypeGrammar_test_a_record_declares_no_constructors(): Unit = TestSupport.test("test_record_fields.TheRecordGrammarIsNotTheDatatypeGrammar.test_a_record_declares_no_constructors") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\nrecord cpoint = point +\n  col :: nat\n")).plus(e("FOOT"))), Map())
e("rec") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c7 = e.clone(); c7("e") = item; if (V((c7("e").field("tag").cmp("Eq", V("RECORD")))).truth) Vector(c7("e")) else Vector.empty })), Map())
TestSupport.equal(V.set(e("rec").field("bindings").seq.flatMap { item => val c8 = e.clone(); val unpack9 = item; TestSupport.equal(unpack9.seq.size, 2, "unpack arity"); c8("_n") = unpack9.at(V(0)); c8("kind") = unpack9.at(V(1)); if (true) Vector(c8("kind")) else Vector.empty }), vset(V("field")), "tests/test_record_fields.py:172")
}
private def case_TheRecordGrammarIsNotTheDatatypeGrammar_test_a_datatype_declares_no_fields(): Unit = TestSupport.test("test_record_fields.TheRecordGrammarIsNotTheDatatypeGrammar.test_a_datatype_declares_no_fields") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("FieldsAreCitableAndDeclared.SRC") = e("HEAD").plus(V("\nrecord state =\n  ip    :: \"nat\"\n  rreqs :: \"nat set\"\n\nlemma uses_it: \"rreqs s = rreqs s\" by simp\n")).plus(e("FOOT"))

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("datatype t = A \"nat\" | B\n")).plus(e("FOOT"))), Map())
e("dt") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c10 = e.clone(); c10("e") = item; if (V((c10("e").field("tag").cmp("Eq", V("DATATYPE")))).truth) Vector(c10("e")) else Vector.empty })), Map())
TestSupport.check(!V.set(e("dt").field("bindings").seq.flatMap { item => val c11 = e.clone(); val unpack12 = item; TestSupport.equal(unpack12.seq.size, 2, "unpack arity"); c11("_n") = unpack12.at(V(0)); c11("kind") = unpack12.at(V(1)); if (true) Vector(c11("kind")) else Vector.empty }).has(V("field")), "tests/test_record_fields.py:177" + " actual=" + V.set(e("dt").field("bindings").seq.flatMap { item => val c11 = e.clone(); val unpack12 = item; TestSupport.equal(unpack12.seq.size, 2, "unpack arity"); c11("_n") = unpack12.at(V(0)); c11("kind") = unpack12.at(V(1)); if (true) Vector(c11("kind")) else Vector.empty }))
}
def run(): Unit = { case_TheBodyScanReachesEveryField_test_a_blank_line_does_not_end_the_field_list(); case_TheBodyScanReachesEveryField_test_a_comment_between_fields_does_not_end_the_list(); case_TheBodyScanReachesEveryField_test_a_quoted_field_name_is_read_from_the_live_view(); case_TheBodyScanReachesEveryField_test_a_comment_before_a_quoted_field(); case_TheBodyScanReachesEveryField_test_a_field_may_share_the_records_own_name(); case_TheBodyScanReachesEveryField_test_the_span_covers_the_whole_record(); case_TheBodyScanReachesEveryField_test_a_type_at_the_end_of_a_line_is_not_the_next_fields_name(); case_FieldsAreCitableAndDeclared_test_a_field_resolves_to_the_record_that_declares_it(); case_FieldsAreCitableAndDeclared_test_the_declaration_is_not_a_caller_of_itself(); case_TheRecordGrammarIsNotTheDatatypeGrammar_test_a_record_declares_no_constructors(); case_TheRecordGrammarIsNotTheDatatypeGrammar_test_a_datatype_declares_no_fields() }
}
