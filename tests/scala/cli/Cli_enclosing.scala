package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_enclosing {
  def run(): Unit = {
    test("test_enclosing.CliSurface.test_at_alias_parses") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(args("at","Foo:1","Bar:2").pos("locus"),List("Foo:1","Bar:2"));equal(command("at"),command("enclosing"))
    }
    }
    test("test_enclosing.CliSurface.test_enclosing_parses") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(args("enclosing","Foo.thy:42").pos("locus"),List("Foo.thy:42"));equal(command("enclosing").names.head,"enclosing")
    }
    }
    test("test_enclosing.CliSurface.test_locus_is_required") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
raises(args("enclosing"))
    }
    }
    test("test_enclosing.Containment.test_definition_owns_its_line") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val widget=_entry("widget");val (out,err)=_run(List(s"Owners:${widget.thy_line}"));contains(out,"widget (DEF)");contains(out,s"Owners:${widget.thy_line} →");notContains(out,".thy:");equal(err,"")
    }
    }
    test("test_enclosing.Containment.test_extent_annotation_present") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val widget = _entry("widget")
val (out,_) = _run(List(("Owners:" + widget.thy_line.toString)))
check(out.contains(("[src " + widget.thy_line.toString + ".." + widget.thy_end.toString + ",")))
    }
    }
    test("test_enclosing.Containment.test_lemma_proof_line_is_in_proof") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val sb = _entry("size_bound")
check(sb.proof_line>0)
val (out,_) = _run(List(("Owners:" + sb.proof_line.toString)))
check(out.contains("size_bound (LEMMA)"))
check(out.contains("(in proof)"))
    }
    }
    test("test_enclosing.Containment.test_lemma_statement_line_is_in_statement") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val sb = _entry("size_bound")
val (out,_) = _run(List(("Owners:" + sb.thy_line.toString)))
check(out.contains("size_bound (LEMMA)"))
check(out.contains("(in statement)"))
    }
    }
    test("test_enclosing.Containment.test_span_end_line_still_owned") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val sb = _entry("size_bound")
val (out,_) = _run(List(("Owners:" + sb.thy_end.toString)))
check(out.contains("size_bound (LEMMA)"))
    }
    }
    test("test_enclosing.Errors.test_batch_continues_past_a_bad_locus") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val widget = _entry("widget")
val (out,err) = _run(List("Owners",("Owners:" + widget.thy_line.toString)))
check(out.contains("widget (DEF)"))
check(err.contains("expected FILE:LINE"))
    }
    }
    test("test_enclosing.Errors.test_batch_emits_one_line_per_good_locus") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val (widget,sb) = (_entry("widget"),_entry("size_bound"))
val (out,_) = _run(List(("Owners:" + widget.thy_line.toString),("Owners:" + sb.thy_line.toString)))
equal(((CliSupport.lines(out) .filter { case ln => (ln.contains(" → ")) } .map { case ln => ln })).size,2)
    }
    }
    test("test_enclosing.Errors.test_malformed_locus_to_stderr") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val (out,err) = _run(List("Owners"))
equal(out,"")
check(err.contains("expected FILE:LINE"))
    }
    }
    test("test_enclosing.Errors.test_unknown_theory_to_stderr") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val (out,err) = _run(List("Nonesuch:5"))
equal(out,"")
check(err.contains("no such theory"))
    }
    }
    test("test_enclosing.NoOwner.test_header_region_has_no_owner") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val first = ((parse(CliFixtures.owners_THY,"Owners").entries .filter { case e => (e.thy_line > 0) } .map { case e => e.thy_line })).min
check(first>1)
val (out,err) = _run(List("Owners:1"))
check(out.contains("no enclosing entry"))
equal(err,"")
    }
    }
    test("test_enclosing.NoOwner.test_past_end_of_file") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val sec = parse(CliFixtures.owners_THY,"Owners")
val (out,_) = _run(List(("Owners:" + (sec.thy_lines + 100).toString)))
check(out.contains("past end of Owners"))
    }
    }
    test("test_enclosing.OnDiskFixture.test_fixture_parses_from_disk") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>check(Set("widget","size_bound","comm_add").subsetOf(load(root).flatMap(_.entries.map(_.name)).toSet))}
    }
    }
    test("test_enclosing.OnDiskFixture.test_full_stack_via_parser_and_root") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>val r=query(root,"-R",root.toString,"enclosing",s"Owners:${_entry("size_bound").thy_line}");contains(r.out,"size_bound (LEMMA)")}
    }
    }
    test("test_enclosing.OnDiskFixture.test_name_form_locus") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>val r=query(root,"enclosing",s"Owners:${_entry("size_bound").thy_line}");contains(r.out,"size_bound (LEMMA)");equal(r.err,"")}
    }
    }
    test("test_enclosing.OnDiskFixture.test_path_form_locus_resolves_against_on_disk_path") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
withProject(Seq("Owners.thy"->CliFixtures.owners_THY)){root=>val r=query(root,"enclosing",s"${root.resolve("Owners.thy")}:${_entry("size_bound").thy_line}");contains(r.out,"size_bound (LEMMA)");equal(r.err,"")}
    }
    }
    test("test_enclosing.ParseLocus.test_bare_name_form") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(locus("Foo:42"),Some(("Foo",42,Some(42))))
    }
    }
    test("test_enclosing.ParseLocus.test_empty_line_is_rejected") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
for(t<-List("Foo:")) check(locus(t).isEmpty)
    }
    }
    test("test_enclosing.ParseLocus.test_inverted_range_rejected") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
for(t<-List("Foo:12..8")) check(locus(t).isEmpty)
    }
    }
    test("test_enclosing.ParseLocus.test_no_colon_is_rejected") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
for(t<-List("Foo")) check(locus(t).isEmpty)
    }
    }
    test("test_enclosing.ParseLocus.test_non_numeric_line_is_rejected") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
for(t<-List("Foo:bar")) check(locus(t).isEmpty)
    }
    }
    test("test_enclosing.ParseLocus.test_path_form") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(locus("sub/Foo.thy:42"),Some(("sub/Foo.thy",42,Some(42))))
    }
    }
    test("test_enclosing.ParseLocus.test_range_form") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(locus("Foo:8..12"),Some(("Foo",8,Some(12))))
    }
    }
    test("test_enclosing.ParseLocus.test_splits_on_last_colon") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(locus("Foo:4:42"),Some(("Foo:4",42,Some(42))))
    }
    }
    test("test_enclosing.ParseLocus.test_strips_trailing_rg_context_marker") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(locus("Foo:42-"),Some(("Foo",42,Some(42))))
    }
    }
    test("test_enclosing.ParseLocus.test_strips_trailing_rg_match_marker") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
equal(locus("Foo:42:"),Some(("Foo",42,Some(42))))
    }
    }
    test("test_enclosing.ParseLocus.test_zero_and_negative_rejected") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
for(t<-List("Foo:0", "Foo:-3")) check(locus(t).isEmpty)
    }
    }
    test("test_enclosing.RangeMode.test_range_in_header_overlaps_nothing") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val first = ((parse(CliFixtures.owners_THY,"Owners").entries .filter { case e => (e.thy_line > 0) } .map { case e => e.thy_line })).min
check(first>2)
val (out,_) = _run(List("Owners:1..2"))
check(out.contains("no entries overlap"))
    }
    }
    test("test_enclosing.RangeMode.test_range_inside_one_entry_lists_only_it") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val sb = _entry("size_bound")
check(sb.thy_end>sb.thy_line)
val (out,_) = _run(List(("Owners:" + sb.thy_line.toString + ".." + (sb.thy_line + 1).toString)))
equal((_owner_lines(out)).size,1)
check(out.contains("size_bound (LEMMA)"))
    }
    }
    test("test_enclosing.RangeMode.test_range_lists_all_overlapping_entries") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val (widget,sb) = (_entry("widget"),_entry("size_bound"))
val (lo,hi) = (widget.thy_line,sb.thy_line)
val (out,err) = _run(List(("Owners:" + lo.toString + ".." + hi.toString)))
check(out.contains("widget (DEF)"))
check(out.contains("size_bound (LEMMA)"))
check(!out.contains("comm_add"))
equal(err,"")
for (ln <- _owner_lines(out)) {
check(ln.contains(("Owners:" + lo.toString + ".." + hi.toString + " →")))
}
    }
    }
    test("test_enclosing.RangeMode.test_rg_marker_round_trips_through_command") {
val sec=parse(CliFixtures.owners_THY,"Owners","Owners.thy")
def _entry(n:String):Entry=entry(sec,n)
def _run(ls:List[String]):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),ls,"nearest"));(r.out,r.err)}
def _owner_lines(out:String):List[String]=lines(out).filter(_.contains(" → "))
    locally {
val sb = _entry("size_bound")
val (out,err) = _run(List(("Owners:" + sb.proof_line.toString + ":")))
check(out.contains("size_bound (LEMMA)"))
check(out.contains("(in proof)"))
equal(err,"")
    }
    }
  }
}
