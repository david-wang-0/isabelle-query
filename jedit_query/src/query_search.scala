/*  Title:      jedit_query/src/query_search.scala

What the dockable displays: a result set, grouped by theory.

ONE node model for every kind of answer.  A "where is this declared" result and
a "who cites this" result are the same three levels — result set, theory, line
— and differ only in how the panel opens them (`Result_Kind.expand_groups`) and
in what a leaf's preview says.  That is deliberate: the navigation affordances
(open in the active pane, open in a new pane, the popup, the keys) are written
once against `Hit`, so every future view inherits them instead of growing its
own.

The engine entry points are the CLI-free pair.  `Usage.find_callers` is O(one
name x source) and answers a session in well under a second;
`Usage_Graph.build_call_graph` is a corpus-global build (tens of seconds, and
gigabytes, over a whole AFP) and is worth it only for the transitive verbs.  A
plugin that built the graph on a right-click would feel broken, so this module
does not know it exists.

No rendering happens here — a `Result` is data, and the dockable decides what a
hit looks like.
*/

package isabelle.jedit_query


import isabelle.query.Usage

import java.nio.file.{Path => JPath}

import scala.collection.mutable


object Query_Search {
  /* How the panel opens a result set of this kind.  A declaration is one line
     and the user asked to see it, so it opens expanded; a usage list can be
     hundreds of lines across dozens of theories, so its per-theory nodes open
     collapsed and the tree's own arrows reveal them. */
  sealed abstract class Result_Kind(val expand_groups: Boolean)

  object Result_Kind {
    case object Usages extends Result_Kind(false)
    case object Definition extends Result_Kind(true)
  }

  /* One line of source.  `text` is the RAW line, as the engine returns it:
     file form, `\<alpha>` and all.  Decoding for display is the view's
     business. */
  final case class Hit(theory: String, path: Option[JPath], line: Int, text: String)

  final case class Group(theory: String, path: Option[JPath], hits: List[Hit]) {
    def count: Int = hits.length
  }

  /* `label` is what the result-set root says; `name` is what a preview
     highlights. */
  final case class Result(
    kind: Result_Kind,
    label: String,
    name: String,
    groups: List[Group],
    definition: Option[Hit],
    note: String
  ) {
    def hits: Int = groups.foldLeft(0)(_ + _.count)
    def theories: Int = groups.length
    def is_empty: Boolean = groups.isEmpty
  }


  /* Group in the order the engine emitted them — section load order, which is
     the build's own order and therefore stable between runs. */
  private def group(
    snapshot: Query_Index.Snapshot,
    triples: List[(String, Int, String)]
  ): List[Group] = {
    val buf = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[Hit]]
    for ((theory, line, text) <- triples) {
      val path = snapshot.path_of(theory)
      buf.getOrElseUpdate(theory, new mutable.ListBuffer[Hit]) += Hit(theory, path, line, text)
    }
    (for ((theory, hits) <- buf) yield Group(theory, snapshot.path_of(theory), hits.toList)).toList
  }

  private def source_line(snapshot: Query_Index.Snapshot, theory: String, line: Int,
    fallback: String
  ): String =
    snapshot.section(theory) match {
      case Some(sec) if line >= 1 && line <= sec.lines.length => sec.lines(line - 1)
      case _ => fallback
    }

  /* The declaration site, when the project declares the name at all.  A
     citation of something the project only IMPORTS (`mono`, `refl`) resolves
     to nothing, and that is a legitimate answer — the usages are still real. */
  def definition_hit(snapshot: Query_Index.Snapshot, name: String): Option[Hit] =
    snapshot.definition(name).map { case (theory, entry) =>
      Hit(theory, snapshot.path_of(theory), entry.thy_line,
        source_line(snapshot, theory, entry.thy_line, entry.text))
    }

  private def kind_of(snapshot: Query_Index.Snapshot, name: String): String =
    snapshot.definition(name) match {
      case Some((theory, entry)) => " (" + entry.tag + " in " + theory + ")"
      case None => ""
    }

  /* Must run on the index's worker thread, inside `with_namespace`: whether a
     line that says `auto` is a citation or a method invocation depends on the
     table bound for THIS project. */
  def usages(
    snapshot: Query_Index.Snapshot,
    name: String,
    external: Boolean = false,
    note: String = ""
  ): Result =
    Result(
      kind = Result_Kind.Usages,
      label = "usages of " + name + kind_of(snapshot, name) +
        (if (external) " [external]" else ""),
      name = name,
      groups = group(snapshot, Usage.find_callers(snapshot.sections, name, external)),
      definition = definition_hit(snapshot, name),
      note = note)

  /* Where a name is declared.  A direct lookup on the index — no scan — and
     the same node model, so the panel's navigation is unchanged.  The IDE
     phase replaces the leaf's preview with the declaration and its body; the
     shape of the answer does not change with it. */
  def definition(
    snapshot: Query_Index.Snapshot,
    name: String,
    note: String = ""
  ): Result = {
    val hit = definition_hit(snapshot, name)
    Result(
      kind = Result_Kind.Definition,
      label = "definition of " + name + kind_of(snapshot, name),
      name = name,
      groups = hit.toList.map(h => Group(h.theory, h.path, List(h))),
      definition = hit,
      note = note)
  }
}
