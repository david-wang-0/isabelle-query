package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_open_ranges {
  def run(): Unit = {
    test("test_open_ranges.EnclosingOpenRange.test_open_lower_lists_from_start") {

    locally {
withProject(Seq("Demo.thy"->CliFixtures.test_open_ranges_DEMO)){root=> val out=cli(List("enclosing", "Demo:..6").map(_.replace("PATH",root.resolve("Demo.thy").toString)),root).out; contains(out,"alpha");contains(out,"beta");notContains(out,"delta");}
    }
    }
    test("test_open_ranges.EnclosingOpenRange.test_open_upper_echoes_resolved_span") {

    locally {
withProject(Seq("Demo.thy"->CliFixtures.test_open_ranges_DEMO)){root=> val out=cli(List("enclosing", "Demo:5..").map(_.replace("PATH",root.resolve("Demo.thy").toString)),root).out; contains(out,"Demo:5..11");notContains(out,"None");}
    }
    }
    test("test_open_ranges.EnclosingOpenRange.test_open_upper_lists_to_eof") {

    locally {
withProject(Seq("Demo.thy"->CliFixtures.test_open_ranges_DEMO)){root=> val out=cli(List("enclosing", "Demo:5..").map(_.replace("PATH",root.resolve("Demo.thy").toString)),root).out; contains(out,"beta");contains(out,"gamma");contains(out,"delta");notContains(out,"alpha");}
    }
    }
    test("test_open_ranges.GrepOpenWindow.test_open_window_restricts_to_tail") {

    locally {
withProject(Seq("Demo.thy"->CliFixtures.test_open_ranges_DEMO)){root=> val out=cli(List("grep", "simp", "PATH:7..").map(_.replace("PATH",root.resolve("Demo.thy").toString)),root).out; contains(out,"delta");notContains(out,"alpha");notContains(out,"beta");}
    }
    }
    test("test_open_ranges.LinesOpenRange.test_both_open_is_whole_file") {

    locally {
equal(capture((o,e)=>Commands.cmd_lines(o,e,(1 to 6).map(n=>s"l$n").toArray,List(".."))).out,"1| l1\n2| l2\n3| l3\n4| l4\n5| l5\n6| l6\n")
    }
    }
    test("test_open_ranges.LinesOpenRange.test_colon_form_open_upper_round_trips") {

    locally {
equal(capture((o,e)=>Commands.cmd_lines(o,e,(1 to 6).map(n=>s"l$n").toArray,List("4.."))).out,"4| l4\n5| l5\n6| l6\n")
    }
    }
    test("test_open_ranges.LinesOpenRange.test_open_lower_runs_from_one") {

    locally {
equal(capture((o,e)=>Commands.cmd_lines(o,e,(1 to 6).map(n=>s"l$n").toArray,List("..2"))).out,"1| l1\n2| l2\n")
    }
    }
    test("test_open_ranges.LinesOpenRange.test_open_upper_past_eof_echoes_open_spec") {

    locally {
val r=capture((o,e)=>Commands.cmd_lines(o,e,(1 to 6).map(n=>s"l$n").toArray,List("9..")));contains(r.err,"range 9..: past end of file");notContains(r.err,"None")
    }
    }
    test("test_open_ranges.LinesOpenRange.test_open_upper_runs_to_eof") {

    locally {
equal(capture((o,e)=>Commands.cmd_lines(o,e,(1 to 6).map(n=>s"l$n").toArray,List("4.."))).out,"4| l4\n5| l5\n6| l6\n")
    }
    }
    test("test_open_ranges.ParseLineRange.test_both_open_is_whole_file") {

    locally {
equal(Commands.parse_line_range(".."),Some((1,None)))
    }
    }
    test("test_open_ranges.ParseLineRange.test_closed_range") {

    locally {
equal(Commands.parse_line_range("5..9"),Some((5,Some(9))))
    }
    }
    test("test_open_ranges.ParseLineRange.test_descending_closed_rejected") {

    locally {
check(Commands.parse_line_range("9..5").isEmpty)
    }
    }
    test("test_open_ranges.ParseLineRange.test_empty_single_rejected") {

    locally {
check(Commands.parse_line_range("").isEmpty)
    }
    }
    test("test_open_ranges.ParseLineRange.test_garbage_rejected") {

    locally {
check(Commands.parse_line_range("abc").isEmpty)
    }
    }
    test("test_open_ranges.ParseLineRange.test_open_lower_resolves_to_one") {

    locally {
equal(Commands.parse_line_range("..12"),Some((1,Some(12))))
    }
    }
    test("test_open_ranges.ParseLineRange.test_open_upper_is_none") {

    locally {
equal(Commands.parse_line_range("5600.."),Some((5600,None)))
    }
    }
    test("test_open_ranges.ParseLineRange.test_single_line") {

    locally {
equal(Commands.parse_line_range("7"),Some((7,Some(7))))
    }
    }
    test("test_open_ranges.ParseLineRange.test_zero_start_rejected") {

    locally {
check(Commands.parse_line_range("0..3").isEmpty)
    }
    }
  }
}
