package isabelle.query.regression
import isabelle.query.*
import TestSupport.*

private[regression] object ParserSpecial {
  val computedReason = "Scala Theory_Section deliberately computes source views instead of retaining Python cached-list identity (model.scala, COMPUTED NOT CACHED); byte equality and independent array mutation are asserted."
  val cachedId = "test_live_source.Shape.test_result_is_cached"
  val identityId = "test_live_source.Shape.test_clean_theory_shares_the_source_list"
  def run(): Unit = {
    disposition(cachedId,"known-difference",computedReason)
    test(cachedId) {
      val sec=ParserBridge.section(ParserApiFixtures.LIVE_FIXTURE,"A")
      val first=sec.live_source;val second=sec.live_source
      equal(first.toList,second.toList)
      check(!(first eq second),"computed views must not share a mutable array")
      val before=second.toList
      first(0)="mutated result"
      equal(second.toList,before);equal(sec.live_source.toList,before)
      equal(sec.source.toList,Py.split_lines(ParserApiFixtures.LIVE_FIXTURE)._1.toList)
    }
    disposition(identityId,"known-difference",computedReason)
    test(identityId) {
      val text="theory A\nimports Main\nbegin\n\nlemma foo: \"True\" by simp\n\nend\n"
      val sec=ParserBridge.section(text,"A")
      equal(ParserBridge.spans(sec.regions.nonisar),Map.empty[Int,List[(Int,Int)]])
      val live=sec.live_source;val raw=sec.source
      equal(live.toList,raw.toList);check(!(live eq raw))
      live(0)="mutated result"
      equal(raw.toList,Py.split_lines(text)._1.toList)
      equal(sec.live_source.toList,raw.toList)
    }
    test("test_balanced.BalancedEnd.test_quote_aware_skips_a_quoted_paren") {
      val original="(\"a)b\")"
      equal(original.length,7) // original hand-computed matching-close index
      // The production quote-aware scan is reached through its public consumer.
      // An arbitrary clause after the quoted ')' proves the scan did not close
      // at that character; the original quoted term remains byte-for-byte.
      val text="theory T imports Pure begin\nlemma x: \"True\"\n  by (induct " +
        original.substring(1,original.length-1) + " arbitrary: kept)\nend\n"
      val sec=TestSupport.parse(text)
      val got=Shape.scan_inductions(new Shape.Sec_Ctx(sec),sec.entries.head)
      equal(got,List(Shape.Induction(1,1,false,false)))
    }
  }
}
