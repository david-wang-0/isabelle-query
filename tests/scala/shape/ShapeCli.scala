package isabelle.query.regression

/* Port of tests/test_census_sessions.py (17 cases) and tests/test_shape_cli.py
   (22 cases).  The Python originals drive `shape_cmds.cmd_shape_*` and the
   argparse wiring; here the same seams are `Shape_Cmds.cmd_shape_*`, the
   `CLI.commands` grammar (`CLI.parse` on the `shape` group's views), and the
   in-process `CLI.run_result` for everything the Python tests reached by
   monkey-patching `cli._ROOT_OVERRIDE` / `cli.sections_for_session`.

   Two deliberate substitutions, both noted at the test:
     * exception class names on stderr are the JVM's (`RuntimeException`,
       `IOException`) where Python prints `RuntimeError` / `OSError`;
     * `Session.sections_for_session` is not injectable, so the ExitContract
       cases that replaced the loader use a NATURAL load failure instead — a
       declared theory whose `.thy` path is a directory. */

import isabelle.query._
import TestSupport._

import java.io.{StringWriter, Writer}
import java.nio.file.{Files, Path => JPath, Paths}
import scala.collection.mutable

object ShapeCli {

  /* --- fixtures ------------------------------------------------------- */

  private val THY_TEMPLATE =
    "theory {name} imports Main begin\n\n" +
    "lemma {name}_a: \"P a\"\n" +
    "proof -\n" +
    "  have \"Q a\" by simp\n" +
    "  show \"P a\" by simp\n" +
    "qed\n\n" +
    "end\n"

  private def thy(name: String): String = THY_TEMPLATE.replace("{name}", name)

  private def sec(name: String, session: Option[String] = None): Theory_Section =
    parse(thy(name), theory = name, file = name + ".thy", session = session)

  /* The repository's `tests/fixtures`, found by walking up from the cwd so the
     fixture BYTES are the committed ones rather than a transcription. */
  private lazy val fixtures: JPath = {
    var dir: JPath = Paths.get("").toAbsolutePath
    var found: Option[JPath] = None
    while (found.isEmpty && dir != null) {
      val cand = dir.resolve("tests").resolve("fixtures")
      if (Files.isRegularFile(cand.resolve("Shape.thy"))) found = Some(cand)
      else dir = dir.getParent
    }
    found.getOrElse(throw new IllegalStateException("tests/fixtures/Shape.thy not found above cwd"))
  }
  private lazy val SHAPE_THY: String = Files.readString(fixtures.resolve("Shape.thy"))
  private def shapeSec(): Theory_Section = parse(SHAPE_THY, theory = "Shape", file = "Shape.thy")

  private val STEP_KEYS = Set(
    "theory", "lemma", "line", "block", "depth", "kind", "kw", "goal_cmd",
    "method", "label", "stmt_start", "stmt_end", "w2_src", "w1_est",
    "w1_schematic_est", "w1_bound_est", "const_est", "const_canon_est",
    "fanin", "fanin_covered", "live", "introduces", "consumes")
  private val SUMMARY_KEYS = Set(
    "session",
    "theory", "lemma", "n_steps", "n_goals", "n_bare", "depth_max", "w2_src_max",
    "w2_src_mean", "w2_src_p90", "w1_est_max", "w1_est_mean",
    "const_est_max", "const_est_mean",
    "const_canon_est_max", "const_canon_est_mean", "fanin_max",
    "fanin_mean", "fanin_cited", "live_max", "live_mean", "dag_ratio_est_max",
    "introduce",
    "consume", "both", "ratio", "trivial_frac", "removable_w2_est_at_8",
    "method_kinds",
    "bare_kinds",
    "n_induct", "induct_terms_max", "induct_arbitrary_max", "induct_rule",
    "induct_recursion",
    "proof_lines", "proof_lines_code", "proof_tokens", "proof_tokens_code",
    "entry_lines")

  /* --- helpers -------------------------------------------------------- */

  private def capture(body: (Out, Out) => Unit): (String, String) = {
    val o = new StringWriter; val e = new StringWriter
    body(new Out(o), new Out(e))
    (o.toString, e.toString)
  }
  private def stdout(body: Out => Unit): String = capture((o, _) => body(o))._1

  private type Rec = Map[String, Jsonl.V]
  private def jsonl(text: String): List[Rec] =
    text.split("\n").toList.filter(_.trim.nonEmpty).map(ln =>
      Jsonl.parse_object(ln).getOrElse(throw new AssertionError(s"not a JSON object: $ln")))
  private def num(v: Jsonl.V): Double = v match {
    case Jsonl.D(x) => x
    case Jsonl.I(n) => n.toDouble
    case other => throw new AssertionError(s"not a number: $other")
  }
  private def str(v: Jsonl.V): String = v match {
    case Jsonl.S(s) => s
    case other => throw new AssertionError(s"not a string: $other")
  }
  private def isNull(v: Jsonl.V): Boolean = v == Jsonl.Null

  private def steps(sections: List[Theory_Section], span: Option[String],
    as_json: Boolean = false, all_steps: Boolean = false,
    cfg: Option[Shape.Corpus_Config] = None
  ): String = stdout(out =>
    Shape_Cmds.cmd_shape_steps(out, new Out(new StringWriter), sections, span, as_json,
      all_steps, cfg))

  private type Groups = Iterator[(String, () => List[Theory_Section])]
  private def groups(gs: (String, () => List[Theory_Section])*): Groups = gs.iterator

  /* `_run`: (records, stderr, outcome). */
  private def runCensus(gs: Groups, resume: Option[String] = None
  ): (List[Rec], String, Shape_Cmds.Census_Outcome) = {
    var outcome: Shape_Cmds.Census_Outcome = null
    val (out, err) = capture((o, e) => outcome = Shape_Cmds.cmd_shape_census(o, e, gs, resume))
    (jsonl(out), err, outcome)
  }
  private def boom(exn: Throwable = new RuntimeException("unparseable")): () => List[Theory_Section] =
    () => throw exn

  private def withTempFile[A](suffix: String, text: String)(body: String => A): A = {
    val path = Files.createTempFile("query-regression-", suffix)
    try { Files.writeString(path, text); body(path.toString) }
    finally Files.deleteIfExists(path)
  }

  private def shapeGroup: CLI.Cmd = CLI.commands.find(_.names.contains("shape")).get
  private def view(name: String): CLI.Cmd = shapeGroup.subs.find(_.names.contains(name)).get
  private def parseView(name: String, args: List[String]): CLI.Ns =
    CLI.parse(view(name).opts, view(name).pos, args)

  private def censusCli(root: JPath): CliResult = cli(List("shape", "census"), root)

  private val SESSION_ROOT = "session S = HOL +\n  theories\n    T\n"
  private val SHARED_ROOT =
    "session One = HOL +\n  theories\n    \"shared/Shared\"\n\n" +
    "session Two = HOL +\n  theories\n    \"shared/Shared\"\n"
  /* Four sessions over two readable theories and one unreadable one: `Prior`
     claims first, `Failed` would claim `Shared` but cannot be loaded (its
     `Broken.thy` is a directory), `Winner` claims `Shared` after the failure,
     `Again` finds nothing left. */
  private val ATOMIC_ROOT =
    "session Prior = HOL +\n  theories\n    Prior\n\n" +
    "session Failed = HOL +\n  theories\n    \"shared/Shared\"\n    Broken\n\n" +
    "session Winner = HOL +\n  theories\n    \"shared/Shared\"\n\n" +
    "session Again = HOL +\n  theories\n    \"shared/Shared\"\n"

  def run(): Unit = {

    /* ================================================================ */
    /* tests/test_census_sessions.py                                    */
    /* ================================================================ */

    /* -- Provenance -- */

    test("test_census_sessions.Provenance.test_records_carry_their_session") {
      val (recs, err, out) = runCensus(groups(
        "S1" -> (() => List(sec("Alpha", Some("S1")))),
        "S2" -> (() => List(sec("Beta", Some("S2"))))))
      equal(recs.map(r => str(r("session"))), List("S1", "S2"))
      equal(recs.map(r => str(r("theory"))), List("Alpha", "Beta"))
      equal(err, "")
      equal(out, Shape_Cmds.Census_Outcome(2, 2, 0, 2))
    }

    test("test_census_sessions.Provenance.test_session_is_null_without_one") {
      val (recs, _, _) = runCensus(groups("x" -> (() => List(sec("Alpha")))))
      check(recs.head.contains("session"), "session key absent")
      check(isNull(recs.head("session")), s"expected null session, got ${recs.head("session")}")
    }

    /* -- Isolation -- */

    test("test_census_sessions.Isolation.test_a_failing_session_is_skipped_not_fatal") {
      val (recs, err, out) = runCensus(groups(
        "Good1" -> (() => List(sec("Alpha", Some("Good1")))),
        "Bad" -> boom(),
        "Good2" -> (() => List(sec("Beta", Some("Good2"))))))
      equal(recs.map(r => str(r("session"))), List("Good1", "Good2"))
      equal(out, Shape_Cmds.Census_Outcome(3, 2, 1, 2))
      contains(err, "Bad")
      /* Python names `RuntimeError`; the JVM spelling of the same class. */
      contains(err, "RuntimeException")
      contains(err, "unparseable")
    }

    test("test_census_sessions.Isolation.test_a_load_failure_is_isolated_too") {
      val (_, err, out) = runCensus(groups("Bad" -> boom(new java.io.IOException("no such file"))))
      equal(out.skipped, 1)
      /* Python names `OSError`; the JVM spelling of the same failure. */
      contains(err, "IOException")
      contains(err, "no such file")
    }

    test("test_census_sessions.Isolation.test_warnings_never_touch_stdout") {
      val (out, err) = capture((o, e) =>
        Shape_Cmds.cmd_shape_census(o, e, groups("Bad" -> boom()), None))
      equal(out, "")
      check(err != "", "expected a diagnostic on stderr")
    }

    test("test_census_sessions.Isolation.test_broken_pipe_is_not_a_session_failure") {
      val exn = raises(capture((o, e) =>
        Shape_Cmds.cmd_shape_census(o, e, groups("S" -> (() => throw new Broken_Pipe)), None)))
      check(exn.isInstanceOf[Broken_Pipe], s"expected Broken_Pipe to propagate, got $exn")
    }

    /* -- Laziness -- */

    test("test_census_sessions.Laziness.test_sessions_load_one_at_a_time") {
      val order = mutable.ListBuffer.empty[String]
      def loader(name: String): () => List[Theory_Section] =
        () => { order += s"load:$name"; List(sec(name, Some(name))) }
      /* The record stream is observed as it is written, so the assertion can
         see that a later session is not loaded before an earlier one has been
         emitted — which the Python assertion on `order` alone cannot. */
      val line = new StringBuilder
      val text = new StringBuilder
      val writer = new Writer {
        def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
          val s = new String(cbuf, off, len)
          text ++= s
          for (c <- s) {
            if (c == '\n') {
              val rec = Jsonl.parse_object(line.toString).get
              order += s"emit:${str(rec("theory"))}"
              line.clear()
            }
            else line += c
          }
        }
        def flush(): Unit = ()
        def close(): Unit = ()
      }
      val gs = Iterator("Aaa", "Bbb", "Ccc").map(n => (n, loader(n)))
      Shape_Cmds.cmd_shape_census(new Out(writer), new Out(new StringWriter), gs, None)
      equal(order.filter(_.startsWith("load:")).toList, List("load:Aaa", "load:Bbb", "load:Ccc"))
      equal(order.toList,
        List("load:Aaa", "emit:Aaa", "load:Bbb", "emit:Bbb", "load:Ccc", "emit:Ccc"),
        "loads must interleave with emission")
      equal(text.toString.split("\n").length, 3)
    }

    test("test_census_sessions.Laziness.test_a_generator_of_groups_is_consumed_lazily") {
      val built = mutable.ListBuffer.empty[String]
      val gen: Groups = Iterator("Aaa", "Bbb").map { n =>
        built += n
        (n, () => List(sec(n, Some(n))))
      }
      check(built.isEmpty, "a lazy iterator must not be forced before the census runs")
      val (recs, _, _) = runCensus(gen)
      equal(built.toList, List("Aaa", "Bbb"))
      equal(recs.length, 2)
    }

    /* -- Dedup -- */

    test("test_census_sessions.Dedup.test_a_theory_claimed_twice_is_emitted_once") {
      withProject(Seq("shared/Shared.thy" -> thy("Shared"), "ROOT" -> SHARED_ROOT)) { root =>
        val sessions = Discovery.iter_sessions(root)
        equal(sessions.map(_.name), List("One", "Two"))
        val s = new CLI.Session(new Out(new StringWriter), new Out(new StringWriter))
        val seen = mutable.Set.empty[JPath]
        val gs: Groups = sessions.iterator.map(si => (si.name, () => s.sections_for_session(si, seen)))
        val (recs, _, out) = runCensus(gs)
        equal(recs.length, 1, "shared theory emitted more than once")
        equal(str(recs.head("session")), "One")   // first claimant wins
        equal(out.loaded, 2)                       // both sessions still ran
      }
      /* A session that fails to load claims NOTHING: `seen` is untouched, so
         the shared theory still goes to the next session that can load it,
         and the first-owner rule holds for the sessions that did load. */
      subcase("a failed session claims nothing; the next loadable session owns the theory") {
        withProject(Seq(
          "Prior.thy" -> thy("Prior"),
          "shared/Shared.thy" -> thy("Shared"),
          "ROOT" -> ATOMIC_ROOT)) { root =>
          Files.createDirectories(root.resolve("Broken.thy"))
          val sessions = Discovery.iter_sessions(root)
          equal(sessions.map(_.name), List("Prior", "Failed", "Winner", "Again"))
          val by = sessions.map(si => si.name -> si).toMap
          val s = new CLI.Session(new Out(new StringWriter), new Out(new StringWriter))
          val seen = mutable.Set.empty[JPath]

          val prior = s.sections_for_session(by("Prior"), seen)
          equal(prior.map(sec => (sec.theory, sec.session)), List(("Prior", Some("Prior"))))
          val snapshot = seen.toSet
          equal(snapshot.size, 1)

          raises(s.sections_for_session(by("Failed"), seen))
          equal(seen.toSet, snapshot, "a failed session must not claim theories")

          val winner = s.sections_for_session(by("Winner"), seen)
          equal(winner.map(sec => (sec.theory, sec.session)), List(("Shared", Some("Winner"))))

          val again = s.sections_for_session(by("Again"), seen)
          equal(again.map(_.theory), Nil, "a theory already owned must not be emitted again")
          equal(seen.toSet, Set(
            Discovery.real(root.resolve("Prior.thy")),
            Discovery.real(root.resolve("shared").resolve("Shared.thy"))))

          /* The same root through the CLI: the failure is reported, not
             hidden, and the records carry the owning sessions. */
          val r = censusCli(root)
          equal(r.exit, 0, r.err)
          contains(r.err, "1 of 4 session(s) skipped")
          contains(r.err, "Failed")
          val recs = jsonl(r.out)
          equal(recs.map(rec => (str(rec("theory")), str(rec("session")))),
            List(("Prior", "Prior"), ("Shared", "Winner")))
          equal(recs.map(rec => str(rec("theory"))).distinct.length, recs.length,
            "a theory emitted more than once")
        }
      }
    }

    test("test_census_sessions.Dedup.test_the_cli_shares_one_dedup_set_across_sessions") {
      withProject(Seq("shared/Shared.thy" -> thy("Shared"), "ROOT" -> SHARED_ROOT)) { root =>
        val r = censusCli(root)
        equal(r.exit, 0, r.err)
        val recs = jsonl(r.out)
        equal(recs.length, 1, "shared theory emitted more than once")
        equal(str(recs.head("session")), "One")
        equal(r.err, "")
      }
    }

    /* -- Resume -- */

    test("test_census_sessions.Resume.test_resume_skips_already_present_records") {
      withTempFile(".jsonl", "{\"theory\": \"Alpha\", \"lemma\": \"Alpha_a\"}\n") { path =>
        val (recs, _, _) = runCensus(groups(
          "S1" -> (() => List(sec("Alpha", Some("S1")))),
          "S2" -> (() => List(sec("Beta", Some("S2"))))), resume = Some(path))
        equal(recs.map(r => str(r("theory"))), List("Beta"))
      }
    }

    /* -- ExitContract -- */

    test("test_census_sessions.ExitContract.test_no_sessions_at_all_is_bad_root") {
      withProject(Seq("notes.txt" -> "hi")) { root =>
        val r = censusCli(root)
        equal(r.exit, CLI.EXIT_BAD_ROOT)
        contains(r.err, "no ROOT or ROOTS file")
      }
    }

    test("test_census_sessions.ExitContract.test_all_sessions_skipped_is_an_error") {
      /* Python replaced the loader with one that raises; here the declared
         theory `T` is a DIRECTORY named `T.thy`, which no loader can read. */
      withProject(Seq("ROOT" -> SESSION_ROOT)) { root =>
        Files.createDirectories(root.resolve("T.thy"))
        val r = censusCli(root)
        equal(r.exit, CLI.EXIT_BAD_ROOT, r.err)
        contains(r.err, "failed to load")
        equal(r.out, "")
      }
    }

    test("test_census_sessions.ExitContract.test_partial_failure_succeeds_but_says_so") {
      /* Python's flaky loader failed the first session and served the second;
         here S1 declares an unreadable theory and S2 a readable one. */
      withProject(Seq(
        "ROOT" -> ("session S1 = HOL +\n  theories\n    Broken\n\n" +
                   "session S2 = HOL +\n  theories\n    T\n"),
        "T.thy" -> thy("T"))) { root =>
        Files.createDirectories(root.resolve("Broken.thy"))
        val r = censusCli(root)
        equal(r.exit, 0, r.err)                                 // exit 0
        contains(r.err, "1 of 2 session(s) skipped")
        equal(r.out.split("\n").count(_.trim.nonEmpty), 1)     // the good session emitted
      }
    }

    test("test_census_sessions.ExitContract.test_loaded_but_zero_records_is_a_silent_honest_zero") {
      withProject(Seq(
        "T.thy" -> "theory T imports Main begin\ndefinition d :: nat where \"d = 0\"\nend\n",
        "ROOT" -> SESSION_ROOT)) { root =>
        val r = censusCli(root)
        equal(r.exit, 0, r.err)
        equal(r.out, "")
        equal(r.err, "")
      }
    }

    test("test_census_sessions.ExitContract.test_a_rootless_directory_of_theories_still_works") {
      withProject(Seq("Bare.thy" -> thy("Bare"))) { root =>
        val r = censusCli(root)
        equal(r.exit, 0, r.err)
        val recs = jsonl(r.out)
        equal(recs.map(r => str(r("theory"))), List("Bare"))
        check(isNull(recs.head("session")), "no ROOT, so no session name")
        equal(r.err, "")
      }
    }

    test("test_census_sessions.ExitContract.test_exit_codes_are_distinct") {
      check(CLI.EXIT_BAD_ROOT != CLI.EXIT_SIGPIPE, "exit codes collide")
      equal(CLI.EXIT_BAD_ROOT, 2)
      equal(CLI.EXIT_SIGPIPE, 141)   // 128 + SIGPIPE, as a shell reports
    }


    /* ================================================================ */
    /* tests/test_shape_cli.py                                          */
    /* ================================================================ */

    /* -- Wiring -- */

    test("test_shape_cli.Wiring.test_each_view_dispatches_to_its_handler") {
      /* No function pointers in the Scala grammar: the equivalent claim is that
         each view is a registered sub-command of `shape` AND that routing
         `shape VIEW` through the one dispatch path reaches that view's
         renderer, recognised by output only it produces. */
      val distinctive = Map(
        "summary" -> "# Proof shape summary",
        "steps" -> "location",
        "lemma" -> "No proof-bearing entry matching 'x'",
        "widest" -> "Top ",
        "census" -> "\"session\": null, \"theory\": \"Shape\"")
      equal(shapeGroup.subs.map(_.names.head), List("summary", "steps", "lemma", "widest", "census"))
      withProject(Seq("Shape.thy" -> SHAPE_THY)) { root =>
        for ((name, marker) <- distinctive) subcase(s"view=$name") {
          val argv = List("shape", name) ::: (if (name == "lemma") List("x") else Nil)
          val r = cli(argv, root)
          equal(r.exit, 0, r.err)
          contains(r.out, marker)
        }
      }
    }

    test("test_shape_cli.Wiring.test_bare_shape_has_a_help_fallback") {
      withProject(Seq("Shape.thy" -> SHAPE_THY)) { root =>
        val r = cli(List("shape"), root)
        equal(r.exit, 0, r.err)                       // not the top-level parser's rc 1
        contains(r.out, "Usage: isabelle query shape")
        contains(r.out, "shape commands:")
        notContains(r.out, "Usage: isabelle query [OPTIONS] COMMAND")
      }
    }

    test("test_shape_cli.Wiring.test_lemma_is_variadic_lookup") {
      equal(parseView("lemma", List("A", "B")).pos("name"), List("A", "B"))
    }

    test("test_shape_cli.Wiring.test_widest_metric_is_constrained") {
      equal(parseView("widest", List("--metric", "live")).str("metric"), Some("live"))
      val exn = raises(parseView("widest", List("--metric", "bogus")))
      check(exn.isInstanceOf[CLI.Usage_Error], s"expected a usage error, got $exn")
      withProject(Seq("Shape.thy" -> SHAPE_THY)) { root =>
        equal(cli(List("shape", "widest", "--metric", "bogus"), root).exit, 2)
      }
    }

    test("test_shape_cli.Wiring.test_widest_carries_trailing_paths") {
      equal(parseView("widest", List("A.thy", "B.thy")).pos("files"), List("A.thy", "B.thy"))
    }

    test("test_shape_cli.Wiring.test_steps_span_is_optional") {
      equal(parseView("steps", Nil).pos("span"), Nil)
      equal(parseView("steps", List("Foo:1..9")).pos("span"), List("Foo:1..9"))
    }

    test("test_shape_cli.Wiring.test_config_flags_only_on_record_views") {
      for (name <- List("steps", "lemma"))
        check(view(name).opts.exists(_.dest == "config"), s"$name lacks --config")
      for (name <- List("summary", "widest", "census"))
        check(!view(name).opts.exists(_.dest == "config"), s"$name must not take --config")
    }

    /* -- StepJsonSchema -- */

    test("test_shape_cli.StepJsonSchema.test_steps_json_keys_and_position") {
      val recs = jsonl(steps(List(shapeSec()), Some("Shape:62"), as_json = true))
      equal(recs.length, 1)
      val rec = recs.head
      equal(rec.keySet, STEP_KEYS)
      equal((str(rec("theory")), str(rec("lemma")), num(rec("line"))), ("Shape", "redundant", 62.0))
      equal(num(rec("w2_src")), 11.0)
    }

    test("test_shape_cli.StepJsonSchema.test_all_steps_flag_widens_the_stream") {
      val goals = jsonl(steps(List(shapeSec()), Some("Shape"), as_json = true))
      val every = jsonl(steps(List(shapeSec()), Some("Shape"), as_json = true, all_steps = true))
      check(goals.forall(r => str(r("kind")) == "goal"), "default stream must be goals only")
      check(every.length > goals.length, s"${every.length} <= ${goals.length}")
      check(every.exists(r => str(r("kind")) == "closing"), "no closing step in --all")
    }

    test("test_shape_cli.StepJsonSchema.test_config_gates_frame_columns") {
      val cfg = Toml.read_corpus_configs(fixtures.resolve("m3_configs.toml"))("Shape")
      val without = jsonl(steps(List(shapeSec()), Some("Shape:92"), as_json = true)).head
      val withcfg = jsonl(steps(List(shapeSec()), Some("Shape:92"), as_json = true, cfg = Some(cfg))).head
      check(!without.contains("frame_ratio"), "frame_ratio present without a config")
      equal(num(withcfg("frame_ratio")), 5.0)     // m3_wide
    }

    test("test_shape_cli.StepJsonSchema.test_widest_json_is_ranked_records") {
      val recs = jsonl(stdout(o => Shape_Cmds.cmd_shape_widest(o, List(shapeSec()), 3, "w2", true)))
      equal(recs.head.keySet, STEP_KEYS)
      val w2s = recs.map(r => num(r("w2_src")))
      equal(w2s, w2s.sorted.reverse)
      equal(str(recs.head("lemma")), "m3_wide")   // the 51-token step
    }

    /* -- SummaryJsonSchema -- */

    test("test_shape_cli.SummaryJsonSchema.test_summary_json_is_per_proof_records") {
      val recs = jsonl(stdout(o => Shape_Cmds.cmd_shape_summary(o, List(shapeSec()), true, "proof", "all")))
      check(recs.nonEmpty, "no summary records")
      for (r <- recs) equal(r.keySet, SUMMARY_KEYS, str(r("lemma")))
      val red = recs.find(r => str(r("lemma")) == "redundant").get
      equal((num(red("n_goals")), num(red("w2_src_max")), num(red("w1_est_max"))), (3.0, 11.0, 2.0))
      check(math.abs(num(red("dag_ratio_est_max")) - 33.0 / 8) < 1e-7, s"dag ${red("dag_ratio_est_max")}")
      equal(num(red("ratio")), 2.0)
    }

    /* -- Widest -- */

    test("test_shape_cli.Widest.test_ranked_by_w2") {
      val out = stdout(o => Shape_Cmds.cmd_shape_widest(o, List(shapeSec()), 2, "w2", false))
      val first = out.split("\n").find(_.trim.startsWith("51")).getOrElse(
        throw new AssertionError(s"no 51-wide row in:\n$out"))
      contains(first, "m3_wide")
    }

    test("test_shape_cli.Widest.test_metric_live_reranks") {
      val recs = jsonl(stdout(o => Shape_Cmds.cmd_shape_widest(o, List(shapeSec()), 3, "live", true)))
      val lives = recs.map(r => num(r("live")))
      equal(lives, lives.sorted.reverse)
    }

    /* -- StepsScoping -- */

    test("test_shape_cli.StepsScoping.test_locus_scopes_to_lines") {
      val recs = jsonl(steps(List(shapeSec()), Some("Shape:60..62"), as_json = true))
      equal(recs.map(r => num(r("line"))), List(60.0, 61.0, 62.0))
    }

    test("test_shape_cli.StepsScoping.test_bare_theory_name_scopes_to_theory") {
      val recs = jsonl(steps(List(shapeSec()), Some("Shape"), as_json = true))
      check(recs.nonEmpty, "no steps")
      check(recs.forall(r => str(r("theory")) == "Shape"), "a step outside the theory")
    }

    test("test_shape_cli.StepsScoping.test_bad_span_exits") {
      val exn = raises(steps(List(shapeSec()), Some("NoSuchThing")))
      check(exn.isInstanceOf[Exit_Code], s"expected Exit_Code, got $exn")
    }

    /* -- Lemma -- */

    test("test_shape_cli.Lemma.test_footer_reports_the_aggregate") {
      val out = stdout(o => Shape_Cmds.cmd_shape_lemma(o, List(shapeSec()), "redundant", false, None))
      contains(out, "3 goals (0 bare)")
      contains(out, "M6 widest block")      // the extension curve prints
      contains(out, "dag:max 4.12")
    }

    test("test_shape_cli.Lemma.test_unknown_name_is_reported_not_crashed") {
      val out = stdout(o => Shape_Cmds.cmd_shape_lemma(o, List(shapeSec()), "does_not_exist", false, None))
      contains(out, "No proof-bearing entry")
    }

    /* -- Census -- */

    def shapeGroups(): Groups = groups("Shape" -> (() => List(shapeSec())))

    test("test_shape_cli.Census.test_streams_one_record_per_proof") {
      val (recs, _, _) = runCensus(shapeGroups())
      val lemmas = recs.map(r => str(r("lemma"))).toSet
      check(lemmas("redundant"), "redundant missing")
      // flat_proof has a step (the `by`) but no goals — still one record.
      check(lemmas("flat_proof"), "flat_proof missing")
    }

    test("test_shape_cli.Census.test_resume_skips_done_entries") {
      withTempFile(".jsonl", "{\"theory\": \"Shape\", \"lemma\": \"redundant\"}\n") { path =>
        val (recs, _, _) = runCensus(shapeGroups(), resume = Some(path))
        val lemmas = recs.map(r => str(r("lemma"))).toSet
        check(!lemmas("redundant"), "redundant should be skipped")
        check(lemmas("framing"), "framing should remain")   // others remain
      }
    }

    test("test_shape_cli.Census.test_load_done_tolerates_a_truncated_final_line") {
      /* `load_done` is private here, so it is observed through `--resume`: a
         theory with lemmas `a` and `b`, a done-file naming `a` on a good line
         and `b` on a line killed mid-write.  Only `a` may be skipped. */
      val T =
        "theory T imports Main begin\n\n" +
        "lemma a: \"P a\"\nproof -\n  show \"P a\" by simp\nqed\n\n" +
        "lemma b: \"P b\"\nproof -\n  show \"P b\" by simp\nqed\n\nend\n"
      withTempFile(".jsonl",
        "{\"theory\": \"T\", \"lemma\": \"a\"}\n" +
        "{\"theory\": \"T\", \"lemma\": \"b\"") { path =>          // killed mid-write
        val (recs, err, out) = runCensus(groups("T" -> (() => List(parse(T)))), resume = Some(path))
        equal(err, "")
        equal(out.skipped, 0)
        equal(recs.map(r => str(r("lemma"))), List("b"))         // the good line only
        /* The original assertion, exactly: `_load_done(path) == {("T", "a")}`.
           `load_done` is private, so it is reached by reflection; a stray key
           the resume path happens not to consult would still be caught here. */
        val m = Shape_Cmds.getClass.getDeclaredMethods.find(_.getName == "load_done").getOrElse(
          throw new AssertionError("Shape_Cmds.load_done not found by reflection"))
        m.setAccessible(true)
        val done = m.invoke(Shape_Cmds, path).asInstanceOf[Set[(String, String)]]
        equal(done, Set(("T", "a")))                              // the good line only
      }
    }
  }
}
