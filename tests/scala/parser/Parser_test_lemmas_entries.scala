package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_lemmas_entries.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_lemmas_entries {
private def h_entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def case_EachNamedGroupIsAnEntry_test_names_in_source_order(): Unit = TestSupport.test("test_lemmas_entries.EachNamedGroupIsAnEntry.test_names_in_source_order") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
TestSupport.equal(ParserBridge.call("names", Vector(e("sec")), Map()), vs(V("base"), V("other"), V("alias"), V("user"), V("two"), V("three"), V("four"), V("five"), V("six")), "tests/test_lemmas_entries.py:67")
}
private def case_EachNamedGroupIsAnEntry_test_tagged_lemmas(): Unit = TestSupport.test("test_lemmas_entries.EachNamedGroupIsAnEntry.test_tagged_lemmas") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
e("tags") = ParserBridge.call("tags_by_name", Vector(e("sec")), Map())
TestSupport.equal(V.dict(e("FACTS").seq.flatMap { item => val c2 = e.clone(); c2("n") = item; if (true) Vector((c2("n"), c2("tags").at(c2("n")))) else Vector.empty }), ParserBridge.call("dict.fromkeys", Vector(e("FACTS"), V("LEMMAS")), Map()), "tests/test_lemmas_entries.py:73")
}
private def case_EachNamedGroupIsAnEntry_test_an_anonymous_command_binds_nothing(): Unit = TestSupport.test("test_lemmas_entries.EachNamedGroupIsAnEntry.test_an_anonymous_command_binds_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
TestSupport.check(!V.seq(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (true) Vector(c3("e").field("thy_line")) else Vector.empty }).has(V(8)), "tests/test_lemmas_entries.py:77" + " actual=" + V.seq(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (true) Vector(c3("e").field("thy_line")) else Vector.empty }))
}
private def case_EachNamedGroupIsAnEntry_test_group_lines(): Unit = TestSupport.test("test_lemmas_entries.EachNamedGroupIsAnEntry.test_group_lines") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
e("got") = V.dict(e("FACTS").seq.flatMap { item => val c4 = e.clone(); c4("n") = item; if (true) Vector((c4("n"), vs(h_entry(c4, Vector(c4("sec"), c4("n")), Map()).field("thy_line"), h_entry(c4, Vector(c4("sec"), c4("n")), Map()).field("decl_end_line")))) else Vector.empty })
TestSupport.equal(e("got"), vm((V("alias"), vs(V(4), V(4))), (V("two"), vs(V(6), V(6))), (V("three"), vs(V(7), V(7))), (V("four"), vs(V(9), V(10))), (V("five"), vs(V(11), V(11))), (V("six"), vs(V(12), V(12)))), "tests/test_lemmas_entries.py:84")
}
private def case_EachNamedGroupIsAnEntry_test_the_target_covers_every_group(): Unit = TestSupport.test("test_lemmas_entries.EachNamedGroupIsAnEntry.test_the_target_covers_every_group") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
TestSupport.equal(V.seq(vs(V("two"), V("three")).seq.flatMap { item => val c5 = e.clone(); c5("n") = item; if (true) Vector(h_entry(c5, Vector(c5("sec"), c5("n")), Map()).field("in_target")) else Vector.empty }), vs(V("foo"), V("foo")), "tests/test_lemmas_entries.py:89")
}
private def case_EachNamedGroupIsAnEntry_test_the_text_has_no_command_word(): Unit = TestSupport.test("test_lemmas_entries.EachNamedGroupIsAnEntry.test_the_text_has_no_command_word") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("alias")), Map()).field("text"), V("LEMMAS alias = base"), "tests/test_lemmas_entries.py:93")
}
private def case_TheGraphPassesThroughAnAlias_test_each_group_cites_its_own_right_hand_side(): Unit = TestSupport.test("test_lemmas_entries.TheGraphPassesThroughAnAlias.test_each_group_cites_its_own_right_hand_side") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
e("graph") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map())
TestSupport.equal(e("graph").field("callers").at(V("base")), vset(V("alias"), V("two"), V("four"), V("L:<toplevel>")), "tests/test_lemmas_entries.py:103")
TestSupport.equal(e("graph").field("callers").at(V("other")), vset(V("two"), V("three"), V("five"), V("six")), "tests/test_lemmas_entries.py:105")
}
private def case_TheGraphPassesThroughAnAlias_test_a_citation_of_the_alias_reaches_its_target(): Unit = TestSupport.test("test_lemmas_entries.TheGraphPassesThroughAnAlias.test_a_citation_of_the_alias_reaches_its_target") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
e("graph") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map())
TestSupport.equal(e("graph").field("callers").at(V("alias")), vset(V("user")), "tests/test_lemmas_entries.py:109")
}
private def case_TheGraphPassesThroughAnAlias_test_the_oracle_agrees(): Unit = TestSupport.test("test_lemmas_entries.TheGraphPassesThroughAnAlias.test_the_oracle_agrees") {
val e: Env = scala.collection.mutable.Map.empty
e("THY") = V("theory L imports Main begin\nlemma base: \"True\" by simp\nlemma other: \"True\" by simp\nlemmas alias = base\nlemma user: \"True\" using alias by simp\nlemmas (in foo) two [simp] = base[of \"a = b\"] other\n  and three = other\nlemmas [simp] = base\nlemmas\n  four = base and\n  five = other\ntheorems six = other\nend\n")
e("FACTS") = vs(V("alias"), V("two"), V("three"), V("four"), V("five"), V("six"))

e("sec") = ParserBridge.call("section_from", Vector(e("THY"), V("L")), Map())
e("graph") = ParserBridge.call("cli._build_call_graph", Vector(vs(e("sec"))), Map())
e("ref") = ParserBridge.call("brute_force_call_graph", Vector(vs(e("sec"))), Map())
TestSupport.equal(e("graph").field("callers"), e("ref").field("callers"), "tests/test_lemmas_entries.py:113")
TestSupport.equal(e("graph").field("callees"), e("ref").field("callees"), "tests/test_lemmas_entries.py:114")
}
def run(): Unit = { case_EachNamedGroupIsAnEntry_test_names_in_source_order(); case_EachNamedGroupIsAnEntry_test_tagged_lemmas(); case_EachNamedGroupIsAnEntry_test_an_anonymous_command_binds_nothing(); case_EachNamedGroupIsAnEntry_test_group_lines(); case_EachNamedGroupIsAnEntry_test_the_target_covers_every_group(); case_EachNamedGroupIsAnEntry_test_the_text_has_no_command_word(); case_TheGraphPassesThroughAnAlias_test_each_group_cites_its_own_right_hand_side(); case_TheGraphPassesThroughAnAlias_test_a_citation_of_the_alias_reaches_its_target(); case_TheGraphPassesThroughAnAlias_test_the_oracle_agrees() }
}
