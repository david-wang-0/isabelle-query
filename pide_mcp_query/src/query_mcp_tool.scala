/*  Title:      pide_mcp_query/src/query_mcp_tool.scala

Offer the query engine as one optional PIDE MCP tool.  The engine owns the
warm cache; this adapter owns only the MCP request/response boundary.
*/

package isabelle.query.pide_mcp

import isabelle.*
import isabelle.pide.mcp.{JSON_Object, PIDE_MCP_Sessions, PIDE_MCP_Tool, PIDE_MCP_Tool_Result, PIDE_MCP_Tools}
import isabelle.query.{CLI, Discovery, Query_Server}

import java.nio.file.{Path => JPath, Paths}


object Query_MCP_Tool {
  private val top_keys = Set("argv", "env")
  private val env_keys = CLI.request_env.toSet

  private def unknown_keys(kind: String, actual: Set[String], allowed: Set[String]): Unit = {
    val unknown = actual -- allowed
    if (unknown.nonEmpty)
      error(s"Unknown $kind parameter(s): ${unknown.toList.sorted.mkString(", ")}")
  }

  private def request_argv(args: JSON.Object.T): List[String] = {
    unknown_keys("query", args.keySet, top_keys)
    args.get("argv") match {
      case None => error("Missing argv parameter")
      case Some(_) =>
        JSON.strings(args, "argv").getOrElse(
          error("Bad argv parameter: expected an array of strings"))
    }
  }

  private def request_env(args: JSON.Object.T): Map[String, String] =
    args.get("env") match {
      case None => Map.empty
      case Some(JSON.Object(obj)) =>
        unknown_keys("environment", obj.keySet, env_keys)
        obj.iterator.map {
          case (key, value: String) => key -> value
          case (key, _) => error(s"Bad environment value for $key: expected a string")
        }.toMap
      case Some(_) => error("Bad env parameter: expected an object of string values")
    }

  private def canonical_dirs(dirs: List[Path]): List[JPath] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[JPath]
    for (dir <- dirs) seen += Discovery.real(dir.expand.file.toPath.toAbsolutePath)
    seen.toList
  }

  private def ambient_root(dirs: List[Path], env: Map[String, String]): () => JPath =
    () => {
      CLI.root_env.iterator.flatMap(k => env.get(k).filter(_.nonEmpty)).nextOption() match {
        case Some(value) => CLI.default_root_from(Paths.get(""), Some(value))
        case None =>
          canonical_dirs(dirs) match {
            case List(root) => root
            case Nil =>
              throw new CLI.Usage_Error(
                "PIDE MCP query has no session directory; pass -R/--root")
            case roots =>
              throw new CLI.Usage_Error(
                s"PIDE MCP query has ${roots.length} session directories; " +
                  "pass -R/--root to choose one")
          }
      }
    }

  def handle(args: JSON.Object.T, session_dirs: List[Path] = Nil): Exn.Result[JSON.T] =
    Exn.capture {
      val argv = request_argv(args)
      val env = request_env(args)
      val result =
        Query_Server.run(
          argv = argv,
          cwd = None,
          env_root = None,
          env = env,
          limit = Query_Server.default_limit,
          host_ambient_root = Some(ambient_root(session_dirs, env)),
          allow_stdin = false)
      JSON_Object(
        "exit" -> result.exit,
        "stdout" -> result.out,
        "stderr" -> result.err)
    }

  private val env_descriptions: Map[String, String] = Map(
    "ISABELLE_LAYOUT_ROOT" -> "Request-local layout root (overridden by CLI -R/--root).",
    "ISABELLE_QUERY_ROOT" -> "Request-local query root (overridden by CLI -R/--root).",
    "ISABELLE_QUERY_NAMESPACE" ->
      "Request-local namespace selection; use committed for the committed broad table.")

  private val env_schema: JSON.Object.T =
    JSON_Object(
      "type" -> "object",
      "properties" -> JSON_Object(CLI.request_env.map(key =>
        key -> JSON_Object(
          "type" -> "string",
          "description" -> env_descriptions.getOrElse(key, "Request-local query environment."))
      ): _*),
      "additionalProperties" -> false)

  val schema: JSON.Object.T =
    JSON_Object(
      "type" -> "object",
      "properties" -> JSON_Object(
        "argv" -> JSON_Object(
          "type" -> "array",
          "items" -> JSON_Object("type" -> "string"),
          "description" ->
            ("Arguments after `isabelle query`; use -h for the command schema. " +
              "CLI stdin input is unavailable in this resident host.")),
        "env" -> env_schema),
      "required" -> List("argv"),
      "additionalProperties" -> false)
}


class Query_MCP_Tool extends PIDE_MCP_Tool("query") {
  def description: String =
    "Query Isabelle/Isar source across a project using the `isabelle query` CLI grammar. " +
      "Pass argv without the executable or `query`; use -h for commands and options. " +
      "Without -R/--root, one canonical directory across running PIDE MCP sessions is used; zero or " +
      "multiple directories require a root. Relative paths use the MCP process working " +
      "directory. CLI stdin input is unavailable; pass a file path instead. Returns one " +
      "object containing exit, stdout, and stderr."

  def input_schema: JSON.Object.T = Query_MCP_Tool.schema

  override def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      Exn.release(Query_MCP_Tool.handle(args, sessions.all_running().flatMap(_.dirs)))
    }
}


class Query_MCP_Tools extends PIDE_MCP_Tools(new Query_MCP_Tool)
