package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_lines_forms {
  def run(): Unit = {
    test("test_lines_forms.EndToEnd.test_classic_and_colon_agree") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>val p=root.resolve("Owners.thy").toString;val classic=query(root,"lines",p,"5..6").out;equal(classic,query(root,"lines",p+":5..6").out);contains(classic,"definition widget :: nat where")}
    }
    }
    test("test_lines_forms.EndToEnd.test_colon_multi_range_one_file") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>val p=root.resolve("Owners.thy").toString;val out=query(root,"lines",p+":5..6",p+":15..16").out;contains(out,"definition widget :: nat where");contains(out,"lemma comm_add");contains(out,"--")}
    }
    }
    test("test_lines_forms.Errors.test_mixed_forms_rejected") {

    locally {
withProject(Nil){root=>equal(cli(List("lines", "A:1..2", "3..4"),root).exit,2)}
    }
    }
    test("test_lines_forms.Errors.test_multi_file_colon_rejected") {

    locally {
withProject(Nil){root=>equal(cli(List("lines", "A:1..2", "B:3..4"),root).exit,2)}
    }
    }
    test("test_lines_forms.Errors.test_no_range_rejected") {

    locally {
withProject(Nil){root=>equal(cli(List("lines", "Foo"),root).exit,2)}
    }
    }
    test("test_lines_forms.FileAndRangesSplit.test_classic_form") {

    locally {
withProject(Seq("Foo.thy"->(1 to 35).map(n=>s"l$n").mkString("\n"))){root=> val r=cli(List("lines", "Foo", "1..10", "20..30"),root);val expected=capture((o,e)=>Commands.cmd_lines(o,e,(1 to 35).map(n=>s"l$n").toArray,List("1..10", "20..30")));equal(r,expected)}
    }
    }
    test("test_lines_forms.FileAndRangesSplit.test_colon_form_one_file") {

    locally {
withProject(Seq("Foo.thy"->(1 to 35).map(n=>s"l$n").mkString("\n"))){root=> val r=cli(List("lines", "Foo:1..10", "Foo:20..30"),root);val expected=capture((o,e)=>Commands.cmd_lines(o,e,(1 to 35).map(n=>s"l$n").toArray,List("1..10", "20..30")));equal(r,expected)}
    }
    }
    test("test_lines_forms.FileAndRangesSplit.test_colon_form_single_line") {

    locally {
withProject(Seq("Foo.thy"->(1 to 35).map(n=>s"l$n").mkString("\n"))){root=> val r=cli(List("lines", "Foo:6"),root);val expected=capture((o,e)=>Commands.cmd_lines(o,e,(1 to 35).map(n=>s"l$n").toArray,List("6..6")));equal(r,expected)}
    }
    }
  }
}
