/*  Title:      query_base/src/py_text.scala

Python-compatible text primitives.

The reference implementation this engine must reproduce is written in Python,
and three of its lexical conventions are observable in the output rather than
being implementation detail:

  * `str.splitlines()` breaks on eleven characters, not on `\n` alone, so the
    line NUMBERS a record reports depend on it;
  * `str.strip()` strips every character `str.isspace()` accepts (including
    NBSP), which Java's `String.trim` and `String.strip` both get wrong at the
    edges;
  * `re` on `str` is Unicode-aware by default, so `\w` and `\s` there mean what
    `Pattern.UNICODE_CHARACTER_CLASS` means here and not what Java's ASCII
    defaults mean.

Named once, here, so no scanner above has to remember them.
*/

package isabelle.query


import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable


object Py {
  /* whitespace — Python's str.isspace() */

  private val VT = 0x0b       // vertical tab
  private val FF = 0x0c       // form feed
  private val FS = 0x1c       // file / group / record / unit separators: Python
  private val US = 0x1f       // calls all four whitespace, Unicode does not
  private val NEL = 0x85      // next line
  private val LS = 0x2028     // line separator
  private val PS = 0x2029     // paragraph separator

  def is_space(c: Char): Boolean =
    (c >= '\t' && c <= '\r') || c == ' ' ||
      (c >= FS && c <= US) || c == NEL ||
      Character.isSpaceChar(c)

  def strip(s: String): String = {
    var a = 0
    var b = s.length
    while (a < b && is_space(s.charAt(a))) a += 1
    while (b > a && is_space(s.charAt(b - 1))) b -= 1
    if (a == 0 && b == s.length) s else s.substring(a, b)
  }

  def lstrip(s: String): String = {
    var a = 0
    while (a < s.length && is_space(s.charAt(a))) a += 1
    if (a == 0) s else s.substring(a)
  }

  def rstrip(s: String): String = {
    var b = s.length
    while (b > 0 && is_space(s.charAt(b - 1))) b -= 1
    if (b == s.length) s else s.substring(0, b)
  }

  def is_blank(s: String): Boolean = {
    var i = 0
    while (i < s.length) {
      if (!is_space(s.charAt(i))) return false
      i += 1
    }
    true
  }


  /* line splitting — Python's str.splitlines(), after universal-newline decoding

     `read_text()` translates \r\n and \r to \n before splitlines() ever runs,
     so the two rules compose to: break on \r\n as one, and on each of
     \n \r \v \f \x1c \x1d \x1e \x85    .  A trailing break yields no
     final empty line, which is why a theory's line COUNT — `thy_end` for the
     last entry of a file — comes out the same either way.
  */

  def is_line_break(c: Char): Boolean =
    c == '\n' || c == '\r' || c == VT || c == FF ||
      (c >= FS && c <= 0x1e) || c == NEL || c == LS || c == PS

  /* Lines, plus the offset each starts at in `text` — the offsets are what maps
     a token's character range back onto (line, column). */
  def split_lines(text: String): (Array[String], Array[Int]) = {
    val lines = new mutable.ArrayBuffer[String]
    val starts = new mutable.ArrayBuffer[Int]
    val n = text.length
    var start = 0
    var i = 0
    while (i < n) {
      val c = text.charAt(i)
      if (is_line_break(c)) {
        lines += text.substring(start, i)
        starts += start
        i += (if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') 2 else 1)
        start = i
      }
      else i += 1
    }
    if (start < n) { lines += text.substring(start); starts += start }
    (lines.toArray, starts.toArray)
  }


  /* regex — Python `re` semantics on str */

  def compile(s: String): Pattern = Pattern.compile(s, Pattern.UNICODE_CHARACTER_CLASS)

  /* Python's pattern.match(s): anchored at the start, not anchored at the end. */
  def matches_at_start(p: Pattern, s: CharSequence): Option[Matcher] = {
    val m = p.matcher(s)
    if (m.lookingAt()) Some(m) else None
  }

  def matches_start(p: Pattern, s: CharSequence): Boolean =
    p.matcher(s).lookingAt()

  /* Python's pattern.match(s, pos): anchored at `pos`, with the text before it
     still visible to lookbehind (transparent bounds) and `^`/`$` still meaning
     the ends of the whole string (no anchoring bounds). */
  def matches_at(p: Pattern, s: CharSequence, pos: Int): Option[Matcher] = {
    val m = p.matcher(s)
    m.useTransparentBounds(true)
    m.useAnchoringBounds(false)
    m.region(pos, s.length)
    if (m.lookingAt()) Some(m) else None
  }

  def search(p: Pattern, s: CharSequence): Option[Matcher] = {
    val m = p.matcher(s)
    if (m.find()) Some(m) else None
  }

  def found(p: Pattern, s: CharSequence): Boolean = p.matcher(s).find()

  /* Python's pattern.search(s, pos). */
  def search_from(p: Pattern, s: CharSequence, pos: Int): Option[Matcher] = {
    val m = p.matcher(s)
    if (m.find(pos)) Some(m) else None
  }

  def find_all(p: Pattern, s: CharSequence, group: Int = 1): List[String] = {
    val out = new mutable.ListBuffer[String]
    val m = p.matcher(s)
    while (m.find()) out += m.group(group)
    out.toList
  }

  def group_or_empty(m: Matcher, i: Int): String = {
    val g = m.group(i)
    if (g == null) "" else g
  }

  /* Python's `re.escape` (3.7+): only the characters `re` gives a meaning to
     are escaped, everything else — `<`, `>`, `:`, `!`, `_`, … — is left alone.
     This is observable, not cosmetic: `_user_pattern` escapes an Isabelle
     markup token with it, and an over-eager escape (Java's `Pattern.quote`, or
     escaping `<`) would produce a pattern that means something else. */
  private val re_special: Set[Char] =
    "()[]{}?*+-|^$\\.&~# ".toSet ++
      Set('\t', '\n', '\r', 11.toChar, 12.toChar)

  def re_escape(s: String): String = {
    val buf = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (re_special(c)) buf += '\\'
      buf += c
      i += 1
    }
    buf.toString
  }

  /* Python's `format(n, ",")` — the thousands separator the corpus summary
     prints.  Written out rather than taken from `java.text` so it cannot pick
     up a locale's grouping (Indian lakh grouping, a NBSP separator, …). */
  def comma(n: Long): String = {
    val digits = math.abs(n).toString
    val buf = new StringBuilder
    var i = 0
    while (i < digits.length) {
      if (i > 0 && (digits.length - i) % 3 == 0) buf += ','
      buf += digits.charAt(i)
      i += 1
    }
    (if (n < 0) "-" else "") + buf.toString
  }

  /* Python's `int(s)`: surrounding whitespace and an optional sign, then
     ASCII digits (underscores allowed as separators since 3.6). */
  def parse_int(s0: String): Option[Int] = {
    val s = strip(s0)
    if (s.isEmpty) None
    else {
      val neg = s.charAt(0) == '-'
      val body = if (s.charAt(0) == '-' || s.charAt(0) == '+') s.substring(1) else s
      if (body.isEmpty || body.startsWith("_") || body.endsWith("_")) None
      else {
        val digits = new StringBuilder
        var ok = true
        var prev_us = false
        for (c <- body) {
          if (c == '_') { if (prev_us) ok = false; prev_us = true }
          else if (c >= '0' && c <= '9') { digits += c; prev_us = false }
          else ok = false
        }
        if (!ok) None
        else try Some((if (neg) "-" else "") + digits.toString).map(_.toInt)
             catch { case _: NumberFormatException => None }
      }
    }
  }
}
