package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_disambig_names {
  def run(): Unit = {
    /* [label-depth]: a ROOT may spell a theory with a directory.  Upstream's
       layout: `SL/Automation.thy` and `Other/Automation.thy` are `Automation`;
       `VEB` and `VEB2` each spell a copy `"SL/Automation"`.  Under D15 the
       copies are named by their leaf, so all four theories are `Automation`
       here and the labels come out the same as upstream's. */
    val SEGMENTS = List(   // directory -> (theory as the ROOT spells it, file under it)
      "SL" -> ("Automation", "Automation"), "Other" -> ("Automation", "Automation"),
      "VEB" -> ("\"SL/Automation\"", "SL/Automation"),
      "VEB2" -> ("\"SL/Automation\"", "SL/Automation"))
    def segments(body: (JPath, List[Theory_Section], Map[JPath, String], String => JPath) => Unit): Unit =
      withProject(SEGMENTS.zipWithIndex.flatMap { case ((dir, (spelled, rel)), i) =>
        List(s"$dir/$rel.thy" ->
          s"theory Automation\nimports Main\nbegin\nlemma l$i: \"True\" by simp\nend\n",
          s"$dir/ROOT" -> s"session $dir = HOL +\n  theories\n    $spelled\n")
      }) { root =>
        val ss = load(root)
        body(root, ss, Render.theory_labels(ss),
          dir => Discovery.real(root.resolve(s"$dir/${SEGMENTS.toMap.apply(dir)._2}.thy")))
      }
    test("test_disambig_names.APathSpelledNameIsSegments.test_the_fixture_has_path_spelled_names") {
      // D15: the ROOT's `"SL/Automation"` declares a theory named by its leaf.
      segments { (_, ss, _, _) =>
        equal(ss.map(_.theory).sorted, List("Automation", "Automation", "Automation", "Automation"))
      }
    }
    disposition("test_disambig_names.APathSpelledNameIsSegments.test_the_fixture_has_path_spelled_names",
      "known-difference", "D15 in dev/DIVERGENCES.md: a ROOT path spelling names its theory by the Isabelle leaf, so the four theories are Automation x4 rather than Python's Automation x2 + SL/Automation x2; the labels, uniqueness and round-trip IDs beside it still hold.")
    test("test_disambig_names.APathSpelledNameIsSegments.test_no_two_theories_share_a_label") {
      segments { (_, _, labels, _) => equal(labels.values.toSet.size, 4) }
    }
    test("test_disambig_names.APathSpelledNameIsSegments.test_the_labels") {
      segments { (root, _, labels, path) =>
        equal(labels(path("SL")), s"${Discovery.real(root).getFileName}/SL/Automation")
        equal(labels(path("Other")), "Other/Automation")
        // A real path suffix, not `VEB/SL/SL/Automation`.
        equal(labels(path("VEB")), "VEB/SL/Automation")
        equal(labels(path("VEB2")), "VEB2/SL/Automation")
      }
    }
    test("test_disambig_names.APathSpelledNameIsSegments.test_every_label_round_trips") {
      segments { (_, ss, labels, path) =>
        for ((dir, _) <- SEGMENTS) subcase(dir) {
          val sec = Commands.resolve_theory(ss, labels(path(dir)))
          check(sec.nonEmpty)
          equal(sec.get.real_path, path(dir))
        }
      }
    }
    test("test_disambig_names.Degenerate.test_no_sections") {

    locally {
equal(Render.theory_labels(Nil),Map.empty)
    }
    }
    test("test_disambig_names.Degenerate.test_the_same_section_twice_is_one_theory") {

    locally {
source("theory Solo\nimports Main\nbegin\nlemma l0: \"True\" by simp\nend\n","Solo"){(root,sec)=>equal(Render.theory_labels(List(sec,sec)).values.toList,List("Solo"))}
    }
    }
    test("test_disambig_names.OnlyACollisionGrowsAPrefix.test_a_colliding_name_is_qualified") {

    locally {
withProject(CliFixtures.test_disambig_names_FILES.toSeq){root=>val ss=load(root);def label(rel:String):String=Render.theory_labels(ss)(Discovery.real(root.resolve(rel+".thy")));equal(label("alpha/Examples"),"alpha/Examples");equal(label("beta/Examples"),"beta/Examples")}
    }
    }
    test("test_disambig_names.OnlyACollisionGrowsAPrefix.test_a_unique_name_stays_bare") {

    locally {
withProject(CliFixtures.test_disambig_names_FILES.toSeq){root=>val ss=load(root);def label(rel:String):String=Render.theory_labels(ss)(Discovery.real(root.resolve(rel+".thy")));equal(label("alpha/Unique"),"Unique")}
    }
    }
    test("test_disambig_names.OnlyACollisionGrowsAPrefix.test_every_label_is_unique") {

    locally {
withProject(CliFixtures.test_disambig_names_FILES.toSeq){root=>val ss=load(root);def label(rel:String):String=Render.theory_labels(ss)(Discovery.real(root.resolve(rel+".thy")));val ls=Render.theory_labels(ss);equal(ls.values.toSet.size,ls.size)}
    }
    }
    test("test_disambig_names.OnlyACollisionGrowsAPrefix.test_the_shared_root_prefix_is_not_shown") {

    locally {
withProject(CliFixtures.test_disambig_names_FILES.toSeq){root=>val ss=load(root);def label(rel:String):String=Render.theory_labels(ss)(Discovery.real(root.resolve(rel+".thy")));for(rel<-List("alpha/Examples","beta/Examples","alpha/Unique")) notContains(label(rel),root.getFileName.toString)}
    }
    }
    test("test_disambig_names.ScopedToTheCorpusNotTheScreen.test_a_collision_qualifies_even_when_shown_alone") {

    locally {
withProject(CliFixtures.test_disambig_names_FILES.toSeq){root=>val ss=load(root);def label(rel:String):String=Render.theory_labels(ss)(Discovery.real(root.resolve(rel+".thy")));val one=ss.filter(_.theory=="Examples").take(1);contains(Render.theory_labels(ss)(one.head.real_path),"/");notContains(Render.theory_labels(one)(one.head.real_path),"/")}
    }
    }
    test("test_disambig_names.TheLabelIsValidInput.test_a_qualified_label_round_trips") {

    locally {
withProject(CliFixtures.test_disambig_names_FILES.toSeq){root=>val ss=load(root);def label(rel:String):String=Render.theory_labels(ss)(Discovery.real(root.resolve(rel+".thy")));for(rel<-List("alpha/Examples","beta/Examples","alpha/Unique")){val sec=Commands.resolve_theory(ss,label(rel));check(sec.nonEmpty);equal(sec.get.real_path,Discovery.real(root.resolve(rel+".thy")))}}
    }
    }
  }
}
