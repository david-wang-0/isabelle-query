package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_disambig_names {
  def run(): Unit = {
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
