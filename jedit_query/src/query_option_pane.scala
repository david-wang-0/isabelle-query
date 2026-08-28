/*  Title:      jedit_query/src/query_option_pane.scala

Plugin Options -> Isabelle Project Query: the gesture table, editable.

A jEdit `AbstractOptionPane` rather than an entry in Isabelle's own options
page, and that is the load-bearing choice.  `JEdit_Options.Isabelle_Options`
reads `PIDE.options`, which does not exist until the prover plugin has started;
this panel's contract is that it answers before that, so its settings page has
to be readable in the same window.  The Isabelle side is not lost — the same
table ships as `jedit_query/etc/options`, so `isabelle options` sees it and
Plugin Options -> Isabelle -> General edits it — it is simply not the ONLY
surface.

The pane edits jEdit properties.  It cannot produce an illegal value (the
gestures are combo boxes over `Open_Policy.names`, the limit is a spinner), and
where it CANNOT help — a property typed by hand, or an Isabelle option set with
`isabelle options -x` — `Query_Options` logs the mistake instead of silently
falling back.  When an Isabelle option is currently overriding a gesture, the
pane says so rather than showing a value that is not in force.
*/

package isabelle.jedit_query


import java.awt.Component

import javax.swing.{JComboBox, JLabel, JSpinner, SpinnerNumberModel}

import org.gjt.sp.jedit.{jEdit, AbstractOptionPane}


object Query_Option_Pane {
  /* Must match `options.<name>.label` / `.code` and the `option-group` entry
     in plugin.props. */
  val NAME: String = "isabelle-project-query"

  /* "double-click" -> "Double click", for the row label. */
  def title(gesture: String): String = {
    val words = gesture.split("-").toList
    words match {
      case head :: rest =>
        (head.capitalize :: rest).mkString(" ")
      case Nil => gesture
    }
  }

  /* jEdit labels a separator with a PROPERTY, not with a string; Isabelle's
     own option panes set a dummy property and clear it again, and this is that
     idiom, kept because inventing a permanent property per heading litters the
     user's properties file. */
  private val dummy = "options.isabelle-project-query.dummy"

  def separator(pane: AbstractOptionPane, text: String): Unit = {
    jEdit.setProperty(dummy, text)
    pane.addSeparator(dummy)
    jEdit.setProperty(dummy, null)
  }
}


class Query_Option_Pane extends AbstractOptionPane(Query_Option_Pane.NAME) {
  private val gestures =
    for ((gesture, default) <- Open_Policy.gestures) yield {
      val box = new JComboBox[String](Open_Policy.names.toArray)
      box.setSelectedItem(
        Query_Options.property("gesture." + gesture)
          .filter(Open_Policy.names.contains)
          .getOrElse(default.name))
      (gesture, default, box)
    }

  private val limit =
    new JSpinner(new SpinnerNumberModel(
      Query_Index.limit max 0, 0, 1000000, 100))

  /* Which gestures are currently NOT being decided by this page, because an
     Isabelle option has been changed away from its default.  Saying so beats
     showing a combo box whose value is not in force. */
  private def overridden: List[String] =
    for {
      (gesture, _, _) <- gestures
      (value, default) <- Query_Options.isabelle_option("gesture." + gesture).toList
      if value != default
    } yield Query_Option_Pane.title(gesture) + " = " + value +
      " (" + Query_Options.option_name("gesture." + gesture) + ")"

  override protected def _init(): Unit = {
    Query_Option_Pane.separator(this, "Result navigation")
    addComponent(new JLabel(
      "<html>What a gesture on a result row does.  " +
        "<i>peek</i> opens a popup and leaves the editor where it is.</html>")
      .asInstanceOf[Component])
    for ((gesture, _, box) <- gestures)
      addComponent(Query_Option_Pane.title(gesture), box)

    val over = overridden
    if (over.nonEmpty)
      addComponent(new JLabel(
        "<html><b>Currently overridden by Isabelle options:</b><br>" +
          over.mkString("<br>") + "</html>").asInstanceOf[Component])

    Query_Option_Pane.separator(this, "Index")
    addComponent("Largest project, in theories (0 = no limit)", limit)
    addComponent(new JLabel(
      "<html>Above this the index refuses to build rather than answering " +
        "from a partial parse.</html>").asInstanceOf[Component])

    val warnings = Query_Options.warnings
    if (warnings.nonEmpty) {
      Query_Option_Pane.separator(this, "Rejected settings")
      addComponent(new JLabel(
        "<html>" + warnings.takeRight(5).mkString("<br>") + "</html>")
        .asInstanceOf[Component])
    }
  }

  override protected def _save(): Unit = {
    for ((gesture, _, box) <- gestures)
      Query_Options.set_property("gesture." + gesture,
        Option(box.getSelectedItem).map(_.toString))
    Query_Options.set_property("index-limit", Some(limit.getValue.toString))
  }
}
