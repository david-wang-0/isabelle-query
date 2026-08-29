(*  Title:      Demo_Core/Demo_Proofs.thy
    Author:     David Wang, with Claude Opus 5

    The proofs: an apply-style script, a citation chain four links deep, a
    nested structured proof with one deliberately wide step, and one open
    goal.  This is the only theory Demo_Core's ROOT declares -- Demo_Ops,
    Demo_Types and Demo_Legacy arrive through its `imports` closure.

    Demo_Legacy is imported and never cited, which is what makes `deps` and
    `refs` disagree here.
*)

theory Demo_Proofs
  imports Demo_Ops Demo_Legacy
begin

section \<open>An apply-style script\<close>

text \<open>
  Two scripts, so \<^verbatim>\<open>query shape\<close> has an unstructured proof to contrast with the
  Isar ones below. \<open>reachable_four\<close> cites \<open>reachable_suc\<close>, which is the first
  link of the chain \<^verbatim>\<open>query callers -r\<close> walks.
\<close>

lemma reachable_suc: "reachable n m \<Longrightarrow> reachable n (Suc (Suc m))"
  apply (rule hop)
  apply (rule hop)
  apply assumption
  done

lemma reachable_four: "reachable n m \<Longrightarrow> reachable n (Suc (Suc (Suc (Suc m))))"
  apply (drule reachable_suc)
  apply (drule reachable_suc)
  apply assumption
  done

section \<open>A citation chain\<close>

text \<open>
  \<open>total_eq\<close> \<open>\<leftarrow>\<close> \<open>total_append\<close> \<open>\<leftarrow>\<close> \<open>total_shift\<close> \<open>\<leftarrow>\<close> \<open>total_snoc_ge\<close> \<open>\<leftarrow>\<close>
  \<open>total_grows\<close>: four links, so \<^verbatim>\<open>callers total_append -r\<close> reaches strictly
  further than \<^verbatim>\<open>callers total_append\<close>.
\<close>

lemma total_shift: "total (xs @ [x]) = total xs + x"
  using total_append[of xs "[x]"] total_facts
  by simp

lemma total_snoc_ge:
  assumes "0 \<le> x"
  shows "total xs \<le> total (xs @ [x])"
  using assms by (simp add: total_shift)

lemma total_snoc_twice:
  assumes "0 \<le> x" and "0 \<le> y"
  shows "total xs \<le> total ((xs @ [x]) @ [y])"
proof -
  have "total xs \<le> total (xs @ [x])"
    using assms(1) by (rule total_snoc_ge)
  also have "\<dots> \<le> total ((xs @ [x]) @ [y])"
    using assms(2) by (rule total_snoc_ge)
  finally show ?thesis .
qed

theorem total_grows:
  fixes xs ys :: "int list"
  assumes "\<forall>y \<in> set ys. 0 \<le> y"
  shows "total xs \<le> total (xs @ ys)"
  using assms
proof (induct ys arbitrary: xs)
  case Nil
  show ?case by simp
next
  case (Cons y ys)
  have step: "total xs \<le> total (xs @ [y])"
    by (rule total_snoc_ge) (use Cons.prems in simp)
  have rest: "total (xs @ [y]) \<le> total ((xs @ [y]) @ ys)"
    by (rule Cons.hyps) (use Cons.prems in simp)
  from step rest have "total xs \<le> total ((xs @ [y]) @ ys)"
    by (rule order_trans)
  then show ?case by simp
qed

theorem demo_export: "total xs \<le> total (xs @ [1, 2])"
  by (rule total_grows) simp

section \<open>A nested structured proof, with one very wide step\<close>

text \<open>
  \<open>ledger\<close> below is the widest step in this corpus by as-written token count,
  which is what \<^verbatim>\<open>query shape widest\<close> ranks by. It is wide on purpose: four
  equations said at once, where four steps would each have been narrow.
\<close>

lemma account_settlement:
  fixes a b c d :: int
  assumes "0 \<le> a" and "0 \<le> b" and "0 \<le> c" and "0 \<le> d"
  shows "0 \<le> total [a, b, c, d]"
proof -
  have ledger:
    "total [a, b, c, d] = a + b + c + d \<and> total ([a, b] @ [c, d]) = total [a, b] + total [c, d]
      \<and> total [a, b] = a + b \<and> total [c, d] = c + d \<and> total (rev [a, b, c, d]) = d + c + b + a"
    by (simp add: total_eq)
  have flat: "total [a, b, c, d] = a + b + c + d"
    by (rule conjunct1[OF ledger])
  from flat show ?thesis
    using assms by linarith
qed

lemma tree_depth_pos:
  assumes "\<not> is_leaf t"
  shows "0 < depth t"
proof (cases t)
  case Leaf
  with assms show ?thesis by simp
next
  case (Node l x r)
  have "depth t = Suc (max (depth l) (depth r))"
  proof -
    from Node have "t = Node l x r" .
    then show ?thesis by simp
  qed
  then show ?thesis by simp
qed

section \<open>Small entries, for contrast under `largest`\<close>

lemma evens_nil: "evens [] = []"
  by simp

lemma account_balance_update: "balance (r \<lparr> balance := b \<rparr>) = b"
  by simp

lemma cost_fuel_positive: "0 < cost i + fuel"
  using cost_pos fuel_pos by simp

lemma f\<^sub>1_chain: "n < f\<^sub>1 (f\<^sub>1 n)"
  using f\<^sub>1_gt[of n] f\<^sub>1_gt[of "f\<^sub>1 n"] by linarith

section \<open>One open goal\<close>

lemma reachable_le: "reachable n m \<Longrightarrow> n \<le> m"
  \<comment> \<open>left open on purpose, so that \<^verbatim>\<open>query sorry\<close> has exactly one hit\<close>
  sorry

end
