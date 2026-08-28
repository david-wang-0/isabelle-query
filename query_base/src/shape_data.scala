/*  Title:      query_base/src/shape_data.scala

The two committed tables the shape width metrics read, ported as DATA.

  * `CORPUS_CONSTANTS` — bucket (c) of the layered variable/constant classifier
    (`Shape.classify_identifier`): names that appear FREE across many unrelated
    AFP entries yet are almost never BOUND, which is the signature of a shared
    library constant rather than a conventional free variable.  Harvested by the
    reference implementation's `scripts/harvest_corpus_constants.py` over the AFP
    (Isabelle2025-2): 159 entries >= 10,000 lines, free-DF >= 20, bound-fraction
    <= 0.15.

  * `NOTATION` — operator glyph -> canonical Isabelle constant, behind
    `const_canon_est`.  Resolved once from the HOL-Analysis heap by the reference
    implementation's `scripts/extract_notation.py`.  A glyph the table does not
    carry falls back to itself, so the map only ever DEDUPS a constant, never
    loses one.

GENERATED CONTENT — regenerate in the reference implementation, not here.
*/

package isabelle.query


object Shape_Data {
  /* 73 names. */
  val CORPUS_CONSTANTS: Set[String] = Set(
    "Collect", "False", "Inl", "Inr", "List", "Max", "Min", "None", "Pair", "Pow", "Some",
    "Suc", "True", "UNIV", "Var", "abs", "bij_betw", "butlast", "card", "concat",
    "distinct", "div", "dom", "drop", "dvd", "empty", "filter", "finite", "fold", "foldl",
    "foldr", "fst", "hd", "id", "infinite", "inj", "inj_on", "insert", "int", "inv",
    "inverse", "last", "length", "list", "list_all", "map", "map_of", "map_option", "max",
    "measure", "min", "mod", "nat", "norm", "o", "range", "real", "real_of_int",
    "replicate", "res", "rev", "set", "size", "snd", "sorted", "sqrt", "sum", "sum_list",
    "take", "tl", "undefined", "wf", "zip"
  )

  /* 33 glyphs. */
  val NOTATION: Map[String, String] = Map(
    "*" -> "Groups.times_class.times", "+" -> "Groups.plus_class.plus",
    "-" -> "Groups.minus_class.minus", "/" -> "Rings.divide_class.divide",
    "<" -> "Orderings.ord_class.less", "=" -> "HOL.eq", ">" -> "Orderings.ord_class.less",
    "@" -> "List.append", "\\<Longrightarrow>" -> "Pure.imp", "\\<and>" -> "HOL.conj",
    "\\<circ>" -> "Fun.comp", "\\<equiv>" -> "Pure.eq",
    "\\<ge>" -> "Orderings.ord_class.less_eq", "\\<i>" -> "Complex.imaginary_unit",
    "\\<in>" -> "Set.member", "\\<int>" -> "Int.ring_1_class.Ints",
    "\\<inter>" -> "Lattices.inf_class.inf", "\\<le>" -> "Orderings.ord_class.less_eq",
    "\\<longleftrightarrow>" -> "HOL.eq", "\\<longlongrightarrow>" -> "Filter.filterlim",
    "\\<longrightarrow>" -> "HOL.implies", "\\<nat>" -> "Nat.semiring_1_class.Nats",
    "\\<noteq>" -> "HOL.Not", "\\<notin>" -> "HOL.Not", "\\<or>" -> "HOL.disj",
    "\\<rat>" -> "Rat.field_char_0_class.Rats", "\\<real>" -> "Real_Vector_Spaces.Reals",
    "\\<subset>" -> "Orderings.ord_class.less",
    "\\<subseteq>" -> "Orderings.ord_class.less_eq",
    "\\<supset>" -> "Orderings.ord_class.less",
    "\\<supseteq>" -> "Orderings.ord_class.less_eq", "\\<times>" -> "Product_Type.Sigma",
    "\\<union>" -> "Lattices.sup_class.sup"
  )
}
