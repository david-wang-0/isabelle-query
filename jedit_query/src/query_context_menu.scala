/*  Title:      jedit_query/src/query_context_menu.scala

Right-click -> "Project Query" -> the verbs for the word under the pointer.

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

ONE SUBMENU, NOT SIX ITEMS, and that is a correctness property rather than a
matter of taste.  jEdit builds and shows this menu during the mouse PRESS
(`TextAreaMouseHandler.mousePressed` -> `JEditTextArea.handlePopupTrigger` ->
`TextArea.handlePopupTrigger`), and positions it with
`GenericGUIUtilities.showPopupMenu(popup, text_area, x, y, point = false)`.
With `point = false` there is no offset: the popup's top-left lands *at* the
pointer, so the pointer normally rests on the popup's top border and the
button RELEASE that completes the same click misses every item.  But when the
menu is too tall to fit, that same call re-anchors it flush with the window's
bottom edge (`y = win.getHeight() - size.height - offsetY`) — and then the
pointer sits *inside* the popup, a few rows down, on an item.  Swing routes
that release into the menu (`BasicPopupMenuUI.MouseGrabber.eventDispatched`
-> `MenuSelectionManager.processMouseEvent` -> `BasicMenuItemUI`'s
`menuDragMouseReleased`), which calls `doClick`: the item fires and the menu
closes.  The JDK has no guard against a release older than the popup it lands
in, so a right-click near the bottom of the window silently ran whichever item
the fold put under the cursor — in practice jEdit's own `paste` and
`paste-previous`, two and three rows down.

So the HEIGHT a service adds to this shared popup decides whether the menu
survives its own click.  Four to six entries with labels like "Find external
usages of foo" added some 150px and moved that fold up over a third of the
text area; one submenu adds a single row.  A `JMenu` is also the one entry
kind that cannot be fired this way at all — `BasicMenuUI`'s
`menuDragMouseReleased` is empty — so even a release landing on ours does
nothing.  Anyone tempted to flatten these back into top-level items should
read this paragraph first.

The item LABELS are therefore the panel's own (`Query_Name_Search.Finder`),
without the trailing "of X": the submenu title carries the name once, and the
two front doors say the same words about the same verbs.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.JEdit_Lib

import java.awt.event.{ActionEvent, MouseEvent}

import javax.swing.{JMenu, JMenuItem, AbstractAction}

import org.gjt.sp.jedit.{Buffer, View}
import org.gjt.sp.jedit.gui.DynamicContextMenuService
import org.gjt.sp.jedit.textarea.JEditTextArea


object Query_Context_Menu {
  /* What the submenu is called, before the name it is about. */
  val TITLE: String = "Project Query"

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

  /* What a right-click resolves to, resolved ONCE.  The OFFSET is carried
     because a right-click does not move the caret: the menu describes the
     word under the POINTER, so a peek raised from it has to open the same
     one, and re-reading the caret later would open a different thing from the
     one the title names. */
  final case class Subject(view: View, buffer: Buffer, name: String, offset: Int)

  def subject(text_area: JEditTextArea, evt: MouseEvent): Option[Subject] = {
    val buffer = buffer_of(text_area).orNull
    if (!is_theory(buffer)) None
    else {
      val offset =
        if (evt != null && evt.getSource == text_area.getPainter)
          text_area.xyToOffset(evt.getX, evt.getY)
        else text_area.getCaretPosition

      word_at(buffer, offset).map(word =>
        Subject(text_area.getView, buffer, word.base, offset))
    }
  }

  /* The verbs, in the submenu's order.  Never empty: the first four answer for
     any name at all. */
  def menu_items(s: Subject): List[JMenuItem] =
    List(
      item("Find usages") {
        Query_Dockable.find_usages(s.view, s.buffer, s.name, external = false)
      },
      item("Find external usages") {
        Query_Dockable.find_usages(s.view, s.buffer, s.name, external = true)
      },
      item("Find definition") {
        Query_Dockable.find_definition(s.view, s.buffer, s.name)
      },
      item("Peek definition") {
        Query_Peek.at_offset(s.view, s.offset)
      }) :::
    site_items(s)

  /* The whole contribution: one entry, or none. */
  def menu(text_area: JEditTextArea, evt: MouseEvent): Option[JMenu] =
    subject(text_area, evt).map { s =>
      val menu = new JMenu(TITLE + ": " + Symbol.decode(s.name))
      menu_items(s).foreach(menu.add)
      menu
    }

  /* The two site verbs, offered only where they have an answer.

     ABSENT rather than disabled, and the choice follows the menu this class
     already builds: its contract is `null` for "nothing to contribute", and
     it drops the whole menu for a non-theory buffer rather than greying it
     out.  A permanently grey pair of items on every right-click in a project
     with no locales would be noise.

     The predicate is the ENGINE's -- `Query_Search.is_subject`, which is
     `Sites.resolve`, which is what the CLI exits 1 on -- so an item that
     appears always leads to an answer, and the two front doors can never
     disagree about what a locale is.

     Reads only the index that is ALREADY built: `snapshot` is a volatile
     read, never a parse, because this runs on the EDT for every right-click.
     A cold index therefore offers neither item; the actions (and the keyboard)
     still reach both, build the index, and report honestly.  This is the one
     place the menu is less capable than the action, and it is the price of
     never blocking a right-click. */
  private def site_items(s: Subject): List[JMenuItem] = {
    val snapshot =
      for {
        file <- JEdit_Lib.buffer_file(s.buffer)
        index <- Query_Index.for_file(file.toPath)
        snapshot <- index.snapshot
      } yield snapshot
    snapshot.toList.flatMap { snap =>
      (if (Query_Search.is_subject(snap, s.name, isabelle.query.Sites.locale_tags))
        List(item("Find instantiations") {
          Query_Dockable.find_instantiations(s.view, s.buffer, s.name)
        })
       else Nil) :::
      (if (Query_Search.is_subject(snap, s.name, isabelle.query.Sites.constant_tags))
        List(item("Find code equations") {
          Query_Dockable.find_code_equations(s.view, s.buffer, s.name)
        })
       else Nil)
    }
  }

  private def item(label: String)(body: => Unit): JMenuItem =
    new JMenuItem(new AbstractAction(label) {
      def actionPerformed(e: ActionEvent): Unit = body
    })
}


class Query_Context_Menu extends DynamicContextMenuService {
  def createMenu(text_area: JEditTextArea, evt: MouseEvent): Array[JMenuItem] = {
    val menu =
      try Query_Context_Menu.menu(text_area, evt)
      catch { case _: Throwable => None }
    menu match {
      case Some(m) => Array[JMenuItem](m)
      case None => null
    }
  }
}
