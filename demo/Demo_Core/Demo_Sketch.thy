(*  Title:      Demo_Core/Demo_Sketch.thy
    Author:     Andras Salamon, with Claude Opus 5

    AN ORPHAN.  This file sits in the session directory and no ROOT-declared
    theory imports it, so `isabelle build` never compiles it and `query` never
    loads it.  Discovery is the import closure of the declared roots, not a
    glob over *.thy.

    Nothing below is reported by `query summary`, `query theory Demo_Sketch`,
    `query find sketch_.*` or `query dump-theories`.  It is the negative
    control for session discovery.
*)

theory Demo_Sketch
  imports Main
begin

definition sketch_double :: "nat \<Rightarrow> nat"
  where "sketch_double n = n + n"

lemma sketch_double_even: "even (sketch_double n)"
  by (simp add: sketch_double_def)

end
