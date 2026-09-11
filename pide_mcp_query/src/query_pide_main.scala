/*  Title:      pide_mcp_query/src/query_pide_main.scala

Optional query endpoint for the lifetime of the upstream PIDE MCP server.
*/

package isabelle.query.pide_mcp

import isabelle.Command_Line
import isabelle.pide.mcp.PIDE_MCP
import isabelle.query.Query_Host


object Query_PIDE_Main {
  def main(args: Array[String]): Unit =
    Command_Line.tool {
      // The usual help invocation needs no endpoint. Leave parsing to upstream.
      val arguments = args.toList
      if (arguments == List("-?")) PIDE_MCP.isabelle_tool.body(arguments)
      else {
        // Upstream Getopts calls sys.exit on usage errors, bypassing finally.
        Runtime.getRuntime.addShutdownHook(
          new Thread(() => Query_Host.stop(), "query_pide_shutdown"))
        Query_Host.with_host("pide") { PIDE_MCP.isabelle_tool.body(arguments) }
      }
    }
}
