package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import CliSupport.*

/** Upstream `tests/test_sites_instances.py` (0.9.2): `instances`, where a
  * locale or class is instantiated [instantiation-sites] [midline-interpret].
  * The fixture theories are the upstream bytes; every expectation is the
  * upstream hand-computed one, line numbers included.  The three
  * `TheClosureIsBuiltOnce` IDs pin a cost with mocks upstream; here the same
  * claim is observed through `Reach`'s closure memo (one closure per request,
  * none for a name declared nowhere). */
private[regression] object Cli_sites_instances {
  val SITES_FIX: String = "theory Sites_Fix\n  imports Main\nbegin\n\nsection \\<open>A heading that says interpretation magma is not a command\\<close>\n\nlocale magma =\n  fixes f :: \"'a \\<Rightarrow> 'a \\<Rightarrow> 'a\"\n\nlocale semi = magma +\n  assumes assoc: \"f (f x y) z = f x (f y z)\"\n\nlocale magma\\<^sub>2 =\n  fixes h :: \"'a \\<Rightarrow> 'a\"\n\nlocale \"open\" =\n  fixes g :: \"'a \\<Rightarrow> 'a\"\n\nclass mynull =\n  fixes null :: 'a\n\ninterpretation nat_magma: magma \"(+)\" ..\n\nglobal_interpretation int_magma: magma \"(*)\" ..\n\nsublocale semi \\<subseteq> magma f ..\n\nsublocale magma < dual: magma \"\\<lambda>x y. f y x\" ..\n\ninterpretation m2: magma\\<^sub>2 id ..\n\ninterpretation q: \"open\" id ..\n\nlemma uses_interpret: \"True\"\nproof -\n  interpret loc: magma \"(+)\" ..\n  show ?thesis by simp\nqed\n\ninstantiation nat :: mynull\nbegin\ndefinition null_nat :: nat where \"null_nat = (0::nat)\"\ninstance ..\nend\n\ninstantiation bool :: \"{mynull, ord}\"\nbegin\ndefinition null_bool :: bool where \"null_bool = False\"\ninstance ..\nend\n\ninstance prod :: (mynull, mynull) mynull ..\n\ntext \\<open>\n  interpretation magma \"(-)\" ..\n\\<close>\n\nlemma decoy: \"True\"\n  (* interpretation magma \"(-)\" .. *)\n  by simp\n\n\\<^cancel>\\<open>interpretation magma \"(div)\" ..\\<close>\n\nend\n"
  val NAMES_FIX: String = "theory Names_Fix\n  imports Main\nbegin\n\nlocale plain =\n  fixes p :: \"'a \\<Rightarrow> 'a\"\n\nlocale holder =\n  fixes q :: \"'a \\<Rightarrow> 'a\"\nbegin\n\ninterpretation plain q ..\n\nsublocale sub: plain q ..\n\nend\n\ninterpretation plain id ..\n\nsublocale holder \\<subseteq> inner: plain q ..\n\nlemma anon_interpret: \"True\"\nproof -\n  interpret plain id ..\n  show ?thesis by simp\nqed\n\nend\n"
  val CLOSURE_FIX: String = "theory Closure_Fix\n  imports Main\nbegin\n\nclass hasb =\n  fixes b :: 'a\n\nclass base = hasb +\n  assumes base_refl: \"b = b\"\n\nclass mid = base +\n  assumes mid_refl: \"b = b\"\n\nclass leaf = mid +\n  assumes leaf_refl: \"b = b\"\n\nclass side = hasb +\n  assumes side_refl: \"b = b\"\n\nclass both = side + leaf\n\nclass qmid = Closure_Fix.base +\n  assumes qmid_refl: \"b = b\"\n\nclass alt = hasb +\n  assumes alt_refl: \"b = b\"\nbegin\nsubclass base\n  by standard simp\nend\n\ninstantiation nat :: leaf\nbegin\ndefinition b_nat :: nat where \"b_nat = 0\"\ninstance by standard simp\nend\n\ninstantiation int :: base\nbegin\ndefinition b_int :: int where \"b_int = 0\"\ninstance by standard simp\nend\n\ninstantiation bool :: side\nbegin\ndefinition b_bool :: bool where \"b_bool = False\"\ninstance by standard simp\nend\n\ninstantiation prod :: (hasb, hasb) hasb\nbegin\ndefinition b_prod :: \"'a \\<times> 'b\" where \"b_prod = (b, b)\"\ninstance ..\nend\n\ninstance prod :: (both, both) both\n  by standard simp\n\ninstantiation \"fun\" :: (type, mid) mid\nbegin\ndefinition b_fun :: \"'a \\<Rightarrow> 'b\" where \"b_fun = (\\<lambda>_. b)\"\ninstance by standard simp\nend\n\ninstantiation unit :: qmid\nbegin\ndefinition b_unit :: unit where \"b_unit = ()\"\ninstance by standard simp\nend\n\ninstantiation option :: (alt) alt\nbegin\ndefinition b_option :: \"'a option\" where \"b_option = Some b\"\ninstance by standard simp\nend\n\ntext \\<open>\n  class ghost = base +\n    fixes g :: 'a\n\\<close>\n\n(* class ghost2 = base + fixes g :: 'a *)\n\n\\<^cancel>\\<open>instantiation char :: base begin instance .. end\\<close>\n\nend\n"
  val ROOT: String = "session Fix = HOL +\n  theories\n    Sites_Fix\n    Names_Fix\n    Closure_Fix\n"
  val CHAIN_FIX: String = "theory Chain_Fix\n  imports Main\nbegin\n\nlocale ring0 =\n  fixes r :: \"'a \\<Rightarrow> 'a\"\n  assumes ax: \"r x = x\"\n\nlemma chained:\n  assumes \"ring0 r\"\n  shows \"True\"\nproof -\n  from assms interpret a: ring0 r .\n  have \"ring0 r\" using assms by simp then interpret b: ring0 r .\n  with assms interpret c: ring0 r .\n  show ?thesis by simp\nqed\n\nlemma decoys:\n  assumes \"ring0 r\"\n  shows \"True\"\nproof -\n  have \"ring0 r \\<and> (interpret \\<longleftrightarrow> interpret)\" sorry\n  \\<comment> \\<open>then interpret d: ring0 r\\<close>\n  note interpret_foo = assms\n  show ?thesis by simp\nqed\n\nend\n"

  def heads(text: String): List[String] = Sites.expression_heads(text, blank_terms(text))
  def instances(text: String): List[(String, String)] =
    Sites.expression_instances(text, blank_terms(text))
  def arity(text: String): List[String] = Sites.arity_classes(text, blank_terms(text))
  def parts(text: String): (String, String) = Sites.arity_parts(text, blank_terms(text))
  def ext(text: String): List[String] = Sites.extends_heads(text, blank_terms(text))

  def fixture(body: List[Theory_Section] => Unit): Unit =
    withProject(Seq("Sites_Fix.thy" -> SITES_FIX, "Names_Fix.thy" -> NAMES_FIX,
      "Closure_Fix.thy" -> CLOSURE_FIX, "ROOT" -> ROOT)) { root => body(load(root)) }
  def chain(body: List[Theory_Section] => Unit): Unit =
    withProject(Seq("Chain_Fix.thy" -> CHAIN_FIX,
      "ROOT" -> "session Fix = HOL +\n  theories\n    Chain_Fix\n")) { root => body(load(root)) }

  def loci(found: List[Sites.Site]): List[String] = found.map(s => s"${s.theory}:${s.line}")
  def inst(ss: List[Theory_Section], name: String): List[Sites.Site] =
    Sites.find_instantiations(ss, name)
  def trans(ss: List[Theory_Section], name: String): List[(Sites.Site, String)] =
    Sites.find_instantiations_transitive(ss, name)
  def cmd(ss: List[Theory_Section], name: String, flags: Flags = Flags()): String =
    capture((o, e) => Sites.cmd_instances(o, e, ss, name, flags)).out
  /* Exit 1, a diagnostic on stderr and NOTHING on stdout. */
  def unresolved(ss: List[Theory_Section], name: String, flags: Flags = Flags()): String = {
    val r = capture((o, e) => Sites.cmd_instances(o, e, ss, name, flags))
    equal(r.exit, 1, "exit")
    equal(r.out, "", "stdout")
    r.err
  }
  def resolve(ss: List[Theory_Section], name: String): Either[String, Sites.Subject] =
    Sites.resolve(ss, name, Sites.locale_tags, "a locale or class")
  def split(line: String): List[String] = line.trim.split("\\s+").toList

  def run(): Unit = {
    /* -- TheGrammars ------------------------------------------------------ */
    test("test_sites_instances.TheGrammars.test_expression_heads") {
      List[(String, List[String])](
        (" magma f", List("magma")),
        (" add: comm_monoid plus 0", List("comm_monoid")),
        (" weak?: weak_complete_lattice", List("weak_complete_lattice")),
        (" \"and\": semilattice_neutr \\<open>(AND)\\<close>", List("semilattice_neutr")),
        (" q: \"open\" id", List("open")),
        (" m2: magma\\<^sub>2 id", List("magma\\<^sub>2")),
        (" Groups.monoid plus", List("Groups.monoid")),
        (" L1 x + qual: L2 y", List("L1", "L2")),
        (" folding \"\\<lambda>x. x + 1\" 0", List("folding"))
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(heads(text), want) } }
    }
    test("test_sites_instances.TheGrammars.test_expression_instances") {
      List[(String, List[(String, String)])](
        (" nat_magma: magma \"(+)\" ", List("nat_magma" -> "magma")),
        (" \"and\": semilattice_neutr x", List("and" -> "semilattice_neutr")),
        (" weak?: weak_complete_lattice x", List("weak" -> "weak_complete_lattice")),
        (" magma f", List("" -> "magma")),
        (" L1 x + q: L2 y", List("" -> "L1", "q" -> "L2"))
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(instances(text), want) } }
    }
    test("test_sites_instances.TheGrammars.test_arity_classes") {
      List[(String, List[String])](
        (" nat :: mynull", List("mynull")),
        // Argument sorts are CONSTRAINTS, not instantiations.
        (" prod :: (exhaustive, exhaustive) exhaustive", List("exhaustive")),
        (" bool :: \"{order_bot, order_top, linorder}\" ", List("order_bot", "order_top", "linorder")),
        (" \"fun\" :: (type, ord) ord", List("ord")),
        (" \"fun\" :: (\"{equal,exhaustive}\", exhaustive) exhaustive", List("exhaustive")),
        // A class inclusion and a bare `instance ..` have no `::`.
        (" bifinite \\<subseteq> profinite", Nil),
        (" ..", Nil)
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(arity(text), want) } }
    }
    test("test_sites_instances.TheGrammars.test_arity_parts") {
      List[(String, (String, String))](
        (" prod :: (topological_space, topological_space) topological_space",
          ("prod", "(topological_space, topological_space) topological_space")),
        (" \"fun\" :: (type, ord) ord", ("fun", "(type, ord) ord")),
        // Verbatim: a quoted brace sort stays quoted.
        (" bool :: \"{mynull, ord}\" ", ("bool", "\"{mynull, ord}\""))
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(parts(text), want) } }
    }
    test("test_sites_instances.TheGrammars.test_denotes") {
      check(Sites.denotes("monoid", "monoid"))
      check(Sites.denotes("Groups.monoid", "monoid"))
      check(!Sites.denotes("foo_monoid", "monoid"))
    }

    /* -- TheEngineOnTheFixture -------------------------------------------- */
    test("test_sites_instances.TheEngineOnTheFixture.test_the_fixture_loaded_whole") {
      fixture { ss => equal(ss.map(_.theory), List("Sites_Fix", "Names_Fix", "Closure_Fix")) }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_magma") {
      fixture { ss =>
        val found = inst(ss, "magma")
        equal(loci(found), List("Sites_Fix:22", "Sites_Fix:24", "Sites_Fix:26",
          "Sites_Fix:28", "Sites_Fix:36"))
        equal(found.map(_.kind), List("interpretation", "global_interpretation",
          "sublocale", "sublocale", "interpret"))
        equal(found.map(_.name), List("nat_magma", "int_magma", "semi", "dual", "loc"))
        // Nothing at or past the `text` block (55), the comment (59) or the
        // `\<^cancel>` (62): those are not live command positions.
        check(found.forall(_.line < 55))
      }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_extending_is_not_instantiating") {
      // `locale semi = magma + ...` (line 10) supplies nothing to magma.
      fixture { ss => check(!inst(ss, "magma").map(_.line).contains(10)) }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_a_sublocale_is_a_site_of_its_target_only") {
      fixture { ss => equal(inst(ss, "semi"), Nil) }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_a_symbol_bearing_and_a_quoted_locale_name") {
      fixture { ss =>
        equal(loci(inst(ss, "magma\\<^sub>2")), List("Sites_Fix:30"))
        equal(loci(inst(ss, "open")), List("Sites_Fix:32"))
      }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_mynull") {
      fixture { ss =>
        val found = inst(ss, "mynull")
        equal(loci(found), List("Sites_Fix:40", "Sites_Fix:46", "Sites_Fix:52"))
        equal(found.map(_.kind), List("instantiation", "instantiation", "instance"))
        equal(found.map(_.name), List("nat", "bool", "prod"))
        // The bare `instance ..` closing each block (43, 49) is not a second site.
        equal(found.map(_.label(true)),
          List("nat :: mynull", "bool :: \"{mynull, ord}\"", "prod :: (mynull, mynull) mynull"))
        equal(found.map(_.label(false)), List("nat", "bool", "prod"))
      }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_the_name_column_fallback_chain") {
      fixture { ss =>
        val found = inst(ss, "plain")
        equal(loci(found), List("Names_Fix:12", "Names_Fix:14", "Names_Fix:18",
          "Names_Fix:20", "Names_Fix:24"))
        // The enclosing block, the written qualifier, the honest `?`, the
        // qualifier over the sublocale target, the enclosing entry.
        equal(found.map(_.name), List("holder", "sub", "?", "inner", "anon_interpret"))
        // An interpretation writes no sort.
        equal(found.map(_.label(true)), found.map(_.label(false)))
      }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_a_bare_interpretation_is_not_named_after_the_locale") {
      fixture { ss =>
        val by_line = inst(ss, "plain").map(s => s.line -> s).toMap
        equal(by_line(18).name, "?")
        equal(by_line(20).name, "inner")
      }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_the_hierarchy_theory_direct_sites") {
      fixture { ss =>
        equal(loci(inst(ss, "base")), List("Closure_Fix:38"))
        equal(loci(inst(ss, "side")), List("Closure_Fix:44"))
      }
    }
    test("test_sites_instances.TheEngineOnTheFixture.test_the_source_cell_is_the_raw_line") {
      fixture { ss =>
        val by_line = inst(ss, "magma").map(s => s.line -> s).toMap
        equal(by_line(22).text, "interpretation nat_magma: magma \"(+)\" ..")
      }
    }

    /* -- SubjectResolution -------------------------------------------------- */
    test("test_sites_instances.SubjectResolution.test_a_locale_and_a_class_resolve") {
      fixture { ss =>
        equal(resolve(ss, "magma").map(_.tag), Right("LOCALE"))
        equal(resolve(ss, "mynull").map(_.tag), Right("CLASS"))
        equal(resolve(ss, "magma").map(_.how), Right(""))
      }
    }
    test("test_sites_instances.SubjectResolution.test_an_unknown_name_is_a_reason") {
      fixture { ss =>
        equal(resolve(ss, "no_such_thing"),
          Left("'no_such_thing' is not a locale or class declared in this project"))
      }
    }
    test("test_sites_instances.SubjectResolution.test_a_wrong_kinded_name_names_its_kind_and_theory") {
      fixture { ss =>
        equal(resolve(ss, "uses_interpret"),
          Left("'uses_interpret' is a LEMMA in Sites_Fix, not a locale or class"))
      }
    }

    /* -- TheCommand --------------------------------------------------------- */
    test("test_sites_instances.TheCommand.test_counts") {
      fixture { ss =>
        List("semi" -> "0\n", "magma" -> "5\n", "mynull" -> "3\n", "base" -> "1\n").foreach {
          case (name, want) => subcase(s"name=$name") { equal(cmd(ss, name, Flags(mode = "count")), want) }
        }
      }
    }
    test("test_sites_instances.TheCommand.test_names_are_bare_loci") {
      fixture { ss =>
        equal(cmd(ss, "mynull", Flags(mode = "names")),
          "Sites_Fix:40\nSites_Fix:46\nSites_Fix:52\n")
        // Nothing at all for a known subject with no sites.
        equal(cmd(ss, "semi", Flags(mode = "names")), "")
      }
    }
    test("test_sites_instances.TheCommand.test_the_table_byte_for_byte") {
      fixture { ss =>
        equal(cmd(ss, "base"),
          "1 instantiation(s) of base:\n\n  Closure_Fix:38  int  instantiation  instantiation int :: base\n")
      }
    }
    test("test_sites_instances.TheCommand.test_the_padded_columns") {
      // Locus 12, name 9 (`nat_magma`), kind 21 (`global_interpretation`),
      // each followed by two spaces; the source cell is stripped and never padded.
      fixture { ss =>
        val got = lines(cmd(ss, "magma"))
        equal(got(0), "5 instantiation(s) of magma:")
        equal(got(1), "")
        equal(got(2), "  Sites_Fix:22  nat_magma  interpretation         interpretation nat_magma: magma \"(+)\" ..")
        equal(got(4), "  Sites_Fix:26  semi       sublocale              sublocale semi \\<subseteq> magma f ..")
        check(!got.exists(_.endsWith(" ")))
      }
    }
    test("test_sites_instances.TheCommand.test_an_honest_zero") {
      fixture { ss => equal(cmd(ss, "semi"), "No instantiations found for 'semi'.\n") }
    }
    test("test_sites_instances.TheCommand.test_the_name_column_end_to_end") {
      fixture { ss =>
        val names = lines(cmd(ss, "plain")).drop(2).map(split(_)(1))
        equal(names, List("holder", "sub", "?", "inner", "anon_interpret"))
      }
    }
    test("test_sites_instances.TheCommand.test_sorts_is_written_text") {
      fixture { ss =>
        val on = cmd(ss, "mynull", Flags(sorts = true))
        for (cell <- List("nat :: mynull", "bool :: \"{mynull, ord}\"", "prod :: (mynull, mynull) mynull"))
          contains(on, s"  $cell  ")
        val off = lines(cmd(ss, "mynull")).drop(2).map(split(_)(1))
        equal(off, List("nat", "bool", "prod"))
      }
    }
    test("test_sites_instances.TheCommand.test_an_unknown_subject_is_a_refusal") {
      fixture { ss =>
        val err = unresolved(ss, "no_such_locale_xyz")
        contains(err, "'no_such_locale_xyz' is not a locale or class declared in this project")
        notContains(err, "ERROR:")
      }
    }
    test("test_sites_instances.TheCommand.test_a_wrong_kinded_subject_is_a_refusal") {
      fixture { ss =>
        contains(unresolved(ss, "uses_interpret"),
          "'uses_interpret' is a LEMMA in Sites_Fix, not a locale or class")
      }
    }
    test("test_sites_instances.TheCommand.test_count_mode_on_an_unresolved_subject_prints_nothing") {
      // Never a plausible `0`: the refusal happens before the mode switch.
      fixture { ss => unresolved(ss, "no_such_locale_xyz", Flags(mode = "count")); () }
    }

    /* -- TheParser ---------------------------------------------------------- */
    test("test_sites_instances.TheParser.test_the_flags") {
      val ns = args("instances", "L", "-c", "--names", "--sorts")
      check(ns.bool("count") && ns.bool("names") && ns.bool("sorts"))
      check(!ns.bool("recursive"))
      equal(ns.pos("name"), List("L"))
    }
    test("test_sites_instances.TheParser.test_count_beats_names") {
      val f = CLI.flags_of(args("instances", "L", "--names", "-c"))
      equal(f.mode, "count")
      check(!f.sorts)
      check(CLI.flags_of(args("instances", "L", "--sorts")).sorts)
    }
    test("test_sites_instances.TheParser.test_no_reach_flag") {
      // The import-visibility filter is unconditional here: an
      // `interpretation L` in a theory that cannot see L interprets a
      // different L, and there is no name-only answer worth offering.
      raises(args("instances", "L", "--reach", "name")) match {
        case e: CLI.Usage_Error => contains(e.message, "--reach")
        case e => check(false, s"not a usage error: $e")
      }
      withProject(Seq("ROOT" -> ROOT, "Sites_Fix.thy" -> SITES_FIX)) { root =>
        val r = query(root, "instances", "L", "--reach", "name")
        equal(r.exit, 2)
        contains(r.err, "--reach")
      }
    }
    test("test_sites_instances.TheParser.test_at_least_one_subject") {
      raises(args("instances")); ()
    }
    test("test_sites_instances.TheParser.test_recursive") {
      check(args("instances", "L", "-r").bool("recursive"))
      check(CLI.flags_of(args("instances", "L", "-rc")).recursive)
    }

    /* -- TheHierarchy ------------------------------------------------------- */
    test("test_sites_instances.TheHierarchy.test_extends_heads") {
      List[(String, List[String])](
        ("class X = A + B + fixes f :: 'a", List("A", "B")),
        ("class X = A + assumes eq: \"a = b\" ", List("A")),
        ("class X = A + constrains f :: 'a", List("A")),
        ("class X = A + notes foo = bar", List("A")),
        ("class X = A", List("A")),
        ("class X = fixes f :: 'a", Nil),
        ("locale X = q: \"open\" id + Groups.monoid plus", List("open", "Groups.monoid")),
        ("locale X = folding \"\\<lambda>x. x + 1\" 0", List("folding"))
      ).foreach { case (text, want) => subcase(s"text=$text") { equal(ext(text), want) } }
    }
    test("test_sites_instances.TheHierarchy.test_extenders") {
      fixture { ss =>
        def ex(n: String) = Sites.extenders(ss, n).sorted
        equal(ex("hasb"), List("alt", "base", "side"))
        equal(ex("base"), List("alt", "mid", "qmid"))
        equal(ex("mid"), List("leaf"))
        equal(ex("leaf"), List("both"))
        equal(ex("side"), List("both"))
        equal(ex("both"), Nil)
      }
    }
    test("test_sites_instances.TheHierarchy.test_decoys_are_not_extenders") {
      // `class ghost = base +` in a `text` block, `ghost2` in a comment.
      fixture { ss =>
        val ex = Sites.extenders(ss, "base")
        check(!ex.contains("ghost"))
        check(!ex.contains("ghost2"))
      }
    }
    test("test_sites_instances.TheHierarchy.test_descendants") {
      fixture { ss =>
        def d(n: String) = Sites.descendants(ss, n).sorted
        equal(d("base"), List("alt", "both", "leaf", "mid", "qmid"))
        equal(d("mid"), List("both", "leaf"))
        equal(d("side"), List("both"))
        equal(d("both"), Nil)
        // A class never has its own parent among its descendants.
        check(!d("base").contains("hasb"))
      }
    }
    test("test_sites_instances.TheHierarchy.test_a_locale_is_not_its_own_descendant") {
      // `semi` extends magma twice over (its header, and the `sublocale` at
      // Sites_Fix:26); `sublocale magma < dual: magma ...` is a self-edge that
      // neither loops nor makes magma its own descendant.
      fixture { ss => equal(Sites.descendants(ss, "magma").sorted, List("semi")) }
    }
    test("test_sites_instances.TheHierarchy.test_the_transitive_sites_of_base") {
      fixture { ss =>
        val rows = trans(ss, "base")
        equal(loci(rows.map(_._1)), List("Closure_Fix:32", "Closure_Fix:38", "Closure_Fix:56",
          "Closure_Fix:59", "Closure_Fix:65", "Closure_Fix:71"))
        equal(rows.map(_._2), List("leaf", "base", "both", "mid", "qmid", "alt"))
        equal(rows.map(_._1.kind), List("instantiation", "instantiation", "instance",
          "instantiation", "instantiation", "instantiation"))
        equal(rows.map(_._1.name), List("nat", "int", "prod", "fun", "unit", "option"))
        // The sibling (44) and the parent's own arity (50) are not among them.
        check(!rows.map(_._1.line).contains(44))
        check(!rows.map(_._1.line).contains(50))
      }
    }
    test("test_sites_instances.TheHierarchy.test_the_transitive_sites_of_the_rest") {
      fixture { ss =>
        List(
          "mid" -> List("Closure_Fix:32", "Closure_Fix:56", "Closure_Fix:59"),
          "side" -> List("Closure_Fix:44", "Closure_Fix:56"),
          "both" -> List("Closure_Fix:56"),
          "hasb" -> List("Closure_Fix:32", "Closure_Fix:38", "Closure_Fix:44",
            "Closure_Fix:50", "Closure_Fix:56", "Closure_Fix:59",
            "Closure_Fix:65", "Closure_Fix:71")
        ).foreach { case (name, want) =>
          subcase(s"name=$name") { equal(loci(trans(ss, name).map(_._1)), want) }
        }
      }
    }
    test("test_sites_instances.TheHierarchy.test_a_leaf_of_the_hierarchy_is_its_direct_listing") {
      fixture { ss =>
        for (name <- List("both", "qmid", "alt"))
          subcase(s"name=$name") { equal(loci(trans(ss, name).map(_._1)), loci(inst(ss, name))) }
      }
    }
    test("test_sites_instances.TheHierarchy.test_no_dedup_pass_is_needed") {
      // `sublocale semi \<subseteq> magma f ..` (26) is both a site of magma
      // and the edge that brings semi's (nonexistent) sites in: it appears
      // exactly once, and every row is via magma itself.
      fixture { ss =>
        val rows = trans(ss, "magma")
        equal(loci(rows.map(_._1)), loci(inst(ss, "magma")))
        equal(rows.count(_._1.line == 26), 1)
        equal(rows.map(_._2).toSet, Set("magma"))
        equal(trans(ss, "semi"), Nil)
      }
    }

    /* -- TheClosureIsBuiltOnce ---------------------------------------------
       Upstream counts header reads and `_Visibility` constructions with
       mocks.  Here the import closure is the only thing that reads a theory
       header, and `Reach` memoises it per section list; so "each header is
       read at most once" and "one visibility per request" are both "the
       request built exactly one closure", and "no closure" is "none". */
    test("test_sites_instances.TheClosureIsBuiltOnce.test_each_header_is_read_at_most_once") {
      fixture { ss =>
        Reach.clear_cache()
        val rows = trans(ss, "base")
        equal(rows.length, 6)
        equal(Reach.cached_closures, 1)
      }
    }
    test("test_sites_instances.TheClosureIsBuiltOnce.test_one_visibility_per_request") {
      fixture { ss =>
        Reach.clear_cache()
        val rows = trans(ss, "hasb")
        equal(rows.length, 8)
        equal(Reach.cached_closures, 1)
      }
    }
    test("test_sites_instances.TheClosureIsBuiltOnce.test_a_name_declared_nowhere_builds_no_closure") {
      fixture { ss =>
        Reach.clear_cache()
        val admits = Reach.site_filter(ss, "nothing_declares_this")
        check(admits("Closure_Fix"))
        equal(Reach.cached_closures, 0)
      }
    }

    /* -- TheTransitiveCommand ----------------------------------------------- */
    def rcmd(ss: List[Theory_Section], name: String, flags: Flags = Flags()): String =
      cmd(ss, name, flags.copy(recursive = true))
    test("test_sites_instances.TheTransitiveCommand.test_counts") {
      fixture { ss =>
        List("base" -> "6\n", "mid" -> "3\n", "side" -> "2\n", "both" -> "1\n",
          "magma" -> "5\n", "hasb" -> "8\n").foreach { case (name, want) =>
          subcase(s"name=$name") { equal(rcmd(ss, name, Flags(mode = "count")), want) }
        }
      }
    }
    test("test_sites_instances.TheTransitiveCommand.test_the_default_form_is_untouched") {
      fixture { ss => equal(cmd(ss, "base", Flags(mode = "count")), "1\n") }
    }
    test("test_sites_instances.TheTransitiveCommand.test_names") {
      fixture { ss =>
        equal(rcmd(ss, "base", Flags(mode = "names")),
          "Closure_Fix:32\nClosure_Fix:38\nClosure_Fix:56\nClosure_Fix:59\nClosure_Fix:65\nClosure_Fix:71\n")
      }
    }
    test("test_sites_instances.TheTransitiveCommand.test_the_table_with_its_via_column") {
      // Locus 14, name 6 (`option`), kind 13 (`instantiation`), VIA 4
      // (`leaf` / `both` / `qmid`), each followed by two spaces.  Line 56 wraps
      // its proof onto the next line, so its source cell ends at `both`.
      fixture { ss =>
        equal(rcmd(ss, "base"), "6 instantiation(s) of base (transitive):\n\n  Closure_Fix:32  nat     instantiation  leaf  instantiation nat :: leaf\n  Closure_Fix:38  int     instantiation  base  instantiation int :: base\n  Closure_Fix:56  prod    instance       both  instance prod :: (both, both) both\n  Closure_Fix:59  fun     instantiation  mid   instantiation \"fun\" :: (type, mid) mid\n  Closure_Fix:65  unit    instantiation  qmid  instantiation unit :: qmid\n  Closure_Fix:71  option  instantiation  alt   instantiation option :: (alt) alt\n")
      }
    }
    test("test_sites_instances.TheTransitiveCommand.test_sorts_is_orthogonal") {
      fixture { ss =>
        val got = rcmd(ss, "base", Flags(sorts = true))
        contains(got, "  nat :: leaf  ")
        equal(lines(got).drop(2).map(split(_).head), List("Closure_Fix:32", "Closure_Fix:38",
          "Closure_Fix:56", "Closure_Fix:59", "Closure_Fix:65", "Closure_Fix:71"))
      }
    }
    test("test_sites_instances.TheTransitiveCommand.test_the_exit_contract_is_the_same_question") {
      fixture { ss =>
        equal(rcmd(ss, "semi"), "No instantiations found for 'semi'.\n")
        for (name <- List("no_such_locale_xyz", "uses_interpret"))
          subcase(s"name=$name") { unresolved(ss, name, Flags(recursive = true)); () }
      }
    }

    /* -- MidLineInterpret [midline-interpret] -------------------------------
       `interpret` at 13 (after `from assms`), 14 (after a whole `have ... by
       simp then`, whose `by` must not cut the header short) and 15 (after
       `with assms`).  The decoys: a term that spells `interpret` (23, blank in
       the outer view), a comment (24) and a fact name that starts with the
       word (25). */
    test("test_sites_instances.MidLineInterpret.test_a_chained_interpret_is_a_site") {
      chain { ss =>
        equal(Sites.find_instantiations(ss, "ring0").map(s => (s.line, s.kind, s.name)),
          List((13, "interpret", "a"), (14, "interpret", "b"), (15, "interpret", "c")))
      }
    }
    test("test_sites_instances.MidLineInterpret.test_the_command_reader") {
      equal(Sites.inst_command("  then interpret x: L"), Some(("interpret", 7, 16)))
      equal(Sites.inst_command("  interpretation L"), Some(("interpretation", 2, 16)))
      for (line <- List("  note interpret_foo = a", "  using M.interpret",
        "interpret_foo", "  then show ?thesis"))
        subcase(s"line=$line") { equal(Sites.inst_command(line), None) }
    }
  }
}
