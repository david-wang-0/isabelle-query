package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import CliSupport.*

/* Each header says `[k of n]` when there is more than one match
   [show-match-count]; a single match keeps its header as it was. */
private[regression] object Cli_show_match_count {
  private def headers(name: String, mode: String = "first", find: Boolean = false): List[String] = {
    val ss = List(parse(CliFixtures.test_show_match_count_A, "A", "A.thy"),
      parse(CliFixtures.test_show_match_count_B, "B", "B.thy"))
    val r = capture { (o, e) =>
      if (find) Commands.cmd_find(o, e, ss, name, Flags(mode = mode))
      else Commands.cmd_show(o, e, ss, name, Flags(mode = mode))
    }
    lines(r.out).filter(_.startsWith("---"))
  }

  def run(): Unit = {
    test("test_show_match_count.TheHeaderCarriesTheCount.test_first_of_two") {
      equal(headers("twice"),
        List("--- twice (LEMMA) — A.thy [1 of 2] [src 2..2, 1 lines] ---"))
    }
    test("test_show_match_count.TheHeaderCarriesTheCount.test_all_numbers_each") {
      equal(headers("twice", "all"), List(
        "--- twice (LEMMA) — A.thy [1 of 2] [src 2..2, 1 lines] ---",
        "--- twice (LEMMA) — B.thy [2 of 2] [src 2..2, 1 lines] ---"))
    }
    test("test_show_match_count.TheHeaderCarriesTheCount.test_a_single_match_is_unchanged") {
      equal(headers("once"), List("--- once (LEMMA) — A.thy [src 3..3, 1 lines] ---"))
    }
    test("test_show_match_count.TheHeaderCarriesTheCount.test_find_shares_the_header") {
      val h = headers("twice", find = true)
      check(h.head.contains("[1 of 2]"), h.head)
    }
  }
}
