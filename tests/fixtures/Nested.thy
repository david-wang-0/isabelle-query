theory Nested imports Main begin

lemma flat: "True"
  by simp

lemma structured:
  shows "A"
proof -
  have apos_1: "A"
  proof -
    have key: "B"
    proof -
      show "B" by blast
    qed
    show "A" using key by blast
  qed
  show "A" by (rule apos_1)
qed

lemma braced: "C"
proof -
  {
    have inner: "C" by blast
  }
  show "C" by blast
qed

end
