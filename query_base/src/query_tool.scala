/* Isabelle Query — fast syntax-aware queries over an Isabelle/Isar project.

Scala rewrite of the Python isabelle-query tool (see PLAN.md).
CLI entry point: `isabelle query …`.
*/

package isabelle.query


import isabelle.*


object Query_Tool {
  val version = "0.8.0-scala"

  def main_tool(args: List[String]): Unit =
    args match {
      case ("-V" | "--version") :: _ => Output.writeln("query " + version)
      case _ =>
        Output.writeln(
          "isabelle query: skeleton of the Scala rewrite — commands arrive per PLAN.md")
    }

  val isabelle_tool: Isabelle_Tool =
    Isabelle_Tool("query", "query an Isabelle/Isar project (syntax-aware, no build)",
      Scala_Project.here, main_tool)
}

class Query_Tools extends Isabelle_Scala_Tools(Query_Tool.isabelle_tool)
