package isabelle.query.regression
import isabelle.query.*
import TestSupport.*

/** Additional native-lexer guards attached to the original two failing legacy
  * IDs. Inputs and expected columns below are hand-computed, not goldens from
  * the implementation. The original assertions remain in the generated cases.
  */
private[regression] object ParserLegacy {
  private def parsed(text: String): Theory_Section =
    Theory.parse_one("T",java.nio.file.Paths.get("T.thy"),text)
  private def lengths(sec: Theory_Section): Unit = {
    equal(sec.live_source.length,sec.source.length)
    equal(sec.live_source.map(_.length).toList,sec.source.map(_.length).toList)
    equal(sec.outer_source.map(_.length).toList,sec.source.map(_.length).toList)
  }
  def inlineGuards(): Unit = {
    val blocks=List("{* closed *}","{* \"an unmatched quote *}",
      "{* \\<open>an unmatched cartouche *}","{* (* an unmatched comment *}")
    for(block <- blocks) subcase("legacy opaque body: "+block) {
      val text="before "+block+" after"
      val sec=parsed(text)
      equal(sec.live_source.toList,List("before "+(" "*block.length)+" after"))
      equal(sec.source.toList,List(text))
      equal(sec.regions.nonisar.at(0),List((7,7+block.length)))
      equal(sec.regions.inner.at(0),List((7,7+block.length)))
      equal(sec.regions.open_at.toList,List(false))
      lengths(sec)
    }
    subcase("legacy does not swallow the next declaration after quote-looking content") {
      val text="theory T imports Pure begin\nlemma first: \"True\" by simp {* \"quote *}\nlemma after: \"True\" by simp\nend\n"
      val sec=parsed(text)
      equal(sec.entries.map(_.name),List("first","after"))
      equal(sec.entries.map(_.thy_line),List(2,3))
      lengths(sec)
    }
    val kept=List("\"{* literal *}\"","`{* literal *}`","\\<open>{* literal *}\\<close>","‹{* literal *}›")
    for(term <- kept) subcase("native delimited term protects legacy-looking literal: "+term) {
      val text="before "+term+" after"
      val sec=parsed(text)
      equal(sec.live_source.toList,List(text))
      equal(sec.regions.nonisar.at(0),Nil)
      equal(sec.regions.open_at.toList,List(false))
      lengths(sec)
    }
    subcase("native nested comments retain their own delimiters") {
      val text="before (* {* legacy-looking *) after"
      val sec=parsed(text)
      equal(sec.live_source.toList,List("before "+(" "*23)+" after"))
      lengths(sec)
    }
  }
  def regionGuards(): Unit = {
    subcase("closed multiline legacy source columns and open_at") {
      val text="aa {* first\n  \"quote\nlast *} zz"
      val sec=parsed(text)
      equal(sec.source.toList,List("aa {* first","  \"quote","last *} zz"))
      equal(sec.live_source.toList,List("aa "+(" "*8)," "*8,(" "*7)+" zz"))
      equal(sec.regions.nonisar.at(0),List((3,11)))
      equal(sec.regions.nonisar.at(1),List((0,8)))
      equal(sec.regions.nonisar.at(2),List((0,7)))
      equal(sec.regions.open_at.toList,List(false,true,true))
      equal(sec.nonisar_ranges,List((2,2)))
      lengths(sec)
    }
    subcase("unterminated legacy consumes to EOF without moving offsets") {
      val text="x {* open\nlemma phantom: \"True\"\n"
      val sec=parsed(text)
      equal(sec.live_source.toList,List("x "+(" "*7)," "*21))
      equal(sec.regions.nonisar.at(0),List((2,9)))
      equal(sec.regions.nonisar.at(1),List((0,21)))
      equal(sec.regions.open_at.toList,List(false,true))
      equal(sec.entries,Nil)
      lengths(sec)
    }
    subcase("legacy-looking multiline cartouche remains live") {
      val text="x \\<open>first\n{* literal *}\nlast\\<close> y"
      val sec=parsed(text)
      equal(sec.live_source.toList,sec.source.toList)
      equal(ParserBridge.spans(sec.regions.nonisar),Map.empty[Int,List[(Int,Int)]])
      equal(sec.regions.open_at.toList,List(false,true,true))
      lengths(sec)
    }
    subcase("closing legacy at line boundary leaves next line closed") {
      val sec=parsed("{* first\nlast *}\nafter")
      equal(sec.regions.open_at.toList,List(false,true,false))
      equal(sec.nonisar_ranges,List((1,2)))
      equal(sec.live_source.last,"after")
      lengths(sec)
    }
  }
}
