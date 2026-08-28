/*  Title:      jedit_query/src/query_editor.scala

Jumping from a result to the source, and the gesture table that decides where.

Every policy funnels through Isabelle's own `PIDE.editor.goto_file`, which is
the one entry point that (a) opens the file if it is not open, (b) waits for
the load before moving the caret — the `runAfterIoTasks` handshake that a bare
`jEdit.openFile` + `setCaretPosition` gets wrong — and (c) records the
pre-jump position on `Isabelle_Navigator`, so a find-usages jump joins the same
back/forward history a ctrl-click does.  Splitting or opening a view FIRST and
then calling `goto_file` on the result is what keeps all three properties for
every policy, instead of reimplementing the handshake per mode.

The gesture -> policy mapping is ONE indirection through jEdit properties
(defaults in `plugin.props`), not a set of hard-wired listeners: adding a
gesture, or a policy such as a peek preview, is a table entry and a `case`, and
a user who wants single-click-to-open sets a property.  The defaults follow the
IDE convention rather than jEdit's own HyperSearch (which opens on a single
click): double-click opens in the active pane, shift-click opens a new pane.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.PIDE

import java.awt.event.{InputEvent, MouseEvent}
import java.nio.file.{Path => JPath}

import org.gjt.sp.jedit.{jEdit, Buffer, View}


sealed abstract class Open_Policy(val name: String)

object Open_Policy {
  /* Select only — what a single click does by default. */
  case object Nothing extends Open_Policy("none")
  case object Current extends Open_Policy("current")
  /* A new EditPane in the same view: jEdit's "pane", an IDE's split. */
  case object New_Pane extends Open_Policy("new-pane")
  case object New_View extends Open_Policy("new-view")

  val values: List[Open_Policy] = List(Nothing, Current, New_Pane, New_View)

  private val by_name: Map[String, Open_Policy] = values.map(p => p.name -> p).toMap


  /* --- the gesture table --- */

  /* Order is precedence: the first gesture whose test fires wins, so
     shift+double-click is a shift-click. */
  val gestures: List[(String, Open_Policy)] =
    List(
      "middle-click" -> New_View,
      "shift-click" -> New_Pane,
      "double-click" -> Current,
      "single-click" -> Nothing,
      "enter" -> Current)

  def property(gesture: String): String = "isabelle-project-query.gesture." + gesture

  def of_gesture(gesture: String): Open_Policy = {
    val fallback = gestures.collectFirst { case (g, p) if g == gesture => p }.getOrElse(Nothing)
    val configured = jEdit.getProperty(property(gesture), fallback.name)
    by_name.getOrElse(configured, fallback)
  }

  def gesture_of(evt: MouseEvent): String =
    if (evt == null) "enter"
    else if (evt.getButton == MouseEvent.BUTTON2) "middle-click"
    else if ((evt.getModifiersEx & InputEvent.SHIFT_DOWN_MASK) != 0) "shift-click"
    else if (evt.getClickCount >= 2) "double-click"
    else "single-click"

  def of_click(evt: MouseEvent): Open_Policy = of_gesture(gesture_of(evt))
}


object Query_Editor {
  /* jEdit identifies a buffer by its resolved path, which is also the form
     `Discovery` hands back, so the two agree without a conversion table. */
  def buffer_name(path: JPath): String =
    isabelle.query.Discovery.real(path.toAbsolutePath).toString

  private def pide_ready: Boolean =
    try PIDE.get_plugin.isDefined
    catch { case _: Throwable => false }

  /* `line` is 1-indexed, as everything the engine prints is; `goto_file` wants
     it 0-indexed. */
  def goto(view: View, path: JPath, line: Int, policy: Open_Policy): Unit = {
    GUI_Thread.require {}

    if (policy != Open_Policy.Nothing) {
      val name = buffer_name(path)
      val target =
        policy match {
          case Open_Policy.New_Pane => { view.splitHorizontally(); view }
          case Open_Policy.New_View => jEdit.newView(view)
          case _ => view
        }

      if (pide_ready) {
        PIDE.editor.goto_file(target, name, line = (line - 1) max 0, focus = true)
      }
      else {
        /* Before the Isabelle plugin is up there is no navigator and no
           `goto_file`; opening the buffer is still better than doing nothing,
           and jEdit's own loader takes care of the wait. */
        val buffer: Buffer = jEdit.openFile(target, name)
        if (buffer != null) {
          target.goToBuffer(buffer)
          val text_area = target.getTextArea
          if (text_area != null && line >= 1) {
            val n = buffer.getLineCount
            text_area.setCaretPosition(
              buffer.getLineStartOffset(((line - 1) max 0) min ((n - 1) max 0)))
          }
        }
      }
    }
  }
}
