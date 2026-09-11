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
import java.util.zip.{Deflater, Inflater}

import scala.collection.mutable
import scala.util.control.NonFatal


object Model {
  /* Shared by discovery, parsing and hosts: malformed/unreadable source is
     recoverable, fatal failures and cancellation (including wrappers) are not. */
  private[isabelle] def recoverable(exn: Throwable): Boolean = {
    val seen = new java.util.IdentityHashMap[Throwable, java.lang.Boolean]()
    var current = exn
    while (current != null && seen.put(current, java.lang.Boolean.TRUE) == null) {
      // NonFatal also excludes InterruptedException. Inspect each node directly:
      // Exn.cause/is_interrupt skip intermediate nodes and do not bound cycles.
      if (!NonFatal(current) ||
          current.isInstanceOf[java.util.concurrent.CancellationException]) return false
      current = current.getCause
    }
    true
  }

  /* Immutable, source-only snapshot. Blocks bound decode working storage even
     for an unusually long declaration. UTF-16 code units are encoded directly:
     charset encoders replace lone surrogates, which can occur in an unfinished
     editor buffer. No file path or reload hook exists here. */
  private[query] object Source {
    val block_chars = 32768

    private sealed trait Block {
      def decode: String
      def stored_bytes: Long
    }
    private final class Raw(val decode: String) extends Block {
      def stored_bytes: Long =
        decode.length.toLong * (if (decode.forall(_ <= 255)) 1 else 2)
    }
    private final class Packed(bytes: Array[Byte], chars: Int) extends Block {
      def stored_bytes: Long = bytes.length.toLong
      def decode: String = {
        val inflater = new Inflater
        try {
          inflater.setInput(bytes)
          val raw = new Array[Byte](chars * 2)
          var at = 0
          while (at < raw.length && !inflater.finished()) {
            val n = inflater.inflate(raw, at, raw.length - at)
            require(n > 0, "Invalid source snapshot block")
            at += n
          }
          require(at == raw.length && inflater.finished(), "Truncated source snapshot block")
          val out = new Array[Char](chars)
          var i = 0
          while (i < chars) {
            out(i) = (((raw(2 * i) & 255) << 8) | (raw(2 * i + 1) & 255)).toChar
            i += 1
          }
          new String(out)
        }
        finally inflater.end()
      }
    }

    private def pack(s: String): Block = {
      if (s.length < 1024) new Raw(s)
      else {
        val raw = new Array[Byte](s.length * 2)
        var i = 0
        var latin = true
        while (i < s.length) {
          val c = s.charAt(i)
          if (c > 255) latin = false
          raw(2 * i) = (c.toInt >>> 8).toByte
          raw(2 * i + 1) = c.toByte
          i += 1
        }
        val deflater = new Deflater(Deflater.BEST_SPEED)
        try {
          deflater.setInput(raw)
          deflater.finish()
          // Never retain a compressed representation larger than a compact String.
          val packed = new Array[Byte](if (latin) s.length else raw.length)
          val n = deflater.deflate(packed)
          if (deflater.finished() && n + 32 < packed.length)
            new Packed(java.util.Arrays.copyOf(packed, n), s.length)
          else new Raw(s)
        }
        finally deflater.end()
      }
    }

    def apply(lines: Array[String]): Source = {
      val blocks = new mutable.ArrayBuffer[Block]
      val buf = new java.lang.StringBuilder(block_chars)
      var length = 0
      var utf8_bytes = 0L
      def append(s: String): Unit = {
        var at = 0
        while (at < s.length) {
          val end = (at + block_chars - buf.length) min s.length
          buf.append(s, at, end)
          length += end - at
          at = end
          if (buf.length == block_chars) { blocks += pack(buf.toString); buf.setLength(0) }
        }
      }
      var i = 0
      while (i < lines.length) {
        if (i > 0) { append("\n"); utf8_bytes += 1 }
        val line = lines(i)
        var k = 0
        while (k < line.length) {
          val c = line.charAt(k)
          if (c < 0x80) utf8_bytes += 1
          else if (c < 0x800) utf8_bytes += 2
          else if (Character.isHighSurrogate(c) && k + 1 < line.length &&
              Character.isLowSurrogate(line.charAt(k + 1))) {
            utf8_bytes += 4
            k += 1
          }
          else if (Character.isSurrogate(c)) utf8_bytes += 1 // Java UTF-8 replacement '?'
          else utf8_bytes += 3
          k += 1
        }
        append(line)
        i += 1
      }
      if (buf.length > 0) blocks += pack(buf.toString)
      new Source(blocks.toArray, length, utf8_bytes)
    }
  }

  private[query] final class Source private(
    blocks: Array[Source.Block], val length: Int, val utf8_bytes: Long
  ) {
    /* A reader is request-local, never retained by the section. A scan decodes
       each block once; a snippet decodes only the blocks intersecting its span. */
    final class Reader {
      private var index = -1
      private var decoded = ""
      private def block(k: Int): String = {
        if (k != index) { decoded = blocks(k).decode; index = k }
        decoded
      }
      def append(out: java.lang.StringBuilder, start: Int, end: Int): Unit = {
        require(0 <= start && start <= end && end <= length)
        var at = start
        while (at < end) {
          val s = block(at / Source.block_chars)
          val lo = at % Source.block_chars
          val n = (end - at) min (s.length - lo)
          out.append(s, lo, lo + n)
          at += n
        }
      }
      def slice(start: Int, end: Int): String = {
        require(0 <= start && start <= end && end <= length)
        if (start == end) ""
        else if (start / Source.block_chars == (end - 1) / Source.block_chars) {
          val s = block(start / Source.block_chars)
          val lo = start % Source.block_chars
          s.substring(lo, lo + end - start)
        }
        else {
          val out = new java.lang.StringBuilder(end - start)
          append(out, start, end)
          out.toString
        }
      }
    }
    def stored_bytes: Long = blocks.iterator.map(_.stored_bytes).sum
    def reader: Reader = new Reader
    def text: String = reader.slice(0, length)
  }

  /* Python's `Path.resolve()`: symlinks followed, made absolute, and a path
     that does not exist normalised rather than refused.  It sits here, at the
     bottom, because `Theory_Section` caches its own resolved path;
     `Discovery.real` is the name the rest of the tree calls it by. */
  def real(p: JPath): JPath =
    try p.toRealPath()
    catch { case _: Exception => p.toAbsolutePath.normalize }

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
          val chars = line.toCharArray
          var prev = 0
          spans.each(i) { (lo0, hi0) =>
            val lo = lo0 max prev
            val hi = hi0 min chars.length
            if (hi > lo) {
              java.util.Arrays.fill(chars, lo, hi, ' ')
              prev = hi
            }
          }
          new String(chars)
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
  /* The path with symlinks resolved — the identity of the FILE, as against
     `path`, which is the spelling this load reached it by.  Held here and
     computed once because `Render.theory_labels` needs it per section on every
     request, and a resident index answering out of a whole-AFP snapshot would
     otherwise pay ~10k `toRealPath` syscalls each time. */
  lazy val real_path: JPath = Model.real(path)

  /* An inclusive 1-indexed line window from a grep `PATH:A..B` positional, an
     open upper bound left for the section to resolve to its own length.  A
     property of THIS load of the section, not of the theory, so it is a var
     the routing sets rather than a constructor argument every caller passes. */
  var line_window: Option[(Int, Option[Int])] = None

  /* The constructor's lines are consumed, never retained. Source blocks own
     their bytes independently of disk and of subsequent buffer/array edits.
     Full text remains available on demand, but is not a second retained copy. */
  private val snapshot = Model.Source(lines0)
  def text: String = snapshot.text

  /* Logical UTF-8 size preserves host source-byte budgets without decoding.
     Payload storage excludes object headers, offsets, entries and regions;
     it is profiling evidence, not a whole-section heap-size estimate. */
  def source_utf8_bytes: Long = snapshot.utf8_bytes
  def source_storage_bytes: Long = snapshot.stored_bytes

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

  /* Source coordinates are UTF-16 columns, as in the original String-backed
     representation. A read owns at most one decoded block besides its output. */
  def line(i: Int): String =
    snapshot.reader.slice(starts(i), (starts(i + 1) - 1) max starts(i))

  private def materialize(from: Int, until: Int,
    spans: Regions.Spans = Regions.empty_spans
  ): Array[String] = {
    val out = new Array[String]((until - from) max 0)
    val reader = snapshot.reader
    var i = from
    while (i < until) {
      val start = starts(i)
      val end = (starts(i + 1) - 1) max start
      if (i >= spans.bound.length - 1 || spans.is_empty(i))
        out(i - from) = reader.slice(start, end)
      else {
        // Assemble only the visible segments; do not first allocate a full
        // source line which would immediately be copied and discarded.
        val buf = new java.lang.StringBuilder(end - start)
        var prev = 0
        spans.each(i) { (lo0, hi0) =>
          val lo = lo0 max prev
          val hi = hi0 min (end - start)
          if (hi > lo) {
            reader.append(buf, start + prev, start + lo)
            var k = lo
            while (k < hi) { buf.append(' '); k += 1 }
            prev = hi
          }
        }
        reader.append(buf, start + (prev min (end - start)), end)
        out(i - from) = buf.toString
      }
      i += 1
    }
    out
  }

  def lines: Array[String] = materialize(0, thy_lines)
  def source: Array[String] = lines

  /* Computed per request: retaining these views doubles/triples corpus source.
     Whole-source consumers still receive exactly their existing arrays. */
  def live_source: Array[String] = materialize(0, thy_lines, regions.nonisar)
  def outer_source: Array[String] = materialize(0, thy_lines, regions.inner)

  /* 1-indexed inclusive range; allocation depends on the requested extent,
     never on a default snippet window or on the number of unrelated lines. */
  def slice(start: Int, end: Int): Array[String] =
    materialize(((start.toLong - 1) max 0L min thy_lines.toLong).toInt,
      (end max 0) min thy_lines)
}
