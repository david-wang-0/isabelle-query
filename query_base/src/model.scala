/*  Title:      query_base/src/model.scala

The parsed representation of a theory: entries, their spans, and the three
views of the source every scanner above reads.

Bottom of the engine's dependency chain — `model` → `regions` → `entries` →
`discovery` → the tool — so nothing here may reach upwards.

The three views all have the SAME line count and the SAME line lengths as the
source; a redacted character becomes one space.  That is the whole contract: a
line number and a column index mean the same thing in every view, so a scanner
switches view and changes nothing else about itself.

  source        as written; what a display must print.
  live_source   comments, `\<^cancel>` regions, `\<comment>` notes and ML
                bodies blanked.  Terms are KEPT — the `mono` in
                `lemma "mono f"` is a real citation.
  outer_source  the above plus every term, string and cartouche blanked, so
                what is left is exactly Isar's command position.
*/

package isabelle.query


import isabelle.*

import java.nio.file.{Path => JPath}

import scala.collection.mutable


object Model {
  /* Length-preserving blanking: each half-open [lo, hi) column range becomes
     spaces.  Spans arrive sorted; the clamping is written not to rely on it. */
  def blank_spans(line: String, spans: List[(Int, Int)]): String = {
    val buf = new StringBuilder
    var prev = 0
    for ((lo0, hi0) <- spans) {
      val lo = lo0 max prev
      val hi = hi0 min line.length
      if (hi > lo) {
        buf ++= line.substring(prev, lo)
        buf ++= " " * (hi - lo)
        prev = hi
      }
    }
    buf ++= line.substring(prev min line.length)
    buf.toString
  }

  /* The flat form, walked in place: a line with no spans is returned as it
     stands, and one with spans is rebuilt without ever materialising a list.
     `spans` may be shorter than `lines` only in the degenerate empty case. */
  def blank_all(lines: Array[String], spans: Regions.Spans): Array[String] = {
    val out = new Array[String](lines.length)
    val n = spans.bound.length - 1
    var i = 0
    while (i < lines.length) {
      out(i) =
        if (i >= n || spans.is_empty(i)) lines(i)
        else {
          val line = lines(i)
          val buf = new StringBuilder
          var prev = 0
          spans.each(i) { (lo0, hi0) =>
            val lo = lo0 max prev
            val hi = hi0 min line.length
            if (hi > lo) {
              buf ++= line.substring(prev, lo)
              buf ++= " " * (hi - lo)
              prev = hi
            }
          }
          buf ++= line.substring(prev min line.length)
          buf.toString
        }
      i += 1
    }
    out
  }
}


/* One declaration.  `bindings` are the ADDITIONAL names the same command binds
   (see SCANNING.md "The names one declaration binds"); they are deliberately
   not separate entries, because one command has one span. */
case class Entry(
  tag: String,
  name: String,
  text: String,
  thy_line: Int,
  var decl_end_line: Int = 0,
  var proof_line: Int = 0,
  var thy_end: Int = 0,
  var body_end_line: Int = 0,
  var theory: String = "",
  var preamble: Option[(Int, Int)] = None,
  var annotations: List[(Int, String, String)] = Nil,
  var bindings: List[(String, String)] = Nil,
  var blocks: List[(String, String)] = Nil,
  var in_target: String = ""
) {
  /* An explicit `(in foo)` wins over lexical nesting: the modifier RETARGETS
     the declaration, which is what Isabelle does. */
  def target: String =
    if (in_target.nonEmpty) in_target
    else blocks.lastOption.map(_._2).getOrElse("")

  def bound_names: List[String] = bindings.map(_._1)

  /* The leading `text` preamble documents THIS entry, so it counts as part of
     this entry's extent (and is excluded from the preceding entry's end). */
  def src_start: Int = preamble match {
    case Some((s, _)) => s
    case None => thy_line
  }

  def line_count: Int = if (thy_line > 0) thy_end - src_start + 1 else 0
}


class Theory_Section(
  val theory: String,
  val path: JPath,
  val entries: List[Entry],
  lines0: Array[String],
  val regions: Regions.Result,
  val outline: List[(String, String, Int)] = Nil,
  val text_blocks: List[(Int, Int)] = Nil,
  val heading_spans: List[(Int, Int)] = Nil,
  val comment_ranges: List[(Int, Int)] = Nil,
  val nonisar_ranges: List[(Int, Int)] = Nil,
  val session: Option[String] = None,
  /* False for a non-`.thy` path handed to `grep` as a trailing positional: a
     Markdown memo has no Isabelle entries, so the search degrades to plain
     line matching with no owner column and no live/prose classification. */
  val is_thy: Boolean = true
) {
  /* An inclusive 1-indexed line window from a grep `PATH:A..B` positional, an
     open upper bound left for the section to resolve to its own length.  A
     property of THIS load of the section, not of the theory, so it is a var
     the routing sets rather than a constructor argument every caller passes. */
  var line_window: Option[(Int, Option[Int])] = None

  /* THE SOURCE, AS ONE STRING PLUS OFFSETS, not one String per line.
     `lines0` is read here and never retained.

     A resident index holds this for every theory in the project, and one
     `String` per line is the second-largest thing it holds: on `src/HOL`'s
     838k lines that was ~20 MB of `String` objects and ~13 MB of `byte[]`
     headers, for 34 MB of actual text.  One String and one `Int` array is the
     same text plus 3.4 MB.

     What that trades is a materialisation each time `lines` is read.  It is
     the right trade because the array is TRANSIENT — allocated, walked once by
     a scanner, and collected — where the per-line Strings were RETAINED for the
     life of the index.  The discipline it needs is the one `live_source` below
     already documents and every caller already follows: bind the result to a
     local, never call it in a loop. */
  val text: String = lines0.mkString("\n")

  /* `starts(i)` is where line `i` begins; `starts(i + 1) - 1` is where it ends,
     the `- 1` being the separator `mkString` put there.  Length n + 1 so the
     last line needs no special case. */
  val starts: Array[Int] = {
    val n = lines0.length
    val a = new Array[Int](n + 1)
    var i = 0
    var at = 0
    while (i < n) { a(i) = at; at += lines0(i).length + 1; i += 1 }
    a(n) = at
    a
  }

  def thy_lines: Int = starts.length - 1

  /* One line, without building the rest.  For a scanner that wants every line,
     `lines` below is cheaper than calling this in a loop — it walks the offsets
     once instead of bounds-checking each. */
  def line(i: Int): String = text.substring(starts(i), starts(i + 1) - 1 max starts(i))

  def lines: Array[String] = {
    val n = thy_lines
    val out = new Array[String](n)
    var i = 0
    while (i < n) { out(i) = text.substring(starts(i), (starts(i + 1) - 1) max starts(i)); i += 1 }
    out
  }

  def source: Array[String] = lines

  /* COMPUTED, NOT CACHED — and every caller binds the result to a local rather
     than calling this in a loop.  A cached view is a second full copy of the
     corpus text held for the life of the process, which at whole-AFP scale is
     gigabytes for something each consumer reads exactly once (the call graph,
     the method census, `grep`, `callers`).  The one-call-per-section discipline
     is what makes a `def` the cheaper shape here; a per-entry caller would need
     the cache back. */
  def live_source: Array[String] = Model.blank_all(lines, regions.nonisar)
  def outer_source: Array[String] = Model.blank_all(lines, regions.inner)

  /* 1-indexed inclusive line range. */
  def slice(start: Int, end: Int): Array[String] =
    lines.slice((start - 1) max 0, end min lines.length)
}
