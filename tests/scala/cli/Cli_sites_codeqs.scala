package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import CliSupport.*

/** Upstream `tests/test_sites_codeqs.py` (0.9.2): `codeqs`, the declared
  * code-equation sites of a constant [code-equations] [code-heads]
  * [code-clauses].  The fixture theories are the upstream bytes; every
  * expectation is the upstream hand-computed one, line numbers included. */
private[regression] object Cli_sites_codeqs {
  val CODE_FIX: String = "theory Code_Fix\n  imports Main\nbegin\n\nfun fib :: \"nat \\<Rightarrow> nat\" where\n  \"fib 0 = 0\"\n| \"fib (Suc 0) = 1\"\n| \"fib (Suc (Suc n)) = fib n + fib (Suc n)\"\n\ndefinition twice :: \"nat \\<Rightarrow> nat\" where\n  \"twice n = n + n\"\n\ndefinition thrice :: \"nat \\<Rightarrow> nat\" where [code]: \"thrice n = n + n + n\"\n\ndefinition half :: \"nat \\<Rightarrow> nat\" where\n  \"half n = n div 2\"\n\ndefinition x\\<^sub>1 :: nat where\n  \"x\\<^sub>1 = 1\"\n\ndatatype mytree = Leaf | Node mytree mytree\n\nlemma twice_alt [code]: \"twice n = 2 * n\"\n  by (simp add: twice_def)\n\nlemma not_a_code_eq [code_unfold]: \"twice n = n + n\"\n  by (simp add: twice_def)\n\nlemma mentions_only [code]: \"thrice n = twice n + n\"\n  by (simp add: twice_def thrice_def)\n\nlemma cond_code [code]: \"n > 0 \\<Longrightarrow> half n = n div 2\"\n  by (simp add: half_def)\n\nlemma x1_code [code]: \"x\\<^sub>1 = 1\"\n  by (simp add: x\\<^sub>1_def)\n\nlemma node_code [code]: \"Node l r = Node l r\"\n  by simp\n\ndeclare fib.simps [code del]\n\nlemmas twice_lemmas [code] = twice_alt\n\ndeclare [[code drop: twice]]\n\nlemma decoy_code: \"True\"\n  (* declare twice_def [code] *)\n  by simp\n\n\\<^cancel>\\<open>lemma cancelled_code [code]: \"twice n = 0\"\\<close>\n\nend\n"
  val NESTED_FIX: String = "theory Nested_Fix\n  imports Code_Fix\nbegin\n\ndefinition quad :: \"nat \\<Rightarrow> nat\" where\n  \"quad n = twice (twice n)\"\n\nlemma quad_code [code]: \"quad n = 4 * n\"\n  by (simp add: quad_def twice_def)\n\nend\n"
  val DEEPER_FIX: String = "theory Deeper_Fix\n  imports Nested_Fix\nbegin\n\ndeclare quad_code [code del]\n\nend\n"
  val ROOT: String = "session Fix = HOL +\n  theories\n    Code_Fix\n\nsession Fix_Nested in Nested = Fix +\n  theories\n    Nested_Fix\n\nsession Fix_Deep in \"Deep/Down\" = Fix_Nested +\n  theories\n    Deeper_Fix\n"
  val ALPHA: String = "theory Examples\nimports Main\nbegin\n\nlocale L =\n  fixes g :: \"'a \\<Rightarrow> 'a\"\n\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f n = n + 1\"\n\ninterpretation a_inst: L id ..\n\nlemma a_eq [code]: \"f n = Suc n\"\n  by (simp add: f_def)\n\nend\n"
  val BETA: String = "theory Examples\nimports Main\nbegin\n\nlocale L =\n  fixes g :: \"'a \\<Rightarrow> 'a\"\n\ndefinition f :: \"nat \\<Rightarrow> nat\" where\n  \"f n = n + 2\"\n\nlemma b_pad: \"True\" by simp\n\nlemma b_pad2: \"True\" by simp\n\ninterpretation b_inst: L id ..\n\nlemma b_eq [code]: \"f n = Suc (Suc n)\"\n  by (simp add: f_def)\n\nend\n"
  val MIXFIX_FIX: String = "theory Mixfix_Fix\n  imports Main\nbegin\n\ndefinition set :: \"nat \\<Rightarrow> nat set\" where \"set n = {n}\"\n\ndefinition A :: \"nat set\" where \"A = {}\"\n\nlemma minus_fold [code]: \"A - set xs = fold Set.remove xs A\"\n  sorry\n\nlemma insert_code [code]: \"insert x (set n) = set x \\<union> set n\"\n  sorry\n\nlemma set_abs [code abstract]: \"Rep_T (mk n) = set n\"\n  sorry\n\ndefinition mk :: \"nat \\<Rightarrow> nat\" where \"mk n = n\"\n\ndefinition twin :: \"nat \\<Rightarrow> nat\" where \"twin n = n\"\n\nlemma twin_a [code]: \"mk n = n\"\n  and twin_b [code]: \"twin n = n\"\n  sorry\n\nlemma prem [code]:\n  assumes \"mk 0 = 0\" and p: \"True\"\n  shows \"twin n = n\"\n  sorry\n\nend\n"

  def attrs(text: String): List[Sites.Code_Attr] = Sites.code_attrs(text, blank_terms(text))
  def wt(text: String, name: String): String = Sites.written_type(text, blank_terms(text), name)

  def fixture(body: List[Theory_Section] => Unit): Unit =
    withProject(Seq("Code_Fix.thy" -> CODE_FIX, "Nested/Nested_Fix.thy" -> NESTED_FIX,
      "Deep/Down/Deeper_Fix.thy" -> DEEPER_FIX, "ROOT" -> ROOT)) { root => body(load(root)) }
  def collision(body: (java.nio.file.Path, List[Theory_Section]) => Unit): Unit =
    withProject(List("alpha" -> ALPHA, "beta" -> BETA).flatMap { case (sub, text) =>
      List(s"$sub/Examples.thy" -> text,
        s"$sub/ROOT" -> s"session ${sub.capitalize} = HOL +\n  theories\n    Examples\n")
    }) { root => body(root, load(root)) }
  def mixfix(body: List[Theory_Section] => Unit): Unit =
    withProject(Seq("Mixfix_Fix.thy" -> MIXFIX_FIX,
      "ROOT" -> "session Fix = HOL +\n  theories\n    Mixfix_Fix\n")) { root => body(load(root)) }

  def code(ss: List[Theory_Section], name: String): List[Sites.Site] =
    Sites.find_code_equations(ss, name)
  def loci(found: List[Sites.Site]): List[String] = found.map(s => s"${s.theory}:${s.line}")
  def codeqs(ss: List[Theory_Section], name: String, flags: Flags = Flags()): String =
    capture((o, e) => Sites.cmd_codeqs(o, e, ss, name, flags)).out
  def instances(ss: List[Theory_Section], name: String, flags: Flags = Flags()): String =
    capture((o, e) => Sites.cmd_instances(o, e, ss, name, flags)).out
  def unresolved(ss: List[Theory_Section], name: String, flags: Flags = Flags()): String = {
    val r = capture((o, e) => Sites.cmd_codeqs(o, e, ss, name, flags))
    equal(r.exit, 1, "exit")
    equal(r.out, "", "stdout")
    r.err
  }
  def resolve(ss: List[Theory_Section], name: String): Either[String, Sites.Subject] =
    Sites.resolve(ss, name, Sites.constant_tags, "a constant")
  def split(line: String): List[String] = line.trim.split("\\s+").toList

  def run(): Unit = {
    /* -- TheGrammars ------------------------------------------------------ */
    test("test_sites_codeqs.TheGrammars.test_code_attrs") {
      List[(String, List[String])](
        ("lemma foo [code]: ", List("code")),
        ("lemma foo [code equation]: ", List("code equation")),
        ("lemma foo [code prepend]: ", List("code prepend")),
        ("lemma foo [code nbe]: ", List("code nbe")),
        ("lemma foo [code abstract]: ", List("code abstract")),
        ("lemma foo [code abstype]: ", List("code abstype")),
        ("declare f.simps [code del]", List("code del")),
        ("declare [[code drop: f]]", List("code drop")),
        ("declare [[code abort: f]]", List("code abort")),
        // The preprocessor's attributes, and the predicate compiler's, are a
        // different store: the token boundary keeps them out.
        ("lemma foo [code_unfold]: ", Nil),
        ("lemma foo [code_abbrev]: ", Nil),
        ("lemma foo [code_post]: ", Nil),
        ("declare i [code_pred_intro]", Nil),
        ("lemma foo [simp, code, code_unfold]: ", List("code")),
        // A `[` inside a term opens nothing.
        ("lemma foo: \"f [x] = y\"", Nil)
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(attrs(text).map(_.spelling), want) } }
    }
    test("test_sites_codeqs.TheGrammars.test_the_config_form") {
      equal(attrs("declare [[code drop: f]]").map(a => (a.spelling, a.config)), List(("code drop", true)))
      equal(attrs("declare f [code del]").map(_.config), List(false))
    }
    test("test_sites_codeqs.TheGrammars.test_dropped_constants") {
      val text = "declare [[code drop: \"open :: real set \\<Rightarrow> bool\" g]]"
      val got = attrs(text)
      equal(got.length, 1)
      equal(Sites.dropped_constants(text, got.head), List("open", "g"))
    }
    test("test_sites_codeqs.TheGrammars.test_equation_heads") {
      List[(String, List[String])](
        (" \"twice n = 2 * n\" ", List("twice")),
        (" \"thrice n = twice n + n\" ", List("thrice")),
        (" \"n > 0 \\<Longrightarrow> half n = n div 2\" ", List("half")),
        // A plain equation's head is its first token only: what opens a
        // parenthesised argument is a pattern or an argument.
        (" \"rep_pos (mk n) = max 1 n\" ", List("rep_pos")),
        (" \"ntrancl (Suc n) r = r\" ", List("ntrancl")),
        (" \"insert x (set xs) = set (x # xs)\" ", List("insert")),
        // Read as one qualified name, not cut at the dot.
        (" \"HOL.equal n (n::natural) \\<longleftrightarrow> True\" ", List("HOL.equal")),
        // Mixfix hides the head: an operator after the first token, or a
        // bracketed operand first, is no head at all -- never the operand
        // (`A` here is `minus_set_fold`'s left operand of `-`).
        (" \"A - set xs = fold Set.remove xs A\" ", Nil),
        (" \"xs @ ys = foldr Cons xs ys\" ", Nil),
        (" \"A \\<union> B = C\" ", Nil),
        (" \"(A - B) = C\" ", Nil),
        // ... or after the first token's arguments: application binds tighter
        // than any infix (HOL's `union_code`).
        (" \"set xs \\<union> A = fold insert xs A\" ", Nil),
        // A letter symbol is an argument, not an operator.
        (" \"f \\<alpha> = 0\" ", List("f")),
        (" \"f x \\<equiv> g x\" ", List("f")),
        (" \"p x \\<longleftrightarrow> q x\" ", List("p")),
        (" \\<open>f x = y\\<close> ", List("f")),
        (" \"f 0 = a\" \"g n = b\" ", List("f", "g")),
        (" assumes \"p x = y\" shows \"f x = z\" ", List("f")),
        (" \"x\\<^sub>1 = 1\" ", List("x\\<^sub>1"))
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(Sites.equation_heads(text), want) } }
    }
    test("test_sites_codeqs.TheGrammars.test_abstract_equation_heads") {
      // `[code abstract]`: `Rep_T (f x) = ...` is an equation of `f`, the head
      // of the projection's argument -- `rat_sgn_code` in HOL's Rat.
      List[(String, List[String])](
        (" \"rep_pos (mk n) = max 1 n\" ", List("mk")),
        (" \"quotient_of (sgn p) = (sgn (fst (quotient_of p)), 1)\" ", List("sgn")),
        (" \"Rep_T c = 0\" ", List("c")),
        (" \"Rep_T (a + b) = 0\" ", Nil)
      ).foreach { case (text, want) =>
        subcase(s"text=$text") { equal(Sites.equation_heads(text, abstract_eq = true), want) }
      }
    }
    test("test_sites_codeqs.TheGrammars.test_written_type") {
      List[(String, String, String)](
        ("definition twice :: \"nat \\<Rightarrow> nat\" where", "twice", "nat \\<Rightarrow> nat"),
        ("definition null_nat :: nat where", "null_nat", "nat"),
        // A `::` inside a term is not the declaration's own.
        ("lemma foo: \"f :: nat \\<Rightarrow> bool\" ", "f", ""),
        ("lemma twice_alt [code]: \"twice n = 2 * n\" ", "twice_alt", "")
      ).foreach { case (text, name, want) => subcase(s"text=$text") { equal(wt(text, name), want) } }
    }
    test("test_sites_codeqs.TheGrammars.test_cited_fact_names") {
      equal(Sites.cited_fact_names(blank_terms("lemmas foo [code] = bar baz"), 6),
        List("foo", "bar", "baz"))
      equal(Sites.cited_fact_names(blank_terms("declare fib.simps [code del]"), 7),
        List("fib.simps"))
    }

    /* -- TheEngineOnTheFixture -------------------------------------------- */
    test("test_sites_codeqs.TheEngineOnTheFixture.test_the_fixture_loaded_whole") {
      fixture { ss => equal(ss.map(_.theory), List("Code_Fix", "Nested_Fix", "Deeper_Fix")) }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_twice") {
      fixture { ss =>
        val found = code(ss, "twice")
        equal(loci(found), List("Code_Fix:10", "Code_Fix:23", "Code_Fix:43", "Code_Fix:45"))
        equal(found.map(_.kind), List("default", "[code]", "[code]", "[code drop]"))
        equal(found.map(_.name), List("twice", "twice_alt", "twice_lemmas", "twice"))
        // Only the definition writes a signature.
        equal(found.map(_.label(true)),
          List("twice :: nat \\<Rightarrow> nat", "twice_alt", "twice_lemmas", "twice"))
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_a_right_hand_side_is_not_a_site") {
      // `mentions_only [code]: "thrice n = twice n + n"` (29) is an equation of thrice.
      fixture { ss =>
        check(!code(ss, "twice").map(_.line).contains(29))
        check(code(ss, "thrice").map(_.line).contains(29))
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_the_preprocessor_attribute_is_not_a_site") {
      fixture { ss => check(!code(ss, "twice").map(_.line).contains(26)) }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_the_decoys_are_not_sites") {
      fixture { ss =>
        val lines = code(ss, "twice").map(_.line)
        check(!lines.contains(48))   // `(* declare twice_def [code] *)`
        check(!lines.contains(51))   // `\<^cancel>\<open>lemma ... [code]`
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_fib") {
      fixture { ss =>
        val found = code(ss, "fib")
        equal(loci(found), List("Code_Fix:5", "Code_Fix:41"))
        equal(found.map(_.kind), List("default", "[code del]"))
        equal(found.map(_.name), List("fib", "fib.simps"))
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_a_declaration_with_an_attribute_has_no_default_row") {
      fixture { ss =>
        val found = code(ss, "thrice")
        equal(loci(found), List("Code_Fix:13", "Code_Fix:29"))
        equal(found.map(_.kind), List("[code]", "[code]"))
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_a_conditional_equation") {
      fixture { ss =>
        val found = code(ss, "half")
        equal(loci(found), List("Code_Fix:15", "Code_Fix:32"))
        equal(found.map(_.name), List("half", "cond_code"))
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_a_symbol_bearing_constant") {
      fixture { ss => equal(loci(code(ss, "x\\<^sub>1")), List("Code_Fix:18", "Code_Fix:35")) }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_a_constructor") {
      fixture { ss =>
        equal(loci(code(ss, "Node")), List("Code_Fix:38"))
        // A datatype registers constructors, not equations.
        equal(code(ss, "Leaf"), Nil)
      }
    }
    test("test_sites_codeqs.TheEngineOnTheFixture.test_registered_here_and_retracted_there") {
      fixture { ss =>
        val found = code(ss, "quad")
        equal(loci(found), List("Nested_Fix:5", "Nested_Fix:8", "Deeper_Fix:5"))
        equal(found.map(_.kind), List("default", "[code]", "[code del]"))
        equal(found.map(_.name), List("quad", "quad_code", "quad_code"))
      }
    }

    /* -- SubjectResolution -------------------------------------------------- */
    test("test_sites_codeqs.SubjectResolution.test_a_lemma_is_not_a_constant") {
      fixture { ss =>
        resolve(ss, "twice_alt") match {
          case Left(got) => contains(got, "LEMMA"); contains(got, "Code_Fix")
          case Right(s) => check(false, s"resolved: $s")
        }
      }
    }
    test("test_sites_codeqs.SubjectResolution.test_a_constructor_resolves_through_its_binder") {
      fixture { ss =>
        val got = resolve(ss, "Leaf")
        equal(got.map(_.how), Right("a constructor of mytree"))
        equal(got.map(_.name), Right("Leaf"))
        equal(got.map(_.tag), Right("DATATYPE"))
      }
    }
    test("test_sites_codeqs.SubjectResolution.test_a_primrec_is_a_constant") {
      fixture { ss => equal(resolve(ss, "fib").map(_.tag), Right("FUN")) }
    }

    /* -- TheCommand --------------------------------------------------------- */
    test("test_sites_codeqs.TheCommand.test_counts") {
      fixture { ss =>
        List("twice" -> "4\n", "fib" -> "2\n", "quad" -> "3\n", "Leaf" -> "0\n").foreach {
          case (name, want) => subcase(s"name=$name") {
            val got = codeqs(ss, name, Flags(mode = "count"))
            check(got.endsWith(want), got)
          }
        }
      }
    }
    test("test_sites_codeqs.TheCommand.test_names") {
      fixture { ss =>
        equal(codeqs(ss, "quad", Flags(mode = "names")), "Nested_Fix:5\nNested_Fix:8\nDeeper_Fix:5\n")
      }
    }
    test("test_sites_codeqs.TheCommand.test_the_table") {
      fixture { ss =>
        equal(codeqs(ss, "fib"), "2 code equation(s) of fib:\n\n  Code_Fix:5   fib        default     fun fib :: \"nat \\<Rightarrow> nat\" where\n  Code_Fix:41  fib.simps  [code del]  declare fib.simps [code del]\n")
      }
    }
    test("test_sites_codeqs.TheCommand.test_sorts_only_where_a_signature_is_written") {
      fixture { ss =>
        val rows = lines(codeqs(ss, "twice", Flags(sorts = true))).drop(2)
        equal(rows.length, 4)
        // The NAME cell, not the whole row: the source cell of the definition
        // row writes a `::` of its own.
        val cells = rows.map(r => r.trim.split("\\s{2,}")(1))
        equal(cells, List("twice :: nat \\<Rightarrow> nat", "twice_alt", "twice_lemmas", "twice"))
      }
    }
    test("test_sites_codeqs.TheCommand.test_a_known_constant_with_no_equations") {
      // The binding note first, in every mode, then the honest zero.
      fixture { ss =>
        equal(codeqs(ss, "Leaf"), "# 'Leaf' is a constructor of mytree.\nNo code equations found for 'Leaf'.\n")
        equal(codeqs(ss, "Leaf", Flags(mode = "count")), "# 'Leaf' is a constructor of mytree.\n0\n")
      }
    }
    test("test_sites_codeqs.TheCommand.test_a_lemma_is_a_refusal") {
      fixture { ss =>
        val err = unresolved(ss, "twice_alt")
        contains(err, "'twice_alt' is a LEMMA in Code_Fix, not a constant")
        notContains(err, "ERROR:")
      }
    }
    test("test_sites_codeqs.TheCommand.test_an_unknown_constant_is_a_refusal_in_count_mode_too") {
      fixture { ss =>
        contains(unresolved(ss, "no_such_const_xyz", Flags(mode = "count")),
          "'no_such_const_xyz' is not a constant declared in this project")
      }
    }

    /* -- TheParser ---------------------------------------------------------- */
    def rejected(words: String*): String =
      raises(args(words.head, words.tail*)) match {
        case e: CLI.Usage_Error => e.message
        case e => check(false, s"not a usage error: $e"); ""
      }
    test("test_sites_codeqs.TheParser.test_the_flags") {
      val ns = args("codeqs", "f", "g", "-c", "--names", "--sorts")
      check(ns.bool("count") && ns.bool("names") && ns.bool("sorts"))
      equal(ns.pos("name"), List("f", "g"))
      equal(CLI.flags_of(ns).mode, "count")
    }
    test("test_sites_codeqs.TheParser.test_no_recursive_flag") {
      contains(rejected("codeqs", "f", "-r"), "-r")
    }
    test("test_sites_codeqs.TheParser.test_no_reach_flag") {
      contains(rejected("codeqs", "f", "--reach", "name"), "--reach")
    }

    /* -- TheCollisionFixture -------------------------------------------------
       Two theories called `Examples`, in `alpha/` and `beta/`.  At depth 1
       both are `Examples` and neither settles; at depth 2 `alpha/Examples`
       and `beta/Examples` both do.  Neither verb may print the bare,
       ambiguous `Examples:N`. */
    test("test_sites_codeqs.TheCollisionFixture.test_sections_load_alpha_then_beta") {
      collision { (_, ss) =>
        equal(ss.map(_.theory), List("Examples", "Examples"))
        equal(ss.map(_.path.getParent.getFileName.toString), List("alpha", "beta"))
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_names_are_qualified") {
      collision { (_, ss) =>
        equal(instances(ss, "L", Flags(mode = "names")), "alpha/Examples:11\nbeta/Examples:15\n")
        equal(codeqs(ss, "f", Flags(mode = "names")),
          "alpha/Examples:8\nalpha/Examples:13\nbeta/Examples:8\nbeta/Examples:17\n")
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_counts") {
      collision { (_, ss) =>
        equal(instances(ss, "L", Flags(mode = "count")), "2\n")
        equal(codeqs(ss, "f", Flags(mode = "count")), "4\n")
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_the_instances_table") {
      collision { (_, ss) =>
        equal(instances(ss, "L"), "2 instantiation(s) of L:\n\n  alpha/Examples:11  a_inst  interpretation  interpretation a_inst: L id ..\n  beta/Examples:15   b_inst  interpretation  interpretation b_inst: L id ..\n")
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_the_codeqs_table") {
      collision { (_, ss) =>
        equal(codeqs(ss, "f"), "4 code equation(s) of f:\n\n  alpha/Examples:8   f     default  definition f :: \"nat \\<Rightarrow> nat\" where\n  alpha/Examples:13  a_eq  [code]   lemma a_eq [code]: \"f n = Suc n\"\n  beta/Examples:8    f     default  definition f :: \"nat \\<Rightarrow> nat\" where\n  beta/Examples:17   b_eq  [code]   lemma b_eq [code]: \"f n = Suc (Suc n)\"\n")
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_no_row_prints_the_bare_name") {
      collision { (_, ss) =>
        for ((out, name) <- List(instances(ss, "L") -> "L", codeqs(ss, "f") -> "f"))
          for (line <- lines(out).drop(2))
            check(!matches(line, "^  Examples:\\d"), s"$name: $line")
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_sorts_leaves_the_locus_column_alone") {
      collision { (_, ss) =>
        equal(lines(codeqs(ss, "f", Flags(sorts = true))).drop(2).map(split(_).head),
          List("alpha/Examples:8", "alpha/Examples:13", "beta/Examples:8", "beta/Examples:17"))
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_every_locus_round_trips_through_enclosing") {
      collision { (root, _) =>
        val out = query(root, "enclosing", "alpha/Examples:13").out
        contains(out, "a_eq (LEMMA)")
        contains(out, "alpha/Examples:13")
      }
    }
    test("test_sites_codeqs.TheCollisionFixture.test_an_unknown_subject_still_exits_1") {
      collision { (_, ss) =>
        equal(capture((o, e) => Sites.cmd_instances(o, e, ss, "no_such_locale_xyz", Flags())).exit, 1)
      }
    }

    /* -- MixfixAttribution [code-heads] [code-clauses] -----------------------
       Hand-read: `A` is a declared constant (7), and `minus_set_fold`'s shape
       (9) is an equation of `-`, whose left operand is `A`; `insert_code` (12)
       is one of `insert`, not of the `set` in its argument; `set_abs` (15) is
       an abstract equation, which IS one of the `mk` in the projection's
       argument.  `twin_a` / `twin_b` (22-23) are two statements with an
       attribute each, one equation of `mk` and one of `twin`, not two of each;
       `prem` (26) is an equation of `twin`, its `and p:` a premise, not a
       second statement. */
    def sites_of(ss: List[Theory_Section], name: String): List[(Int, String)] =
      code(ss, name).map(s => (s.line, s.kind))
    test("test_sites_codeqs.MixfixAttribution.test_an_operand_is_not_a_head") {
      mixfix { ss => equal(sites_of(ss, "A"), List((7, "default"))) }
    }
    test("test_sites_codeqs.MixfixAttribution.test_an_argument_is_not_a_head") {
      mixfix { ss => equal(sites_of(ss, "set"), List((5, "default"))) }
    }
    test("test_sites_codeqs.MixfixAttribution.test_an_abstract_equation_reads_one_level_in") {
      mixfix { ss => equal(sites_of(ss, "mk").take(2), List((15, "[code abstract]"), (18, "default"))) }
    }
    test("test_sites_codeqs.MixfixAttribution.test_each_statement_carries_its_own_attribute") {
      mixfix { ss =>
        equal(sites_of(ss, "mk"), List((15, "[code abstract]"), (18, "default"), (22, "[code]")))
        equal(code(ss, "twin").map(s => (s.line, s.kind, s.name)),
          List((20, "default", "twin"), (23, "[code]", "twin_b"), (26, "[code]", "prem")))
      }
    }
  }
}
