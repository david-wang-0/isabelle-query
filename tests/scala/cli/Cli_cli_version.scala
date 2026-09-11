package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_version {
  def run(): Unit = {
    test("test_cli_version.VersionFlag.test_resolver_returns_nonempty") {

    locally {
check(CLI.version.nonEmpty)
    }
    }
    test("test_cli_version.VersionFlag.test_version_exits_zero") {

    locally {
withProject(Nil){root=> val r=cli(List("--version"),root); equal(r.exit,0); equal(r.out.trim,CLI.prog+" "+CLI.version); equal(r.err,"") }
    }
    }
    test("test_cli_version.VersionFlag.test_version_prints_query_and_resolved_version") {

    locally {
withProject(Nil){root=> val r=cli(List("--version"),root); equal(r.exit,0); equal(r.out.trim,CLI.prog+" "+CLI.version); equal(r.err,"") }
    }
    }
    test("test_cli_version.VersionPosition.test_after_a_nested_shape_verb") {

    locally {
withProject(Nil){root=> val r=cli(List("shape", "census", "--version"),root); equal(r.exit,0); equal(r.out.trim,CLI.prog+" "+CLI.version); equal(r.err,"") }
    }
    }
    test("test_cli_version.VersionPosition.test_after_a_subcommand") {

    locally {
withProject(Nil){root=> val r=cli(List("callers", "foo", "--version"),root); equal(r.exit,0); equal(r.out.trim,CLI.prog+" "+CLI.version); equal(r.err,"") }
    }
    }
    test("test_cli_version.VersionPosition.test_short_form_at_top_level") {

    locally {
withProject(Nil){root=> val r=cli(List("-V"),root); equal(r.exit,0); equal(r.out.trim,CLI.prog+" "+CLI.version); equal(r.err,"") }
    }
    }
    test("test_cli_version.VersionPosition.test_short_form_still_means_verbatim_on_show") {

    locally {
check(args("show","foo","-V").bool("verbatim"))
    }
    }
    test("test_cli_version.VersionPosition.test_wins_over_a_missing_required_positional") {

    locally {
withProject(Nil){root=> val r=cli(List("callers", "--version"),root); equal(r.exit,0); equal(r.out.trim,CLI.prog+" "+CLI.version); equal(r.err,"") }
    }
    }
  }
}
