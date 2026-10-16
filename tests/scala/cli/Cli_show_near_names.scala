package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import CliSupport.*

/* `show` says when it falls back to near names [show-near-names]; under
   `--names` / `--count` that note, and the bound-name note, go to stderr. */
private[regression] object Cli_show_near_names {
  private val NOTE = "# no entry named 'tape_len'; 1 whose name contains it:"

  private def shown(name: String, mode: String = "first"): (List[String], List[String]) = {
    val ss = List(parse(CliFixtures.test_show_near_names_THY, "T"))
    val r = capture((o, e) => Commands.cmd_show(o, e, ss, name, Flags(mode = mode)))
    (lines(r.out), lines(r.err))
  }

  def run(): Unit = {
    test("test_show_near_names.TheFallbackSaysSo.test_first_line_of_the_default_view") {
      val (out, err) = shown("tape_len")
      equal(out(0), NOTE)
      check(out(1).contains("descriptor_tape_length (LEMMA)"), out(1))
      equal(err, Nil)
    }
    test("test_show_near_names.TheFallbackSaysSo.test_names_mode_keeps_stdout_names_only") {
      val (out, err) = shown("tape_len", "names")
      equal(out.length, 1)
      check(out(0).startsWith("descriptor_tape_length (LEMMA)"), out(0))
      equal(err, List(NOTE))
    }
    test("test_show_near_names.TheFallbackSaysSo.test_count_mode_prints_only_the_number") {
      equal(shown("tape_len", "count"), (List("1"), List(NOTE)))
    }
    test("test_show_near_names.TheFallbackSaysSo.test_an_exact_match_has_no_note") {
      val (out, err) = shown("descriptor_tape_length")
      check(!out(0).startsWith("#"), out(0))
      equal(err, Nil)
    }
    test("test_show_near_names.TheBoundNameNoteFollowsTheSameRule.test_count_mode") {
      val (out, err) = shown("conj", "count")
      equal(out, List("1"))
      equal(err.length, 1)
      check(err(0).startsWith("# 'conj' is "), err(0))
    }
  }
}
