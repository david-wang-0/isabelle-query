/*  Title:      jedit_query/src/query_actions.scala

The keyboard half of the context menu.

Everything the right-click offers is also an `actions.xml` action, because a
context menu is unreachable without a mouse and jEdit binds keys to actions,
not to menu items.  The bodies are one-liners on purpose: the actions are a
second front door onto exactly the same path, never a second implementation of
it.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.JEdit_Lib

import org.gjt.sp.jedit.View


object Query_Actions {
  /* The word at the caret of the view's current text area. */
  private def caret_word(view: View): Option[(org.gjt.sp.jedit.Buffer, Query_Word.Word)] = {
    val text_area = if (view == null) null else view.getTextArea
    if (text_area == null) None
    else {
      for {
        buffer <- Query_Context_Menu.buffer_of(text_area)
        if Query_Context_Menu.is_theory(buffer)
        word <- Query_Context_Menu.word_at(buffer, text_area.getCaretPosition)
      } yield (buffer, word)
    }
  }

  def find_usages(view: View, external: Boolean = false): Unit = {
    GUI_Thread.require {}
    caret_word(view) match {
      case Some((buffer, word)) =>
        Query_Dockable.find_usages(view, buffer, word.base, external)
      case None =>
        Query_Dockable.show(view)
    }
  }

  def find_external_usages(view: View): Unit = find_usages(view, external = true)

  /* Where the name at the caret is declared, as a result set in the panel —
     the same node model and the same navigation as a usages set, opened
     EXPANDED because showing it is the whole request. */
  def find_definition(view: View): Unit = {
    GUI_Thread.require {}
    caret_word(view) match {
      case Some((buffer, word)) => Query_Dockable.find_definition(view, buffer, word.base)
      case None => Query_Dockable.show(view)
    }
  }

  /* The two site verbs.  Unlike the context menu, an action does NOT gate on
     the entry kind: it is reached from the keyboard or the Plugins menu, where
     there is nothing to look at that would explain a missing item, and the
     panel answers a bad subject with the reason ("'foo' is a LEMMA in Bar, not
     a locale or class") rather than an empty set.  Refusing to run would say
     less. */
  def find_instantiations(view: View): Unit = {
    GUI_Thread.require {}
    caret_word(view) match {
      case Some((buffer, word)) => Query_Dockable.find_instantiations(view, buffer, word.base)
      case None => Query_Dockable.show(view)
    }
  }

  def find_code_equations(view: View): Unit = {
    GUI_Thread.require {}
    caret_word(view) match {
      case Some((buffer, word)) => Query_Dockable.find_code_equations(view, buffer, word.base)
      case None => Query_Dockable.show(view)
    }
  }

  /* The same answer as Find definition, in a popup instead of the panel: the
     reading you do not want to leave the pane for. */
  def peek_definition(view: View): Unit = {
    GUI_Thread.require {}
    Query_Peek.at_caret(view)
  }

  /* Type a few letters, land on a declaration: the whole project's name list,
     filtered without touching the engine. */
  def go_to_symbol(view: View): Unit = {
    GUI_Thread.require {}
    Query_Quick_Open.open(view)
  }

  /* The panel's own name field -- the keyboard route to it, since a text field
     in a dockable is otherwise reachable only with the mouse.  Distinct from
     go-to-symbol on purpose: that JUMPS to a declaration, this SEARCHES from
     one and leaves a result set behind. */
  def search_by_name(view: View): Unit = {
    GUI_Thread.require {}
    Query_Dockable.focus_search(view)
  }

  /* Isabelle's own jump stacks, under an action name of ours so a default
     keybinding in `plugin.props` cannot collide with `isabelle.jedit_main`'s
     properties. */
  def navigate_backwards(view: View): Unit = {
    GUI_Thread.require {}
    Query_Navigate.backward(view)
    Query_Dockable.update_navigation()
  }

  def navigate_forwards(view: View): Unit = {
    GUI_Thread.require {}
    Query_Navigate.forward(view)
    Query_Dockable.update_navigation()
  }

  def show_panel(view: View): Unit = {
    GUI_Thread.require {}
    Query_Dockable.show(view)
  }

  def refresh_index(view: View): Unit = {
    GUI_Thread.require {}
    for {
      buffer <- Option(view).map(_.getBuffer)
      file <- JEdit_Lib.buffer_file(buffer)
      index <- Query_Index.for_file(file.toPath)
    } index.invalidate()
    Query_Dockable.show(view).foreach(_.refresh())
  }
}
