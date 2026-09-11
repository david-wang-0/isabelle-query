package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_names.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_names {
private def h_DefinitionalCommands_tags_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return ParserBridge.call("tags_by_name", Vector(ParserBridge.call("section_from", Vector(e("snippet")), Map())), Map())
V.none
}
private def h_ContinuationLineName_names_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return V.seq(ParserBridge.call("section_from", Vector(e("snippet")), Map()).field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty })
V.none
}
private def case_ParseName_test_bare_identifier(): Unit = TestSupport.test("test_names.ParseName.test_bare_identifier") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("foo: ...")), Map()), V("foo"), "tests/test_names.py:22")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("foo' : ...")), Map()), V("foo'"), "tests/test_names.py:23")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("foo_bar123 :: nat")), Map()), V("foo_bar123"), "tests/test_names.py:24")
}
private def case_ParseName_test_quoted_special_name(): Unit = TestSupport.test("test_names.ParseName.test_quoted_special_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"beta-C-cor:3\": ...")), Map()), V("beta-C-cor:3"), "tests/test_names.py:28")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"vdash-properties:1[1]\": ...")), Map()), V("vdash-properties:1[1]"), "tests/test_names.py:29")
}
private def case_ParseName_test_locale_prefix_is_skipped(): Unit = TestSupport.test("test_names.ParseName.test_locale_prefix_is_skipped") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("(in jozsa) jozsa_dim [simp]: ...")), Map()), V("jozsa_dim"), "tests/test_names.py:32")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("(in quantum_machine) no_cloning: ...")), Map()), V("no_cloning"), "tests/test_names.py:33")
}
private def case_ParseName_test_other_modifier_prefix(): Unit = TestSupport.test("test_names.ParseName.test_other_modifier_prefix") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("(nonexhaustive) Abs_hmultiset :: ...")), Map()), V("Abs_hmultiset"), "tests/test_names.py:36")
}
private def case_ParseName_test_nested_modifier_prefix(): Unit = TestSupport.test("test_names.ParseName.test_nested_modifier_prefix") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("(in foo (bar)) baz: ...")), Map()), V("baz"), "tests/test_names.py:40")
}
private def case_ParseName_test_symbolic_name(): Unit = TestSupport.test("test_names.ParseName.test_symbolic_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<psi>\\<^sub>1\\<^sub>0:: ...")), Map()), V("\\<psi>\\<^sub>1\\<^sub>0"), "tests/test_names.py:43")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<tau>rtrancl3p :: ...")), Map()), V("\\<tau>rtrancl3p"), "tests/test_names.py:44")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<alpha>ah :: ...")), Map()), V("\\<alpha>ah"), "tests/test_names.py:45")
}
private def case_ParseName_test_symbolic_suffix_not_truncated(): Unit = TestSupport.test("test_names.ParseName.test_symbolic_suffix_not_truncated") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("finally\\<^sub>n :: ...")), Map()), V("finally\\<^sub>n"), "tests/test_names.py:50")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("not\\<^sub>n where ...")), Map()), V("not\\<^sub>n"), "tests/test_names.py:51")
}
private def case_ParseName_test_anonymous_stays_unparsed(): Unit = TestSupport.test("test_names.ParseName.test_anonymous_stays_unparsed") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("[simp]: \"...\"")), Map()), V("?"), "tests/test_names.py:55")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V(": ...")), Map()), V("?"), "tests/test_names.py:56")
}
private def case_ParseName_test_reserved_control_symbols_are_not_names(): Unit = TestSupport.test("test_names.ParseName.test_reserved_control_symbols_are_not_names") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<comment> \\<open>a note\\<close>")), Map()), V("?"), "tests/test_names.py:63")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<open>P x \\<longrightarrow> Q x\\<close>")), Map()), V("?"), "tests/test_names.py:64")
}
private def case_ParseName_test_quoted_statement_is_not_a_name(): Unit = TestSupport.test("test_names.ParseName.test_quoted_statement_is_not_a_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"set xs = set ys\" by simp")), Map()), V("?"), "tests/test_names.py:70")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"f x \\<equiv> g x\" ")), Map()), V("?"), "tests/test_names.py:71")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"my_rule\": \"P\"")), Map()), V("my_rule"), "tests/test_names.py:72")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"my_rule\" [simp]: \"P\"")), Map()), V("my_rule"), "tests/test_names.py:73")
}
private def case_ParseName_test_bare_reserved_keyword_is_not_a_name(): Unit = TestSupport.test("test_names.ParseName.test_bare_reserved_keyword_is_not_a_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("assumes \"s \\<in> S\" and \"fair rs\"")), Map()), V("?"), "tests/test_names.py:79")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("by lexicographic_order")), Map()), V("?"), "tests/test_names.py:80")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("fixes p :: nat")), Map()), V("?"), "tests/test_names.py:81")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("shows negmax_maxmin: ...")), Map()), V("?"), "tests/test_names.py:82")
}
private def case_ParseName_test_quoted_reserved_keyword_is_a_name(): Unit = TestSupport.test("test_names.ParseName.test_quoted_reserved_keyword_is_a_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"for\" :: \"nat list\"")), Map()), V("for"), "tests/test_names.py:87")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"if\": \"P\"")), Map()), V("if"), "tests/test_names.py:88")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\"and\" :: \"fm\"")), Map()), V("and"), "tests/test_names.py:89")
}
private def case_ParseName_test_margin_comment_before_name_is_skipped(): Unit = TestSupport.test("test_names.ParseName.test_margin_comment_before_name_is_skipped") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<comment> \\<open>a note\\<close> bar :: \"nat\"")), Map()), V("bar"), "tests/test_names.py:94")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<comment> \\<open>see \\<open>X\\<close>\\<close> baz ::")), Map()), V("baz"), "tests/test_names.py:96")
TestSupport.equal(ParserBridge.call("cli._parse_name", Vector(V("\\<comment> \\<open>only a note\\<close>")), Map()), V("?"), "tests/test_names.py:98")
}
private def case_ParseDefName_test_explicit_label_still_wins(): Unit = TestSupport.test("test_names.ParseDefName.test_explicit_label_still_wins") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("foo :: nat where ...")), Map()), V("foo"), "tests/test_names.py:108")
TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("\"my_rule\": \"P\"")), Map()), V("my_rule"), "tests/test_names.py:109")
}
private def case_ParseDefName_test_implicit_lhs_head(): Unit = TestSupport.test("test_names.ParseDefName.test_implicit_lhs_head") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("\"language_ltlc \\<phi> \\<equiv> {\\<xi>. P}\"")), Map()), V("language_ltlc"), "tests/test_names.py:112")
TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("\"foo = 0\"")), Map()), V("foo"), "tests/test_names.py:114")
TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("\"pad m s = replicate m x @ s\"")), Map()), V("pad"), "tests/test_names.py:115")
}
private def case_ParseDefName_test_implicit_lhs_head_through_locale_prefix(): Unit = TestSupport.test("test_names.ParseDefName.test_implicit_lhs_head_through_locale_prefix") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("(in grp) \"e \\<equiv> 1\"")), Map()), V("e"), "tests/test_names.py:118")
}
private def case_ParseDefName_test_no_connective_is_not_a_definition(): Unit = TestSupport.test("test_names.ParseDefName.test_no_connective_is_not_a_definition") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("\"P x\" by simp")), Map()), V("?"), "tests/test_names.py:123")
TestSupport.equal(ParserBridge.call("cli._parse_def_name", Vector(V("\"\\<forall>x. Q x\"")), Map()), V("?"), "tests/test_names.py:124")
}
private def case_ParseTypedeclName_test_leading_type_variable(): Unit = TestSupport.test("test_names.ParseTypedeclName.test_leading_type_variable") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_typedecl_name", Vector(V("'a foo = ...")), Map()), V("foo"), "tests/test_names.py:129")
TestSupport.equal(ParserBridge.call("cli._parse_typedecl_name", Vector(V("('a, 'b) bar = ...")), Map()), V("bar"), "tests/test_names.py:130")
}
private def case_ParseTypedeclName_test_type_args_without_space(): Unit = TestSupport.test("test_names.ParseTypedeclName.test_type_args_without_space") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_typedecl_name", Vector(V("('si,'nsi)simple_state_impl = ...")), Map()), V("simple_state_impl"), "tests/test_names.py:133")
}
private def case_ParseTypedeclName_test_quoted_keyword_name(): Unit = TestSupport.test("test_names.ParseTypedeclName.test_quoted_keyword_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_typedecl_name", Vector(V("'a \"term\" = ...")), Map()), V("term"), "tests/test_names.py:137")
}
private def case_ParseTypedeclName_test_modifier_then_type_args(): Unit = TestSupport.test("test_names.ParseTypedeclName.test_modifier_then_type_args") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_typedecl_name", Vector(V("(discs_sels) ('a, 'b) fmla = ...")), Map()), V("fmla"), "tests/test_names.py:140")
}
private def case_ParseTypedeclName_test_symbolic_type_name(): Unit = TestSupport.test("test_names.ParseTypedeclName.test_symbolic_type_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

TestSupport.equal(ParserBridge.call("cli._parse_typedecl_name", Vector(V("\\<upsilon> = ...")), Map()), V("\\<upsilon>"), "tests/test_names.py:143")
}
private def case_ExtractEntriesEndToEnd_test_names_and_tags(): Unit = TestSupport.test("test_names.ExtractEntriesEndToEnd.test_names_and_tags") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")
e("SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET")), Map())
e("by_name") = ParserBridge.call("tags_by_name", Vector(e("sec")), Map())
TestSupport.equal(e("by_name").invoke("get", Vector(V("min_set")), Map()), V("DEF"), "tests/test_names.py:167")
TestSupport.equal(e("by_name").invoke("get", Vector(V("beta-C-cor:3")), Map()), V("THEOREM"), "tests/test_names.py:168")
TestSupport.equal(e("by_name").invoke("get", Vector(V("jozsa_dim")), Map()), V("LEMMA"), "tests/test_names.py:169")
TestSupport.equal(e("by_name").invoke("get", Vector(V("\\<psi>\\<^sub>1")), Map()), V("ABBREV"), "tests/test_names.py:170")
TestSupport.equal(e("by_name").invoke("get", Vector(V("term")), Map()), V("DATATYPE"), "tests/test_names.py:171")
}
private def case_ExtractEntriesEndToEnd_test_anonymous_lemma_is_the_only_question_mark(): Unit = TestSupport.test("test_names.ExtractEntriesEndToEnd.test_anonymous_lemma_is_the_only_question_mark") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")
e("SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("SNIPPET")), Map())
e("unparsed") = V.seq(e("sec").field("entries").seq.flatMap { item => val c2 = e.clone(); c2("e") = item; if (V((c2("e").field("name").cmp("Eq", V("?")))).truth) Vector(c2("e")) else Vector.empty })
TestSupport.equal(ParserBridge.call("len", Vector(e("unparsed")), Map()), V(1), "tests/test_names.py:177")
TestSupport.equal(e("unparsed").at(V(0)).field("tag"), V("LEMMA"), "tests/test_names.py:178")
}
private def case_DefinitionalCommands_test_function_indexes_its_constant(): Unit = TestSupport.test("test_names.DefinitionalCommands.test_function_indexes_its_constant") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\nfunction wrap_enc :: \"nat \\<Rightarrow> nat\" where\n  \"wrap_enc 0 = 0\"\n| \"wrap_enc (Suc n) = wrap_enc n\"\n  by pat_completeness auto\ntermination by lexicographic_order\nend\n")
TestSupport.equal(h_DefinitionalCommands_tags_of(e, Vector(e("snippet")), Map()).invoke("get", Vector(V("wrap_enc")), Map()), V("FUN"), "tests/test_names.py:210")
}
private def case_DefinitionalCommands_test_function_sequential_option_is_stripped(): Unit = TestSupport.test("test_names.DefinitionalCommands.test_function_sequential_option_is_stripped") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\nfunction (sequential) merge :: \"nat list \\<Rightarrow> nat list\" where\n  \"merge [] = []\"\n  by pat_completeness auto\nend\n")
TestSupport.equal(h_DefinitionalCommands_tags_of(e, Vector(e("snippet")), Map()).invoke("get", Vector(V("merge")), Map()), V("FUN"), "tests/test_names.py:219")
}
private def case_DefinitionalCommands_test_function_body_span_covers_the_termination_proof(): Unit = TestSupport.test("test_names.DefinitionalCommands.test_function_body_span_covers_the_termination_proof") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\nfunction f :: \"nat \\<Rightarrow> nat\" where\n  \"f 0 = 0\"\n  by pat_completeness auto\ntermination by lexicographic_order\n\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("snippet")), Map())
e("f") = ParserBridge.call("next", Vector(V.seq(e("sec").field("entries").seq.flatMap { item => val c3 = e.clone(); c3("e") = item; if (V((c3("e").field("name").cmp("Eq", V("f")))).truth) Vector(c3("e")) else Vector.empty })), Map())
TestSupport.equal(e("f").field("thy_line"), V(2), "tests/test_names.py:234")
TestSupport.equal(e("f").field("body_end_line"), V(5), "tests/test_names.py:235")
}
private def case_DefinitionalCommands_test_primrec_inductive_already_indexed(): Unit = TestSupport.test("test_names.DefinitionalCommands.test_primrec_inductive_already_indexed") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\nprimrec plen :: \"nat list \\<Rightarrow> nat\" where\n  \"plen [] = 0\"\ninductive even2 :: \"nat \\<Rightarrow> bool\" where\n  \"even2 0\"\ninductive_set Reach :: \"nat set\" where\n  \"0 \\<in> Reach\"\nend\n")
e("tags") = h_DefinitionalCommands_tags_of(e, Vector(e("snippet")), Map())
TestSupport.equal(e("tags").invoke("get", Vector(V("plen")), Map()), V("FUN"), "tests/test_names.py:250")
TestSupport.equal(e("tags").invoke("get", Vector(V("even2")), Map()), V("IND"), "tests/test_names.py:251")
TestSupport.equal(e("tags").invoke("get", Vector(V("Reach")), Map()), V("INDSET"), "tests/test_names.py:252")
}
private def case_ContinuationLineName_test_inductive_set_name_below(): Unit = TestSupport.test("test_names.ContinuationLineName.test_inductive_set_name_below") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ninductive_set\n  myset :: \"nat set\"\nwhere base: \"0 \\<in> myset\"\nend\n")
TestSupport.check(h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()).has(V("myset")), "tests/test_names.py:270" + " actual=" + h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()))
}
private def case_ContinuationLineName_test_definition_name_below(): Unit = TestSupport.test("test_names.ContinuationLineName.test_definition_name_below") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndefinition\n  foo :: \"nat\" where \"foo = 0\"\nend\n")
TestSupport.check(h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()).has(V("foo")), "tests/test_names.py:278" + " actual=" + h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()))
}
private def case_ContinuationLineName_test_datatype_name_below(): Unit = TestSupport.test("test_names.ContinuationLineName.test_datatype_name_below") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndatatype\n  'a tree = Leaf | Node \"'a tree\" 'a \"'a tree\"\nend\n")
TestSupport.check(h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()).has(V("tree")), "tests/test_names.py:287" + " actual=" + h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()))
}
private def case_ContinuationLineName_test_locale_prefix_alone_then_name_below(): Unit = TestSupport.test("test_names.ContinuationLineName.test_locale_prefix_alone_then_name_below") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndefinition (in ord)\n  min_set :: \"'a set\" where \"min_set = {}\"\nend\n")
TestSupport.check(h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()).has(V("min_set")), "tests/test_names.py:296" + " actual=" + h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()))
}
private def case_ContinuationLineName_test_blank_and_comment_lines_are_skipped(): Unit = TestSupport.test("test_names.ContinuationLineName.test_blank_and_comment_lines_are_skipped") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndefinition\n\n  \\<comment> \\<open>a note\\<close>\n  spaced :: \"nat\" where \"spaced = 0\"\nend\n")
TestSupport.check(h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()).has(V("spaced")), "tests/test_names.py:306" + " actual=" + h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()))
}
private def case_ContinuationLineName_test_following_command_means_no_name(): Unit = TestSupport.test("test_names.ContinuationLineName.test_following_command_means_no_name") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndefinition\nlemma foo: \"True\" by auto\nend\n")
e("names") = h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map())
TestSupport.check(!e("names").has(V("lemma")), "tests/test_names.py:317" + " actual=" + e("names"))
TestSupport.check(e("names").has(V("foo")), "tests/test_names.py:318" + " actual=" + e("names"))
}
private def case_ContinuationLineName_test_same_line_name_still_parses(): Unit = TestSupport.test("test_names.ContinuationLineName.test_same_line_name_still_parses") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndefinition bar :: \"nat\" where \"bar = 0\"\nend\n")
TestSupport.check(h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()).has(V("bar")), "tests/test_names.py:326" + " actual=" + h_ContinuationLineName_names_of(e, Vector(e("snippet")), Map()))
}
private def case_ContinuationLineName_test_lhs_head_name_through_lookahead(): Unit = TestSupport.test("test_names.ContinuationLineName.test_lhs_head_name_through_lookahead") {
val e: Env = scala.collection.mutable.Map.empty

e("ExtractEntriesEndToEnd.SNIPPET") = V("theory T imports Main begin\n\ndefinition (in ord) min_set :: \"'a set\" where \"min_set = {}\"\n\ntheorem \"beta-C-cor:3\": \"True\" by auto\n\nlemma (in jozsa) jozsa_dim [simp]: \"True\" by auto\n\nabbreviation \\<psi>\\<^sub>1 :: \"nat\" where \"\\<psi>\\<^sub>1 = 1\"\n\ndatatype 'a \"term\" = C 'a\n\nlemma [simp]: \"True\" by auto\n\nend\n")

e("snippet") = V("theory T imports Main begin\ndefinition\n  \"trans_rel \\<equiv> {(a, b). foo a b}\"\ntext\\<open>Final remark\\<close>\nend\n")
e("sec") = ParserBridge.call("section_from", Vector(e("snippet")), Map())
e("defs") = V.seq(e("sec").field("entries").seq.flatMap { item => val c4 = e.clone(); c4("e") = item; if (V((c4("e").field("tag").cmp("Eq", V("DEF")))).truth) Vector(c4("e")) else Vector.empty })
TestSupport.equal(ParserBridge.call("len", Vector(e("defs")), Map()), V(1), "tests/test_names.py:341")
TestSupport.equal(e("defs").at(V(0)).field("name"), V("trans_rel"), "tests/test_names.py:342")
}
def run(): Unit = { case_ParseName_test_bare_identifier(); case_ParseName_test_quoted_special_name(); case_ParseName_test_locale_prefix_is_skipped(); case_ParseName_test_other_modifier_prefix(); case_ParseName_test_nested_modifier_prefix(); case_ParseName_test_symbolic_name(); case_ParseName_test_symbolic_suffix_not_truncated(); case_ParseName_test_anonymous_stays_unparsed(); case_ParseName_test_reserved_control_symbols_are_not_names(); case_ParseName_test_quoted_statement_is_not_a_name(); case_ParseName_test_bare_reserved_keyword_is_not_a_name(); case_ParseName_test_quoted_reserved_keyword_is_a_name(); case_ParseName_test_margin_comment_before_name_is_skipped(); case_ParseDefName_test_explicit_label_still_wins(); case_ParseDefName_test_implicit_lhs_head(); case_ParseDefName_test_implicit_lhs_head_through_locale_prefix(); case_ParseDefName_test_no_connective_is_not_a_definition(); case_ParseTypedeclName_test_leading_type_variable(); case_ParseTypedeclName_test_type_args_without_space(); case_ParseTypedeclName_test_quoted_keyword_name(); case_ParseTypedeclName_test_modifier_then_type_args(); case_ParseTypedeclName_test_symbolic_type_name(); case_ExtractEntriesEndToEnd_test_names_and_tags(); case_ExtractEntriesEndToEnd_test_anonymous_lemma_is_the_only_question_mark(); case_DefinitionalCommands_test_function_indexes_its_constant(); case_DefinitionalCommands_test_function_sequential_option_is_stripped(); case_DefinitionalCommands_test_function_body_span_covers_the_termination_proof(); case_DefinitionalCommands_test_primrec_inductive_already_indexed(); case_ContinuationLineName_test_inductive_set_name_below(); case_ContinuationLineName_test_definition_name_below(); case_ContinuationLineName_test_datatype_name_below(); case_ContinuationLineName_test_locale_prefix_alone_then_name_below(); case_ContinuationLineName_test_blank_and_comment_lines_are_skipped(); case_ContinuationLineName_test_following_command_means_no_name(); case_ContinuationLineName_test_same_line_name_still_parses(); case_ContinuationLineName_test_lhs_head_name_through_lookahead() }
}
