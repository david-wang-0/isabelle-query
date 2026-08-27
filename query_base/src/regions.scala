/*  Title:      query_base/src/regions.scala

Which characters of a theory are live Isar text, decided by Isabelle's own
outer-syntax lexer.

`Token.explode` already knows everything a region scan needs — that `(* *)`
comments nest, that a `(*` inside a `"..."` term is multiplication and opens
nothing, that a cartouche is ONE token however deeply it nests, that
`\<comment> \<open>...\<close>` and `\<^cancel>\<open>...\<close>` are formal
comments — so this module classifies tokens rather than re-deriving the lexical
grammar.  The classification is the only judgement left:

  live    ordinary outer syntax, and the terms inside it.
  inner   not a place a COMMAND can start: strings, cartouches, control
          cartouches — kept in `live_source`, blanked in `outer_source`.
  noise   not live Isar text at all: comments, cancelled regions, marginal
          notes, ML bodies — blanked in both views.

Two judgements are not the lexer's to make, and both are decided by the
COMMAND in front of the token, exactly as `text` prose is:

  * a cartouche is an ML body when its command is one (`ML`, `method_setup`,
    …).  ML has its own namespace, so an identifier there cites no Isabelle
    fact — and an ML `fun` is not an Isabelle `fun`.
  * `\<^latex>` and `\<^marker>` are formal comments to the lexer but their
    bodies are still document text rather than deleted text, so they redact
    like a term (inner) rather than like a comment (noise).

Isabelle's inner syntax takes cartouche comments too, so a
`\<comment> \<open>round 1\<close>` written INSIDE a multi-line term is prose
rather than part of the term.  The lexer cannot see it — the term is one token
— so it is recovered by a second, bounded scan over delimited token bodies.
*/

package isabelle.query


import isabelle.*

import java.util.regex.Pattern

import scala.collection.mutable


object Regions {
  /* Commands whose body is an ML cartouche. */
  val ml_body_commands: Set[String] =
    Set("ML", "ML_prf", "ML_val", "ML_command", "ML_export",
      "setup", "local_setup", "declaration", "syntax_declaration",
      "attribute_setup", "method_setup", "simproc_setup", "oracle",
      "parse_translation", "print_translation", "typed_print_translation",
      "parse_ast_translation", "print_ast_translation")

  /* ML brought in by path: no body to redact, but still a command, so it ends
     the span above it.  Kept apart so it never arms a cartouche it does not
     own. */
  val ml_file_commands: Set[String] =
    Set("ML_file", "ML_file_debug", "ML_file_no_debug",
      "SML_file", "SML_export", "SML_import")

  private val LEADING_TOKEN = Py.compile("^\\s*([A-Za-z][A-Za-z_0-9']*)")

  def leads_with_ml(line: String): Boolean =
    Py.matches_at_start(LEADING_TOKEN, line).exists(m => ml_body_commands(m.group(1)))

  /* Is the cartouche at (0-indexed line `i`, column `pos`) an ML command's
     body?  True when the command is on the same line (`ML \<open>`,
     `method_setup foo = \<open>`), or when the cartouche starts its own line
     and the nearest preceding non-blank line is the command. */
  def opens_ml_body(lines: Array[String], i: Int, pos: Int): Boolean = {
    val line = lines(i)
    if (leads_with_ml(line)) true
    else if (!Py.is_blank(line.substring(0, pos min line.length))) false
    else {
      var k = i - 1
      while (k >= 0 && Py.is_blank(lines(k))) k -= 1
      k >= 0 && leads_with_ml(lines(k))
    }
  }


  /* the marker forms, and the cartouche delimiters, in both spellings */

  private val cart_open: List[String] = List("\\<open>", Symbol.open_decoded)
  private val cart_close: List[String] = List("\\<close>", Symbol.close_decoded)

  private val note_markers: List[String] = List(Symbol.comment, Symbol.comment_decoded)
  private val redacting_markers: List[String] =
    note_markers ::: List(Symbol.cancel, Symbol.cancel_decoded)

  /* A marker plus the cartouche it owns, matched as one token — the same
     shape, and the same same-line rule, the lexer applies at outer level. */
  private val MARKER_OPEN =
    Py.compile("""\\<(?:\^cancel|comment)>[ \t]*(?:\\<open>|""" +
      Pattern.quote(Symbol.open_decoded) + ")")

  private def starts_with_any(s: String, prefixes: List[String]): Boolean =
    prefixes.exists(s.startsWith)


  /* classification */

  private val LIVE = 0
  private val INNER = 1
  private val NOISE = 2

  private def classify(tok: Token, lines: Array[String], line: Int, col: Int): Int =
    tok.kind match {
      case Token.Kind.INFORMAL_COMMENT => NOISE
      case Token.Kind.FORMAL_COMMENT =>
        if (starts_with_any(tok.source, redacting_markers)) NOISE else INNER
      case Token.Kind.STRING | Token.Kind.ALT_STRING | Token.Kind.CONTROL => INNER
      case Token.Kind.CARTOUCHE =>
        if (opens_ml_body(lines, line, col)) NOISE else INNER
      case Token.Kind.ERROR =>
        /* an unterminated delimiter: recovered as the delimited thing it began */
        val s = tok.source
        if (s.startsWith("(*")) NOISE
        else if (starts_with_any(s, redacting_markers)) NOISE
        else if (s.startsWith("\"") || s.startsWith("`")) INNER
        else if (starts_with_any(s, cart_open))
          (if (opens_ml_body(lines, line, col)) NOISE else INNER)
        else LIVE
      case _ => LIVE
    }


  /* result */

  final class Result(
    val nonisar: Array[List[(Int, Int)]],   // 0-indexed by line, half-open columns
    val inner: Array[List[(Int, Int)]],
    val open_at: Array[Boolean],            // line BEGAN inside a delimited region
    val notes: Array[Set[Int]]              // columns a genuine \<comment> opens at
  )

  val empty_result: Result =
    new Result(Array.empty, Array.empty, Array.empty, Array.empty)


  /* scan */

  def scan(text: String, lines: Array[String], line_starts: Array[Int],
    keywords: Keyword.Keywords = Keyword.Keywords.empty
  ): Result = {
    val n = lines.length
    val nonisar = Array.fill(n)(new mutable.ListBuffer[(Int, Int)])
    val inner = Array.fill(n)(new mutable.ListBuffer[(Int, Int)])
    val open_at = new Array[Boolean](n)
    val notes = Array.fill(n)(new mutable.TreeSet[Int]())

    def line_of(offset: Int): Int = {
      var lo = 0
      var hi = n - 1
      var res = 0
      while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (line_starts(mid) <= offset) { res = mid; lo = mid + 1 } else hi = mid - 1
      }
      res
    }

    /* [a, b) as per-line column spans, plus the "began inside" flags */
    def add(buf: Array[mutable.ListBuffer[(Int, Int)]], a: Int, b: Int): Unit =
      if (b > a && n > 0) {
        var i = line_of(a)
        val last = line_of(b - 1)
        while (i <= last) {
          val base = line_starts(i)
          val lo = (a - base) max 0
          val hi = (b - base) min lines(i).length
          if (hi > lo) buf(i) += ((lo, hi))
          i += 1
        }
      }

    def mark_open(a: Int, b: Int): Unit =
      if (b > a && n > 0) {
        var i = line_of(a) + 1
        while (i < n && line_starts(i) < b) { open_at(i) = true; i += 1 }
      }

    /* A marginal note or cancelled region written INSIDE a term or string,
       which the lexer hands over as one opaque token.  Bounded to the token's
       own extent, and it does not recurse: a marker inside a marker's body is
       part of that body, exactly as the outer lexer treats it. */
    def scan_nested(src: String, base: Int, limit: Int): Unit = {
      val m = MARKER_OPEN.matcher(src)
      var from = 0
      while (from <= src.length && m.find(from)) {
        val start = m.start()
        var i = m.end()
        var depth = 1
        var done = -1
        while (done < 0 && i < src.length) {
          val op = cart_open.find(src.startsWith(_, i))
          val cl = if (op.isEmpty) cart_close.find(src.startsWith(_, i)) else None
          if (op.isDefined) { depth += 1; i += op.get.length }
          else if (cl.isDefined) {
            depth -= 1
            i += cl.get.length
            if (depth <= 0) done = i
          }
          else i += 1
        }
        val end = if (done >= 0) done else src.length
        add(nonisar, base + start, (base + end) min limit)
        if (starts_with_any(src.substring(start), note_markers)) {
          val abs = base + start
          val ln = line_of(abs)
          notes(ln) += (abs - line_starts(ln))
        }
        from = end max (m.end())
      }
    }

    val tokens =
      try Token.explode(keywords, text)
      catch { case ERROR(_) => Nil }

    var offset = 0
    for (tok <- tokens) {
      val len = tok.source.length
      if (len > 0) {
        val start = offset
        val stop = offset + len
        val line = line_of(start)
        val col = start - line_starts(line)
        classify(tok, lines, line, col) match {
          case NOISE =>
            add(nonisar, start, stop)
            add(inner, start, stop)
            mark_open(start, stop)
            if (starts_with_any(tok.source, note_markers)) notes(line) += col
          case INNER =>
            add(inner, start, stop)
            mark_open(start, stop)
            scan_nested(tok.source, start, stop)
          case _ =>
        }
      }
      offset += len
    }

    def freeze(buf: Array[mutable.ListBuffer[(Int, Int)]]): Array[List[(Int, Int)]] =
      buf.map(b => if (b.isEmpty) Nil else b.toList.sortBy(_._1))

    new Result(freeze(nonisar), freeze(inner), open_at, notes.map(_.toSet))
  }


  /* Whole lines that hold no live Isar text at all.

     Deliberately conservative: a line with live code OUTSIDE such a region
     (`by simp (* see foo *)`) is NOT reported.  These ranges drive
     line-granular consumers, and reporting that line to them would blank its
     live half and drop a real citation. */
  def nonisar_ranges(lines: Array[String], spans: Array[List[(Int, Int)]]): List[(Int, Int)] = {
    val marked = new mutable.ListBuffer[Int]
    var i = 0
    while (i < lines.length) {
      val sp = spans(i)
      if (sp.nonEmpty) {
        val line = lines(i)
        val buf = new StringBuilder
        var prev = 0
        for ((a, b) <- sp) {
          if (a > prev) buf ++= line.substring(prev min line.length, a min line.length)
          prev = prev max b
        }
        if (prev < line.length) buf ++= line.substring(prev)
        if (Py.is_blank(buf.toString)) marked += (i + 1)
      }
      i += 1
    }
    val out = new mutable.ListBuffer[(Int, Int)]
    for (ln <- marked) {
      /* Coalesce across an intervening blank run: a blank line inside an ML
         body carries no citation and no command either way, and a short range
         list keeps the membership tests above cheap. */
      if (out.nonEmpty && ((out.last._2 + 1) until ln).forall(k => Py.is_blank(lines(k - 1))))
        out(out.length - 1) = (out.last._1, ln)
      else out += ((ln, ln))
    }
    out.toList
  }
}
