package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_declared_names.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_declared_names {
private def h__bindings(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(V.none))
e("sec") = ParserBridge.call("section_from", Vector(e("snippet")), Map())
e("ents") = V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((V((c1("name").cmp("Is", V.none))).truth) || (V((c1("e").field("name").cmp("Eq", c1("name")))).truth)).truth) Vector(c1("e")) else Vector.empty })
TestSupport.check(e("ents").truth, V("no entry named " + e("name").str + " in " + V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (true) Vector(c2("e").field("name")) else Vector.empty }).str).str)
return V.dict(e("ents").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (true) c3("e").field("bindings").seq.flatMap { item => val c4 = c3.clone(); val unpack5 = item; TestSupport.equal(unpack5.seq.size, 2, "unpack arity"); c4("n") = unpack5.at(V(0)); c4("k") = unpack5.at(V(1)); if (true) Vector((c4("n"), c4("k"))) else Vector.empty } else Vector.empty })
V.none
}
private def case_InductiveRuleNames_test_the_reference_case(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_the_reference_case") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

e("got") = h__bindings(e, Vector(e("HEAD").plus(V("\ninductive terminate :: \"recf \\<Rightarrow> nat list \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate z [n]\"\n  | termi_s: \"terminate s [n]\"\n  | termi_id: \"\\<lbrakk>n < m\\<rbrakk> \\<Longrightarrow> terminate (id m n) xs\"\n")).plus(e("FOOT")), V("terminate")), Map())
TestSupport.equal(e("got"), vm((V("termi_z"), V("rule")), (V("termi_s"), V("rule")), (V("termi_id"), V("rule"))), "tests/test_declared_names.py:49")
}
private def case_InductiveRuleNames_test_unnamed_rules_bind_nothing(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_unnamed_rules_bind_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ninductive p :: \"nat \\<Rightarrow> bool\"\n  where\n    \"p 0\"\n  | \"p n \\<Longrightarrow> p (Suc n)\"\n")).plus(e("FOOT")), V("p")), Map()), vm(), "tests/test_declared_names.py:53")
}
private def case_InductiveRuleNames_test_attributes_on_the_label(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_attributes_on_the_label") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ninductive p :: \"nat \\<Rightarrow> bool\"\n  where\n    base[simp, intro]: \"p 0\"\n  | step [intro!]: \"p n \\<Longrightarrow> p (Suc n)\"\n")).plus(e("FOOT")), V("p")), Map()), vm((V("base"), V("rule")), (V("step"), V("rule"))), "tests/test_declared_names.py:61")
}
private def case_InductiveRuleNames_test_a_for_clause_binds_no_rules(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_a_for_clause_binds_no_rules") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ninductive_set reach :: \"nat set\"\n  for A :: \"nat set\" and I :: \"nat set\"\n  where\n    reach_init: \"x \\<in> I \\<Longrightarrow> x \\<in> reach A I\"\n")).plus(e("FOOT")), V("reach")), Map()), vm((V("reach_init"), V("rule"))), "tests/test_declared_names.py:72")
}
private def case_InductiveRuleNames_test_a_bar_inside_a_term_is_not_a_rule_separator(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_a_bar_inside_a_term_is_not_a_rule_separator") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nfun f :: \"nat \\<Rightarrow> nat\"\n  where\n    \"f n = (case n of 0 \\<Rightarrow> 1 | xs: Suc m \\<Rightarrow> m)\"\n")).plus(e("FOOT")), V("f")), Map()), vm(), "tests/test_declared_names.py:82")
}
private def case_InductiveRuleNames_test_the_entrys_own_name_is_not_a_binding(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_the_entrys_own_name_is_not_a_binding") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ndefinition c :: \"nat\"\n  where c: \"c = 0\"\n")).plus(e("FOOT")), V("c")), Map()), vm(), "tests/test_declared_names.py:89")
}
private def case_InductiveRuleNames_test_a_repeated_label_is_recorded_once(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_a_repeated_label_is_recorded_once") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ninductive p :: \"nat \\<Rightarrow> bool\"\n  where\n    base: \"p 0\"\n  | base: \"p 1\"\n")).plus(e("FOOT"))), Map())
val unpack7 = V.seq(e("sec").field("entries").seq.flatMap { item => val c6 = e.clone(); c6("e") = item; if (V((c6("e").field("name").cmp("Eq", V("p")))).truth) Vector(c6("e")) else Vector.empty }); TestSupport.equal(unpack7.seq.size, 1, "unpack arity"); e("e") = unpack7.at(V(0))
TestSupport.equal(e("e").field("bindings"), vs(vs(V("base"), V("rule"))), "tests/test_declared_names.py:104")
}
private def case_InductiveRuleNames_test_a_one_line_declaration(): Unit = TestSupport.test("test_declared_names.InductiveRuleNames.test_a_one_line_declaration") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("inductive p where r1: \"p 0\"")).plus(e("FOOT")), V("p")), Map()), vm((V("r1"), V("rule"))), "tests/test_declared_names.py:107")
}
private def case_LabelPattern_test_a_type_ascription_is_not_a_label(): Unit = TestSupport.test("test_declared_names.LabelPattern.test_a_type_ascription_is_not_a_label") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(ParserBridge.constant("cli.RULE_LABEL_RE").invoke("findall", Vector(V("where x :: \"nat\" | y :: \"bool\"")), Map()), vs(), "tests/test_declared_names.py:124")
}
private def case_LabelPattern_test_a_single_colon_is(): Unit = TestSupport.test("test_declared_names.LabelPattern.test_a_single_colon_is") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(ParserBridge.constant("cli.RULE_LABEL_RE").invoke("findall", Vector(V("where x: \"P\" | y: \"Q\"")), Map()), vs(V("x"), V("y")), "tests/test_declared_names.py:128")
}
private def case_LabelPattern_test_a_locale_element_colon_is_never_a_type_ascription(): Unit = TestSupport.test("test_declared_names.LabelPattern.test_a_locale_element_colon_is_never_a_type_ascription") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.check(ParserBridge.constant("cli._LOCALE_LABEL_RE").invoke("match", Vector(V(" g :: \"T\"")), Map()).isNone, "tests/test_declared_names.py:135")
TestSupport.equal(ParserBridge.constant("cli._LOCALE_LABEL_RE").invoke("match", Vector(V(" a: \"P\"")), Map()).invoke("group", Vector(V(1)), Map()), V("a"), "tests/test_declared_names.py:136")
}
private def case_LabelPattern_test_a_selector_colon_is_never_a_type_ascription(): Unit = TestSupport.test("test_declared_names.LabelPattern.test_a_selector_colon_is_never_a_type_ascription") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(ParserBridge.constant("cli._SELECTOR_RE").invoke("findall", Vector(V("(x :: nat)")), Map()), vs(), "tests/test_declared_names.py:142")
TestSupport.equal(ParserBridge.constant("cli._SELECTOR_RE").invoke("findall", Vector(V("(x: nat)")), Map()), vs(V("x")), "tests/test_declared_names.py:143")
}
private def case_LabelsOutsideTheInductiveFamily_test_definition_with_a_named_equation(): Unit = TestSupport.test("test_declared_names.LabelsOutsideTheInductiveFamily.test_definition_with_a_named_equation") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ndefinition F :: \"nat \\<Rightarrow> nat\"\n  where eq_fold: \"F x = x\"\n")).plus(e("FOOT")), V("F")), Map()), vm((V("eq_fold"), V("rule"))), "tests/test_declared_names.py:153")
}
private def case_LabelsOutsideTheInductiveFamily_test_primrec_with_named_equations(): Unit = TestSupport.test("test_declared_names.LabelsOutsideTheInductiveFamily.test_primrec_with_named_equations") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nprimrec nodup :: \"nat list \\<Rightarrow> bool\"\n  where\n    nodup_nil: \"nodup [] = True\"\n  | nodup_step: \"nodup (x # xs) = (x \\<notin> set xs)\"\n")).plus(e("FOOT")), V("nodup")), Map()), vm((V("nodup_nil"), V("rule")), (V("nodup_step"), V("rule"))), "tests/test_declared_names.py:160")
}
private def case_AndSiblings_test_mutually_recursive_functions(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_mutually_recursive_functions") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nfun maxmin :: \"nat \\<Rightarrow> nat\" and minmax :: \"nat \\<Rightarrow> nat\"\n  where\n    \"maxmin 0 = 0\"\n  | \"minmax 0 = 0\"\n")).plus(e("FOOT")), V("maxmin")), Map()), vm((V("minmax"), V("sibling"))), "tests/test_declared_names.py:177")
}
private def case_AndSiblings_test_three_way(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_three_way") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nfun f :: \"nat \\<Rightarrow> nat\" and g :: \"nat \\<Rightarrow> nat\"\n    and h :: \"nat \\<Rightarrow> nat\"\n  where \"f 0 = 0\"\n")).plus(e("FOOT")), V("f")), Map()), vm((V("g"), V("sibling")), (V("h"), V("sibling"))), "tests/test_declared_names.py:185")
}
private def case_AndSiblings_test_a_for_clause_is_not_a_sibling_list(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_a_for_clause_is_not_a_sibling_list") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ninductive_set reach :: \"nat set\"\n  for A :: \"nat set\" and I :: \"nat set\"\n  where \"x \\<in> reach A I\"\n")).plus(e("FOOT")), V("reach")), Map()), vm(), "tests/test_declared_names.py:195")
}
private def case_AndSiblings_test_an_and_inside_a_term_is_invisible(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_an_and_inside_a_term_is_invisible") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nfun f :: \"bool \\<Rightarrow> bool\"\n  where \"f x = (x \\<and> and_of x)\"\n")).plus(e("FOOT")), V("f")), Map()), vm(), "tests/test_declared_names.py:202")
}
private def case_AndSiblings_test_a_name_ending_in_and_is_not_a_separator(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_a_name_ending_in_and_is_not_a_separator") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nfun band and c\n  where \"band 0 = (0::nat)\"\n")).plus(e("FOOT")), V("band")), Map()), vm((V("c"), V("sibling"))), "tests/test_declared_names.py:211")
}
private def case_AndSiblings_test_the_helpers_own_name_guard(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_the_helpers_own_name_guard") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(ParserBridge.call("cli._and_siblings", Vector(vs(V("fun f :: \"nat\" and f :: \"nat\" where")), V(1), V(1), V("f"), V("FUN")), Map()), vs(), "tests/test_declared_names.py:220")
}
private def case_AndSiblings_test_definition_takes_no_and_list(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_definition_takes_no_and_list") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ndefinition d :: \"nat\"\n  where \"d = 0\"\n")).plus(e("FOOT")), V("d")), Map()), vm(), "tests/test_declared_names.py:227")
}
private def case_AndSiblings_test_a_custom_command_is_not_guessed_at(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_a_custom_command_is_not_guessed_at") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

e("src") = V("theory T imports Main\n  keywords \"AOT_register_type_constraints\" :: thy_decl\nbegin\nAOT_register_type_constraints\n  Individual: nat and\n  Proposition: bool\n").plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("src")), Map())
TestSupport.equal(V.seq(e("sec").field("entries").seq.flatMap { item => val c8 = e.clone(); c8("e") = item; if (V((c8("e").field("bindings").truth) && (ParserBridge.call("any", Vector(V.seq(c8("e").field("bindings").seq.flatMap { item => val c9 = c8.clone(); val unpack10 = item; TestSupport.equal(unpack10.seq.size, 2, "unpack arity"); c9("_") = unpack10.at(V(0)); c9("k") = unpack10.at(V(1)); if (true) Vector(V((c9("k").cmp("Eq", V("sibling"))))) else Vector.empty })), Map()).truth)).truth) Vector(c8("e").field("bindings")) else Vector.empty }), vs(), "tests/test_declared_names.py:244")
}
private def case_AndSiblings_test_siblings_and_rules_coexist(): Unit = TestSupport.test("test_declared_names.AndSiblings.test_siblings_and_rules_coexist") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

e("got") = h__bindings(e, Vector(e("HEAD").plus(V("\ninductive p :: \"nat \\<Rightarrow> bool\" and q :: \"nat \\<Rightarrow> bool\"\n  where\n    pq_base: \"p 0\"\n  | pq_step: \"q n \\<Longrightarrow> p n\"\n")).plus(e("FOOT")), V("p")), Map())
TestSupport.equal(e("got"), vm((V("q"), V("sibling")), (V("pq_base"), V("rule")), (V("pq_step"), V("rule"))), "tests/test_declared_names.py:255")
}
private def case_ProofBodiesAreNotScanned_test_obtain_in_a_proof_binds_nothing(): Unit = TestSupport.test("test_declared_names.ProofBodiesAreNotScanned.test_obtain_in_a_proof_binds_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

e("got") = h__bindings(e, Vector(e("HEAD").plus(V("\nlemma foo: \"True\"\nproof -\n  obtain S' where S: \"S' = (0::nat)\" by blast\n  show ?thesis by simp\nqed\n")).plus(e("FOOT")), V("foo")), Map())
TestSupport.equal(e("got"), vm(), "tests/test_declared_names.py:273")
}
private def case_ProofBodiesAreNotScanned_test_a_shows_conjunct_is_still_tagged_conjunct(): Unit = TestSupport.test("test_declared_names.ProofBodiesAreNotScanned.test_a_shows_conjunct_is_still_tagged_conjunct") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

e("got") = h__bindings(e, Vector(e("HEAD").plus(V("\nlemma bar:\n  assumes \"True\"\n  shows a: \"1 = (1::nat)\" and b: \"2 = (2::nat)\"\n  by simp_all\n")).plus(e("FOOT")), V("bar")), Map())
TestSupport.equal(e("got"), vm((V("a"), V("conjunct")), (V("b"), V("conjunct"))), "tests/test_declared_names.py:282")
}
private def case_TypeDeclarations_test_constructors(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_constructors") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("datatype Role = Party1 | Party2 | Party3")).plus(e("FOOT")), V("Role")), Map()), vm((V("Party1"), V("constructor")), (V("Party2"), V("constructor")), (V("Party3"), V("constructor"))), "tests/test_declared_names.py:293")
}
private def case_TypeDeclarations_test_a_multi_line_datatype(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_multi_line_datatype") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ndatatype 'a t =\n    A nat\n  | B \"'a list\"\n  | C\n")).plus(e("FOOT")), V("t")), Map()), vm((V("A"), V("constructor")), (V("B"), V("constructor")), (V("C"), V("constructor"))), "tests/test_declared_names.py:302")
}
private def case_TypeDeclarations_test_a_discriminator(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_discriminator") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("datatype variable = varname: Var name")).plus(e("FOOT")), V("variable")), Map()), vm((V("varname"), V("discriminator")), (V("Var"), V("constructor"))), "tests/test_declared_names.py:313")
}
private def case_TypeDeclarations_test_selectors(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_selectors") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\ndatatype 'ent atom = predAtm (predicate: predicate) (args: \"'ent list\")\n                   | Eq (lhs: 'ent) (rhs: 'ent)\n")).plus(e("FOOT")), V("atom")), Map()), vm((V("predAtm"), V("constructor")), (V("predicate"), V("selector")), (V("args"), V("selector")), (V("Eq"), V("constructor")), (V("lhs"), V("selector")), (V("rhs"), V("selector"))), "tests/test_declared_names.py:320")
}
private def case_TypeDeclarations_test_a_constructor_spelled_with_markup(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_constructor_spelled_with_markup") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("datatype t = View\\<^sub>m nat")).plus(e("FOOT")), V("t")), Map()), vm((V("View\\<^sub>m"), V("constructor"))), "tests/test_declared_names.py:332")
}
private def case_TypeDeclarations_test_a_constructor_named_for_its_type_is_not_a_binding(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_constructor_named_for_its_type_is_not_a_binding") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("datatype 'a list_R1 = list_R1 (unR: \"'a\")")).plus(e("FOOT")), V("list_R1")), Map()), vm((V("unR"), V("selector"))), "tests/test_declared_names.py:338")
}
private def case_TypeDeclarations_test_argument_types_are_not_constructors(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_argument_types_are_not_constructors") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("datatype t = A \"nat \\<Rightarrow> bool option\"")).plus(e("FOOT")), V("t")), Map()), vm((V("A"), V("constructor"))), "tests/test_declared_names.py:346")
}
private def case_TypeDeclarations_test_a_record_declares_its_fields(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_record_declares_its_fields") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("\nrecord point =\n  x :: nat\n  y :: nat\n")).plus(e("FOOT")), V("point")), Map()), vm((V("x"), V("field")), (V("y"), V("field"))), "tests/test_declared_names.py:357")
}
private def case_TypeDeclarations_test_a_records_parent_type_is_not_a_field(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_records_parent_type_is_not_a_field") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

vs(V("\"'a point\""), V("point")).seq.foreach { item => e("parent") = item;
TestSupport.subcase("tests/test_declared_names.py:368" + vm((V("parent"), e("parent"))).str) {
TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("record cpoint = " + e("parent").str + " +\n  col :: nat\n")).plus(e("FOOT")), V("cpoint")), Map()), vm((V("col"), V("field"))), "tests/test_declared_names.py:369")
}
}
}
private def case_TypeDeclarations_test_a_field_type_contributes_no_names(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_field_type_contributes_no_names") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("record r =\n  edges :: \"('n, 'p) edge set\"\n")).plus(e("FOOT")), V("r")), Map()), vm((V("edges"), V("field"))), "tests/test_declared_names.py:377")
}
private def case_TypeDeclarations_test_a_field_spelled_with_markup_keeps_its_markup(): Unit = TestSupport.test("test_declared_names.TypeDeclarations.test_a_field_spelled_with_markup_keeps_its_markup") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))

TestSupport.equal(h__bindings(e, Vector(e("HEAD").plus(V("record r =\n  gen_\\<alpha>e :: \"nat\"\n  \\<R>\\<^sub>A_rel :: \"nat\"\n")).plus(e("FOOT")), V("r")), Map()), vm((V("gen_\\<alpha>e"), V("field")), (V("\\<R>\\<^sub>A_rel"), V("field"))), "tests/test_declared_names.py:386")
}
private def case_LocaleAndClass_test_the_locale_is_an_entry(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_the_locale_is_an_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
val unpack12 = V.seq(e("sec").field("entries").seq.flatMap { item => val c11 = e.clone(); c11("e") = item; if (V((c11("e").field("name").cmp("Eq", V("hpk")))).truth) Vector(c11("e")) else Vector.empty }); TestSupport.equal(unpack12.seq.size, 1, "unpack arity"); e("e") = unpack12.at(V(0))
TestSupport.equal(e("e").field("tag"), V("LOCALE"), "tests/test_declared_names.py:413")
}
private def case_LocaleAndClass_test_the_span_is_the_head_only(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_the_span_is_the_head_only") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
val unpack14 = V.seq(e("sec").field("entries").seq.flatMap { item => val c13 = e.clone(); c13("e") = item; if (V((c13("e").field("name").cmp("Eq", V("hpk")))).truth) Vector(c13("e")) else Vector.empty }); TestSupport.equal(unpack14.seq.size, 1, "unpack arity"); e("e") = unpack14.at(V(0))
TestSupport.equal(vs(e("e").field("thy_line"), e("e").field("decl_end_line")), vs(V(2), V(7)), "tests/test_declared_names.py:421")
}
private def case_LocaleAndClass_test_assumptions_and_defines_are_bound_with_their_kind(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_assumptions_and_defines_are_bound_with_their_kind") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
TestSupport.equal(h__bindings(e, Vector(e("SRC"), V("hpk")), Map()), vm((V("commute"), V("assumption")), (V("idem"), V("assumption")), (V("h_def"), V("definition"))), "tests/test_declared_names.py:424")
}
private def case_LocaleAndClass_test_fixed_parameters_are_not_bound(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_fixed_parameters_are_not_bound") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
TestSupport.check(!h__bindings(e, Vector(e("SRC"), V("hpk")), Map()).has(V("f")), "tests/test_declared_names.py:431" + " actual=" + h__bindings(e, Vector(e("SRC"), V("hpk")), Map()))
TestSupport.check(!h__bindings(e, Vector(e("SRC"), V("hpk")), Map()).has(V("g")), "tests/test_declared_names.py:432" + " actual=" + h__bindings(e, Vector(e("SRC"), V("hpk")), Map()))
}
private def case_LocaleAndClass_test_an_entry_inside_still_targets_the_locale(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_an_entry_inside_still_targets_the_locale") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
val unpack16 = V.seq(e("sec").field("entries").seq.flatMap { item => val c15 = e.clone(); c15("e") = item; if (V((c15("e").field("name").cmp("Eq", V("inner")))).truth) Vector(c15("e")) else Vector.empty }); TestSupport.equal(unpack16.seq.size, 1, "unpack arity"); e("e") = unpack16.at(V(0))
TestSupport.equal(e("e").field("target"), V("hpk"), "tests/test_declared_names.py:437")
}
private def case_LocaleAndClass_test_a_class_is_an_entry_too(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_a_class_is_an_entry_too") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nclass ord =\n  fixes less :: \"'a \\<Rightarrow> 'a \\<Rightarrow> bool\"\n  assumes irrefl: \"\\<not> less x x\"\nbegin\nend\nend\n")), Map())
val unpack18 = V.seq(e("sec").field("entries").seq.flatMap { item => val c17 = e.clone(); c17("e") = item; if (V((c17("e").field("name").cmp("Eq", V("ord")))).truth) Vector(c17("e")) else Vector.empty }); TestSupport.equal(unpack18.seq.size, 1, "unpack arity"); e("e") = unpack18.at(V(0))
TestSupport.equal(e("e").field("tag"), V("CLASS"), "tests/test_declared_names.py:448")
TestSupport.equal(V.seq(e("e").field("bindings").seq.flatMap { item => val c19 = e.clone(); val unpack20 = item; TestSupport.equal(unpack20.seq.size, 2, "unpack arity"); c19("n") = unpack20.at(V(0)); c19("_") = unpack20.at(V(1)); if (true) Vector(c19("n")) else Vector.empty }), vs(V("irrefl")), "tests/test_declared_names.py:449")
}
private def case_LocaleAndClass_test_context_and_interpretation_declare_nothing(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_context_and_interpretation_declare_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\nlocale foo begin\nend\ncontext foo begin\nlemma a: \"True\" by simp\nend\ninterpretation bar: foo by standard\nend\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c21 = e.clone(); c21("e") = item; if (true) Vector(c21("e").field("name")) else Vector.empty })), Map()), vs(V("a"), V("foo")), "tests/test_declared_names.py:463")
}
private def case_LocaleAndClass_test_a_second_fixes_group_resets_the_element_kind(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_a_second_fixes_group_resets_the_element_kind") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("got") = h__bindings(e, Vector(V("theory T imports Main begin\nlocale akra_bazzi_real =\n  fixes integrable integral\n  assumes integral: \"True\"\n  fixes g :: \"nat \\<Rightarrow> real\"\n    and C :: real\nbegin\nend\nend\n"), V("akra_bazzi_real")), Map())
TestSupport.equal(e("got"), vm((V("integral"), V("assumption"))), "tests/test_declared_names.py:480")
}
private def case_LocaleAndClass_test_a_for_clause_after_assumes_binds_nothing(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_a_for_clause_after_assumes_binds_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("got") = h__bindings(e, Vector(V("theory T imports Main begin\nlocale L = base +\n  assumes a: \"True\"\n  for x and y\nbegin\nend\nend\n"), V("L")), Map())
TestSupport.equal(e("got"), vm((V("a"), V("assumption"))), "tests/test_declared_names.py:490")
}
private def case_LocaleAndClass_test_a_label_must_follow_its_keyword_immediately(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_a_label_must_follow_its_keyword_immediately") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("got") = h__bindings(e, Vector(V("theory T imports Main begin\nlocale L =\n  assumes \"True\"\n  notes n = conjI\n  notes m: conjI\nbegin\nend\nend\n"), V("L")), Map())
TestSupport.equal(e("got"), vm((V("m"), V("note"))), "tests/test_declared_names.py:504")
}
private def case_LocaleAndClass_test_the_helpers_parameter_and_own_name_guards(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_the_helpers_parameter_and_own_name_guards") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
TestSupport.equal(ParserBridge.call("cli._locale_facts", Vector(vs(V("locale L = assumes a: True fixes x and y: T")), V(1), V(1), V("L")), Map()), vs(vs(V("a"), V("assumption"))), "tests/test_declared_names.py:510")
TestSupport.equal(ParserBridge.call("cli._locale_facts", Vector(vs(V("locale L = assumes L: True")), V(1), V(1), V("L")), Map()), vs(), "tests/test_declared_names.py:515")
}
private def case_LocaleAndClass_test_an_anonymous_context_mints_no_entry(): Unit = TestSupport.test("test_declared_names.LocaleAndClass.test_an_anonymous_context_mints_no_entry") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(V("theory T imports Main begin\ncontext fixes x :: nat begin\nlemma a: \"True\" by simp\nend\nend\n")), Map())
TestSupport.equal(V.seq(e("sec").field("entries").seq.flatMap { item => val c22 = e.clone(); c22("e") = item; if (true) Vector(c22("e").field("name")) else Vector.empty }), vs(V("a")), "tests/test_declared_names.py:524")
}
private def case_Resolution_test_the_declaration_is_not_its_own_caller(): Unit = TestSupport.test("test_declared_names.Resolution.test_the_declaration_is_not_its_own_caller") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("hits") = ParserBridge.call("cli._find_callers", Vector(vs(ParserBridge.call("section_from", Vector(e("SRC")), Map())), V("termi_z")), Map())
TestSupport.equal(V.seq(e("hits").seq.flatMap { item => val c23 = e.clone(); val unpack24 = item; TestSupport.equal(unpack24.seq.size, 3, "unpack arity"); c23("s") = unpack24.at(V(0)); c23("ln") = unpack24.at(V(1)); c23("_") = unpack24.at(V(2)); if (true) Vector(vs(c23("s").field("theory"), c23("ln"))) else Vector.empty }), vs(vs(V("Test"), V(9))), V("expected only the `intro:` citation, got " + e("hits").str).str)
}
private def case_Resolution_test_the_declaration_site_is_registered(): Unit = TestSupport.test("test_declared_names.Resolution.test_the_declaration_site_is_registered") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("sites") = ParserBridge.call("cli._build_def_sites", Vector(vs(e("sec"))), Map("names" -> vset(V("termi_z"))))
TestSupport.check(e("sites").at(e("sec").field("path")).has(V("termi_z")), "tests/test_declared_names.py:554" + " actual=" + e("sites").at(e("sec").field("path")))
}
private def case_Resolution_test_find_matches_via_a_bound_name(): Unit = TestSupport.test("test_declared_names.Resolution.test_find_matches_via_a_bound_name") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("LocaleAndClass.SRC") = V("theory T imports Main begin\nlocale hpk =\n  fixes f :: \"nat \\<Rightarrow> nat\"\n    and g :: \"nat \\<Rightarrow> nat\"\n  assumes commute: \"f (g x) = g (f x)\"\n      and idem[simp]: \"f (f x) = f x\"\n  defines h_def: \"h \\<equiv> f\"\nbegin\nlemma inner: \"True\" by simp\nend\nend\n")
e("Resolution.SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\ninductive terminate :: \"nat \\<Rightarrow> bool\"\n  where\n    termi_z: \"terminate 0\"\n  | termi_s: \"terminate n \\<Longrightarrow> terminate (Suc n)\"\n\nlemma uses_it: \"terminate 0\"\n  by (auto intro: termi_z)\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("hits") = V.seq(e("sec").field("entries").seq.flatMap { item => val c25 = e.clone(); c25("e") = item; if (V((c25("e").field("bound_names").has(V("termi_s")))).truth) Vector(c25("e").field("name")) else Vector.empty })
TestSupport.equal(e("hits"), vs(V("terminate")), "tests/test_declared_names.py:560")
}
def run(): Unit = { case_InductiveRuleNames_test_the_reference_case(); case_InductiveRuleNames_test_unnamed_rules_bind_nothing(); case_InductiveRuleNames_test_attributes_on_the_label(); case_InductiveRuleNames_test_a_for_clause_binds_no_rules(); case_InductiveRuleNames_test_a_bar_inside_a_term_is_not_a_rule_separator(); case_InductiveRuleNames_test_the_entrys_own_name_is_not_a_binding(); case_InductiveRuleNames_test_a_repeated_label_is_recorded_once(); case_InductiveRuleNames_test_a_one_line_declaration(); case_LabelPattern_test_a_type_ascription_is_not_a_label(); case_LabelPattern_test_a_single_colon_is(); case_LabelPattern_test_a_locale_element_colon_is_never_a_type_ascription(); case_LabelPattern_test_a_selector_colon_is_never_a_type_ascription(); case_LabelsOutsideTheInductiveFamily_test_definition_with_a_named_equation(); case_LabelsOutsideTheInductiveFamily_test_primrec_with_named_equations(); case_AndSiblings_test_mutually_recursive_functions(); case_AndSiblings_test_three_way(); case_AndSiblings_test_a_for_clause_is_not_a_sibling_list(); case_AndSiblings_test_an_and_inside_a_term_is_invisible(); case_AndSiblings_test_a_name_ending_in_and_is_not_a_separator(); case_AndSiblings_test_the_helpers_own_name_guard(); case_AndSiblings_test_definition_takes_no_and_list(); case_AndSiblings_test_a_custom_command_is_not_guessed_at(); case_AndSiblings_test_siblings_and_rules_coexist(); case_ProofBodiesAreNotScanned_test_obtain_in_a_proof_binds_nothing(); case_ProofBodiesAreNotScanned_test_a_shows_conjunct_is_still_tagged_conjunct(); case_TypeDeclarations_test_constructors(); case_TypeDeclarations_test_a_multi_line_datatype(); case_TypeDeclarations_test_a_discriminator(); case_TypeDeclarations_test_selectors(); case_TypeDeclarations_test_a_constructor_spelled_with_markup(); case_TypeDeclarations_test_a_constructor_named_for_its_type_is_not_a_binding(); case_TypeDeclarations_test_argument_types_are_not_constructors(); case_TypeDeclarations_test_a_record_declares_its_fields(); case_TypeDeclarations_test_a_records_parent_type_is_not_a_field(); case_TypeDeclarations_test_a_field_type_contributes_no_names(); case_TypeDeclarations_test_a_field_spelled_with_markup_keeps_its_markup(); case_LocaleAndClass_test_the_locale_is_an_entry(); case_LocaleAndClass_test_the_span_is_the_head_only(); case_LocaleAndClass_test_assumptions_and_defines_are_bound_with_their_kind(); case_LocaleAndClass_test_fixed_parameters_are_not_bound(); case_LocaleAndClass_test_an_entry_inside_still_targets_the_locale(); case_LocaleAndClass_test_a_class_is_an_entry_too(); case_LocaleAndClass_test_context_and_interpretation_declare_nothing(); case_LocaleAndClass_test_a_second_fixes_group_resets_the_element_kind(); case_LocaleAndClass_test_a_for_clause_after_assumes_binds_nothing(); case_LocaleAndClass_test_a_label_must_follow_its_keyword_immediately(); case_LocaleAndClass_test_the_helpers_parameter_and_own_name_guards(); case_LocaleAndClass_test_an_anonymous_context_mints_no_entry(); case_Resolution_test_the_declaration_is_not_its_own_caller(); case_Resolution_test_the_declaration_site_is_registered(); case_Resolution_test_find_matches_via_a_bound_name() }
}
