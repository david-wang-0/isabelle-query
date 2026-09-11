package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_axiom_names.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_axiom_names {
private def h__axioms(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(e("snippet")).plus(e("FOOT"))), Map())
return V.dict(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((V((c1("e").field("tag").cmp("Eq", V("AXIOM")))).truth) && (V((c1("e").field("name").cmp("NotEq", V("?")))).truth)).truth) Vector((c1("e").field("name"), c1("e").field("thy_line"))) else Vector.empty })
V.none
}
private def case_WhereDoesNotEndTheCommand_test_where_in_column_zero(): Unit = TestSupport.test("test_axiom_names.WhereDoesNotEndTheCommand.test_where_in_column_zero") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization\n  f :: \"nat \\<Rightarrow> nat\" and\n  Cap :: \"nat\"\nwhere\n  lower: \"f 0 = 0\" and\n  Upper_case: \"f 1 = 1\" and\n  ax1: \"f 2 = 2\" and\n  prime': \"f 3 = 3\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("Cap"), V("Upper_case"), V("ax1"), V("f"), V("lower"), V("prime'")), "tests/test_axiom_names.py:67")
}
private def case_WhereDoesNotEndTheCommand_test_where_indented(): Unit = TestSupport.test("test_axiom_names.WhereDoesNotEndTheCommand.test_where_indented") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization\n  f :: \"nat\"\n  where lower: \"f = 0\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("f"), V("lower")), "tests/test_axiom_names.py:80")
}
private def case_WhereDoesNotEndTheCommand_test_bare_where_line_then_labels(): Unit = TestSupport.test("test_axiom_names.WhereDoesNotEndTheCommand.test_bare_where_line_then_labels") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization where\n  A1a:\"\\<lfloor>P\\<rfloor>\" and\n  A2: \"\\<lfloor>Q\\<rfloor>\" and\n  T2: \"\\<lfloor>R\\<rfloor>\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("A1a"), V("A2"), V("T2")), "tests/test_axiom_names.py:92")
}
private def case_NameOnTheCommandLine_test_label_after_where_on_the_command_line(): Unit = TestSupport.test("test_axiom_names.NameOnTheCommandLine.test_label_after_where_on_the_command_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization where process_finite:\n  \"OFCLASS(process, finite_class)\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("process_finite")), "tests/test_axiom_names.py:106")
}
private def case_NameOnTheCommandLine_test_constant_on_the_command_line_with_mixfix(): Unit = TestSupport.test("test_axiom_names.NameOnTheCommandLine.test_constant_on_the_command_line_with_mixfix") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization contents :: \"Person \\<Rightarrow> Set_Integer\" (\\<open>(1(_).contents'('))\\<close> 50)\nwhere contents_def:\n\"(self .contents()) = (\\<lambda> \\<tau>. SOME res. res)\"\nand cp0_contents:\"(X .contents()) \\<tau> = ((\\<lambda>_. X \\<tau>) .contents()) \\<tau>\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("contents"), V("contents_def"), V("cp0_contents")), "tests/test_axiom_names.py:118")
}
private def case_NameOnTheCommandLine_test_umbrella_and_head_name_share_a_line(): Unit = TestSupport.test("test_axiom_names.NameOnTheCommandLine.test_umbrella_and_head_name_share_a_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\ntext \\<open>Finiteness is needed for the maximum.\\<close>\naxiomatization where process_finite: \\<comment> \\<open>a note\\<close>\n  \"OFCLASS(process, finite_class)\"\n")).plus(e("FOOT"))), Map())
e("umbrella") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (V((V((c2("e").field("tag").cmp("Eq", V("AXIOM")))).truth) && (V((c2("e").field("name").cmp("Eq", V("?")))).truth)).truth) Vector(c2("e")) else Vector.empty })), Map())
e("at") = V.seq(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (V((c3("e").field("thy_line").cmp("Eq", c3("umbrella").field("thy_line")))).truth) Vector(c3("e").field("name")) else Vector.empty })
TestSupport.equal(e("at"), vs(V("?"), V("process_finite")), "tests/test_axiom_names.py:137")
}
private def case_SeveralNamesOnOneLine_test_constants_sharing_a_line(): Unit = TestSupport.test("test_axiom_names.SeveralNamesOnOneLine.test_constants_sharing_a_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization f :: \"nat\" and g :: \"nat\" and h :: \"nat\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("f"), V("g"), V("h")), "tests/test_axiom_names.py:154")
}
private def case_SeveralNamesOnOneLine_test_labels_sharing_a_line_after_where(): Unit = TestSupport.test("test_axiom_names.SeveralNamesOnOneLine.test_labels_sharing_a_line_after_where") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization\nwhere ax1: \"f 0 = 0\" and ax2: \"g 0 = 0\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("ax1"), V("ax2")), "tests/test_axiom_names.py:161")
}
private def case_SeveralNamesOnOneLine_test_they_all_report_the_line_they_are_on(): Unit = TestSupport.test("test_axiom_names.SeveralNamesOnOneLine.test_they_all_report_the_line_they_are_on") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("sec") = ParserBridge.call("section_from", Vector(e("HEAD").plus(V("\naxiomatization f :: \"nat\" and g :: \"nat\"\n")).plus(e("FOOT"))), Map())
e("lines") = V.dict(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (V((c4("e").field("tag").cmp("Eq", V("AXIOM")))).truth) Vector((c4("e").field("name"), c4("e").field("thy_line"))) else Vector.empty })
TestSupport.equal(e("lines").at(V("f")), e("lines").at(V("g")), "tests/test_axiom_names.py:168")
}
private def case_SeveralNamesOnOneLine_test_and_inside_a_proposition_does_not_split_it(): Unit = TestSupport.test("test_axiom_names.SeveralNamesOnOneLine.test_and_inside_a_proposition_does_not_split_it") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization\nwhere ax1: \"P \\<and> Q and R\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("ax1")), "tests/test_axiom_names.py:178")
}
private def case_NotEveryColonIsALabel_test_colon_inside_a_proposition_is_not_a_name(): Unit = TestSupport.test("test_axiom_names.NotEveryColonIsALabel.test_colon_inside_a_proposition_is_not_a_name") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization\nwhere ax1: \"(\n    inner :: nat) = 0\"\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("ax1")), "tests/test_axiom_names.py:191")
}
private def case_NotEveryColonIsALabel_test_a_following_command_ends_the_scan(): Unit = TestSupport.test("test_axiom_names.NotEveryColonIsALabel.test_a_following_command_ends_the_scan") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))

e("got") = h__axioms(e, Vector(V("\naxiomatization where ax1: \"P\"\nlemma foo: \"True\" by simp\n")), Map())
TestSupport.equal(ParserBridge.call("sorted", Vector(e("got")), Map()), vs(V("ax1")), "tests/test_axiom_names.py:199")
}
private def case_TheUmbrellaIsAnonymousAndStays_test_no_entry_is_named_after_the_keyword(): Unit = TestSupport.test("test_axiom_names.TheUmbrellaIsAnonymousAndStays.test_no_entry_is_named_after_the_keyword") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("axioms") = V.seq(e("sec").field("entries").seq.flatMap { item => val c5 = e.clone(); c5("e") = item; if (V((c5("e").field("tag").cmp("Eq", V("AXIOM")))).truth) Vector(c5("e")) else Vector.empty })
TestSupport.equal(V.seq(e("sec").field("entries").seq.flatMap { item => val c6 = e.clone(); c6("e") = item; if (V((c6("e").field("name").cmp("Eq", V("axiomatization")))).truth) Vector(c6("e").field("name")) else Vector.empty }), vs(), "tests/test_axiom_names.py:232")
}
private def case_TheUmbrellaIsAnonymousAndStays_test_the_umbrella_is_anonymous(): Unit = TestSupport.test("test_axiom_names.TheUmbrellaIsAnonymousAndStays.test_the_umbrella_is_anonymous") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("axioms") = V.seq(e("sec").field("entries").seq.flatMap { item => val c7 = e.clone(); c7("e") = item; if (V((c7("e").field("tag").cmp("Eq", V("AXIOM")))).truth) Vector(c7("e")) else Vector.empty })
TestSupport.equal(ParserBridge.call("sum", Vector(V.seq(e("axioms").seq.flatMap { item => val c8 = e.clone(); c8("e") = item; if (V((c8("e").field("name").cmp("Eq", V("?")))).truth) Vector(V(1)) else Vector.empty })), Map()), V(1), "tests/test_axiom_names.py:237")
}
private def case_TheUmbrellaIsAnonymousAndStays_test_the_declared_names_are_still_indexed(): Unit = TestSupport.test("test_axiom_names.TheUmbrellaIsAnonymousAndStays.test_the_declared_names_are_still_indexed") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("axioms") = V.seq(e("sec").field("entries").seq.flatMap { item => val c9 = e.clone(); c9("e") = item; if (V((c9("e").field("tag").cmp("Eq", V("AXIOM")))).truth) Vector(c9("e")) else Vector.empty })
TestSupport.equal(ParserBridge.call("sorted", Vector(V.seq(e("axioms").seq.flatMap { item => val c10 = e.clone(); c10("e") = item; if (V((c10("e").field("name").cmp("NotEq", V("?")))).truth) Vector(c10("e").field("name")) else Vector.empty })), Map()), vs(V("eq"), V("neq"), V("refl")), "tests/test_axiom_names.py:240")
}
private def case_TheUmbrellaIsAnonymousAndStays_test_the_anchor_owns_the_command_line(): Unit = TestSupport.test("test_axiom_names.TheUmbrellaIsAnonymousAndStays.test_the_anchor_owns_the_command_line") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("axioms") = V.seq(e("sec").field("entries").seq.flatMap { item => val c11 = e.clone(); c11("e") = item; if (V((c11("e").field("tag").cmp("Eq", V("AXIOM")))).truth) Vector(c11("e")) else Vector.empty })
e("umbrella") = ParserBridge.call("next", Vector(V.seq(e("axioms").seq.flatMap { item => val c12 = e.clone(); c12("e") = item; if (V((c12("e").field("name").cmp("Eq", V("?")))).truth) Vector(c12("e")) else Vector.empty })), Map())
e("cmd_line") = ParserBridge.call("next", Vector(V.seq(ParserBridge.call("enumerate", Vector(e("sec").invoke("source", Vector(), Map()), V(1)), Map()).seq.flatMap { item => val c13 = e.clone(); val unpack14 = item; TestSupport.equal(unpack14.seq.size, 2, "unpack arity"); c13("i") = unpack14.at(V(0)); c13("ln") = unpack14.at(V(1)); if (c13("ln").invoke("startswith", Vector(V("axiomatization")), Map()).truth) Vector(c13("i")) else Vector.empty })), Map())
TestSupport.equal(e("umbrella").field("thy_line"), e("cmd_line"), "tests/test_axiom_names.py:249")
}
private def case_TheUmbrellaIsAnonymousAndStays_test_an_anonymous_entry_mints_no_citation_edge(): Unit = TestSupport.test("test_axiom_names.TheUmbrellaIsAnonymousAndStays.test_an_anonymous_entry_mints_no_citation_edge") {
val e: Env = scala.collection.mutable.Map.empty
e("HEAD") = V("theory T imports Main begin\n")
e("FOOT") = V("\nend\n")
e("TheUmbrellaIsAnonymousAndStays.SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("SRC") = e("HEAD").plus(V("\nlemma before_it: \"True\" by simp\n\naxiomatization\n  eq :: \"nat\" and\n  neq :: \"nat\"\nwhere refl: \"eq = eq\"\n")).plus(e("FOOT"))
e("sec") = ParserBridge.call("section_from", Vector(e("SRC")), Map())
e("axioms") = V.seq(e("sec").field("entries").seq.flatMap { item => val c15 = e.clone(); c15("e") = item; if (V((c15("e").field("tag").cmp("Eq", V("AXIOM")))).truth) Vector(c15("e")) else Vector.empty })
e("g") = ParserBridge.call("graph._build_call_graph", Vector(vs(e("sec"))), Map())
TestSupport.check(!e("g").field("all_names").has(V("?")), "tests/test_axiom_names.py:256" + " actual=" + e("g").field("all_names"))
TestSupport.check(!e("g").field("all_names").has(V("axiomatization")), "tests/test_axiom_names.py:257" + " actual=" + e("g").field("all_names"))
}
def run(): Unit = { case_WhereDoesNotEndTheCommand_test_where_in_column_zero(); case_WhereDoesNotEndTheCommand_test_where_indented(); case_WhereDoesNotEndTheCommand_test_bare_where_line_then_labels(); case_NameOnTheCommandLine_test_label_after_where_on_the_command_line(); case_NameOnTheCommandLine_test_constant_on_the_command_line_with_mixfix(); case_NameOnTheCommandLine_test_umbrella_and_head_name_share_a_line(); case_SeveralNamesOnOneLine_test_constants_sharing_a_line(); case_SeveralNamesOnOneLine_test_labels_sharing_a_line_after_where(); case_SeveralNamesOnOneLine_test_they_all_report_the_line_they_are_on(); case_SeveralNamesOnOneLine_test_and_inside_a_proposition_does_not_split_it(); case_NotEveryColonIsALabel_test_colon_inside_a_proposition_is_not_a_name(); case_NotEveryColonIsALabel_test_a_following_command_ends_the_scan(); case_TheUmbrellaIsAnonymousAndStays_test_no_entry_is_named_after_the_keyword(); case_TheUmbrellaIsAnonymousAndStays_test_the_umbrella_is_anonymous(); case_TheUmbrellaIsAnonymousAndStays_test_the_declared_names_are_still_indexed(); case_TheUmbrellaIsAnonymousAndStays_test_the_anchor_owns_the_command_line(); case_TheUmbrellaIsAnonymousAndStays_test_an_anonymous_entry_mints_no_citation_edge() }
}
