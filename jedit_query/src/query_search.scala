/*  Title:      jedit_query/src/query_search.scala

What the dockable displays: a result set, grouped by theory — and, for the site
verbs, by the DIRECTORY each theory lives in.

ONE node model for every kind of answer.  A "where is this declared" result and
a "who cites this" result are the same three levels — result set, theory, line
— and differ only in how the panel opens them (`Result_Kind.expand_groups`) and
in what a leaf's preview says.  That is deliberate: the navigation affordances
(open in the active pane, open in a new pane, the popup, the keys) are written
once against `Hit`, so every future view inherits them instead of growing its
own.

P6d adds ONE optional level above the theory, and only where it answers a
question: a site listing reports registrations and RETRACTIONS together
(`[code]` here, `[code del]` there), and "which part of the project does that"
is a question about the directory layout.  A `Folder` is therefore built from
the site's own theory PATH — path arithmetic against the project root, no
filesystem walk — and only for a kind that asks for it (`Result_Kind.folders`).
Usages and definition keep exactly the tree they had.

The engine entry points are the CLI-free pair.  `Usage.find_callers` is O(one
name x source) and answers a session in well under a second;
`Usage_Graph.build_call_graph` is a corpus-global build (tens of seconds, and
gigabytes, over a whole AFP) and is worth it only for the transitive verbs.  A
plugin that built the graph on a right-click would feel broken, so this module
does not know it exists.

No rendering happens here — a `Result` is data, and the dockable decides what a
hit looks like.
*/

package isabelle.jedit_query


import isabelle.query.{Commands, Entry, Namespace, Render, Sites, Theory_Section,
  Usage}

import java.nio.file.{Path => JPath}

import scala.collection.mutable


object Query_Search {
  /* What SHAPE a result set of this kind has, and how the panel opens it.

     `expand_groups` is the file level: a declaration is one line and the user
     asked to see it, so it opens expanded; a usage list can be hundreds of
     lines across dozens of theories, so its per-theory nodes open collapsed
     and the tree's own arrows reveal them.

     `folders` is the level ABOVE the file: whether the theories are hung under
     the directories they live in.  It is set for the two site kinds and for
     nothing else, because it is their question — a site list mixes
     registrations with retractions, and where in the tree each one is written
     is the thing a flat per-theory list cannot say.  Directory nodes
     themselves always open (see `query_dockable.scala`): a collapsed one shows
     a name and a number, which is strictly less than the flat list it
     replaced. */
  sealed abstract class Result_Kind(val expand_groups: Boolean,
    val folders: Boolean = false)

  object Result_Kind {
    case object Usages extends Result_Kind(false)
    case object Definition extends Result_Kind(true)
    /* P6b opened these COLLAPSED, on the usages argument: `category` has 37
       sites.  P6d reverses that, and the hierarchy is why.  A site row is
       already the answer — the locus, the name, and the `[code]` / `[code del]`
       role that says whether this site gives or takes away — so a tree that
       hides every one of them behind a directory node has replaced a list of
       answers with a list of directory names.  A site listing is bounded by
       DECLARATIONS, tens of them, where a usage list is bounded by citations;
       and the Collapse button is one click for the rare listing that is not. */
    case object Instantiations extends Result_Kind(true, folders = true)
    case object Code_Equations extends Result_Kind(true, folders = true)
  }

  /* One line of source.  `text` is the RAW line, as the engine returns it:
     file form, `\<alpha>` and all.  Decoding for display is the view's
     business.

     `note` marks a row that is ABOUT the source rather than of it — the
     "[+N more lines]" tail of a truncated declaration body.  It still carries
     a line, so it still navigates; it is only rendered differently.

     `tag` is the syntactic ROLE of a site row -- `sublocale`, `[code del]` --
     which is the column the CLI prints between the locus and the text and the
     one thing a site list says that a usages list does not.  Empty for every
     other kind, so no existing row changes.

     `name` / `sorts` are the site's own name and the sort or signature the
     source writes at it, exactly as the CLI's name column carries them.  BOTH
     are kept, rather than one pre-rendered string, so the Sorts toggle
     re-renders the tree that is already on screen instead of re-running the
     query -- a display choice must not cost a parse. */
  final case class Hit(theory: String, path: Option[JPath], line: Int, text: String,
    note: Boolean = false, tag: String = "", name: String = "", sorts: String = "")

  /* `label`, when set, replaces the bare theory name in the caption: a
     declaration's group says what the ENGINE says about it
     (`Render.format_name_line`), extent and all, rather than repeating the
     theory a third time.

     `theory_label` is the weaker form — the theory name qualified only as far
     as it needs to name ONE theory, which is what every `theory:line` the CLI
     prints now carries [disambig-loci].  It is what the caption falls back to,
     so two files called `Misc` are two visibly different nodes rather than two
     nodes reading the same.  Empty (and therefore invisible) whenever the
     name is already unique, which is every single-session project. */
  final case class Group(theory: String, path: Option[JPath], hits: List[Hit],
    label: String = "", theory_label: String = ""
  ) {
    def count: Int = hits.length
    def caption: String =
      if (label.nonEmpty) label
      else if (theory_label.nonEmpty) theory_label
      else theory
  }


  /* --- the directory level (P6d) --- */

  /* One directory of a site result: the sub-directories under it and the
     theories in it.  `name` is one or more path segments — see `collapse`.

     A `Folder` holds no path of its own and is therefore not a navigation
     target.  That is deliberate: a directory has no line to jump to, and every
     gesture in the panel is defined against a `Hit`.  What it does carry is its
     own arithmetic, so a caption can say how much is under it without walking
     the Swing tree twice. */
  final case class Folder(name: String, folders: List[Folder], groups: List[Group]) {
    /* The leaves below, at any depth: a directory node's count is everything
       it contains, not what happens to sit directly in it. */
    def sites: Int = groups.foldLeft(0)(_ + _.count) + folders.foldLeft(0)(_ + _.sites)
    def files: Int = groups.length + folders.foldLeft(0)(_ + _.files)
    def is_empty: Boolean = folders.isEmpty && groups.isEmpty
  }

  /* Displayed with `/` on every platform, because that is how Isabelle writes
     a theory path and how the rest of this tool's output spells one. */
  val SEPARATOR: String = "/"

  /* Where a theory's file sits, relative to the project root, as path
     segments.  No filesystem is touched: `path` came off the snapshot the
     engine built, and everything below is arithmetic on it, which is what
     makes the whole tree safe to build on the EDT.

     Three answers, and each is a real case:

       * `Nil` — the theory is directly in the root, or the index knows no path
         for it (a section built without one).  It hangs at the top level, so a
         project whose theories are all in its root looks EXACTLY as it did
         before this level existed;
       * the relative segments, for the ordinary case;
       * the absolute directory as ONE segment, for a theory the ROOT reaches
         outside the root directory — `..` chains would be read as structure,
         and there is none to read. */
  def directory_of(root: JPath, path: Option[JPath]): List[String] = {
    val dir = path.flatMap(p => Option(p.toAbsolutePath.normalize.getParent))
    dir match {
      case None => Nil
      case Some(d) =>
        val r = root.toAbsolutePath.normalize
        if (d == r) Nil
        else if (d.startsWith(r)) {
          val rel = r.relativize(d)
          (0 until rel.getNameCount).iterator.map(rel.getName(_).toString).toList
        }
        else List(d.toString)
    }
  }

  /* `a/b/c` with nothing else under `a` is ONE node reading `a/b/c`.

     Without this a shallow project gains a column of arrows that each reveal a
     single arrow, and a deep one buries every answer.  The rule is the file
     browser's own: a directory that holds exactly one directory and no files
     of its own is not a level, it is a prefix.  Children are already collapsed
     when this runs, so one merge suffices; the recursion is what makes that
     true rather than assumed. */
  private def collapse(folder: Folder): Folder =
    folder.folders match {
      case List(only) if folder.groups.isEmpty =>
        collapse(Folder(folder.name + SEPARATOR + only.name, only.folders, only.groups))
      case _ => folder
    }

  private def build(name: String, items: List[(List[String], Group)]): Folder = {
    val here = items.collect { case (Nil, group) => group }
    val below = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[(List[String], Group)]]
    for ((segments, group) <- items; segment <- segments.headOption)
      below.getOrElseUpdate(segment, new mutable.ListBuffer) += ((segments.tail, group))
    Folder(name,
      (for ((segment, rest) <- below) yield collapse(build(segment, rest.toList))).toList,
      here)
  }

  /* The whole directory hierarchy of a result set, as an UNNAMED root whose
     children the panel hangs under the result node.  The root is never
     collapsed into its only child: it is not a node, it is the result set.

     Order is the engine's throughout — section load order, which is the
     build's own — with a directory taking the position of the first theory
     that put it there.  The panel renders directories before the loose files
     of the same level, which is the one place the two lists are re-ordered. */
  def tree(root: JPath, groups: List[Group]): Folder =
    build("", groups.map(group => (directory_of(root, group.path), group)))

  /* `label` is what the result-set root says; `name` is what a preview
     highlights.

     `refused` is set when the QUESTION could not be asked -- the subject of a
     site query is not a locale, class or constant this project declares.  It
     is deliberately not an empty result: the panel must say "not a locale or
     class here", not "no instantiations", for the same reason the CLI exits 1
     rather than printing an honest-looking zero. */
  final case class Result(
    kind: Result_Kind,
    label: String,
    name: String,
    groups: List[Group],
    definition: Option[Hit],
    note: String,
    refused: String = ""
  ) {
    def hits: Int = groups.foldLeft(0)(_ + _.count)
    def theories: Int = groups.length
    def is_empty: Boolean = groups.isEmpty
  }


  /* Group in the order the engine emitted them — section load order, which is
     the build's own order and therefore stable between runs. */
  /* Grouped by the hit's OWN section, not by a name-keyed lookup: a theory
     name is unique in a session and not in a corpus, so two files called
     `Misc` would otherwise share one group and one of them would be opened at
     the other's path [name-is-not-identity].  `find_callers` hands over the
     section for exactly this reason. */
  private def group(
    snapshot: Query_Index.Snapshot,
    triples: List[(Theory_Section, Int, String)]
  ): List[Group] = {
    val buf = mutable.LinkedHashMap.empty[JPath, (Theory_Section, mutable.ListBuffer[Hit])]
    for ((sec, line, text) <- triples) {
      val hits = buf.getOrElseUpdate(sec.path, (sec, new mutable.ListBuffer[Hit]))._2
      hits += Hit(sec.theory, Some(sec.path), line, text)
    }
    (for ((_, (sec, hits)) <- buf)
      yield Group(sec.theory, Some(sec.path), hits.toList,
        theory_label = snapshot.label_of(sec.path))).toList
  }

  private def source_line(snapshot: Query_Index.Snapshot, theory: String, line: Int,
    fallback: String
  ): String =
    snapshot.section(theory) match {
      case Some(sec) if line >= 1 && line <= sec.lines.length => sec.lines(line - 1)
      case _ => fallback
    }

  /* --- resolving a name to the declaration that owns it --- */

  /* `entry_by_name` is a direct map read.  The fallback is the ENGINE's own
     rule, not a new one: `Commands.cmd_show` resolves a name that some
     declaration BINDS — a `shows` conjunct, an introduction rule, a datatype
     constructor, a `.simps` — to the declaration binding it, and phrases how
     through `Commands.binding_kinds`.  Refusing that here would make "Show
     declaration of foo.simps" answer nothing for a name Isabelle genuinely
     minted.  The scan is linear over already-parsed entries and only runs when
     the map misses. */
  final case class Found(theory: String, entry: Entry, how: String)

  def resolve(snapshot: Query_Index.Snapshot, name: String): Option[Found] =
    snapshot.definition(name).map { case (theory, entry) => Found(theory, entry, "") }
      .orElse(
        snapshot.sections.iterator.flatMap(sec =>
          sec.entries.iterator.flatMap(e =>
            e.bindings.iterator.collect {
              case (n, kind) if n == name =>
                Found(sec.theory, e, Commands.binding_kinds.getOrElse(kind, "bound by"))
            })).nextOption())

  /* The declaration site, when the project declares the name at all.  A
     citation of something the project only IMPORTS (`mono`, `refl`) resolves
     to nothing, and that is a legitimate answer — the usages are still real. */
  def definition_hit(snapshot: Query_Index.Snapshot, name: String): Option[Hit] =
    resolve(snapshot, name).map { found =>
      Hit(found.theory, snapshot.path_of(found.theory), found.entry.thy_line,
        source_line(snapshot, found.theory, found.entry.thy_line, found.entry.text))
    }

  private def kind_of(snapshot: Query_Index.Snapshot, name: String): String =
    resolve(snapshot, name) match {
      case Some(found) => " (" + found.entry.tag + " in " + found.theory + ")"
      case None => ""
    }


  /* --- the declaration, as lines --- */

  /* How many source lines of a declaration the panel shows before it says how
     many it left.  A `definition` is three lines and a big induction is three
     hundred, and the second must not fill the tree; the tail row still
     navigates, so nothing is unreachable. */
  val BODY_LIMIT: Int = 40

  /* `thy_line .. body_end` — the declaration and its proof, which is what
     "show me the definition" means.  Deliberately NOT `src_start`: a leading
     `text \<open>…\<close>` block is documentation and can be longer than the
     lemma, and the group caption already names the full `[src A..B]` extent
     the engine computed.

     Every row is a REAL source line with its own number, rather than the
     engine's rendered `show` output.  `Render.render_entry` interleaves
     synthetic lines (a header, `[+N more proof lines]`) that have no line to
     jump to, and a tree whose rows navigate cannot afford rows that do not. */
  def body_hits(sec: Theory_Section, e: Entry, limit: Int = BODY_LIMIT): List[Hit] = {
    val path = Some(sec.path)
    if (e.thy_line <= 0) List(Hit(sec.theory, path, 1, e.text))
    else {
      val start = e.thy_line
      val stop = {
        val body_end = if (e.body_end_line != 0) e.body_end_line else e.thy_end
        (if (body_end >= start) body_end else start) min sec.lines.length
      }
      val shown = if (limit > 0) (start + limit - 1) min stop else stop
      val rows =
        (for ((text, i) <- sec.slice(start, shown).zipWithIndex)
          yield Hit(sec.theory, path, start + i, text)).toList
      val rest = stop - shown
      if (rest <= 0) rows
      else
        rows :+ Hit(sec.theory, path, shown + 1,
          "[+" + rest.toString + " more line" + (if (rest == 1) "" else "s") +
            ", to " + stop.toString + "]", note = true)
    }
  }

  /* The only engine call in the plugin that reads a table: whether a line
     that says `auto` is a citation or a method invocation depends on THIS
     project's, so the caller resolves one with `Query_Index.with_table` and
     passes it in. */
  def usages(
    snapshot: Query_Index.Snapshot,
    name: String,
    external: Boolean = false,
    note: String = "",
    namespace: Namespace.Table = Namespace.census
  ): Result =
    Result(
      kind = Result_Kind.Usages,
      label = "usages of " + name + kind_of(snapshot, name) +
        (if (external) " [external]" else ""),
      name = name,
      groups =
        group(snapshot,
          Usage.find_callers(snapshot.sections, name, external, namespace = namespace)),
      definition = definition_hit(snapshot, name),
      note = note)

  /* Where a name is declared, and what it says there.  Same three levels and
     the same navigation as a usages set — only the leaves differ: the
     declaration's own source lines instead of the lines that cite it, which is
     why the kind opens EXPANDED.  The group caption is the engine's
     `format_name_line`, extent annotation and all, so the panel and
     `isabelle query find --names` describe a declaration identically. */
  def definition(
    snapshot: Query_Index.Snapshot,
    name: String,
    note: String = "",
    limit: Int = BODY_LIMIT
  ): Result = {
    val found = resolve(snapshot, name)
    val groups =
      for {
        f <- found.toList
        sec <- snapshot.section(f.theory).toList
      } yield Group(f.theory, Some(sec.path), body_hits(sec, f.entry, limit),
        label = Render.format_name_line(sec, f.entry))

    val how =
      found.map(_.how).filter(_.nonEmpty) match {
        case Some(how) => " " + Render.EM_DASH + " " + how + " " + found.get.entry.name
        case None => ""
      }

    Result(
      kind = Result_Kind.Definition,
      label = "definition of " + name + kind_of(snapshot, name) + how,
      name = name,
      groups = groups,
      /* The result-set node itself jumps to the declaration line, not to the
         first row of the body, which for a bound name is the same thing. */
      definition = definition_hit(snapshot, name),
      note = note)
  }


  /* --- the site families (P6b) --- */

  /* Both site verbs are one shape, so they are one producer: resolve the
     subject through the ENGINE's predicate (`Sites.resolve` -- the same
     function the context menu asks before offering the item, so the menu and
     the panel can never disagree about what a locale is), then group the sites
     by theory exactly as a usages set is grouped.  The navigation, the
     gestures and the peek come with `Hit` and are not written again. */
  private def sites(
    snapshot: Query_Index.Snapshot,
    kind: Result_Kind,
    name: String,
    noun: String,
    what: String,
    tags: Set[String],
    scan: (List[Theory_Section], String) => List[Sites.Site],
    note: String
  ): Result =
    Sites.resolve(snapshot.sections, name, tags, what) match {
      case Left(refused) =>
        Result(kind, noun + " of " + name, name, Nil, definition_hit(snapshot, name),
          note, refused = refused)
      case Right(subject) =>
        /* Keyed by the site's OWN path, exactly as `group` above keys a usages
           set, and for the same reason: `Sites.Site` carries the section's
           path since [p10-sites-locus], and resolving the FILE by theory NAME
           through `path_of` opened whichever file the name last mapped to --
           so on a corpus with two `Examples` one group's rows navigated into
           the other's source, and the two collapsed into one node besides.
           The directory level (P6d) is unaffected: `directory_of` was already
           path arithmetic, and it now gets a path that is right. */
        val buf = mutable.LinkedHashMap.empty[JPath, (String, mutable.ListBuffer[Hit])]
        for (site <- scan(snapshot.sections, name)) {
          val hits = buf.getOrElseUpdate(site.path, (site.theory, new mutable.ListBuffer[Hit]))._2
          hits += Hit(site.theory, Some(site.path), site.line, site.text,
            tag = site.kind, name = site.name, sorts = site.sorts)
        }
        val groups =
          (for ((path, (theory, hits)) <- buf)
            yield Group(theory, Some(path), hits.toList,
              theory_label = snapshot.label_of(path))).toList
        val how = if (subject.how.isEmpty) "" else " " + Render.EM_DASH + " " + subject.how
        Result(kind, noun + " of " + name + kind_of(snapshot, name) + how, name, groups,
          definition_hit(snapshot, name), note)
    }

  def instantiations(snapshot: Query_Index.Snapshot, name: String,
    note: String = ""
  ): Result =
    sites(snapshot, Result_Kind.Instantiations, name, "instantiations",
      "a locale or class", Sites.locale_tags, Sites.find_instantiations, note)

  def code_equations(snapshot: Query_Index.Snapshot, name: String,
    note: String = ""
  ): Result =
    sites(snapshot, Result_Kind.Code_Equations, name, "code equations",
      "a constant", Sites.constant_tags, Sites.find_code_equations, note)


  /* --- what the menu may offer --- */

  /* Whether a caret word is a legitimate subject for a site verb.  The index
     knows entry kinds, so the menu can ask before it offers -- and asks the
     ENGINE's predicate, so an item that appears always leads to an answer. */
  def is_subject(snapshot: Query_Index.Snapshot, name: String,
    tags: Set[String]
  ): Boolean =
    Sites.resolve(snapshot.sections, name, tags, "").isRight
}
