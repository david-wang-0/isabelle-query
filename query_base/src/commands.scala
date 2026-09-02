/*  Title:      query_base/src/commands.scala

One `cmd_*` per subcommand, plus the lookup, locus and drill-down helpers they
share.

Above `render`, below the CLI.  Each command takes an already-loaded
`List[Theory_Section]` and prints; it never loads an index and never reads
argv, so the layering stays acyclic and a command is testable by handing it
sections.

Also home to the pieces more than one command needs and the CLI's own routing
reaches for: theory resolution, the shared `theory:line` / `theory:A..B` locus
grammar, and the proof-block drill-down behind `enclosing`.
*/

package isabelle.query


import isabelle.*

import java.nio.file.{Files, Path => JPath, Paths}
import java.util.regex.{Pattern, PatternSyntaxException}

import scala.collection.mutable


object Commands {
  private val EM_DASH = Render.EM_DASH
  private val MIDDLE_DOT = 0x00b7.toChar.toString   // U+00B7
  private val TRIANGLE = Render.TRIANGLE
  private val ARROW = Render.ARROW

  /* Definition-like exports — the `D` column, and what `defs` lists.  NOT the
     same set as the citation-graph kinds: a datatype is a definition and never
     a graph node, a lemma the reverse. */
  val definition_tags: Set[String] =
    Set("DEF", "ABBREV", "FUN", "DATATYPE", "RECORD", "TYPE")

  private def pad_right(s: String, w: Int): String =
    if (s.length >= w) s else s + (" " * (w - s.length))

  private def pad_left(s: String, w: Int): String =
    if (s.length >= w) s else (" " * (w - s.length)) + s


  /* ------------------------------------------------------------------ */
  /* the unresolvable subject                                           */
  /* ------------------------------------------------------------------ */

  /* Distinct from 0 (the question was asked and the answer was empty) and from
     `CLI.EXIT_BAD_ROOT` 2 (the corpus itself could not be read), because those
     are the distinctions a caller has to make.  Lives here rather than beside
     the other two because it is a COMMAND's verdict and `commands` sits below
     `cli`; `README.md`'s exit-status table is the user-facing statement of all
     three. */
  val EXIT_UNRESOLVED = 1

  /* `CLI.Session.fail_root`'s rule one level down [unresolved-subject].  "No
     callees of zzz" and "there is no zzz" are different answers, and a caller
     cannot act on the difference if both arrive on stdout with status 0 —
     `$(query callees X -c)` captured a sentence where it expected a number,
     and `$?` said the run succeeded.

     Note the two kinds of empty this keeps apart.  `find zzz -c` prints `0`,
     because searching for something absent is a real search with a real
     answer; `callees zzz` cannot begin, because there is no entry to have
     callees.  The verbs disagree because the questions do.

     `out.flush()` first, for the reason `fail_root` does it: on a terminal the
     two streams interleave by flush order, and a diagnostic that overtakes the
     stdout written before it reads as belonging to the wrong subject.  The
     `Exit_Code` is caught in `CLI.run_result`, so in a batch the earlier
     subjects' output survives and the loop stops here — which is exactly the
     reference implementation's `sys.exit` inside `_run_each`. */
  def fail_subject(out: Out, err: Out, what: String): Nothing = {
    out.flush()
    err.println(s"isabelle query: $what")
    throw Exit_Code(EXIT_UNRESOLVED)
  }


  /* ------------------------------------------------------------------ */
  /* summary                                                            */
  /* ------------------------------------------------------------------ */

  def tag_counts(sec: Theory_Section): (Int, Int, Int) = {
    var d, l, t = 0
    for (e <- sec.entries) {
      if (definition_tags(e.tag)) d += 1
      else if (e.tag == "LEMMA") l += 1
      else if (e.tag == "THEOREM") t += 1
    }
    (d, l, t)
  }

  def cmd_summary(out: Out, sections: List[Theory_Section], by_session: Boolean,
    verbose: Boolean, totals_only: Boolean
  ): Unit = {
    if (totals_only || by_session) summary_aggregate(out, sections, verbose, totals_only)
    else {
      val total = sections.map(_.entries.length).sum
      out.println("# Theory Index\n")
      out.println(s"$total entries across ${sections.length} theories  " +
        "(parsed live from .thy files)\n")
      out.println("## Theories\n")
      out.println("Source-line counts (`.thy` file size), entry counts, and key exports.\n")
      out.println("| Theory | Src | D | L | T | Key Exports |")
      out.println("|--------|----:|--:|--:|--:|-------------|")
      for (sec <- sections) {
        val defs = sec.entries.filter(e => definition_tags(e.tag))
        val lemmas = sec.entries.filter(_.tag == "LEMMA")
        val thms = sec.entries.filter(_.tag == "THEOREM")

        val key_names = new mutable.ListBuffer[String]
        def offer(e: Entry): Unit =
          if (e.name != "?" && !key_names.contains(e.name)) key_names += e.name
        for (e <- defs) offer(e)
        for (e <- thms) offer(e)
        if (key_names.isEmpty) for (e <- lemmas.take(3)) offer(e)

        val exports =
          key_names.take(6).mkString(", ") + (if (key_names.length > 6) ", ..." else "")
        out.println(s"| ${sec.theory} | ${sec.thy_lines} | " +
          s"${defs.length} | ${lemmas.length} | ${thms.length} | $exports |")
      }
    }
  }

  /* Roll the per-theory counts up to the session / corpus level: one row per
     session plus a grand total, in ROOT-discovery (first-seen) order.  A
     theory reached through several sessions is counted once, under the first
     that reached it — the same rule the path dedup uses. */
  private def summary_aggregate(out: Out, sections: List[Theory_Section],
    verbose: Boolean, totals_only: Boolean
  ): Unit = {
    val groups = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[Theory_Section]]
    for (sec <- sections)
      groups.getOrElseUpdate(sec.session.getOrElse("(no session)"),
        new mutable.ListBuffer[Theory_Section]) += sec

    val tot_thy = sections.length
    val tot_src = sections.map(_.thy_lines).sum
    val tot_entries = sections.map(_.entries.length).sum
    var tot_d, tot_l, tot_t = 0
    for (sec <- sections) {
      val (d, l, t) = tag_counts(sec)
      tot_d += d; tot_l += l; tot_t += t
    }

    out.println("# Corpus summary\n")
    out.println(s"${Py.comma(tot_entries)} entries $MIDDLE_DOT ${Py.comma(tot_src)} source " +
      s"lines across ${Py.comma(tot_thy)} theories in ${Py.comma(groups.size)} sessions  " +
      "(parsed live from .thy files)\n")
    if (!totals_only) {
      out.println("Per-session aggregate — `Src` is summed `.thy` line counts " +
        "(`wc -l`-comparable), `D`/`L`/`T` are definition / lemma / theorem counts.\n")

      if (verbose) {
        for ((name, secs) <- groups) {
          val g_src = secs.map(_.thy_lines).sum
          out.println(s"## $name  (${Py.comma(secs.length)} theories, " +
            s"${Py.comma(g_src)} lines)\n")
          out.println("| Theory | Src | D | L | T |")
          out.println("|--------|----:|--:|--:|--:|")
          for (sec <- secs) {
            val (d, l, t) = tag_counts(sec)
            out.println(s"| ${sec.theory} | ${sec.thy_lines} | $d | $l | $t |")
          }
          out.println()
        }
      }
      else {
        out.println("| Session | Thy | Src | D | L | T |")
        out.println("|---------|----:|----:|--:|--:|--:|")
        for ((name, secs) <- groups) {
          val g_src = secs.map(_.thy_lines).sum
          var g_d, g_l, g_t = 0
          for (sec <- secs) {
            val (d, l, t) = tag_counts(sec)
            g_d += d; g_l += l; g_t += t
          }
          out.println(s"| $name | ${secs.length} | $g_src | $g_d | $g_l | $g_t |")
        }
        out.println(s"| **TOTAL** | $tot_thy | $tot_src | $tot_d | $tot_l | $tot_t |")
      }
    }
  }


  /* ------------------------------------------------------------------ */
  /* theory resolution                                                  */
  /* ------------------------------------------------------------------ */

  private def stem_of(name: String): String = {
    val leaf = {
      val i = name.lastIndexOf('/')
      if (i < 0) name else name.substring(i + 1)
    }
    val j = leaf.lastIndexOf('.')
    if (j > 0) leaf.substring(0, j) else leaf
  }

  def real(p: JPath): JPath = Discovery.real(p)

  /* A theory by PATH (the token carries a separator or a `.thy` suffix) or by
     NAME (exact, then case-insensitive).  A path that matches no section falls
     back to its stem, so `path/to/Foo.thy` still resolves to theory `Foo`.

     The two forms are TRIED in that order rather than chosen between, because
     a discovered theory NAME may itself contain a separator: a ROOT can address
     a theory in a subdirectory by path (`theories "LK/Propositional"` — the
     grammar has no per-theory `in` clause), and discovery carries it under that
     spelling.  Branching on `/` alone made such a name unresolvable, so
     `summary` printed a row, `theory`'s "Known theories" listed it, and passing
     it back gave "not found" — the tool disagreeing with its own output, and a
     hole in the round-trip the locus grammar rests on [name-roundtrip]. */
  def resolve_theory(sections: List[Theory_Section], name: String): Option[Theory_Section] = {
    if (name.endsWith(".thy") || name.contains("/")) {
      val target =
        try Some(real(Paths.get(name).toAbsolutePath))
        catch { case _: Exception => None }
      val by_path =
        target.flatMap(t => sections.find(s => real(s.path) == t))
      by_path
        /* Before the stem fallback: the whole argument may be a path-spelled
           theory NAME.  Exact-first matters — with a `Propositional` section
           also present, the stem would otherwise capture `LK/Propositional`. */
        .orElse(sections.find(_.theory == name))
        /* A LABEL, as `Render.theory_labels` emits it: the shortest directory
           qualification that names one theory, matched here as a suffix of the
           same tuple the label was built from — the exact inverse, so the
           emitter and the resolver cannot drift [disambig-names].  Without it
           the emitter's half is worse than useless: nineteen AFP theories are
           called `Examples`, and `beta/Examples` would fall to the stem
           fallback and resolve to `alpha/Examples`, silently and confidently.
           A label that looks paste-able and lands on a DIFFERENT theory is a
           worse answer than a bare ambiguous name, which at least reads as
           ambiguous.  Only a UNIQUE hit counts; anything else falls through. */
        .orElse {
          val want = Render.name_parts(name)
          if (want.isEmpty) None
          else {
            val hits = sections.filter { s =>
              val tuple = Render.path_parts(s.real_path.getParent) :+ s.theory
              tuple.length >= want.length && tuple.takeRight(want.length) == want
            }
            if (hits.length == 1) Some(hits.head) else None
          }
        }
        .orElse {
          val stem = stem_of(name)
          sections.find(_.theory == stem)
        }
    }
    else
      sections.find(_.theory == name)
        .orElse(sections.find(_.theory.toLowerCase == name.toLowerCase))
  }

  /* The closest theory to `name`, as a cwd-relative `.thy` path — a "did you
     mean …?" hint, and stderr-only.  The similarity measure is a longest
     common subsequence rather than `difflib`'s matching-block ratio: the two
     agree on which candidate is closest in every case a hint is worth giving,
     and the exact score is never printed. */
  def suggest_theory(sections: List[Theory_Section], name: String): Option[String] = {
    val key = stem_of(name)
    def ratio(a: String, b: String): Double = {
      if (a.isEmpty && b.isEmpty) 1.0
      else {
        val prev = new Array[Int](b.length + 1)
        val cur = new Array[Int](b.length + 1)
        var i = 0
        while (i < a.length) {
          java.util.Arrays.fill(cur, 0)
          var j = 0
          while (j < b.length) {
            cur(j + 1) =
              if (a.charAt(i) == b.charAt(j)) prev(j) + 1 else cur(j) max prev(j + 1)
            j += 1
          }
          System.arraycopy(cur, 0, prev, 0, cur.length)
          i += 1
        }
        2.0 * prev(b.length) / (a.length + b.length)
      }
    }
    val best =
      sections.map(s => (ratio(key, s.theory), s))
        .filter(_._1 >= 0.6)
        .sortBy(-_._1)
        .headOption
    best.map { case (_, sec) =>
      val cwd = Paths.get("").toAbsolutePath
      val p = real(sec.path)
      if (p.startsWith(cwd)) cwd.relativize(p).toString else p.toString
    }
  }


  /* ------------------------------------------------------------------ */
  /* theory / defs / outline                                            */
  /* ------------------------------------------------------------------ */

  def cmd_theory(out: Out, err: Out, sections: List[Theory_Section], name: String,
    flags: Flags
  ): Unit =
    resolve_theory(sections, name) match {
      case None =>
        /* The known-theory list goes to stderr WITH the diagnostic, as one
           message: it is the hint that makes the failure actionable, and a
           caller reading stdout must get nothing at all. */
        fail_subject(out, err,
          (s"no theory '$name'.  Known theories:" ::
            sections.map(_.theory).sorted.map("  " + _)).mkString("\n"))
      case Some(sec) =>
        if (flags.mode == "count") out.println(sec.entries.length.toString)
        else if (flags.mode == "names")
          for (e <- sec.entries) out.println(Render.format_name_line(sec, e))
        else {
          out.println(s"## ${sec.theory}.thy  (${sec.thy_lines} src lines, " +
            s"${sec.entries.length} entries)")
          if (flags.verbatim)
            for (e <- sec.entries) {
              out.println()
              out.println(Render.render_entry(sec, e, verbatim = true))
            }
          else {
            out.println("```")
            for (e <- sec.entries) {
              if (flags.comments != "off" && e.preamble.isDefined) {
                val (ps, pe) = e.preamble.get
                val body = Render.strip_text_wrapper(sec.slice(ps, pe).toList)
                val (preview, _) = Render.truncate_preview(body, flags.context)
                if (preview.nonEmpty) {
                  out.println()
                  out.println(s"[preamble $ps-$pe]: " +
                    preview.map(Py.strip).mkString(" "))
                }
              }
              out.println(e.text)
            }
            out.println("```")
          }
        }
    }

  def cmd_defs(out: Out, err: Out, sections: List[Theory_Section], theory: String,
    flags: Flags
  ): Unit =
    resolve_theory(sections, theory) match {
      case None => fail_subject(out, err, s"no theory '$theory'")
      case Some(sec) =>
        val matches = sec.entries.filter(e => definition_tags(e.tag))
        if (matches.isEmpty) out.println(s"No definitions found in '${sec.theory}'.")
        else if (flags.mode == "count") out.println(matches.length.toString)
        else if (flags.mode == "names")
          for (e <- matches) out.println(Render.format_name_line(sec, e))
        else
          for (e <- matches) {
            out.println(Render.render_entry(sec, e))
            out.println()
          }
    }

  private val outline_indent: Map[String, String] =
    Map("chapter" -> "", "section" -> "", "subsection" -> "  ",
      "subsubsection" -> "    ")

  def cmd_outline(out: Out, err: Out, sections: List[Theory_Section], theory: String,
    flags: Flags
  ): Unit =
    resolve_theory(sections, theory) match {
      case None => fail_subject(out, err, s"no theory '$theory'")
      case Some(sec) =>
        /* (line, kind, payload) with a stable sort on the line, exactly as the
           reference implementation's `items.sort(key=first)` does: a heading
           and an entry on the same line keep the order they were appended in. */
        val items = new mutable.ListBuffer[(Int, String, Any)]
        for ((level, title, ln) <- sec.outline) items += ((ln, "section", (level, title)))
        for (e <- sec.entries if e.thy_line > 0) items += ((e.thy_line, "entry", e))
        if (flags.comments != "off")
          for (tb <- sec.text_blocks) items += ((tb._1, "text", tb))
        val sorted = items.toList.sortBy(_._1)

        if (sorted.isEmpty) out.println(s"No outline data for '${sec.theory}'.")
        else {
          out.println(s"Outline of ${sec.theory}.thy:\n")
          for ((ln, kind, payload) <- sorted) {
            if (kind == "section") {
              val (level, title) = payload.asInstanceOf[(String, String)]
              outline_indent.get(level) match {
                case Some(indent) =>
                  out.println(s"$indent${pad_left(level, 14)}: $title  (line $ln)")
                case None =>
                  /* The reference implementation indexes a fixed dict here and
                     dies on `paragraph` / `subparagraph`, which its own heading
                     recogniser accepts.  Reproduced rather than fixed: the
                     alternative is inventing an indent it never prints. */
                  out.flush()
                  err.println(s"isabelle query: outline: unknown heading level '$level'")
                  throw Exit_Code(1)
              }
            }
            else if (kind == "text") {
              val (tb_start, tb_end) = payload.asInstanceOf[(Int, Int)]
              val block_size = tb_end - tb_start + 1
              val body = Render.strip_text_wrapper(sec.slice(tb_start, tb_end).toList)
              val (preview, _) = Render.truncate_preview(body, flags.context)
              val joined = preview.map(Py.strip).mkString(" ")
              val preview_text =
                if (joined.length > 100) joined.substring(0, 97) + "..." else joined
              out.println(s"        text     [$tb_start..$tb_end, $block_size lines]: " +
                preview_text)
            }
            else {
              val e = payload.asInstanceOf[Entry]
              out.println(s"        ${pad_right(e.tag, 8)} ${e.name}  " +
                s"(${e.src_start}..${e.thy_end}, ${e.line_count} lines)")
            }
          }
        }
    }


  /* ------------------------------------------------------------------ */
  /* user patterns                                                      */
  /* ------------------------------------------------------------------ */

  /* `ISA_SYMBOL`, not the name atom: this asks the LEXICAL question — is this
     an Isabelle symbol token, whose `^` must be protected from being read as
     an anchor? — and the answer is yes for `\<open>` and `\<^marker>` as much
     as for `\<^sub>`.  A user may legitimately grep for either. */
  private val PAT_MARKUP_RE: Pattern = Py.compile(Entries.ISA_SYMBOL)

  /* Make a user-typed pattern mean what the user meant.  Both rewrites exist
     because a pattern that quietly matches nothing is worse than one that
     errors: the caller is told "no matches" and believes it.

       * shell-grep alternation `a\|b` is a literal `|` to a regex engine;
       * an Isabelle name is PRINTED with markup (`split\<^sub>i_tree`) and
         pasted back in, where `\<` is a literal `<` and the `^` after it is a
         start-of-string anchor sitting mid-pattern — unmatchable, not merely
         imprecise.

     Only the `\<...>` spans are escaped, so the rest stays a regex.  That is
     also what makes the rewrite safe: `\<` has no other meaning, so no working
     pattern changes meaning. */
  def user_pattern(pattern0: String): String = {
    val pattern = pattern0.replace("""\|""", "|")
    val m = PAT_MARKUP_RE.matcher(pattern)
    val buf = new StringBuilder
    var last = 0
    while (m.find()) {
      buf ++= pattern.substring(last, m.start)
      buf ++= Py.re_escape(m.group(0))
      last = m.end
    }
    buf ++= pattern.substring(last)
    buf.toString
  }

  def compile_user_pattern(err: Out, out: Out, pattern: String,
    ignore_case: Boolean = false
  ): Pattern = {
    val flags =
      Pattern.UNICODE_CHARACTER_CLASS |
        (if (ignore_case) Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE else 0)
    try Pattern.compile(user_pattern(pattern), flags)
    catch {
      case exn: PatternSyntaxException =>
        out.flush()
        err.println(s"ERROR: invalid regex '$pattern': " +
          exn.getMessage.replace('\n', ' '))
        throw Exit_Code(2)
    }
  }


  /* ------------------------------------------------------------------ */
  /* find / show                                                        */
  /* ------------------------------------------------------------------ */

  def find_matches(sections: List[Theory_Section], pat: Pattern,
    statement: Boolean
  ): List[Entry] = {
    val out = new mutable.ListBuffer[Entry]
    for (s <- sections; e <- s.entries) {
      val hit =
        if (statement) Py.found(pat, Render.statement_text(s, e))
        /* A bound name counts: `rreqs` finds the record that declares it. */
        else Py.found(pat, e.name) || e.bound_names.exists(c => Py.found(pat, c))
      if (hit) out += e
    }
    out.toList
  }

  def cmd_find(out: Out, err: Out, sections: List[Theory_Section], pattern: String,
    flags: Flags
  ): Unit = {
    val pat = compile_user_pattern(err, out, pattern, ignore_case = true)
    val by_theory = Usage_Graph.sections_by_theory(sections)
    val matches = find_matches(sections, pat, flags.statement)
    Render.emit_matches(out, by_theory, matches, pattern, flags)
    if (flags.with_comments) emit_comment_matches(out, sections, List(pat), pattern, flags)
  }

  /* Conjunctive find: the entries matching EVERY pattern, reported once.
     Intersecting hit SETS rather than and-ing the regexes is what makes it
     compose — the patterns may match different parts of the entry, in any
     order, which one concatenated regex cannot express. */
  def cmd_find_and(out: Out, err: Out, sections: List[Theory_Section],
    patterns: List[String], flags: Flags
  ): Unit = {
    val pats = patterns.map(p => compile_user_pattern(err, out, p, ignore_case = true))
    val by_theory = Usage_Graph.sections_by_theory(sections)
    val label = patterns.mkString(" AND ")
    var matches = find_matches(sections, pats.head, flags.statement)
    for (pat <- pats.tail) {
      val keep = find_matches(sections, pat, flags.statement)
        .map(System.identityHashCode).toSet
      matches = matches.filter(e => keep(System.identityHashCode(e)))
    }
    Render.emit_matches(out, by_theory, matches, label, flags)
    if (flags.with_comments) emit_comment_matches(out, sections, pats, label, flags)
  }

  private def emit_comment_matches(out: Out, sections: List[Theory_Section],
    pats: List[Pattern], label: String, flags: Flags
  ): Unit = {
    val hits = find_in_comments(sections, pats.head, flags.context, pats.tail)
    if (hits.nonEmpty) {
      out.println()
      out.println(s"--- comment matches for '$label' (${hits.length} hit(s)) ---")
      for (hit <- hits) out.println(hit)
    }
  }

  /* Inside document blocks and `\<comment>` annotations.  The conjunction
     carries over per LINE: a note is a hit only if every pattern is on it,
     rather than a union quietly reintroducing the OR the flag turned off. */
  def find_in_comments(sections: List[Theory_Section], pat: Pattern, context: Int,
    require: List[Pattern]
  ): List[String] = {
    val hits = new mutable.ListBuffer[String]
    def matched(text: String): Boolean =
      Py.found(pat, text) && require.forall(r => Py.found(r, text))
    for (sec <- sections) {
      val src = sec.source
      for ((tb_start, tb_end) <- sec.text_blocks) {
        var ln = tb_start
        while (ln <= tb_end && ln <= src.length) {
          val line = src(ln - 1)
          if (matched(line)) {
            val lo = tb_start max (ln - context)
            val hi = tb_end min (ln + context)
            hits += s"\n${sec.theory}.thy:$ln (in text block $tb_start..$tb_end):"
            var j = lo
            while (j <= hi) {
              val marker = if (j == ln) ">" else " "
              hits += s"  $marker $j: ${src(j - 1)}"
              j += 1
            }
          }
          ln += 1
        }
      }
      for (e <- sec.entries; (ln, content, kind) <- e.annotations if matched(content))
        hits += s"\n${sec.theory}.thy:$ln (\\<comment> in ${e.name} $kind): $content"
    }
    hits.toList
  }

  val binding_kinds: Map[String, String] = Map(
    "conjunct" -> "a named conjunct of",
    "rule" -> "an introduction rule of",
    "sibling" -> "declared together with",
    "constructor" -> "a constructor of",
    "discriminator" -> "a discriminator of",
    "selector" -> "a selector of",
    "field" -> "a field of",
    "assumption" -> "an assumption of",
    "definition" -> "a defined element of",
    "note" -> "a named fact of")

  /* If `name` is an extra name some declaration binds (see `Entry.bindings`),
     the entry that binds it and the phrasing that says HOW — an introduction
     rule and a `shows` conjunct resolve the same way but are not the same
     thing.  Lets `callers` / `callees` / `show` answer for a name Isabelle
     minted rather than refusing it. */
  def resolve_binding(sections: List[Theory_Section], name: String): Option[(String, String)] =
    sections.iterator.flatMap(sec =>
      sec.entries.iterator.flatMap(e =>
        e.bindings.iterator.collect {
          case (n, kind) if n == name => (e.name, binding_kinds(kind))
        })).nextOption()

  def cmd_show(out: Out, sections: List[Theory_Section], name: String,
    flags: Flags
  ): Unit = {
    val by_theory = Usage_Graph.sections_by_theory(sections)
    val matches = new mutable.ListBuffer[Entry]
    for (s <- sections; e <- s.entries if e.name == name) matches += e
    if (matches.isEmpty)
      for (s <- sections; e <- s.entries if e.name.toLowerCase == name.toLowerCase)
        matches += e
    if (matches.isEmpty) {
      /* Bound-name fallback, BEFORE substring: NAME may be an extra name a
         declaration binds — a `shows` conjunct, an introduction rule, a
         mutually-declared constant. */
      var how = ""
      for (s <- sections; e <- s.entries; (n, kind) <- e.bindings if n == name) {
        matches += e
        how = binding_kinds(kind)
      }
      if (matches.nonEmpty) {
        val parents = matches.map(_.name).distinct.sorted.mkString(", ")
        out.println(s"# '$name' is $how $parents:")
      }
    }
    if (matches.isEmpty)
      for (s <- sections; e <- s.entries if e.name.toLowerCase.contains(name.toLowerCase))
        matches += e
    Render.emit_matches(out, by_theory, matches.toList, name, flags,
      statement = flags.statement)
  }


  /* ------------------------------------------------------------------ */
  /* line ownership, loci, proof blocks                                 */
  /* ------------------------------------------------------------------ */

  def enclosing_entry(sec: Theory_Section, line_no: Int): Option[Entry] =
    sec.entries.find(e =>
      e.thy_line > 0 && e.thy_end > 0 && e.src_start <= line_no && line_no <= e.thy_end)

  /* `name (TAG) lo..hi`, or `—` for a line with no owner.  `span` is the one
     content choice that legitimately differs by command: `callers` keeps it
     (the extent is the next locus), `grep` drops it (a search hit is already a
     precise locus, and the lemma span is constant across its hits). */
  def owner_field(owner: Option[Entry], span: Boolean = true): String =
    owner match {
      case None => EM_DASH
      case Some(e) if e.name == "?" => EM_DASH
      case Some(e) =>
        if (span && e.thy_line > 0 && e.thy_end > 0)
          s"${e.name} (${e.tag}) ${e.src_start}..${e.thy_end}"
        else s"${e.name} (${e.tag})"
    }

  /* `A..B`, `A..` (to EOF — an open upper bound this parser cannot resolve),
     `..B` (from line 1) or `A`.  Every range surface funnels through here, so
     the open forms light up everywhere at once. */
  def parse_line_range(spec: String): Option[(Int, Option[Int])] = {
    val (a, b) =
      if (spec.contains("..")) {
        val i = spec.indexOf("..")
        val a_str = spec.substring(0, i)
        val b_str = spec.substring(i + 2)
        val a = if (a_str.isEmpty) Some(1) else Py.parse_int(a_str)
        val b = if (b_str.isEmpty) Some(None) else Py.parse_int(b_str).map(Some(_))
        (a, b)
      }
      else {
        val n = Py.parse_int(spec)
        (n, n.map(Some(_)))
      }
    for {
      lo <- a
      hi <- b
      if lo >= 1 && hi.forall(_ >= lo)
    } yield (lo, hi)
  }

  /* `FILE:LINE` / `FILE:A..B` into `(file, lo, hi)`.  The file is split off on
     the LAST colon, so a path with separators keeps them.  A single trailing
     `:` or `-` is peeled first — ripgrep's match / context marker, which this
     tool's own location output and a real `grep -n` both emit, so a pasted
     line round-trips. */
  def parse_locus(token0: String): Option[(String, Int, Option[Int])] = {
    val token =
      if (token0.nonEmpty && (token0.last == ':' || token0.last == '-'))
        token0.substring(0, token0.length - 1)
      else token0
    val i = token.lastIndexOf(':')
    if (i <= 0 || i == token.length - 1) None
    else {
      val file_token = token.substring(0, i)
      val span = token.substring(i + 1)
      parse_line_range(span).map { case (lo, hi) => (file_token, lo, hi) }
    }
  }

  /* Where in the entry a line sits — the same `proof_line` / `decl_end_line`
     boundaries the renderer slices on, so the answer matches what
     `show --statement` would show.  The point during a build chase: knowing
     the failing line is the STATEMENT rather than a proof step says which to
     edit. */
  def locus_role(e: Entry, line_no: Int): String =
    if (e.thy_line > 0 && line_no < e.thy_line) "in preamble"
    else if (e.proof_line > 0 && line_no >= e.proof_line) "in proof"
    else if (e.decl_end_line > 0 && line_no <= e.decl_end_line) "in statement"
    else ""

  private val GOAL_INTRO_RE: Pattern =
    Py.compile("""^(have|show|hence|thus|obtain|consider)\b(?:\s+([A-Za-z][\w'.]*)\s*:)?""")
  private val PROOF_OPEN_RE: Pattern = Py.compile("""^proof\b""")
  private val QED_RE: Pattern = Py.compile("""^qed\b""")
  private val TERMINAL_RE: Pattern = Py.compile("""^(by|done|sorry|oops)\b|^\.\.?\s*$""")

  final case class Block(kw: String, name: String, start: Int, end: Int)

  def block_label(b: Block): String =
    if (b.kw == "{") "{ }" else Py.strip(b.kw + " " + b.name)

  def block_field(b: Block): String = s"${block_label(b)} ${b.start}..${b.end}"

  /* The nested blocks inside an entry's proof, or None if the open/close stack
     went unbalanced — then the caller falls back to the entry-level answer
     rather than emit a span it is not sure of.  The lemma's own outermost
     `proof` is not reported: that is what the entry already represents. */
  def proof_blocks(sec: Theory_Section, entry: Entry): Option[List[Block]] = {
    if (entry.proof_line == 0) Some(Nil)
    else {
      val lines = sec.source
      val end0 =
        if (entry.body_end_line != 0) entry.body_end_line
        else if (entry.thy_end != 0) entry.thy_end
        else lines.length
      val end = end0 min lines.length
      val noise = Entries.line_mask(lines.length, Usage_Graph.noise_spans(sec))
      val stack = new mutable.ArrayBuffer[(String, String, Int)]
      val blocks = new mutable.ListBuffer[Block]
      var pending: Option[(String, String, Int)] = None
      var main_open = false
      var unbalanced = false
      var ln = entry.proof_line
      while (!unbalanced && ln <= end) {
        if (!(ln < noise.length && noise(ln))) {
          val stripped = Py.strip(lines(ln - 1))
          if (stripped.nonEmpty) {
            Py.matches_at_start(GOAL_INTRO_RE, stripped) match {
              case Some(gm) => pending = Some((gm.group(1), Py.group_or_empty(gm, 2), ln))
              case None =>
            }
            if (Py.matches_start(PROOF_OPEN_RE, stripped)) {
              if (!main_open && stack.isEmpty) {
                stack += (("__main__", "", ln))
                main_open = true
              }
              else stack += pending.getOrElse(("proof", "", ln))
              pending = None
            }
            else if (Py.matches_start(QED_RE, stripped)) {
              if (stack.isEmpty) unbalanced = true
              else {
                val (kw, nm, start) = stack.remove(stack.length - 1)
                if (kw != "__main__") blocks += Block(kw, nm, start, ln)
                pending = None
              }
            }
            else if (stripped == "{") stack += (("{", "", ln))
            else if (stripped == "}") {
              if (stack.isEmpty) unbalanced = true
              else {
                val (kw, nm, start) = stack.remove(stack.length - 1)
                if (kw != "__main__") blocks += Block(kw, nm, start, ln)
                pending = None
              }
            }
            else if (Py.matches_start(TERMINAL_RE, stripped)) pending = None
          }
        }
        ln += 1
      }
      if (unbalanced || stack.nonEmpty) None else Some(blocks.toList)
    }
  }

  def enclosing_blocks(blocks: List[Block], line_no: Int): List[Block] =
    blocks.filter(b => b.start <= line_no && line_no <= b.end)
      .sortBy(b => (b.start, -b.end))

  /* The echoed locus is the theory's LABEL, not the token the user typed: one
     that came in as a path (`thys/Foo/Bar.thy:12`) goes back out in the house
     `theory:line` form, and one that came in bare goes back out qualified when
     the corpus holds two theories of that name.  Echoing the input verbatim
     would be the easy answer and the wrong one — the point of the echo is to
     say which theory the tool actually resolved to [disambig-loci]. */
  def cmd_enclosing(out: Out, err: Out, sections: List[Theory_Section],
    loci: List[String], block_mode: String
  ): Unit = {
    val labels = Render.locus_labels(sections)
    for (token <- loci) {
      parse_locus(token) match {
        case None =>
          out.flush()
          err.println(s"$token: expected FILE:LINE or FILE:A..B " +
            "(e.g. Foo.thy:42 or Foo:8..12)")
        case Some((file_token, lo, hi)) =>
          resolve_theory(sections, file_token) match {
            case None =>
              out.flush()
              val hint = suggest_theory(sections, file_token) match {
                case Some(s) => s" (did you mean $s?)"
                case None => ""
              }
              err.println(s"$token: no such theory '$file_token'$hint")
            case Some(sec) =>
              /* An open upper bound resolves to the theory's last line here.
                 The point test stays on the RAW hi, so `A..` is always a range
                 and never mistaken for a single line. */
              val hi_eff = hi.getOrElse(sec.thy_lines)
              val point = hi.contains(lo)
              val thy = labels.getOrElse(sec.path, sec.theory)
              val loc = if (point) s"$thy:$lo" else s"$thy:$lo..$hi_eff"
              if (lo > sec.thy_lines)
                out.println(s"$loc $ARROW (past end of $thy " +
                  s"$EM_DASH ${sec.thy_lines} lines)")
              else if (point) {
                enclosing_entry(sec, lo) match {
                  case None =>
                    out.println(s"$loc $ARROW (no enclosing entry $EM_DASH " +
                      "theory header or inter-section gap)")
                  case Some(entry) =>
                    val role = locus_role(entry, lo)
                    val suffix = if (role.nonEmpty) s"  ($role)" else ""
                    val target = Render.format_target(entry)
                    val scope =
                      if (target.nonEmpty) s"$thy $TRIANGLE $target" else thy
                    val base = s"$loc $ARROW ${entry.name} (${entry.tag}) $EM_DASH " +
                      s"$scope ${Render.format_extent(entry)}"
                    val blocks =
                      if (block_mode != "entry" && role == "in proof")
                        enclosing_blocks(proof_blocks(sec, entry).getOrElse(Nil), lo)
                      else Nil
                    if (blocks.isEmpty) out.println(base + suffix)
                    else if (block_mode == "blocks") {
                      out.println(base + suffix)
                      val indent = " " * (loc.length + 3)
                      val width = blocks.map(b => block_label(b).length).max
                      for (b <- blocks)
                        out.println(s"$indent$TRIANGLE ${pad_right(block_label(b), width)} " +
                          s"${b.start}..${b.end}")
                    }
                    else out.println(s"$base $TRIANGLE ${block_field(blocks.last)}$suffix")
                }
              }
              else {
                val overlap =
                  sec.entries.filter(e =>
                    e.thy_line > 0 && e.thy_end > 0 &&
                      !(e.thy_end < lo || e.src_start > hi_eff)).sortBy(_.src_start)
                if (overlap.isEmpty)
                  out.println(s"$loc $ARROW (no entries overlap $EM_DASH " +
                    "theory header or inter-section gap)")
                else
                  for (e <- overlap) {
                    val target = Render.format_target(e)
                    val scope =
                      if (target.nonEmpty) s"$thy $TRIANGLE $target" else thy
                    out.println(s"$loc $ARROW ${e.name} (${e.tag}) $EM_DASH $scope " +
                      Render.format_extent(e))
                  }
              }
          }
      }
    }
  }


  /* ------------------------------------------------------------------ */
  /* grep / sorry                                                       */
  /* ------------------------------------------------------------------ */

  /* The section's PATH, not a display name: these two verbs report a FILE, and
     a bare file name is ambiguous over a corpus in exactly the way a bare
     theory name is (nineteen AFP files are called `Examples.thy`).
     `Render.file_locus` turns the path into the printed locus — the label with
     its suffix restored, so a non-`.thy` positional still reports its actual
     filename rather than `<stem>.thy` [disambig-loci]. */
  final case class Hit(path: JPath, line_no: Int, text: String,
    owner: Option[Entry], is_live: Boolean, is_thy: Boolean)

  /* One record per matching line.  `is_live` is decided on the REDACTED copy,
     not on the line: `by simp \<comment> \<open>see foo\<close>` is a live line
     holding a prose-only match, and a line-granular test would report that
     `foo` as source.  The raw line is still what is matched first, so
     `--with-comments` still finds prose. */
  def grep_sections(sections: List[Theory_Section], pat: Pattern): List[Hit] = {
    val line_index = Usage_Graph.build_line_index(sections)
    val out = new mutable.ListBuffer[Hit]
    for (sec <- sections) {
      val lines = sec.source
      val live_lines = sec.live_source
      val noise = Entries.line_mask(lines.length, Usage_Graph.noise_spans(sec))
      val idx = line_index.getOrElse(sec.path, Array.empty[(Int, Int, Entry)])
      val (win_lo, win_hi) = sec.line_window match {
        case Some((lo, hi)) => (lo, hi.getOrElse(lines.length))
        case None => (1, lines.length)
      }
      var line_no = 1
      while (line_no <= lines.length) {
        if (win_lo <= line_no && line_no <= win_hi) {
          val line = lines(line_no - 1)
          if (Py.found(pat, line)) {
            val is_live =
              !(line_no < noise.length && noise(line_no)) &&
                Py.found(pat, live_lines(line_no - 1))
            out += Hit(sec.path, line_no, Py.rstrip(line),
              Usage_Graph.entry_at_line(idx, line_no), is_live, sec.is_thy)
          }
        }
        line_no += 1
      }
    }
    out.toList
  }

  def cmd_grep(out: Out, err: Out, sections: List[Theory_Section], pattern: String,
    flags: Flags
  ): Unit = {
    val pat = compile_user_pattern(err, out, pattern)
    val all_hits = grep_sections(sections, pat)
    val live_hits = all_hits.filter(_.is_live)
    val dead_hits = all_hits.filterNot(_.is_live)
    val hits = if (flags.with_comments) all_hits else live_hits

    if (flags.mode == "count")
      out.println((if (flags.with_comments) all_hits.length else live_hits.length).toString)
    else if (hits.isEmpty)
      out.println(s"No ${if (flags.with_comments) "" else "live "}matches for '$pattern'.")
    else {
      if (flags.with_comments)
        out.println(s"${all_hits.length} match(es) for '$pattern' " +
          s"(${live_hits.length} live, ${dead_hits.length} in comments/text):\n")
      else out.println(s"${live_hits.length} live match(es) for '$pattern':\n")

      val labels = Render.locus_labels(sections)
      val loci = hits.map(h => s"${Render.file_locus(labels, h.path)}:${h.line_no}")
      val loc_w = loci.map(_.length).max
      for ((h, loc) <- hits.zip(loci)) {
        val marker = if (h.is_live) "" else "  [in comment/text]"
        if (!h.is_thy) out.println(s"  ${pad_right(loc, loc_w)}  ${Py.strip(h.text)}$marker")
        else {
          out.println(s"  ${pad_right(loc, loc_w)}  ${owner_field(h.owner, span = false)}$marker")
          if (flags.mode != "names") out.println(s"    ${Py.strip(h.text)}")
        }
      }
    }
  }

  /* A fact name as a complete reference.  A prime-aware boundary, because
     Isabelle allows `'` inside identifiers and `\b` does not; a name written
     with `\<...>` tokens must not glue onto a neighbouring symbol; and a name
     carrying a character that must be QUOTED in source matches only between
     the quotes.

     A PLAIN identifier gets two more lookbehinds, because a plain run sits
     between non-`[\w']` characters when it is the inside of a `\<...>` token
     too: `lambda` matched within `\<lambda>`, `le` within `\<le>` and `sub`
     within `\<^sub>` [symbol-body-tokens].  They are the single-name form of
     what `Usage_Graph.build_call_graph` does by blanking symbol tokens before
     its word pass — a symbol's body is that symbol's name, never a fact's —
     and applying it to one and not the other would make `callers` and
     `unused` disagree about the same lemma.  (A name that itself STARTS with
     `\<` is a symbolic spelling and keeps its own `(?<!>)` guard instead.) */
  private val SPECIAL_NAME_RE: Pattern = Py.compile("""[^\w'\\<>^]""")

  def isa_word_pattern(name: String): String =
    if (Py.found(SPECIAL_NAME_RE, name)) """(?<=")""" + Py.re_escape(name) + """(?=")"""
    else {
      val left = """(?<![\w'])""" +
        (if (name.startsWith("""\<""")) "(?<!>)" else """(?<!\\<)(?<!\\<\^)""")
      val right = (if (name.endsWith(">")) """(?!\\<)""" else "") + """(?![\w'])"""
      left + Py.re_escape(name) + right
    }

  def cmd_sorry(out: Out, sections: List[Theory_Section], count_only: Boolean): Unit = {
    val pat = Py.compile(isa_word_pattern("sorry"))
    val hits = grep_sections(sections, pat).filter(_.is_live)
    if (count_only) out.println(hits.length.toString)
    else if (hits.isEmpty) out.println("No sorries.")
    else {
      val labels = Render.locus_labels(sections)
      val loci = hits.map(h => s"${Render.file_locus(labels, h.path)}:${h.line_no}")
      val loc_w = loci.map(_.length).max
      for ((h, loc) <- hits.zip(loci))
        out.println(s"  ${pad_right(loc, loc_w)}  " +
          owner_field(h.owner, span = false))
      out.println(s"${hits.length} sorr${if (hits.length == 1) "y" else "ies"}")
    }
  }


  /* ------------------------------------------------------------------ */
  /* lines / largest                                                    */
  /* ------------------------------------------------------------------ */

  def cmd_lines(out: Out, err: Out, source_lines: Array[String],
    ranges: List[String]
  ): Unit = {
    val parsed = ranges.map(r => (r, parse_line_range(r)))
    for ((spec, p) <- parsed if p.isEmpty) {
      out.flush()
      err.println(s"ERROR: invalid range '$spec': require 1 <= start <= end")
      throw Exit_Code(2)
    }
    val n_lines = source_lines.length
    val resolved =
      parsed.map { p =>
        val (a, b) = p._2.get     // the None case exited above
        (a, b.getOrElse(n_lines), b.isEmpty)
      }
    val max_no = if (resolved.isEmpty) 1 else resolved.map(_._2).max
    val width = (max_no min n_lines).toString.length
    var i = 0
    for ((a, b, open_end) <- resolved) {
      if (i > 0) out.println("--")
      i += 1
      val disp = if (open_end) s"$a.." else s"$a..$b"
      val a_clamped = 1 max a
      val b_clamped = n_lines min b
      if (a_clamped > n_lines) {
        out.flush()
        err.println(s"# range $disp: past end of file ($n_lines lines)")
      }
      else {
        var nr = a_clamped
        while (nr <= b_clamped) {
          out.println(s"${pad_left(nr.toString, width)}| ${source_lines(nr - 1)}")
          nr += 1
        }
        if (b > n_lines) {
          out.flush()
          err.println(s"# range $disp: truncated at line $n_lines")
        }
      }
    }
  }

  def cmd_largest(out: Out, sections: List[Theory_Section], top: Int): Unit = {
    val rows =
      for (s <- sections; e <- s.entries if e.thy_line > 0) yield (e.line_count, e, s)
    val ordered = rows.sortBy(-_._1)
    if (ordered.isEmpty) out.println("No entries found.")
    else {
      /* Qualified against the LOADED CORPUS, not against the rows on screen
         [disambig-names].  Scoping to the shown rows is the tempting reading
         and it breaks the round-trip: whether `Examples:11` names one theory
         is a fact about the corpus — nineteen AFP theories are called
         `Examples` — not about which of them a `-N 8` happened to print.  A
         label unique on screen and ambiguous on paste is worse than no label,
         because it invites the paste.  A single-session run still shows bare
         `Bla`, because the qualification is driven by actual collisions. */
      val labels = Render.locus_labels(sections)
      out.println(s"Top ${top min ordered.length} largest entries:\n")
      out.println(s"${pad_left("Lines", 6)}  ${pad_right("Tag", 8)}  " +
        s"${pad_right("Name", 42)}  Theory  (span)")
      out.println(s"${"-" * 6}  ${"-" * 8}  ${"-" * 42}  ------")
      for ((size, e, s) <- ordered.take(top max 0))
        out.println(s"${pad_left(size.toString, 6)}  ${pad_right(e.tag, 8)}  " +
          s"${pad_right(e.name, 42)}  ${labels.getOrElse(s.path, s.theory)}  " +
          s"(${e.src_start}..${e.thy_end})")
    }
  }
}
