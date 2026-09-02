/*  Title:      dev/p6probe.scala

Headless probe for the P6 IDE features.

`dev/p5probe.scala` covers the engine-facing layer P5 built (the caret word,
project discovery, the warm index, usages, the overlay, the per-project
namespace, and the plugin jar's XML).  This one covers what P6 added, and the
split is deliberate: P5's probe is a regression gate that must keep passing
untouched, so P6 does not edit it except where a check was WRONG.

What is reachable without a display turns out to be most of P6:

  * `Query_Fuzzy` is a pure function and is pinned outright, ranking included;
  * find-definition's content is data — the group caption, the body lines,
    their numbers, the truncation tail — and is checked against the source it
    claims to quote AND against the CLI's own `show -V`;
  * the peek popup's CONTENT is the same data, resolved the same way;
  * the gesture table is a resolution function over jEdit properties, which do
    work headlessly, so the invalid-value path (the P5 red flag) is a test
    rather than a promise;
  * the index cap is a threshold and a message.

What is NOT reachable is every pixel: the popup, the dialog, the toolbar
buttons, the tree.  Those are the manual checklist in `dev/P6-STATUS.md`.
*/

package isabelle.jedit_query_dev


object P6_Probe {
  def main(args: Array[String]): Unit = {
  import isabelle.*
  import isabelle.query.{Discovery, Entry, Render, Theory_Section}
  import isabelle.jedit_query.{Open_Policy, Query_Dockable, Query_Fuzzy, Query_Index,
    Query_Navigate, Query_Options, Query_Peek, Query_Search}

  import java.awt.event.{InputEvent, MouseEvent}
  import java.nio.file.{Files, Paths}

  import org.gjt.sp.jedit.jEdit

  Isabelle_System.init()

  var failures = 0

  def check(name: String, ok: Boolean, detail: String = ""): Unit = {
    if (ok) println("  ok    " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
    else { failures += 1; println("  FAIL  " + name + "  [" + detail + "]") }
  }

  val hol_root = Paths.get(System.getenv("P6PROBE_HOL"))
  val out_dir = Paths.get(System.getenv("P6PROBE_OUT"))


  /* ---------------- 1. the fuzzy matcher ---------------- */

  println("1. Query_Fuzzy -- the ranking, pinned")

  def score(q: String, c: String): Option[Int] = Query_Fuzzy.matching(q, c).map(_.score)
  def best(q: String, cs: List[String]): List[String] =
    Query_Fuzzy.filter(q, cs, 0).map(_.name)

  check("a subsequence matches", score("mdt", "mono_dtree").isDefined, "")
  check("out of order does not", score("tdm", "mono_dtree").isEmpty, "")
  check("a missing character does not", score("mdtx", "mono_dtree").isEmpty, "")
  check("case is ignored", score("MDT", "mono_dtree").isDefined, "")
  check("the empty query matches everything",
    score("", "anything").contains(0) && score("", "").contains(0), "")
  check("nothing matches the empty candidate for a non-empty query",
    score("a", "").isEmpty, "")

  check("word starts are found",
    Query_Fuzzy.is_word_start("mono_dtree", 0) &&
      Query_Fuzzy.is_word_start("mono_dtree", 5) &&
      !Query_Fuzzy.is_word_start("mono_dtree", 6) &&
      Query_Fuzzy.is_word_start("monoDtree", 4), "")

  /* The three orderings a user actually relies on. */
  check("an exact name outranks a longer one",
    best("map", List("map_ident", "map", "mapper")) == List("map", "mapper", "map_ident"),
    best("map", List("map_ident", "map", "mapper")).mkString(", "))
  check("a prefix outranks an interior match",
    best("tree", List("wf_tree", "tree_map")).head == "tree_map",
    best("tree", List("wf_tree", "tree_map")).mkString(", "))
  check("word starts outrank a scattered match",
    best("md", List("mono_dtree", "command")).head == "mono_dtree",
    best("md", List("mono_dtree", "command")).mkString(", "))
  check("equal scores break by length then name",
    best("ab", List("ab_z", "ab_a", "ab")) == List("ab", "ab_a", "ab_z"),
    best("ab", List("ab_z", "ab_a", "ab")).mkString(", "))

  check("the limit is honoured",
    Query_Fuzzy.filter("a", List("a", "ab", "abc", "abcd"), 2).length == 2, "")
  check("positions index the candidate",
    Query_Fuzzy.matching("mt", "mono_tree").map(_.positions).contains(List(0, 5)),
    Query_Fuzzy.matching("mt", "mono_tree").map(_.positions.mkString(",")).getOrElse("<none>"))

  /* Determinism: the same input, twice, is the same list. */
  val shuffled = List("beta", "alpha", "gamma", "alpha_beta", "al")
  check("the ranking is a function",
    best("al", shuffled) == best("al", shuffled), best("al", shuffled).mkString(", "))


  /* ---------------- 2. the index, and its cap ---------------- */

  println("2. Query_Index -- the size guard")

  check("over_limit is off at zero and below",
    !Query_Index.over_limit(10000, 0) && !Query_Index.over_limit(10000, -1), "")
  check("over_limit is exclusive at the limit",
    !Query_Index.over_limit(2000, 2000) && Query_Index.over_limit(2001, 2000), "")
  check("the message names both ways out", {
    val m = Query_Index.limit_message("HOL", 10336, 2000)
    m.contains("10336") && m.contains("2000") && m.contains("index-limit") &&
      m.contains(".isabelle-query")
  }, Query_Index.limit_message("HOL", 10336, 2000))

  check("the default limit clears src/HOL and stops an AFP checkout",
    Query_Index.LIMIT_DEFAULT > 1451 && Query_Index.LIMIT_DEFAULT < 10336,
    Query_Index.LIMIT_DEFAULT.toString)

  val index = Query_Index(hol_root)

  jEdit.setProperty("isabelle-project-query.index-limit", "1")
  check("the property is read back", Query_Index.limit == 1, Query_Index.limit.toString)
  val refused =
    try { index.refreshed(Map.empty); None }
    catch { case exn: Throwable => Some(Exn.message(exn)) }
  check("a project over the limit is refused, not truncated",
    refused.exists(_.contains("too large")), refused.getOrElse("<indexed anyway>"))
  check("the status says so too",
    index.status match {
      case Query_Index.Failed(m) => m.contains("too large")
      case _ => false
    }, index.status.message)

  jEdit.unsetProperty("isabelle-project-query.index-limit")
  check("clearing the property restores the built-in default",
    Query_Index.limit == Query_Index.LIMIT_DEFAULT, Query_Index.limit.toString)

  val snapshot = index.refreshed(Map.empty)
  check("and the project indexes again",
    snapshot.theories > 0 && snapshot.entries > 0,
    snapshot.theories.toString + " theories, " + snapshot.entries.toString + " entries")


  /* ---------------- 3. find definition ---------------- */

  println("3. Query_Search.definition -- the declaration, as source lines")

  check("the sidebar contract: usages collapsed, a definition expanded",
    !Query_Search.Result_Kind.Usages.expand_groups &&
      Query_Search.Result_Kind.Definition.expand_groups, "")

  /* A deterministic subject: the first declaration, in load order, whose body
     is between five and twenty lines -- long enough to be a body, short enough
     to read in a log. */
  def body_size(e: Entry): Int =
    (if (e.body_end_line != 0) e.body_end_line else e.thy_end) - e.thy_line + 1

  def candidates(tight: Boolean): Iterator[(Theory_Section, Entry)] =
    for {
      sec <- snapshot.sections.iterator
      e <- sec.entries.iterator
      if e.thy_line > 0 && e.name.nonEmpty && body_size(e) >= 5 && body_size(e) <= 20
      /* and unambiguous: the name must resolve back to THIS entry */
      if snapshot.definition(e.name).exists(_._2 eq e)
      /* Preferably one whose body ends where the entry does, so the CLI
         cross-check below compares the WHOLE `show -V` slice rather than a
         prefix of it. */
      if !tight || e.body_end_line == e.thy_end
    } yield (sec, e)

  val subject: Option[(Theory_Section, Entry)] =
    candidates(tight = true).nextOption().orElse(candidates(tight = false).nextOption())

  check("a subject declaration exists", subject.isDefined,
    subject.map(p => p._2.name + " in " + p._1.theory).getOrElse("<none>"))

  for ((sec, e) <- subject) {
    val result = Query_Search.definition(snapshot, e.name)

    check("one group, for the declaring theory",
      result.groups.length == 1 && result.groups.head.theory == sec.theory,
      result.groups.map(_.theory).mkString(", "))
    check("the group caption is the engine's own name line",
      result.groups.head.caption == Render.format_name_line(sec, e),
      result.groups.head.caption)
    check("the caption carries the extent annotation",
      result.groups.head.caption.contains("[src "), "")

    val hits = result.groups.head.hits
    val stop = if (e.body_end_line != 0) e.body_end_line else e.thy_end
    check("the rows are the declaration and its body",
      hits.length == stop - e.thy_line + 1,
      hits.length.toString + " rows for " + e.thy_line.toString + ".." + stop.toString)
    check("every row carries its own line number",
      hits.zipWithIndex.forall { case (h, i) => h.line == e.thy_line + i }, "")
    check("every row is the source line it points at",
      hits.forall(h => sec.lines(h.line - 1) == h.text), "")
    check("no row is a note when nothing was cut", hits.forall(!_.note), "")
    check("the result-set node jumps to the declaration line",
      result.definition.exists(_.line == e.thy_line),
      result.definition.map(_.line.toString).getOrElse("<none>"))

    /* The truncation tail. */
    val cut = Query_Search.definition(snapshot, e.name, limit = 3)
    val cut_hits = cut.groups.head.hits
    check("a capped body shows the cap and one note",
      cut_hits.length == 4 && cut_hits.take(3).forall(!_.note) && cut_hits.last.note,
      cut_hits.length.toString + " rows")
    check("the note says how many were left and where they end",
      cut_hits.last.text.contains((stop - e.thy_line + 1 - 3).toString) &&
        cut_hits.last.text.contains(stop.toString),
      cut_hits.last.text)
    check("the note still navigates",
      cut_hits.last.line == e.thy_line + 3 && cut_hits.last.path.isDefined,
      cut_hits.last.line.toString)

    /* And the renderer's own treatment of a note. */
    check("a note renders without a line number and without highlighting", {
      val html = Query_Dockable.hit_html(e.name, cut_hits.last)
      html.contains("<i>") && !html.contains("<b>")
    }, "")
    check("an ordinary row renders line-numbered", {
      val html = Query_Dockable.hit_html(e.name, hits.head)
      html.startsWith("<html>" + e.thy_line.toString + ": ")
    }, "")

    /* Hand the body to the shell, which diffs it against `isabelle query
       show NAME -V` -- the gate-verified verb for the same question. */
    Files.write(out_dir.resolve("def-body.txt"),
      (hits.map(_.text).mkString("\n") + "\n")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
    println("PROBE-DEF-NAME " + e.name)
    println("PROBE-DEF-LINES " + hits.length.toString)
  }

  /* The bound-name fallback: a name no entry is CALLED, but one binds. */
  val bound =
    (for {
      sec <- snapshot.sections.iterator
      e <- sec.entries.iterator
      (n, kind) <- e.bindings.iterator
      if snapshot.definition(n).isEmpty
    } yield (n, e, kind)).nextOption()

  check("the corpus has a bound name to resolve", bound.isDefined,
    bound.map(_._1).getOrElse("<none>"))
  for ((n, e, kind) <- bound) {
    val found = Query_Search.resolve(snapshot, n)
    check("a bound name resolves to the declaration that binds it",
      found.exists(_.entry eq e), found.map(_.entry.name).getOrElse("<none>"))
    check("and is phrased with the engine's own words",
      found.exists(f => f.how == isabelle.query.Commands.binding_kinds(kind)),
      found.map(_.how).getOrElse(""))
    check("the label says how",
      Query_Search.definition(snapshot, n).label.contains(e.name),
      Query_Search.definition(snapshot, n).label)
  }


  /* ---------------- 4. peek content ---------------- */

  println("4. Query_Peek -- what the popup would show")

  for ((sec, e) <- subject) {
    val by_name = Query_Peek.of_name(snapshot, e.name)
    check("peeking a name shows that declaration",
      by_name.exists(_.caption == Render.format_name_line(sec, e)),
      by_name.map(_.caption).getOrElse("<none>"))
    check("its rows are the declaration's own lines",
      by_name.exists(_.rows.map(_._1) ==
        Query_Search.body_hits(sec, e, Query_Peek.LINES).map(_.line)), "")

    /* A line INSIDE the declaration peeks the declaration. */
    val inside = e.thy_line + 1
    check("peeking a line inside a declaration shows the declaration",
      Query_Peek.of_line(snapshot, sec.path, inside)
        .exists(_.caption == Render.format_name_line(sec, e)), inside.toString)
  }

  /* A line no declaration owns falls back to its neighbourhood. */
  val orphan =
    (for {
      sec <- snapshot.sections.iterator
      line <- (1 to sec.lines.length).iterator
      if isabelle.query.Commands.enclosing_entry(sec, line).isEmpty
      if line > Query_Peek.CONTEXT && line + Query_Peek.CONTEXT <= sec.lines.length
    } yield (sec, line)).nextOption()

  check("the corpus has a line no declaration owns", orphan.isDefined,
    orphan.map(p => p._1.theory + ":" + p._2.toString).getOrElse("<none>"))
  for ((sec, line) <- orphan) {
    val content = Query_Peek.of_line(snapshot, sec.path, line)
    check("an unowned line peeks its own neighbourhood",
      content.exists(c =>
        c.rows.map(_._1) == ((line - Query_Peek.CONTEXT) to (line + Query_Peek.CONTEXT)).toList),
      content.map(_.rows.map(_._1).mkString(",")).getOrElse("<none>"))
    check("and is captioned with the locus",
      content.exists(_.caption ==
        Render.file_locus(snapshot.labels, sec.path) + ":" + line.toString),
      content.map(_.caption).getOrElse(""))
  }

  check("an unknown name peeks nothing",
    Query_Peek.of_name(snapshot, "p6probe_no_such_name_at_all").isEmpty, "")


  /* ---------------- 5. the gesture table ---------------- */

  println("5. Open_Policy -- resolution, and what a typo does")

  val panel = new javax.swing.JPanel
  def mouse(button: Int, modifiers: Int, clicks: Int): MouseEvent =
    new MouseEvent(panel, MouseEvent.MOUSE_PRESSED, 0L, modifiers, 0, 0, clicks, false, button)

  check("a plain click is a single click",
    Open_Policy.gesture_of(mouse(MouseEvent.BUTTON1, 0, 1)) == "single-click", "")
  check("two of them are a double click",
    Open_Policy.gesture_of(mouse(MouseEvent.BUTTON1, 0, 2)) == "double-click", "")
  check("shift wins over the click count",
    Open_Policy.gesture_of(
      mouse(MouseEvent.BUTTON1, InputEvent.SHIFT_DOWN_MASK, 2)) == "shift-click", "")
  check("alt wins over shift",
    Open_Policy.gesture_of(mouse(MouseEvent.BUTTON1,
      InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 1)) == "alt-click", "")
  check("the middle button wins over everything",
    Open_Policy.gesture_of(
      mouse(MouseEvent.BUTTON2, InputEvent.ALT_DOWN_MASK, 2)) == "middle-click", "")
  check("no event at all is the keyboard gesture",
    Open_Policy.gesture_of(null) == "enter", "")

  check("peek is a policy, and the fifth one",
    Open_Policy.of_name("peek").contains(Open_Policy.Peek) &&
      Open_Policy.values.length == 5,
    Open_Policy.names.mkString(", "))

  /* Defaults, resolution, and the red-flag path. */
  Query_Options.forget_warnings()
  for ((gesture, default) <- Open_Policy.gestures) {
    jEdit.unsetProperty(Open_Policy.property(gesture))
    check("no property means the built-in default: " + gesture,
      Open_Policy.of_gesture(gesture) == default, default.name)
  }

  jEdit.setProperty(Open_Policy.property("single-click"), "peek")
  check("a legal property overrides the default",
    Open_Policy.of_gesture("single-click") == Open_Policy.Peek, "")

  jEdit.setProperty(Open_Policy.property("single-click"), "new-pane")
  check("and a different one overrides differently",
    Open_Policy.of_gesture("single-click") == Open_Policy.New_Pane, "")

  Query_Options.forget_warnings()
  jEdit.setProperty(Open_Policy.property("single-click"), "peeek")
  check("a typo does NOT silently become the default -- it is reported",
    Query_Options.warnings.isEmpty && {
      val p = Open_Policy.of_gesture("single-click")
      p == Open_Policy.default_of("single-click") && Query_Options.warnings.nonEmpty
    },
    Query_Options.warnings.headOption.getOrElse("<silent>"))
  check("the report names the property and the value",
    Query_Options.warnings.exists(w =>
      w.contains("peeek") && w.contains(Open_Policy.property("single-click"))),
    Query_Options.warnings.mkString(" | "))
  check("and it is reported once, not once per click", {
    val before = Query_Options.warnings.length
    for (_ <- 1 to 20) Open_Policy.of_gesture("single-click")
    Query_Options.warnings.length == before
  }, Query_Options.warnings.length.toString)
  jEdit.unsetProperty(Open_Policy.property("single-click"))

  Query_Options.forget_warnings()
  jEdit.setProperty("isabelle-project-query.index-limit", "lots")
  check("a non-integer limit is reported and ignored",
    Query_Index.limit == Query_Index.LIMIT_DEFAULT && Query_Options.warnings.nonEmpty,
    Query_Options.warnings.headOption.getOrElse("<silent>"))
  jEdit.unsetProperty("isabelle-project-query.index-limit")
  Query_Options.forget_warnings()

  check("with no prover up there is no Isabelle option to consult",
    Query_Options.isabelle_option("gesture.enter").isEmpty, "")


  /* ---------------- 6. the three tables agree ---------------- */

  println("6. defaults -- compiled in, in plugin.props, in etc/options")

  /* Read the shipped Isabelle options through the real mechanism, not by
     reading our own file: this is also the check that a component's
     `etc/options` is picked up at all. */
  val options = Options.init0()

  for ((gesture, default) <- Open_Policy.gestures) {
    val name = Query_Options.option_name("gesture." + gesture)
    options.get(name) match {
      case Some(entry) =>
        check("etc/options agrees for " + gesture,
          entry.default_value == default.name && entry.typ == Options.String,
          entry.default_value)
      case None => check("etc/options declares " + name, false, "missing")
    }
  }
  options.get(Query_Options.option_name("index-limit")) match {
    case Some(entry) =>
      check("etc/options agrees on the index limit",
        entry.default_value == Query_Index.LIMIT_DEFAULT.toString, entry.default_value)
    case None => check("etc/options declares the index limit", false, "missing")
  }


  /* ---------------- 7. the plugin resources P6 added ---------------- */

  println("7. the plugin jar -- the option pane and the new properties")

  val shim =
    Paths.get(Isabelle_System.getenv("JEDIT_SETTINGS"))
      .resolve("jars").resolve("isabelle_jedit_query.jar")
  check("the plugin shim jar is built", Files.isRegularFile(shim), shim.toString)

  if (Files.isRegularFile(shim)) {
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
    val loader = new java.net.URLClassLoader(Array(shim.toUri.toURL), getClass.getClassLoader)
    def load(name: String): Option[Class[?]] =
      try Some(Class.forName(name, false, loader)) catch { case _: Throwable => None }

    /* Continuation lines JOINED.  A `.properties` value may be written
       `key= \` and continued over several lines, which is how the plugin menu
       is written -- and a `(?m)^key=(.*)$` read of it captured the lone
       backslash, left an EMPTY menu list, and made the "every menu entry names
       a real action" check below pass vacuously.  Found by P6b; the size guard
       under it is what stops it happening again. */
    val props = resource("plugin.props").replaceAll("""\\\r?\n[ \t]*""", "")
    val actions = resource("actions.xml")

    def prop(name: String): Option[String] =
      ("(?m)^" + java.util.regex.Pattern.quote(name) + "=(.*)$").r
        .findFirstMatchIn(props).map(_.group(1).trim)

    /* plugin.props ships the SAME gesture defaults as the code and
       etc/options -- the invariant that lets an unchanged Isabelle option stay
       silent without the two stores disagreeing. */
    for ((gesture, default) <- Open_Policy.gestures)
      check("plugin.props agrees for " + gesture,
        prop(Open_Policy.property(gesture)).contains(default.name),
        prop(Open_Policy.property(gesture)).getOrElse("<missing>"))
    check("plugin.props agrees on the index limit",
      prop("isabelle-project-query.index-limit").contains(Query_Index.LIMIT_DEFAULT.toString),
      prop("isabelle-project-query.index-limit").getOrElse("<missing>"))

    /* The option pane is a BeanShell expression like the dockables, and fails
       the same way -- at plugin-load time and nowhere else. */
    val groups =
      prop("plugin.isabelle.jedit_query_plugin.Plugin.option-group")
        .toList.flatMap(_.split("\\s+")).filter(_.nonEmpty)
    check("an option group is declared", groups.nonEmpty, groups.mkString(", "))
    for (group <- groups) {
      check("the option group has a label: " + group,
        prop("options." + group + ".label").exists(_.nonEmpty),
        prop("options." + group + ".label").getOrElse("<missing>"))
      val code = prop("options." + group + ".code").getOrElse("")
      val cls = """new\s+([\w.$]+)\s*\(\s*\)""".r.findFirstMatchIn(code).map(_.group(1))
      check("the option pane class is loadable: " + cls.getOrElse("<none>"),
        cls.flatMap(load).exists(c =>
          classOf[org.gjt.sp.jedit.OptionPane].isAssignableFrom(c) &&
            c.getConstructors.exists(_.getParameterCount == 0)),
        code)
    }

    /* Shortcuts: shipped for OUR actions only.  A `.shortcut` written for
       another plugin's action name is a property collision that would survive
       an Isabelle upgrade. */
    val shortcuts =
      """(?m)^([\w.-]+)\.shortcut2?=(.*)$""".r.findAllMatchIn(props)
        .map(m => (m.group(1), m.group(2).trim)).toList
    check("shortcuts are shipped", shortcuts.nonEmpty,
      shortcuts.map(s => s._1 + "=" + s._2).mkString(", "))
    check("and only for actions of ours",
      shortcuts.forall(_._1.startsWith("isabelle-project-query.")), "")

    val action_names =
      """<ACTION NAME="([^"]+)"""".r.findAllMatchIn(actions).map(_.group(1)).toList
    check("every shortcut names an action that exists",
      shortcuts.forall(s => action_names.contains(s._1)),
      action_names.mkString(", "))
    check("the P6 actions are all there",
      List("peek-definition", "go-to-symbol", "navigate-backwards", "navigate-forwards")
        .forall(a => action_names.contains("isabelle-project-query." + a)), "")

    /* The menu must not name an action that does not exist -- jEdit logs a
       broken menu entry and carries on, which is how one goes unnoticed. */
    val menu =
      prop("plugin.isabelle.jedit_query_plugin.Plugin.menu").toList
        .flatMap(_.split("\\s+")).map(_.trim)
        .filter(s => s.nonEmpty && s != "-" && s != "\\")
    check("the menu is non-empty, so the check below is not vacuous",
      menu.length >= 8, menu.mkString(", "))
    check("every menu entry is an action or the dockable",
      menu.forall(m => action_names.contains(m) || m == Query_Dockable.NAME),
      menu.mkString(", "))

    check("the property prefix matches the dockable name",
      Query_Options.PREFIX == Query_Dockable.NAME + ".", Query_Options.PREFIX)

    zip.close()
  }


  /* ---------------- 8. quick-open over the real index ---------------- */

  println("8. quick-open -- filtering the project's own name list")

  val names = snapshot.entry_names.distinct
  check("the candidate list is the declaration list",
    names.nonEmpty && names.length <= snapshot.entries, names.length.toString)

  for ((_, e) <- subject) {
    val hits = Query_Fuzzy.filter(e.name, names, 200)
    check("a full name finds itself, first",
      hits.headOption.exists(_.name == e.name),
      hits.headOption.map(_.name).getOrElse("<none>"))
    /* Every declaration in the list resolves back to a jumpable target --
       which is what selecting a row does. */
    check("every offered name resolves to a file and a line",
      hits.take(20).forall(m =>
        snapshot.definition(m.name).exists { case (theory, entry) =>
          snapshot.path_of(theory).isDefined && entry.thy_line > 0
        }), hits.length.toString + " offered")
  }

  val broad = Query_Fuzzy.filter("a", names, 200)
  check("a broad query is capped", broad.length <= 200, broad.length.toString)

  /* At the scale that decides whether this can run per keystroke.  The test
     corpus is one AFP entry; `src/HOL` is 78k declarations, and a filter that
     is fine over a hundred names and hopeless over a hundred thousand is a
     feature that works only where it is not needed.  Synthetic, so the bound
     does not depend on which corpus the probe was pointed at. */
  val many =
    (for (i <- 0 until 100000)
      yield "lemma_" + Integer.toString(i, 36) + "_aux_step").toList
  val t0 = System.currentTimeMillis()
  val big = Query_Fuzzy.filter("las", many, 200)
  val elapsed = System.currentTimeMillis() - t0
  check("a one-word query over 100k names stays interactive",
    elapsed < 1000 && big.length == 200,
    elapsed.toString + " ms, " + big.length.toString + " hits")


  /* ---------------- 9. the navigator, with no prover ---------------- */

  println("9. Query_Navigate -- degrading when PIDE is not up")

  check("no plugin means no navigator", !Query_Navigate.available, "")
  check("and nothing to navigate to",
    !Query_Navigate.can_backward && !Query_Navigate.can_forward, "")
  /* On the EDT, because that is the only thread these may be called from:
     `GUI_Thread.require` is the first line of each, and a probe that called
     them from `main` would be testing the assertion rather than the
     degradation. */
  check("asking anyway does not throw",
    try GUI_Thread.now { Query_Navigate.backward(null); Query_Navigate.forward(null); true }
    catch { case _: Throwable => false }, "")
  check("the tooltips say why",
    Query_Navigate.back_tip.contains("not up") &&
      Query_Navigate.forward_tip.contains("not up"), Query_Navigate.back_tip)
  check("the button captions are the two triangles",
    Query_Navigate.BACK.length == 1 && Query_Navigate.FORWARD.length == 1 &&
      Query_Navigate.BACK != Query_Navigate.FORWARD,
    Query_Navigate.BACK + Query_Navigate.FORWARD)


  println(if (failures == 0) "P6PROBE OK" else "P6PROBE FAILURES " + failures.toString)

  System.out.flush()
  sys.exit(if (failures == 0) 0 else 1)
}
}
