/*  Title:      jedit_query/src/query_name_search.scala

Search by name from the panel itself: type a declaration's name, pick a finder,
get a normal result set.

Everything reachable from the right-click menu needs a name under the caret,
which is fine when you are reading the theory that mentions it and useless when
you are not.  Isar's own `code_thms c` / `print_interps L` take the name as an
ARGUMENT, and that is the affordance this restores: an input, resolved against
the index the panel already holds, and then any of the finders.

Two decisions, and this file exists so both are testable without a display:

  * RESOLUTION is exact-then-fuzzy.  A name that IS a declaration wins outright
    -- a project with `map` and `map_map` must not have `map` mean the second
    because it scores higher -- and only a name that is not one falls through
    to `Query_Fuzzy`, the same ranking go-to-symbol uses, so what the two rank
    first is the same thing.

  * The OFFERED finders are gated by the same predicate as the context menu
    (`Query_Search.is_subject`, which is `Sites.resolve`, which is what the CLI
    exits 1 on).  Usages and definition are offered always, because they answer
    for any name at all -- including one this project only cites.  The two site
    verbs are offered only where they have an answer, and a COLD index (no
    snapshot yet) offers neither: the predicate needs entries, reading it must
    not parse on the EDT, and the same documented degradation already applies
    to the right-click menu.
*/

package isabelle.jedit_query


import isabelle.query.Sites


object Query_Name_Search {
  /* One offered finder: what the menu says, and what the panel then runs. */
  final case class Finder(label: String, kind: Query_Search.Result_Kind,
    external: Boolean = false)

  /* Answerable for any name, so never gated: `find_callers` is a text scan
     that needs no entry, and `definition` reports honestly when there is
     none. */
  val ungated: List[Finder] =
    List(
      Finder("Find usages", Query_Search.Result_Kind.Usages),
      Finder("Find external usages", Query_Search.Result_Kind.Usages, external = true),
      Finder("Find definition", Query_Search.Result_Kind.Definition))

  def finders(snapshot: Option[Query_Index.Snapshot], name: String): List[Finder] =
    if (name.isEmpty) Nil
    else
      ungated ::: snapshot.toList.flatMap { s =>
        (if (Query_Search.is_subject(s, name, Sites.locale_tags))
          List(Finder("Find instantiations", Query_Search.Result_Kind.Instantiations))
         else Nil) :::
        (if (Query_Search.is_subject(s, name, Sites.constant_tags))
          List(Finder("Find code equations", Query_Search.Result_Kind.Code_Equations))
         else Nil)
      }

  /* The ranked candidates for what has been typed so far -- `Query_Fuzzy` over
     the index's already-materialised name list, which is what makes this safe
     to run per keystroke on the EDT (`query_quick_open.scala` says why). */
  def candidates(snapshot: Option[Query_Index.Snapshot], typed: String, limit: Int
  ): List[Query_Fuzzy.Match] =
    snapshot match {
      case None => Nil
      case Some(s) => Query_Fuzzy.filter(typed.trim, s.entry_names.distinct, limit)
    }

  /* What a typed string MEANS.  An exact declaration name is itself; anything
     else is the best fuzzy match; a name that matches nothing at all is
     returned unchanged, so the finder still runs and the panel still reports
     -- refusing here would be a second, quieter way to say "no". */
  def resolve(snapshot: Option[Query_Index.Snapshot], typed: String): String = {
    val name = typed.trim
    if (name.isEmpty) ""
    else if (snapshot.exists(_.definition(name).isDefined)) name
    else candidates(snapshot, name, 1).headOption.map(_.name).getOrElse(name)
  }

  /* What the panel shows beside the field: what the typed text resolved to,
     and what the index says it is.  Empty when the two are the same word and
     nothing is known about it, so an exact name costs no noise. */
  def hint(snapshot: Option[Query_Index.Snapshot], typed: String): String = {
    val name = resolve(snapshot, typed)
    if (name.isEmpty) ""
    else {
      val what =
        for {
          s <- snapshot
          (theory, entry) <- s.definition(name)
        } yield entry.tag + " in " + theory
      (if (name == typed.trim) name else "→ " + name) +
        what.map(w => " (" + w + ")").getOrElse("")
    }
  }
}
