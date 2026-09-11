package isabelle.query.regression

import isabelle.query.*
import java.io.StringWriter
import java.nio.file.{Path as JPath}
import scala.collection.mutable
import TestSupport.*

/** Graph-local adapters and the independent O(lines x names) reference.
  * The reference searches each declared name with boundary regexes; it never
  * calls build_call_graph or uses its token/hash candidate extraction.
  * Like tests/support.py it shares source masks, name-boundary rules and the
  * positional shadowed-name primitive; hand-computed edges remain alongside it.
  */
object GraphSupport {
  def matches(name: String, text: String): Boolean =
    Py.compile(Commands.isa_word_pattern(name)).matcher(text).find()
  def use(line: String, name: String = "simp", derived: Boolean = false): Boolean =
    Usage_Graph.shadowed_uses_on_line(line, Set(name), derived).nonEmpty
  def scan(text: String, only: Option[String]): (Map[String, Int], List[Usage_Graph.Method_Use]) = {
    val (counts, located) = Usage_Graph.scan_methods(List(parse(text)), only)
    (counts.toMap, located)
  }
  def edges(g: Usage_Graph.Call_Graph): Set[(String, String)] =
    g.callers.iterator.flatMap { case (name, callers) => callers.map(_ -> name) }.toSet
  def reference(sections: List[Theory_Section], drop_upto: Int = 1,
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
  def checkedGraph(sections: List[Theory_Section]): Usage_Graph.Call_Graph = {
    val fast = Usage_Graph.build_call_graph(sections)
    val ref = reference(sections)
    equal(fast.callers, ref.callers); equal(fast.callees, ref.callees)
    fast
  }
  def load(root: JPath): List[Theory_Section] = {
    val session = new CLI.Session(new Out(new StringWriter), new Out(new StringWriter))
    session.root_override = Some(root)
    session.env = Map("ISABELLE_QUERY_NAMESPACE" -> "committed").get
    session.load_index()
  }
  def parsed(verb: String, args: List[String]): CLI.Ns = {
    val cmd = CLI.commands.find(_.names.contains(verb)).get
    CLI.parse(cmd.opts, cmd.pos, args)
  }
  def lineFor(text: String, theory: String): String =
    text.linesIterator.find(line => line.matches("\\s+" + java.util.regex.Pattern.quote(theory) + "\\s+\\(.*")).getOrElse("")
  def scope(sections: List[Theory_Section], names: List[String]): (List[Theory_Section], String) = {
    val err = new StringWriter
    val session = new CLI.Session(new Out(err), new Out(new StringWriter))
    val ns = new CLI.Ns
    ns.appended("theory_scope") = mutable.ListBuffer.from(names)
    (CLI.scope_to_theories(session, ns, sections), err.toString)
  }
  def ownersBelong(sections: List[Theory_Section], hits: List[(JPath, Option[Entry])]): Unit = {
    var checked = 0
    hits.foreach { case (path, owner) => owner.foreach { entry =>
      checked += 1
      check(sections.filter(_.path == path).flatMap(_.entries).exists(_.name == entry.name),
        path.toString + ": " + entry.name)
    } }
    check(checked > 0, "nothing was checked")
  }
  def jsonObject(text: String): Map[String, Any] = isabelle.JSON.parse(text) match {
    case isabelle.JSON.Object(fields) => fields
    case other => throw new AssertionError("not a JSON object: " + other)
  }
  def jsonNodes(data: Map[String, Any]): List[Map[String, Any]] =
    data("nodes").asInstanceOf[List[Map[String, Any]]]
  def jsonEdges(data: Map[String, Any]): List[List[String]] =
    data("edges").asInstanceOf[List[List[String]]]
  def dotToken(raw: String): String = {
    val sec = parse("theory T imports Main begin\nlemma named: \"True\" by simp\nend\n")
    val renamed = new Theory_Section(sec.theory, sec.path,
      sec.entries.map(_.copy(name = raw)), sec.source, sec.regions)
    val out = new StringWriter
    Usage.cmd_graph(new Out(out), List(renamed), "citation", "dot", Flags())
    out.toString.linesIterator.map(_.trim).find(_.startsWith("\"")).get.stripSuffix(";")
  }
  def dotUnquote(token: String): String = {
    check(token.startsWith("\"") && token.endsWith("\""), token)
    val body = token.substring(1, token.length - 1)
    val out = new StringBuilder
    var i = 0
    while (i < body.length) {
      if (body(i) == '\\' && i + 1 < body.length) i += 1
      out.append(body(i)); i += 1
    }
    out.toString
  }
}
