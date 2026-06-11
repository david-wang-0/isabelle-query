theory Owners imports Main begin

section \<open>Widgets\<close>

definition widget :: nat where
  "widget = 42"

lemma size_bound:
  assumes "x > widget"
  shows "x \<noteq> 0"
  using assms by auto

section \<open>More\<close>

lemma comm_add: "a + b = b + (a::nat)"
  by simp

end
