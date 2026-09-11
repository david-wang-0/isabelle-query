package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_context_flag {
  def run(): Unit = {
    test("test_cli_context_flag.ContextFlag.test_callers_C_flag_is_gone") {

    locally {
raises(args("callers", "foo", "-C", "3"))
    }
    }
    test("test_cli_context_flag.ContextFlag.test_callers_accepts_U") {

    locally {
equal(args("callers", "foo", "-U", "3").str("context"), Some("3"))
    }
    }
    test("test_cli_context_flag.ContextFlag.test_per_command_defaults_preserved") {

    locally {
for ((c,d) <- Map("theory" -> 2, "outline" -> 2, "find" -> 2, "show" -> 2, "callers" -> 0)) equal(command(c).context_default,d,c)
    }
    }
    test("test_cli_context_flag.ContextFlag.test_short_flag_is_U_everywhere") {

    locally {
for (c <- List("theory", "outline", "find", "show", "callers")) equal(command(c).opts.find(_.dest == "context").get.strings, List("-U", "--context"), c)
    }
    }
  }
}
