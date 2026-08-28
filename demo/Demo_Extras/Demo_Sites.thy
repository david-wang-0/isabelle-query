(*  Title:      Demo_Extras/Demo_Sites.thy
    Author:     Andras Salamon, with Claude Opus 5

    One site per link of the naming chain `query instances` uses, in order:
    a written qualifier, an arity's type constructor, the L of `sublocale
    L <= M`, the enclosing context, and nothing at all (`?`).
*)

theory Demo_Sites
  imports Demo_Core.Demo_Proofs
begin

section \<open>Arities\<close>

text \<open>
  Three arities of the class \<open>weight\<close>, of one, one and two parameters. Under
  \<^verbatim>\<open>query instances weight --sorts\<close> the name cell grows to the arity
  \<^emph>\<open>as written\<close> --- nothing is inferred, because no prover runs.
\<close>

instantiation nat :: weight
begin

definition wt_nat_def: "wt (n::nat) = Suc n"

instance ..

end

instantiation list :: (weight) weight
begin

definition wt_list_def: "wt xs = sum_list (map wt xs)"

instance ..

end

instantiation prod :: (weight, weight) weight
begin

definition wt_prod_def: "wt p = wt (fst p) + wt (snd p)"

instance ..

end

subsection \<open>A bare `instance` arity\<close>

text \<open>
  A class extension (\<open>class heavy = weight + \<dots>\<close>) is \<^emph>\<open>not\<close> a site: it supplies
  no type and no term. The \<^verbatim>\<open>instance\<close> beneath it is.
\<close>

class heavy = weight +
  assumes wt_pos: "0 < wt x"

instance nat :: heavy
  by intro_classes (simp add: wt_nat_def)

section \<open>Interpretations\<close>

text \<open>
  \<open>nat_max\<close> and \<open>nat_min\<close> write a qualifier, so that is the row's name. The
  third writes none and is named \<open>?\<close> --- the same placeholder the engine uses
  for an unnamed \<^verbatim>\<open>context\<close>, and deliberately not the locale's own name, which
  would make every such row repeat the question.
\<close>

interpretation nat_max: assoc_op "max :: nat \<Rightarrow> nat \<Rightarrow> nat"
  by unfold_locales (simp add: max.assoc)

global_interpretation nat_min: assoc_op "min :: nat \<Rightarrow> nat \<Rightarrow> nat"
  by unfold_locales (simp add: min.assoc)

interpretation assoc_op "(+) :: nat \<Rightarrow> nat \<Rightarrow> nat"
  by unfold_locales (simp add: add.assoc)

text \<open>The quoted locale name has to stay quoted at the site as well.\<close>

interpretation rev_functor: "functor" rev
  by unfold_locales simp

section \<open>Sublocales, and which locale the row is named after\<close>

text \<open>
  \<^verbatim>\<open>sublocale idem_op \<subseteq> assoc_op g\<close> is a site of \<^bold>\<open>assoc_op\<close> --- that is what is
  interpreted --- named after \<open>idem_op\<close>, where the interpretation is installed.
  \<^verbatim>\<open>query instances idem_op\<close> does not report it. This is the single case that
  separates the scan from a grep.
\<close>

sublocale idem_op \<subseteq> assoc_op g
  by unfold_locales (rule g_assoc)

locale holder =
  fixes q :: "nat \<Rightarrow> nat \<Rightarrow> nat"
  assumes q_assoc: "q (q x y) z = q x (q y z)"
begin

text \<open>A written qualifier beats the enclosing block: this row is named \<open>sub\<close>.\<close>

sublocale sub: assoc_op q
  by unfold_locales (rule q_assoc)

text \<open>This one writes nothing, so it falls through to the enclosing target.\<close>

interpretation assoc_op q
  by unfold_locales (rule q_assoc)

end

section \<open>Interpretations inside a proof\<close>

text \<open>
  A written qualifier names the row \<open>times_sg\<close>; the one below writes none, and
  falls through to the entry the \<^verbatim>\<open>interpret\<close> sits inside.
\<close>

lemma interp_qualified: "(a::nat) * (b * c) = a * b * c"
proof -
  interpret times_sg: assoc_op "(*) :: nat \<Rightarrow> nat \<Rightarrow> nat"
    by unfold_locales (simp add: mult.assoc)
  show ?thesis by (simp add: times_sg.assoc)
qed

lemma interp_anonymous: "((xs :: nat list) @ ys) @ zs = xs @ ys @ zs"
proof -
  interpret assoc_op "(@) :: nat list \<Rightarrow> nat list \<Rightarrow> nat list"
    by unfold_locales simp
  show ?thesis by (rule assoc)
qed

end
