package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_grep_window {
  def run(): Unit = {
    test("test_grep_window.GrepWindow.test_no_window_finds_all") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>equal(cli(List("grep", "widget", "PATH", "-c").map(_.replace("PATH",root.resolve("Owners.thy").toString)),root).out.trim,"3")}
    }
    }
    test("test_grep_window.GrepWindow.test_single_line_window") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>equal(cli(List("grep", "widget", "PATH:6", "-c").map(_.replace("PATH",root.resolve("Owners.thy").toString)),root).out.trim,"1")}
    }
    }
    test("test_grep_window.GrepWindow.test_window_excludes_outside_lines") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>val out=query(root,"grep","widget",root.resolve("Owners.thy").toString+":8..12").out;contains(out,"Owners.thy:9");notContains(out,"Owners.thy:5");notContains(out,"Owners.thy:6") }
    }
    }
    test("test_grep_window.GrepWindow.test_window_restricts_count") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>equal(cli(List("grep", "widget", "PATH:8..12", "-c").map(_.replace("PATH",root.resolve("Owners.thy").toString)),root).out.trim,"1")}
    }
    }
    test("test_grep_window.SplitPathWindow.test_no_window_when_file_missing") {

    locally {
withProject(Nil){root=>val r=capture((out,err)=>{val s=new CLI.Session(err,out);s.env=_=>None;s.ambient_root=()=>root;s.index_provider=_=>Some((Nil,Map.empty));val n=new CLI.Ns;n.positional("files")=List("nope.thy:1..9");CLI.load_sections(s,n,windows=true)});contains(r.err,"nope.thy:1..9");equal(r.exit,1)}
    }
    }
    test("test_grep_window.SplitPathWindow.test_no_window_without_colon") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=> val path=root.resolve("Owners.thy");val secs=fileSections(root,List("PATH".replace("PATH",path.toString)),windows=true);equal(secs.size,1);equal(secs.head.path,path);check(secs.head.line_window.isEmpty)}
    }
    }
    test("test_grep_window.SplitPathWindow.test_window_when_file_resolves") {

    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=> val path=root.resolve("Owners.thy");val secs=fileSections(root,List("PATH:8..12".replace("PATH",path.toString)),windows=true);equal(secs.size,1);equal(secs.head.path,path);equal(secs.head.line_window,Some((8,Some(12))))}
    }
    }
  }
}
