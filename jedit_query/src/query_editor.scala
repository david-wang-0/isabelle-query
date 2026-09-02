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
  /* A transient popup: the one policy that does NOT move the editor. */
  case object Peek extends Open_Policy("peek")

  val values: List[Open_Policy] = List(Nothing, Current, New_Pane, New_View, Peek)

  val names: List[String] = values.map(_.name)

  private val by_name: Map[String, Open_Policy] = values.map(p => p.name -> p).toMap

  def of_name(name: String): Option[Open_Policy] = by_name.get(name)


  /* --- the gesture table --- */

  /* Order is precedence: the first gesture whose test fires wins, so
     shift+double-click is a shift-click.  `alt-click` is where peek lands by
     default — ALT rather than CTRL because ctrl+click is how a JTree extends a
     discontiguous selection, which this panel uses for multi-node removal. */
  val gestures: List[(String, Open_Policy)] =
    List(
      "middle-click" -> New_View,
      "alt-click" -> Peek,
      "shift-click" -> New_Pane,
      "double-click" -> Current,
      "single-click" -> Nothing,
      "enter" -> Current)

  def property(gesture: String): String = Query_Options.property_name("gesture." + gesture)

  def default_of(gesture: String): Open_Policy =
    gestures.collectFirst { case (g, p) if g == gesture => p }.getOrElse(Nothing)

  /* One indirection, now over `Query_Options` rather than straight over jEdit
     properties: the Isabelle option is consulted when it has been changed, the
     jEdit property otherwise, and a value that is neither `none` nor one of
     the policy names is REPORTED rather than silently becoming the default. */
  def of_gesture(gesture: String): Open_Policy = {
    val fallback = default_of(gesture)
    by_name.getOrElse(
      Query_Options.choice("gesture." + gesture, names, fallback.name), fallback)
  }

  def gesture_of(evt: MouseEvent): String =
    if (evt == null) "enter"
    else if (evt.getButton == MouseEvent.BUTTON2) "middle-click"
    else if ((evt.getModifiersEx & InputEvent.ALT_DOWN_MASK) != 0) "alt-click"
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

  /* Where a peek popup is anchored when the caller has no better idea: below
     the caret of the view's own text area, which is where the eye is. */
  private def caret_anchor(view: View): Option[(java.awt.Component, java.awt.Point)] =
    for {
      text_area <- Option(view).flatMap(v => Option(v.getTextArea))
      painter <- Option(text_area.getPainter)
      p <- Option(text_area.offsetToXY(text_area.getCaretPosition))
    } yield (painter, new java.awt.Point(p.x, p.y + painter.getLineHeight))

  /* `line` is 1-indexed, as everything the engine prints is; `goto_file` wants
     it 0-indexed.

     `origin` is where a POPUP policy should appear — the component and point
     of the gesture that asked for it.  Every other policy ignores it, which is
     why it is optional rather than threaded through the whole table. */
  def goto(view: View, path: JPath, line: Int, policy: Open_Policy,
    origin: Option[(java.awt.Component, java.awt.Point)] = None
  ): Unit = {
    GUI_Thread.require {}

    if (policy == Open_Policy.Peek) {
      /* The PATH is the whole of it: a peek needs no theory name at the call
         site, so the gesture table stays a table of policies — and the stem
         this used to derive was not a name the index could always look up
         [name-is-not-identity]. */
      val (component, point) =
        origin.orElse(caret_anchor(view)).getOrElse((view, new java.awt.Point(0, 0)))
      Query_Peek.at_line(view, component, point, path, line)
    }
    else if (policy != Open_Policy.Nothing) {
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
