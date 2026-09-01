/*  Title:      query_base/src/render.scala

Turning entries into the text the tool prints.

Above `entries` and below `commands`: pure formatting, no index loading and no
argument parsing.  Every match-listing command funnels through `emit_matches`,
so the four verbosity modes (first / all / names / count) cannot drift apart
command by command, and every located line names its owner through
`Commands.owner_field`.

The extent annotation, the preamble and annotation previews, and the
single-entry renderer are the reference implementation's `render.py` — its
output IS the interface, so the shapes here are transcriptions, not choices.
*/

package isabelle.query


import isabelle.*

import java.util.regex.Pattern

import scala.collection.mutable


object Render {
  /* Lines carrying LaTeX figure / typesetting markup, skipped in a truncated
     preview of a document block: a tikzpicture is not a summary of anything. */
  val LATEX_LINE_RE: Pattern = Py.compile(
    """\\(begin|end|caption|node|draw|newlength|newcommand|settowidth|settoheight|scalebox|label)\b""")

  /* Written as code points, not as literals: the compiler's source encoding
     is not ours to assume, and these three characters are load-bearing
     output (U+2014 EM DASH, U+25B8 SMALL TRIANGLE, U+2192 RIGHTWARDS ARROW). */
  val EM_DASH: String = 0x2014.toChar.toString
  val TRIANGLE: String = 0x25b8.toChar.toString
  val ARROW: String = 0x2192.toChar.toString

  /* The enclosing locale/class as a scope step — rendered by the caller as
     `THEORY ▸ context hpk`.  An explicit `(in foo)` prints without a kind
     because the source does not say whether `foo` is a locale or a class. */
  def format_target(e: Entry): String =
    if (e.in_target.nonEmpty) "target " + e.in_target
    else e.blocks.map(kn => kn._1 + " " + kn._2).mkString(" " + TRIANGLE + " ")

  /* `[src A..B, N lines]`, widened to name the BODY span whenever that is
     narrower at either end — a leading doc block or a trailing inter-lemma
     block.  The body end is the safe cut boundary; `src` is where the next
     entry-or-section starts after. */
  def format_extent(e: Entry): String =
    if (e.thy_line == 0) ""
    else {
      val src_start = e.src_start
      val span_size = e.line_count
      val body_end = if (e.body_end_line != 0) e.body_end_line else e.thy_end
      if (src_start < e.thy_line || body_end < e.thy_end) {
        val body_size = body_end - e.thy_line + 1
        s"[src $src_start..${e.thy_end}, body ${e.thy_line}..$body_end, " +
          s"$body_size/$span_size lines]"
      }
      else s"[src $src_start..${e.thy_end}, $span_size lines]"
    }

  def format_name_line(sec: Theory_Section, e: Entry): String = {
    val ext = format_extent(e)
    val span = if (ext.nonEmpty) " " + ext else ""
    s"${e.name} (${e.tag}) $EM_DASH ${sec.theory}$span"
  }

  def is_latex_noise(line: String): Boolean = Py.found(LATEX_LINE_RE, line)

  private val TEXT_WRAPPER_RE: Pattern =
    Py.compile("""^(\s*)(?:text_raw|text)\s*\\<open>\s*(.*)$""")

  /* A document block's body without its `text \<open>` … `\<close>` wrapper,
     so a preview shows prose rather than syntax. */
  def strip_text_wrapper(lines0: List[String]): List[String] = {
    if (lines0.isEmpty) lines0
    else {
      var out = lines0
      Py.matches_at_start(TEXT_WRAPPER_RE, out.head) match {
        case Some(m) =>
          val first = Py.rstrip(m.group(1) + m.group(2))
          out = if (first.nonEmpty) first :: out.tail else out.tail
        case None =>
      }
      if (out.isEmpty) out
      else {
        val last = Py.rstrip(out.last)
        if (last.endsWith("""\<close>""")) {
          val trimmed = Py.rstrip(last.substring(0, last.length - """\<close>""".length))
          out = if (trimmed.nonEmpty) out.init :+ trimmed else out.init
        }
        out
      }
    }
  }

  /* Up to `n` non-blank, non-LaTeX lines from the start, plus how many of the
     ORIGINAL lines the preview never reached. */
  def truncate_preview(lines: List[String], n: Int, skip_latex: Boolean = true
  ): (List[String], Int) =
    if (n <= 0) (Nil, lines.length)
    else {
      val out = new mutable.ListBuffer[String]
      var consumed = 0
      val it = lines.iterator
      while (it.hasNext && out.length < n) {
        val line = it.next()
        consumed += 1
        if (Py.strip(line).nonEmpty && !(skip_latex && is_latex_noise(line))) out += line
      }
      (out.toList, (lines.length - consumed) max 0)
    }

  def render_preamble(sec: Theory_Section, preamble: (Int, Int), mode: String,
    context: Int
  ): String = {
    val (start, end) = preamble
    val body = strip_text_wrapper(sec.slice(start, end).toList)
    val block_size = body.length
    if (mode == "full") body.mkString("\n")
    else {
      val (preview, _) = truncate_preview(body, context)
      val remaining = block_size - preview.length
      if (remaining == 1) body.mkString("\n")
      else if (remaining > 0)
        preview.mkString("\n") +
          s"\n  [+$remaining more preamble lines, use --comments-only or -V to see]"
      else preview.mkString("\n")
    }
  }

  val annotation_kinds: List[String] = List("decl", "statement", "proof")

  private val kind_labels: Map[String, String] =
    Map("decl" -> "declaration", "statement" -> "statement", "proof" -> "proof")

  /* `summary`: the first `context` notes flat, beside the statement.
     `full`: every note, GROUPED by which part of the entry it annotates — in
     the dedicated prose view the grouping IS the content. */
  def render_annotations(annotations: List[(Int, String, String)], context: Int,
    proof_remaining: Int, mode: String
  ): String =
    if (annotations.isEmpty) {
      if (proof_remaining > 0)
        s"  [+$proof_remaining more proof line" + (if (proof_remaining != 1) "s" else "") + "]"
      else ""
    }
    else if (mode == "full") {
      val out = new mutable.ListBuffer[String]
      for (kind <- annotation_kinds) {
        val of_kind = annotations.filter(_._3 == kind)
        if (of_kind.nonEmpty) {
          out += "  " + kind_labels(kind) + ":"
          for ((ln, content, _) <- of_kind) out += s"    | line $ln: $content"
        }
      }
      out.mkString("\n")
    }
    else {
      val out = new mutable.ListBuffer[String]
      val shown = annotations.take(1 max context)
      for ((ln, content, _) <- shown) out += s"  | line $ln: $content"
      if (annotations.length > shown.length) {
        val rest = annotations.length - shown.length
        if (rest == 1) {
          val (ln, content, _) = annotations(shown.length)
          out += s"  | line $ln: $content"
        }
        else
          out += s"  | ...($rest more annotations in $proof_remaining-line proof, " +
            "use -U N to see more)"
      }
      out.mkString("\n")
    }

  /* The declaration slice as one string — what `find --statement` matches. */
  def statement_text(sec: Theory_Section, e: Entry): String =
    if (e.thy_line == 0) e.text
    else sec.slice(e.thy_line, e.decl_end_line).mkString("\n")

  def render_entry(sec: Theory_Section, e: Entry, verbatim: Boolean = false,
    statement: Boolean = false, comments: String = "on", context: Int = 2
  ): String = {
    val ext = format_extent(e)
    val header = s"--- ${e.name} (${e.tag}) $EM_DASH ${sec.theory}.thy $ext ---"

    if (e.thy_line == 0) header + "\n" + e.text
    else if (statement) header + "\n" + sec.slice(e.thy_line, e.decl_end_line).mkString("\n")
    else if (verbatim) header + "\n" + sec.slice(e.thy_line, e.thy_end).mkString("\n")
    else {
      val out = new mutable.ListBuffer[String]

      /* Above the header because that is where the author wrote it, and butted
         straight against it: a match LIST separates entries by one blank line,
         so an unnamed block set off the same way would read as a hit of its
         own. */
      if (comments != "off" && e.preamble.isDefined) {
        val pmode = if (comments == "only") "full" else "summary"
        val rendered = render_preamble(sec, e.preamble.get, pmode, context)
        if (rendered.nonEmpty) {
          val (pstart, pend) = e.preamble.get
          out += s"--- preamble for ${e.name} [$pstart-$pend] ---"
          out += rendered
        }
      }
      out += header

      if (comments == "only") {
        if (e.annotations.nonEmpty) {
          out += """--- annotations (\<comment>) ---"""
          out += render_annotations(e.annotations, context, 0, "full")
        }
        else if (e.preamble.isEmpty) out += "(no comment context for this entry)"
        out.mkString("\n")
      }
      else {
        if (e.proof_line > 0 && e.proof_line >= e.decl_end_line) {
          val stmt = sec.slice(e.thy_line, e.decl_end_line).toList
          /* `lemma a: "P" by simp` puts the proof ON the last statement line,
             which the statement slice has already printed. */
          val first_proof =
            if (e.proof_line <= e.decl_end_line) Nil
            else sec.slice(e.proof_line, e.proof_line).toList
          val proof_end = Entries.proof_extent(sec.source, e.proof_line, e.thy_end)
          val remaining = (proof_end - e.proof_line) max 0
          out += (stmt ::: first_proof).mkString("\n")
          if (comments != "off" && e.annotations.nonEmpty)
            out += render_annotations(e.annotations, context, remaining, "summary")
          else if (remaining == 1)
            out += sec.slice(e.proof_line + 1, e.proof_line + 1).mkString("\n")
          else if (remaining > 0) out += s"  [+$remaining more proof lines]"
        }
        else {
          /* No proof captured — a `definition` lands here and its whole
             declaration is printed, so only the notes BELOW the slice are
             worth previewing. */
          out += sec.slice(e.thy_line, e.decl_end_line).mkString("\n")
          val unseen = e.annotations.filter(_._1 > e.decl_end_line)
          if (comments != "off" && unseen.nonEmpty)
            out += render_annotations(unseen, context, 0, "summary")
        }
        out.mkString("\n")
      }
    }
  }


  /* --- verbosity-mode dispatch --- */

  /* `statement` is the RENDER selector (declaration only), passed explicitly
     rather than read off the flags: on `find`, `--statement` means "match the
     statement slice" and must not bleed into how a match is displayed. */
  def emit_matches(out: Out, by_theory: Map[String, Theory_Section],
    matches: List[Entry], pattern: String, flags: Flags, statement: Boolean = false
  ): Unit = {
    /* Mode dispatch BEFORE the empty guard [count-mode-zero].  A count mode
       must print a number, and the empty case is the one a script most wants
       to branch on: with the sentence first, `$(query find X -c)` was
       arithmetic when X matched and a parse error when it did not.  `names` is
       the same rule — an empty list is the right answer for a pipeline, and a
       sentence on stdout would be read as a name.  Only the human-readable
       modes say so in words. */
    if (flags.mode == "count") out.println(matches.length.toString)
    else if (flags.mode == "names")
      for (e <- matches) out.println(format_name_line(by_theory(e.theory), e))
    else if (matches.isEmpty) out.println(s"No entries matching '$pattern'.")
    else if (flags.mode == "all")
      for (e <- matches) {
        out.println(render_entry(by_theory(e.theory), e, verbatim = flags.verbatim,
          statement = statement, comments = flags.comments, context = flags.context))
        out.println("")
      }
    else {
      val e0 = matches.head
      out.println(render_entry(by_theory(e0.theory), e0, verbatim = flags.verbatim,
        statement = statement, comments = flags.comments, context = flags.context))
      if (matches.length > 1) {
        out.println("")
        out.println(s"[+${matches.length - 1} more match(es).  Use --all to show, " +
          "--names for a list, --count for just the count.]")
      }
    }
  }
}
