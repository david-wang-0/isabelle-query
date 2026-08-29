(*  Title:      Demo_Core/Demo_Ops.thy
    Author:     David Wang, with Claude Opus 5

    Function-shaped declarations, the names one command binds beyond its own,
    and the decoys: a commented-out declaration and a cancelled one, neither
    of which is an entry.
*)

theory Demo_Ops
  imports Demo_Types
begin

section \<open>Recursion\<close>

subsection \<open>Mutual recursion binds two constants\<close>

text \<open>
  \<open>evens\<close> and \<open>odds\<close> are one command, one span and one entry --- counting them
  twice would triple-count the span under \<^verbatim>\<open>largest\<close> and give \<^verbatim>\<open>enclosing\<close> two
  owners for each line. \<open>odds\<close> is still citable, and resolves back here.
\<close>

fun evens :: "nat list \<Rightarrow> nat list"
  and odds :: "nat list \<Rightarrow> nat list"
where
  "evens [] = []"
| "evens (x # xs) = x # odds xs"
| "odds [] = []"
| "odds (x # xs) = evens xs"

subsection \<open>Primitive recursion over a datatype\<close>

primrec depth :: "'a tree \<Rightarrow> nat" where
  "depth Leaf = 0"
| "depth (Node l x r) = Suc (max (depth l) (depth r))"

subsection \<open>An inductive predicate with labelled rules\<close>

text \<open>The rule labels \<open>start\<close> and \<open>hop\<close> are bound names of the entry \<open>reachable\<close>.\<close>

inductive reachable :: "nat \<Rightarrow> nat \<Rightarrow> bool" where
  start: "reachable n n"
| hop: "reachable n m \<Longrightarrow> reachable n (Suc m)"

section \<open>Totals\<close>

subsection \<open>A definition with a named equation\<close>

definition total :: "int list \<Rightarrow> int"
  where total_eq: "total xs = sum_list xs"

lemma total_append: \<comment> \<open>the total of a concatenation splits\<close>
  "total (xs @ ys) = total xs + total ys"
  unfolding total_eq
  by simp \<comment> \<open>the list sum already distributes over append\<close>

subsection \<open>One statement, two named conjuncts\<close>

lemma total_facts:
  shows total_nil: "total [] = 0"
    and total_cons: "total (x # xs) = x + total xs"
  by (simp_all add: total_eq)

section \<open>A fact whose name is also an Isabelle attribute\<close>

text \<open>
  \<open>mono\<close> is a real attribute (the \<^verbatim>\<open>inductive\<close> package's) \<^emph>\<open>and\<close>, here, a real
  fact name. Which one a mention is depends on where it stands, and where
  position cannot decide, on the method/attribute table.
\<close>

lemma mono: "m \<le> n \<Longrightarrow> f\<^sub>1 m \<le> f\<^sub>1 n"
  by (simp add: f\<^sub>1_def)

lemma f\<^sub>1_le_double: "f\<^sub>1 m \<le> f\<^sub>1 (m + m)"
  using mono by simp \<comment> \<open>chained fact\<close>

lemma f\<^sub>1_mono_rule:
  assumes "m \<le> n"
  shows "f\<^sub>1 m \<le> f\<^sub>1 n"
  using assms by (rule mono) \<comment> \<open>argument of a rule\<close>

lemma f\<^sub>1_le_suc: "f\<^sub>1 m \<le> f\<^sub>1 (Suc m)"
  by (simp add: mono) \<comment> \<open>bare method argument\<close>

section \<open>Declarations inside a target\<close>

lemma (in assoc_op) assoc_right: "f x (f y z) = f (f x y) z"
  by (simp add: assoc)

context idem_op
begin

lemma g_absorb: "g x (g x x) = x"
  by (simp add: g_idem)

end

section \<open>Two decoys that are not declarations\<close>

text \<open>
  Neither of the two below is an entry, so \<^verbatim>\<open>query find decoy\<close> reports nothing,
  \<^verbatim>\<open>query unused\<close> never lists them and \<^verbatim>\<open>query callers total\<close> does not count the
  \<open>total\<close> inside them. \<^verbatim>\<open>query grep --with-comments decoy\<close> is what finds them,
  marked as non-live.
\<close>

(*  A superseded definition, left behind in a comment.

    definition decoy_total :: "int list \<Rightarrow> int"
      where "decoy_total xs = fold (+) xs 0"

    lemma decoy_total_eq: "decoy_total xs = total xs"
      by (simp add: total_eq)
*)

text \<open>The same thing again in the other spelling, which jEdit strikes through.\<close>

\<^cancel>\<open>definition cancelled_total :: "int list \<Rightarrow> int"
  where "cancelled_total xs = 0"\<close>

section \<open>Dead code\<close>

lemma dead_helper: "f\<^sub>1 0 = 1"
  \<comment> \<open>deliberately dead: declared, proved, cited by nothing --- \<^verbatim>\<open>query unused\<close> finds it\<close>
  by (simp add: f\<^sub>1_def)

end
