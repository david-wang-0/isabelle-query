/*  Title:      jedit_query/src/query_word.scala

The identifier under the caret, resolved from plain buffer text.

Deliberately NOT from PIDE markup.  A right-click must answer while the prover
is still loading, or has never been started at all, so the only thing this may
read is the characters in the buffer — which is exactly what the engine reads
too.

The grammar is the engine's.  `Commands.isa_word_pattern` is what every
`callers`/`grep` scan uses to decide where a fact name begins and ends: the
boundary is `[\w']` rather than `\b` (Isabelle allows `'` inside identifiers),
a `\<...>` token must not glue onto a neighbouring symbol, and a name carrying
anything outside `[\w'\\<>^]` matches only between quotes.  This scanner
expands over exactly that character class and then CHECKS itself against the
pattern: if the extracted word does not match the engine's own pattern on the
line it came from, no menu item is offered.  One grammar, two users, and the
disagreement is caught rather than shipped.

Buffer text is Isabelle-DECODED (`\<alpha>` displays as one character), while
the engine reads file text.  The scan therefore runs over `Symbol.explode` and
re-encodes the result, so a name like `my_allE\<^sub>1` comes back in the form
the entry table holds it, whatever the buffer's encoding does to it.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.query.{Commands, Py}


object Query_Word {
  /* The character class of `isa_word_pattern`'s boundaries, one symbol at a
     time.  `.` is admitted so a qualified citation (`List.map`) is picked up
     whole; the qualifier is split off afterwards, because a name containing
     `.` is one the engine's pattern only matches inside quotes. */
  def is_name_symbol(sym: String): Boolean =
    if (sym.startsWith("\\<")) true
    else if (sym.length != 1) false
    else {
      val c = sym.charAt(0)
      Symbol.is_ascii_letter(c) || Symbol.is_ascii_digit(c) ||
        c == '_' || c == '\'' || c == '.'
    }

  /* A leading `'` is a type variable and a leading/trailing `.` is punctuation
     around the name rather than part of it. */
  private def trim(name: String): String = {
    var a = 0
    var b = name.length
    while (a < b && (name.charAt(a) == '.' || name.charAt(a) == '\'')) a += 1
    while (b > a && name.charAt(b - 1) == '.') b -= 1
    name.substring(a, b)
  }

  /* `full` as written, `base` after the last qualifier: `Nat.add` cites the
     fact the entry table calls `add`, and it is `base` every engine verb
     takes. */
  final case class Word(full: String, base: String) {
    def is_empty: Boolean = base.isEmpty
    override def toString: String = base
  }

  /* `line` is one line of buffer text (decoded); `column` is a character index
     into it.  Returns the word straddling that column, or the one immediately
     to its left when the caret sits just past a word's end — the position a
     double-click leaves it in. */
  def at(line: String, column: Int): Option[Word] = {
    val syms = Symbol.explode(line).toArray
    if (syms.isEmpty) None
    else {
      val enc = syms.map(Symbol.encode)

      /* symbol index -> character span, so a click coordinate can find it */
      val starts = new Array[Int](syms.length + 1)
      var off = 0
      for (i <- syms.indices) { starts(i) = off; off += syms(i).length }
      starts(syms.length) = off

      val col = column max 0
      var k = 0
      while (k < syms.length && starts(k + 1) <= col) k += 1

      val hit =
        if (k < syms.length && is_name_symbol(enc(k))) k
        else if (k > 0 && is_name_symbol(enc(k - 1))) k - 1
        else -1

      if (hit < 0) None
      else {
        var a = hit
        while (a > 0 && is_name_symbol(enc(a - 1))) a -= 1
        var b = hit + 1
        while (b < syms.length && is_name_symbol(enc(b))) b += 1

        val full = trim(enc.slice(a, b).mkString)
        val base = { val i = full.lastIndexOf('.'); if (i < 0) full else full.substring(i + 1) }

        /* A bare numeral is a literal, not a citation. */
        val numeral = base.nonEmpty && base.forall(c => Symbol.is_ascii_digit(c))

        if (base.isEmpty || numeral) None
        else {
          /* The self-check: does the ENGINE's pattern find this word on this
             line?  Run against the encoded line, which is the text the engine
             would have scanned. */
          val agrees =
            try Py.found(Py.compile(Commands.isa_word_pattern(base)), Symbol.encode(line))
            catch { case _: Throwable => false }
          if (agrees) Some(Word(full, base)) else None
        }
      }
    }
  }
}
