package isabelle.query.regression

private[regression] object HiddenFixture {
  val text: String = "theory T imports Main\nbegin\n\nlemma a_cartouche_citation: \"True\"\nproof -\n  have p: \"True\" by simp\n  from p \\<open>True\\<close> have \"True \\<and> True\" by simp\n  show ?thesis by simp\nqed\n\nlemma a_control_no_cartouche: \"True\"\nproof -\n  have p: \"True\" by simp\n  from p have \"True \\<and> True\" by simp\n  show ?thesis by simp\nqed\n\nlemma b_wrapped_statement: \"True\"\nproof -\n  have\n    \"True \\<and> True\"\n    by simp\n  show ?thesis by simp\nqed\n\nlemma b_control_same_line: \"True\"\nproof -\n  have \"True \\<and> True\" by simp\n  show ?thesis by simp\nqed\n\nend\n"
}
