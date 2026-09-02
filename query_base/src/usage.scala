/*  Title:      query_base/src/usage.scala

The usage family: `callers`, `callees`, `deps`, `uses`, `refs`, `graph`,
`unused`, `methods`.

Above `commands` and `graph`, below the CLI.  Everything here asks the same
question in different directions — who uses what — and each verb takes an
already-loaded index and prints; none of them loads the index or reads argv.

Two graphs, deliberately kept apart because the difference is the point:

  * the CITATION graph (`Usage_Graph.build_call_graph`) is entry-level — which
    lemma's proof body names which fact.  `callers` / `callees` read one node
    of it, `refs` rolls it up per theory, `unused` asks which nodes have no
    in-edge, `graph citation` serialises the whole thing.
  * the IMPORT graph is theory-level — which theory's header names which
    theory.  `deps` / `uses` read one node of it, `graph imports` serialises
    it.  It is a statement of intent; comparing it against the citation graph
    is what `refs` exists for.
*/

package isabelle.query


import isabelle.*

import java.util.regex.Pattern

import scala.collection.mutable


/* Just enough JSON to reproduce `json.dumps(…, indent=2, sort_keys=True,
   ensure_ascii=False)` byte for byte: two-space indent, `": "` between key and
   value, keys sorted, and only `"`, `\` and the C0 controls escaped — a
   non-ASCII character is written raw, which for a corpus full of `\<^sub>`
   spellings is the difference between readable output and a wall of `\u`. */
object Json {
  sealed abstract class T
  final case class Str(s: String) extends T
  final case class Num(n: Long) extends T
  final case class Bool(b: Boolean) extends T
  final case class Arr(items: List[T]) extends T
  final case class Obj(fields: List[(String, T)]) extends T

  def quote(s: String): String = {
    val buf = new StringBuilder
    buf += '"'
    for (c <- s) {
      c match {
        case '"' => buf ++= "\\\""
        case '\\' => buf ++= "\\\\"
        case '\b' => buf ++= "\\b"
        case '\f' => buf ++= "\\f"
        case '\n' => buf ++= "\\n"
        case '\r' => buf ++= "\\r"
        case '\t' => buf ++= "\\t"
        case _ =>
          if (c < 0x20) buf ++= "\\u%04x".format(c.toInt) else buf += c
      }
    }
    buf += '"'
    buf.toString
  }

  def render(t: T, indent: Int = 0): String = {
    val pad = "  " * indent
    val pad1 = "  " * (indent + 1)
    t match {
      case Str(s) => quote(s)
      case Num(n) => n.toString
      case Bool(b) => if (b) "true" else "false"
      case Arr(Nil) => "[]"
      case Arr(items) =>
        items.map(i => pad1 + render(i, indent + 1)).mkString("[\n", ",\n", "\n" + pad + "]")
      case Obj(Nil) => "{}"
      case Obj(fields) =>
        fields.sortBy(_._1).map(kv => pad1 + quote(kv._1) + ": " + render(kv._2, indent + 1))
          .mkString("{\n", ",\n", "\n" + pad + "}")
    }
  }
}


object Usage {
  private def pad_right(s: String, w: Int): String =
    if (s.length >= w) s else s + (" " * (w - s.length))

  private def pad_left(s: String, w: Int): String =
    if (s.length >= w) s else (" " * (w - s.length)) + s

  private val EM_DASH = Render.EM_DASH


  /* ------------------------------------------------------------------ */
  /* the import graph: deps / uses                                      */
  /* ------------------------------------------------------------------ */

  /* A raw `imports`-clause token mapped to the bare in-project theory it
     denotes, or `None` if it is external.  A genuinely external import
     (`HOL-Library.FuncSet`) names no in-project theory by either spelling, so
     it stays `None` and the caller keeps the RAW token for the
     `[out-of-project]` line.

     The rule itself lives in `Reach`, which needs it before this file exists;
     this is the section-map spelling of the same membership test, kept because
     `deps` wants the section it resolved to.  `by_leaf` is
     `Reach.leaf_index` of the same map — pass it, because deriving it per call
     inside a BFS is a pass over every theory name [import-leaf]. */
  def resolve_import(imp: String, by_theory: Map[String, Theory_Section],
    by_leaf: Map[String, List[String]]
  ): Option[String] = Reach.resolve_import(imp, by_theory.contains, by_leaf)

  /* `{theory: depth}` over the in-project imports graph from `start`; depth 0
     is a DIRECT import, and `start` itself is excluded.  Every import that does
     not resolve to a loaded theory is collected into `out_of_project` rather
     than walked — a side effect of the lazy neighbour callback, which is why
     one BFS can serve both this and the stored reverse adjacency. */
  def import_depths(start: String, by_theory: Map[String, Theory_Section],
    out_of_project: Option[mutable.Set[String]] = None
  ): Map[String, Int] = {
    /* SINGLE-VALUED, like the `deps` output it feeds: where a theory name is
       declared twice this walks the last-wins section, and where a leaf is
       ambiguous it takes one candidate.  The visibility FILTER cannot afford
       either narrowing and does not make them — see `Reach.build`. */
    val by_leaf = Reach.leaf_index(by_theory.keys)
    def imports_of(name: String): List[String] =
      by_theory.get(name) match {
        case None => Nil
        case Some(sec) =>
          val children = new mutable.ListBuffer[String]
          for (imp <- Discovery.thy_imports(sec.path))
            resolve_import(imp, by_theory, by_leaf) match {
              case None => out_of_project.foreach(_ += imp)
              case Some(child) => children += child
            }
          children.toList
      }
    Usage_Graph.bfs_depths(imports_of, List(start), seed_depth = -1) - start
  }

  def cmd_deps(out: Out, err: Out, sections: List[Theory_Section], theory: String,
    reverse: Boolean = false, recursive: Boolean = false
  ): Unit = {
    Commands.resolve_theory(sections, theory) match {
      case None => Commands.fail_subject(out, err, s"no theory '$theory'")
      case Some(target) =>
        val by_theory = Usage_Graph.sections_by_theory(sections)
        val by_leaf = Reach.leaf_index(by_theory.keys)

        def emit(found: Map[String, Int]): Unit =
          for ((name, depth) <- found.toList.sortBy(kv => (kv._2, kv._1))) {
            val sec = by_theory(name)
            val tag = if (depth == 0) "  [direct]" else s"  [depth $depth]"
            out.println(s"  $name  (${sec.thy_lines} src lines, " +
              s"${sec.entries.length} entries)$tag")
          }

        val scope = if (recursive) "transitively" else "directly"

        if (reverse) {
          /* Invert the in-project adjacency: child -> the theories that import
             it.  The reverse direction needs the whole graph regardless of
             depth, so the full scan is unavoidable. */
          val rev = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[String]]
          for (s <- sections) rev(s.theory) = new mutable.ListBuffer[String]
          for (s <- sections; imp <- Discovery.thy_imports(s.path))
            resolve_import(imp, by_theory, by_leaf).foreach(r => rev(r) += s.theory)
          val found =
            if (recursive)
              Usage_Graph.bfs_depths(n => rev.get(n).map(_.toList).getOrElse(Nil),
                List(target.theory), seed_depth = -1) - target.theory
            else rev.get(target.theory).map(_.map(_ -> 0).toMap).getOrElse(Map.empty)
          if (found.isEmpty)
            out.println(s"No in-project theory imports ${target.theory} ($scope).")
          else {
            out.println(s"Theories that import ${target.theory} ($scope):")
            emit(found)
          }
        }
        else {
          /* Forward.  Direct: the target's own import line.  Recursive: a lazy
             BFS over the imports graph.  An out-of-project import is a direct
             edge either way, so it shows in both modes. */
          val out_of_project = mutable.LinkedHashSet.empty[String]
          val in_project = mutable.LinkedHashMap.empty[String, Int]
          if (recursive)
            in_project ++= import_depths(target.theory, by_theory, Some(out_of_project))
          else
            for (imp <- Discovery.thy_imports(target.path))
              resolve_import(imp, by_theory, by_leaf) match {
                case None => out_of_project += imp
                case Some(r) => if (r != target.theory) in_project(r) = 0
              }

          if (in_project.isEmpty && out_of_project.isEmpty)
            out.println(s"${target.theory} has no upstream dependencies.")
          else {
            val header =
              if (recursive) "Import-transitive dependencies" else "Direct imports"
            out.println(s"$header of ${target.theory}:")
            emit(in_project.toMap)
            for (name <- out_of_project.toList.sorted)
              out.println(s"  $name  [out-of-project]")
          }
        }
    }
  }


  /* ------------------------------------------------------------------ */
  /* refs                                                               */
  /* ------------------------------------------------------------------ */

  /* What a theory REFERENCES, rolled up from the citation graph: the
     complement of `theory --names`, which lists what a theory EXPORTS.

     Finer-grained than `deps` / `uses`, and the difference is the point.  Those
     work at the `imports`-clause level — theory A declares that it imports B,
     a statement of intent.  This works at the citation level: which entries A's
     proofs actually invoke.  Comparing the two surfaces an import that is
     declared but never used, and the converse — a theory whose facts are cited
     without being imported directly.

     Ownership is resolved through the CITING theory's own import closure, not
     globally: a name may be declared in several theories and the citing theory
     can only see some of them.  A local declaration wins, then the nearest by
     import depth, and only failing both does an arbitrary declaration get the
     credit.  AODV declares each of its theories again under `variants/`, so
     crediting the first in load order reported `Aodv_Loop_Freedom` as citing
     nothing from either of its two direct imports — the precise opposite of the
     truth, in the output whose whole purpose is that comparison.

     One approximation remains, inherited from the name-level graph: counts are
     CITING ENTRIES, not citation sites. */
  def cmd_refs(out: Out, err: Out, sections: List[Theory_Section], theory: String,
    flags: Flags
  ): Unit = {
    Commands.resolve_theory(sections, theory) match {
      case None => Commands.fail_subject(out, err, s"no theory '$theory'")
      case Some(target) =>
        val g = Usage_Graph.build_call_graph(sections, flags.drop_names_upto)
        val by_theory = Usage_Graph.sections_by_theory(sections)
        val own = target.theory
        val closure = import_depths(own, by_theory)

        /* Every theory declaring each name, not just the first — the whole
           point is to choose among them per citing theory. */
        val declared_in = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[String]]
        for (sec <- sections; e <- sec.entries)
          declared_in.getOrElseUpdate(e.name, new mutable.ListBuffer) += sec.theory

        def owner_of(name: String): String =
          declared_in.get(name) match {
            case None => own
            case Some(cands) =>
              if (cands.contains(own)) own      // a local declaration shadows an import
              else {
                val visible = cands.toList.filter(closure.contains).map(c => (closure(c), c))
                if (visible.nonEmpty) visible.min._2 else cands.head
              }
          }

        val tally = mutable.LinkedHashMap.empty[String, Int]
        for (e <- target.entries; callee <- g.callees.getOrElse(e.name, Set.empty))
          tally(callee) = tally.getOrElse(callee, 0) + 1

        val groups = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[(String, Int)]]
        for ((name, n) <- tally) {
          val owner = owner_of(name)
          if (!(flags.external && owner == own))
            groups.getOrElseUpdate(owner, new mutable.ListBuffer) += ((name, n))
        }
        val sorted_groups =
          groups.map(kv => kv._1 -> kv._2.toList.sortBy(p => (-p._2, p._1))).toMap

        val total = sorted_groups.values.map(_.length).sum
        if (flags.mode == "count") out.println(total.toString)
        else if (flags.mode == "names")
          /* Bare names, one per line, so the output pipes straight back into
             another query call.  Unique already: the tally is keyed by name. */
          for (name <- sorted_groups.values.flatMap(_.map(_._1)).toList.sorted)
            out.println(name)
        else if (total == 0) {
          val scope = if (flags.external) "cross-theory " else ""
          out.println(s"$own makes no ${scope}references.")
        }
        else {
          /* The import clause, for the declared-vs-used comparison.
             Out-of-project imports are not in the closure: their entries are
             not indexed, so no citation of them could ever appear here and
             calling them unreferenced would be an artefact of what query loads,
             not a fact about the theory. */
          val declared = closure.filter(_._2 == 0).keySet
          val cited = sorted_groups.keySet - own

          out.println(s"$own references $total name(s) " +
            s"from ${sorted_groups.size} theory/theories:\n")
          val order = groups.keys.toList.sortBy(t =>
            (if (t == own) 1 else 0, -sorted_groups(t).length, t))
          val width = order.map(_.length).max
          val notes = order.map { t =>
            t -> (
              if (t == own) "[self]"
              else if (closure.get(t).contains(0)) "[direct import]"
              else closure.get(t) match {
                case Some(d) => s"[import depth $d]"
                case None => "[not imported]"
              })
          }.toMap
          val note_w = notes.values.map(_.length).max
          for (owner <- order) {
            out.println(s"  ${pad_right(owner, width)}  ${pad_right(notes(owner), note_w)}  " +
              sorted_groups(owner).length)
            for ((name, n) <- sorted_groups(owner)) out.println(s"      $name  ($n)")
            out.println()
          }

          val unused = (declared -- cited).toList.sorted
          if (unused.nonEmpty)
            out.println(s"  Direct imports no citation reaches (${unused.length}): " +
              unused.mkString(", "))
          val indirect = (cited -- declared).toList.sorted
          if (indirect.nonEmpty)
            out.println(s"  Cited but not directly imported (${indirect.length}): " +
              indirect.mkString(", "))
        }
    }
  }


  /* ------------------------------------------------------------------ */
  /* callers / callees                                                  */
  /* ------------------------------------------------------------------ */

  /* Proof-body usages of one name across every theory, filtering out: the
     definition site itself; prose (`text` blocks, headings, preambles, comment
     regions); an antiquotation-only mention (`@{thm name}`), where the name is
     documentation rather than a call; and — when the name is also a proof
     method or attribute — a line that merely INVOKES it, which is the method
     doing its job and not a reference to the entry that shares its name.

     `external` additionally skips every FILE that defines the name, for the
     "is anything OUTSIDE Foo using Foo's primitives?" audit — the file, not
     every file that happens to share its theory name [name-is-not-identity].

     And a theory that cannot SEE any declaration of the name is skipped
     outright (`Reach`): over a corpus that declares one word many times, a
     match in a tree that imports none of them is a different `rev`.

     A hit carries its OWN section, not just the theory name.  Everything
     downstream wants the file — the owner column, the context lines, the
     locus label — and re-deriving it from the name through
     `sections_by_theory` is the fourth instance of the collapse the three
     indexes above just moved off [name-is-not-identity]. */
  def find_callers(sections: List[Theory_Section], name: String,
    external: Boolean = false
  ): List[(Theory_Section, Int, String)] = {
    val word_re = Py.compile(Commands.isa_word_pattern(name))
    val antiq_re =
      Py.compile("""@\{(?:text|thm|term|const)\s+["']?""" + Py.re_escape(name) + """["']?\}""")

    val all_def_sites = Usage_Graph.build_def_sites(sections, Some(Set(name)))
    val def_paths = all_def_sites.filter(_._2.nonEmpty).keySet
    val text_ranges = Usage_Graph.noise_ranges(sections)
    /* Read late: the namespace table is bound by the CLI after start-up. */
    val shadowed = Namespace.non_citation(name)
    val reachable = Reach.site_filter(sections, name)

    val results = new mutable.ListBuffer[(Theory_Section, Int, String)]
    for (sec <- sections if !(external && def_paths(sec.path)) && reachable(sec.theory)) {
      /* Decide on the redacted view, report the raw one: a mention inside a
         comment / `\<^cancel>` / inline ML body is not a use even when live
         proof text shares its line, but the hit we print is the user's line. */
      val lines = sec.live_source
      val raw = sec.source
      val t_mask = Entries.line_mask(lines.length, text_ranges.getOrElse(sec.path, Nil))
      val d_ranges =
        all_def_sites.getOrElse(sec.path, Map.empty).getOrElse(name, Nil)
      var line_no = 1
      while (line_no <= lines.length) {
        val line = lines(line_no - 1)
        if (Py.found(word_re, line) &&
          !d_ranges.exists(r => r._1 <= line_no && line_no <= r._2) &&
          !t_mask(line_no)) {
          val stripped = antiq_re.matcher(line).replaceAll("")
          if (Py.found(word_re, stripped) &&
            !(shadowed && Usage_Graph.shadowed_uses_on_line(line, Set(name)).isEmpty))
            results += ((sec, line_no, Py.rstrip(raw(line_no - 1))))
        }
        line_no += 1
      }
    }
    results.toList
  }

  /* Shared rendering for `callers -r` and `callees -r`. */
  private def render_graph_results(out: Out, sections: List[Theory_Section],
    reachable: Map[String, Int], label: String, seed: String, flags: Flags
  ): Unit = {
    if (flags.mode == "count") out.println(reachable.size.toString)
    else if (reachable.isEmpty) out.println(s"No ${label}s found for '$seed'.")
    else {
      val by_name = Usage_Graph.entry_by_name(sections)
      if (flags.mode == "names")
        for (name <- reachable.keys.toList.sorted)
          by_name.get(name) match {
            case Some((thy, e)) => out.println(s"  $name (${e.tag}) $EM_DASH $thy")
            case None => out.println(s"  $name")
          }
      else {
        out.println(s"${reachable.size} transitive $label(s) of $seed:\n")
        for ((name, depth) <- reachable.toList.sortBy(kv => (kv._2, kv._1))) {
          val indent = "  " * (depth + 1)
          by_name.get(name) match {
            case Some((thy, e)) =>
              out.println(s"$indent$name (${e.tag}) $EM_DASH $thy [L${e.thy_line}]")
            case None => out.println(s"$indent$name")
          }
        }
      }
    }
  }

  def cmd_callers(out: Out, err: Out, sections: List[Theory_Section], name0: String,
    flags: Flags
  ): Unit = {
    var name = name0
    if (flags.recursive) {
      val g = Usage_Graph.build_call_graph(sections, flags.drop_names_upto)
      if (!g.all_names(name)) {
        Commands.resolve_binding(sections, name) match {
          case Some((parent, how)) =>
            out.println(s"# '$name' is $how $parent; recursive caller closure " +
              s"operates at the $parent (entry) level.")
            name = parent
          case None =>
            /* Only the RECURSIVE branch: the closure walks the entry index and
               a name that is not in it has no closure to walk.  The plain scan
               below asks a different question — how many times does this token
               appear — and zero is a truthful answer to it whether or not the
               name is declared [unresolved-subject]. */
            Commands.fail_subject(out, err, s"'$name' is not in the entry index")
        }
      }
      val reachable =
        Usage_Graph.bfs_depths(n => g.callers.getOrElse(n, Set.empty), List(name)) - name
      render_graph_results(out, sections, reachable, "caller", name, flags)
    }
    else {
      val hits = find_callers(sections, name, external = flags.external)
      if (flags.mode == "count") out.println(hits.length.toString)
      else if (hits.isEmpty) out.println(s"No callers found for '$name'.")
      else {
        val n_after = 0 max flags.context
        /* Align the loci into a column; each is a clean `theory:line` that
           pastes into `enclosing` / `lines` / an editor.  The owner and the
           context lines come from the hit's OWN section, never from a
           name-keyed lookup [name-is-not-identity]. */
        val loc_w = hits.map(h => s"${h._1.theory}:${h._2}".length).max
        out.println(s"${hits.length} caller(s) of $name:\n")
        for ((sec, line_no, text) <- hits) {
          val theory = sec.theory
          val encl = Commands.enclosing_entry(sec, line_no)
          out.println(s"  ${pad_right(s"$theory:$line_no", loc_w)}  " +
            s"${Commands.owner_field(encl)}  ${Py.strip(text)}")
          if (n_after > 0) {
            val src = sec.source
            /* The context lines keep ripgrep's `-` marker: it flags the line as
               context rather than a match, and `parse_locus` strips it so the
               locus still round-trips. */
            var off = 1
            while (off <= n_after && line_no - 1 + off < src.length) {
              out.println(s"  $theory:${line_no + off}-  ${Py.rstrip(src(line_no - 1 + off))}")
              off += 1
            }
          }
        }
      }
    }
  }

  def cmd_callees(out: Out, err: Out, sections: List[Theory_Section], name0: String,
    flags: Flags
  ): Unit = {
    var name = name0
    val g = Usage_Graph.build_call_graph(sections, flags.drop_names_upto)
    if (!g.all_names(name)) {
      Commands.resolve_binding(sections, name) match {
        case Some((parent, how)) =>
          out.println(s"# '$name' is $how $parent; reporting $parent's callees " +
            "(shared proof body).")
          name = parent
        case None =>
          Commands.fail_subject(out, err, s"'$name' is not in the entry index")
      }
    }
    if (flags.recursive) {
      val reachable =
        Usage_Graph.bfs_depths(n => g.callees.getOrElse(n, Set.empty), List(name)) - name
      render_graph_results(out, sections, reachable, "dependency", name, flags)
    }
    else {
      val by_name = Usage_Graph.entry_by_name(sections)
      val used0 = g.callees.getOrElse(name, Set.empty)
      val used =
        if (flags.external) {
          /* Mirror of `callers --external`: drop callees defined in NAME's own
             theory, leaving only its cross-theory dependencies. */
          val own_theory = by_name.get(name).map(_._1)
          used0.filter(u => by_name.get(u).map(_._1) != own_theory)
        }
        else used0
      if (flags.mode == "count") out.println(used.size.toString)
      else if (used.isEmpty) {
        val scope = if (flags.external) "cross-theory " else ""
        out.println(s"No ${scope}references found in $name's body.")
      }
      else {
        out.println(s"${used.size} callee(s) of $name:\n")
        for (uname <- used.toList.sorted)
          by_name.get(uname) match {
            case Some((thy, e)) =>
              out.println(s"  $uname (${e.tag}) $EM_DASH $thy [L${e.thy_line}]")
            case None => out.println(s"  $uname")
          }
      }
    }
  }


  /* ------------------------------------------------------------------ */
  /* methods                                                            */
  /* ------------------------------------------------------------------ */

  /* Proof-method usage, the complement of the citation graph.

       methods        ranked tally of every proof method used, with occurrence
                      counts and corpus share.
       methods NAME   every live use of NAME with its location and owning
                      entry — the method analogue of `callers`. */
  def cmd_methods(out: Out, err: Out, sections: List[Theory_Section],
    name: Option[String], flags: Flags
  ): Unit = {
    val (counts, located) = Usage_Graph.scan_methods(sections, name)
    val count_map = counts.toMap

    name match {
      case None =>
        if (flags.mode == "count") out.println(counts.length.toString)  // distinct methods
        else if (counts.isEmpty) out.println("No proof-method uses found.")
        else {
          val ranked = counts.sortBy(-_._2)      // stable: ties keep first-seen order
          if (flags.mode == "names") for ((meth, _) <- ranked) out.println(meth)
          else {
            val total = counts.map(_._2).sum
            val shown = if (flags.mode == "all") ranked else ranked.take(30)
            val suffix = if (flags.mode == "all") "" else s" (top ${shown.length})"
            out.println(s"${counts.length} proof methods used across $total " +
              s"by/apply/proof introducers$suffix:\n")
            val name_w = shown.map(_._1.length).max
            for ((meth, c) <- shown)
              out.println(s"  ${pad_right(meth, name_w)}  ${pad_left(c.toString, 8)}  " +
                s"${pad_left(Py.format_fixed(100.0 * c / total, 1), 5)}%")
            if (flags.mode != "all" && ranked.length > shown.length)
              out.println(s"\n  ... ${ranked.length - shown.length} more methods " +
                "(use -a for all, or `methods NAME` for uses)")
          }
        }

      case Some(m) =>
        /* Two authorities, and both are needed.  `counts` says the project uses
           NAME in introducer position, which includes tactics no fixed table can
           carry — an entry's own Eisbach / ML method.  Gating on the table alone
           refused to locate exactly the methods the tally had just reported.  The
           table is still consulted, for the opposite case: a genuine method this
           project happens not to use should answer "no uses", not "not a method".
           Failing both is the only real error, and it must stay one — a mistyped
           name would otherwise get an empty success for a question never asked. */
        if (!count_map.contains(m) && !Namespace.proof_methods(m))
          Commands.fail_subject(out, err,
            s"'$m' is not used as a proof method here, and is not in the " +
              "resolved proof-method namespace.  Try `methods` for the list of " +
              "methods actually used.")
        else if (flags.mode == "count") out.println(located.length.toString)
        else if (located.isEmpty) out.println(s"No uses of method '$m' found.")
        else {
          val loc_w = located.map(u => s"${u.theory}:${u.line_no}".length).max
          if (flags.mode == "names")
            for (u <- located)
              out.println(s"  ${pad_right(s"${u.theory}:${u.line_no}", loc_w)}  " +
                Commands.owner_field(u.owner))
          else {
            out.println(s"${located.length} use(s) of method '$m':\n")
            for (u <- located)
              out.println(s"  ${pad_right(s"${u.theory}:${u.line_no}", loc_w)}  " +
                s"${Commands.owner_field(u.owner)}  ${Py.strip(u.text)}")
          }
        }
    }
  }


  /* ------------------------------------------------------------------ */
  /* unused                                                             */
  /* ------------------------------------------------------------------ */

  /* Entries with zero callers.  Names in `keep` are live roots — never flagged,
     for a top-of-pyramid theorem that legitimately has no in-project caller. */
  def compute_unused(g: Usage_Graph.Call_Graph, keep: Set[String]): Set[String] =
    g.all_names.filter(n => !keep(n) && g.callers.getOrElse(n, Set.empty).isEmpty)

  /* Fixed-point cascade: an entry is unused if all its callers are.  Returns
     `{name: depth}` with depth 0 = directly unused, 1 = became unused once the
     depth-0 entries are removed, and so on.

     LEVEL-SYNCHRONISED: a pass tests against the set as it stood BEFORE the
     pass, so a chain does not collapse into one level depending on the order
     names happen to come out of a hash table.  The reference implementation
     tests against the set as it GROWS, which makes its depth labels depend on
     Python's per-process string hash seed — the same corpus prints different
     depths on two consecutive runs (`dev/DIVERGENCES.md`, D10).  The unused SET
     is the same either way; only the labels move. */
  def compute_unused_recursive(g: Usage_Graph.Call_Graph, keep: Set[String]): Map[String, Int] = {
    val unused = mutable.LinkedHashMap.empty[String, Int]
    for (n <- compute_unused(g, keep)) unused(n) = 0
    var depth = 1
    var changed = true
    while (changed) {
      changed = false
      val settled = unused.keySet.toSet
      for (name <- g.all_names if !settled(name) && !keep(name)) {
        val callers = g.callers.getOrElse(name, Set.empty)
        if (callers.nonEmpty && callers.subsetOf(settled)) {
          unused(name) = depth
          changed = true
        }
      }
      depth += 1
    }
    unused.toMap
  }

  /* The forest of unused roots with exclusive subtree sizes: for each root
     (zero callers, modulo `keep`), the entries reachable ONLY from it and the
     whole cone reachable from it.

     A single-pass BFS is INCORRECT here — a node's root-set must accumulate
     from ALL its callers, but a BFS visits each node once at first discovery
     and misses later-discovered contributions.  So: fixed-point iteration,
     which the citation DAG makes converge in O(longest caller chain) passes.
     Kept roots are seeded too, so an entry shared between an unused root and a
     live one is not counted as exclusive to the unused one — it would survive
     a prune. */
  def compute_forest(g: Usage_Graph.Call_Graph, sections: List[Theory_Section],
    keep: Set[String]
  ): List[(String, Int, Int, Int, Int)] = {
    val roots = compute_unused(g, keep)
    val all_roots = roots | keep
    val root_sets = mutable.LinkedHashMap.empty[String, Set[String]]
    for (r <- all_roots) root_sets(r) = Set(r)

    var changed = true
    while (changed) {
      changed = false
      for (name <- g.all_names if !all_roots(name)) {
        var rset = Set.empty[String]
        for (c <- g.callers.getOrElse(name, Set.empty))
          rset = rset | root_sets.getOrElse(c, Set.empty)
        if (rset.nonEmpty && !root_sets.get(name).contains(rset)) {
          root_sets(name) = rset
          changed = true
        }
      }
    }

    val entry_lines = mutable.LinkedHashMap.empty[String, Int]
    for (sec <- sections; e <- sec.entries
         if g.all_names(e.name) && !entry_lines.contains(e.name))
      entry_lines(e.name) = e.line_count

    val result = new mutable.ListBuffer[(String, Int, Int, Int, Int)]
    for (root <- roots.toList.sorted) {
      var ee, el, te, tl = 0
      for ((name, rset) <- root_sets if rset(root)) {
        val sz = entry_lines.getOrElse(name, 0)
        te += 1
        tl += sz
        if (rset.size == 1) { ee += 1; el += sz }
      }
      result += ((root, ee, el, te, tl))
    }
    result.toList.sortBy(-_._3)                 // by exclusive lines, descending
  }

  private def render_forest(out: Out, sections: List[Theory_Section],
    forest: List[(String, Int, Int, Int, Int)], flags: Flags
  ): Unit = {
    if (forest.isEmpty) out.println("No unused roots found.")
    else if (flags.mode == "count") out.println(forest.length.toString)
    else {
      val by_name = Usage_Graph.entry_by_name(sections)
      out.println(s"${forest.length} unused roots:\n")
      out.println(s"  ${pad_right("Root", 42)}  ${pad_left("Excl", 5)}  " +
        s"${pad_left("Lines", 6)}  ${pad_left("Total", 5)}  ${pad_left("Lines", 6)}  Theory")
      out.println(s"  ${pad_right("-" * 42, 42)}  ${pad_left("-" * 5, 5)}  " +
        s"${pad_left("-" * 6, 6)}  ${pad_left("-" * 5, 5)}  ${pad_left("-" * 6, 6)}  ------")
      for ((root, ee, el, te, tl) <- forest) {
        val thy = by_name.get(root).map(_._1).getOrElse("?")
        out.println(s"  ${pad_right(root, 42)}  ${pad_left(ee.toString, 5)}  " +
          s"${pad_left(el.toString, 6)}  ${pad_left(te.toString, 5)}  " +
          s"${pad_left(tl.toString, 6)}  $thy")
      }
    }
  }

  private def render_unused(out: Out, entries: List[(String, Entry, Int)],
    flags: Flags, recursive: Boolean
  ): Unit = {
    val label = if (recursive) "transitively unused" else "unused"
    val total = entries.length
    /* Before the empty guard [count-mode-zero]: a project with nothing unused
       has ZERO unused entries, and that is the answer a script wants — the one
       case it most wants to branch on, and the one that used to be a
       sentence. */
    if (flags.mode == "count") out.println(total.toString)
    else if (entries.isEmpty) out.println("No unused entries found.")
    else if (flags.by_theory) {
      val theory_entries = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[(Entry, Int)]]
      for ((theory, e, depth) <- entries)
        theory_entries.getOrElseUpdate(theory, new mutable.ListBuffer) += ((e, depth))
      val total_lines =
        theory_entries.values.flatten.filter(_._1.thy_line > 0).map(_._1.line_count).sum
      out.println(s"$total $label entries across ${theory_entries.size} theories " +
        s"($total_lines source lines):\n")
      for ((theory, tes) <- theory_entries.toList.sortBy(-_._2.length)) {
        val lines = tes.filter(_._1.thy_line > 0).map(_._1.line_count).sum
        val head = tes.take(4).map(_._1.name).mkString(", ")
        val names = if (tes.length > 4) head + s", ... (+${tes.length - 4})" else head
        out.println(s"  ${pad_left(tes.length.toString, 3)}  ${pad_right(theory, 30)}  " +
          s"${pad_left(lines.toString, 5)} lines  $names")
      }
    }
    else {
      if (recursive) {
        val direct = entries.count(_._3 == 0)
        val cascade = total - direct
        val total_lines = entries.filter(_._2.thy_line > 0).map(_._2.line_count).sum
        out.println(s"$total $label entries ($direct direct + $cascade cascading, " +
          s"$total_lines source lines):\n")
      }
      else out.println(s"$total unused entries (zero callers):\n")
      out.println(s"${pad_right("Tag", 8)}  ${pad_right("Name", 42)}  Theory  (span)")
      out.println(s"${pad_right("-" * 8, 8)}  ${pad_right("-" * 42, 42)}  ------")
      for ((theory, e, depth) <- entries) {
        val mark = if (recursive && depth > 0) s"  [cascade depth $depth]" else ""
        out.println(s"${pad_right(e.tag, 8)}  ${pad_right(e.name, 42)}  $theory  " +
          s"(${e.src_start}..${e.thy_end}, ${e.line_count} lines)$mark")
      }
    }
  }

  def cmd_unused(out: Out, err: Out, sections: List[Theory_Section], flags: Flags): Unit = {
    /* `derived = true` here and NOWHERE else.  The citation graph is over FACTS,
       and `foo_def` is a different fact from `foo` — `callers foo` keeps meaning
       `foo`.  Deadness, though, is a question about the DECLARATION: deleting
       `definition foo` breaks every proof citing `foo_def`, so such a proof keeps
       `foo` alive.  Asking the fact-level question here would report live
       definitions as dead. */
    val g = Usage_Graph.build_call_graph(sections, flags.drop_names_upto, derived = true)

    val keep = flags.keep
    if (keep.nonEmpty) {
      val unknown = (keep -- g.all_names).toList.sorted
      if (unknown.nonEmpty) {
        out.flush()
        err.println("warning: --keep names not found in call graph: " + unknown.mkString(", "))
      }
    }

    if (flags.roots) render_forest(out, sections, compute_forest(g, sections, keep), flags)
    else {
      val unused_map =
        if (flags.recursive) compute_unused_recursive(g, keep)
        else compute_unused(g, keep).map(_ -> 0).toMap
      val rows = new mutable.ListBuffer[(String, Entry, Int)]
      for (sec <- sections; e <- sec.entries
           if Usage_Graph.citable_tags(e.tag) && e.name != "?" && unused_map.contains(e.name))
        rows += ((sec.theory, e, unused_map(e.name)))
      render_unused(out, rows.toList, flags, flags.recursive)
    }
  }


  /* ------------------------------------------------------------------ */
  /* graph                                                              */
  /* ------------------------------------------------------------------ */

  /* A DOT-safe double-quoted ID.  Isabelle names routinely carry backslashes
     (`split\<^sub>i_tree`), and DOT reads `\` as an escape introducer inside a
     quoted ID, so an unescaped name is not merely ugly: `\<` would be consumed
     and the graph would carry a different name than the corpus does. */
  private def dot_quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  final case class Graph_Data(kind: String, nodes: List[Json.Obj],
    edges: List[(String, String)], external: Set[String]) {
    def json: Json.T =
      Json.Obj(List(
        "kind" -> Json.Str(kind),
        "nodes" -> Json.Arr(nodes),
        "edges" -> Json.Arr(edges.map(e => Json.Arr(List(Json.Str(e._1), Json.Str(e._2)))))))

    def node_names: List[String] =
      nodes.map(_.fields.collectFirst { case ("name", Json.Str(s)) => s }.getOrElse(""))
  }

  /* Nodes = indexed entries; edges = caller -> callee. */
  private def citation_graph_data(sections: List[Theory_Section], flags: Flags): Graph_Data = {
    val g = Usage_Graph.build_call_graph(sections, flags.drop_names_upto)
    val by_name = Usage_Graph.entry_by_name(sections)
    val known = g.all_names.toList.sorted.filter(by_name.contains)
    val nodes =
      for (n <- known) yield {
        val (thy, e) = by_name(n)
        Json.Obj(List("name" -> Json.Str(n), "theory" -> Json.Str(thy),
          "tag" -> Json.Str(e.tag), "line" -> Json.Num(e.thy_line)))
      }
    val known_set = known.toSet
    val edges =
      (for {
        (caller, callees) <- g.callees.toList if known_set(caller)
        callee <- callees if known_set(callee)
      } yield (caller, callee)).sorted
    Graph_Data("citation", nodes, edges, Set.empty)
  }

  /* Nodes = theories; edges = importer -> imported.  Out-of-project imports are
     kept as nodes flagged `external`, not dropped: they are a real part of the
     picture a dependency diagram is for, and query knows their raw token even
     though it does not load their sources. */
  private def import_graph_data(sections: List[Theory_Section]): Graph_Data = {
    val by_theory = Usage_Graph.sections_by_theory(sections)
    val by_leaf = Reach.leaf_index(by_theory.keys)
    val nodes = new mutable.ListBuffer[Json.Obj]
    for (s <- sections.sortBy(_.theory))
      nodes += Json.Obj(List("name" -> Json.Str(s.theory), "external" -> Json.Bool(false),
        "lines" -> Json.Num(s.thy_lines), "entries" -> Json.Num(s.entries.length)))
    val edges = new mutable.ListBuffer[(String, String)]
    val external = mutable.LinkedHashSet.empty[String]
    for (sec <- sections; imp <- Discovery.thy_imports(sec.path))
      resolve_import(imp, by_theory, by_leaf) match {
        case None => external += imp; edges += ((sec.theory, imp))
        case Some(r) => edges += ((sec.theory, r))
      }
    for (n <- external.toList.sorted)
      nodes += Json.Obj(List("name" -> Json.Str(n), "external" -> Json.Bool(true)))
    Graph_Data("imports", nodes.toList, edges.toList.distinct.sorted, external.toSet)
  }

  def cmd_graph(out: Out, sections: List[Theory_Section], kind: String, fmt: String,
    flags: Flags
  ): Unit = {
    val data =
      if (kind == "citation") citation_graph_data(sections, flags)
      else import_graph_data(sections)
    if (fmt == "json")
      /* Sorted and indented: the output of two runs over the same tree must
         diff cleanly, which is most of what makes an export worth having. */
      out.println(Json.render(data.json))
    else {
      out.println(s"digraph ${data.kind} {")
      out.println("  rankdir=LR;")
      for (n <- data.node_names) {
        val attrs = if (data.external(n)) " [style=dashed]" else ""
        out.println(s"  ${dot_quote(n)}$attrs;")
      }
      for ((src, dst) <- data.edges) out.println(s"  ${dot_quote(src)} -> ${dot_quote(dst)};")
      out.println("}")
    }
  }
}
