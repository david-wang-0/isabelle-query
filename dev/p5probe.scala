/*  Title:      dev/p5probe.scala

Headless probe for the jEdit plugin's engine-facing layer (P5).

Compiled and run by `dev/p5probe.sh`, which supplies the two corpus
directories in `$P5PROBE_HOL` and `$P5PROBE_NONHOL`.  This exercises everything in the plugin
that does NOT need a display — the caret-word grammar, project discovery from a
file path, the warm index with its mtime cache and dirty-buffer overlay, the
per-project namespace binding, and the usages search — and cross-checks the
answers against the engine entry point the CLI's own `callers` verb uses.

It exists because the plugin's UI cannot be tested here: there is no X server
and no `xvfb-run` on this machine, and launching jEdit on the real display is
forbidden.  What can be checked headlessly is checked; the rest is the manual
checklist in `dev/P5-STATUS.md`.
*/

package isabelle.jedit_query_dev


object P5_Probe {
  def main(args: Array[String]): Unit = {
  import isabelle.*
  import isabelle.query.{Discovery, Namespace, Usage}
  import isabelle.jedit_query.{Query_Index, Query_Search, Query_Word}

  import java.nio.file.{Files, Paths}

  Isabelle_System.init()

  var failures = 0

  def check(name: String, ok: Boolean, detail: String = ""): Unit = {
    if (ok) println("  ok    " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
    else { failures += 1; println("  FAIL  " + name + "  [" + detail + "]") }
  }

  val hol_root = Paths.get(System.getenv("P5PROBE_HOL"))
  val nonhol_root = Paths.get(System.getenv("P5PROBE_NONHOL"))


  /* ---------------- 1. the caret-word grammar ---------------- */

  println("1. Query_Word -- the identifier under the caret")

  def word(line: String, col: Int): String =
    Query_Word.at(line, col).map(_.base).getOrElse("<none>")

  /*             0123456789...                                  */
  val l1 = """  by (rule mono_dtree)"""      // mono_dtree spans columns 11..20
  check("mid-word", word(l1, 15) == "mono_dtree", word(l1, 15))
  check("first character", word(l1, 11) == "mono_dtree", word(l1, 11))
  check("last character", word(l1, 20) == "mono_dtree", word(l1, 20))
  check("just past the end", word(l1, 21) == "mono_dtree", word(l1, 21))
  check("the word before a blank", word(l1, 10) == "rule", word(l1, 10))
  check("caret just after a short word", word(l1, 4) == "by", word(l1, 4))

  val l2 = """  using foo' bar\<^sub>1 by simp"""
  check("a prime belongs to the name", word(l2, 9) == "foo'", word(l2, 9))
  check("a \\<^sub> name stays whole", word(l2, 16) == """bar\<^sub>1""", word(l2, 16))

  val l3 = """  apply (simp add: List.map_ident)"""
  check("a qualified name yields its base", word(l3, 26) == "map_ident", word(l3, 26))

  val l4 = """  apply (subgoal_tac 42)"""
  check("a numeral is not a name", word(l4, 22) == "<none>", word(l4, 22))
  check("empty line", word("", 0) == "<none>", word("", 0))
  check("blank line", word("      ", 3) == "<none>", word("      ", 3))

  /* Past the end of the line the scan falls back to the last symbol, so a
     line that ends in punctuation has no word there and one that ends in a
     name does. */
  val l6 = """  have "P \<Longrightarrow> Q" by simp"""
  check("a bare symbol token is syntax, not a name", word(l6, 15) == "<none>", word(l6, 15))
  check("the word beside it still resolves", word(l6, 4) == "have", word(l6, 4))

  /* A buffer is Isabelle-DECODED: the same name reaches this scanner with the
     control symbol as one character, and must come back encoded. */
  val l7 = "  using bar\u21e91 by simp"
  check("a decoded buffer yields the encoded name",
    word(l7, 9) == """bar\<^sub>1""", word(l7, 9))

  val l5 = """  using mono_dtree"""
  check("past the end, after punctuation", word(l1, 999) == "<none>", word(l1, 999))
  check("past the end, after a name", word(l5, 999) == "mono_dtree", word(l5, 999))


  /* ---------------- 2. project discovery from a file ---------------- */

  println("2. Query_Index.root_of -- which project a buffer belongs to")

  val hol_thys =
    Discovery.walk(hol_root, p => p.getFileName.toString.endsWith(".thy"))
      .sorted(Discovery.Path_Order)
  check("corpus has theories", hol_thys.nonEmpty, hol_thys.length.toString + " .thy files")

  val found_root = Query_Index.root_of(hol_thys.head)
  check("a theory resolves to its session directory",
    found_root.contains(Discovery.real(hol_root)),
    found_root.map(_.toString).getOrElse("<none>"))

  check("a path under no session resolves to nothing",
    Query_Index.root_of(Paths.get("/nonexistent-p5probe/Foo.thy")).isEmpty, "")


  /* ---------------- 3. the warm index ---------------- */

  println("3. Query_Index -- build, cache, invalidate")

  val index = Query_Index(hol_root)
  val t0 = System.currentTimeMillis()
  val snap1 = index.refreshed(Map.empty)
  val cold = System.currentTimeMillis() - t0

  check("index is non-empty", snap1.theories > 0 && snap1.entries > 0,
    snap1.theories.toString + " theories, " + snap1.entries.toString + " entries")

  val t1 = System.currentTimeMillis()
  val snap2 = index.refreshed(Map.empty)
  val warm = System.currentTimeMillis() - t1

  check("a warm refresh reuses every parsed section",
    snap1.sections.length == snap2.sections.length &&
      snap1.sections.zip(snap2.sections).forall { case (a, b) => a eq b },
    "cold " + cold.toString + " ms, warm " + warm.toString + " ms")

  check("status is Ready", index.status.isInstanceOf[Query_Index.Ready], index.status.message)

  index.invalidate()
  val snap3 = index.refreshed(Map.empty)
  check("invalidate forces a reparse",
    snap3.sections.length == snap1.sections.length &&
      !snap3.sections.zip(snap1.sections).exists { case (a, b) => a eq b }, "")

  check("theory set matches discovery",
    snap3.theory_names.toSet == Discovery.theories(hol_root).map(_.name).toSet,
    snap3.theory_names.length.toString)


  /* ---------------- 4. usages ---------------- */

  println("4. Query_Search.usages -- against the engine's own find_callers")

  /* The most-cited declared name that is not itself a proof method or
     attribute — a shadowed name answers a different question. */
  val (subject, expected) =
    index.with_table { table =>
      val shadowed = table.non_citation
      snap3.entry_names.distinct.filterNot(shadowed)
        .map(n => (n, Usage.find_callers(snap3.sections, n, false, namespace = table).length))
        .maxBy(_._2)
    }
  check("a subject with usages exists", expected > 0,
    subject + " x " + expected.toString)

  val result = index.with_table(t => Query_Search.usages(snap3, subject, namespace = t))
  check("hit count matches the engine", result.hits == expected,
    result.hits.toString + " vs " + expected.toString)
  check("grouped by theory, no empty group",
    result.theories > 0 && result.groups.forall(_.hits.nonEmpty),
    result.theories.toString + " theories")
  check("every group carries a resolvable path", result.groups.forall(_.path.isDefined), "")
  check("every hit line is inside its theory",
    result.groups.forall(g =>
      snap3.section(g.theory).exists(sec =>
        g.hits.forall(h => h.line >= 1 && h.line <= sec.lines.length))), "")
  check("every hit text is the source line it points at",
    result.groups.forall(g =>
      snap3.section(g.theory).exists(sec =>
        g.hits.forall(h => sec.lines(h.line - 1).startsWith(h.text.take(20))))), "")
  check("the declaration site is known", result.definition.isDefined,
    result.definition.map(d => d.theory + ":" + d.line.toString).getOrElse("<none>"))

  /* The direct lookups the IDE features are built on. */
  check("definition lookup agrees with the entry table",
    snap3.definition(subject).isDefined, "")
  check("entry_names is the declaration list",
    snap3.entry_names.nonEmpty && snap3.entry_names.length <= snap3.entries,
    snap3.entry_names.length.toString + " of " + snap3.entries.toString)

  println("PROBE-SUBJECT " + subject)
  println("PROBE-HITS " + result.hits.toString)


  /* ---------------- 5. the dirty-buffer overlay ---------------- */

  println("5. overlay -- an unsaved buffer is what gets searched")

  val victim = snap3.sections.find(_.entries.nonEmpty).get
  val victim_path = Discovery.real(victim.path)
  val original =
    new String(Files.readAllBytes(victim_path), java.nio.charset.StandardCharsets.UTF_8)
  val extra = "lemma p5_probe_lemma: \"True\" using " + subject + " by simp\n"
  val cut = original.lastIndexOf("\nend")
  val edited =
    if (cut < 0) original + "\n" + extra
    else original.substring(0, cut + 1) + extra + original.substring(cut + 1)

  val snap4 = index.refreshed(Map(victim_path -> edited))
  val result4 = index.with_table(t => Query_Search.usages(snap4, subject, namespace = t))

  check("the overlaid buffer is reparsed, the rest reused",
    snap4.sections.length == snap3.sections.length &&
      snap4.sections.count { s => !snap3.sections.exists(_ eq s) } == 1,
    "")
  check("the unsaved citation is found", result4.hits == result.hits + 1,
    result4.hits.toString + " vs " + (result.hits + 1).toString)
  check("the probe lemma became an entry", snap4.definition("p5_probe_lemma").isDefined, "")
  check("the file on disk is untouched",
    new String(Files.readAllBytes(victim_path),
      java.nio.charset.StandardCharsets.UTF_8) == original, "")

  val snap5 = index.refreshed(Map.empty)
  val result5 = index.with_table(t => Query_Search.usages(snap5, subject, namespace = t))
  check("dropping the overlay restores the on-disk answer",
    result5.hits == result.hits && snap5.definition("p5_probe_lemma").isEmpty,
    result5.hits.toString)


  /* ---------------- 6. the per-project namespace ---------------- */

  println("6. Namespace -- a VALUE per index, not process state")

  val nonhol = Query_Index(nonhol_root)
  nonhol.refreshed(Map.empty)

  val hol_table = index.with_table(_.methods.size)
  val nonhol_table = nonhol.with_table(_.methods.size)
  val hol_again = index.with_table(_.methods.size)

  check("a HOL project resolves the census union",
    hol_table == Namespace.CENSUS_METHODS.size, hol_table.toString)
  check("a non-HOL project steps down to the Pure floor",
    nonhol_table == Namespace.PURE_METHODS.size, nonhol_table.toString)
  check("interleaving does not leak the other project's table",
    hol_again == hol_table,
    hol_again.toString + " after " + nonhol_table.toString)

  /* The by-value invariant itself, which the old global could not state: both
     projects' tables held at the SAME time, distinct, and each the committed
     one for its base logic.  Under a rebinding global the second `with_table`
     would have overwritten what the first returned. */
  val held_hol = index.with_table(t => t)
  val held_nonhol = nonhol.with_table(t => t)
  check("both projects' tables are held at once, and differ",
    (held_hol eq Namespace.census) && (held_nonhol eq Namespace.pure) &&
      held_hol.methods.size != held_nonhol.methods.size,
    held_hol.methods.size.toString + " vs " + held_nonhol.methods.size.toString)
  check("the two indexes stay distinct objects",
    !(Query_Index(hol_root) eq Query_Index(nonhol_root)) &&
      (Query_Index(hol_root) eq index), "")
  println("PROBE-NOTE " + (if (nonhol.note.isEmpty) "<silent>" else nonhol.note))

  /* ---------------- 7. the jEdit resources ---------------- */

  println("7. the plugin jar -- every class the XML names is loadable")

  /* dockables.xml, services.xml and actions.xml hold BeanShell expressions
     that jEdit evaluates at plugin-load time, so a typo in a class or method
     name there fails at start-up and nowhere else.  Read them back out of the
     built jar and resolve every name against it.  This is the one thing about
     the Swing half that can be checked without a display. */
  val shim =
    Paths.get(Isabelle_System.getenv("JEDIT_SETTINGS"))
      .resolve("jars").resolve("isabelle_jedit_query.jar")

  /* A FAILURE, not a skip: the shim jar is what jEdit actually loads, and
     `dev/p5probe.sh` builds it before running this.  Skipping the section while
     the script still reported OK is how a broken `dockables.xml` would have
     reached a start-up. */
  check("the plugin shim jar is built", Files.isRegularFile(shim), shim.toString)

  if (!Files.isRegularFile(shim)) {
    println("        (isabelle scala -e '{ isabelle.Isabelle_System.init(); " +
      "isabelle.Scala_Project.plugins.foreach(p => p.context().build()) }')")
  }
  else {
    val zip = new java.util.zip.ZipFile(shim.toFile)
    def resource(name: String): String = {
      val entry = zip.getEntry(name)
      if (entry == null) ""
      else {
        val in = zip.getInputStream(entry)
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        finally in.close()
      }
    }

    val loader =
      new java.net.URLClassLoader(Array(shim.toUri.toURL), getClass.getClassLoader)
    def load(name: String): Option[Class[?]] =
      try Some(Class.forName(name, false, loader)) catch { case _: Throwable => None }

    val dockables = resource("dockables.xml")
    val services = resource("services.xml")
    val actions = resource("actions.xml")
    val props = resource("plugin.props")

    check("every resource is in the jar",
      dockables.nonEmpty && services.nonEmpty && actions.nonEmpty && props.nonEmpty, "")

    /* `new <class>(args)` in dockables.xml and services.xml — the class must
       exist AND take the number of arguments the expression passes it, which
       is the whole failure mode this catches. */
    val constructed =
      """new\s+([\w.]+)\s*\(([^)]*)\)""".r.findAllMatchIn(dockables + services)
        .map(m => (m.group(1), m.group(2).trim)).toList
    check("the XML constructs at least two classes", constructed.length >= 2,
      constructed.map(_._1).mkString(", "))
    for ((name, args) <- constructed) {
      val arity = if (args.isEmpty) 0 else args.split(",").length
      check("constructor exists: " + name + "/" + arity.toString,
        load(name).exists(_.getConstructors.exists(_.getParameterCount == arity)), args)
    }

    /* `<class>.<method>(` in actions.xml */
    val called =
      """([\w.]+)\.(\w+)\s*\(\s*(view[^)]*)\)""".r.findAllMatchIn(actions)
        .map(m => (m.group(1), m.group(2), m.group(3).split(",").length)).toList
    check("actions.xml calls at least four entry points", called.length >= 4,
      called.map(_._2).mkString(", "))
    for ((cls, method, arity) <- called) {
      check("action target exists: " + cls + "." + method + "/" + arity.toString,
        load(cls).exists(_.getMethods.exists(m =>
          m.getName == method && m.getParameterCount == arity)), "")
    }

    /* THE CONTEXT MENU CONTRIBUTES ONE ENTRY, AND IT IS A SUBMENU.

       jEdit shows the text-area popup during the mouse PRESS and, when the
       menu does not fit, re-anchors it flush with the window's bottom edge --
       which leaves the pointer INSIDE the popup, on an item.  Swing then
       routes the button RELEASE of that same click into that item
       (`BasicPopupMenuUI.MouseGrabber` -> `MenuSelectionManager` ->
       `BasicMenuItemUI.menuDragMouseReleased` -> `doClick`), and the JDK has
       no guard against a release older than the popup.  So every ROW this
       service adds moves that fold further up the text area: the height of a
       shared-popup contribution is a correctness property, not a taste.  A
       `JMenu` is additionally the one entry kind that cannot be fired that
       way at all -- `BasicMenuUI`'s `menuDragMouseReleased` is empty.

       Checked on the TYPE, not on a built menu: building one needs a live
       `JEditTextArea` and there is no display here -- and the type says more
       than a sample would, because `Option[JMenu]` cannot hold two rows. */
    val contribution =
      Class.forName("isabelle.jedit_query.Query_Context_Menu$").getMethods
        .find(_.getName == "menu").map(_.getGenericReturnType)
    check("the context menu contributes one entry, and it is a JMenu",
      contribution.exists {
        case p: java.lang.reflect.ParameterizedType =>
          p.getRawType == classOf[Option[?]] &&
            p.getActualTypeArguments.toList == List(classOf[javax.swing.JMenu])
        case _ => false
      },
      contribution.map(_.getTypeName).getOrElse("<no Query_Context_Menu.menu>"))

    /* And the "nothing to contribute" contract is `null`, never an empty
       array -- jEdit adds a SEPARATOR for a non-empty list, so an empty array
       leaves a stray divider in everyone else's menu.  Runnable headlessly
       because a null text area never reaches Swing. */
    check("a null text area contributes null, not an empty array",
      new isabelle.jedit_query.Query_Context_Menu().createMenu(null, null) == null, "")

    /* the dockable NAME, and the labels every action needs */
    val dockable_name =
      """NAME="([^"]+)"""".r.findFirstMatchIn(dockables).map(_.group(1)).getOrElse("")
    check("dockables.xml agrees with Query_Dockable.NAME",
      dockable_name == isabelle.jedit_query.Query_Dockable.NAME, dockable_name)

    val action_names = """<ACTION NAME="([^"]+)"""".r.findAllMatchIn(actions).map(_.group(1)).toList
    check("every action has a label in plugin.props",
      action_names.forall(n => props.contains(n + ".label")),
      action_names.mkString(", "))

    /* the gesture defaults the panel reads */
    check("plugin.props ships the gesture table",
      List("single-click", "double-click", "shift-click", "middle-click", "enter")
        .forall(g => props.contains("isabelle-project-query.gesture." + g + "=")), "")

    /* plugin.props must describe the plugin class the jar contains */
    val plugin_class =
      """plugin\.([\w.]+)\.name=""".r.findFirstMatchIn(props).map(_.group(1)).getOrElse("")
    check("plugin.props names a loadable EditPlugin",
      load(plugin_class).exists(c =>
        classOf[org.gjt.sp.jedit.EditPlugin].isAssignableFrom(c)), plugin_class)

    zip.close()
  }


  println(if (failures == 0) "P5PROBE OK" else "P5PROBE FAILURES " + failures.toString)

  /* Isabelle's worker pool holds non-daemon threads, so a probe that merely
     returns from `main` leaves the JVM alive; the CLI exits the same way. */
  System.out.flush()
  sys.exit(if (failures == 0) 0 else 1)
}
}
