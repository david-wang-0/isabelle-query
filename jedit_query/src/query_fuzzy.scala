/*  Title:      jedit_query/src/query_fuzzy.scala

The fuzzy matcher behind go-to-symbol.

A pure function over strings, in its own file and depending on nothing — no
jEdit, no engine, no index.  That is the point: it is what runs on every
keystroke, so it must be cheap, and it is the one piece of the quick-open
feature a machine with no display can actually test.  `dev/p6probe.scala` pins
its ranking.

The rule, stated once so the ranking is predictable rather than merely
plausible:

  * a candidate MATCHES when the query's characters occur in it in order,
    case-insensitively — the usual subsequence test;
  * the positions are chosen GREEDILY, first occurrence at or after the
    cursor.  Greedy can be beaten by a cleverer alignment, but it is total,
    O(candidate), and — the deciding property — always gives the same answer,
    which a ranking a user is learning has to;
  * the SCORE rewards structure: a character landing on a word start
    (position 0, or after `_` `.` `'`, or a lower-to-upper step) counts most,
    a character continuing the previous match next, and a name that is longer
    than what was typed pays a little for the surplus.

Ties are broken by shorter name and then lexicographically, so the order is
total and the same on every run.
*/

package isabelle.jedit_query


import scala.collection.mutable


object Query_Fuzzy {
  final case class Match(name: String, score: Int, positions: List[Int])

  /* Isabelle identifiers separate with `_` and qualify with `.`; a prime ends
     a name rather than starting a part of one, but a name after it still
     reads as a new part (`foo'bar`). */
  private def is_break(c: Char): Boolean = c == '_' || c == '.' || c == '\'' || c == '-'

  def is_word_start(s: String, i: Int): Boolean =
    i == 0 || is_break(s.charAt(i - 1)) ||
      (Character.isUpperCase(s.charAt(i)) && !Character.isUpperCase(s.charAt(i - 1)))

  private def lower(c: Char): Char = Character.toLowerCase(c)

  val WORD_START = 18
  val CONTIGUOUS = 10
  val PREFIX_BONUS = 40
  val EXACT_BONUS = 80

  /* `None` when the query is not a subsequence of the candidate. */
  def matching(query: String, candidate: String): Option[Match] = {
    if (query.isEmpty) Some(Match(candidate, 0, Nil))
    else if (candidate.isEmpty) None
    else {
      val positions = new mutable.ListBuffer[Int]
      var qi = 0
      var ci = 0
      while (qi < query.length && ci < candidate.length) {
        if (lower(query.charAt(qi)) == lower(candidate.charAt(ci))) {
          positions += ci
          qi += 1
        }
        ci += 1
      }
      if (qi < query.length) None
      else {
        val pos = positions.toList
        var score = 0
        var previous = -2
        for (p <- pos) {
          if (is_word_start(candidate, p)) score += WORD_START
          if (p == previous + 1) score += CONTIGUOUS
          previous = p
        }
        /* A prefix is what the user most often means, and an exact name even
           more so. */
        if (pos.head == 0 && pos.last == query.length - 1) score += PREFIX_BONUS
        if (candidate.length == query.length) score += EXACT_BONUS
        /* Surplus costs a little: between two names that both match, the one
           with less left over is the closer answer. */
        score -= (candidate.length - query.length) / 4
        /* And so does a long run-up before the first character matches. */
        score -= pos.head min 10
        Some(Match(candidate, score, pos))
      }
    }
  }

  /* Best `limit` names for `query`, best first.  `names` is the index's
     `entry_names` snapshot — an already-materialised list, walked without
     touching the engine, which is what makes this safe to run per keystroke
     (`with_namespace` re-reads the project's ROOT files and must not). */
  def filter(query: String, names: Iterable[String], limit: Int): List[Match] = {
    val hits = new mutable.ArrayBuffer[Match]
    for (name <- names) matching(query, name).foreach(hits += _)
    val ordered =
      hits.sortInPlaceBy(m => (-m.score, m.name.length, m.name)).toList
    if (limit > 0) ordered.take(limit) else ordered
  }
}
