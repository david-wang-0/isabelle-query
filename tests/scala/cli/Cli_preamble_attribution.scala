package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_preamble_attribution {
  def run(): Unit = {
    test("test_preamble_attribution.PreambleAttribution.test_a_match_list_has_one_block_per_match") {
val sec=parse(CliFixtures.test_preamble_attribution_THY,"Pre","Pre.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
    locally {
val out=capture((o,e)=>Render.emit_matches(o,Map("Pre" -> sec),List("alpha","beta","gamma").map(entry(sec,_)),"P",Flags(mode="all"))).out
val blocks=out.split("\n\n").filter(_.trim.nonEmpty)
equal(blocks.length,3)
    }
    }
    test("test_preamble_attribution.PreambleAttribution.test_an_entry_renders_as_one_block") {
val sec=parse(CliFixtures.test_preamble_attribution_THY,"Pre","Pre.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
    locally {
check(!_render("alpha").contains("\n\n"))
    }
    }
    test("test_preamble_attribution.PreambleAttribution.test_every_block_in_a_match_list_is_attributable") {
val sec=parse(CliFixtures.test_preamble_attribution_THY,"Pre","Pre.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
    locally {
val out=capture((o,e)=>Render.emit_matches(o,Map("Pre" -> sec),List("alpha","beta","gamma").map(entry(sec,_)),"P",Flags(mode="all"))).out
val blocks=out.split("\n\n").filter(_.trim.nonEmpty)
for(block<-blocks) check(List("alpha","beta","gamma").exists(lines(block).head.contains))
    }
    }
    test("test_preamble_attribution.PreambleAttribution.test_the_fixture_has_the_preambles_the_test_assumes") {
val sec=parse(CliFixtures.test_preamble_attribution_THY,"Pre","Pre.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
    locally {
check(entry(sec,"alpha").preamble.nonEmpty)
check(entry(sec,"beta").preamble.isEmpty)
check(entry(sec,"gamma").preamble.nonEmpty)
    }
    }
    test("test_preamble_attribution.PreambleAttribution.test_the_preamble_names_its_entry") {
val sec=parse(CliFixtures.test_preamble_attribution_THY,"Pre","Pre.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
    locally {
val head = CliSupport.lines(_render("alpha"))(0)
check(head.contains("preamble"))
check(head.contains("alpha"))
    }
    }
    test("test_preamble_attribution.PreambleAttribution.test_the_preamble_precedes_the_header") {
val sec=parse(CliFixtures.test_preamble_attribution_THY,"Pre","Pre.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
    locally {
val lines = CliSupport.lines(_render("alpha"))
val pre = ((lines.zipWithIndex.map { case (x,i)=>(i,x) } .filter { case (i,s) => (s.contains("preamble")) } .map { case (i,s) => i })).head
val hdr = ((lines.zipWithIndex.map { case (x,i)=>(i,x) } .filter { case (i,s) => (s.contains("(LEMMA)")) } .map { case (i,s) => i })).head
check(pre<hdr)
    }
    }
  }
}
