(*  Title:      Demo_Core/Demo_Types.thy
    Author:     Andras Salamon, with Claude Opus 5

    One declaration of every kind the entry scanner recognises, plus the
    awkward spellings: a quoted name, a name carrying a markup symbol, and an
    `axiomatization` whose names are separately locatable.
*)

theory Demo_Types
  imports Main
begin

chapter \<open>A corpus written to be queried\<close>

text \<open>
  Nothing here is deep. Every declaration exists so that some verb of
  \<^verbatim>\<open>isabelle query\<close> has a named thing to point at, and \<open>DEMO.md\<close> names the
  verb beside it.
\<close>

section \<open>Containers\<close>

subsection \<open>A datatype, with a discriminator and selectors\<close>

text \<open>
  One command, five citable names: the entry \<open>tree\<close> plus the constructors
  \<open>Leaf\<close> and \<open>Node\<close>, the discriminator \<open>is_leaf\<close> and the selectors \<open>left\<close>,
  \<open>item\<close>, \<open>right\<close>. \<^verbatim>\<open>query show Node\<close> resolves any of them back to \<open>tree\<close>.
\<close>

datatype 'a tree =
    is_leaf: Leaf
  | Node (left: "'a tree") (item: 'a) (right: "'a tree")

subsubsection \<open>An instruction set\<close>

text \<open>
  Four heading depths, which is what \<^verbatim>\<open>query outline\<close> indents by. The other two
  Isabelle has --- \<^verbatim>\<open>paragraph\<close> and \<^verbatim>\<open>subparagraph\<close> --- are in \<^verbatim>\<open>Demo_Legacy\<close>,
  for the reason given there.
\<close>

datatype instr = Push (arg: int) | Add | Dup

subsection \<open>A record\<close>

text \<open>Fields one per line, which is how the field names stay locatable.\<close>

record account =
  owner :: string
  balance :: int

section \<open>Targets\<close>

subsection \<open>A class\<close>

class weight =
  fixes wt :: "'a \<Rightarrow> nat"

subsection \<open>Locales\<close>

locale assoc_op =
  fixes f :: "'a \<Rightarrow> 'a \<Rightarrow> 'a"
  assumes assoc: "f (f x y) z = f x (f y z)"

locale idem_op =
  fixes g :: "'a \<Rightarrow> 'a \<Rightarrow> 'a"
  assumes g_assoc: "g (g x y) z = g x (g y z)"
    and g_idem: "g x x = x"

text \<open>
  A locale whose name is a reserved word has to be written quoted --- \<open>functor\<close>
  is an Isar command in HOL. The name is read out of the \<^emph>\<open>live\<close> view, because
  the outer view blanks exactly where the quotes put it.
\<close>

locale "functor" =
  fixes F :: "'a list \<Rightarrow> 'b list"
  assumes F_nil: "F [] = []"

section \<open>Names that are awkward to spell\<close>

text \<open>A markup symbol inside an identifier is ordinary Isabelle.\<close>

definition f\<^sub>1 :: "nat \<Rightarrow> nat"
  where "f\<^sub>1 n = Suc n"

lemma f\<^sub>1_gt: "n < f\<^sub>1 n"
  by (simp add: f\<^sub>1_def)

section \<open>Axiomatization\<close>

text \<open>
  \<^verbatim>\<open>axiomatization\<close> is the one command whose bound names are separate entries:
  each is written on its own line and so is independently locatable.
\<close>

axiomatization cost :: "instr \<Rightarrow> nat" and
  fuel :: nat where
  cost_pos: "0 < cost i" and
  fuel_pos: "0 < fuel"

end
