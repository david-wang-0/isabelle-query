package isabelle.query.regression

private[regression] object BareFixture {
  val text: String = "theory Bare\nimports Main\nbegin\n\nlemma by_construction: \"True \\<and> True\"\nproof -\n  have a: \"True\" by simp\n  also\n  have \"True\" by simp\n  interpret dummy_locale\n  finally show ?thesis by simp\nqed\n\nlemma undelimited: \"True\"\nproof -\n  have nf: \"\\<not> False\" by simp\n  hence False by simp\n  with \\<open>\\<not> False\\<close> show False ..\n  thus ?thesis by simp\nqed\n\nlemma unfound: \"True\"\nproof -\n  obtain x where\n    \"x = (0::nat)\" by simp\n  have\n    \"True\" by simp\n  thus ?thesis by simp\nqed\n\nend\n"
}
