/*  Title:      dev/p12integration.scala

Exercise the real plugin lifecycle without a jEdit window or prover session.
*/

package isabelle.query.integration

import isabelle.*
import isabelle.jedit_query.{Query_Plugin}

object P12_JEdit_Probe {
  def main(args: Array[String]): Unit = Command_Line.tool {
    Isabelle_System.init()
    val plugin = new Query_Plugin
    plugin.start()
    assert(Query_Plugin.instance.contains(plugin))
    try { System.in.read(); () }
    finally {
      plugin.stop()
      // Drain the deferred popup cleanup before the process exits.
      GUI_Thread.now { () }
    }
    assert(Query_Plugin.instance.isEmpty)
  }
}
