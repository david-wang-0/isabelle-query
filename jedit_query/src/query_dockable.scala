/*  Title:      jedit_query/src/query_dockable.scala

The Project Query results panel.

Presentation follows jEdit's own directory-search window (the HyperSearch
Results dockable): an invisible root, one bold node per query carrying the query
text and a hit count, one bold node per file below it with its own count, and
one leaf per matching line, line-number-prefixed and with the searched name
picked out.  Successive queries stack as siblings rather than replacing each
other; DELETE removes a subtree; the toolbar clears, expands and collapses.
The expand affordance is the tree's own handle — the same arrow HyperSearch
puts on a file node — not a toggle of our own invention, down to the angled
line style jEdit sets on that tree.

ONE node model serves every kind of result (`Query_Search.Result_Kind`).  A
usages set opens with its per-theory nodes COLLAPSED, because it can be
hundreds of lines; a definition set opens EXPANDED, because it is the one line
the user asked for.  Both carry exactly the same navigation.

The tree is re-implemented rather than reused, because `HyperSearchResults` is
a concrete singleton wired 1:1 to `SearchAndReplace` / `HyperSearchRequest` /
`SearchMatcher` — its stop, multi and redo controls all name those classes, and
its dockable NAME belongs to core jEdit.  What IS reused is everything that is
actually general: `HyperSearchResults.traverseNodes` with a
`HyperSearchTreeNodeCallback` for every walk of the tree (so the counts in a
caption stay right after a user deletes a node, rather than being frozen at
build time), `EnhancedTreeCellRenderer` as the renderer base (a plain
`DefaultTreeCellRenderer` subclass renders wrongly after a Look-and-Feel
change), and `GenericGUIUtilities.isPopupTrigger` / `showPopupMenu` for the
platform's right-click convention.

Threading: every engine call is handed to `Query_Index.background`, and every
result comes back through `GUI_Thread.later`.  Nothing below `handle` touches
the tree off the EDT.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.{Dockable, JEdit_Lib}
import isabelle.query.{Commands, Py}

import java.awt.BorderLayout
import java.awt.event.{ActionEvent, KeyAdapter, KeyEvent, MouseAdapter, MouseEvent}
import java.nio.file.{Path => JPath}

import javax.swing.{AbstractAction, Box, BoxLayout, JButton, JCheckBox, JLabel, JPopupMenu,
  JScrollPane, JTree, KeyStroke, UIManager}
import javax.swing.tree.{DefaultMutableTreeNode, DefaultTreeModel, TreeCellRenderer,
  TreePath, TreeSelectionModel}

import org.gjt.sp.jedit.{jEdit, Buffer, OperatingSystem, View}
import org.gjt.sp.jedit.search.{HyperSearchResults, HyperSearchTreeNodeCallback}
import org.gjt.sp.util.{EnhancedTreeCellRenderer, GenericGUIUtilities}

import scala.collection.mutable


object Query_Dockable {
  /* Must match dockables.xml.  NOT `isabelle-query`: Isabelle/jEdit already
     owns that name for its prover Query panel (find_theorems / find_consts /
     print_context), and two dockables cannot share a name. */
  val NAME = "isabelle-project-query"

  /* Open dockables, so an action or a context menu can push a result into the
     one belonging to its own view.  `getDockableWindow` returns null until the
     window has been added, hence the registry as well as the lookup. */
  private val instances = new mutable.LinkedHashMap[View, Query_Dockable]

  private[jedit_query] def register(view: View, dockable: Query_Dockable): Unit =
    GUI_Thread.require { instances(view) = dockable }

  private[jedit_query] def unregister(view: View): Unit =
    GUI_Thread.require { instances -= view }

  def show(view: View): Option[Query_Dockable] = {
    GUI_Thread.require {}
    val wm = view.getDockableWindowManager
    wm.showDockableWindow(NAME)
    wm.getDockableWindow(NAME) match {
      case d: Query_Dockable => Some(d)
      case _ => instances.get(view)
    }
  }

  /* The whole plugin path from the outside: resolve, then list. */
  def find_usages(view: View, buffer: Buffer, name: String, external: Boolean = false): Unit = {
    GUI_Thread.require {}
    show(view).foreach(_.request(buffer, name, external = external, definition = false))
  }

  def find_definition(view: View, buffer: Buffer, name: String): Unit = {
    GUI_Thread.require {}
    show(view).foreach(_.request(buffer, name, external = false, definition = true))
  }


  /* --- buffer text --- */

  /* The dirty buffers of one project, encoded back to file form so the engine
     sees what a save would have written.  Collected on the EDT (a buffer's
     text may not be read from a worker thread without its lock, and the lock
     is what `buffer_text` takes). */
  def overlay(root: JPath): Map[JPath, String] = {
    GUI_Thread.require {}
    val out = new mutable.LinkedHashMap[JPath, String]
    for {
      buffer <- JEdit_Lib.jedit_buffers()
      if buffer.isDirty && !buffer.isLoading
      file <- JEdit_Lib.buffer_file(buffer)
      if file.getName.endsWith(".thy")
    } {
      val path =
        try isabelle.query.Discovery.real(file.toPath.toAbsolutePath)
        catch { case _: Throwable => null }
      if (path != null && path.startsWith(root))
        out(path) = Symbol.encode(JEdit_Lib.buffer_text(buffer))
    }
    out.toMap
  }


  /* --- tree walks, on jEdit's own helper --- */

  private class Counter extends HyperSearchTreeNodeCallback {
    var hits: Int = 0
    var groups: Int = 0
    def processNode(node: DefaultMutableTreeNode): Boolean = {
      node.getUserObject match {
        case _: Query_Search.Hit => hits += 1
        case _: Query_Search.Group => groups += 1
        case _ =>
      }
      true
    }
  }

  private def count(node: DefaultMutableTreeNode): Counter = {
    val c = new Counter
    HyperSearchResults.traverseNodes(node, c)
    c
  }

  private def tree_path(node: DefaultMutableTreeNode): TreePath =
    new TreePath(node.getPath.map(_.asInstanceOf[Object]))

  private def subtree(node: DefaultMutableTreeNode): List[TreePath] = {
    val paths = new mutable.ListBuffer[TreePath]
    HyperSearchResults.traverseNodes(node, new HyperSearchTreeNodeCallback {
      def processNode(n: DefaultMutableTreeNode): Boolean = { paths += tree_path(n); true }
    })
    paths.toList
  }


  /* --- captions --- */

  private def plural(n: Int, one: String, many: String): String =
    n.toString + " " + (if (n == 1) one else many)

  private def escape(s: String): String = {
    val buf = new StringBuilder
    for (c <- s) {
      c match {
        case '<' => buf ++= "&lt;"
        case '>' => buf ++= "&gt;"
        case '&' => buf ++= "&amp;"
        case '"' => buf ++= "&quot;"
        case _ => buf += c
      }
    }
    buf.toString
  }

  /* Line number, then the source line with every occurrence of the searched
     name in bold.  The occurrences are found with the ENGINE's word pattern,
     so what is highlighted is what was counted; a name the pattern cannot be
     built for simply renders unhighlighted.  No colours: an HTML label keeps
     them when the row is selected, and a hard-coded colour is unreadable on
     half the themes. */
  def hit_html(name: String, hit: Query_Search.Hit): String = {
    val shown = Symbol.decode(hit.text).trim
    val target = Symbol.decode(name)
    val buf = new StringBuilder("<html>")
    buf ++= hit.line.toString
    buf ++= ": "
    try {
      val matcher = Py.compile(Commands.isa_word_pattern(target)).matcher(shown)
      var prev = 0
      while (matcher.find()) {
        buf ++= escape(shown.substring(prev, matcher.start))
        buf ++= "<b>"
        buf ++= escape(shown.substring(matcher.start, matcher.end))
        buf ++= "</b>"
        prev = matcher.end
      }
      buf ++= escape(shown.substring(prev))
    }
    catch { case _: Throwable => buf ++= escape(shown) }
    buf ++= "</html>"
    buf.toString
  }
}


class Query_Dockable(view: View, position: String) extends Dockable(view, position) {
  /* ------------------------------------------------------------------ */
  /* state                                                              */
  /* ------------------------------------------------------------------ */

  /* What Refresh replays. */
  private final case class Request(
    file: JPath, name: String, external: Boolean, definition: Boolean)

  private var last_request: Option[Request] = None


  /* ------------------------------------------------------------------ */
  /* the tree                                                           */
  /* ------------------------------------------------------------------ */

  private val tree_root = new DefaultMutableTreeNode
  private val tree_model = new DefaultTreeModel(tree_root)

  private val tree: JTree = new JTree(tree_model) {
    /* The renderer already produces the caption; converting here as well
       would double-escape it.  This is what a screen reader and the tree's
       type-ahead see. */
    override def convertValueToText(value: Object, selected: Boolean, expanded: Boolean,
      leaf: Boolean, row: Int, focus: Boolean
    ): String =
      value match {
        case node: DefaultMutableTreeNode =>
          node.getUserObject match {
            case hit: Query_Search.Hit => hit.line.toString + ": " + Symbol.decode(hit.text).trim
            case group: Query_Search.Group => group.theory
            case result: Query_Search.Result => result.label
            case null => ""
            case obj => obj.toString
          }
        case _ => ""
      }
  }

  private class Renderer extends EnhancedTreeCellRenderer {
    private val plain_font = {
      val f = UIManager.getFont("Tree.font")
      if (f == null) tree.getFont else f
    }
    private val bold_font = plain_font.deriveFont(java.awt.Font.BOLD)

    protected def newInstance(): TreeCellRenderer = new Renderer

    protected def configureTreeCellRendererComponent(t: JTree, value: Object,
      selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean
    ): Unit = {
      setIcon(null)
      value match {
        case node: DefaultMutableTreeNode =>
          node.getUserObject match {
            case result: Query_Search.Result =>
              setFont(bold_font)
              val c = Query_Dockable.count(node)
              setText(result.label + " -- " +
                Query_Dockable.plural(c.hits, "hit", "hits") + " in " +
                Query_Dockable.plural(c.groups, "theory", "theories"))
            case group: Query_Search.Group =>
              setFont(bold_font)
              setText(group.theory + " (" + Query_Dockable.count(node).hits.toString + ")")
            case hit: Query_Search.Hit =>
              setFont(plain_font)
              setText(Query_Dockable.hit_html(result_name(node), hit))
            case _ => setFont(plain_font)
          }
        case _ => setFont(plain_font)
      }
    }
  }

  /* The searched name lives on the enclosing result set, which is where a leaf
     has to look for it — the same walk-up `HyperSearchResults`' highlighting
     tree does, and the reason a node removal cannot desynchronise it. */
  private def result_name(node: DefaultMutableTreeNode): String = {
    var n = node.getParent
    while (n != null) {
      n match {
        case d: DefaultMutableTreeNode =>
          d.getUserObject match {
            case r: Query_Search.Result => return r.name
            case _ =>
          }
        case _ =>
      }
      n = n.getParent
    }
    ""
  }

  tree.setRootVisible(false)
  tree.setShowsRootHandles(true)
  tree.setEditable(false)
  tree.setRowHeight(0)
  tree.setToolTipText(null)
  tree.setCellRenderer(new Renderer)
  /* Discontiguous, so a user can select several nodes and remove them at once;
     everything that jumps uses the LEAD path, so a multiple selection can
     never open several files at once by accident. */
  tree.getSelectionModel.setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION)
  /* ESCAPE is bound to hideTip by Swing, which shadows jEdit's
     close-docking-area — the same removal HyperSearchResults makes. */
  tree.getInputMap.remove(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
  if (!OperatingSystem.isMacOSLF) tree.putClientProperty("JTree.lineStyle", "Angled")


  /* ------------------------------------------------------------------ */
  /* navigation                                                         */
  /* ------------------------------------------------------------------ */

  private def node_of(path: TreePath): Option[DefaultMutableTreeNode] =
    if (path == null) None
    else path.getLastPathComponent match {
      case node: DefaultMutableTreeNode => Some(node)
      case _ => None
    }

  private def target_of(node: DefaultMutableTreeNode): Option[(JPath, Int)] =
    node.getUserObject match {
      case hit: Query_Search.Hit => hit.path.map((_, hit.line))
      case group: Query_Search.Group => group.path.map((_, 1))
      case result: Query_Search.Result => result.definition.flatMap(d => d.path.map((_, d.line)))
      case _ => None
    }

  private def goto_selected(policy: Open_Policy): Unit = {
    GUI_Thread.require {}
    for {
      node <- node_of(tree.getLeadSelectionPath)
      (path, line) <- target_of(node)
    } Query_Editor.goto(view, path, line, policy)
  }

  private def remove_selected(): Unit = {
    GUI_Thread.require {}
    val paths = tree.getSelectionPaths
    if (paths != null) {
      for (path <- paths if path.getPathCount > 1) {
        node_of(path).foreach { node =>
          if (node.getParent != null) tree_model.removeNodeFromParent(node)
        }
      }
      tree.clearSelection()
      tree_model.nodeStructureChanged(tree_root)
    }
  }

  private def expand_node(node: DefaultMutableTreeNode): Unit = {
    GUI_Thread.require {}
    for (path <- Query_Dockable.subtree(node)) tree.expandPath(path)
  }

  private def collapse_node(node: DefaultMutableTreeNode): Unit = {
    GUI_Thread.require {}
    /* Deepest first, so collapsing a parent does not hide a child that still
       has to be collapsed for the next expansion to look right. */
    for (path <- Query_Dockable.subtree(node).reverse) tree.collapsePath(path)
  }

  def expand_all(): Unit = {
    GUI_Thread.require {}
    expand_node(tree_root)
  }

  def collapse_all(): Unit = {
    GUI_Thread.require {}
    var row = tree.getRowCount - 1
    while (row >= 0) { tree.collapseRow(row); row -= 1 }
  }


  /* ------------------------------------------------------------------ */
  /* mouse and keyboard                                                 */
  /* ------------------------------------------------------------------ */

  private def popup_menu(evt: MouseEvent): Unit = {
    val menu = new JPopupMenu

    def item(label: String)(body: => Unit): Unit =
      menu.add(new AbstractAction(label) {
        def actionPerformed(e: ActionEvent): Unit = body
      })

    val node = node_of(tree.getLeadSelectionPath)
    if (node.exists(n => target_of(n).isDefined)) {
      item("Open") { goto_selected(Open_Policy.Current) }
      item("Open in new pane") { goto_selected(Open_Policy.New_Pane) }
      item("Open in new view") { goto_selected(Open_Policy.New_View) }
      menu.addSeparator()
    }
    node.filter(!_.isLeaf).foreach { n =>
      item("Expand") { expand_node(n) }
      item("Collapse") { collapse_node(n) }
      menu.addSeparator()
    }
    item("Remove") { remove_selected() }
    item("Clear all") { clear() }
    GenericGUIUtilities.showPopupMenu(menu, tree, evt.getX, evt.getY)
  }

  tree.addMouseListener(new MouseAdapter {
    override def mousePressed(evt: MouseEvent): Unit = {
      if (!evt.isConsumed) {
        val path = tree.getPathForLocation(evt.getX, evt.getY)
        if (path != null) {
          if (GenericGUIUtilities.isPopupTrigger(evt)) {
            if (!tree.isPathSelected(path)) tree.setSelectionPath(path)
            popup_menu(evt)
          }
          else {
            tree.setSelectionPath(path)
            goto_selected(Open_Policy.of_click(evt))
          }
        }
      }
    }
  })

  tree.addKeyListener(new KeyAdapter {
    override def keyPressed(evt: KeyEvent): Unit = {
      evt.getKeyCode match {
        case KeyEvent.VK_ENTER | KeyEvent.VK_SPACE =>
          goto_selected(Open_Policy.of_gesture("enter")); evt.consume()
        case KeyEvent.VK_DELETE =>
          remove_selected(); evt.consume()
        case _ =>
      }
    }
  })


  /* ------------------------------------------------------------------ */
  /* controls                                                           */
  /* ------------------------------------------------------------------ */

  private val caption = new JLabel(" ")

  private val stack_button = new JCheckBox("Stack")
  stack_button.setToolTipText("keep earlier result sets as siblings")
  stack_button.setSelected(jEdit.getBooleanProperty("isabelle-project-query.stack", true))
  stack_button.addActionListener((_: ActionEvent) =>
    jEdit.setBooleanProperty("isabelle-project-query.stack", stack_button.isSelected))

  private def button(label: String, tip: String)(body: => Unit): JButton = {
    val b = new JButton(label)
    b.setToolTipText(tip)
    b.addActionListener((_: ActionEvent) => body)
    b
  }

  private val controls = new Box(BoxLayout.X_AXIS)
  controls.add(caption)
  controls.add(Box.createGlue())
  controls.add(button("Refresh", "re-read the project and re-run the last query")(refresh()))
  controls.add(button("Expand", "expand every result set")(expand_all()))
  controls.add(button("Collapse", "collapse every result set")(collapse_all()))
  controls.add(button("Clear", "remove every result set")(clear()))
  controls.add(stack_button)

  add(controls, BorderLayout.NORTH)
  set_content(new JScrollPane(tree))


  /* ------------------------------------------------------------------ */
  /* status                                                             */
  /* ------------------------------------------------------------------ */

  private def set_caption(text: String): Unit = {
    GUI_Thread.require {}
    caption.setText(if (text.isEmpty) " " else text)
  }

  private def status(index: Query_Index, prefix: String = ""): String = {
    val head = if (prefix.isEmpty) "" else prefix + " -- "
    head + index.name + ": " + index.status.message
  }


  /* ------------------------------------------------------------------ */
  /* running a query                                                    */
  /* ------------------------------------------------------------------ */

  def clear(): Unit = {
    GUI_Thread.require {}
    tree_root.removeAllChildren()
    tree_model.reload(tree_root)
    set_caption("")
  }

  def refresh(): Unit = {
    GUI_Thread.require {}
    last_request match {
      case Some(req) =>
        Query_Index.for_file(req.file).foreach(_.invalidate())
        run(req)
      case None =>
        for {
          buffer <- Option(view.getBuffer)
          file <- JEdit_Lib.buffer_file(buffer)
          index <- Query_Index.for_file(file.toPath)
        } {
          index.invalidate()
          reindex(index)
        }
    }
  }

  def request(buffer: Buffer, name: String, external: Boolean, definition: Boolean): Unit = {
    GUI_Thread.require {}
    JEdit_Lib.buffer_file(buffer) match {
      case Some(file) => run(Request(file.toPath, name, external, definition))
      case None => set_caption("not a file buffer -- nothing to search")
    }
  }

  /* Index (incrementally) and then search, both on the worker thread; the tree
     is only ever touched from the EDT. */
  private def run(req: Request): Unit = {
    GUI_Thread.require {}
    last_request = Some(req)
    Query_Index.for_file(req.file) match {
      case None =>
        set_caption("no ROOT above " + req.file.getFileName.toString +
          " -- not an Isabelle project")
      case Some(index) =>
        val overlay = Query_Dockable.overlay(index.root)
        set_caption(status(index, "searching " + req.name))
        Query_Index.background {
          try {
            val snapshot =
              index.refreshed(overlay,
                st => GUI_Thread.later { set_caption(index.name + ": " + st.message) })
            val result =
              index.with_namespace {
                if (req.definition) Query_Search.definition(snapshot, req.name, index.note)
                else Query_Search.usages(snapshot, req.name, req.external, index.note)
              }
            GUI_Thread.later { handle(index, result) }
          }
          catch {
            case exn: Throwable =>
              val msg = Exn.message(exn)
              GUI_Thread.later { set_caption("query failed: " + msg) }
          }
        }
    }
  }

  /* Rebuild the index without searching -- what Refresh does when nothing has
     been queried yet. */
  private def reindex(index: Query_Index): Unit = {
    GUI_Thread.require {}
    val overlay = Query_Dockable.overlay(index.root)
    Query_Index.background {
      try {
        index.refreshed(overlay,
          st => GUI_Thread.later { set_caption(index.name + ": " + st.message) })
        ()
      }
      catch {
        case exn: Throwable =>
          val msg = Exn.message(exn)
          GUI_Thread.later { set_caption("index failed: " + msg) }
      }
    }
  }

  private def handle(index: Query_Index, result: Query_Search.Result): Unit = {
    GUI_Thread.require {}

    if (result.is_empty) {
      val what = if (result.kind == Query_Search.Result_Kind.Definition) "declaration" else "usages"
      set_caption("no " + what + " of " + result.name + " -- " + status(index))
    }
    else {
      val set_node = new DefaultMutableTreeNode(result)
      for (group <- result.groups) {
        val group_node = new DefaultMutableTreeNode(group)
        for (hit <- group.hits) group_node.add(new DefaultMutableTreeNode(hit))
        set_node.add(group_node)
      }

      if (!stack_button.isSelected) tree_root.removeAllChildren()
      tree_root.add(set_node)
      tree_model.reload(tree_root)

      /* The result set itself always opens, so its theories are visible; the
         theories open only for a kind that says so. */
      val path = Query_Dockable.tree_path(set_node)
      if (result.kind.expand_groups) expand_node(set_node) else tree.expandPath(path)
      tree.setSelectionPath(path)
      tree.scrollPathToVisible(path)

      set_caption(status(index))
    }
  }


  /* ------------------------------------------------------------------ */
  /* lifecycle                                                          */
  /* ------------------------------------------------------------------ */

  override def init(): Unit = {
    Query_Dockable.register(view, this)
    set_caption(
      Option(view.getBuffer).flatMap(JEdit_Lib.buffer_file)
        .flatMap(f => Query_Index.for_file(f.toPath)) match {
          case Some(index) => status(index)
          case None => "right-click an identifier in a theory, or use Find Usages"
        })
  }

  override def exit(): Unit = Query_Dockable.unregister(view)
}
