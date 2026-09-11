package isabelle.query.regression

import isabelle.query.*
import TestSupport.*
import GraphSupport.*

object GraphPerf {
  def run(): Unit = {
    disposition("test_perf.BuildScaling.test_build_scales_near_linearly", "optional", "Opt-in timing guard; set ISABELLE_QUERY_PERF=1 to exercise original sizes, repeats and thresholds.")
    if (sys.env.get("ISABELLE_QUERY_PERF").exists(_.nonEmpty))
    test("test_perf.BuildScaling.test_build_scales_near_linearly") {
      val (small, large) = GraphPerformance.corpora; val tSmall = GraphPerformance.buildTime(small); val tLarge = GraphPerformance.buildTime(large); val ratio = tLarge / tSmall; check(ratio < 8.0, s"build ratio $ratio")
    }
    disposition("test_perf.BuildScaling.test_build_throughput_is_sane", "optional", "Opt-in timing guard; set ISABELLE_QUERY_PERF=1 to exercise original sizes, repeats and thresholds.")
    if (sys.env.get("ISABELLE_QUERY_PERF").exists(_.nonEmpty))
    test("test_perf.BuildScaling.test_build_throughput_is_sane") {
      val large = GraphPerformance.corpora._2; val rate = large.map(_.source.length).sum / GraphPerformance.buildTime(large); check(rate > 20000, s"build throughput $rate")
    }
    disposition("test_perf.ParseScaling.test_parse_scales_near_linearly", "optional", "Opt-in timing guard; set ISABELLE_QUERY_PERF=1 to exercise original sizes, repeats and thresholds.")
    if (sys.env.get("ISABELLE_QUERY_PERF").exists(_.nonEmpty))
    test("test_perf.ParseScaling.test_parse_scales_near_linearly") {
      val small = GraphPerformance.parseTime(GraphPerformance.source(1)); val large = GraphPerformance.parseTime(GraphPerformance.source(4)); check(large / small < 8.0, s"parse ratio ${large / small}")
    }
  }
}
