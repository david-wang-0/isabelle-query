/*  Title:      dev/p11mcp-probe.scala

Direct, heap-free checks for the optional PIDE MCP query adapter.  The shell
runner creates every fixture below; counts are hand-computed from those files.
*/

package isabelle.query.pide_mcp_dev

import isabelle.*
import isabelle.pide.mcp.{JSON_Object, PIDE_MCP_Sessions, PIDE_MCP_Tool, PIDE_MCP_Tool_Result, PIDE_MCP_Tools, Result}
import isabelle.query.Query_Server
import isabelle.query.pide_mcp.Query_MCP_Tool

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}


object P11_MCP_Probe {
  final case class Reply(exit: Int, stdout: String, stderr: String)

  def main(args: Array[String]): Unit = {
    Isabelle_System.init()

    val fix = sys.env("P11MCP_FIX")
    val other = sys.env("P11MCP_OTHER")
    val empty = sys.env("P11MCP_EMPTY")
    val missing = sys.env("P11MCP_MISSING")
    val alias = sys.env("P11MCP_ALIAS")
    val source = Paths.get(sys.env("P11MCP_SOURCE"))
    val relative_source = sys.env("P11MCP_RELATIVE_SOURCE")
    val faildemo = sys.env.get("P11MCP_FAILDEMO").contains("1")

    var checks = 0
    var failures = 0

    def check(name: String, condition: Boolean, detail: => String = ""): Unit = {
      checks += 1
      if (condition) println("  ok    " + name)
      else {
        failures += 1
        println("  FAIL  " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
      }
    }

    def request(argv: List[String], env: Option[Map[String, String]] = None): JSON.Object.T = {
      val base = JSON_Object("argv" -> argv)
      env match {
        case None => base
        case Some(values) => base + ("env" -> JSON_Object(values.toList: _*))
      }
    }

    def decode(value: JSON.T): Reply = value match {
      case JSON.Object(obj) =>
        Reply(
          JSON.int(obj, "exit").getOrElse(error("reply has no integer exit")),
          JSON.string(obj, "stdout").getOrElse(error("reply has no string stdout")),
          JSON.string(obj, "stderr").getOrElse(error("reply has no string stderr")))
      case _ => error("handler did not return a JSON object")
    }

    def run(argv: List[String], dirs: List[Path] = Nil,
      env: Option[Map[String, String]] = None
    ): Reply =
      decode(Exn.release(Query_MCP_Tool.handle(request(argv, env), dirs)))

    def expect_error(name: String, args: JSON.Object.T, needle: String): Unit =
      Query_MCP_Tool.handle(args) match {
        case Exn.Exn(exn) =>
          val message = Exn.message(exn)
          check(name, message.contains(needle), message)
        case Exn.Res(value) => check(name, false, "unexpected result: " + JSON.Format(value))
      }

    val fix_dir = Path.explode(fix)
    val other_dir = Path.explode(other)
    val alias_dir = Path.explode(alias)

    Query_Server.close_all()

    println("1. component API and strict handler schema")
    val services = Isabelle_System.make_services(classOf[PIDE_MCP_Tools])
    val query_tools: List[PIDE_MCP_Tool] =
      services.flatMap(_.entries).filter(_.name == "query")
    check("one query service is discoverable", query_tools.length == 1,
      query_tools.map(_.getClass.getName).mkString(", "))
    check("the generic tool omits annotations",
      query_tools.headOption.flatMap(_.annotations).isEmpty)

    val direct_tool = new Query_MCP_Tool
    val sessions = new PIDE_MCP_Sessions(Map("query" -> direct_tool), new Logger, Options.init())
    val progress = new Progress
    check("session manager starts with no running prover", Result.release(sessions.running_sessions(None)).isEmpty)

    def hosted(tool: PIDE_MCP_Tool, args: JSON.Object.T): Reply =
      tool.handle(sessions, args, progress) match {
        case PIDE_MCP_Tool_Result.Res(value) => decode(value)
        case PIDE_MCP_Tool_Result.Error(value) =>
          error("unexpected MCP tool error: " + JSON.Format(value))
      }

    val direct = hosted(direct_tool, request(List("-R", fix, "find", "alpha", "-c")))
    check("direct override accepts explicit root without a prover",
      direct == Reply(0, "1\n", ""), direct.toString)
    val service = hosted(query_tools.head, request(List("find", "alpha", "-c"),
      Some(Map("ISABELLE_QUERY_ROOT" -> fix))))
    check("discovered service accepts request root without a prover",
      service == Reply(0, "1\n", ""), service.toString)
    val hosted_no_root = hosted(direct_tool, request(List("find", "alpha", "-c")))
    check("override preserves CLI usage failure as exit two data",
      hosted_no_root.exit == 2 && hosted_no_root.stderr.contains("no session directory"),
      hosted_no_root.toString)
    for (tool <- List(direct_tool, query_tools.head)) {
      tool.handle(sessions, JSON_Object(), progress) match {
        case PIDE_MCP_Tool_Result.Error(message: String) =>
          check("malformed request becomes one MCP error string", message == "Missing argv parameter",
            message)
        case value => check("malformed request becomes one MCP error string", false, value.toString)
      }
    }

    expect_error("missing argv is rejected", JSON_Object(), "Missing argv")
    expect_error("non-array argv is rejected", JSON_Object("argv" -> "find"), "Bad argv")
    expect_error("non-string argv element is rejected",
      JSON_Object("argv" -> List("find", 1)), "Bad argv")
    expect_error("non-object env is rejected",
      JSON_Object("argv" -> List("-h"), "env" -> "bad"), "Bad env")
    expect_error("non-string env value is rejected",
      JSON_Object("argv" -> List("-h"),
        "env" -> JSON_Object("ISABELLE_QUERY_ROOT" -> 1)), "expected a string")
    expect_error("unknown env key is rejected",
      JSON_Object("argv" -> List("-h"), "env" -> JSON_Object("HOME" -> fix)),
      "Unknown environment")
    expect_error("unknown top-level key is rejected",
      JSON_Object("argv" -> List("-h"), "stdin" -> "text"), "Unknown query")

    println("2. lazy roots, aliases, overrides, and relative paths")
    val top_help = run(List("-h"))
    check("top help needs no session directory",
      top_help.exit == 0 && top_help.stdout.contains("Usage:"), top_help.toString)
    val command_help = run(List("find", "-h"), List(fix_dir, other_dir))
    check("command help does not evaluate an ambiguous default",
      command_help.exit == 0 && command_help.stdout.contains("find"), command_help.toString)
    val inert = run(List("-h", "-"))
    check("an inert sentinel retains CLI help behavior",
      inert.exit == 0 && inert.stderr.isEmpty, inert.toString)
    val lazy_bad_env = run(List("-h"), env = Some(Map("ISABELLE_QUERY_ROOT" -> missing)))
    check("help does not evaluate a bad request root",
      lazy_bad_env.exit == 0 && lazy_bad_env.stderr.isEmpty, lazy_bad_env.toString)

    val no_default = run(List("find", "alpha", "-c"))
    check("zero session directories require a root lazily",
      no_default.exit == 2 && no_default.stderr.contains("no session directory"),
      no_default.toString)
    val one_default = run(List("find", "alpha", "-c"), List(fix_dir))
    check("one session directory is the default",
      one_default == Reply(0, "1\n", ""), one_default.toString)
    val aliases = run(List("find", "alpha", "-c"), List(fix_dir, alias_dir))
    check("canonical aliases count as one directory",
      aliases == Reply(0, "1\n", ""), aliases.toString)
    val ambiguous = run(List("find", "alpha", "-c"), List(fix_dir, other_dir))
    check("several distinct directories are ambiguous",
      ambiguous.exit == 2 && ambiguous.stderr.contains("2 session directories"),
      ambiguous.toString)
    val explicit = run(List("-R", fix, "find", "alpha", "-c"),
      List(fix_dir, other_dir))
    check("explicit root bypasses an ambiguous default",
      explicit == Reply(0, "1\n", ""), explicit.toString)
    val env_default = run(List("find", "alpha", "-c"),
      env = Some(Map("ISABELLE_QUERY_ROOT" -> fix)))
    check("request query-root supplies a zero-directory default",
      env_default == Reply(0, "1\n", ""), env_default.toString)
    val env_order = run(List("find", "alpha", "-c"),
      env = Some(Map("ISABELLE_LAYOUT_ROOT" -> fix, "ISABELLE_QUERY_ROOT" -> other)))
    check("layout root precedes query root",
      env_order == Reply(0, "1\n", ""), env_order.toString)
    val explicit_over_env = run(List("-R", fix, "find", "alpha", "-c"),
      env = Some(Map("ISABELLE_QUERY_ROOT" -> other)))
    check("explicit root overrides request root environment",
      explicit_over_env == Reply(0, "1\n", ""), explicit_over_env.toString)
    val relative = run(List("lines", relative_source, "1..1"))
    check("relative file paths use the MCP process working directory",
      relative == Reply(0, "1| theory MCP_Fix\n", ""), relative.toString)

    println("3. output, errors, namespace isolation, and stdin denial")
    val zero = run(List("-R", fix, "find", "does_not_exist", "-c"))
    check("an honest empty search is exit zero data",
      zero == Reply(0, "0\n", ""), zero.toString)
    val unresolved = run(List("-R", fix, "callees", "does_not_exist"))
    check("an unresolved subject is exit one data",
      unresolved.exit == 1 && unresolved.stdout.isEmpty && unresolved.stderr.nonEmpty,
      unresolved.toString)
    val bad_root = run(List("-R", missing, "find", "alpha", "-c"))
    check("a missing explicit root is exit two data",
      bad_root.exit == 2 && bad_root.stdout.isEmpty && bad_root.stderr.contains("no such"),
      bad_root.toString)
    Query_MCP_Tool.handle(request(List("-R", empty, "find", "alpha", "-c"))) match {
      case Exn.Exn(exn) => check("empty-root warm refusal is a tool error",
        Exn.message(exn).contains("no ROOT") || Exn.message(exn).contains("no theor"),
        Exn.message(exn))
      case Exn.Res(value) =>
        check("empty-root warm refusal is a tool error", false, JSON.Format(value))
    }

    /* The runner starts this JVM with ISABELLE_QUERY_NAMESPACE=committed.
       Empty request env must still resolve the Pure fixture's own namespace. */
    val pure1 = run(List("-R", fix, "methods", "auto", "-c"))
    val committed = run(List("-R", fix, "methods", "auto", "-c"),
      env = Some(Map("ISABELLE_QUERY_NAMESPACE" -> "committed")))
    val pure2 = run(List("-R", fix, "methods", "auto", "-c"))
    check("host namespace environment is not inherited",
      pure1.exit == 1 && pure1.stderr.contains("resolved proof-method namespace"),
      pure1.toString)
    check("request namespace is honored",
      committed == Reply(0, "0\n", ""), committed.toString)
    check("request namespace does not leak to the next call",
      pure2.exit == 1 && pure2.stderr.contains("resolved proof-method namespace"),
      pure2.toString)

    def stdin_denied(name: String, argv: List[String]): Unit = {
      val reply = run(argv)
      check(name, reply.exit == 2 && reply.stdout.isEmpty &&
        reply.stderr.toLowerCase.contains("stdin"), reply.toString)
    }
    stdin_denied("grep exact sentinel is denied at consumption",
      List("-R", fix, "grep", "alpha", "-"))
    stdin_denied("lines separate stdin form is denied at consumption",
      List("-R", fix, "lines", "-", "1..2"))
    stdin_denied("lines locus stdin form is denied at consumption",
      List("-R", fix, "lines", "--", "-:1..2"))
    val grep_locus_option = run(List("-R", fix, "grep", "alpha", "-:1..2"))
    check("grep locus-like sentinel is an option error without --",
      grep_locus_option.exit == 2 && grep_locus_option.stderr.contains("unrecognized argument: -:") &&
        !grep_locus_option.stderr.toLowerCase.contains("stdin"),
      grep_locus_option.toString)
    val grep_locus_positional =
      run(List("-R", fix, "grep", "alpha", "--", "-:1..2"))
    check("grep positional locus-like sentinel remains non-stdin",
      grep_locus_positional.exit == 1 &&
        !grep_locus_positional.stderr.toLowerCase.contains("stdin"),
      grep_locus_positional.toString)

    println("4. process-owned warm reuse, edits, and fresh tool instances")
    Query_Server.close_all()
    val warm1 = run(List("-R", fix, "find", "alpha", "-c"))
    val stats1 = Query_Server.open_indexes
    val warm2 = run(List("-R", fix, "find", "alpha", "-c"))
    val stats2 = Query_Server.open_indexes
    val stat1 = stats1.headOption.getOrElse(JSON.Object.empty)
    val stat2 = stats2.headOption.getOrElse(JSON.Object.empty)
    val id1 = JSON.string(stat1, "index_id").getOrElse("")
    val id2 = JSON.string(stat2, "index_id").getOrElse("")
    val reparsed2 = JSON.int(stat2, "reparsed").getOrElse(-1)
    val uses1 = JSON.long(stat1, "uses").getOrElse(-1L)
    val uses2 = JSON.long(stat2, "uses").getOrElse(-1L)
    check("two warm calls retain one registry entry",
      warm1.stdout == "1\n" && warm2.stdout == "1\n" &&
        stats1.length == 1 && stats2.length == 1,
      s"entries=${stats1.length},${stats2.length} replies=$warm1,$warm2")
    check("the unchanged call reuses the same index ID",
      id1.nonEmpty && id2 == id1, s"ids=$id1,$id2")
    check("the unchanged call reparses zero sections",
      reparsed2 == 0, s"reparsed=$reparsed2")
    check("the unchanged call advances the use count",
      uses1 >= 0L && uses2 > uses1, s"uses=$uses1,$uses2")

    val old_source = Files.readString(source, StandardCharsets.UTF_8)
    val edit = "lemma gamma: \"PROP P\"\n  sorry"
    if (old_source.contains("(* EDIT_MARKER *)"))
      Files.writeString(source, old_source.replace("(* EDIT_MARKER *)", edit),
        StandardCharsets.UTF_8)
    val fresh = run(List("-R", fix, "find", "gamma", "-c"))
    check("an on-disk edit is visible on the next warm call",
      fresh == Reply(0, "1\n", ""), fresh.toString)

    val before_fresh_tool = Query_Server.registry_size
    val fresh_tool = hosted(new Query_MCP_Tool, request(List("-R", fix, "find", "gamma", "-c")))
    check("fresh tool instance shares the process-owned cache",
      fresh_tool == Reply(0, "1\n", "") && before_fresh_tool > 0 &&
        Query_Server.registry_size == before_fresh_tool, fresh_tool.toString)

    check("deliberate failability", !faildemo, "P11MCP_FAILDEMO=1")

    if (failures == 0) {
      Query_Server.close_all()
      println(s"P11MCP OK: $checks checks")
      Console.out.flush()
      Console.err.flush()
      sys.exit(0)
    }
    else {
      println(s"P11MCP FAIL: $failures of $checks checks")
      Console.out.flush()
      Console.err.flush()
      sys.exit(1)
    }
  }
}
