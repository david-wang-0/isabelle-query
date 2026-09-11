package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_disambig_loci {
  def run(): Unit = {
    test("test_disambig_loci.EveryLocusIsQualified.test_callers") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("callers","shared");contains(out,"alpha/Examples:4");contains(out,"beta/Examples:6");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_enclosing") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;check(emit("enclosing","beta/Examples:6").startsWith("beta/Examples:6 →"))}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_enclosing_range") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("enclosing","beta/Examples:4..6");contains(out,"beta/Examples:4..6");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_grep") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("grep","using shared");contains(out,"alpha/Examples.thy:4");contains(out,"beta/Examples.thy:6");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_grep_names_mode") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("grep","using shared","--names");contains(out,"alpha/Examples.thy:4");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_largest") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("largest","-N","20");contains(out,"alpha/Examples");contains(out,"beta/Examples");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_methods") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("methods","simp");contains(out,"alpha/Examples:4");contains(out,"beta/Examples:4");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_methods_names_mode") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("methods","simp","--names");contains(out,"alpha/Examples:4");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_shape_lemma") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("shape","lemma","b_owner");contains(out,"beta/Examples:");}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_shape_steps") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("shape","steps","--all");check(printed_loci(out).exists(_.startsWith("alpha/Examples:")),out)}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_shape_widest") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("shape","widest","-N","20");check(printed_loci(out).exists(_.contains("/Base:")),out)}
    }
    }
    test("test_disambig_loci.EveryLocusIsQualified.test_sorry") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("sorry");contains(out,"beta/Examples.thy:8");}
    }
    }
    test("test_disambig_loci.OnlyACollisionQualifies.test_a_unique_name_stays_bare") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("callers","shared");contains(out,"Unique:4");notContains(out,"solo/Unique")}
    }
    }
    test("test_disambig_loci.OnlyACollisionQualifies.test_grep_keeps_a_unique_file_bare") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val out=emit("grep","only_one");contains(out,"Unique.thy:4");notContains(out,"solo/Unique.thy")}
    }
    }
    test("test_disambig_loci.PlainFilesKeepTheirName.test_a_markdown_positional_keeps_its_suffix") {

    locally {
withProject(Seq("notes.md"->"a line about shared\n")){root=>contains(query(root,"grep","shared",root.resolve("notes.md").toString).out,"notes.md:1")}
    }
    }
    test("test_disambig_loci.TheEchoSaysWhatWasResolved.test_a_path_input_is_echoed_as_a_theory") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;check(emit("enclosing",root.resolve("beta/Examples.thy").toString+":6").startsWith("beta/Examples:6 →"))}
    }
    }
    test("test_disambig_loci.TheEchoSaysWhatWasResolved.test_an_ambiguous_input_is_echoed_qualified") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;check(matches(emit("enclosing","Examples:6"),"^(alpha|beta)/Examples:6"))}
    }
    }
    test("test_disambig_loci.TheEchoSaysWhatWasResolved.test_past_end_names_the_theory_it_measured") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;check(matches(emit("enclosing","Examples:99"),"past end of (alpha|beta)/Examples"))}
    }
    }
    test("test_disambig_loci.TheLocusIsValidInput.test_callers_loci_round_trip") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;for(tok<-printed_loci(emit("callers","shared"))){val (name,lo,hi)=locus(tok).get;val sec=Commands.resolve_theory(ss,name);check(sec.nonEmpty);contains(sec.get.source(lo-1),"using shared")}}
    }
    }
    test("test_disambig_loci.TheLocusIsValidInput.test_context_loci_round_trip_too") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val toks=printed_loci(emit("callers","shared","-U","2"),context=true);check(toks.nonEmpty);for(tok<-toks){val (name,lo,hi)=locus(tok).get;check(Commands.resolve_theory(ss,name).nonEmpty)}}
    }
    }
    test("test_disambig_loci.TheLocusIsValidInput.test_enclosing_accepts_its_own_output") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val first=emit("enclosing","beta/Examples:6");equal(emit("enclosing",printed_loci(first).head),first)}
    }
    }
    test("test_disambig_loci.TheLocusIsValidInput.test_grep_loci_round_trip_with_their_suffix") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;for(tok<-printed_loci(emit("grep","using shared"))){val (name,lo,hi)=locus(tok).get;check(name.endsWith(".thy"));val sec=Commands.resolve_theory(ss,name);check(sec.nonEmpty);contains(sec.get.source(lo-1),"using shared")}}
    }
    }
    test("test_disambig_loci.WrongFileAttribution.test_context_lines_come_from_the_matched_file") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val ctx=lines(emit("callers","shared","-U","1")).map(_.trim).filter(_.startsWith("alpha/Examples:5-"));equal(ctx.size,1);check(ctx.head.endsWith("end"))}
    }
    }
    test("test_disambig_loci.WrongFileAttribution.test_each_row_is_owned_by_a_lemma_in_its_own_file") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val rows=lines(emit("callers","shared","-U","0")).filter(_.contains("using shared"));equal(rows.size,3);val owners=rows.map(r=>{val p=r.trim.split("\\s+");p(0)->p(1)}).toMap;equal(owners("alpha/Examples:4"),"a_owner");equal(owners("beta/Examples:6"),"b_owner");equal(owners("Unique:4"),"only_one")}
    }
    }
    test("test_disambig_loci.WrongFileAttribution.test_no_row_names_an_entry_from_another_file") {

    locally {
withProject(CliFixtures.test_disambig_loci_FILES.toSeq){root=>val ss=load(root);def emit(a:String*):String=query(root,a*).out;val alpha=lines(emit("callers","shared","-U","0")).find(_.trim.startsWith("alpha/Examples:4")).get;notContains(alpha,"b_pad");notContains(alpha,"b_owner")}
    }
    }
  }
}
