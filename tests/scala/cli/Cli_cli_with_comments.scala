package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_with_comments {
  def run(): Unit = {
    test("test_cli_with_comments.WithCommentsToggle.test_both_search_verbs_accept_with_comments") {

    locally {
for(c <- List("find","grep")) check(args(c,"foo","--with-comments").bool("with_comments"))
    }
    }
    test("test_cli_with_comments.WithCommentsToggle.test_find_a_flag_still_means_show_all") {

    locally {
val n=args("find","foo","-a"); check(n.bool("all")); check(!n.bool("with_comments"))
    }
    }
    test("test_cli_with_comments.WithCommentsToggle.test_grep_a_flag_is_gone") {

    locally {
raises(args("grep","foo","-a"))
    }
    }
    test("test_cli_with_comments.WithCommentsToggle.test_prose_help_is_shared") {

    locally {
equal(command("find").opts.find(_.dest=="with_comments").get.help,command("grep").opts.find(_.dest=="with_comments").get.help)
    }
    }
  }
}
