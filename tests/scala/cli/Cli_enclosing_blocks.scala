package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_enclosing_blocks {
  def run(): Unit = {
    test("test_enclosing_blocks.CliModes.test_blocks_mode_shows_full_path") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:13"),mode = "blocks")
check(out.contains("▸ have apos_1 9..16"))
check(out.contains("have key"))
check(out.contains("11..14"))
    }
    }
    test("test_enclosing_blocks.CliModes.test_brace_block") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:23"))
check(out.contains("▸ { } 22..24"))
    }
    }
    test("test_enclosing_blocks.CliModes.test_default_shows_nearest_block") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:13"))
check(out.contains("structured (LEMMA)"))
check(out.contains("▸ have key 11..14"))
check(!out.contains("apos_1"))
    }
    }
    test("test_enclosing_blocks.CliModes.test_entry_mode_matches_pre_drilldown_output") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:13"),mode = "entry")
val sec = _sec()
val e = _entry(sec,"structured")
check(out.contains(("Nested:13 → structured (" + e.tag.toString + ") — Nested " + Render.format_extent(e).toString + "  (in proof)")))
    }
    }
    test("test_enclosing_blocks.CliModes.test_entry_mode_suppresses_drilldown") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:13"),mode = "entry")
check(out.contains("structured (LEMMA)"))
check(!out.contains("▸"))
    }
    }
    test("test_enclosing_blocks.CliModes.test_flat_proof_degrades_to_entry") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:4"))
check(out.contains("flat (LEMMA)"))
check(!out.contains("▸"))
    }
    }
    test("test_enclosing_blocks.CliModes.test_in_proof_but_no_block_degrades") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:17"))
check(!out.contains("▸"))
    }
    }
    test("test_enclosing_blocks.CliModes.test_outer_block_when_outside_inner") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val (out,_) = _run(List("Nested:15"))
check(out.contains("▸ have apos_1 9..16"))
check(!out.contains("key"))
    }
    }
    test("test_enclosing_blocks.EnclosingBlocks.test_innermost_is_last") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val labels = (Commands.enclosing_blocks(_blocks("structured"),13) .map { case b => Commands.block_label(b) })
equal(labels,List("have apos_1","have key"))
    }
    }
    test("test_enclosing_blocks.EnclosingBlocks.test_line_in_main_proof_only") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
equal(Commands.enclosing_blocks(_blocks("structured"),17),List())
    }
    }
    test("test_enclosing_blocks.EnclosingBlocks.test_line_in_outer_block_only") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val labels = (Commands.enclosing_blocks(_blocks("structured"),15) .map { case b => Commands.block_label(b) })
equal(labels,List("have apos_1"))
    }
    }
    test("test_enclosing_blocks.ParserWiring.test_blocks_flag") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
check(args("enclosing","-b","Foo:1").bool("blocks"))
    }
    }
    test("test_enclosing_blocks.ParserWiring.test_defaults_to_nearest") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val n=args("enclosing","Foo:1");check(!n.bool("entry"));check(!n.bool("blocks"))
    }
    }
    test("test_enclosing_blocks.ParserWiring.test_entry_and_blocks_mutually_exclusive") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
withProject(Nil){root=>equal(query(root,"enclosing","-e","-b","Foo:1").exit,2)}
    }
    }
    test("test_enclosing_blocks.ProofBlocks.test_flat_proof_has_no_blocks") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
equal(_blocks("flat"),List())
    }
    }
    test("test_enclosing_blocks.ProofBlocks.test_main_proof_is_not_reported") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val spans = (_blocks("structured") .map { case b => (b.start,b.end) }.toSet)
check(!spans.contains((8,18)))
    }
    }
    test("test_enclosing_blocks.ProofBlocks.test_raw_brace_block") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
equal(_as_tuples(_blocks("braced")),List(("{ }",22,24)))
    }
    }
    test("test_enclosing_blocks.ProofBlocks.test_structured_nested_haves") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
equal(_as_tuples(_blocks("structured")),List(("have apos_1",9,16),("have key",11,14)))
    }
    }
    test("test_enclosing_blocks.ProofBlocks.test_unbalanced_proof_fails_safe") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val bad=parse("theory U imports Main begin\n\nlemma bad: \"P\"\nproof -\n  have x: \"Q\"\n  proof -\n    show \"Q\" by blast\n  qed\n\nend\n","U","U.thy");check(Commands.proof_blocks(bad,entry(bad,"bad")).isEmpty)
    }
    }
    test("test_enclosing_blocks.RoundTrip.test_block_field_span_parses_back") {
val sec=parse(CliFixtures.nested_THY,"Nested","Nested.thy")
def _sec():Theory_Section=sec
def _entry(s:Theory_Section,n:String):Entry=entry(s,n)
def _blocks(n:String):List[Commands.Block]=Commands.proof_blocks(sec,entry(sec,n)).get
def _as_tuples(bs:List[Commands.Block]):List[(String,Int,Int)]=bs.sortBy(b=>(b.start,b.end)).map(b=>(Commands.block_label(b),b.start,b.end))
def _run(loci:List[String],mode:String="nearest"):(String,String)={val r=capture((o,e)=>Commands.cmd_enclosing(o,e,List(sec),loci,mode));(r.out,r.err)}
    locally {
val b=Commands.Block("have","key",11,14);equal(Commands.block_field(b),"have key 11..14");equal(locus(s"Nested:${b.start}..${b.end}"),Some(("Nested",11,Some(14))))
    }
    }
  }
}
