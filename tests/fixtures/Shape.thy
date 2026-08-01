theory Shape
  imports Main
begin

lemma flat_proof: "True"
  by simp

lemma chained:
  assumes a: "P"
  shows "P \<and> P"
proof -
  from a have p1: "P" by blast
  moreover have "P" using a by simp
  ultimately show "P \<and> P" by (rule conjI)
qed

lemma nested:
  shows "x = x"
proof -
  have outer: "x = x"
  proof -
    fix y
    assume "y = y"
    show "x = x" by simp
  qed
  show "x = x" by (rule outer)
qed

lemma standalone:
  assumes h: "Q"
  shows "Q"
proof -
  from h
  have step: "Q" by assumption
  then show "Q" by blast
qed

lemma reuse:
  assumes a: "A" and b: "B"
  shows "A \<and> B \<and> A"
proof -
  have f1: "A" using a by simp
  have f2: "B" using b by simp
  have f3: "A \<and> B" using f1 f2 by simp
  show "A \<and> B \<and> A" using f3 f1 by simp
qed

lemma classify_demo:
  fixes g :: nat
  shows "rev [g] = rev [g]"
proof -
  have step: "\<forall>k. rev [g, k] = rev [g, k]" by simp
  show "rev [g] = rev [g]" by simp
qed

lemma redundant:
  fixes a b :: nat
  shows "(a, b) = (a, b)"
proof -
  have s1: "(a, b) = (a, b)" by simp
  have s2: "(a, b) = (a, b)" by simp
  show "(a, b) = (a, b)" using s1 s2 by simp
qed

lemma framing:
  fixes p q :: nat
  shows "P (p, q)"
proof -
  have "R (p, q) (p, q)" by simp
  have "R (p, q) (q, p)" by simp
  show "P (p, q)" by simp
qed

lemma m3_pair:
  fixes c d :: "nat \<times> nat"
  shows "fst c = fst d \<and> snd c = snd d \<longrightarrow> c = d"
proof -
  show "fst c = fst d \<and> snd c = snd d \<longrightarrow> c = d" by (auto simp: prod_eq_iff)
qed

lemma m3_framing:
  fixes xs :: "nat list" and v :: nat
  shows "xs[0 := v] = xs[0 := v]"
proof -
  show "xs[0 := v] = xs[0 := v]" by simp
qed

lemma m3_wide:
  fixes xs :: "nat list" and z :: nat
  shows "(xs ! 0, xs ! 1, xs ! 2, (xs[3 := z]) ! 3) = (xs ! 0, xs ! 1, xs ! 2, (xs[3 := z]) ! 3)"
proof -
  show "(xs ! 0, xs ! 1, xs ! 2, (xs[3 := z]) ! 3) = (xs ! 0, xs ! 1, xs ! 2, (xs[3 := z]) ! 3)" by simp
qed

lemma deeply_nested: "x = x"
proof -
  have "x = x"
  proof -
    have "x = x"
    proof -
      show "x = x" by simp
    qed
    show "x = x" by simp
  qed
  show "x = x" by simp
qed

end
