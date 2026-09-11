package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_keywords.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_keywords {
private def h_names_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return V.seq(ParserBridge.call("section_from", Vector(e("snippet")), Map()).field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty })
V.none
}
private def h_entries_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return V.seq(ParserBridge.call("section_from", Vector(e("snippet")), Map()).field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (true) Vector(vs(c2("e").field("tag"), c2("e").field("name"))) else Vector.empty })
V.none
}
private def h_ScanKeywordBlock__scan(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("block") = kw.getOrElse("block", args.lift(0).getOrElse(throw new IllegalArgumentException("missing block")))
e("hdr") = V("theory T imports Main\n  ").plus(e("block")).plus(V("\nbegin")).invoke("splitlines", Vector(), Map())
return ParserBridge.call("cli.scan_keywords", Vector(e("hdr")), Map())
V.none
}
private def case_ScanKeywordBlock_test_kind_to_family(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_kind_to_family") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"g\" :: thy_goal")), Map()), vm((V("g"), V("THEOREM"))), "tests/test_keywords.py:46")
TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"s\" :: thy_goal_stmt")), Map()), vm((V("s"), V("THEOREM"))), "tests/test_keywords.py:47")
TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"d\" :: thy_defn")), Map()), vm((V("d"), V("DEF"))), "tests/test_keywords.py:48")
TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"c\" :: thy_decl")), Map()), vm((V("c"), V("DEF"))), "tests/test_keywords.py:49")
TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"b\" :: thy_decl_block")), Map()), vm((V("b"), V("DEF"))), "tests/test_keywords.py:50")
}
private def case_ScanKeywordBlock_test_non_theory_kinds_are_skipped(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_non_theory_kinds_are_skipped") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

vs(V("prf_decl"), V("prf_goal"), V("diag"), V("document_body"), V("quasi_command"), V("thy_load"), V("qed_block")).seq.foreach { item => e("kind") = item;
TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"x\" :: " + e("kind").str)), Map()), vm(), V(e("kind").str + " should create no command").str)
}
}
private def case_ScanKeywordBlock_test_multiple_names_share_one_kind(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_multiple_names_share_one_kind") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"a\" \"b\" \"c\" :: thy_decl")), Map()), vm((V("a"), V("DEF")), (V("b"), V("DEF")), (V("c"), V("DEF"))), "tests/test_keywords.py:60")
}
private def case_ScanKeywordBlock_test_and_separated_decls(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_and_separated_decls") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("got") = h_ScanKeywordBlock__scan(e, Vector(V("keywords \"named_simpset\" :: thy_decl and \"print_named_simpset\" :: diag")), Map())
TestSupport.equal(e("got"), vm((V("named_simpset"), V("DEF"))), "tests/test_keywords.py:66")
}
private def case_ScanKeywordBlock_test_kindless_minor_keywords_skipped(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_kindless_minor_keywords_skipped") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("got") = h_ScanKeywordBlock__scan(e, Vector(V("keywords \"ensures\"\n     and \"returns\" \"variant\"\n     and \"program_spec\" :: thy_goal")), Map())
TestSupport.equal(e("got"), vm((V("program_spec"), V("THEOREM"))), "tests/test_keywords.py:72")
}
private def case_ScanKeywordBlock_test_tag_value_is_not_a_name(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_tag_value_is_not_a_name") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"@qed\" :: prf_block % \"proof\"")), Map()), vm(), "tests/test_keywords.py:76")
TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"g\" :: thy_goal % \"proof\"")), Map()), vm((V("g"), V("THEOREM"))), "tests/test_keywords.py:77")
}
private def case_ScanKeywordBlock_test_glued_colon_kind(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_glued_colon_kind") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

TestSupport.equal(h_ScanKeywordBlock__scan(e, Vector(V("keywords \"named_rules\"::thy_decl")), Map()), vm((V("named_rules"), V("DEF"))), "tests/test_keywords.py:81")
}
private def case_ScanKeywordBlock_test_no_keywords_clause(): Unit = TestSupport.test("test_keywords.ScanKeywordBlock.test_no_keywords_clause") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

TestSupport.equal(ParserBridge.call("cli.scan_keywords", Vector(V("theory T imports Main begin").invoke("splitlines", Vector(), Map())), Map()), vm(), "tests/test_keywords.py:85")
}
private def case_CustomCommandEntries_test_goal_command_quoted_label(): Unit = TestSupport.test("test_keywords.CustomCommandEntries.test_goal_command_quoted_label") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("snippet") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\nAOT_theorem \"foo:1\": \\<open>p \\<rightarrow> p\\<close>\n  by simp\nend\n")
TestSupport.equal(h_entries_of(e, Vector(e("snippet")), Map()), vs(vs(V("THEOREM"), V("foo:1"))), "tests/test_keywords.py:99")
}
private def case_CustomCommandEntries_test_decl_command_bare_name(): Unit = TestSupport.test("test_keywords.CustomCommandEntries.test_decl_command_bare_name") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("snippet") = V("theory T imports Main\n  keywords \"AOT_define\" :: thy_decl\nbegin\nAOT_define Bar :: \\<open>nat\\<close> (\\<open>B\\<close>)\nend\n")
TestSupport.equal(h_entries_of(e, Vector(e("snippet")), Map()), vs(vs(V("DEF"), V("Bar"))), "tests/test_keywords.py:108")
}
private def case_CustomCommandEntries_test_proof_command_makes_no_entry(): Unit = TestSupport.test("test_keywords.CustomCommandEntries.test_proof_command_makes_no_entry") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("snippet") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal and \"AOT_show\" :: prf_asm_goal % \"proof\"\nbegin\nAOT_theorem \"t:1\": \\<open>p\\<close>\nAOT_show \\<open>p\\<close> by simp\nend\n")
TestSupport.equal(h_names_of(e, Vector(e("snippet")), Map()), vs(V("t:1")), "tests/test_keywords.py:119")
}
private def case_CrossTheoryUnion_test_use_without_local_declaration(): Unit = TestSupport.test("test_keywords.CrossTheoryUnion.test_use_without_local_declaration") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("snippet") = V("theory T imports Main begin\nAOT_theorem \"bar:2\": \\<open>q\\<close> by simp\nend\n")
e("ents") = ParserBridge.call("cli.extract_entries", Vector(e("snippet").invoke("splitlines", Vector(), Map())), Map("custom" -> vm((V("AOT_theorem"), V("THEOREM")))))
TestSupport.equal(V.seq(e("ents").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (true) Vector(vs(c3("e").field("tag"), c3("e").field("name"))) else Vector.empty }), vs(vs(V("THEOREM"), V("bar:2"))), "tests/test_keywords.py:134")
}
private def case_CrossTheoryUnion_test_unknown_command_stays_unrecognised(): Unit = TestSupport.test("test_keywords.CrossTheoryUnion.test_unknown_command_stays_unrecognised") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")

e("snippet") = V("theory T imports Main begin\nAOT_theorem \"bar:2\": \\<open>q\\<close> by simp\nend\n")
TestSupport.equal(ParserBridge.call("cli.extract_entries", Vector(e("snippet").invoke("splitlines", Vector(), Map())), Map()), vs(), "tests/test_keywords.py:142")
}
private def case_SpanCollapse_test_builtin_span_is_bounded_by_next_custom_command(): Unit = TestSupport.test("test_keywords.SpanCollapse.test_builtin_span_is_bounded_by_next_custom_command") {
val e: Env = scala.collection.mutable.Map.empty

e("SpanCollapse.SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")
e("SNIPPET") = V("theory T imports Main\n  keywords \"AOT_theorem\" :: thy_goal\nbegin\ntheorem first: \"True\" by simp\n\nAOT_theorem \"a:1\": \\<open>X\\<close> by simp\n\nAOT_theorem \"a:2\": \\<open>Y\\<close> by simp\n\nAOT_theorem \"a:3\": \\<open>Z\\<close> by simp\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET")), Map())
e("by_name") = V.dict(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (true) Vector((c4("e").field("name"), c4("e"))) else Vector.empty })
TestSupport.equal(ParserBridge.call("set", Vector(e("by_name")), Map()), vset(V("first"), V("a:1"), V("a:2"), V("a:3")), "tests/test_keywords.py:165")
e("first") = e("by_name").at(V("first"))
TestSupport.check(e("first").field("thy_end").cmp("Lt", e("by_name").at(V("a:1")).field("thy_line")), "tests/test_keywords.py:168" + " actual=" + e("first").field("thy_end") + " expected=" + e("by_name").at(V("a:1")).field("thy_line"))
TestSupport.check(e("first").field("thy_end").minus(e("first").field("thy_line")).cmp("LtE", V(3)), "tests/test_keywords.py:169" + " actual=" + e("first").field("thy_end").minus(e("first").field("thy_line")) + " expected=" + V(3))
}
def run(): Unit = { case_ScanKeywordBlock_test_kind_to_family(); case_ScanKeywordBlock_test_non_theory_kinds_are_skipped(); case_ScanKeywordBlock_test_multiple_names_share_one_kind(); case_ScanKeywordBlock_test_and_separated_decls(); case_ScanKeywordBlock_test_kindless_minor_keywords_skipped(); case_ScanKeywordBlock_test_tag_value_is_not_a_name(); case_ScanKeywordBlock_test_glued_colon_kind(); case_ScanKeywordBlock_test_no_keywords_clause(); case_CustomCommandEntries_test_goal_command_quoted_label(); case_CustomCommandEntries_test_decl_command_bare_name(); case_CustomCommandEntries_test_proof_command_makes_no_entry(); case_CrossTheoryUnion_test_use_without_local_declaration(); case_CrossTheoryUnion_test_unknown_command_stays_unrecognised(); case_SpanCollapse_test_builtin_span_is_bounded_by_next_custom_command() }
}
