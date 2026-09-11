package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_targets.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_targets {
private def h_entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def h_Rendering__enclosing(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("locus") = kw.getOrElse("locus", args.lift(1).getOrElse(throw new IllegalArgumentException("missing locus")))
return V(ParserBridge.enclosing(List(e("sec").section),List(e("locus").str))._1)
V.none
}
private def case_LexicalNesting_test_lemma_inside_a_locale_records_it(): Unit = TestSupport.test("test_targets.LexicalNesting.test_lemma_inside_a_locale_records_it") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo =\n  fixes x :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nlemma outside: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:48")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V("foo"), "tests/test_targets.py:49")
}
private def case_LexicalNesting_test_after_the_end_the_target_is_gone(): Unit = TestSupport.test("test_targets.LexicalNesting.test_after_the_end_the_target_is_gone") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo =\n  fixes x :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nlemma outside: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("outside")), Map()).field("blocks"), vs(), "tests/test_targets.py:61")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("outside")), Map()).field("target"), V(""), "tests/test_targets.py:62")
}
private def case_LexicalNesting_test_the_theory_block_is_not_reported(): Unit = TestSupport.test("test_targets.LexicalNesting.test_the_theory_block_is_not_reported") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlemma plain: \"True\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("plain")), Map()).field("blocks"), vs(), "tests/test_targets.py:71")
}
private def case_LexicalNesting_test_context_reopening_a_locale(): Unit = TestSupport.test("test_targets.LexicalNesting.test_context_reopening_a_locale") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext hpk\nbegin\ndefinition K0 :: nat where \"K0 = 0\"\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("K0")), Map()).field("blocks"), vs(vs(V("context"), V("hpk"))), "tests/test_targets.py:81")
}
private def case_LexicalNesting_test_anonymous_context_nests_but_is_not_named(): Unit = TestSupport.test("test_targets.LexicalNesting.test_anonymous_context_nests_but_is_not_named") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo =\n  fixes y :: nat\nbegin\ncontext fixes x :: nat\nbegin\nlemma deep: \"True\" by simp\nend\nlemma shallow: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("deep")), Map()).field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:99")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("shallow")), Map()).field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:101")
}
private def case_LexicalNesting_test_an_unrecognised_opener_still_nests(): Unit = TestSupport.test("test_targets.LexicalNesting.test_an_unrecognised_opener_still_nests") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo =\n  fixes y :: nat\nbegin\nif_architecture_context (ARM)\nbegin\nlemma deep: \"True\" by simp\nend\nlemma shallow: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("deep")), Map()).field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:122")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("shallow")), Map()).field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:123")
}
private def case_LexicalNesting_test_a_declared_but_never_opened_locale_is_not_inherited(): Unit = TestSupport.test("test_targets.LexicalNesting.test_a_declared_but_never_opened_locale_is_not_inherited") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale A = fixes x :: nat\nlocale B = fixes y :: nat\nbegin\nlemma here: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("here")), Map()).field("blocks"), vs(vs(V("locale"), V("B"))), "tests/test_targets.py:136")
}
private def case_LexicalNesting_test_class_and_instantiation_are_targets(): Unit = TestSupport.test("test_targets.LexicalNesting.test_class_and_instantiation_are_targets") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ninstantiation nat :: tape begin\ndefinition tape_of_nat where \"tape_of_nat n = n\"\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("tape_of_nat")), Map()).field("blocks"), vs(vs(V("instantiation"), V("nat"))), "tests/test_targets.py:145")
}
private def case_LexicalNesting_test_two_openers_and_two_begins_on_one_line(): Unit = TestSupport.test("test_targets.LexicalNesting.test_two_openers_and_two_begins_on_one_line") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext srules begin context begin\nlemma deep: \"True\" by simp\nend end\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("deep")), Map()).field("blocks"), vs(vs(V("context"), V("srules"))), "tests/test_targets.py:158")
}
private def case_LexicalNesting_test_an_indented_block_is_still_a_block(): Unit = TestSupport.test("test_targets.LexicalNesting.test_an_indented_block_is_still_a_block") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\n  locale foo =\n    fixes x :: nat\n  begin\n    lemma inside: \"True\" by simp\n  end\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:170")
}
private def case_ExplicitTarget_test_in_modifier_is_recorded(): Unit = TestSupport.test("test_targets.ExplicitTarget.test_in_modifier_is_recorded") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlemma (in foo) retargeted: \"True\" by simp\nend\n")), Map())
e("e") = h_entry(e, Vector(e("sec"), V("retargeted")), Map())
TestSupport.equal(e("e").field("in_target"), V("foo"), "tests/test_targets.py:181")
TestSupport.equal(e("e").field("blocks"), vs(), "tests/test_targets.py:182")
TestSupport.equal(e("e").field("target"), V("foo"), "tests/test_targets.py:183")
}
private def case_ExplicitTarget_test_in_modifier_beats_lexical_nesting(): Unit = TestSupport.test("test_targets.ExplicitTarget.test_in_modifier_beats_lexical_nesting") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo =\n  fixes x :: nat\nbegin\nlemma (in bar) elsewhere: \"True\" by simp\nend\nend\n")), Map())
e("e") = h_entry(e, Vector(e("sec"), V("elsewhere")), Map())
TestSupport.equal(e("e").field("blocks"), vs(vs(V("locale"), V("foo"))), "tests/test_targets.py:197")
TestSupport.equal(e("e").field("in_target"), V("bar"), "tests/test_targets.py:198")
TestSupport.equal(e("e").field("target"), V("bar"), "tests/test_targets.py:199")
}
private def case_ExplicitTarget_test_a_term_mentioning_in_is_not_a_target(): Unit = TestSupport.test("test_targets.ExplicitTarget.test_a_term_mentioning_in_is_not_a_target") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlemma quoted: \"f (in_set xs) = (in_set xs)\" by simp\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("quoted")), Map()).field("in_target"), V(""), "tests/test_targets.py:208")
}
private def case_Rendering_test_enclosing_names_the_locale(): Unit = TestSupport.test("test_targets.Rendering.test_enclosing_names_the_locale") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo =\n  fixes x :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
e("out") = h_Rendering__enclosing(e, Vector(e("sec"), V("Test:5")), Map())
TestSupport.check(e("out").has(V("locale foo")), "tests/test_targets.py:223" + " actual=" + e("out"))
TestSupport.check(e("out").has(V("inside")), "tests/test_targets.py:224" + " actual=" + e("out"))
}
private def case_Rendering_test_theory_level_entry_gets_no_scope_step(): Unit = TestSupport.test("test_targets.Rendering.test_theory_level_entry_gets_no_scope_step") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlemma plain: \"True\" by simp\nend\n")), Map())
TestSupport.check(!h_Rendering__enclosing(e, Vector(e("sec"), V("Test:2")), Map()).has(V("▸")), "tests/test_targets.py:231" + " actual=" + h_Rendering__enclosing(e, Vector(e("sec"), V("Test:2")), Map()))
}
private def case_TargetNameSpellings_test_a_markup_symbol_does_not_end_the_name(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_a_markup_symbol_does_not_end_the_name") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale split\\<^sub>i_tree =\n  fixes x :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V("split\\<^sub>i_tree"), "tests/test_targets.py:267")
}
private def case_TargetNameSpellings_test_two_locales_differing_only_in_markup_stay_distinct(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_two_locales_differing_only_in_markup_stay_distinct") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale split\\<^sub>i_tree =\n  fixes x :: nat\nbegin\nlemma one: \"True\" by simp\nend\nlocale split\\<^sub>i_list =\n  fixes y :: nat\nbegin\nlemma two: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("one")), Map()).field("target"), V("split\\<^sub>i_tree"), "tests/test_targets.py:286")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("two")), Map()).field("target"), V("split\\<^sub>i_list"), "tests/test_targets.py:287")
TestSupport.equal(V.set(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (V((c2("e").field("tag").cmp("Eq", V("LOCALE")))).truth) Vector(c2("e").field("name")) else Vector.empty }), vset(V("split\\<^sub>i_tree"), V("split\\<^sub>i_list")), "tests/test_targets.py:288")
}
private def case_TargetNameSpellings_test_a_name_that_is_only_a_symbol(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_a_name_that_is_only_a_symbol") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale \\<Z> =\n  fixes x :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V("\\<Z>"), "tests/test_targets.py:301")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("\\<Z>")), Map()).field("tag"), V("LOCALE"), "tests/test_targets.py:302")
}
private def case_TargetNameSpellings_test_a_quoted_locale_name(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_a_quoted_locale_name") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale \"functor\" =\n  fixes x :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V("functor"), "tests/test_targets.py:315")
}
private def case_TargetNameSpellings_test_a_quoted_instantiation_type(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_a_quoted_instantiation_type") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ninstantiation \"pseqp\" :: ord\nbegin\ndefinition foo :: nat where \"foo = 0\"\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("target"), V("pseqp"), "tests/test_targets.py:325")
}
private def case_TargetNameSpellings_test_a_symbol_instantiation_type(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_a_symbol_instantiation_type") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ninstantiation \\<o> :: AOT_subst\nbegin\ndefinition foo :: nat where \"foo = 0\"\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("target"), V("\\<o>"), "tests/test_targets.py:335")
}
private def case_TargetNameSpellings_test_a_qualified_target_keeps_its_dot(): Unit = TestSupport.test("test_targets.TargetNameSpellings.test_a_qualified_target_keeps_its_dot") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext Rings.dvd begin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V("Rings.dvd"), "tests/test_targets.py:346")
}
private def case_TargetsThatCorrectlyHaveNoName_test_a_bare_context_stays_anonymous(): Unit = TestSupport.test("test_targets.TargetsThatCorrectlyHaveNoName.test_a_bare_context_stays_anonymous") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext\n  fixes h :: nat\nbegin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V(""), "tests/test_targets.py:369")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("blocks"), vs(), "tests/test_targets.py:370")
}
private def case_TargetsThatCorrectlyHaveNoName_test_a_context_element_is_not_a_name(): Unit = TestSupport.test("test_targets.TargetsThatCorrectlyHaveNoName.test_a_context_element_is_not_a_name") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext fixes h :: nat begin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V(""), "tests/test_targets.py:379")
}
private def case_TargetsThatCorrectlyHaveNoName_test_a_cartouche_is_not_a_name(): Unit = TestSupport.test("test_targets.TargetsThatCorrectlyHaveNoName.test_a_cartouche_is_not_a_name") {
val e: Env = scala.collection.mutable.Map.empty


e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext \\<open>bogus\\<close>\nbegin\nlemma inside: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("inside")), Map()).field("target"), V(""), "tests/test_targets.py:391")
}
def run(): Unit = { case_LexicalNesting_test_lemma_inside_a_locale_records_it(); case_LexicalNesting_test_after_the_end_the_target_is_gone(); case_LexicalNesting_test_the_theory_block_is_not_reported(); case_LexicalNesting_test_context_reopening_a_locale(); case_LexicalNesting_test_anonymous_context_nests_but_is_not_named(); case_LexicalNesting_test_an_unrecognised_opener_still_nests(); case_LexicalNesting_test_a_declared_but_never_opened_locale_is_not_inherited(); case_LexicalNesting_test_class_and_instantiation_are_targets(); case_LexicalNesting_test_two_openers_and_two_begins_on_one_line(); case_LexicalNesting_test_an_indented_block_is_still_a_block(); case_ExplicitTarget_test_in_modifier_is_recorded(); case_ExplicitTarget_test_in_modifier_beats_lexical_nesting(); case_ExplicitTarget_test_a_term_mentioning_in_is_not_a_target(); case_Rendering_test_enclosing_names_the_locale(); case_Rendering_test_theory_level_entry_gets_no_scope_step(); case_TargetNameSpellings_test_a_markup_symbol_does_not_end_the_name(); case_TargetNameSpellings_test_two_locales_differing_only_in_markup_stay_distinct(); case_TargetNameSpellings_test_a_name_that_is_only_a_symbol(); case_TargetNameSpellings_test_a_quoted_locale_name(); case_TargetNameSpellings_test_a_quoted_instantiation_type(); case_TargetNameSpellings_test_a_symbol_instantiation_type(); case_TargetNameSpellings_test_a_qualified_target_keeps_its_dot(); case_TargetsThatCorrectlyHaveNoName_test_a_bare_context_stays_anonymous(); case_TargetsThatCorrectlyHaveNoName_test_a_context_element_is_not_a_name(); case_TargetsThatCorrectlyHaveNoName_test_a_cartouche_is_not_a_name() }
}
