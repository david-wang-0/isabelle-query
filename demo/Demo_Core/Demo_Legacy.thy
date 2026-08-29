(*  Title:      Demo_Core/Demo_Legacy.thy
    Author:     David Wang, with Claude Opus 5

    An import that nothing cites.  Demo_Proofs imports this theory and never
    mentions a name from it, which is the one disagreement `deps` and `refs`
    are built to expose.
*)

theory Demo_Legacy
  imports Demo_Types
begin

section \<open>Superseded arithmetic\<close>

paragraph \<open>The two deepest heading levels live here.\<close>

text \<open>
  \<^verbatim>\<open>paragraph\<close> and \<^verbatim>\<open>subparagraph\<close> are two of Isabelle's six heading commands and
  \<^verbatim>\<open>query outline\<close> refuses them, with a diagnostic and exit 1. That is
  deliberate: the reference Python implementation indexes a fixed four-entry
  table there and dies with a \<^verbatim>\<open>KeyError\<close>, and this engine is byte-compatible
  with it. Inventing an indent it never printed would be the larger change.
  Every other verb reads this theory normally.
\<close>

subparagraph \<open>Including this one.\<close>

definition legacy_scale :: "nat \<Rightarrow> nat"
  where "legacy_scale n = 2 * n"

lemma legacy_scale_mono: "m \<le> n \<Longrightarrow> legacy_scale m \<le> legacy_scale n"
  by (simp add: legacy_scale_def)

end
