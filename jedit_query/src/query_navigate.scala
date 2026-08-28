/*  Title:      jedit_query/src/query_navigate.scala

Back and forward, over Isabelle/jEdit's OWN jump stacks.

There is nothing to implement here and that is the finding, not a shortcut:
`isabelle.jedit.Isabelle_Navigator` is already a complete browser-style history
— a 500-entry cap, a `_bypass` flag so a back-jump is not itself recorded, and
a `BufferListener` per open buffer that re-maps stored offsets as the user
types before them.  `Main_Plugin` feeds it from `EditPaneUpdate.PositionChanging`,
so EVERY jump lands in it: a ctrl-click hyperlink, a goto-line, a HyperSearch
result — and, because P5's jump goes through `PIDE.editor.goto_file`, a Project
Query result too.

What is missing is only the affordance.  Isabelle registers the actions
`navigate-backwards` / `navigate-forwards` but sets no `.label` and no
`.shortcut` for them, so they are reachable only from the action-search dialog.
This module gives them a keybinding and a pair of toolbar buttons — under OUR
action names, not by writing a `.shortcut` property for another plugin's
action, which is a collision waiting for the next Isabelle release.

Everything degrades: before `PIDE._plugin` exists there is no navigator at all,
and the panel is explicitly meant to work in that window.  So the buttons
disable themselves with a tooltip that says why, and the actions report on the
status line instead of throwing out of an action handler.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.{Isabelle_Navigator, PIDE}

import org.gjt.sp.jedit.View


object Query_Navigate {
  /* `PIDE.plugin` ERRORS when the plugin is not up, and `plugin.navigator` is
     a `val` on an instance that may still be constructing.  Both are wrapped:
     this is read from a button's enabled-state update, which runs on every
     caret move. */
  def navigator: Option[Isabelle_Navigator] =
    try PIDE.get_plugin.flatMap(p => Option(p.navigator))
    catch { case _: Throwable => None }

  def available: Boolean = navigator.isDefined

  /* `History.top` is `Pos.none` when the stack is empty and `push` never
     pushes an undefined position, so "there is somewhere to go" is exactly
     "the top position is defined" — without reaching into private state. */
  def can_backward: Boolean = navigator.exists(_.current.defined)
  def can_forward: Boolean = navigator.exists(_.recurrent.defined)

  private val unavailable =
    "no jump history yet -- the Isabelle plugin is not up"

  private def go(view: View, what: String, ready: Boolean,
    body: Isabelle_Navigator => Unit
  ): Unit = {
    GUI_Thread.require {}
    navigator match {
      case None => Query_Options.status(unavailable)
      case Some(nav) =>
        if (ready) body(nav)
        else Query_Options.status("no position to navigate " + what + " to")
    }
  }

  def backward(view: View): Unit =
    go(view, "back", can_backward, _.backward(view))

  def forward(view: View): Unit =
    go(view, "forward", can_forward, _.forward(view))


  /* Button captions.  Written as code points for the same reason
     `Render.EM_DASH` is: the compiler's source encoding is not ours to assume,
     and these are load-bearing glyphs (U+25C0 / U+25B6 pointing triangles). */
  val BACK: String = 0x25c0.toChar.toString
  val FORWARD: String = 0x25b6.toChar.toString

  def back_tip: String =
    if (available) "back to the previous position (Isabelle jump history)"
    else unavailable

  def forward_tip: String =
    if (available) "forward again (Isabelle jump history)"
    else unavailable
}
