package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_show_inline_proof {
  def run(): Unit = {
    test("test_show_inline_proof.NoDuplicatedLines.test_a_bare_statement_term_line_still_shows_its_proof") {
val sec=parse(CliFixtures.test_show_inline_proof_THY, "Show", "Show.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
def _body(name:String):List[String]=lines(_render(name)).drop(1)
    locally {
val body = _body("term_line")
equal(body.count(_ == "  \"P c\" by auto"),1)
equal((body).size,2)
    }
    }
    test("test_show_inline_proof.NoDuplicatedLines.test_declaration_ending_in_its_proof_prints_once") {
val sec=parse(CliFixtures.test_show_inline_proof_THY, "Show", "Show.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
def _body(name:String):List[String]=lines(_render(name)).drop(1)
    locally {
val body = _body("shows_form")
equal(body.count(_ == "  shows \"P g\" by simp"),1)
equal((body).size,3)
    }
    }
    test("test_show_inline_proof.NoDuplicatedLines.test_every_rendered_line_is_distinct_across_the_fixture") {
val sec=parse(CliFixtures.test_show_inline_proof_THY, "Show", "Show.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
def _body(name:String):List[String]=lines(_render(name)).drop(1)
    locally {
for (name <- List("one_liner","next_line","shows_form","term_line","structured")) {
val body = (_body(name) .filter { case ln => ln.trim.nonEmpty } .map { case ln => ln })
equal((body).size,((body).toSet).size)
}
    }
    }
    test("test_show_inline_proof.NoDuplicatedLines.test_one_liner_prints_its_line_once") {
val sec=parse(CliFixtures.test_show_inline_proof_THY, "Show", "Show.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
def _body(name:String):List[String]=lines(_render(name)).drop(1)
    locally {
val body = _body("one_liner")
equal(body.count(_ == "lemma one_liner: \"P a\" by simp"),1)
equal((body).size,1)
    }
    }
    test("test_show_inline_proof.NoDuplicatedLines.test_proof_on_its_own_line_still_shows_it") {
val sec=parse(CliFixtures.test_show_inline_proof_THY, "Show", "Show.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
def _body(name:String):List[String]=lines(_render(name)).drop(1)
    locally {
val body = _body("next_line")
equal(body.count(_ == "  by simp"),1)
check(body.contains("lemma next_line: \"P b\""))
    }
    }
    test("test_show_inline_proof.NoDuplicatedLines.test_structured_proof_is_unchanged") {
val sec=parse(CliFixtures.test_show_inline_proof_THY, "Show", "Show.thy")
def _render(name:String):String=Render.render_entry(sec,entry(sec,name))
def _body(name:String):List[String]=lines(_render(name)).drop(1)
    locally {
val body = _body("structured")
equal(body.count(_ == "lemma structured: \"P i\""),1)
equal(body.count(_ == "proof -"),1)
check(body.contains("  [+3 more proof lines]"))
    }
    }
  }
}
