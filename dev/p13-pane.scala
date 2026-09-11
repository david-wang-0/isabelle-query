/* Headless EDT regression for the production callers pane publication seam.
   Run with dev/p13-pane.sh; no View, display, prover or live jEdit is created. */
package isabelle.jedit_query_dev

import isabelle.jedit_query.{Query_Dockable, Query_Search, Query_Index, Query_Peek}
import java.nio.file.Paths
import javax.swing.{JTree, SwingUtilities}
import javax.swing.tree.{DefaultMutableTreeNode, DefaultTreeModel, TreePath}

object P13_Pane {
  def main(args: Array[String]): Unit = {
    System.setProperty("java.awt.headless", "true")
    isabelle.Isabelle_System.init()
    var checks = 0
    SwingUtilities.invokeAndWait(() => {
      import Query_Search.*
      val root = new DefaultMutableTreeNode
      val model = new DefaultTreeModel(root)
      val pane = new Query_Dockable.Result_Tree(model)
      val tree = new JTree(model)
      tree.setRootVisible(false)
      def check(label: String)(ok: => Boolean): Unit = {
        assert(ok, label)
        checks += 1
        println("ok " + label)
      }
      def key(name: String = "A.foo", project: String = "project", external: Boolean = false,
        kind: Result_Kind = Result_Kind.Usages): Scope =
        scope(Paths.get(project), name, external, kind)
      def result(k: Scope, text: String = "fresh", empty: Boolean = false,
        failed: Boolean = false): Result = {
        val path = Some(k.root.resolve("A.thy"))
        Result(k.kind, "usages of " + k.name, k.name,
          if (empty || failed) Nil else List(Group("A", path,
            List(Hit("A", path, 3, text)))), None, "",
          if (failed) "query failed: synthetic failure" else "")
      }
      def publish(t: Query_Dockable.Ticket, r: Result): Option[DefaultMutableTreeNode] =
        pane.publish(t, r) { node =>
          for (g <- r.groups) {
            val group = new DefaultMutableTreeNode(g)
            g.hits.foreach(h => group.add(new DefaultMutableTreeNode(h)))
            node.add(group)
          }
        }
      def run(k: Scope, text: String = "fresh", stack: Boolean = true): DefaultMutableTreeNode =
        publish(pane.begin(k, stack), result(k, text)).get
      def path(n: DefaultMutableTreeNode): TreePath = new TreePath(n.getPath.map(_.asInstanceOf[Object]))
      def value(n: DefaultMutableTreeNode): Result = n.getUserObject.asInstanceOf[Result]

      val a = key()
      val b = key("B.foo")
      val first = run(a, "old")
      val other = run(b)
      val other_group = other.getChildAt(0).asInstanceOf[DefaultMutableTreeNode]
      tree.expandPath(path(other_group))
      val updated = run(a, "changed")
      check("stack refresh replaces one exact group") {
        root.getChildCount == (if (args.contains("--faildemo")) 3 else 2) && (updated eq first) &&
          value(first).groups.head.hits.head.text == "changed"
      }
      check("unrelated expanded group and node identity survive") {
        (root.getChildAt(1) eq other) && tree.isExpanded(path(other_group))
      }
      tree.collapsePath(path(other))
      run(a, "again", stack = false)
      check("matching refresh also preserves unrelated collapsed group with Stack off") {
        root.getChildCount == 2 && !tree.isExpanded(path(other))
      }
      run(key(project = "second-project"))
      run(key(external = true))
      run(key("foo"))
      run(key(kind = Result_Kind.Definition))
      check("project, qualification, external scope and kind remain distinct") {
        root.getChildCount == 6
      }
      check("equivalent lexical project roots share a key") {
        key(project = "project/../project") == a
      }

      publish(pane.begin(a, true), result(a, empty = true))
      check("successful empty refresh clears stale callers") {
        root.getChildCount == 6 && first.getChildCount == 0 &&
          value(first).is_empty && value(first).refused.isEmpty
      }
      run(a, "before failure")
      publish(pane.begin(a, true), result(a, failed = true))
      check("failure replaces stale rows with explicit failure") {
        first.getChildCount == 0 && value(first).refused.contains("synthetic failure")
      }
      run(a, "recovered")
      check("success recovers the same failed group") {
        root.getChildCount == 6 && value(first).refused.isEmpty && first.getChildCount == 1
      }

      val old = pane.begin(a, true)
      val newest = pane.begin(a, true)
      check("new request immediately invalidates older completion and progress") {
        !pane.current(old) && !pane.foreground(old) && pane.foreground(newest)
      }
      check("old success cannot land even before newer completion") {
        publish(old, result(a, "too old")).isEmpty
      }
      publish(newest, result(a, "newest"))
      check("late old failure cannot overwrite newer success") {
        publish(old, result(a, failed = true)).isEmpty &&
          value(first).groups.head.hits.head.text == "newest"
      }
      val earlier = pane.begin(a, true)
      val later = pane.begin(a, true)
      publish(later, result(a, empty = true))
      check("late old success cannot restore rows after newer empty") {
        publish(earlier, result(a, "obsolete")).isEmpty && first.getChildCount == 0
      }
      val x = pane.begin(a, true)
      val y = pane.begin(b, true)
      publish(y, result(b, "B newest"))
      check("distinct scopes can complete in reverse order") {
        publish(x, result(a, "A newest")).nonEmpty &&
          value(other).groups.head.hits.head.text == "B newest"
      }
      val deleted = pane.begin(a, true)
      pane.remove(first)
      check("Delete cancels a refresh of the deleted group") {
        publish(deleted, result(a)).isEmpty && first.getParent == null
      }
      val cleared = pane.begin(b, true)
      pane.clear()
      check("Clear cancels pending work") {
        root.getChildCount == 0 && publish(cleared, result(b)).isEmpty
      }
      run(a)
      run(b, stack = false)
      check("new distinct query with Stack off replaces the pane") {
        root.getChildCount == 1 && value(root.getChildAt(0).asInstanceOf[DefaultMutableTreeNode]).name == b.name
      }
      val cancelled = pane.begin(a, true)
      val replacement = pane.begin(b, false)
      check("new nonstack request cancels older queued requests") { !pane.current(cancelled) }
      publish(replacement, result(b))
    })
    // The real compressed source representation, with whole-source access
    // forbidden: reverting any of the three excerpt call sites must fail.
    {
      import isabelle.query.{Entry, Regions, Theory_Section}
      val root = Paths.get("p13-excerpts").toAbsolutePath
      val file = root.resolve("Large.thy")
      val lines = Array.tabulate(100000)(i => s"source line ${i + 1} " + "x" * 80)
      lines(0) = "theory Large imports Main begin"
      lines(49) = "lemma sample: \"α = α\""
      lines(50) = "  by simp"
      lines(lines.length - 1) = "end"
      val entry = Entry("LEMMA", "sample", "fallback", 50,
        decl_end_line = 50, thy_end = 99, body_end_line = 99, theory = "Large")
      val sec = new Theory_Section("Large", file, List(entry), lines, Regions.empty_result) {
        override def lines: Array[String] = sys.error("whole-source lines requested for excerpt")
        override def text: String = sys.error("whole-source text requested for excerpt")
      }
      val snapshot = new Query_Index.Snapshot(root, List(sec))
      def check(label: String)(ok: => Boolean): Unit = {
        assert(ok, label)
        checks += 1
        println("ok " + label)
      }
      check("definition hit uses exactly one captured source line") {
        Query_Search.definition_hit(snapshot, "sample").get.text == lines(49)
      }
      for (limit <- List(0, 1, 24, 40, 100)) {
        val hits = Query_Search.body_hits(sec, entry, limit)
        val n = if (limit <= 0) 50 else limit min 50
        check(s"declaration excerpt preserves exact extent and truncation, limit=$limit") {
          hits.filterNot(_.note).map(_.text) == lines.slice(49, 49 + n).toList &&
            hits.filterNot(_.note).map(_.line) == (50 until 50 + n).toList &&
            (if (n < 50) hits.last.note && hits.last.text == s"[+${50 - n} more lines, to 99]"
             else !hits.exists(_.note))
        }
      }
      check("named peek preserves decoding and its 24-line limit") {
        val rows = Query_Peek.of_name(snapshot, "sample").get.rows
        rows.take(24).map(_._2) == lines.slice(49, 73).map(isabelle.Symbol.decode).toList &&
          rows.last == (0, "[+26 more lines, to 99]")
      }
      for (at <- List(1, 25, lines.length, lines.length + 2)) {
        val lo = (at - Query_Peek.CONTEXT) max 1
        val hi = (at + Query_Peek.CONTEXT) min lines.length
        check(s"unowned peek preserves clipped context at line $at") {
          Query_Peek.of_line(snapshot, file, at).get.rows ==
            lines.slice(lo - 1, hi).zipWithIndex.map { case (text, i) =>
              (lo + i, isabelle.Symbol.decode(text)) }.toList
        }
      }
      // A caller/unsaved buffer mutation must not change the captured excerpt.
      val captured = Query_Peek.of_line(snapshot, file, 25).get
      lines(24) = "subsequent unsaved edit"
      check("peek remains tied to its captured source snapshot") {
        Query_Peek.of_line(snapshot, file, 25).get == captured
      }
      val bean = java.lang.management.ManagementFactory.getThreadMXBean
        .asInstanceOf[com.sun.management.ThreadMXBean]
      if (bean.isThreadAllocatedMemorySupported) {
        bean.setThreadAllocatedMemoryEnabled(true)
        for (_ <- 1 to 10) Query_Peek.of_line(snapshot, file, 25)
        val thread = Thread.currentThread().getId
        val before = bean.getThreadAllocatedBytes(thread)
        for (_ <- 1 to 10) Query_Peek.of_line(snapshot, file, 25)
        val bytes = (bean.getThreadAllocatedBytes(thread) - before) / 10
        println(s"P13 tiny peek: $bytes allocated bytes/request, ${sec.thy_lines} source lines")
        check("tiny peek allocation stays below full source size") {
          bytes > 0 && bytes < sec.source_utf8_bytes / 4
        }
      }
    }
    println(s"P13-PANE OK ($checks checks)")
  }
}
