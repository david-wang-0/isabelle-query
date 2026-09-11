package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_families {
  def run(): Unit = {
    test("test_cli_families.FamilyContract.test_callers_has_no_path_positional") {

    locally {
check(!command("callers").pos.exists(_.dest == "files"))
    }
    }
    test("test_cli_families.FamilyContract.test_callers_is_variadic") {

    locally {
equal(args("callers", "A", "B", "C").pos("name"),List("A","B","C")); check(command("callers").names.contains("callers"))
    }
    }
    test("test_cli_families.FamilyContract.test_lookup_subjects_are_variadic_without_paths") {

    locally {
for (c <- List("show","callees","callers","deps","uses")) { equal(command(c).pos.head.nargs,"+"); check(!command(c).pos.exists(_.dest == "files")) }
    }
    }
    test("test_cli_families.FamilyContract.test_search_verbs_carry_trailing_paths") {

    locally {
for (c <- List("grep","largest","sorry")) { val p = command(c).pos.find(_.dest == "files").get; equal(p.nargs,"*"); val help = capture((o,e) => CLI.cmd_help(o,command(c))).out; contains(help,"FILES");equal(args(c,(if(c=="grep")List("p","a.thy","b.thy") else List("a.thy","b.thy"))*).pos("files"),List("a.thy","b.thy")) }
    }
    }
    disposition("test_cli_families.FamilyContract.test_search_verbs_carry_trailing_paths", "known-difference", "PLAN.md help-text parity exemption: Scala uses FILES rather than argparse PATH metavar; assert every original search verb retains zero-or-more trailing paths and discoverable file help.")
    test("test_cli_families.SharedHelpWording.test_subject_list_help_is_templated") {

    locally {
for(c<-List("show","callees","callers","deps","uses","find")){val cmd=command(c);check(cmd.pos.head.help.nonEmpty,c);equal(cmd.pos.head.nargs,"+",c);equal(args(c,"A","B","C").pos(cmd.pos.head.dest),List("A","B","C"));val h=capture((o,e)=>CLI.cmd_help(o,cmd)).out;contains(h,c);contains(h,cmd.pos.head.help)}
    }
    }
    disposition("test_cli_families.SharedHelpWording.test_subject_list_help_is_templated", "known-difference", "PLAN.md help-text parity exemption: Scala uses concise positional help instead of argparse gate-loop prose; all six original commands retain help and variadic subject behavior.")
    test("test_cli_families.SharedHelpWording.test_template_varies_only_by_extra") {

    locally {
check(command("show").pos.head.help.replace("show","CMD").startsWith(command("callees").pos.head.help.replace("callees","CMD")))
    }
    }
  }
}
