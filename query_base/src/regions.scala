/*  Title:      query_base/src/regions.scala

Which characters of a theory are live Isar text, decided by Isabelle's own
outer-syntax lexer.

`Token.explode` already knows everything a region scan needs — that `(* *)`
comments nest, that a `(*` inside a `"..."` term is multiplication and opens
nothing, that a cartouche is ONE token however deeply it nests, that all four
of `\<comment>`, `\<^cancel>`, `\<^latex>` and `\<^marker>` plus the cartouche
each owns are formal comments — so this module classifies tokens rather than
re-deriving the lexical grammar.  The classification is the only judgement
left:

  live    ordinary outer syntax, and the terms inside it.
  inner   not a place a COMMAND can start: strings, cartouches, control
          cartouches — kept in `live_source`, blanked in `outer_source`.
  noise   not live Isar text at all: comments, cancelled regions, formal
          comments of every spelling, ML bodies — blanked in both views.

**All four formal comments are noise, and none of them is a term.**  The
earlier reading was that `\<^latex>` and `\<^marker>` bodies are still
*document* text rather than *deleted* text, so they should redact like a term
(inner) rather than like a comment (noise).  That confuses what the body says
with where it stands: none of the four is live Isar, Isabelle's lexer skips
all four wherever a token may appear, and a consumer reading `live_source`
is asking what the theory ASSERTS — to which a document tag contributes
nothing.  Keeping two of them live meant `grep important` reported seven live
matches in `\<^marker>\<open>tag important\<close>` bodies, `callers` counted a
document tag as a citation, and every line-granular rule built on
`nonisar_ranges` — the pre-name lookahead, the declaration-body scan — could
not see that a `\<^marker>` line holds nothing.  One list, `redacting_markers`,
and the name grammar in `entries` reads the same four from
`annotation_markers`, so the two views cannot disagree about what a marker is.

One judgement is genuinely not the lexer's to make, and it is decided by the
COMMAND in front of the token, exactly as `text` prose is: a cartouche is an
ML body when its command is one (`ML`, `method_setup`, …).  ML has its own
namespace, so an identifier there cites no Isabelle fact — and an ML `fun` is
not an Isabelle `fun`.

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

  /* Isabelle's FOUR formal comments — a marginal note, deleted text, raw LaTeX
     and a document-build marker — each of which OWNS the cartouche after it.
     Listed once and used three times: the classification below, the recovery
     scan inside a delimited token, and (via `Entries.annotation_markers`, the
     same four) the name grammar.  A marker missing from one of the three is a
     view that disagrees with the others about what a marker is. */
  private val redacting_markers: List[String] =
    (note_markers ::: List(Symbol.cancel, Symbol.cancel_decoded,
      Symbol.latex, Symbol.latex_decoded, Symbol.marker, Symbol.marker_decoded)).distinct

  /* A marker plus the cartouche it owns, matched as one token — the same
     shape, and the same same-line rule, the lexer applies at outer level.
     Built from the list above rather than respelled: a marker this pattern
     does not know about is never recovered inside a term, silently. */
  private val MARKER_OPEN =
    Py.compile("(?:" + redacting_markers.map(Pattern.quote).mkString("|") + ")[ \\t]*(?:" +
      cart_open.map(Pattern.quote).mkString("|") + ")")

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

  /* PER-LINE COLUMN SPANS, FLAT.  `bound(i) until bound(i+1)` indexes `lo` and
     `hi`; a line with no spans is two equal bounds and costs nothing beyond its
     4-byte slot.

     This used to be `Array[List[(Int, Int)]]`, and on a resident index that is
     the single most expensive thing the engine keeps.  Measured on `src/HOL`
     (838k lines, 452k spans) by heap histogram: 17.3 MB of `Tuple2$mcII$sp`,
     17.2 MB of cons cells, and 12.8 MB of per-line array slots that exist
     whether or not a line has any spans — 47 MB of a 155 MB index, or 35%, for
     904k integers.  Flat, the same information is `bound` (one int per line)
     plus `lo`/`hi` (one int per span): about 10 MB.

     The tuples were already specialised (`$mcII$sp`), so there was never any
     `Integer` boxing to remove; the cost is object headers and cons cells, and
     only a flat encoding removes those.  See `todo.md [index-footprint]`. */
  final class Spans(val bound: Array[Int], val lo: Array[Int], val hi: Array[Int]) {
    def is_empty(i: Int): Boolean = bound(i) == bound(i + 1)

    /* The one iteration primitive.  A callback rather than an `Iterator`
       because an iterator here would allocate per line, which is the cost this
       whole representation exists to avoid. */
    inline def each(i: Int)(f: (Int, Int) => Unit): Unit = {
      var k = bound(i)
      val end = bound(i + 1)
      while (k < end) { f(lo(k), hi(k)); k += 1 }
    }

    /* For the few places that genuinely want the old shape (diagnostics, and
       `Model.blank_spans`'s single-line form).  Allocates; not for loops. */
    def at(i: Int): List[(Int, Int)] = {
      var out: List[(Int, Int)] = Nil
      var k = bound(i + 1) - 1
      while (k >= bound(i)) { out = (lo(k), hi(k)) :: out; k -= 1 }
      out
    }
  }

  val empty_spans: Spans = new Spans(Array(0), Array.empty, Array.empty)

  final class Result(
    val nonisar: Spans,                     // 0-indexed by line, half-open columns
    val inner: Spans,
    val open_at: Array[Boolean],            // line BEGAN inside a delimited region
    val notes: Spans                        // columns a genuine \<comment> opens at,
                                            // encoded as zero-width spans (lo == hi)
  )

  val empty_result: Result =
    new Result(empty_spans, empty_spans, Array.empty, empty_spans)


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

    /* A formal comment whose cartouche never closes.  The lexer cannot pair
       them — `\<comment> \<open>...` with no `\<close>` comes back as a
       SYM_IDENT, a space and a `bad input` token rather than one
       FORMAL_COMMENT — so the pairing is made here, over the same
       marker-then-cartouche shape and the same same-line rule the lexer
       applies when it succeeds.  Without it the recovered cartouche is read as
       a TERM, which leaves the rest of the file live: an unterminated note
       before a declaration's name then hands `lookahead_name` its prose, and
       the guard that bounds that walk cannot fire on lines it thinks are
       source.  A marker NOT followed by a cartouche is left exactly as the
       lexer classified it, which costs nothing — it is live either way. */
    var pending = -1        // offset of a bare marker awaiting its cartouche
    var pending_note = false

    var offset = 0
    for (tok <- tokens) {
      val len = tok.source.length
      if (len > 0) {
        val start = offset
        val stop = offset + len
        val line = line_of(start)
        val col = start - line_starts(line)
        val paired =
          pending >= 0 && tok.kind == Token.Kind.ERROR && starts_with_any(tok.source, cart_open)
        if (paired) {
          add(nonisar, pending, stop)
          add(inner, pending, stop)
          mark_open(pending, stop)
          if (pending_note) {
            val ln = line_of(pending)
            notes(ln) += (pending - line_starts(ln))
          }
        }
        else classify(tok, lines, line, col) match {
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
        if (redacting_markers.contains(tok.source)) {
          pending = start
          pending_note = note_markers.contains(tok.source)
        }
        /* Only horizontal space may stand between a marker and its cartouche,
           as `MARKER_OPEN` says at outer level. */
        else if (!(tok.is_space && !tok.source.contains('\n'))) pending = -1
      }
      offset += len
    }

    /* Sorted by start column, as the `ListBuffer` form was: `Model.blank_spans`
       walks them in order and clamps against the previous end. */
    def freeze(buf: Array[mutable.ListBuffer[(Int, Int)]]): Spans = {
      val bound = new Array[Int](n + 1)
      var total = 0
      var i = 0
      while (i < n) { total += buf(i).length; i += 1; bound(i) = total }
      val lo = new Array[Int](total)
      val hi = new Array[Int](total)
      i = 0
      while (i < n) {
        var k = bound(i)
        for ((a, b) <- buf(i).sortBy(_._1)) { lo(k) = a; hi(k) = b; k += 1 }
        i += 1
      }
      new Spans(bound, lo, hi)
    }

    /* `notes` is a set of COLUMNS, not ranges, so it rides the same encoding as
       zero-width spans — one `lo` entry per column, `hi` unused.  A separate
       shape for one field would cost more in code than the ints it saves. */
    def freeze_cols(buf: Array[mutable.TreeSet[Int]]): Spans = {
      val bound = new Array[Int](n + 1)
      var total = 0
      var i = 0
      while (i < n) { total += buf(i).size; i += 1; bound(i) = total }
      val lo = new Array[Int](total)
      i = 0
      var k = 0
      while (i < n) { for (c <- buf(i)) { lo(k) = c; k += 1 }; i += 1 }
      new Spans(bound, lo, lo)
    }

    if (n == 0) empty_result
    else new Result(freeze(nonisar), freeze(inner), open_at, freeze_cols(notes))
  }


  /* Whole lines that hold no live Isar text at all.

     Deliberately conservative: a line with live code OUTSIDE such a region
     (`by simp (* see foo *)`) is NOT reported.  These ranges drive
     line-granular consumers, and reporting that line to them would blank its
     live half and drop a real citation. */
  def nonisar_ranges(lines: Array[String], spans: Regions.Spans): List[(Int, Int)] = {
    val marked = new mutable.ListBuffer[Int]
    var i = 0
    while (i < lines.length) {
      if (!spans.is_empty(i)) {
        val line = lines(i)
        val buf = new StringBuilder
        var prev = 0
        spans.each(i) { (a, b) =>
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
