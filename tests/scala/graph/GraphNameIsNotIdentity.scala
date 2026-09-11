package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphNameIsNotIdentity {
  def run(): Unit = {
    test("test_name_is_not_identity.AnOwnerBelongsToTheFileItNames.test_grep_owners") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      ownersBelong(sections, Commands.grep_sections(sections, java.util.regex.Pattern.compile("True")).map(h => (h.path, h.owner)))
      }
    }
    test("test_name_is_not_identity.AnOwnerBelongsToTheFileItNames.test_method_owners") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      ownersBelong(sections, Usage_Graph.scan_methods(sections, Some("simp"))._2.map(h => (h.path, h.owner)))
      }
    }
    test("test_name_is_not_identity.AnOwnerBelongsToTheFileItNames.test_the_fixture_really_collides") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(sections.count(_.theory == "Preliminaries"), 2); equal(sections.count(_.theory == "Base"), 2)
      }
    }
    test("test_name_is_not_identity.CollidingCorpus.test_the_fixture_really_collides") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(sections.count(_.theory == "Preliminaries"), 2); equal(sections.count(_.theory == "Base"), 2)
      }
    }
    test("test_name_is_not_identity.EachSectionKeepsItsOwnIndex.test_def_sites") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(Usage_Graph.build_def_sites(sections, None).size, sections.size)
      }
    }
    test("test_name_is_not_identity.EachSectionKeepsItsOwnIndex.test_line_index") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(Usage_Graph.build_line_index(sections).size, sections.size)
      }
    }
    test("test_name_is_not_identity.EachSectionKeepsItsOwnIndex.test_noise_ranges") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(Usage_Graph.noise_ranges(sections).size, sections.size)
      }
    }
    test("test_name_is_not_identity.EachSectionKeepsItsOwnIndex.test_prose_is_prose_only_in_the_file_that_wrote_it") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      val noise = Usage_Graph.noise_ranges(sections)
      check(noise(sec("alpha/Preliminaries").path).exists { case (lo, hi) => lo <= 6 && 6 <= hi })
      check(!noise(sec("beta/Preliminaries").path).exists { case (lo, hi) => lo <= 6 && 6 <= hi })
      }
    }
    test("test_name_is_not_identity.EachSectionKeepsItsOwnIndex.test_the_fixture_really_collides") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(sections.count(_.theory == "Preliminaries"), 2); equal(sections.count(_.theory == "Base"), 2)
      }
    }
    test("test_name_is_not_identity.EachSectionKeepsItsOwnIndex.test_the_owner_of_a_line_comes_from_that_file") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      val idx = Usage_Graph.build_line_index(sections)
      equal(Usage_Graph.entry_at_line(idx(sec("alpha/Preliminaries").path), 6).get.name, "a_tail")
      equal(Usage_Graph.entry_at_line(idx(sec("beta/Preliminaries").path), 6).get.name, "b_cites")
      }
    }
    test("test_name_is_not_identity.ExternalSkipsFilesNotNames.test_a_citation_outside_the_declaring_file_survives") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(Usage.find_callers(sections, "target", external = true).map(h => (h._1.theory, h._2)), List(("Preliminaries", 6)))
      }
    }
    test("test_name_is_not_identity.ExternalSkipsFilesNotNames.test_the_fixture_really_collides") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(sections.count(_.theory == "Preliminaries"), 2); equal(sections.count(_.theory == "Base"), 2)
      }
    }
    test("test_name_is_not_identity.SuppressionIsTheWorstHalf.test_exactly_one_citation") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(Usage.find_callers(sections, "target").size, 1)
      }
    }
    test("test_name_is_not_identity.SuppressionIsTheWorstHalf.test_the_call_graph_agrees_with_callers") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(Usage_Graph.build_call_graph(sections).callers.get("target"), Some(Set("b_cites")))
      }
    }
    test("test_name_is_not_identity.SuppressionIsTheWorstHalf.test_the_fixture_really_collides") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      equal(sections.count(_.theory == "Preliminaries"), 2); equal(sections.count(_.theory == "Base"), 2)
      }
    }
    test("test_name_is_not_identity.SuppressionIsTheWorstHalf.test_the_live_citation_is_found") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      check(Usage.find_callers(sections, "target").map(h => (h._1.theory, h._2)).contains(("Preliminaries", 6)))
      }
    }
    test("test_name_is_not_identity.SuppressionIsTheWorstHalf.test_the_prose_mention_is_not") {
      withProject(GraphFixtures.files("test_name_is_not_identity.FILES")) { root =>
      val sections = load(root)
      def sec(rel: String) = sections.find(_.path == root.resolve(rel + ".thy")).get
      Usage.find_callers(sections, "target").foreach(h => notContains(h._3, "a paragraph about"))
      }
    }
  }
}
