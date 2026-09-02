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

A site set (P6d) has one more level: the DIRECTORY the theory lives in, which
is where the same tree grows a `HyperSearchFolderNode` when jEdit's own "Tree
View" is on.  Directory nodes always open, down to the file level — a
collapsed directory shows a name and a number, which is less than the flat list
it replaced — and the file level then follows the kind's `expand_groups` as it
always did.  A directory is not a navigation target (it has no line), so a
double-click on one toggles it, which is the JTree's own behaviour and
HyperSearch's.

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

import javax.swing.{AbstractAction, Box, BoxLayout, DefaultListCellRenderer,
  DefaultListModel, JButton, JCheckBox, JLabel, JList, JPanel, JPopupMenu, JScrollPane,
  JTextField, JTree, KeyStroke, ListSelectionModel, UIManager}
import javax.swing.event.{DocumentEvent, DocumentListener}
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

  /* Persisted like `Stack`: a display preference, remembered per user. */
  val SORTS_PROPERTY = "isabelle-project-query.sorts"

  /* The button that opens the finder menu beside the name field. */
  val FIND_LABEL = "Find"

  /* Focus the name field of this view's panel, opening the panel first. */
  def focus_search(view: View): Unit = {
    GUI_Thread.require {}
    show(view).foreach(_.focus_name_field())
  }

  /* Open dockables, so an action or a context menu can push a result into the
     one belonging to its own view.  `getDockableWindow` returns null until the
     window has been added, hence the registry as well as the lookup. */
  private val instances = new mutable.LinkedHashMap[View, Query_Dockable]

  private[jedit_query] def register(view: View, dockable: Query_Dockable): Unit =
    GUI_Thread.require { instances(view) = dockable }

  private[jedit_query] def unregister(view: View): Unit =
    GUI_Thread.require { instances -= view }

  /* Called from the plugin's EditBus handler: the jump history changes on
     every caret move, and the panel's back/forward buttons follow it. */
  def update_navigation(): Unit =
    GUI_Thread.later { instances.values.foreach(_.update_navigation()) }

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
    show(view).foreach(_.request(buffer, name, external, Query_Search.Result_Kind.Usages))
  }

  def find_definition(view: View, buffer: Buffer, name: String): Unit = {
    GUI_Thread.require {}
    show(view).foreach(_.request(buffer, name, false, Query_Search.Result_Kind.Definition))
  }

  def find_instantiations(view: View, buffer: Buffer, name: String): Unit = {
    GUI_Thread.require {}
    show(view).foreach(_.request(buffer, name, false, Query_Search.Result_Kind.Instantiations))
  }

  def find_code_equations(view: View, buffer: Buffer, name: String): Unit = {
    GUI_Thread.require {}
    show(view).foreach(_.request(buffer, name, false, Query_Search.Result_Kind.Code_Equations))
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

  /* Just the DIRECTORY nodes below a result set — empty for every kind that
     has none, which is what lets one `open_result` serve all of them.
     `expandPath` opens the ancestors of what it is given, so the order this
     returns them in does not matter. */
  private def folder_paths(node: DefaultMutableTreeNode): List[TreePath] = {
    val paths = new mutable.ListBuffer[TreePath]
    HyperSearchResults.traverseNodes(node, new HyperSearchTreeNodeCallback {
      def processNode(n: DefaultMutableTreeNode): Boolean = {
        n.getUserObject match {
          case _: Query_Search.Folder => paths += tree_path(n)
          case _ =>
        }
        true
      }
    })
    paths.toList
  }


  /* --- captions --- */

  private def plural(n: Int, one: String, many: String): String =
    n.toString + " " + (if (n == 1) one else many)

  /* The result-set caption counts what the kind actually holds: a usages set
     counts hits and the theories they fall in, a declaration counts the source
     lines it is showing — "19 hits in 1 theory" for one lemma is a miscount
     dressed as a summary. */
  /* Public and over plain numbers rather than over the private `Counter`, so
     the headless probe can pin the wording: a caption is the one part of the
     tree a display-less machine can still check. */
  def count_caption(kind: Query_Search.Result_Kind, hits: Int, groups: Int): String =
    kind match {
      case Query_Search.Result_Kind.Definition => plural(hits, "line", "lines")
      /* A site list counts SITES, not hits: an instantiation is a place where
         something happens, and "3 hits" would describe the search rather than
         the answer. */
      case Query_Search.Result_Kind.Instantiations | Query_Search.Result_Kind.Code_Equations =>
        plural(hits, "site", "sites") + " in " + plural(groups, "theory", "theories")
      case _ =>
        plural(hits, "hit", "hits") + " in " + plural(groups, "theory", "theories")
    }

  private def count_caption(kind: Query_Search.Result_Kind, c: Counter): String =
    count_caption(kind, c.hits, c.groups)

  /* A FILE node's own count.  A usages or definition set keeps the bare number
     it has always shown; a site set spells the noun, because in that tree a
     bare `(2)` sits one row under a directory's `(2 sites in 1 theory)` and
     reads as a different quantity. */
  def group_caption(kind: Query_Search.Result_Kind, caption: String, hits: Int): String =
    caption + " (" + (kind match {
      case Query_Search.Result_Kind.Instantiations | Query_Search.Result_Kind.Code_Equations =>
        plural(hits, "site", "sites")
      case _ => hits.toString
    }) + ")"

  /* A DIRECTORY node's: everything below it, at any depth, in the kind's own
     words -- the same `count_caption` the result root uses, so the two levels
     cannot drift into two vocabularies.  In this tree one file is one theory,
     which is why the second half reads "theories". */
  def folder_caption(kind: Query_Search.Result_Kind, name: String, hits: Int,
    files: Int
  ): String = name + " (" + count_caption(kind, hits, files) + ")"

  /* What an EMPTY set of this kind has none of. */
  def empty_noun(kind: Query_Search.Result_Kind): String =
    kind match {
      case Query_Search.Result_Kind.Definition => "declaration"
      case Query_Search.Result_Kind.Instantiations => "instantiations"
      case Query_Search.Result_Kind.Code_Equations => "code equations"
      case _ => "usages"
    }

  /* Package-visible: the quick-open list renders HTML labels too, and two
     escapers is one too many. */
  private[jedit_query] def escape(s: String): String = {
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
  /* What a site row is CALLED, as the CLI's name column spells it: bare, or
     with the written sort when the Sorts toggle is on.  Shared with the CLI
     through `Sites.Site.label` would be the ideal, but a `Hit` is the panel's
     own record; the two are pinned against each other in `dev/p6bprobe`. */
  def hit_name(hit: Query_Search.Hit, sorts: Boolean): String =
    if (sorts && hit.sorts.nonEmpty) hit.name + " :: " + hit.sorts else hit.name

  def hit_html(name: String, hit: Query_Search.Hit, sorts: Boolean = false): String = {
    val shown = Symbol.decode(hit.text).trim
    /* A note is ABOUT the source ("[+17 more lines, to 94]"), so it carries no
       line number and nothing in it is a citation to highlight. */
    if (hit.note) "<html><i>" + escape(shown) + "</i></html>"
    else {
      val target = Symbol.decode(name)
      val buf = new StringBuilder("<html>")
      buf ++= hit.line.toString
      buf ++= ": "
      /* The site's NAME, then its ROLE in italics, then the source.  The name
         is plain: bold already means "an occurrence of what you searched for"
         below, and a second meaning for one weight would make both unreadable. */
      if (hit.name.nonEmpty) {
        buf ++= escape(Symbol.decode(hit_name(hit, sorts)))
        buf ++= "&nbsp;&nbsp;"
      }
      /* A site's ROLE, in italics before the source: it is what the row is
         about, not part of the line it quotes.  Italic rather than a colour,
         for the reason above. */
      if (hit.tag.nonEmpty) {
        buf ++= "<i>"
        buf ++= escape(hit.tag)
        buf ++= "</i>&nbsp;&nbsp;"
      }
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
}


class Query_Dockable(view: View, position: String) extends Dockable(view, position) {
  /* ------------------------------------------------------------------ */
  /* state                                                              */
  /* ------------------------------------------------------------------ */

  /* What Refresh replays.  `kind` is the RESULT kind the request produces --
     the one switch that decides which engine entry point runs -- so a fifth
     view is a case here and a case in `run`, not a fifth boolean. */
  private final case class Request(
    file: JPath, name: String, external: Boolean,
    kind: Query_Search.Result_Kind = Query_Search.Result_Kind.Usages)

  private var last_request: Option[Request] = None

  /* Whether site rows show their written sorts.  Read from the PROPERTY, not
     from the checkbox below: the tree and its renderer are built first, and a
     renderer that reached forward into a control declared after it would work
     only by accident of initialisation order. */
  private def sorts_on: Boolean =
    jEdit.getBooleanProperty(Query_Dockable.SORTS_PROPERTY, false)


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
            case hit: Query_Search.Hit =>
              if (hit.note) Symbol.decode(hit.text).trim
              else hit.line.toString + ": " +
                (if (hit.name.isEmpty) ""
                 else Symbol.decode(Query_Dockable.hit_name(hit, sorts_on)) + "  ") +
                (if (hit.tag.isEmpty) "" else hit.tag + "  ") +
                Symbol.decode(hit.text).trim
            case folder: Query_Search.Folder => folder.name
            case group: Query_Search.Group => group.caption
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
              setText(result.label + " -- " +
                Query_Dockable.count_caption(result.kind, Query_Dockable.count(node)))
            case folder: Query_Search.Folder =>
              setFont(bold_font)
              val c = Query_Dockable.count(node)
              setText(
                Query_Dockable.folder_caption(result_kind(node), folder.name, c.hits, c.groups))
            case group: Query_Search.Group =>
              setFont(bold_font)
              setText(Query_Dockable.group_caption(result_kind(node), group.caption,
                Query_Dockable.count(node).hits))
            case hit: Query_Search.Hit =>
              setFont(plain_font)
              setText(Query_Dockable.hit_html(result_name(node), hit, sorts_on))
            case _ => setFont(plain_font)
          }
        case _ => setFont(plain_font)
      }
    }
  }

  /* The searched name and the KIND both live on the enclosing result set,
     which is where a row has to look for them — the same walk-up
     `HyperSearchResults`' highlighting tree does, and the reason a node removal
     cannot desynchronise it.  Written once and over an arbitrary depth,
     because with a directory level the walk is no longer one or two steps. */
  private def enclosing_result(node: DefaultMutableTreeNode): Option[Query_Search.Result] = {
    var n = node.getParent
    while (n != null) {
      n match {
        case d: DefaultMutableTreeNode =>
          d.getUserObject match {
            case r: Query_Search.Result => return Some(r)
            case _ =>
          }
        case _ =>
      }
      n = n.getParent
    }
    None
  }

  private def result_name(node: DefaultMutableTreeNode): String =
    enclosing_result(node).map(_.name).getOrElse("")

  private def result_kind(node: DefaultMutableTreeNode): Query_Search.Result_Kind =
    enclosing_result(node).map(_.kind).getOrElse(Query_Search.Result_Kind.Usages)

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
      /* A DIRECTORY has nothing to open: no file, and therefore no line.  So
         every gesture is a no-op on one and the JTree's own double-click
         toggle is what is left, which is what a HyperSearch folder node does
         too.  The popup menu still offers Expand / Collapse / Remove. */
      case _: Query_Search.Folder => None
      case _ => None
    }

  /* Where a peek popup opens: at the gesture, or — for a keyboard gesture and
     for the popup menu — at the selected row, so it never lands on top of what
     it is describing. */
  private def anchor(evt: MouseEvent): Option[(java.awt.Component, java.awt.Point)] = {
    val point =
      if (evt != null) Some(evt.getPoint)
      else
        Option(tree.getLeadSelectionPath).map(tree.getPathBounds).collect {
          case r if r != null => new java.awt.Point(r.x, r.y + r.height)
        }
    point.map((tree, _))
  }

  private def goto_selected(policy: Open_Policy, evt: MouseEvent = null): Unit = {
    GUI_Thread.require {}
    for {
      node <- node_of(tree.getLeadSelectionPath)
      (path, line) <- target_of(node)
    } Query_Editor.goto(view, path, line, policy, anchor(evt))
    /* Our own jump is recorded by `goto_file` like any other, so the buttons
       have to follow it here as well as from the EditBus. */
    update_navigation()
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

  /* How a result set of this kind opens, at every level it has.

     The result node itself always opens, so its contents are visible.  Every
     DIRECTORY under it opens too — a directory node that shows only its own
     name is strictly less than the flat list it replaced, so there is no kind
     for which a closed one is the right default.  The FILE level is the kind's
     own choice, and the one this seam has always carried.

     Written as one function over both, rather than a branch per kind: for a
     kind with no directories `folder_paths` is empty and this is exactly the
     `tree.expandPath(path)` it replaces. */
  private def open_result(node: DefaultMutableTreeNode,
    kind: Query_Search.Result_Kind
  ): Unit = {
    GUI_Thread.require {}
    if (kind.expand_groups) expand_node(node)
    else {
      tree.expandPath(Query_Dockable.tree_path(node))
      for (path <- Query_Dockable.folder_paths(node)) tree.expandPath(path)
    }
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
      item("Peek") { goto_selected(Open_Policy.Peek) }
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
            goto_selected(Open_Policy.of_click(evt), evt)
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

  /* The CLI's `--sorts`, as a toggle.  It changes only what a row SAYS, and
     both halves are already on every `Hit`, so it repaints the tree instead of
     re-running the query — a result set on screen must not be lost to a
     display choice. */
  private val sorts_button = new JCheckBox("Sorts")
  sorts_button.setToolTipText(
    "on site rows, show the sort / arity / signature the SOURCE writes " +
      "(no types are inferred)")
  sorts_button.setSelected(sorts_on)
  sorts_button.addActionListener((_: ActionEvent) => {
    jEdit.setBooleanProperty(Query_Dockable.SORTS_PROPERTY, sorts_button.isSelected)
    tree.repaint()
  })

  private def button(label: String, tip: String)(body: => Unit): JButton = {
    val b = new JButton(label)
    b.setToolTipText(tip)
    b.addActionListener((_: ActionEvent) => body)
    b
  }

  /* The two navigation buttons drive `Isabelle_Navigator`, which is the
     history EVERY jump in the editor lands in — not a per-panel one.  They sit
     first because that is where a browser puts them, and they are disabled
     (with a tooltip that says why) whenever the prover plugin is not up. */
  private val back_button =
    button(Query_Navigate.BACK, Query_Navigate.back_tip)(Query_Navigate.backward(view))
  private val forward_button =
    button(Query_Navigate.FORWARD, Query_Navigate.forward_tip)(Query_Navigate.forward(view))

  def update_navigation(): Unit = {
    GUI_Thread.require {}
    back_button.setEnabled(Query_Navigate.can_backward)
    forward_button.setEnabled(Query_Navigate.can_forward)
    back_button.setToolTipText(Query_Navigate.back_tip)
    forward_button.setToolTipText(Query_Navigate.forward_tip)
  }

  private val buttons = new Box(BoxLayout.X_AXIS)
  buttons.add(back_button)
  buttons.add(forward_button)
  buttons.add(Box.createHorizontalStrut(8))
  buttons.add(button("Refresh", "re-read the project and re-run the last query")(refresh()))
  buttons.add(button("Expand", "expand every result set")(expand_all()))
  buttons.add(button("Collapse", "collapse every result set")(collapse_all()))
  buttons.add(button("Clear", "remove every result set")(clear()))
  buttons.add(stack_button)
  buttons.add(sorts_button)


  /* --- search by name --- */

  /* Isar's diagnostics take the name as an argument (`code_thms c`); the
     right-click menu takes it from the caret.  This is the first: an input,
     resolved against the index, and then any finder that applies.

     The completion list is a NON-FOCUSABLE popup driven from the field's own
     key handler, rather than a second window: a dialog here would be
     `Query_Quick_Open` again, and the panel already has somewhere to put
     results. */
  private val name_field = new JTextField(14)
  name_field.setToolTipText(
    "a declaration name; ENTER finds its usages, " + Query_Dockable.FIND_LABEL +
      " offers the rest")

  private val completion = new JPopupMenu
  completion.setFocusable(false)
  private val completion_model = new DefaultListModel[Query_Fuzzy.Match]
  private val completion_list = new JList[Query_Fuzzy.Match](completion_model)
  completion_list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  completion_list.setVisibleRowCount(8)
  completion_list.setFocusable(false)
  locally {
    val scroll = new JScrollPane(completion_list)
    scroll.setPreferredSize(new java.awt.Dimension(360, 160))
    completion.add(scroll)
  }

  /* The snapshot the field reads: whatever the index ALREADY has.  A volatile
     read, never a parse — this runs per keystroke on the EDT, exactly as the
     context menu's kind check does. */
  private def current_snapshot: Option[Query_Index.Snapshot] =
    for {
      buffer <- Option(view.getBuffer)
      file <- JEdit_Lib.buffer_file(buffer)
      index <- Query_Index.for_file(file.toPath)
      snapshot <- index.snapshot
    } yield snapshot

  private def hide_completion(): Unit = {
    GUI_Thread.require {}
    if (completion.isVisible) completion.setVisible(false)
  }

  private def show_completion(): Unit = {
    GUI_Thread.require {}
    val typed = name_field.getText.trim
    val hits =
      if (typed.isEmpty) Nil
      else Query_Name_Search.candidates(current_snapshot, typed, Query_Quick_Open.LIMIT)
    completion_model.clear()
    hits.take(50).foreach(completion_model.addElement)
    if (completion_model.getSize > 0) {
      completion_list.setSelectedIndex(0)
      if (!completion.isVisible && name_field.isShowing)
        completion.show(name_field, 0, name_field.getHeight)
    }
    else hide_completion()
    set_caption(Query_Name_Search.hint(current_snapshot, typed))
  }

  /* Coalesced exactly as go-to-symbol coalesces: holding a key down must not
     schedule one full scan per repeat. */
  private val completion_delay: Delay =
    Delay.last(Time.seconds(0.05), gui = true) { show_completion() }

  name_field.getDocument.addDocumentListener(new DocumentListener {
    def insertUpdate(e: DocumentEvent): Unit = completion_delay.invoke()
    def removeUpdate(e: DocumentEvent): Unit = completion_delay.invoke()
    def changedUpdate(e: DocumentEvent): Unit = completion_delay.invoke()
  })

  private def move_completion(delta: Int): Unit = {
    val n = completion_model.getSize
    if (n > 0) {
      val i = ((completion_list.getSelectedIndex + delta) max 0) min (n - 1)
      completion_list.setSelectedIndex(i)
      completion_list.ensureIndexIsVisible(i)
    }
  }

  /* What the field currently MEANS: the highlighted completion if the list is
     up, else the typed text resolved exact-then-fuzzy. */
  private def field_name: String = {
    val typed = name_field.getText.trim
    if (completion.isVisible) Option(completion_list.getSelectedValue) match {
      case Some(m) => m.name
      case None => Query_Name_Search.resolve(current_snapshot, typed)
    }
    else Query_Name_Search.resolve(current_snapshot, typed)
  }

  private def run_finder(finder: Query_Name_Search.Finder): Unit = {
    GUI_Thread.require {}
    val name = field_name
    hide_completion()
    if (name.isEmpty) set_caption("type a declaration name to search for")
    else
      Option(view.getBuffer) match {
        case Some(buffer) => request(buffer, name, finder.external, finder.kind)
        case None => set_caption("no buffer -- nothing to search")
      }
  }

  /* The finders this name admits, as a menu.  Built when it is opened, from
     the snapshot at that moment, so it says the same thing the right-click
     menu would. */
  private def finder_menu(anchor: java.awt.Component): Unit = {
    GUI_Thread.require {}
    val name = field_name
    val menu = new JPopupMenu
    val offered = Query_Name_Search.finders(current_snapshot, name)
    if (offered.isEmpty)
      menu.add(new AbstractAction("(type a declaration name)") {
        def actionPerformed(e: ActionEvent): Unit = ()
      }).setEnabled(false)
    else
      for (finder <- offered)
        menu.add(new AbstractAction(finder.label + " of " + name) {
          def actionPerformed(e: ActionEvent): Unit = run_finder(finder)
        })
    GenericGUIUtilities.showPopupMenu(menu, anchor, 0, anchor.getHeight)
  }

  private val find_button: JButton =
    button(Query_Dockable.FIND_LABEL,
      "the finders this name admits (the site verbs only where they have an answer)") {
      finder_menu(find_button)
    }

  name_field.addKeyListener(new KeyAdapter {
    override def keyPressed(evt: KeyEvent): Unit =
      evt.getKeyCode match {
        case KeyEvent.VK_ESCAPE if completion.isVisible => hide_completion(); evt.consume()
        case KeyEvent.VK_DOWN => move_completion(1); evt.consume()
        case KeyEvent.VK_UP => move_completion(-1); evt.consume()
        case KeyEvent.VK_PAGE_DOWN => move_completion(10); evt.consume()
        case KeyEvent.VK_PAGE_UP => move_completion(-10); evt.consume()
        /* ENTER runs the default finder; the button (or CTRL+ENTER) offers the
           rest, because a menu on every ENTER would be one gesture too many
           for the thing people ask for nine times in ten. */
        case KeyEvent.VK_ENTER if evt.isControlDown =>
          finder_menu(name_field); evt.consume()
        case KeyEvent.VK_ENTER =>
          run_finder(Query_Name_Search.ungated.head); evt.consume()
        case _ =>
      }
  })

  completion_list.setCellRenderer(new DefaultListCellRenderer {
    override def getListCellRendererComponent(l: JList[?], value: Object, i: Int,
      selected: Boolean, focus: Boolean
    ): java.awt.Component = {
      val c = super.getListCellRendererComponent(l, value, i, selected, focus)
      value match {
        case m: Query_Fuzzy.Match =>
          setText("<html>" + Query_Dockable.escape(Symbol.decode(m.name)) +
            (current_snapshot.flatMap(_.definition(m.name)) match {
              case Some((theory, entry)) =>
                "  <font color=\"gray\">" +
                  Query_Dockable.escape(entry.tag + " -- " + theory + ":" +
                    entry.thy_line.toString) + "</font>"
              case None => ""
            }) + "</html>")
        case _ =>
      }
      c
    }
  })

  completion_list.addMouseListener(new MouseAdapter {
    override def mousePressed(evt: MouseEvent): Unit = {
      val i = completion_list.locationToIndex(evt.getPoint)
      if (i >= 0) {
        completion_list.setSelectedIndex(i)
        name_field.setText(completion_model.getElementAt(i).name)
        hide_completion()
        name_field.requestFocusInWindow()
      }
    }
  })

  private val search_box = new Box(BoxLayout.X_AXIS)
  search_box.add(new JLabel("Name: "))
  search_box.add(name_field)
  search_box.add(find_button)
  search_box.add(Box.createHorizontalStrut(8))

  /* Focus the field, for the action that gives this a keyboard route. */
  def focus_name_field(): Unit = {
    GUI_Thread.require {}
    name_field.requestFocusInWindow()
    name_field.selectAll()
  }

  /* BorderLayout rather than one Box: the caption takes the remaining width
     and a JLabel clips itself with an ellipsis, where a Box would let a long
     status line push the buttons out of the panel. */
  private val controls = new JPanel(new BorderLayout)
  controls.add(search_box, BorderLayout.WEST)
  controls.add(caption, BorderLayout.CENTER)
  controls.add(buttons, BorderLayout.EAST)

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

  def request(buffer: Buffer, name: String, external: Boolean,
    kind: Query_Search.Result_Kind
  ): Unit = {
    GUI_Thread.require {}
    JEdit_Lib.buffer_file(buffer) match {
      case Some(file) => run(Request(file.toPath, name, external, kind))
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
              index.with_table { table =>
                req.kind match {
                  case Query_Search.Result_Kind.Definition =>
                    Query_Search.definition(snapshot, req.name, index.note)
                  case Query_Search.Result_Kind.Instantiations =>
                    Query_Search.instantiations(snapshot, req.name, index.note)
                  case Query_Search.Result_Kind.Code_Equations =>
                    Query_Search.code_equations(snapshot, req.name, index.note)
                  case _ =>
                    Query_Search.usages(snapshot, req.name, req.external, index.note, table)
                }
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

  /* A theory and its lines — the two levels that have always been there. */
  private def group_node(group: Query_Search.Group): DefaultMutableTreeNode = {
    val node = new DefaultMutableTreeNode(group)
    for (hit <- group.hits) node.add(new DefaultMutableTreeNode(hit))
    node
  }

  /* A `Folder`'s CONTENTS under `parent`, so the unnamed root of
     `Query_Search.tree` hangs its children straight off the result node
     instead of becoming a node of its own.  Directories first, then the files
     of the same level: a directory is a heading, and a heading below the rows
     it heads reads as an afterthought. */
  private def add_folder(parent: DefaultMutableTreeNode,
    folder: Query_Search.Folder
  ): Unit = {
    for (sub <- folder.folders) {
      val node = new DefaultMutableTreeNode(sub)
      add_folder(node, sub)
      parent.add(node)
    }
    for (group <- folder.groups) parent.add(group_node(group))
  }

  private def handle(index: Query_Index, result: Query_Search.Result): Unit = {
    GUI_Thread.require {}

    /* A question that could not be asked is not an empty answer: say which,
       in the words the CLI would have exited 1 with. */
    if (result.refused.nonEmpty) set_caption(result.refused + " -- " + status(index))
    else if (result.is_empty)
      set_caption("no " + Query_Dockable.empty_noun(result.kind) + " of " + result.name +
        " -- " + status(index))
    else {
      val set_node = new DefaultMutableTreeNode(result)
      /* Building the tree is pure arithmetic over a result set the worker has
         already computed — no engine call, no file read — which is what makes
         it EDT work rather than another background hop. */
      if (result.kind.folders) add_folder(set_node, Query_Search.tree(index.root, result.groups))
      else for (group <- result.groups) set_node.add(group_node(group))

      /* Inserting rather than reloading keeps the expansion state of the
         result sets already on the tree; a reload would collapse all of them
         every time a new query lands. */
      if (stack_button.isSelected) {
        tree_root.add(set_node)
        tree_model.nodesWereInserted(tree_root, Array(tree_root.getChildCount - 1))
      }
      else {
        tree_root.removeAllChildren()
        tree_root.add(set_node)
        tree_model.reload(tree_root)
      }

      val path = Query_Dockable.tree_path(set_node)
      open_result(set_node, result.kind)
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
    update_navigation()
    set_caption(
      Option(view.getBuffer).flatMap(JEdit_Lib.buffer_file)
        .flatMap(f => Query_Index.for_file(f.toPath)) match {
          case Some(index) => status(index)
          case None => "right-click an identifier in a theory, or use Find Usages"
        })
  }

  override def exit(): Unit = Query_Dockable.unregister(view)
}
