package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_names_flag {
  def run(): Unit = {
    test("test_cli_names_flag.NamesFlag.test_long_names_flag_still_parses") {

    locally {
check(args("callers","foo","--names").bool("names"))
    }
    }
    test("test_cli_names_flag.NamesFlag.test_noop_n_parses_identically_to_its_absence") {

    locally {
for(c <- List("grep","sorry")) { val a=if(c=="grep") List("x") else Nil; val original=args(c,a*); for(flag <- List("-n","--line-number")) {val n=args(c,(a :+ flag)*); equal(n.flags,original.flags); equal(n.values,original.values); equal(n.positional,original.positional);equal(n.appended,original.appended)} }
    }
    }
    test("test_cli_names_flag.NamesFlag.test_short_n_flag_is_rejected_outside_the_search_verbs") {

    locally {
for(c <- List("theory","defs","find","show","callers","callees","methods")) { val a=if(c=="methods") List("-n") else List("x","-n"); raises(args(c,a*)); () }
    }
    }
    test("test_cli_names_flag.NamesFlag.test_short_n_never_sets_names") {

    locally {
check(!args("grep","x","-n").bool("names"))
    }
    }
  }
}
