package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_stdin_path {
  def run(): Unit = {
    test("test_stdin_path.GrepStdin.test_default_skips_prose_block") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val out=grep("foo");contains(out,"1 live match");contains(out,"foo_bar");notContains(out,"[in comment/text]")
    }
    }
    test("test_stdin_path.GrepStdin.test_with_comments_includes_prose") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val out=grep("foo",with_comments=true);contains(out,"[in comment/text]");contains(out,CLI.STDIN_NAME+":3")
    }
    }
    test("test_stdin_path.LinesStdin.test_reads_range_from_stdin_with_preserved_numbers") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
withProject(Nil){root=>equal(lines(cli(List("lines","-","5..6"),root,stdin=lines(text).toArray).out),List("5| lemma foo_bar: \"P x = P x\"","6| proof -"))}
    }
    }
    test("test_stdin_path.LinesStdin.test_single_line") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
withProject(Nil){root=>equal(cli(List("lines","-","1"),root,stdin=lines(text).toArray).out.trim,"1| theory Demo imports Main begin")}
    }
    }
    test("test_stdin_path.LoadSectionsStdin.test_dash_reads_and_parses_stdin_as_theory") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val ss=read();equal(ss.size,1);check(ss.head.is_thy);equal(ss.head.entries.map(_.name).toSet,Set("foo_bar","baz"))
    }
    }
    test("test_stdin_path.LoadSectionsStdin.test_repeated_dash_reads_stdin_only_once") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
var reads=0;val s=sess();s.stdin_source=()=>{reads+=1;lines(text).toArray};val n=new CLI.Ns;n.positional("files")=List("-","-");equal(CLI.load_sections(s,n).size,1);equal(reads,1)
    }
    }
    test("test_stdin_path.LoadSectionsStdin.test_source_is_cached_so_no_disk_read_of_synthetic_path") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
equal(read().head.source(4),"lemma foo_bar: \"P x = P x\"")
    }
    }
    test("test_stdin_path.LoadSectionsStdin.test_stdin_section_uses_synthetic_location_label") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val sec=read().head;equal(sec.theory,CLI.STDIN_NAME);equal(sec.path.getFileName.toString,CLI.STDIN_NAME)
    }
    }
    test("test_stdin_path.ParsePolicy.test_infer_defaults_stdin_to_syntax") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val src=CLI.File_Source(CLI.STDIN_NAME,CLI.stdin_path,Some(lines(text).toArray));check(src.from_stdin);val sec=CLI.section_from(sess(),src,"infer");check(sec.is_thy);equal(sec.entries.map(_.name).toSet,Set("foo_bar","baz"))
    }
    }
    test("test_stdin_path.ParsePolicy.test_infer_is_plain_on_nonthy") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val sec=CLI.section_from(sess(),CLI.File_Source("note",Paths.get("note.md"),Some(lines(text).toArray)),"infer");check(!sec.is_thy);equal(sec.entries,Nil)
    }
    }
    test("test_stdin_path.ParsePolicy.test_infer_is_syntax_on_thy") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val sec=CLI.section_from(sess(),CLI.File_Source("Demo",Paths.get("Demo.thy"),Some(lines(text).toArray)),"infer");check(sec.is_thy)
    }
    }
    test("test_stdin_path.ParsePolicy.test_syntax_forces_grammar_even_on_nonthy") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val sec=CLI.section_from(sess(),CLI.File_Source("note",Paths.get("note.md"),Some(lines(text).toArray)),"syntax");check(sec.is_thy);equal(sec.entries.map(_.name).toSet,Set("foo_bar","baz"))
    }
    }
    test("test_stdin_path.ParserWiring.test_lines_accepts_dash_as_file") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
equal(args("lines","-","1..3").pos("args"),List("-","1..3"))
    }
    }
    test("test_stdin_path.ParserWiring.test_search_family_accepts_dash_in_files") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
for((c,a)<-List("grep"->List("foo"),"largest"->Nil,"sorry"->Nil)) equal(args(c,(a :+ "-")*).pos("files"),List("-"))
    }
    }
    test("test_stdin_path.SharedRouting.test_bare_name_resolves_via_index") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
withProject(Seq("Demo.thy"->text)){root=>val s=sess();s.ambient_root=()=>root;val sec=load(root).head;val src=CLI.resolve_file_source(s,"Demo",root.resolve("Demo"));equal(src.path,sec.path);equal(src.label,"Demo")}
    }
    }
    test("test_stdin_path.SharedRouting.test_existing_file_resolves_to_itself") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
withProject(Seq("Demo.thy"->text)){root=>val p=root.resolve("Demo.thy");val src=CLI.resolve_file_source(sess(),p.toString,p);equal(src.path,p);equal(src.label,"Demo");check(!src.from_stdin)}
    }
    }
    test("test_stdin_path.SharedRouting.test_unknown_token_exits_with_hint") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
withProject(Nil){root=>val r=capture((out,err)=>{val s=new CLI.Session(err,out);s.env=_=>None;s.ambient_root=()=>root;s.index_provider=_=>Some((Nil,Map.empty));CLI.resolve_file_source(s,"Nope",root.resolve("Nope"))});contains(r.err,"not a path or known theory");equal(r.exit,1)}
    }
    }
    test("test_stdin_path.SorryStdin.test_sorry_over_stdin_reports_owning_entry") {
val text=CliFixtures.test_stdin_path_THY
def sess():CLI.Session={val s=new CLI.Session(new Out(new java.io.StringWriter),new Out(new java.io.StringWriter));s.env=_=>None;s.stdin_source=()=>lines(text).toArray;s}
def read(tokens:List[String]=List("-")):List[Theory_Section]={val n=new CLI.Ns;n.positional("files")=tokens;CLI.load_sections(sess(),n)}
def grep(pattern:String,with_comments:Boolean=false):String=capture((o,e)=>Commands.cmd_grep(o,e,read(),pattern,Flags(with_comments=with_comments))).out

    locally {
val out=capture((o,e)=>Commands.cmd_sorry(o,read(),false)).out;contains(out,CLI.STDIN_NAME+":8");contains(out,"foo_bar");contains(out,"1 sorry")
    }
    }
  }
}
