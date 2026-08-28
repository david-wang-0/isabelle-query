/*  Title:      jedit_query/src/query_peek.scala

Peek: the declaration in a popup, without leaving the pane you are in.

The fourth `Open_Policy`, and the one P5 deferred.  Everything else in the
policy table moves the editor — the active pane, a split, a new view — and the
common case does not want any of that: you want to know what `wf_dtree` says
and then keep reading where you were.

`Pretty_Tooltip` is Isabelle's popup and is deliberately NOT reused: it takes a
`JEdit_Rendering` and a `Command.Results`, i.e. prover output for a position in
a checked document.  This popup shows SOURCE, from the same index that answers
a right-click, and its whole point is that it works while the prover is still
starting — the P5 rule.  What is reused is the layer below both, which is the
part that is actually general: `isabelle.Popup` over `GUI.layered_pane`, placed
with `GUI.screen_location(...).relative(...)` so a popup near the bottom of the
screen opens upwards.  That is the same three calls `Pretty_Tooltip` and
`Completion_Popup` make.

Content resolution is a volatile read of the index snapshot, a map lookup and a
linear scan of ONE theory's entries (`Commands.enclosing_entry`).  None of it
enters the engine's citation router, so none of it needs the per-project
`Namespace` binding, which is what makes it safe to do at all — the index's
worker thread is still where the parse happens, and a cold index hops there
first.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.query.{Commands, Discovery, Render}

import java.awt.{BorderLayout, Component, Dimension, Font, Point}
import java.awt.event.{FocusAdapter, FocusEvent, KeyAdapter, KeyEvent, MouseAdapter, MouseEvent}
import java.nio.file.{Path => JPath}

import javax.swing.{BorderFactory, JLabel, JPanel, JScrollPane, JTextArea, SwingUtilities}

import org.gjt.sp.jedit.{jEdit, View}


object Query_Peek {
  /* A peek is a glance, not a reading window: past two dozen lines the answer
     is "open it", which ENTER in the popup does. */
  val LINES: Int = 24

  /* When no declaration owns the line — a citation in the middle of a proof —
     the popup shows the line in context instead of nothing. */
  val CONTEXT: Int = 3

  final case class Content(caption: String, rows: List[(Int, String)],
    target: Option[(JPath, Int)])


  /* ------------------------------------------------------------------ */
  /* what to show                                                       */
  /* ------------------------------------------------------------------ */

  private def rows_of(hits: List[Query_Search.Hit]): List[(Int, String)] =
    for (h <- hits) yield (if (h.note) 0 else h.line, Symbol.decode(h.text))

  /* The declaration a name resolves to — what "peek at the caret" shows. */
  def of_name(snapshot: Query_Index.Snapshot, name: String): Option[Content] =
    for {
      found <- Query_Search.resolve(snapshot, name)
      sec <- snapshot.section(found.theory)
    } yield Content(Render.format_name_line(sec, found.entry),
      rows_of(Query_Search.body_hits(sec, found.entry, LINES)),
      Some((sec.path, found.entry.thy_line)))

  /* The declaration owning a LINE — what "peek at a result row" shows.  A row
     inside a proof peeks the lemma it is in, which is the useful answer; a row
     no declaration owns falls back to its own neighbourhood. */
  def of_line(snapshot: Query_Index.Snapshot, theory: String, line: Int): Option[Content] =
    snapshot.section(theory).map { sec =>
      Commands.enclosing_entry(sec, line) match {
        case Some(e) =>
          Content(Render.format_name_line(sec, e),
            rows_of(Query_Search.body_hits(sec, e, LINES)), Some((sec.path, line)))
        case None =>
          val lo = (line - CONTEXT) max 1
          val hi = (line + CONTEXT) min sec.lines.length
          Content(theory + ".thy:" + line.toString,
            (for ((text, i) <- sec.slice(lo, hi).zipWithIndex.toList)
              yield (lo + i, Symbol.decode(text))),
            Some((sec.path, line)))
      }
    }


  /* ------------------------------------------------------------------ */
  /* the two entry points                                               */
  /* ------------------------------------------------------------------ */

  /* Both hop to the index worker before rendering: a peek must never parse on
     the EDT, and a cold index has to be built before there is anything to
     read.  The overlay is collected here, on the EDT, because that is where a
     buffer's text may be read. */
  private def request(view: View, origin: Component, point: Point, file: JPath)(
    body: Query_Index.Snapshot => Option[Content]
  ): Unit = {
    GUI_Thread.require {}
    Query_Index.for_file(file) match {
      case None =>
        Query_Options.status("no ROOT above " + file.getFileName.toString)
      case Some(index) =>
        val overlay = Query_Dockable.overlay(index.root)
        Query_Index.background {
          val content =
            try {
              /* Refreshed, not "the snapshot if there is one": a peek showing
                 the lines a file used to have is a wrong answer, and the
                 refresh is incremental (mtime + size per file, buffer text for
                 a dirty one) and runs here, off the EDT, where it costs the
                 popup a few milliseconds and the editor nothing. */
              body(index.refreshed(overlay))
            }
            catch { case exn: Throwable => Query_Options.status(Exn.message(exn)); None }
          GUI_Thread.later {
            content match {
              case Some(c) => open(view, origin, point, c)
              case None => Query_Options.status("nothing to peek at")
            }
          }
        }
    }
  }

  /* Peek the declaration of the identifier at a buffer OFFSET, anchored under
     it.  The offset is a parameter rather than always the caret because a
     right-click in jEdit does not move the caret — `Query_Context_Menu` reads
     the position under the mouse, and a peek raised from that menu has to
     agree with the item's own label. */
  def at_offset(view: View, offset: Int): Unit = {
    GUI_Thread.require {}
    val text_area = if (view == null) null else view.getTextArea
    val target =
      for {
        buffer <- Query_Context_Menu.buffer_of(text_area)
        if Query_Context_Menu.is_theory(buffer)
        word <- Query_Context_Menu.word_at(buffer, offset)
        file <- isabelle.jedit.JEdit_Lib.buffer_file(buffer)
      } yield (word.base, file.toPath)

    target match {
      case None => Query_Options.status("no identifier there")
      case Some((name, file)) =>
        val painter = text_area.getPainter
        val point =
          Option(text_area.offsetToXY(offset)) match {
            case Some(p) => new Point(p.x, p.y + painter.getLineHeight)
            case None => new Point(0, 0)
          }
        request(view, painter, point, file)(snapshot => of_name(snapshot, name))
    }
  }

  def at_caret(view: View): Unit = {
    GUI_Thread.require {}
    val text_area = if (view == null) null else view.getTextArea
    if (text_area == null) Query_Options.status("no text area")
    else at_offset(view, text_area.getCaretPosition)
  }

  /* Peek a result row in the panel. */
  def at_line(view: View, origin: Component, point: Point, path: JPath, theory: String,
    line: Int
  ): Unit = {
    GUI_Thread.require {}
    request(view, origin, point, path)(snapshot => of_line(snapshot, theory, line))
  }


  /* ------------------------------------------------------------------ */
  /* the popup                                                          */
  /* ------------------------------------------------------------------ */

  // owned by GUI thread
  private var active: Option[(Popup, Peek_Panel)] = None

  def dismiss(): Unit = {
    GUI_Thread.require {}
    active.foreach { case (popup, _) => popup.hide }
    active = None
  }

  private def open(view: View, origin: Component, point: Point, content: Content): Unit = {
    GUI_Thread.require {}
    dismiss()
    GUI.layered_pane(if (origin == null) view else origin) match {
      case None => Query_Options.status("nowhere to place the peek popup")
      case Some(layered) =>
        val panel = new Peek_Panel(view, content)
        val loc =
          if (origin == null) new Point(0, 0)
          else SwingUtilities.convertPoint(origin, point, layered)
        val screen = GUI.screen_location(layered, loc)
        val size = {
          val want = panel.getPreferredSize
          val w_max = layered.getWidth max 1
          val h_max = ((layered.getHeight * 2) / 3) max 1
          new Dimension(want.width min w_max, want.height min h_max)
        }
        val popup = new Popup(layered, panel, screen.relative(layered, size), size)
        active = Some((popup, panel))
        popup.show
        panel.focus()
    }
  }
}


/* Non-editable, monospaced, line-numbered.  ENTER or a click opens the target
   for real and closes the popup, which is the escalation path a peek needs:
   glance, then commit. */
private class Peek_Panel(view: View, content: Query_Peek.Content)
extends JPanel(new BorderLayout) {
  private val text_font: Font =
    try jEdit.getFontProperty("view.font", new Font("Monospaced", Font.PLAIN, 12))
    catch { case _: Throwable => new Font("Monospaced", Font.PLAIN, 12) }

  private val width = content.rows.foldLeft(24)((n, r) => n max (r._2.length + 7))

  private val body = new JTextArea(content.rows.length max 1, width min 120)
  body.setEditable(false)
  body.setFont(text_font)
  body.setText(
    content.rows.map({
      case (0, text) => "      " + text
      case (line, text) => String.format("%5d ", Integer.valueOf(line)) + text
    }).mkString("\n"))
  body.setCaretPosition(0)

  private val caption = new JLabel(content.caption)
  caption.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4))

  add(caption, BorderLayout.NORTH)
  add(new JScrollPane(body), BorderLayout.CENTER)
  setBorder(BorderFactory.createLineBorder(GUI.default_foreground_color()))
  setOpaque(true)

  private def commit(): Unit = {
    Query_Peek.dismiss()
    for ((path, line) <- content.target) Query_Editor.goto(view, path, line, Open_Policy.Current)
  }

  body.addKeyListener(new KeyAdapter {
    override def keyPressed(evt: KeyEvent): Unit =
      evt.getKeyCode match {
        case KeyEvent.VK_ESCAPE => Query_Peek.dismiss(); evt.consume()
        case KeyEvent.VK_ENTER => commit(); evt.consume()
        case _ =>
      }
  })

  body.addMouseListener(new MouseAdapter {
    override def mousePressed(evt: MouseEvent): Unit =
      if (evt.getClickCount >= 2) commit()
  })

  /* Focus is the dismissal contract: the popup goes away as soon as the user
     is doing something else.  Deferred, because focus moving WITHIN the popup
     (the scroll pane to the text area) passes through a lost/gained pair. */
  body.addFocusListener(new FocusAdapter {
    override def focusLost(evt: FocusEvent): Unit =
      GUI_Thread.later {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager.getFocusOwner
        if (owner == null || !SwingUtilities.isDescendingFrom(owner, Peek_Panel.this))
          Query_Peek.dismiss()
      }
  })

  def focus(): Unit = body.requestFocusInWindow()
}
