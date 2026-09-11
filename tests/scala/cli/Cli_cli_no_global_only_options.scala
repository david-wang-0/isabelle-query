package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_cli_no_global_only_options {
  def run(): Unit = {
    test("test_cli_no_global_only_options.NoGlobalOnlyOptions.test_every_parent_option_is_on_every_child") {

    locally {
def optionGroups(help:String):List[Set[String]]=lines(help).filter(_.trim.startsWith("-")).map(line=>"--?[A-Za-z][A-Za-z-]*".r.findAllIn(line).toSet)
// --no-server is Scala's routing escape, not a Python parser option.
val parentOnly=Set("-h","--help","--version","-V","--no-server")
def checkChildren(parentHelp:String,children:List[CLI.Cmd],prefix:String=""):Unit={
 val applicable=optionGroups(parentHelp).filter(_.intersect(parentOnly).isEmpty)
 for(c<-children){val h=capture((o,e)=>if(c.subs.isEmpty)CLI.cmd_help(o,c,prefix)else CLI.group_help(o,c)).out;val options=optionGroups(h).flatten.toSet;for(group<-applicable)check(group.intersect(options).nonEmpty,c.names.head+": "+group)}
}
val top=capture((o,e)=>CLI.top_help(o)).out
checkChildren(top,CLI.commands)
var parents=1
for(c<-CLI.commands if c.subs.nonEmpty){parents+=1;checkChildren(capture((o,e)=>CLI.group_help(o,c)).out,c.subs,c.names.head+" ")}
check(parents>=2)
    }
    }
  }
}
