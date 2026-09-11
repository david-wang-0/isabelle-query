package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_instantiation_spans.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_instantiation_spans {
private def h__entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def h__unused(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sections") = V.seq(args.drop(0))
e("graph") = ParserBridge.call("cli._build_call_graph", Vector(ParserBridge.call("list", Vector(e("sections")), Map())), Map("derived" -> V(true)))
return ParserBridge.call("set", Vector(ParserBridge.call("cli._compute_unused", Vector(e("graph"), ParserBridge.call("set", Vector(), Map())), Map())), Map())
V.none
}
private def h_OracleParity_assertParity(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sections") = kw.getOrElse("sections", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sections")))
e("derived") = kw.getOrElse("derived", args.lift(1).getOrElse(throw new IllegalArgumentException("missing derived")))
e("fast") = ParserBridge.call("cli._build_call_graph", Vector(e("sections")), Map("derived" -> e("derived")))
e("ref") = ParserBridge.call("brute_force_call_graph", Vector(e("sections")), Map("derived" -> e("derived")))
TestSupport.equal(e("fast").field("callers"), e("ref").field("callers"), "tests/test_instantiation_spans.py:158")
TestSupport.equal(e("fast").field("callees"), e("ref").field("callees"), "tests/test_instantiation_spans.py:159")
V.none
}
private def case_InstantiationSpans_test_definition_does_not_swallow_the_instance_proof(): Unit = TestSupport.test("test_instantiation_spans.InstantiationSpans.test_definition_does_not_swallow_the_instance_proof") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("INSTANTIATION"), V("T")), Map())
e("eq") = h__entry(e, Vector(e("sec"), V("equal_foo")), Map())
TestSupport.equal(e("eq").field("thy_line"), e("eq").field("thy_end"), "tests/test_instantiation_spans.py:70")
}
private def case_InstantiationSpans_test_instance_proof_keeps_its_definition_alive(): Unit = TestSupport.test("test_instantiation_spans.InstantiationSpans.test_instance_proof_keeps_its_definition_alive") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("INSTANTIATION"), V("T")), Map())
TestSupport.check(!h__unused(e, Vector(e("sec")), Map()).has(V("equal_foo")), "tests/test_instantiation_spans.py:76" + " actual=" + h__unused(e, Vector(e("sec")), Map()))
}
private def case_InstantiationSpans_test_a_genuinely_uncited_definition_is_still_unused(): Unit = TestSupport.test("test_instantiation_spans.InstantiationSpans.test_a_genuinely_uncited_definition_is_still_unused") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("INSTANTIATION"), V("T")), Map())
TestSupport.check(h__unused(e, Vector(e("sec")), Map()).has(V("bar")), "tests/test_instantiation_spans.py:81" + " actual=" + h__unused(e, Vector(e("sec")), Map()))
}
private def case_TrailingCommandSpans_test_lemmas_alias_is_not_absorbed_by_the_lemma_above(): Unit = TestSupport.test("test_instantiation_spans.TrailingCommandSpans.test_lemmas_alias_is_not_absorbed_by_the_lemma_above") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\n\ndefinition baz :: \"nat\" where \"baz = 0\"\n\nlemma quux: \"baz = 0\" by (simp add: baz_def)\n\nlemmas quux_code [code] = quux\n\nend\n"), V("T")), Map())
e("q") = h__entry(e, Vector(e("sec"), V("quux")), Map())
TestSupport.check(e("q").field("thy_end").cmp("Lt", V(7)), "tests/test_instantiation_spans.py:98" + " actual=" + e("q").field("thy_end") + " expected=" + V(7))
}
private def case_TrailingCommandSpans_test_boundary_does_not_land_on_a_separating_blank(): Unit = TestSupport.test("test_instantiation_spans.TrailingCommandSpans.test_boundary_does_not_land_on_a_separating_blank") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(e("INSTANTIATION"), V("T")), Map())
e("bar") = h__entry(e, Vector(e("sec"), V("bar")), Map())
TestSupport.equal(e("bar").field("thy_line"), e("bar").field("thy_end"), "tests/test_instantiation_spans.py:105")
}
private def case_TrailingCommandSpans_test_a_commented_out_command_is_not_a_boundary(): Unit = TestSupport.test("test_instantiation_spans.TrailingCommandSpans.test_a_commented_out_command_is_not_a_boundary") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")

e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\n\ndefinition wib :: \"nat\" where\n  \"wib = 0\"\n(* end *)\n\nend\n"), V("T")), Map())
TestSupport.check(h__entry(e, Vector(e("sec"), V("wib")), Map()).field("thy_end").cmp("GtE", V(4)), "tests/test_instantiation_spans.py:117" + " actual=" + h__entry(e, Vector(e("sec"), V("wib")), Map()).field("thy_end") + " expected=" + V(4))
}
private def case_DeadnessVersusFactIdentity_test_callers_stays_fact_level(): Unit = TestSupport.test("test_instantiation_spans.DeadnessVersusFactIdentity.test_callers_stays_fact_level") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("T")), Map())
e("graph") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map())
TestSupport.check(!e("graph").field("callers").at(V("foo")).has(V("bar")), "tests/test_instantiation_spans.py:133" + " actual=" + e("graph").field("callers").at(V("foo")))
}
private def case_DeadnessVersusFactIdentity_test_unused_resolves_the_derived_spelling(): Unit = TestSupport.test("test_instantiation_spans.DeadnessVersusFactIdentity.test_unused_resolves_the_derived_spelling") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("T")), Map())
TestSupport.check(!h__unused(e, Vector(e("sec")), Map()).has(V("foo")), "tests/test_instantiation_spans.py:137" + " actual=" + h__unused(e, Vector(e("sec")), Map()))
}
private def case_OracleParity_test_toplevel_citation_matches_oracle(): Unit = TestSupport.test("test_instantiation_spans.OracleParity.test_toplevel_citation_matches_oracle") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("TOPLEVEL"), V("T")), Map())
TestSupport.check(ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map()).field("callers").at(V("bar")).has(V("T:<toplevel>")), "tests/test_instantiation_spans.py:164" + " actual=" + ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map()).field("callers").at(V("bar")))
h_OracleParity_assertParity(e, Vector(vs(e("sec"))), Map("derived" -> V(false)))
}
private def case_OracleParity_test_instantiation_matches_oracle_in_both_modes(): Unit = TestSupport.test("test_instantiation_spans.OracleParity.test_instantiation_matches_oracle_in_both_modes") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("INSTANTIATION"), V("T")), Map())
h_OracleParity_assertParity(e, Vector(vs(e("sec"))), Map("derived" -> V(false)))
h_OracleParity_assertParity(e, Vector(vs(e("sec"))), Map("derived" -> V(true)))
}
private def case_OracleParity_test_derived_mode_only_adds_edges(): Unit = TestSupport.test("test_instantiation_spans.OracleParity.test_derived_mode_only_adds_edges") {
val e: Env = scala.collection.mutable.Map.empty
e("INSTANTIATION") = V("theory T imports Main begin\n\ntypedecl foo\n\ninstantiation foo :: equal begin\ndefinition \"equal_foo (x::foo) y = (x = y)\"\ninstance by standard (simp add: equal_foo_def)\nend\n\ndefinition bar :: \"nat\" where \"bar = 0\"\n\nend\n")
e("DeadnessVersusFactIdentity.THY") = V("theory T imports Main begin\ndefinition foo :: \"nat\" where \"foo = 0\"\nlemma bar: \"(0::nat) = 0\" using foo_def by simp\nend\n")
e("OracleParity.TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("TOPLEVEL") = V("theory T imports Main begin\n\nlemma bar: \"(0::nat) = 0\" by simp\n\nlemmas bar_alias [simp] = bar\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("DeadnessVersusFactIdentity.THY"), V("T")), Map())
e("plain") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map()).field("callers")
e("withd") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map("derived" -> V(true))).field("callers")
e("plain").invoke("items", Vector(), Map()).seq.foreach { item => val unpack2 = item; TestSupport.equal(unpack2.seq.size, 2, "unpack arity"); e("name") = unpack2.at(V(0)); e("callers") = unpack2.at(V(1));
TestSupport.check(V((e("callers").cmp("LtE", e("withd").at(e("name"))))).truth, "tests/test_instantiation_spans.py:177")
}
TestSupport.check(!e("plain").cmp("Eq", e("withd")), "tests/test_instantiation_spans.py:178")
}
def run(): Unit = { case_InstantiationSpans_test_definition_does_not_swallow_the_instance_proof(); case_InstantiationSpans_test_instance_proof_keeps_its_definition_alive(); case_InstantiationSpans_test_a_genuinely_uncited_definition_is_still_unused(); case_TrailingCommandSpans_test_lemmas_alias_is_not_absorbed_by_the_lemma_above(); case_TrailingCommandSpans_test_boundary_does_not_land_on_a_separating_blank(); case_TrailingCommandSpans_test_a_commented_out_command_is_not_a_boundary(); case_DeadnessVersusFactIdentity_test_callers_stays_fact_level(); case_DeadnessVersusFactIdentity_test_unused_resolves_the_derived_spelling(); case_OracleParity_test_toplevel_citation_matches_oracle(); case_OracleParity_test_instantiation_matches_oracle_in_both_modes(); case_OracleParity_test_derived_mode_only_adds_edges() }
}
