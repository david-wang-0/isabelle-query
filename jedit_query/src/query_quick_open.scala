/*  Title:      jedit_query/src/query_quick_open.scala

Go to symbol: type a few letters, land on the declaration.

The index already holds the answer — `Snapshot.entry_names` is every
declaration in the project and `Snapshot.definition` resolves one to its entry
— so this is a picker, not a search.  What matters is where each half runs.

`with_table` re-runs the CLI's `resolve_namespace`, which walks the
project's ROOT files: about ten milliseconds, once, and exactly the wrong shape
for something that would run per keystroke.  So NOTHING here enters the engine
after the index is built.  A keystroke filters an already-materialised list of
strings with `Query_Fuzzy`, on the EDT, behind a 50 ms coalescing delay; the
index build, and only the index build, goes to the worker thread.

The jump reuses the panel's gesture table (`Open_Policy`), so ENTER in this
dialog and ENTER in the results tree do the same configurable thing.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.JEdit_Lib

import java.awt.{BorderLayout, Component, Dimension}
import java.awt.event.{ActionEvent, KeyAdapter, KeyEvent, MouseAdapter, MouseEvent,
  WindowAdapter, WindowEvent}

import javax.swing.{AbstractAction, BorderFactory, DefaultListCellRenderer, DefaultListModel,
  JDialog, JLabel, JList, JPanel, JScrollPane, JTextField, KeyStroke, ListSelectionModel}
import javax.swing.event.{DocumentEvent, DocumentListener}

import org.gjt.sp.jedit.View


object Query_Quick_Open {
  /* Beyond a couple of hundred rows the list is not a choice any more, it is
     a corpus; the answer is to type another letter. */
  val LIMIT: Int = 200

  // owned by GUI thread
  private var active: Option[Query_Quick_Open] = None

  def dismiss(): Unit = {
    GUI_Thread.require {}
    active.foreach(_.close())
    active = None
  }

  /* A dialog that closed itself (ESC, a jump, focus moving away) must drop out
     of the registry too, or `dismiss` keeps a disposed window alive. */
  private[jedit_query] def forget(dialog: Query_Quick_Open): Unit = {
    GUI_Thread.require {}
    if (active.contains(dialog)) active = None
  }

  def open(view: View): Unit = {
    GUI_Thread.require {}
    val index =
      for {
        buffer <- Option(view).flatMap(v => Option(v.getBuffer))
        file <- JEdit_Lib.buffer_file(buffer)
        index <- Query_Index.for_file(file.toPath)
      } yield index

    index match {
      case None =>
        Query_Options.status("no Isabelle project for the current buffer")
      case Some(index) =>
        dismiss()
        val dialog = new Query_Quick_Open(view, index)
        active = Some(dialog)
        dialog.start()
    }
  }
}


class Query_Quick_Open private[jedit_query] (view: View, index: Query_Index) {
  private val dialog = new JDialog(view, "Go to symbol", false)

  private val field = new JTextField(40)
  private val model = new DefaultListModel[Query_Fuzzy.Match]
  private val list = new JList[Query_Fuzzy.Match](model)
  private val caption = new JLabel(" ")

  /* The candidate list and the snapshot behind it, both handed over from the
     worker thread once and then read only. */
  @volatile private var names: List[String] = Nil
  @volatile private var snapshot: Option[Query_Index.Snapshot] = None


  /* ------------------------------------------------------------------ */
  /* rendering                                                          */
  /* ------------------------------------------------------------------ */

  private def row_html(m: Query_Fuzzy.Match): String = {
    val shown = Symbol.decode(m.name)
    val buf = new StringBuilder("<html>")
    /* The positions are indices into the RAW name; a decoded name has a
       different length, so highlighting is only applied when the two agree —
       a `\<^sub>` name simply renders unhighlighted rather than wrongly. */
    if (shown.length == m.name.length && m.positions.nonEmpty) {
      val marked = m.positions.toSet
      for (i <- 0 until shown.length) {
        val c = Query_Dockable.escape(shown.substring(i, i + 1))
        if (marked(i)) { buf ++= "<b>"; buf ++= c; buf ++= "</b>" } else buf ++= c
      }
    }
    else buf ++= Query_Dockable.escape(shown)

    for {
      snap <- snapshot
      (theory, entry) <- snap.definition(m.name)
    } {
      buf ++= "  <font color=\"gray\">"
      buf ++= Query_Dockable.escape(entry.tag + " -- " + theory + ":" + entry.thy_line.toString)
      buf ++= "</font>"
    }
    buf ++= "</html>"
    buf.toString
  }

  list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  list.setCellRenderer(new DefaultListCellRenderer {
    override def getListCellRendererComponent(l: JList[?], value: Object, i: Int,
      selected: Boolean, focus: Boolean
    ): Component = {
      val c = super.getListCellRendererComponent(l, value, i, selected, focus)
      value match {
        case m: Query_Fuzzy.Match => setText(row_html(m))
        case _ =>
      }
      c
    }
  })


  /* ------------------------------------------------------------------ */
  /* filtering                                                          */
  /* ------------------------------------------------------------------ */

  private def refilter(): Unit = {
    GUI_Thread.require {}
    val query = field.getText.trim
    val hits = Query_Fuzzy.filter(query, names, Query_Quick_Open.LIMIT)
    model.clear()
    hits.foreach(model.addElement)
    if (model.getSize > 0) list.setSelectedIndex(0)
    caption.setText(
      if (names.isEmpty) index.status.message
      else
        hits.length.toString + (if (hits.length == Query_Quick_Open.LIMIT) "+" else "") +
          " of " + names.length.toString + " declarations")
  }

  /* Coalescing, so holding a key down does not schedule one full scan per
     repeat.  `Delay.last` is Isabelle's own, GUI-flavoured. */
  private val delay: Delay = Delay.last(Time.seconds(0.05), gui = true) { refilter() }

  field.getDocument.addDocumentListener(new DocumentListener {
    def insertUpdate(e: DocumentEvent): Unit = delay.invoke()
    def removeUpdate(e: DocumentEvent): Unit = delay.invoke()
    def changedUpdate(e: DocumentEvent): Unit = delay.invoke()
  })


  /* ------------------------------------------------------------------ */
  /* jumping                                                            */
  /* ------------------------------------------------------------------ */

  private def selected: Option[Query_Fuzzy.Match] = Option(list.getSelectedValue)

  private def go(policy: Open_Policy): Unit = {
    GUI_Thread.require {}
    for {
      m <- selected
      snap <- snapshot
      (theory, entry) <- snap.definition(m.name)
      path <- snap.path_of(theory)
    } {
      close()
      Query_Editor.goto(view, path, entry.thy_line, policy)
    }
  }

  private def move(delta: Int): Unit = {
    val n = model.getSize
    if (n > 0) {
      val i = ((list.getSelectedIndex + delta) max 0) min (n - 1)
      list.setSelectedIndex(i)
      list.ensureIndexIsVisible(i)
    }
  }

  private val keys = new KeyAdapter {
    override def keyPressed(evt: KeyEvent): Unit =
      evt.getKeyCode match {
        case KeyEvent.VK_ESCAPE => close(); evt.consume()
        case KeyEvent.VK_ENTER =>
          go(Open_Policy.of_gesture("enter")); evt.consume()
        case KeyEvent.VK_DOWN => move(1); evt.consume()
        case KeyEvent.VK_UP => move(-1); evt.consume()
        case KeyEvent.VK_PAGE_DOWN => move(10); evt.consume()
        case KeyEvent.VK_PAGE_UP => move(-10); evt.consume()
        case _ =>
      }
  }

  field.addKeyListener(keys)
  list.addKeyListener(keys)

  list.addMouseListener(new MouseAdapter {
    override def mousePressed(evt: MouseEvent): Unit = {
      val i = list.locationToIndex(evt.getPoint)
      if (i >= 0) {
        list.setSelectedIndex(i)
        val policy = Open_Policy.of_click(evt)
        if (policy != Open_Policy.Nothing) go(policy)
      }
    }
  })


  /* ------------------------------------------------------------------ */
  /* lifecycle                                                          */
  /* ------------------------------------------------------------------ */

  private val panel = new JPanel(new BorderLayout(4, 4))
  panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6))
  panel.add(field, BorderLayout.NORTH)
  panel.add(new JScrollPane(list), BorderLayout.CENTER)
  panel.add(caption, BorderLayout.SOUTH)

  dialog.setContentPane(panel)
  dialog.setSize(new Dimension(620, 420))
  dialog.setLocationRelativeTo(view)

  /* A quick-open that survives losing focus is a window the user has to close;
     it should behave like the popup it is.  Only AFTER it has been focused
     once, though: `setVisible` can bounce focus before the dialog settles, and
     a dialog that closes itself on the way up is worse than one that lingers. */
  @volatile private var was_focused = false

  dialog.addWindowFocusListener(new WindowAdapter {
    override def windowGainedFocus(evt: WindowEvent): Unit = was_focused = true
    override def windowLostFocus(evt: WindowEvent): Unit = if (was_focused) close()
  })

  def close(): Unit = {
    GUI_Thread.require {}
    delay.revoke()
    dialog.setVisible(false)
    dialog.dispose()
    Query_Quick_Open.forget(this)
  }

  def start(): Unit = {
    GUI_Thread.require {}
    caption.setText(index.name + ": " + index.status.message)
    dialog.setVisible(true)
    field.requestFocusInWindow()

    val overlay = Query_Dockable.overlay(index.root)
    Query_Index.background {
      try {
        val snap =
          index.refreshed(overlay,
            st => GUI_Thread.later { if (names.isEmpty) caption.setText(index.name + ": " + st.message) })
        /* Force the name map HERE, on the worker: it is a `lazy val` over
           every entry in the project, and the renderer reads it per visible
           row on the EDT. */
        snap.entry_by_name
        val candidates = snap.entry_names.distinct
        GUI_Thread.later {
          snapshot = Some(snap)
          names = candidates
          refilter()
        }
      }
      catch {
        case exn: Throwable =>
          val msg = Exn.message(exn)
          GUI_Thread.later { caption.setText("index failed: " + msg) }
      }
    }
  }
}
