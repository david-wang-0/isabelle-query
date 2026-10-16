package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import CliSupport.*

/* `show --premises`: one line per premise, keyed by how a proof cites it
   [show-premises].  Expected values are upstream's, read off the Isar
   grammar rather than off the implementation. */
private[regression] object Cli_show_premises {
  private def rows(text: String): (List[(String, String, String, String)], Int, Int) = {
    val st = Premises.read_statement(text).get
    (st.premises.map(p => (p.kind, p.cite, p.label, p.text)), st.conclusions, st.cases)
  }

  private def show(name: String): List[String] = {
    val ss = List(parse(CliFixtures.test_show_premises_THY, "T"))
    lines(capture((o, e) => Commands.cmd_show(o, e, ss, name, Flags(premises = true))).out)
  }

  def run(): Unit = {
    /* --- LongForm --- */
    test("test_show_premises.LongForm.test_numbering_runs_across_elements_and_groups") {
      equal(rows("""lemma foo [simp]:
  fixes x :: "nat"
  assumes wf: "well_formed M" "P \<Longrightarrow> Q" and \<open>0 < q\<close>
  assumes k2: "2 \<le> k"
  defines "d \<equiv> x"
  shows c: "A" and "B (x::nat)"
  by simp"""), (List(
        ("assumes", "assms(1)", "wf(1)", "well_formed M"),
        ("assumes", "assms(2)", "wf(2)", "P \\<Longrightarrow> Q"),
        ("assumes", "assms(3)", "", "0 < q"),
        ("assumes", "assms(4)", "k2", "2 \\<le> k"),
        ("defines", "", "", "d \\<equiv> x")), 2, 0))
    }
    test("test_show_premises.LongForm.test_bare_propositions") {
      /* Isar accepts a one-token proposition unquoted. */
      equal(rows("theorem T4:\n  assumes transitivity\n  shows Quasitransit\n  by metis"),
        (List(("assumes", "assms(1)", "", "transitivity")), 1, 0))
    }
    test("test_show_premises.LongForm.test_keyword_against_a_cartouche") {
      equal(rows("lemma x: assumes h:\\<open>a\\<close> \\<open>b\\<close>\n" +
          "  shows\\<open>c\\<close> by simp"),
        (List(("assumes", "assms(1)", "h(1)", "a"),
          ("assumes", "assms(2)", "h(2)", "b")), 1, 0))
    }
    test("test_show_premises.LongForm.test_obtains_counts_cases") {
      equal(rows("theorem t: assumes \"A\" obtains x where \"P x\" | y where " +
          "\"Q y\" using assms by blast"),
        (List(("assumes", "assms(1)", "", "A")), 0, 2))
    }

    /* --- ShortForm --- */
    test("test_show_premises.ShortForm.test_if_premises_are_that") {
      val (got, n, _) = rows("""lemma bar: "C" if h: "P" and "Q" for x by simp""")
      equal((got, n), (List(("if", "that(1)", "h", "P"), ("if", "that(2)", "", "Q")), 1))
    }
    test("test_show_premises.ShortForm.test_no_premises") {
      equal(rows("""lemma simple: "x = x" .."""), (Nil, 1, 0))
    }
    test("test_show_premises.ShortForm.test_several_conclusions_are_not_split") {
      /* Each conclusion would have premises of its own. */
      equal(rows("""lemma "A \<Longrightarrow> B" "C""""), (Nil, 2, 0))
    }

    /* --- PremisesInsideTheTerm --- */
    test("test_show_premises.PremisesInsideTheTerm.test_outermost_implications_only") {
      equal(Premises.split_imp("""A \<Longrightarrow> (B \<Longrightarrow> C) \<Longrightarrow> D"""),
        List("A", "(B \\<Longrightarrow> C)", "D"))
    }
    test("test_show_premises.PremisesInsideTheTerm.test_bracket_list") {
      equal(Premises.split_imp("""\<lbrakk>a; b \<le> c\<rbrakk> \<Longrightarrow> R"""),
        List("a", "b \\<le> c", "R"))
    }
    test("test_show_premises.PremisesInsideTheTerm.test_an_outer_meta_quantifier_is_stripped") {
      equal(Premises.split_imp("""\<And>x. P x \<Longrightarrow> Q x"""), List("P x", "Q x"))
    }
    test("test_show_premises.PremisesInsideTheTerm.test_a_later_quantifier_scopes_the_rest") {
      equal(Premises.split_imp("""A \<Longrightarrow> \<And>x. B x \<Longrightarrow> C"""),
        List("A", "\\<And>x. B x \\<Longrightarrow> C"))
    }
    test("test_show_premises.PremisesInsideTheTerm.test_rel_fun_is_not_an_implication") {
      equal(Premises.split_imp("(R ===> S) f g ==> T"), List("(R ===> S) f g", "T"))
    }
    test("test_show_premises.PremisesInsideTheTerm.test_keyed_after_the_named_premises") {
      val (got, _, _) = rows("""lemma m: assumes "P" shows "Q \<Longrightarrow> R"""")
      equal(got, List(("assumes", "assms(1)", "", "P"), ("term", "", "", "Q")))
    }

    /* --- TheView --- */
    test("test_show_premises.TheView.test_aligned_and_counted") {
      equal(show("lin").drop(1), List(
        "  assms(1) wf:  well_formed M",
        "  assms(2):     0 < q",
        "  shows: 3 conclusions"))
    }
    test("test_show_premises.TheView.test_term_premises") {
      equal(show("arrow").drop(1), List("  term 1:  P", "  shows: 1 conclusion"))
    }
    test("test_show_premises.TheView.test_not_a_goal") {
      equal(show("d").drop(1), List("  (no goal statement: a DEF)"))
    }
    test("test_show_premises.TheView.test_the_header_is_the_usual_one") {
      val h = show("lin").head
      check(h.startsWith("--- lin (LEMMA) — T.thy"), h)
    }

    /* --- TheFlag --- */
    test("test_show_premises.TheFlag.test_excludes_the_other_slices") {
      withProject(Seq("T.thy" -> CliFixtures.test_show_premises_THY)) { root =>
        for (other <- List("--statement", "-V")) subcase(s"other=$other") {
          val r = cli(List("show", "--premises", other, "x"), root)
          equal(r.exit, 2)
          contains(r.err, "not allowed with")
        }
      }
    }
  }
}
