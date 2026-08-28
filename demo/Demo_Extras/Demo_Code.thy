(*  Title:      Demo_Extras/Demo_Code.thy
    Author:     Andras Salamon, with Claude Opus 5

    Every spelling of a code-equation site that `query codeqs` reports, plus
    the one it is documented to miss, plus one declaration the Python oracle
    cannot index at all.

    This is the only theory Demo_Extras' ROOT declares; Demo_Sites arrives
    through its `imports` closure.
*)

theory Demo_Code
  imports Demo_Sites
begin

section \<open>Default equations\<close>

text \<open>
  A \<^verbatim>\<open>fun\<close> registers code equations with no attribute written. Those are the
  sites a purely attribute-driven scan misses entirely, and \<^verbatim>\<open>query codeqs\<close>
  reports them as \<open>default\<close>, named after the defining entry.
\<close>

fun twice :: "nat \<Rightarrow> nat" where
  "twice 0 = 0"
| "twice (Suc n) = Suc (Suc (twice n))"

primrec sumlist :: "nat list \<Rightarrow> nat" where
  "sumlist [] = 0"
| "sumlist (x # xs) = x + sumlist xs"

section \<open>Equations declared by attribute\<close>

lemma twice_add [code]: "twice n = n + n"
  by (induct n) simp_all

definition thrice :: "nat \<Rightarrow> nat"
  where "thrice n = twice n + n"

declare thrice_def [code]

lemma thrice_alt: "thrice n = 3 * n"
  by (simp add: thrice_def twice_add)

lemmas thrice_code [code] = thrice_alt

text \<open>
  A \<^verbatim>\<open>declare\<close> attributes the site to the binding label it attaches to, not to
  the constant --- \<open>sumlist.simps\<close>, not \<open>sumlist\<close>.
\<close>

declare sumlist.simps [code]

section \<open>A retraction, reported and marked\<close>

text \<open>
  A listing that showed the equations and hid the \<^verbatim>\<open>[code del]\<close> that is the
  reason one of them is not in force would be the more misleading of the two.
  So the retraction is a row, marked \<open>[code del]\<close>.
\<close>

declare twice.simps [code del]

section \<open>The documented blind spot\<close>

text \<open>
  \<^verbatim>\<open>codeqs\<close> finds an equation by the head symbol of its left-hand side, and
  mixfix notation defeats that rule: in \<open>x \<oplus> y = \<dots>\<close> the head position holds no
  identifier at all. So \<open>shift_code\<close> below is \<^bold>\<open>not\<close> reported under
  \<^verbatim>\<open>codeqs shift\<close> --- only the \<open>default\<close> row at the definition is. This is the
  one place these scans lean the unsafe way: they under-report. If
  \<^verbatim>\<open>codeqs c\<close> looks short, check with \<^verbatim>\<open>query grep\<close>.
\<close>

definition shift :: "nat \<Rightarrow> nat \<Rightarrow> nat"  (infixl \<open>\<oplus>\<close> 65)
  where "x \<oplus> y = x + 2 * y"

lemma shift_code [code]: "x \<oplus> y = x + y + y"
  by (simp add: shift_def)

section \<open>An abstract type\<close>

text \<open>
  An abstract code equation reads \<open>projection (constructor args) = \<dots>\<close>, whose
  outermost head is the projection and whose subject is the constructor, so
  the head rule collects both. \<^verbatim>\<open>codeqs elems\<close> and \<^verbatim>\<open>codeqs NDlist\<close> each report
  this line.
\<close>

typedef ndlist = "{xs :: nat list. distinct xs}"
  morphisms elems NDList
  by (rule exI[of _ "[]"]) simp

definition NDlist :: "nat list \<Rightarrow> ndlist"
  where "NDlist xs = NDList (remdups xs)"

code_datatype NDlist

lemma elems_NDlist [code abstract]: "elems (NDlist xs) = remdups xs"
  by (simp add: NDlist_def NDList_inverse)

section \<open>One declaration the oracle cannot index\<close>

text \<open>
  A document marker is written with no space after the command keyword. The
  Python \<^verbatim>\<open>query\<close> requires whitespace there, so it does not see a command at
  all and the declaration is invisible to it; this engine uses Isabelle's own
  lexer, which treats \<^verbatim>\<open>\<^marker>\<close> as a formal comment. \<open>dev/DIVERGENCES.md\<close>
  records it as D2, and it costs the oracle 751 declarations in the Isabelle
  distribution alone.
\<close>

definition\<^marker>\<open>tag important\<close> marked_const :: nat
  where "marked_const = 7"

lemma marked_const_pos: "0 < marked_const"
  by (simp add: marked_const_def)

end
