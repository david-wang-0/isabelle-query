package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_bad_root {
  def run(): Unit = {
    test("test_bad_root.Diagnosis.test_directory_with_neither_root_nor_theories") {

    locally {
withProject(Map("notes.txt" -> "nothing to see\n").toSeq){root=>val msg=CLI.diagnose_empty_root(root);contains(msg,"no ROOT or ROOTS file");contains(msg,"no .thy files");}
    }
    }
    test("test_bad_root.Diagnosis.test_missing_directory") {

    locally {
withProject(Nil){root=>equal(CLI.diagnose_empty_root(root.resolve("missing")),"no such directory")}
    }
    }
    test("test_bad_root.Diagnosis.test_not_a_directory") {

    locally {
withProject(Seq("note.txt"->"x")){root=>equal(CLI.diagnose_empty_root(root.resolve("note.txt")),"not a directory")}
    }
    }
    test("test_bad_root.Diagnosis.test_root_file_declaring_no_session") {

    locally {
withProject(Map("ROOT" -> "chapter Demo\n").toSeq){root=>val msg=CLI.diagnose_empty_root(root);contains(msg,"none declares a session");}
    }
    }
    test("test_bad_root.Diagnosis.test_session_declared_but_no_theory_resolves") {

    locally {
withProject(Map("ROOT" -> "session Demo = HOL +\n  theories\n    Missing\n").toSeq){root=>val msg=CLI.diagnose_empty_root(root);contains(msg,"session(s) declared");contains(msg,"Demo");}
    }
    }
    test("test_bad_root.EmptyIndexFails.test_a_real_session_still_loads") {

    locally {
withProject(Seq("ROOT"->"session Demo = HOL +\n  theories\n    Top\n","Top.thy"->"theory Top imports Main begin\nlemma t: \"True\" by simp\nend\n")){root=>equal(load(root).map(_.theory),List("Top"))}
    }
    }
    test("test_bad_root.EmptyIndexFails.test_a_rootless_directory_of_theories_still_loads") {

    locally {
withProject(Seq("Orphan.thy"->"theory Orphan begin\nlemma o1: \"True\" by simp\nend\n")){root=>equal(load(root).map(_.theory),List("Orphan"))}
    }
    }
    test("test_bad_root.EmptyIndexFails.test_load_index_on_an_empty_directory_exits_2") {

    locally {
withProject(Seq("notes.txt"->"nothing to see\n")){root=>equal(query(root,"summary").exit,2)}
    }
    }
    test("test_bad_root.ExplicitRootFailsEarly.test_bad_root_reported_even_when_files_are_given") {

    locally {
withProject(Seq("A.thy"->"theory A begin\nlemma a: \"True\" by simp\nend\n")){root=>equal(query(root,"-R",root.resolve("missing").toString,"grep","lemma",root.resolve("A.thy").toString).exit,2)}
    }
    }
    test("test_bad_root.ExplicitRootFailsEarly.test_exit_code_is_not_1") {

    locally {
withProject(Nil){root=>check(query(root,"-R",root.resolve("missing").toString,"summary").exit!=1)}
    }
    }
    test("test_bad_root.ExplicitRootFailsEarly.test_file_as_root_exits_2") {

    locally {
withProject(Seq("note.txt"->"x")){root=>equal(query(root,"-R",root.resolve("note.txt").toString,"summary").exit,2)}
    }
    }
    test("test_bad_root.ExplicitRootFailsEarly.test_missing_root_exits_2") {

    locally {
withProject(Nil){root=>equal(query(root,"-R",root.resolve("missing").toString,"summary").exit,2)}
    }
    }
  }
}
