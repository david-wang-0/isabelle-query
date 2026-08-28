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

  def blank_all(lines: Array[String], spans: Array[List[(Int, Int)]]): Array[String] = {
    val out = new Array[String](lines.length)
    var i = 0
    while (i < lines.length) {
      val sp = if (i < spans.length) spans(i) else Nil
      out(i) = if (sp.isEmpty) lines(i) else blank_spans(lines(i), sp)
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
  val lines: Array[String],
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

  def thy_lines: Int = lines.length

  private lazy val live: Array[String] = Model.blank_all(lines, regions.nonisar)
  private lazy val outer: Array[String] = Model.blank_all(lines, regions.inner)

  def source: Array[String] = lines
  def live_source: Array[String] = live
  def outer_source: Array[String] = outer

  /* 1-indexed inclusive line range. */
  def slice(start: Int, end: Int): Array[String] =
    lines.slice((start - 1) max 0, end min lines.length)
}
