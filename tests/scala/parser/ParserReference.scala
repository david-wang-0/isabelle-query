package isabelle.query.regression
import isabelle.query.*
import scala.collection.mutable
/** Independent per-name reference from tests/support.py; shares the original
  * masking/attribution primitives but never invokes production build_call_graph.
  * Kept parser-local because the parser suite compiles independently of graph.
  */
private[regression] object ParserReference {
  def matches(name: String, text: String): Boolean =
    Py.compile(Commands.isa_word_pattern(name)).matcher(text).find()
  def use(line: String, name: String = "simp", derived: Boolean = false): Boolean =
    Usage_Graph.shadowed_uses_on_line(line, Set(name), derived).nonEmpty
  def edges(g: Usage_Graph.Call_Graph): Set[(String, String)] =
    g.callers.iterator.flatMap { case (name, callers) => callers.map(_ -> name) }.toSet
  def graph(sections: List[Theory_Section], drop_upto: Int = 1,
    derived: Boolean = false): Usage_Graph.Call_Graph = {
    val names = sections.flatMap(_.entries).filter(e =>
      Usage_Graph.citable_tags(e.tag) && e.name != "?" && e.name.length > drop_upto &&
        !e.name.forall(_.isDigit)).map(_.name).toSet
    val spellings = names.map(n => n -> (List(n) :::
      (if (derived) List(n + "_def", n + "_defs").filterNot(names) else Nil))).toMap
    val defs = Usage_Graph.build_def_sites(sections, Some(names))
    val noise = Usage_Graph.noise_ranges(sections)
    val index = Usage_Graph.build_line_index(sections)
    val callers = mutable.Map.from(names.map(_ -> mutable.Set.empty[String]))
    val callees = mutable.Map.empty[String, mutable.Set[String]]
    val antiq = Py.compile("""@\{(?:text|thm|term|const)\s+["']?\w+["']?\}""")
    for (sec <- sections; (line, i) <- sec.live_source.zipWithIndex) {
      val number = i + 1
      if (!noise(sec.path).exists { case (lo, hi) => lo <= number && number <= hi }) {
        val stripped = antiq.matcher(line).replaceAll("")
        for (name <- names if spellings(name).exists(s => stripped.contains(s) && matches(s, stripped))) {
          val atDefinition = defs(sec.path).getOrElse(name, Nil).exists {
            case (lo, hi) => lo <= number && number <= hi
          }
          val positional = !Namespace.census.non_citation(name) || use(line, name, derived)
          val owner = Usage_Graph.entry_at_line(index(sec.path), number)
          if (!atDefinition && positional && !owner.exists(_.name == "?")) {
            val caller = owner.map(_.name).getOrElse(sec.theory + ":<toplevel>")
            callers(name) += caller
            callees.getOrElseUpdate(caller, mutable.Set.empty) += name
          }
        }
      }
    }
    Usage_Graph.Call_Graph(callers.view.mapValues(_.toSet).toMap,
      callees.view.mapValues(_.toSet).toMap, names)
  }
}
