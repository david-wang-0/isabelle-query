/*  Title:      tests/scala/shape/ShapeCore.scala

Scala port of tests/test_shape.py (97 cases): the step scanner, the per-step
metrics (M1..M6, fan-in, live space, introduce/consume), the classifier, the
per-proof pipeline and the JSONL records.  Every expected value is the one the
Python module hand-computed from tests/fixtures/Shape.thy; original test IDs
are kept as the `test(...)` labels.

The four pure helpers that are private in Scala (`bracket_chunks`,
`multiset_jaccard`, `canonicalize_consts`, `is_operator_const`) are called
through test-local reflection, so the original token lists and loop rows are
asserted exactly.  The Python W1 `provenance` map has no Scala field: it is
read from `classify_identifier`, the classifier `w1_est` consults.
*/

package isabelle.query.regression

import isabelle.query.*
import isabelle.query.regression.TestSupport.*

import java.nio.file.{Files, Path as JPath, Paths}


object ShapeCore {
  /* --- fixtures ------------------------------------------------------- */

  private def repo_file(rel: String): JPath = {
    var dir: JPath = Paths.get("").toAbsolutePath
    while (dir != null && !Files.exists(dir.resolve(rel))) dir = dir.getParent
    if (dir == null) throw new AssertionError(s"fixture not found from cwd: $rel")
    dir.resolve(rel)
  }

  private lazy val THY: String = Files.readString(repo_file("tests/fixtures/Shape.thy"))
  private lazy val M3_TOML: JPath = repo_file("tests/fixtures/m3_configs.toml")

  private def sec(): Theory_Section = parse(THY, "Shape", "Shape.thy")
  private def section_from(text: String, theory: String = "Test"): Theory_Section =
    parse(text, theory, theory + ".thy")

  private def entry(s: Theory_Section, name: String): Entry =
    s.entries.find(_.name == name).getOrElse(throw new NoSuchElementException(name))

  private def ctx_of(s: Theory_Section): Shape.Sec_Ctx = new Shape.Sec_Ctx(s)

  private def steps(name: String): List[Shape.Step] = {
    val s = sec()
    Shape.scan_steps(ctx_of(s), entry(s, name))
  }

  private def annotated(name: String): List[Shape.Step] = {
    val s = sec()
    val c = ctx_of(s)
    val st = Shape.scan_steps(c, entry(s, name))
    Shape.annotate_fanin(st, c.lines)
    st
  }

  private def shape(st: List[Shape.Step]): List[(Int, Int, String, String)] =
    st.map(s => (s.line, s.depth, s.kw, s.kind))

  private def live(name: String): (Int, Double) = {
    val s = sec(); val c = ctx_of(s)
    Shape.live_fact_space(Shape.scan_steps(c, entry(s, name)), c.lines)
  }

  private def ic(name: String): Shape.Intro_Consume = {
    val s = sec(); val c = ctx_of(s)
    Shape.introduce_consume(Shape.scan_steps(c, entry(s, name)), c.lines)
  }

  private def m3cfg(): Shape.Corpus_Config = Toml.read_corpus_configs(M3_TOML)("Shape")

  private def goal(name: String): Shape.Step = steps(name).find(_.kind == "goal").get

  private def pm(name: String): Shape.Proof_Metrics = {
    val s = sec()
    Shape.analyze_proof(ctx_of(s), entry(s, name)).get
  }

  private def approx(actual: Double, expected: Double, clue: String = ""): Unit =
    check(math.abs(actual - expected) < 1e-7, s"$clue expected ~<$expected>, got <$actual>")

  private def fields(v: Jsonl.V): Map[String, Jsonl.V] = v match {
    case Jsonl.O(fs) => fs.toMap
    case other => throw new AssertionError(s"not an object: $other")
  }
  private def keys(v: Jsonl.V): Set[String] = v match {
    case Jsonl.O(fs) => fs.map(_._1).toSet
    case other => throw new AssertionError(s"not an object: $other")
  }
  private def jnum(v: Jsonl.V): Double = v match {
    case Jsonl.I(n) => n.toDouble
    case Jsonl.D(x) => x
    case other => throw new AssertionError(s"not a number: $other")
  }

  /* Private pure helpers of `Shape`, reached by reflection (no production
     seam), so the original Python assertions hold on the exact lists. */
  private def invoke[A](name: String, args: AnyRef*): A = {
    val method = Shape.getClass.getDeclaredMethods
      .find(m => m.getName == name && m.getParameterCount == args.length).get
    method.setAccessible(true)
    method.invoke(Shape, args*).asInstanceOf[A]
  }
  private def bracket_chunks(toks: List[String]): List[String] =
    invoke[List[String]]("bracket_chunks", toks.toArray, Int.box(4))
  private def multiset_jaccard(a: List[String], b: List[String]): Option[Double] =
    invoke[Option[Double]]("multiset_jaccard", a, b)
  private def canonicalize_consts(names: List[String]): List[String] =
    invoke[List[String]]("canonicalize_consts", names)
  private def is_operator_const(tok: String): Boolean =
    invoke[java.lang.Boolean]("is_operator_const", tok).booleanValue

  /* `w1_est` for the one `have` goal stating `stmt`, with `fixes` names. */
  private def w1_of(stmt: String, fixes: String = ""): Shape.W1 = {
    val fx = if (fixes.isEmpty) "" else s"  fixes $fixes\n"
    val thy = "theory T imports Main begin\nlemma mix:\n" + fx + "  shows \"True\"\nproof -\n" +
      s"  have \"$stmt\" sorry\n  show \"True\" by simp\nqed\nend\n"
    val s = section_from(thy, "T")
    val e = entry(s, "mix"); val c = ctx_of(s)
    val st = Shape.scan_steps(c, e)
    val cc = Shape.build_ctx(c, e, st)
    Shape.w1_est(st.find(x => x.kind == "goal" && x.kw == "have").get, cc)
  }

  def run(): Unit = {
    /* ---------------- ClassifyLine ---------------- */
    test("test_shape.ClassifyLine.test_bare_goal") {
      equal(Shape.classify_step_line("have \"P\" by simp"), "goal")
    }
    test("test_shape.ClassifyLine.test_plumbing_prefixed_goal") {
      equal(Shape.classify_step_line("from a have b: \"P\""), "goal")
      equal(Shape.classify_step_line("ultimately show \"Q\" by blast"), "goal")
    }
    test("test_shape.ClassifyLine.test_context") {
      equal(Shape.classify_step_line("fix y"), "context")
      equal(Shape.classify_step_line("assume \"y = y\""), "context")
    }
    test("test_shape.ClassifyLine.test_plumbing") {
      equal(Shape.classify_step_line("moreover"), "plumbing")
      equal(Shape.classify_step_line("using a b"), "plumbing")
    }
    test("test_shape.ClassifyLine.test_closing") {
      equal(Shape.classify_step_line("by simp"), "closing")
      equal(Shape.classify_step_line("done"), "closing")
      equal(Shape.classify_step_line(".."), "closing")
    }
    test("test_shape.ClassifyLine.test_structural_is_other") {
      equal(Shape.classify_step_line("proof -"), "other")
      equal(Shape.classify_step_line("next"), "other")
      equal(Shape.classify_step_line("{"), "other")
    }
    test("test_shape.ClassifyLine.test_goal_keyword_inside_a_term_does_not_count") {
      equal(Shape.classify_step_line("show \"collect_have x = y\""), "goal")
      equal(Shape.classify_step_line("note x = \"no have here\""), "plumbing")
    }
    test("test_shape.ClassifyLine.test_prefixed_terminal_method_is_closing") {
      equal(Shape.classify_step_line("unfolding foo_def by simp"), "closing")
      equal(Shape.classify_step_line("unfolding a b .."), "closing")
      equal(Shape.classify_step_line("unfolding foo_def"), "other")
      equal(Shape.classify_step_line("unfolding Foo.bar_def"), "other")
      equal(Shape.classify_step_line("using a by auto"), "plumbing")
    }

    /* ---------------- FlatProof ---------------- */
    test("test_shape.FlatProof.test_only_the_closing_step") {
      equal(shape(steps("flat_proof")), List((6, 0, "by", "closing")))
    }
    test("test_shape.FlatProof.test_single_line_unfolding_by_is_one_closing_step") {
      val thy = "theory T imports Main begin\n" +
        "lemma foo: \"True\"\n" +
        "  unfolding refl by simp\n" +
        "lemma bar: \"True\"\n" +
        "  by simp\n" +
        "end\n"
      val s = section_from(thy, "T")
      val foo = entry(s, "foo"); val c = ctx_of(s)
      equal(Shape.scan_steps(c, foo).map(x => (x.kind, x.kw)), List(("closing", "unfolding")))
      check(Shape.analyze_proof(c, foo).isDefined, "analyze_proof returns a record")
    }

    /* ---------------- ChainedProof ---------------- */
    test("test_shape.ChainedProof.test_step_shape") {
      equal(shape(steps("chained")), List(
        (12, 0, "from", "goal"),
        (13, 0, "moreover", "goal"),
        (14, 0, "ultimately", "goal"),
        (15, 0, "qed", "closing")))
    }
    test("test_shape.ChainedProof.test_goal_statements_and_labels") {
      val goals = steps("chained").filter(_.kind == "goal")
      equal(goals.map(_.stmt_text), List("P", "P", "P \\<and> P"))
      equal(goals.map(_.label), List("p1", "", ""))
    }

    /* ---------------- NestedProof ---------------- */
    test("test_shape.NestedProof.test_step_shape_and_depth") {
      equal(shape(steps("nested")), List(
        (20, 0, "have", "goal"),
        (22, 1, "fix", "context"),
        (23, 1, "assume", "context"),
        (24, 1, "show", "goal"),
        (25, 1, "qed", "closing"),
        (26, 0, "show", "goal"),
        (27, 0, "qed", "closing")))
    }
    test("test_shape.NestedProof.test_goal_statements") {
      val goals = steps("nested").filter(_.kind == "goal")
      equal(goals.map(_.stmt_text), List("x = x", "x = x", "x = x"))
      equal(goals.map(_.line), List(20, 24, 26))
      equal(goals.head.label, "outer")
    }

    /* ---------------- W2SrcTokeniser ---------------- */
    test("test_shape.W2SrcTokeniser.test_plain") {
      equal(Shape.stmt_tokens("x = x"), List("x", "=", "x"))
    }
    test("test_shape.W2SrcTokeniser.test_symbol_is_one_token") {
      equal(Shape.stmt_tokens("P \\<and> P"), List("P", "\\<and>", "P"))
    }
    test("test_shape.W2SrcTokeniser.test_glued_subscript_stays_attached") {
      equal(Shape.stmt_tokens("f x = g\\<^sub>1 y"), List("f", "x", "=", "g\\<^sub>1", "y"))
    }
    test("test_shape.W2SrcTokeniser.test_delimiters_each_count") {
      equal(Shape.stmt_tokens("(a + b)"), List("(", "a", "+", "b", ")"))
    }

    /* ---------------- W2Src ---------------- */
    test("test_shape.W2Src.test_chained_goal_widths") {
      equal(steps("chained").filter(_.kind == "goal").map(Shape.w2_src), List(1, 1, 3))
    }
    test("test_shape.W2Src.test_nested_goal_widths") {
      equal(steps("nested").filter(_.kind == "goal").map(Shape.w2_src), List(3, 3, 3))
    }
    test("test_shape.W2Src.test_non_goal_steps_are_zero") {
      val non_goals = steps("nested").filter(_.kind != "goal")
      check(non_goals.nonEmpty, "nested has non-goal steps")
      check(non_goals.forall(s => Shape.w2_src(s) == 0), "non-goal w2_src is 0")
    }

    /* ---------------- FanIn ---------------- */
    def goal_fanins(name: String): List[Int] = annotated(name).filter(_.kind == "goal").map(_.fanin)
    test("test_shape.FanIn.test_chained") { equal(goal_fanins("chained"), List(1, 1, 1)) }
    test("test_shape.FanIn.test_nested") { equal(goal_fanins("nested"), List(0, 0, 1)) }
    test("test_shape.FanIn.test_standalone_plumbing_line_attaches_to_next_goal") {
      equal(goal_fanins("standalone"), List(1, 0))
    }
    test("test_shape.FanIn.test_flat_proof_has_no_goal_steps") {
      equal(goal_fanins("flat_proof"), Nil)
    }

    /* ---------------- Introduces ---------------- */
    def by_line(name: String): Map[Int, (Boolean, String)] =
      steps(name).map(s => s.line -> (Shape.introduces(s), s.goal_cmd)).toMap
    test("test_shape.Introduces.test_from_have_introduces_via_goal_cmd") {
      val flags = by_line("chained")
      equal(flags(12), (true, "have"))
      equal(flags(14), (false, "show"))
    }
    test("test_shape.Introduces.test_context_and_closing") {
      val flags = by_line("nested")
      equal(flags(20)._1, true, "have outer:")
      equal(flags(22)._1, false, "fix y")
      equal(flags(23)._1, true, "assume")
      equal(flags(26), (false, "show"))
      equal(flags(27)._1, false, "qed")
    }

    /* ---------------- LiveFactSpace ---------------- */
    test("test_shape.LiveFactSpace.test_reuse_holds_three_at_the_peak") {
      equal(live("reuse"), (3, 1.6))
    }
    test("test_shape.LiveFactSpace.test_standalone_then_chaining_extends_life") {
      equal(live("standalone"), (1, 0.5))
    }
    test("test_shape.LiveFactSpace.test_nested_single_fact_peak") {
      equal(live("nested")._1, 1)
    }
    test("test_shape.LiveFactSpace.test_flat_proof_is_empty") {
      equal(live("flat_proof"), (0, 0.0))
    }

    /* ---------------- IntroduceConsume ---------------- */
    test("test_shape.IntroduceConsume.test_chained") {
      val x = ic("chained")
      equal((x.introduce, x.consume, x.both), (2, 3, 2))
      equal((x.introduce_only, x.consume_only, x.neither), (0, 1, 1))
      equal(x.ratio, Some(2.0 / 3))
    }
    test("test_shape.IntroduceConsume.test_standalone") {
      val x = ic("standalone")
      equal((x.introduce_only, x.consume_only, x.both), (1, 1, 0))
      equal(x.ratio, Some(1.0))
    }
    test("test_shape.IntroduceConsume.test_nested") {
      val x = ic("nested")
      equal((x.introduce, x.consume), (2, 1))
      equal(x.ratio, Some(2.0))
    }
    test("test_shape.IntroduceConsume.test_reuse") {
      val x = ic("reuse")
      equal((x.introduce, x.consume, x.both), (3, 4, 3))
      equal((x.consume_only, x.neither), (1, 1))
      equal(x.ratio, Some(0.75))
    }
    test("test_shape.IntroduceConsume.test_flat_proof_ratio_is_undefined") {
      val x = ic("flat_proof")
      equal((x.introduce, x.consume), (0, 0))
      equal(x.ratio, None)
    }

    /* ---------------- AnalyzeStatement ---------------- */
    test("test_shape.AnalyzeStatement.test_plain") {
      val sv = Shape.analyze_statement("x = x")
      equal((sv.free, sv.schematic, sv.bound), (List("x"), Nil, Nil))
    }
    test("test_shape.AnalyzeStatement.test_binder_separates_bound") {
      val sv = Shape.analyze_statement("\\<forall>k. P k")
      equal(sv.bound, List("k"))
      equal(sv.free, List("P"))
    }
    test("test_shape.AnalyzeStatement.test_glued_binder_prefix_is_split") {
      val sv = Shape.analyze_statement("\\<exists>n. n = n")
      equal((sv.bound, sv.free), (List("n"), Nil))
    }
    test("test_shape.AnalyzeStatement.test_multiple_bound") {
      val sv = Shape.analyze_statement("\\<lambda>x y. f x y")
      equal((sv.bound, sv.free), (List("x", "y"), List("f")))
    }
    test("test_shape.AnalyzeStatement.test_schematic") {
      val sv = Shape.analyze_statement("?P x \\<longrightarrow> ?P x")
      equal((sv.schematic, sv.free), (List("P"), List("x")))
    }

    /* ---------------- ClassifyIdentifier ---------------- */
    test("test_shape.ClassifyIdentifier.test_context_wins_over_entry_and_corpus") {
      val c = Shape.Classify_Ctx(entry_names = Set("myconst"),
        context_vars = Set("x", "myconst"), corpus_consts = Set("rev"))
      equal(Shape.classify_identifier("myconst", c), ("var", "context"))
      equal(Shape.classify_identifier("rev", c), ("const", "corpus"))
      equal(Shape.classify_identifier("if", c), ("const", "syntax"))
    }
    test("test_shape.ClassifyIdentifier.test_entry_then_default") {
      val c = Shape.Classify_Ctx(entry_names = Set("myentry"), context_vars = Set.empty,
        corpus_consts = Set.empty)
      equal(Shape.classify_identifier("myentry", c), ("const", "entry"))
      equal(Shape.classify_identifier("y", c), ("var", "default"))
    }
    test("test_shape.ClassifyIdentifier.test_syntax_wins_over_entry") {
      val c = Shape.Classify_Ctx(entry_names = Set("foo"), context_vars = Set.empty,
        corpus_consts = Set.empty)
      check(c.namespace.methods("foo"), "premise: `foo` is in the committed method union")
      equal(Shape.classify_identifier("foo", c), ("const", "syntax"))
    }
    test("test_shape.ClassifyIdentifier.test_single_letter_constant_misclassifies") {
      val c = Shape.Classify_Ctx(entry_names = Set.empty, context_vars = Set.empty)
      equal(Shape.classify_identifier("e", c), ("var", "default"))
      val sv = Shape.analyze_statement("x \\<otimes> e = x")
      equal(sv.free, List("x", "e"))
    }

    /* ---------------- W1Est ---------------- */
    def goal_w1s(name: String): (List[Shape.W1], Shape.Classify_Ctx) = {
      val s = sec(); val e = entry(s, name); val c = ctx_of(s)
      val st = Shape.scan_steps(c, e)
      val cc = Shape.build_ctx(c, e, st)
      (st.filter(_.kind == "goal").map(x => Shape.w1_est(x, cc)), cc)
    }
    test("test_shape.W1Est.test_classify_demo") {
      val (w1s, cc) = goal_w1s("classify_demo")
      equal(w1s.length, 2, "have + show")
      val have = w1s(0); val show = w1s(1)
      equal((have.free, have.schematic, have.bound), (1, 0, 1))
      equal(have.free_names, List("g"))
      /* The Python W1 carries a `provenance` map; the Scala W1 does not, so
         the provenance is read from the classifier the estimator consults,
         with the const/var split confirmed on the W1 columns. */
      note("provenance via classify_identifier + W1 columns (no provenance field on Scala W1)")
      equal(Shape.classify_identifier("rev", cc), ("const", "corpus"))
      equal(have.const_names, List("rev", "="))
      equal(Shape.classify_identifier("g", cc), ("var", "context"))
      equal((show.free, show.schematic, show.bound), (1, 0, 0))
    }
    test("test_shape.W1Est.test_default_vars_without_fixes") {
      equal(goal_w1s("reuse")._1.map(_.free), List(1, 1, 2, 2))
    }
    test("test_shape.W1Est.test_corpus_list_has_staples") {
      for (c <- List("Suc", "map", "rev", "length", "insert", "finite", "set"))
        check(Shape_Data.CORPUS_CONSTANTS(c), s"$c in CORPUS_CONSTANTS")
    }
    test("test_shape.W1Est.test_fix_line_does_not_leak_proposition_constants") {
      val thy = "theory T imports Main begin\n" +
        "lemma l: shows \"True\"\n" +
        "proof -\n" +
        "  fix VS assume \"VS \\<subseteq> insert a A\"\n" +
        "  show \"True\" by simp\n" +
        "qed\n" +
        "end\n"
      val s = section_from(thy, "T")
      val e = entry(s, "l"); val c = ctx_of(s)
      val st = Shape.scan_steps(c, e)
      val cv = Shape.build_ctx(c, e, st).context_vars
      check(cv("VS"), "VS in context vars")
      check(!cv("insert"), "insert not in context vars")
      check(!cv("A"), "A not in context vars")
    }

    /* ---------------- ConstEst ---------------- */
    test("test_shape.ConstEst.test_names_and_operator_symbols_both_count") {
      val w = w1_of("Suc n + m \\<le> Suc m \\<and> n \\<le> m", "n :: nat and m :: nat")
      equal((w.free, w.free_names), (2, List("n", "m")))
      equal(w.const, 4)
      equal(w.const_names, List("Suc", "+", "\\<le>", "\\<and>"))
      note("provenance of Suc via classify_identifier (no provenance field on Scala W1)")
      val cc = Shape.Classify_Ctx(Set("mix"), Set("n", "m"))
      equal(Shape.classify_identifier("Suc", cc), ("const", "corpus"))
      equal(w.const_canon, 4)
    }
    test("test_shape.ConstEst.test_canonicalize_dedups_overloaded_notation") {
      val raw = List("\\<le>", "\\<subseteq>", "\\<and>")
      equal(Shape_Data.NOTATION("\\<le>"), Shape_Data.NOTATION("\\<subseteq>"))
      equal(canonicalize_consts(raw).length, 2)   // less_eq, conj
      equal(canonicalize_consts(List("insert", "\\<zzz_unknown>")),
        List("insert", "\\<zzz_unknown>"))
    }
    test("test_shape.ConstEst.test_is_operator_const_classification") {
      val yes = List("\\<in>", "+", "=", "\\<le>", "\\<and>", "\\<longrightarrow>")
      val no = List("(", "\\<forall>", "\\<open>", "Suc", "g\\<^sub>1", "\\<Gamma>", "\\<alpha>",
        "\\<And>c_b", "\\<Union>j", "\\<Gamma>\\<^sub>M", "0")
      for (t <- yes) subcase(t) { check(is_operator_const(t), t) }
      for (t <- no) subcase(t) { check(!is_operator_const(t), t) }
    }

    /* ---------------- Blocks ---------------- */
    test("test_shape.Blocks.test_flat_and_chained_are_one_block") {
      equal(steps("flat_proof").map(_.block), List(0))
      equal(steps("chained").map(_.block), List(0, 0, 0, 0))
    }
    test("test_shape.Blocks.test_nested_second_proof_is_a_new_block") {
      equal(steps("nested").map(_.block), List(0, 1, 1, 1, 1, 0, 0))
    }

    /* ---------------- BracketChunks ---------------- */
    test("test_shape.BracketChunks.test_tuple_is_a_chunk") {
      val toks = Shape.stmt_tokens("(a, b) = (a, b)")
      equal(bracket_chunks(toks), List("( a , b )", "( a , b )"))
    }
    test("test_shape.BracketChunks.test_short_group_below_threshold_is_dropped") {
      equal(bracket_chunks(Shape.stmt_tokens("f (x)")), Nil)
    }
    test("test_shape.BracketChunks.test_nested_forest") {
      val toks = Shape.stmt_tokens("g (h (a, b))")
      equal(bracket_chunks(toks), List("( a , b )", "( h ( a , b ) )"))
    }
    test("test_shape.BracketChunks.test_multiset_jaccard") {
      equal(multiset_jaccard(List("x", "x"), List("x", "y")), Some(1.0 / 3))
      equal(multiset_jaccard(Nil, Nil), None)
      equal(multiset_jaccard(List("x"), List("x")), Some(1.0))
    }

    /* ---------------- CrossStepRedundancy ---------------- */
    def blocks(name: String): List[Shape.Block_Redundancy] = Shape.cross_step_redundancy(steps(name))
    test("test_shape.CrossStepRedundancy.test_redundant_block_compresses") {
      val bs = blocks("redundant")
      equal(bs.length, 1)
      val blk = bs.head
      equal((blk.block, blk.n_goals), (0, 3))
      equal((blk.total_tokens, blk.compressed_tokens), (33, 8))
      approx(blk.dag_ratio, 33.0 / 8)
      equal(blk.overlaps, List(Some(1.0), Some(1.0)))
    }
    test("test_shape.CrossStepRedundancy.test_framing_partial_overlap") {
      val bs = blocks("framing")
      equal(bs.length, 1)
      val blk = bs.head
      equal((blk.total_tokens, blk.compressed_tokens), (28, 13))
      approx(blk.dag_ratio, 28.0 / 13)
      approx(blk.overlaps(0).get, 1.0 / 3)
      approx(blk.overlaps(1).get, 1.0 / 2)
    }
    test("test_shape.CrossStepRedundancy.test_nested_two_blocks_no_redundancy") {
      val bs = blocks("nested")
      equal(bs.length, 2)
      val b0 = bs(0); val b1 = bs(1)
      equal((b0.block, b0.n_goals, b0.dag_ratio), (0, 2, 1.0))
      equal(b0.overlaps, List(None))
      equal((b1.block, b1.n_goals, b1.dag_ratio), (1, 1, 1.0))
      equal(b1.overlaps, Nil)
    }

    /* ---------------- ExtensionCurveTest ---------------- */
    def curve(name: String): List[Shape.Extension_Curve] = {
      val s = sec(); val e = entry(s, name); val c = ctx_of(s)
      val st = Shape.scan_steps(c, e)
      Shape.extension_curve(st, Shape.build_ctx(c, e, st))
    }
    test("test_shape.ExtensionCurveTest.test_redundant_collapses") {
      val cs = curve("redundant")
      equal(cs.length, 1)
      val c = cs.head
      equal(c.ks, List(0, 1, 2, 4, 8, 16))
      equal(c.w1, List(6, 0, 0, 0, 0, 0))
      equal(c.w2, List(33, 9, 9, 9, 9, 9))
    }
    test("test_shape.ExtensionCurveTest.test_framing_partial") {
      val cs = curve("framing")
      equal(cs.length, 1)
      equal(cs.head.w1, List(9, 5, 5, 5, 5, 5))
      equal(cs.head.w2, List(28, 12, 12, 12, 12, 12))
    }
    test("test_shape.ExtensionCurveTest.test_k0_reproduces_raw_widths") {
      val s = sec(); val e = entry(s, "framing"); val c = ctx_of(s)
      val st = Shape.scan_steps(c, e)
      val cc = Shape.build_ctx(c, e, st)
      val goals = st.filter(_.kind == "goal")
      equal(Shape.extension_curve(st, cc).head.w1.head, goals.map(g => Shape.w1_est(g, cc).free).sum)
      equal(Shape.extension_curve(st, cc).head.w2.head, goals.map(Shape.w2_src).sum)
    }

    /* ---------------- M3ConfigLoad ---------------- */
    test("test_shape.M3ConfigLoad.test_toml_loads_name_lists") {
      val cfg = m3cfg()
      equal(cfg.selectors, Set("fst", "snd"))
      equal(cfg.constructors, Set("Pair"))
      equal(cfg.relations, Set.empty[String])
    }

    /* ---------------- FrameRatioTest ---------------- */
    def fr(name: String): Shape.Frame_Ratio = Shape.frame_ratio(goal(name), m3cfg()).get
    test("test_shape.FrameRatioTest.test_pair_selectors_no_update") {
      val f = fr("m3_pair")
      equal((f.mentioned, f.changed, f.ratio), (4, 0, 4.0))
    }
    test("test_shape.FrameRatioTest.test_framing_ratio_one") {
      val f = fr("m3_framing")
      equal((f.mentioned, f.changed, f.ratio), (2, 2, 1.0))
    }
    test("test_shape.FrameRatioTest.test_wide_delta_tracing") {
      val f = fr("m3_wide")
      equal((f.mentioned, f.changed, f.ratio), (10, 2, 5.0))
    }
    test("test_shape.FrameRatioTest.test_non_config_proposition_is_null") {
      val cfg = m3cfg()
      equal(Shape.frame_ratio(goal("nested"), cfg), None)
      equal(Shape.frame_ratio(goal("chained"), cfg), None)
    }

    /* ---------------- M3SummaryTest ---------------- */
    def m3sum(name: String): Shape.M3_Summary = Shape.frame_ratios(steps(name), m3cfg())
    test("test_shape.M3SummaryTest.test_wide_full_coverage") {
      val s = m3sum("m3_wide")
      equal((s.n_goals, s.n_computed, s.coverage), (1, 1, 1.0))
      equal((s.max_ratio, s.mean_ratio), (Some(5.0), Some(5.0)))
    }
    test("test_shape.M3SummaryTest.test_nested_zero_coverage") {
      val s = m3sum("nested")
      equal((s.n_goals, s.n_computed, s.coverage), (3, 0, 0.0))
      equal(s.max_ratio, None)
    }

    /* ---------------- AnalyzeProof ---------------- */
    test("test_shape.AnalyzeProof.test_bare_definition_has_no_metrics") {
      val s = sec()
      val p = Shape.analyze_proof(ctx_of(s), entry(s, "flat_proof"))
      check(p.isDefined, "analyze_proof is not None")
      equal(p.get.goals, Nil)
    }
    test("test_shape.AnalyzeProof.test_annotation_ran_before_access") {
      val p = pm("reuse")
      equal(p.goals.last.fanin, 2)
      equal(p.live_max, 3)
    }
    test("test_shape.AnalyzeProof.test_redundant_rollup_is_hand_computed") {
      val ps = Shape.summarize(pm("redundant"))
      equal((ps.n_steps, ps.n_goals, ps.n_bare), (4, 3, 0))
      equal(ps.depth_max, 1)
      equal((ps.w2_max, ps.w2_mean, ps.w2_p90), (11, 11.0, 11.0))
      equal((ps.w1_max, ps.w1_mean), (2, 2.0))
      equal(ps.fanin_max, 2)
      approx(ps.fanin_mean, 2.0 / 3)
      equal(ps.fanin_cited, 1)
      equal(ps.live_max, 2)
      approx(ps.live_mean, 1.25)
      approx(ps.dag_max, 33.0 / 8)
      equal((ps.intro, ps.consume, ps.both, ps.ratio), (2, 1, 0, Some(2.0)))
      equal(ps.trivial_frac, Some(1.0))
      approx(ps.removable_w2, 1 - 9.0 / 33)
    }

    /* ---------------- DepthMax ---------------- */
    def depth(name: String): Int = Shape.summarize(pm(name)).depth_max
    test("test_shape.DepthMax.test_flat_one_liner_is_depth_1") { equal(depth("flat_proof"), 1) }
    test("test_shape.DepthMax.test_flat_structured_proof_is_depth_1") { equal(depth("chained"), 1) }
    test("test_shape.DepthMax.test_one_nested_block_is_depth_2") { equal(depth("nested"), 2) }
    test("test_shape.DepthMax.test_two_nested_blocks_is_depth_3") { equal(depth("deeply_nested"), 3) }
    test("test_shape.DepthMax.test_summary_record_carries_depth_max") {
      val rec = fields(Shape_Cmds.summary_record(Shape.summarize(pm("nested"))))
      equal(rec("depth_max"), Jsonl.I(2))
    }

    /* ---------------- FanInCited ---------------- */
    def cited(name: String): Int = Shape.summarize(pm(name)).fanin_cited
    test("test_shape.FanInCited.test_every_goal_cites") {
      equal(cited("reuse"), 4)
      equal(cited("chained"), 3)
    }
    test("test_shape.FanInCited.test_only_citing_goals_counted") {
      equal(cited("nested"), 1)
      equal(cited("standalone"), 1)
    }
    test("test_shape.FanInCited.test_goal_free_proof_is_zero") { equal(cited("flat_proof"), 0) }
    test("test_shape.FanInCited.test_summary_record_carries_fanin_cited") {
      val rec = fields(Shape_Cmds.summary_record(Shape.summarize(pm("reuse"))))
      equal(rec("fanin_cited"), Jsonl.I(4))
    }

    /* ---------------- TrivialAndRemovable ---------------- */
    /* The Python `@needs_hol_methods` cases bind a session-exact HOL table from a
       running Isabelle (skipping without one).  `Step.method` is positional in
       Scala (`Usage_Graph.leading_method`), so the same values hold under any
       table and the cases run unconditionally here. */
    def methods(name: String): List[String] = pm(name).steps.map(_.method).filter(_.nonEmpty)
    test("test_shape.TrivialAndRemovable.test_method_is_the_leading_discharge") {
      note("needs_hol_methods in Python; Step.method is positional in Scala")
      equal(methods("redundant"), List("simp", "simp", "simp"))
      equal(methods("chained"), List("blast", "simp", "rule"))
      equal(methods("m3_pair"), List("auto"))
    }
    test("test_shape.TrivialAndRemovable.test_trivial_frac_over_methoded_steps") {
      note("needs_hol_methods in Python; Step.method is positional in Scala")
      equal(Shape.trivial_frac(pm("redundant").steps), Some(1.0))
      equal(Shape.trivial_frac(pm("reuse").steps), Some(1.0))
      approx(Shape.trivial_frac(pm("chained").steps).get, 2.0 / 3)
      equal(Shape.trivial_frac(pm("nested").steps), Some(0.5))
    }
    test("test_shape.TrivialAndRemovable.test_trivial_frac_none_when_no_method") {
      val st = List(new Shape.Step("T", "l", 1, 0, "have", "goal", method = ""))
      equal(Shape.trivial_frac(st), None)
    }
    test("test_shape.TrivialAndRemovable.test_removable_scalar") {
      val p = pm("redundant")
      approx(Shape.removable_w2_at_8(p.steps, p.cctx), 1 - 9.0 / 33)
      val q = pm("nested")
      equal(Shape.removable_w2_at_8(q.steps, q.cctx), 0.0)
    }

    /* ---------------- MethodKinds ---------------- */
    test("test_shape.MethodKinds.test_classification") {
      equal(Shape.method_kind("simp"), "automation")
      equal(Shape.method_kind("blast"), "search")
      equal(Shape.method_kind("linarith"), "arith")
      equal(Shape.method_kind("induct"), "structural")
      equal(Shape.method_kind("transfer"), "other")
      equal(Shape.method_kind(""), "")
    }
    test("test_shape.MethodKinds.test_counts_partition_discharged_steps") {
      note("needs_hol_methods in Python; Step.method is positional in Scala")
      val c = Shape.method_kind_counts(pm("chained").steps)
      equal(c.toMap, Map("automation" -> 1, "search" -> 1, "arith" -> 0, "structural" -> 1, "other" -> 0))
      equal(c.map(_._1).toSet, Shape.METHOD_KIND_NAMES.toSet)
      equal(c.map(_._2).sum, pm("chained").steps.count(_.method.nonEmpty))
    }
    test("test_shape.MethodKinds.test_counts_over_redundant") {
      val c = Shape.method_kind_counts(pm("redundant").steps).toMap
      equal((c("automation"), c.values.sum), (3, 3))
    }

    /* ---------------- StepRecord ---------------- */
    def records(name: String, cfg: Option[Shape.Corpus_Config] = None): List[Map[String, Jsonl.V]] = {
      val p = pm(name)
      val lines = p.sec.source
      p.steps.map(s => fields(Shape_Cmds.step_record(s, p.cctx, lines, cfg)))
    }
    test("test_shape.StepRecord.test_goal_record_fields") {
      val rec = records("redundant").find(_("line") == Jsonl.I(62)).get
      equal(rec("theory"), Jsonl.S("Shape"))
      equal(rec("lemma"), Jsonl.S("redundant"))
      equal((rec("kind"), rec("kw"), rec("goal_cmd")), (Jsonl.S("goal"), Jsonl.S("show"), Jsonl.S("show")))
      equal(rec("method"), Jsonl.S("simp"))
      equal(rec("block"), Jsonl.I(0))
      equal(rec("w2_src"), Jsonl.I(11))
      equal(rec("w1_est"), Jsonl.I(2))
      equal((rec("w1_schematic_est"), rec("w1_bound_est")), (Jsonl.I(0), Jsonl.I(0)))
      equal(rec("fanin"), Jsonl.I(2))
      equal(rec("fanin_covered"), Jsonl.B(true))
      equal(rec("live"), Jsonl.I(2))
      equal(rec("introduces"), Jsonl.B(false))
      equal(rec("consumes"), Jsonl.B(true))
    }
    test("test_shape.StepRecord.test_estimator_columns_carry_est_suffix") {
      val rec = records("redundant").head
      equal(rec.keySet.filter(_.endsWith("_est")),
        Set("w1_est", "w1_schematic_est", "w1_bound_est", "const_est", "const_canon_est"))
      for (exact <- List("w2_src", "fanin", "live")) check(rec.contains(exact), s"$exact in record")
    }
    test("test_shape.StepRecord.test_uniform_schema_across_step_kinds") {
      val p = pm("nested")
      val lines = p.sec.source
      val recs = p.steps.map(s => Shape_Cmds.step_record(s, p.cctx, lines, None))
      equal(recs.map(keys).toSet.size, 1, "one schema for all steps")
      val qed = recs.map(fields).find(_("kind") == Jsonl.S("closing")).get
      equal((qed("w2_src"), qed("w1_est"), qed("fanin")), (Jsonl.I(0), Jsonl.I(0), Jsonl.I(0)))
    }
    test("test_shape.StepRecord.test_config_gates_frame_columns") {
      val p = pm("m3_wide")
      val lines = p.sec.source
      val g = p.steps.find(_.kind == "goal").get
      check(!keys(Shape_Cmds.step_record(g, p.cctx, lines, None)).contains("frame_ratio"),
        "no frame_ratio without a config")
      val rec = fields(Shape_Cmds.step_record(g, p.cctx, lines, Some(m3cfg())))
      equal(rec("frame_ratio"), Jsonl.D(5.0))
      equal((rec("frame_mentioned"), rec("frame_changed")), (Jsonl.I(10), Jsonl.I(2)))
    }

    /* ---------------- SummaryRecord ---------------- */
    test("test_shape.SummaryRecord.test_matches_summarize") {
      val ps = Shape.summarize(pm("redundant"))
      val rec = fields(Shape_Cmds.summary_record(ps))
      equal(rec("theory"), Jsonl.S("Shape"))
      equal(rec("lemma"), Jsonl.S("redundant"))
      equal((rec("n_goals"), rec("n_bare")), (Jsonl.I(3), Jsonl.I(0)))
      equal(rec("w2_src_max"), Jsonl.I(11))
      equal(rec("w1_est_max"), Jsonl.I(2))
      approx(jnum(rec("dag_ratio_est_max")), 33.0 / 8)
      equal((rec("ratio"), rec("both")), (Jsonl.D(2.0), Jsonl.I(0)))
      equal(rec("trivial_frac"), Jsonl.D(1.0))
      approx(jnum(rec("removable_w2_est_at_8")), 1 - 9.0 / 33)
      equal(fields(rec("method_kinds")),
        Map("automation" -> Jsonl.I(3), "search" -> Jsonl.I(0), "arith" -> Jsonl.I(0),
          "structural" -> Jsonl.I(0), "other" -> Jsonl.I(0)))
    }

    /* ---------------- ProofSize ---------------- */
    test("test_shape.ProofSize.test_lines_and_tokens_raw_and_code") {
      val snip = "theory T imports Main begin\n" +
        "\n" +
        "lemma foo: \"P \\<longrightarrow> P\"\n" +
        "proof -\n" +
        "  \\<comment> \\<open>this is a\n" +
        "  two-line comment\\<close>\n" +
        "  show \"P \\<longrightarrow> P\" by simp\n" +
        "qed\n" +
        "\n" +
        "end\n"
      val s = section_from(snip)
      val e = s.entries.head
      equal((e.proof_line, e.body_end_line), (4, 8))
      equal(Usage_Graph.noise_spans(s), List((5, 6)))
      val c = ctx_of(s)
      val rec = fields(Shape_Cmds.summary_record(Shape.summarize(Shape.analyze_proof(c, e).get)))
      equal(rec("proof_lines"), Jsonl.I(5))
      equal(rec("proof_lines_code"), Jsonl.I(3))
      equal(rec("proof_tokens"), Jsonl.I(19))
      equal(rec("proof_tokens_code"), Jsonl.I(11))
      equal(rec("entry_lines"), Jsonl.I(e.line_count))
      check(!rec.contains("proof_lines_prose"), "prose is derived, not stored")
    }
    test("test_shape.ProofSize.test_region_counts_empty_span_is_zero") {
      val s = section_from("theory T imports Main begin\nlemma a: \"P\" by simp\nend\n")
      val c = ctx_of(s)
      equal(Shape.region_counts(c, 0, 0), (0, 0, 0, 0))
      equal(Shape.region_counts(c, 5, 2), (0, 0, 0, 0))
    }
  }
}
