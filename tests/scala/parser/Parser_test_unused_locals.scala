package isabelle.query.regression
import isabelle.query.*
import TestSupport.*

/** The parser half of tests/test_unused_locals.py [body-end-text], P14 S2.

  * Only the ID whose assertions need no `proof_locals` is ported here; the
  * rest of the file (the `unused --locals` walk, and the two Extent tests that
  * compare `body_end_line` / `proof_close_line` with `proof_locals.proof_end`)
  * belongs to the cli suite with `[unused-locals]`.  `EXTENT` is upstream's
  * fixture byte for byte.
  *
  * Hand-written guards ride on the ported ID, as ParserLegacy's do: the
  * `remark` half of `test_a_text_block_does_not_end_the_proof` that needs no
  * walk, `Entries.proof_close_line` on every Extent entry against the value
  * upstream's own test holds it to (`proof_locals.proof_end`, read off oracle
  * 0.9.2), and one lemma per lexical slip that 857b35f names.  Each slip
  * lemma puts a `text` block inside the proof, so the proof's body_end reaches
  * its close only if the walk lexes the slip right; mis-lexed, the walk either
  * never sees the close or sees it early, and body_end stops above the `text`
  * (verified against a slipped copy of upstream's regex: 7, 14, 20, 29, 36).
  * Expected values are hand-computed and equal oracle 0.9.2's `body_end`.
  */
private[regression] object Parser_test_unused_locals {
  val EXTENT = """theory Extent
  imports Main
begin

lemma before: "True"
proof -
  have u: "True" by simp
  show ?thesis using u by simp
qed

instance nat :: foo
proof
  have v: "True" by simp
  show "True" by simp
qed

lemma sub: "True \<and> True"
  apply (rule conjI)
  subgoal by simp
  subgoal proof -
    have w: "True" by simp
    show ?thesis by simp
  qed
  done

lemma remark: "True"
proof -
  have x: "True" by simp
  text \<open>A remark in the middle of a proof.\<close>
  show ?thesis using x by simp
qed

lemma prose_decl: "True"
proof -
  have y: "True" by simp
  text \<open>As in
lemma foo, which this mirrors.\<close>
  show ?thesis using y by simp
qed

end
"""

  val SLIPS = """theory Slips
  imports Main
begin

lemma dotdot: "True \<and> True"
  apply (rule conjI)
  subgoal unfolding foo..
  text \<open>A remark between the subgoals.\<close>
  subgoal by simp
  done

lemma dot_after_name: "True \<and> True"
  apply (rule conjI)
  subgoal using TrueI.
  text \<open>A remark between the subgoals.\<close>
  subgoal by simp
  done

lemma symbol_qualified: "True"
  using INV.\<Phi>o_def
  text \<open>A remark before the method.\<close>
  by simp

lemma qed_dot: "True"
proof -
  have "True"
  proof -
    show ?thesis by simp
  qed.
  text \<open>A remark after the inner proof.\<close>
  show ?thesis by simp
qed

lemma schematic_proof: "True"
proof -
  let ?proof = "True"
  text \<open>A remark after the binding.\<close>
  show ?thesis by simp
qed

lemma closed_then_text: "True"
  by simp
text \<open>Trailing prose after a closed proof.\<close>

lemma last: "True" by simp

end
"""

  private def entry(sec: Theory_Section, name: String): Entry =
    sec.entries.find(_.name == name).getOrElse(throw new NoSuchElementException(name))

  def run(): Unit = {
    test("test_unused_locals.Extent.test_prose_in_a_text_block_is_not_a_declaration") {
      /* Line 37 begins with `lemma`, inside the block: English, not the next
         entry (`Kyber_gpv_IND_CPA:273` has exactly this). */
      val sec = TestSupport.parse(EXTENT, "Extent", "Extent.thy")
      equal(entry(sec, "prose_decl").body_end_line, 39, "tests/test_unused_locals.py:343")

      subcase("guard: a text block inside a proof does not end it (remark, was 28)") {
        equal(entry(sec, "remark").body_end_line, 31)
      }
      subcase("guard: proof_close_line on the outer view, as upstream's walk-agreement test reads it") {
        val outer = sec.outer_source
        val closes = sec.entries.filter(_.proof_line > 0).map(e =>
          e.name -> Entries.proof_close_line(outer, e.proof_line, e.thy_end))
        equal(closes, List("before" -> 9, "sub" -> 24, "remark" -> 31, "prose_decl" -> 39))
      }

      val slips = TestSupport.parse(SLIPS, "Slips", "Slips.thy")
      val expected = List(
        "dotdot" -> 10,             // `unfolding foo..` ends the first subgoal
        "dot_after_name" -> 17,     // `using TrueI.` ends the first subgoal
        "symbol_qualified" -> 22,   // `INV.\<Phi>o_def` is one name, not a terminator
        "qed_dot" -> 32,            // `qed.` is `qed` then `.`
        "schematic_proof" -> 39,    // `?proof` is a variable, not the command
        "closed_then_text" -> 42,   // a text AFTER the close still ends the proof
        "last" -> 45)
      for ((name, line) <- expected) subcase(s"guard: lexical slip, $name") {
        equal(entry(slips, name).body_end_line, line, name)
      }
    }
  }
}
