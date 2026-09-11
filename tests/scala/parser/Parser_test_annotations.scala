package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_annotations.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_annotations {
private def h_entry(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
return ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (V((c1("e").field("name").cmp("Eq", c1("name")))).truth) Vector(c1("e")) else Vector.empty })), Map())
V.none
}
private def h_tagged(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("sec") = kw.getOrElse("sec", args.lift(0).getOrElse(throw new IllegalArgumentException("missing sec")))
e("name") = kw.getOrElse("name", args.lift(1).getOrElse(throw new IllegalArgumentException("missing name")))
e("kind") = kw.getOrElse("kind", args.lift(2).getOrElse(V.none))
return V.seq(h_entry(e, Vector(e("sec"), e("name")), Map()).field("annotations").seq.flatMap { item => val c2 = e.clone(); val unpack3 = item; TestSupport.equal(unpack3.seq.size, 3, "unpack arity"); c2("ln") = unpack3.at(V(0)); c2("c") = unpack3.at(V(1)); c2("k") = unpack3.at(V(2)); if (V((V((c2("kind").cmp("Is", V.none))).truth) || (V((c2("k").cmp("Eq", c2("kind")))).truth)).truth) Vector(vs(c2("ln"), c2("c"))) else Vector.empty })
V.none
}
private def h_NoteContent_content(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("line") = kw.getOrElse("line", args.lift(0).getOrElse(throw new IllegalArgumentException("missing line")))
e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\" by simp  ").plus(e("line")).plus(V("\nend\n"))), Map())
return h_entry(e, Vector(e("sec"), V("foo")), Map()).field("annotations").at(V(0)).at(V(1))
V.none
}
private def case_DefinitionBodies_test_notes_in_a_definition_body_attach(): Unit = TestSupport.test("test_annotations.DefinitionBodies.test_notes_in_a_definition_body_attach") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f x = (let a = x  ").plus(e("NOTE").invoke("format", Vector(V("1st round")), Map())).plus(V("\n          in a + 1)\"  ")).plus(e("NOTE").invoke("format", Vector(V("2nd round")), Map())).plus(V("\nend\n"))), Map())
TestSupport.equal(h_tagged(e, Vector(e("sec"), V("f")), Map()), vs(vs(V(3), V("1st round")), vs(V(4), V("2nd round"))), "tests/test_annotations.py:51")
}
private def case_DefinitionBodies_test_a_definitions_notes_are_tagged_statement(): Unit = TestSupport.test("test_annotations.DefinitionBodies.test_a_definitions_notes_are_tagged_statement") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f x = x\"  ").plus(e("NOTE").invoke("format", Vector(V("identity")), Map())).plus(V("\nend\n"))), Map())
TestSupport.equal(V.seq(h_entry(e, Vector(e("sec"), V("f")), Map()).field("annotations").seq.flatMap { item => val c4 = e.clone(); val unpack5 = item; TestSupport.equal(unpack5.seq.size, 3, "unpack arity"); c4("_") = unpack5.at(V(0)); c4("_") = unpack5.at(V(1)); c4("k") = unpack5.at(V(2)); if (true) Vector(c4("k")) else Vector.empty }), vs(V("statement")), "tests/test_annotations.py:61")
}
private def case_DefinitionBodies_test_a_definition_contributes_no_roadmap(): Unit = TestSupport.test("test_annotations.DefinitionBodies.test_a_definition_contributes_no_roadmap") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f x = x\"  ").plus(e("NOTE").invoke("format", Vector(V("identity")), Map())).plus(V("\nend\n"))), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("f")), Map()).field("roadmap"), vs(), "tests/test_annotations.py:71")
}
private def case_Statements_test_assumption_glosses_attach_as_statement(): Unit = TestSupport.test("test_annotations.Statements.test_assumption_glosses_attach_as_statement") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem foo:\n  assumes a: \"True\"  ").plus(e("NOTE").invoke("format", Vector(V("sound system")), Map())).plus(V("\n  assumes b: \"True\"  ")).plus(e("NOTE").invoke("format", Vector(V("and a plan")), Map())).plus(V("\n  shows \"True\"\n  by simp\nend\n"))), Map())
TestSupport.equal(h_tagged(e, Vector(e("sec"), V("foo"), V("statement")), Map()), vs(vs(V(3), V("sound system")), vs(V(4), V("and a plan"))), "tests/test_annotations.py:86")
}
private def case_Statements_test_a_statement_note_is_not_in_the_roadmap(): Unit = TestSupport.test("test_annotations.Statements.test_a_statement_note_is_not_in_the_roadmap") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem foo:\n  assumes a: \"True\"  ").plus(e("NOTE").invoke("format", Vector(V("sound system")), Map())).plus(V("\n  shows \"True\"\n  by simp\nend\n"))), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("roadmap"), vs(), "tests/test_annotations.py:96")
}
private def case_Proofs_test_a_note_in_the_proof_body_is_tagged_proof(): Unit = TestSupport.test("test_annotations.Proofs.test_a_note_in_the_proof_body_is_tagged_proof") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\"\nproof -\n  show \"True\" by simp  ").plus(e("NOTE").invoke("format", Vector(V("the work")), Map())).plus(V("\nqed\nend\n"))), Map())
TestSupport.equal(h_tagged(e, Vector(e("sec"), V("foo"), V("proof")), Map()), vs(vs(V(4), V("the work"))), "tests/test_annotations.py:109")
TestSupport.equal(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("roadmap"), vs(vs(V(4), V("the work"))), "tests/test_annotations.py:110")
}
private def case_Proofs_test_roadmap_is_exactly_the_proof_subset(): Unit = TestSupport.test("test_annotations.Proofs.test_roadmap_is_exactly_the_proof_subset") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem foo:\n  assumes a: \"True\"  ").plus(e("NOTE").invoke("format", Vector(V("what")), Map())).plus(V("\n  shows \"True\"\nproof -\n  show \"True\" by simp  ")).plus(e("NOTE").invoke("format", Vector(V("how")), Map())).plus(V("\nqed\nend\n"))), Map())
e("e") = h_entry(e, Vector(e("sec"), V("foo")), Map())
TestSupport.equal(V.seq(e("e").field("annotations").seq.flatMap { item => val c6 = e.clone(); val unpack7 = item; TestSupport.equal(unpack7.seq.size, 3, "unpack arity"); c6("_") = unpack7.at(V(0)); c6("_") = unpack7.at(V(1)); c6("k") = unpack7.at(V(2)); if (true) Vector(c6("k")) else Vector.empty }), vs(V("statement"), V("proof")), "tests/test_annotations.py:122")
TestSupport.equal(e("e").field("roadmap"), vs(vs(V(6), V("how"))), "tests/test_annotations.py:124")
}
private def case_Proofs_test_annotations_are_in_source_order(): Unit = TestSupport.test("test_annotations.Proofs.test_annotations_are_in_source_order") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem foo:  ").plus(e("NOTE").invoke("format", Vector(V("one")), Map())).plus(V("\n  shows \"True\"  ")).plus(e("NOTE").invoke("format", Vector(V("two")), Map())).plus(V("\n  by simp  ")).plus(e("NOTE").invoke("format", Vector(V("three")), Map())).plus(V("\nend\n"))), Map())
TestSupport.equal(V.seq(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("annotations").seq.flatMap { item => val c8 = e.clone(); val unpack9 = item; TestSupport.equal(unpack9.seq.size, 3, "unpack arity"); c8("_") = unpack9.at(V(0)); c8("c") = unpack9.at(V(1)); c8("_") = unpack9.at(V(2)); if (true) Vector(c8("c")) else Vector.empty }), vs(V("one"), V("two"), V("three")), "tests/test_annotations.py:132")
}
private def case_OneLinerOrdering_test_a_one_line_proof_note_is_a_proof_note(): Unit = TestSupport.test("test_annotations.OneLinerOrdering.test_a_one_line_proof_note_is_a_proof_note") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\" by simp  ").plus(e("NOTE").invoke("format", Vector(V("quick")), Map())).plus(V("\nend\n"))), Map())
e("e") = h_entry(e, Vector(e("sec"), V("foo")), Map())
TestSupport.equal(e("e").field("thy_line"), e("e").field("proof_line"), "tests/test_annotations.py:154")
TestSupport.equal(V.seq(e("e").field("annotations").seq.flatMap { item => val c10 = e.clone(); val unpack11 = item; TestSupport.equal(unpack11.seq.size, 3, "unpack arity"); c10("_") = unpack11.at(V(0)); c10("_") = unpack11.at(V(1)); c10("k") = unpack11.at(V(2)); if (true) Vector(c10("k")) else Vector.empty }), vs(V("proof")), "tests/test_annotations.py:155")
TestSupport.equal(e("e").field("roadmap"), vs(vs(V(2), V("quick"))), "tests/test_annotations.py:156")
}
private def case_OneLinerOrdering_test_a_decl_note_with_no_proof_is_a_decl_note(): Unit = TestSupport.test("test_annotations.OneLinerOrdering.test_a_decl_note_with_no_proof_is_a_decl_note") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntype_synonym hash = nat  ").plus(e("NOTE").invoke("format", Vector(V("hashes")), Map())).plus(V("\nend\n"))), Map())
e("e") = h_entry(e, Vector(e("sec"), V("hash")), Map())
TestSupport.equal(e("e").field("proof_line"), V(0), "tests/test_annotations.py:165")
TestSupport.equal(V.seq(e("e").field("annotations").seq.flatMap { item => val c12 = e.clone(); val unpack13 = item; TestSupport.equal(unpack13.seq.size, 3, "unpack arity"); c12("_") = unpack13.at(V(0)); c12("_") = unpack13.at(V(1)); c12("k") = unpack13.at(V(2)); if (true) Vector(c12("k")) else Vector.empty }), vs(V("decl")), "tests/test_annotations.py:166")
}
private def case_Unowned_test_a_note_above_the_first_declaration_attaches_to_nothing(): Unit = TestSupport.test("test_annotations.Unowned.test_a_note_above_the_first_declaration_attaches_to_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndeclare foo [simp]  ").plus(e("NOTE").invoke("format", Vector(V("theory-level")), Map())).plus(V("\nlemma bar: \"True\" by simp\nend\n"))), Map())
TestSupport.equal(h_entry(e, Vector(e("sec"), V("bar")), Map()).field("annotations"), vs(), "tests/test_annotations.py:179")
}
private def case_Unowned_test_a_note_in_a_commented_out_block_attaches_to_nothing(): Unit = TestSupport.test("test_annotations.Unowned.test_a_note_in_a_commented_out_block_attaches_to_nothing") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\nlemma foo: \"True\"\n  by simp\n(* an old attempt:\n   by auto  ").plus(e("NOTE").invoke("format", Vector(V("dead prose")), Map())).plus(V("\n*)\nend\n"))), Map())
TestSupport.equal(V.seq(h_entry(e, Vector(e("sec"), V("foo")), Map()).field("annotations").seq.flatMap { item => val c14 = e.clone(); val unpack15 = item; TestSupport.equal(unpack15.seq.size, 3, "unpack arity"); c14("_") = unpack15.at(V(0)); c14("c") = unpack15.at(V(1)); c14("_") = unpack15.at(V(2)); if (true) Vector(c14("c")) else Vector.empty }), vs(), "tests/test_annotations.py:195")
}
private def case_NoteContent_test_a_nested_cartouche_is_kept_whole(): Unit = TestSupport.test("test_annotations.NoteContent.test_a_nested_cartouche_is_kept_whole") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

TestSupport.equal(h_NoteContent_content(e, Vector(V("\\<comment> \\<open>We have that \\<open>f(as)\\<close> is applicable\\<close>")), Map()), V("We have that \\<open>f(as)\\<close> is applicable"), "tests/test_annotations.py:215")
}
private def case_NoteContent_test_a_flat_note_is_unchanged(): Unit = TestSupport.test("test_annotations.NoteContent.test_a_flat_note_is_unchanged") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

TestSupport.equal(h_NoteContent_content(e, Vector(e("NOTE").invoke("format", Vector(V("plain prose")), Map())), Map()), V("plain prose"), "tests/test_annotations.py:221")
}
private def case_NoteContent_test_two_nested_cartouches_in_a_row(): Unit = TestSupport.test("test_annotations.NoteContent.test_two_nested_cartouches_in_a_row") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

TestSupport.equal(h_NoteContent_content(e, Vector(V("\\<comment> \\<open>\\<open>a\\<close> and \\<open>b\\<close>\\<close>")), Map()), V("\\<open>a\\<close> and \\<open>b\\<close>"), "tests/test_annotations.py:225")
}
private def case_NoteContent_test_a_note_running_past_its_line_takes_the_rest(): Unit = TestSupport.test("test_annotations.NoteContent.test_a_note_running_past_its_line_takes_the_rest") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

TestSupport.equal(h_NoteContent_content(e, Vector(V("\\<comment> \\<open>starts here")), Map()), V("starts here"), "tests/test_annotations.py:232")
}
private def case_NoteContent_test_the_unicode_cartouche_spelling_is_extracted(): Unit = TestSupport.test("test_annotations.NoteContent.test_the_unicode_cartouche_spelling_is_extracted") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

TestSupport.equal(h_NoteContent_content(e, Vector(V("\\<comment> ‹hand-written›")), Map()), V("hand-written"), "tests/test_annotations.py:243")
}
private def case_ProseView_test_a_definition_has_a_prose_view_at_all(): Unit = TestSupport.test("test_annotations.ProseView.test_a_definition_has_a_prose_view_at_all") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f x = x\"  ").plus(e("NOTE").invoke("format", Vector(V("identity")), Map())).plus(V("\nend\n"))), Map())
e("out") = ParserBridge.call("cli.render_entry", Vector(e("sec"), h_entry(e, Vector(e("sec"), V("f")), Map())), Map("comments" -> V("only")))
TestSupport.check(e("out").has(V("identity")), "tests/test_annotations.py:258" + " actual=" + e("out"))
TestSupport.check(!e("out").has(V("no comment context")), "tests/test_annotations.py:259" + " actual=" + e("out"))
}
private def case_ProseView_test_kinds_are_grouped_and_labelled(): Unit = TestSupport.test("test_annotations.ProseView.test_kinds_are_grouped_and_labelled") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem foo:\n  assumes a: \"True\"  ").plus(e("NOTE").invoke("format", Vector(V("what")), Map())).plus(V("\n  shows \"True\"\nproof -\n  show \"True\" by simp  ")).plus(e("NOTE").invoke("format", Vector(V("how")), Map())).plus(V("\nqed\nend\n"))), Map())
e("out") = ParserBridge.call("cli.render_entry", Vector(e("sec"), h_entry(e, Vector(e("sec"), V("foo")), Map())), Map("comments" -> V("only")))
TestSupport.check(e("out").invoke("index", Vector(V("statement:")), Map()).cmp("Lt", e("out").invoke("index", Vector(V("what")), Map())), "tests/test_annotations.py:271" + " actual=" + e("out").invoke("index", Vector(V("statement:")), Map()) + " expected=" + e("out").invoke("index", Vector(V("what")), Map()))
TestSupport.check(e("out").invoke("index", Vector(V("what")), Map()).cmp("Lt", e("out").invoke("index", Vector(V("proof:")), Map())), "tests/test_annotations.py:272" + " actual=" + e("out").invoke("index", Vector(V("what")), Map()) + " expected=" + e("out").invoke("index", Vector(V("proof:")), Map()))
TestSupport.check(e("out").invoke("index", Vector(V("proof:")), Map()).cmp("Lt", e("out").invoke("index", Vector(V("how")), Map())), "tests/test_annotations.py:273" + " actual=" + e("out").invoke("index", Vector(V("proof:")), Map()) + " expected=" + e("out").invoke("index", Vector(V("how")), Map()))
}
private def case_ProseView_test_the_default_view_does_not_repeat_a_visible_note(): Unit = TestSupport.test("test_annotations.ProseView.test_the_default_view_does_not_repeat_a_visible_note") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f x = x\"  ").plus(e("NOTE").invoke("format", Vector(V("identity")), Map())).plus(V("\nend\n"))), Map())
e("out") = ParserBridge.call("cli.render_entry", Vector(e("sec"), h_entry(e, Vector(e("sec"), V("f")), Map())), Map("comments" -> V("on")))
TestSupport.equal(e("out").invoke("count", Vector(V("identity")), Map()), V(1), "tests/test_annotations.py:287")
}
private def case_ProseView_test_comments_off_still_suppresses_annotations(): Unit = TestSupport.test("test_annotations.ProseView.test_comments_off_still_suppresses_annotations") {
val e: Env = scala.collection.mutable.Map.empty
e("NOTE") = V("\\<comment> \\<open>{}\\<close>")

e("sec") = ParserBridge.call("section_from", Vector(V("theory A imports Main begin\ntheorem foo:\n  shows \"True\"\nproof -\n  show \"True\" by simp  ").plus(e("NOTE").invoke("format", Vector(V("how")), Map())).plus(V("\nqed\nend\n"))), Map())
e("out") = ParserBridge.call("cli.render_entry", Vector(e("sec"), h_entry(e, Vector(e("sec"), V("foo")), Map())), Map("comments" -> V("off")))
TestSupport.check(!e("out").has(V("| line")), "tests/test_annotations.py:298" + " actual=" + e("out"))
}
def run(): Unit = { case_DefinitionBodies_test_notes_in_a_definition_body_attach(); case_DefinitionBodies_test_a_definitions_notes_are_tagged_statement(); case_DefinitionBodies_test_a_definition_contributes_no_roadmap(); case_Statements_test_assumption_glosses_attach_as_statement(); case_Statements_test_a_statement_note_is_not_in_the_roadmap(); case_Proofs_test_a_note_in_the_proof_body_is_tagged_proof(); case_Proofs_test_roadmap_is_exactly_the_proof_subset(); case_Proofs_test_annotations_are_in_source_order(); case_OneLinerOrdering_test_a_one_line_proof_note_is_a_proof_note(); case_OneLinerOrdering_test_a_decl_note_with_no_proof_is_a_decl_note(); case_Unowned_test_a_note_above_the_first_declaration_attaches_to_nothing(); case_Unowned_test_a_note_in_a_commented_out_block_attaches_to_nothing(); case_NoteContent_test_a_nested_cartouche_is_kept_whole(); case_NoteContent_test_a_flat_note_is_unchanged(); case_NoteContent_test_two_nested_cartouches_in_a_row(); case_NoteContent_test_a_note_running_past_its_line_takes_the_rest(); case_NoteContent_test_the_unicode_cartouche_spelling_is_extracted(); case_ProseView_test_a_definition_has_a_prose_view_at_all(); case_ProseView_test_kinds_are_grouped_and_labelled(); case_ProseView_test_the_default_view_does_not_repeat_a_visible_note(); case_ProseView_test_comments_off_still_suppresses_annotations() }
}
