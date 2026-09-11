package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphTheoryRefs {
  def run(): Unit = {
    test("test_theory_refs.CountsAreCitingEntries.test_a_name_used_by_one_entry_counts_one") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      contains(refs("Top"), "mid_fact  (1)")
      }
    }
    test("test_theory_refs.CountsAreCitingEntries.test_a_name_used_by_two_entries_counts_two") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      contains(refs("Top"), "helper  (2)")
      }
    }
    test("test_theory_refs.OwnershipFollowsTheImportClosure.test_a_local_declaration_shadows_an_imported_one") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      val out = refs("Mid"); contains(out, "Base"); contains(out, "[direct import]")
      }
    }
    test("test_theory_refs.OwnershipFollowsTheImportClosure.test_a_visible_declaration_beats_an_earlier_invisible_one") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      val out = refs("Top"); contains(out, "Base"); notContains(out, "A_Base")
      }
    }
    test("test_theory_refs.OwnershipFollowsTheImportClosure.test_the_decoy_sorts_first") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      check(sections.indexWhere(_.theory == "A_Base") < sections.indexWhere(_.theory == "Base"))
      }
    }
    test("test_theory_refs.OwnershipFollowsTheImportClosure.test_the_naive_rule_really_would_get_this_wrong") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(Usage_Graph.entry_by_name(sections)("helper")._1, "A_Base")
      }
    }
    test("test_theory_refs.OwnershipFollowsTheImportClosure.test_the_owning_theorys_import_depth_is_reported") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      val out = refs("Top"); contains(out.linesIterator.find(_.trim.startsWith("Base ")).get, "[import depth 1]"); contains(out.linesIterator.find(_.trim.startsWith("Mid ")).get, "[direct import]")
      }
    }
    test("test_theory_refs.TerseModes.test_a_theory_that_references_nothing_says_so") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      contains(refs("Base"), "makes no references")
      }
    }
    test("test_theory_refs.TerseModes.test_an_unknown_theory_is_reported") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      val result = cli(List("refs", "Nope"), root); equal(result.exit, 1); contains(result.err, "no theory 'Nope'")
      }
    }
    test("test_theory_refs.TerseModes.test_count_is_the_distinct_name_total") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(refs("Top", List("-c")).trim, "2")
      }
    }
    test("test_theory_refs.TerseModes.test_external_drops_the_theorys_own_declarations") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      List("Top", "Mid", "Base").foreach { theory =>
      note(s"theory=$theory")
      val everything = refs(theory, List("-c")).trim.toInt
      val external = refs(theory, List("-c", "--external")).trim.toInt
      check(external <= everything, theory)
      }
      }
    }
    test("test_theory_refs.TerseModes.test_names_prints_bare_names_one_per_line") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(refs("Top", List("--names")).trim.split("\\s+").toList, List("helper", "mid_fact"))
      }
    }
    test("test_theory_refs.TheDeclaredVersusCitedComparison.test_a_cited_theory_that_is_not_a_direct_import_is_named") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      contains(refs("Top"), "Cited but not directly imported (1): Base")
      }
    }
    test("test_theory_refs.TheDeclaredVersusCitedComparison.test_a_theory_citing_only_direct_imports_reports_neither") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      val out = refs("Mid"); notContains(out, "Direct imports no citation reaches"); notContains(out, "Cited but not directly imported")
      }
    }
    test("test_theory_refs.TheDeclaredVersusCitedComparison.test_an_import_no_citation_reaches_is_named") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      contains(refs("Top"), "Direct imports no citation reaches (1): Spare")
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_a_repeat_does_not_duplicate_a_section") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(scope(sections, List("Mid", "Mid"))._1.map(_.theory), List("Mid"))
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_a_thy_suffix_resolves") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(scope(sections, List("Mid.thy"))._1.map(_.theory), List("Mid"))
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_an_unknown_theory_is_reported_not_silently_empty") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      val (kept, err) = scope(sections, List("Nope")); equal(kept, Nil); contains(err, "'Nope' not found")
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_no_flag_leaves_the_index_alone") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(scope(sections, Nil)._1.size, sections.size)
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_one_theory_narrows_to_it") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(scope(sections, List("Mid"))._1.map(_.theory), List("Mid"))
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_several_theories_union") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(scope(sections, List("Mid", "Base"))._1.map(_.theory).sorted, List("Base", "Mid"))
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_the_dest_does_not_collide_with_the_theory_positional") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(parsed("refs", List("Top")).pos("theory"), List("Top"))
      }
    }
    test("test_theory_refs.TheoryScopeOnFind.test_the_flag_parses_and_is_repeatable") {
      withProject(GraphFixtures.files("test_theory_refs.TREE")) { root =>
      val sections = load(root)
      def refs(theory: String, args: List[String] = Nil) = cli(List("refs", theory) ::: args, root).out
      equal(parsed("find", List("x", "--theory", "A", "--theory", "B")).list("theory_scope"), List("A", "B"))
      }
    }
  }
}
