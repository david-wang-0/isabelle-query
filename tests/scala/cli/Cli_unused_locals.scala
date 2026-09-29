package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

/* `unused --locals` [unused-locals]: the port of `tests/test_unused_locals.py`.
   The fixtures are upstream's, byte for byte; `EXPECTED` is its nine
   hand-read rows. */
private[regression] object Cli_unused_locals {
  val LOCALS_FIX: String = """theory Locals_Fix
  imports Main
begin

lemma flat: "True"
proof -
  have a: "True" by simp
  have b: "True" by simp
  have c: "True" using b by simp
  note d = a
  have e: "True" by simp
  then have "True" by simp
  show ?thesis by simp (* using c *)
qed

lemma shadow: "True"
proof -
  have f: "True" by simp
  have f: "True" using f by simp
  have g: "True"
  proof -
    have f: "True" by simp
    show ?thesis by simp
  qed
  show ?thesis using f g by simp
qed

lemma branches: "x \<or> \<not> x"
proof (cases x)
  case True
  have h: "x" using True by simp
  show ?thesis using h by simp
next
  case False
  have h: "\<not> x" using False by simp
  show ?thesis using False by simp
qed

lemma binders: "True"
proof -
  obtain y :: nat where k: "y = y" and k2: "y = 0" by simp
  have "y = 0" using k2 .
  define m where "m = (0::nat)"
  define n where "n = (1::nat)"
  have "n = 1" unfolding n_def by simp
  let ?p = "True"
  let ?q = "True"
  have ?q by simp
  have s [simp]: "True" by simp
  have t [rule_format]: "True" by simp
  show ?thesis by simp
qed

lemma calc: "True"
proof -
  {
    have p: "True" by simp
  }
  have q: "True" by simp
  moreover have r: "True" by simp
  ultimately show ?thesis by simp
qed

end
"""

  val EXPECTED: List[(Int, String, String, String)] = List(
    (9, "c", "have", "flat"),
    (10, "d", "note", "flat"),
    (22, "f", "have", "shadow"),
    (35, "h", "have", "branches"),
    (41, "k", "obtain", "binders"),
    (43, "m", "define", "binders"),
    (46, "?p", "let", "binders"),
    (50, "t", "have", "binders"),
    (57, "p", "have", "calc"))

  val CORNERS: String = """theory Corners
  imports Main
begin

lemma from_this: "True"
proof -
  have a: "True" by simp
  from this show ?thesis by simp
qed

lemma attr_args: "True"
proof -
  note defs = TrueI
  have b [unfolded defs]: "True" by simp
  show ?thesis using b by simp
qed

lemma qualified: "True"
proof -
  have c: "True" by simp
  show ?thesis using local.c by simp
qed

lemma define_for: "True"
proof -
  define f where "f i = (i::nat)" for i
  have "f 0 = 0" by (simp add: f_def)
  show ?thesis by simp
qed

lemma sup_markup: "True"
proof -
  let ?R = "\<lambda>x y. x = (y::nat)"
  have "?R\<^sup>*\<^sup>* 0 0" by simp
  show ?thesis by simp
qed

lemma text_inside: "True"
proof -
  have d: "True" by simp
  text \<open>A remark in the middle of a proof.\<close>
  show ?thesis using d by simp
qed

lemma dup_label: "True"
proof -
  obtain x :: nat where e: "x = x" and e: "x = 0" by simp
  show ?thesis using e by simp
qed

lemma greek_define: "True"
proof -
  define \<epsilon> where eps: "\<epsilon> = (0::nat)"
  have "\<epsilon> = 0" using eps by simp
  show ?thesis by simp
qed

end
"""

  val EXTENT: String = """theory Extent
  imports Main
begin

lemma before: "True"
proof -
  have u: "True" by simp
  show ?thesis using u by simp
qed

instance nat :: foo
proof
  have v: "True" by simp
  show "True" by simp
qed

lemma sub: "True \<and> True"
  apply (rule conjI)
  subgoal by simp
  subgoal proof -
    have w: "True" by simp
    show ?thesis by simp
  qed
  done

lemma remark: "True"
proof -
  have x: "True" by simp
  text \<open>A remark in the middle of a proof.\<close>
  show ?thesis using x by simp
qed

lemma prose_decl: "True"
proof -
  have y: "True" by simp
  text \<open>As in
lemma foo, which this mirrors.\<close>
  show ?thesis using y by simp
qed

end
"""

  /* upstream's `_Root`: a one-session root over the given theories */
  private def project[A](theories: (String, String)*)(body: JPath => A): A =
    withProject(theories.map { case (n, t) => s"$n.thy" -> t } :+
      ("ROOT" -> ("session Fix = HOL +\n  theories\n" +
        theories.map(t => s"    ${t._1}\n").mkString)))(body)

  private def unread(root: JPath): List[(Int, String, String, String)] =
    Proof_Locals.unread_locals(load(root)).map { case (_, b) => (b.line, b.name, b.kind, b.entry) }

  /* (stdout, exit status) of `unused ARGV` */
  private def run_cli(root: JPath, argv: String*): (String, Int) = {
    val r = cli("unused" :: argv.toList, root)
    (r.out, r.exit)
  }

  private def extent_entry(root: JPath, name: String): (Theory_Section, Entry) = {
    val sec = load(root).head
    (sec, entry(sec, name))
  }

  private def path(root: JPath): String = root.resolve("Locals_Fix.thy").toString

  def run(): Unit = {
    /* -- HandReadFixture ------------------------------------------------ */
    test("test_unused_locals.HandReadFixture.test_exactly_the_hand_read_rows") {
      project("Locals_Fix" -> LOCALS_FIX) { root => equal(unread(root), EXPECTED) }
    }
    test("test_unused_locals.HandReadFixture.test_count") {
      project("Locals_Fix" -> LOCALS_FIX) { root => equal(run_cli(root, "--locals", "-c"), ("9\n", 0)) }
    }
    test("test_unused_locals.HandReadFixture.test_keep_names_deliberate_ones") {
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        equal(run_cli(root, "--locals", "-c", "--keep", "c,d")._1, "7\n")
      }
    }
    test("test_unused_locals.HandReadFixture.test_rows_are_pasteable_loci") {
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        val first = lines(run_cli(root, "--locals")._1).head.trim.split("\\s+").toList
        equal(first, List("Locals_Fix:9", "c", "have", "flat"))
      }
    }

    /* -- Selection: PATH selects PROOFS --------------------------------- */
    test("test_unused_locals.Selection.test_entry_selector") {
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        equal(run_cli(root, "--locals", "-c", s"${path(root)}:shadow"), ("1\n", 0))
      }
    }
    test("test_unused_locals.Selection.test_window_selects_whole_proofs") {
      // 30..31 lies inside `branches`; its unread `h` is on line 35.
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        val out = run_cli(root, "--locals", s"${path(root)}:30..31")._1
        contains(out, "Locals_Fix:35")
        equal(lines(out).length, 1)
      }
    }
    test("test_unused_locals.Selection.test_two_selectors_on_one_file_add_up") {
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        equal(run_cli(root, "--locals", "-c", s"${path(root)}:shadow",
          s"${path(root)}:calc")._1, "2\n")
      }
    }
    test("test_unused_locals.Selection.test_unknown_entry_is_unresolved") {
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        equal(run_cli(root, "--locals", s"${path(root)}:nosuch")._2, 1)
      }
    }
    test("test_unused_locals.Selection.test_paths_need_locals") {
      // Entry-level `unused` is corpus-wide by contract: no PATH scoping.
      project("Locals_Fix" -> LOCALS_FIX) { root => equal(run_cli(root, path(root))._2, 2) }
    }
    test("test_unused_locals.Selection.test_locals_rejects_cascade_flags") {
      project("Locals_Fix" -> LOCALS_FIX) { root =>
        equal(run_cli(root, "--locals", "-r")._2, 2)
        equal(run_cli(root, "--locals", "--roots")._2, 2)
      }
    }

    /* -- Corners: each was a false report on the AFP before its rule ----- */
    test("test_unused_locals.Corners.test_only_the_shadowed_duplicate") {
      // Every lemma but the last reads its binding.  In the last, the second
      // `e` shadows the first, so the first cannot be reached by any reader.
      project("Corners" -> CORNERS) { root =>
        equal(unread(root), List((47, "e", "obtain", "dup_label")))
      }
    }

    /* -- Extent: the walk stops where the entry's OWN proof does --------- */
    test("test_unused_locals.Extent.test_a_following_instance_proof_is_not_the_lemmas") {
      // `v` is unread, but it is the `instance`'s, which is no entry: it
      // must not be reported as a binding of `before`.
      project("Extent" -> EXTENT) { root =>
        val (sec, e) = extent_entry(root, "before")
        equal(Proof_Locals.proof_end(sec, e), 9)
        check(!unread(root).exists(_._2 == "v"))
      }
    }
    test("test_unused_locals.Extent.test_an_apply_script_runs_past_its_subgoals") {
      project("Extent" -> EXTENT) { root =>
        val (sec, e) = extent_entry(root, "sub")
        equal(Proof_Locals.proof_end(sec, e), 24)
        check(unread(root).contains((21, "w", "have", "sub")))
      }
    }
    test("test_unused_locals.Extent.test_a_text_block_does_not_end_the_proof") {
      project("Extent" -> EXTENT) { root =>
        val (sec, e) = extent_entry(root, "remark")
        equal(Proof_Locals.proof_end(sec, e), 31)
        // [body-end-text]: the parser's own extent agrees, where it used to
        // stop at the `text` on line 29.
        equal(e.body_end_line, 31)
      }
    }
    test("test_unused_locals.Extent.test_parser_and_walk_agree_on_where_proofs_close") {
      // Two walks of one structure: `Entries.proof_close_line` (for
      // `body_end_line`) and `Proof_Locals` (which also tracks names).  They
      // must not drift.  The plain outer view, as upstream's test passes it.
      project("Extent" -> EXTENT) { root =>
        val sec = load(root).head
        val outer = sec.outer_source
        for (e <- sec.entries if e.proof_line != 0) subcase(e.name) {
          equal(Entries.proof_close_line(outer, e.proof_line, e.thy_end),
            Proof_Locals.proof_end(sec, e), e.name)
        }
      }
    }
  }
}
