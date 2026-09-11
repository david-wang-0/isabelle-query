package isabelle.query.regression
import ParserValues.*
/** Assertion-for-assertion port of tests/test_known_failures.py. Fixture literals retain source bytes. */
private[regression] object Parser_test_known_failures {
private def h_names_of(parent: Env, args: Vector[V], kw: Map[String,V]): V = {
val e = parent.clone()
e("snippet") = kw.getOrElse("snippet", args.lift(0).getOrElse(throw new IllegalArgumentException("missing snippet")))
return V.seq(ParserBridge.call("section_from", Vector(e("snippet")), Map()).field("entries").seq.flatMap { item => val c1 = e.clone(); c1("e") = item; if (true) Vector(c1("e").field("name")) else Vector.empty })
V.none
}
private def case_RecoverableParserGaps_test_infix_abbreviation_operator_name(): Unit = TestSupport.expectedFailure("test_known_failures.RecoverableParserGaps.test_infix_abbreviation_operator_name", "Original upstream @expectedFailure: infix/mixfix abbreviation names still require richer equation parsing; preserve the desired operator-name assertion and fail on XPASS.") {
val e: Env = scala.collection.mutable.Map.empty


e("snippet") = V("theory T imports Main begin\nabbreviation \"x \\<oplus> y \\<equiv> plus x y\"\nend\n")
TestSupport.check(h_names_of(e, Vector(e("snippet")), Map()).has(V("\\<oplus>")), "tests/test_known_failures.py:41" + " actual=" + h_names_of(e, Vector(e("snippet")), Map()))
}
def run(): Unit = { case_RecoverableParserGaps_test_infix_abbreviation_operator_name() }
}
