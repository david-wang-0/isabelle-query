/*  Title:      jedit_query/src/query_context_menu.scala

Right-click -> "Find Usages".

`DynamicContextMenuService` fires on EVERY right-click in EVERY buffer and
mode, so the first job is to say nothing: `null` (not an empty array) is the
contract for "nothing to contribute", and anything that is not an Isabelle
theory file gets it.

`evt` may be null — the menu can be raised from the keyboard — in which case
the caret is the position, exactly as Isabelle/jEdit's own `Context_Menu`
does; and an offset under the mouse is only meaningful when the event came
from the text area's painter.

The identifier itself comes from the buffer text through `Query_Word`, not
from PIDE markup: a right-click must work while the prover is still loading,
or has never been started.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.JEdit_Lib

import java.awt.event.{ActionEvent, MouseEvent}

import javax.swing.{JMenuItem, AbstractAction}

import org.gjt.sp.jedit.Buffer
import org.gjt.sp.jedit.gui.DynamicContextMenuService
import org.gjt.sp.jedit.textarea.JEditTextArea


object Query_Context_Menu {
  /* A text area's buffer is typed `JEditBuffer`, which knows nothing about
     files; everything here needs the `Buffer` a jEdit editor always actually
     holds.  Matched rather than cast, so a hypothetical other buffer kind
     simply contributes no menu. */
  def buffer_of(text_area: JEditTextArea): Option[Buffer] =
    if (text_area == null) None
    else text_area.getBuffer match {
      case buffer: Buffer => Some(buffer)
      case _ => None
    }

  def is_theory(buffer: Buffer): Boolean =
    buffer != null && JEdit_Lib.buffer_name(buffer).endsWith(".thy")

  /* The word at a buffer offset, in the form the engine names entries. */
  def word_at(buffer: Buffer, offset: Int): Option[Query_Word.Word] =
    if (buffer == null || offset < 0 || offset > buffer.getLength) None
    else {
      try {
        val line = buffer.getLineOfOffset(offset)
        val start = buffer.getLineStartOffset(line)
        val text = buffer.getLineText(line)
        Query_Word.at(text, offset - start)
      }
      catch { case _: Throwable => None }
    }

  def menu_items(text_area: JEditTextArea, evt: MouseEvent): List[JMenuItem] = {
    val buffer = buffer_of(text_area).orNull
    if (!is_theory(buffer)) Nil
    else {
      val offset =
        if (evt != null && evt.getSource == text_area.getPainter)
          text_area.xyToOffset(evt.getX, evt.getY)
        else text_area.getCaretPosition

      word_at(buffer, offset) match {
        case None => Nil
        case Some(word) =>
          val view = text_area.getView
          List(
            item("Find usages of " + word.base) {
              Query_Dockable.find_usages(view, buffer, word.base, external = false)
            },
            item("Find external usages of " + word.base) {
              Query_Dockable.find_usages(view, buffer, word.base, external = true)
            },
            item("Find definition of " + word.base) {
              Query_Dockable.find_definition(view, buffer, word.base)
            },
            /* The OFFSET the label was built from, not the caret: a
               right-click does not move the caret, so the two need not
               agree and the menu must not describe one and open the other. */
            item("Peek definition of " + word.base) {
              Query_Peek.at_offset(view, offset)
            })
      }
    }
  }

  private def item(label: String)(body: => Unit): JMenuItem =
    new JMenuItem(new AbstractAction(label) {
      def actionPerformed(e: ActionEvent): Unit = body
    })
}


class Query_Context_Menu extends DynamicContextMenuService {
  def createMenu(text_area: JEditTextArea, evt: MouseEvent): Array[JMenuItem] = {
    val items =
      try Query_Context_Menu.menu_items(text_area, evt)
      catch { case _: Throwable => Nil }
    if (items.isEmpty) null else items.toArray
  }
}
