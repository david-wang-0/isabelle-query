/*  Title:      query_base/src/premises.scala

What a fact's statement assumes, one premise at a time [show-premises].

`show --premises` answers the question an abstract-versus-theorem audit asks:
what does this theorem assume, and how is each assumption cited?  It is short
by construction — one line per premise, then a count of conclusions — so it
needs no truncating however long the conclusion is (upstream issue #17).

Directly above `entries`, whose balanced-delimiter scanner it reuses, and below
`render`, which formats what this returns.  It uses nothing in between.

A statement states its premises in one of three ways, measured by upstream
over the AFP's 297,456 facts (`scripts/probe_premise_forms.py`):

    assumes a: "P" "Q" and "R" shows "C"     34%   premises cited as assms(k)
    "C" if a: "P" for x                       1%   premises cited as that(k)
    "P \<Longrightarrow> Q \<Longrightarrow> C"                 28%   premises inside the term

and 42% have none.  Each premise is reported with the name a proof can cite it
by — `assms(k)` / `that(k)`, numbered across every element as Isabelle numbers
them, plus its own label where it has one.  A premise written inside the term
has no such name, so it is numbered by position alone.

Read on the LIVE view (noise blanked, terms kept), tokenised here: the outer
view blanks a term whole, delimiters included, so `"P" "Q"` and `"P Q"` look
alike there.

This is a transcription of upstream's `premises.py`, quirks included: the
oracle's output is the interface.  Two of them are worth knowing about — a
long-form `shows "Q x" for x` counts `for` and `x` as two more bare
conclusions, and `...` tokenises as a word, so `"A ⟹ B" ...` has two.
*/

package isabelle.query


import java.util.regex.Pattern

import scala.collection.mutable


object Premises {
  /* kind: "assumes" | "if" | "defines" | "term"; cite: `assms(2)` / `that(1)`,
     "" when there is no such name; label: the premise's own label, `wf` or
     `wf(2)`, "" if none; text: the proposition, delimiters stripped,
     whitespace collapsed. */
  final case class Premise(kind: String, cite: String, label: String, text: String)

  /* `cases` counts the `obtains` alternatives; 0 when there is no `obtains`. */
  final case class Statement(premises: List[Premise], conclusions: Int, cases: Int)

  private type Tok = (String, String)

  /* Isar statement elements (`Pure.thy`, `Parse_Spec.statement`). */
  private val ELEMENTS: Set[String] =
    Set("fixes", "constrains", "assumes", "defines", "notes", "includes", "shows", "obtains")

  /* Where the statement ends: the first proof command. */
  private val PROOF_WORDS: Set[String] =
    Set("by", "proof", "apply", "using", "unfolding", "including", "supply", "sorry", "oops",
      "done", "nitpick", "sledgehammer")

  /* A cartouche delimiter is not part of a word: `shows\<open>P\<close>` is
     written with no space between them. */
  private val WORD_RE: Pattern = Py.compile("""(?:\\<(?!open>|close>)\^?\w+>|[\w'.?])+""")

  private val OPEN = """\<open>"""
  private val CLOSE = """\<close>"""


  /* --- tokens --- */

  /* `(kind, text)` tokens: term, attr, paren, word, punct.

     A term is a `"..."` string or a cartouche, with its delimiters stripped.
     An unterminated one runs to the end, which is where a statement slice
     that stops early would leave it.  An attribute list or a parenthesis is
     kept whole, delimiters included, and never looked into. */
  private def tokens(s: String): Vector[Tok] = {
    val out = Vector.newBuilder[Tok]
    val n = s.length
    var i = 0
    while (i < n) {
      val c = s.charAt(i)
      if (Py.is_space(c)) i += 1
      else if (c == '"') {
        var j = i + 1
        while (j < n && !(s.charAt(j) == '"' && s.charAt(j - 1) != '\\')) j += 1
        out += (("term", s.substring(i + 1, j)))
        i = j + 1
      }
      else if (s.startsWith(OPEN, i) || c == '‹') {
        val (op, cl) = if (c == '\\') (OPEN, CLOSE) else ("‹", "›")
        val j = Entries.balanced_end(s, op, cl, i)
        val end = if (j >= 0) j else n
        out += (("term", s.substring(i + op.length, end - (if (j >= 0) cl.length else 0))))
        i = end
      }
      else if (c == '[' || c == '(') {
        val j = Entries.balanced_end(s, c.toString, if (c == '[') "]" else ")", i,
          quote_aware = true)
        val end = if (j >= 0) j else n
        out += ((if (c == '[') "attr" else "paren", s.substring(i, end)))
        i = end
      }
      else {
        val m = Py.matches_at(WORD_RE, s, i).map(_.group())
        if (m.isDefined && m.get != "." && m.get != "..") {
          out += (("word", m.get))
          i += m.get.length
        }
        else if (s.startsWith("::", i)) { out += (("punct", "::")); i += 2 }
        else {
          /* `.` / `..` (proof by default/rule) and lone symbols — one CODE
             POINT, as Python steps, so a non-BMP symbol stays one token. */
          val j = if (s.startsWith("..", i)) i + 2 else i + Character.charCount(s.codePointAt(i))
          out += (("punct", s.substring(i, j)))
          i = j
        }
      }
    }
    out.result()
  }


  /* --- premises written inside the term --- */

  private val IMPS = List("""\<Longrightarrow>""", "⟹", "==>")
  private val PAIRS: List[(String, String)] =
    List("(" -> ")", "[" -> "]", "{" -> "}", """\<lbrakk>""" -> """\<rbrakk>""", "⟦" -> "⟧",
      OPEN -> CLOSE, "‹" -> "›")
  private val CLOSERS: List[String] = PAIRS.map(_._2)
  private val BINDERS = List("""\<And>""", "⋀", "!!")

  private def starts_binder(t: String, i: Int): Boolean = BINDERS.exists(t.startsWith(_, i))

  /* Walk `t` tracking bracket depth; call `at_depth0(i)` at each depth-0
     position, which returns how far to skip (0 = one char) or -1 to stop.
     Openers are checked first, so a bracket is never offered to the visitor;
     depth may go negative on a stray closer, and then nothing splits. */
  private def scan(t: String, at_depth0: Int => Int): Unit = {
    var depth = 0
    var i = 0
    val n = t.length
    while (i < n) {
      PAIRS.find(p => t.startsWith(p._1, i)) match {
        case Some((op, _)) => depth += 1; i += op.length
        case None =>
          CLOSERS.find(t.startsWith(_, i)) match {
            case Some(cl) => depth -= 1; i += cl.length
            case None =>
              val step = if (depth == 0) at_depth0(i) else 0
              if (step < 0) return
              i += (if (step > 0) step else 1)
          }
      }
    }
  }

  /* `\<And>x y. body` -> `body`: an outermost meta-quantifier scopes the
     whole proposition, and its premises are the body's.  The FIRST dot ends
     the binder, as upstream reads it. */
  private def strip_binders(t0: String): String = {
    var t = Py.strip(t0)
    var go = true
    while (go && starts_binder(t, 0)) {
      val dot = t.indexOf('.')
      if (dot < 0) go = false
      else t = Py.strip(t.substring(dot + 1))
    }
    t
  }

  /* The premises and conclusion of `P \<Longrightarrow> Q \<Longrightarrow> C`,
     split at the outermost implications: `[P, Q, C]`.

     Only depth 0 splits, so `(A \<Longrightarrow> B) \<Longrightarrow> C` has
     the one premise `(A \<Longrightarrow> B)`.  A meta-quantifier met after
     the first split scopes the rest, which is then one piece.  A premise
     written as `\<lbrakk>A; B\<rbrakk>` is the two premises A and B.  `===>`
     (`rel_fun`) is not `==>`. */
  def split_imp(t0: String): List[String] = {
    val t = strip_binders(t0)
    val cuts = new mutable.ListBuffer[(Int, Int)]
    scan(t, { i =>
      if (cuts.nonEmpty && starts_binder(t, i)) -1
      else
        IMPS.find(imp => t.startsWith(imp, i) &&
          !(imp == "==>" && i > 0 && "=-".indexOf(t.charAt(i - 1)) >= 0)) match {
          case Some(imp) => cuts += ((i, i + imp.length)); imp.length
          case None => 0
        }
    })
    val pieces = new mutable.ListBuffer[String]
    var lo = 0
    for ((a, b) <- cuts) { pieces += Py.strip(t.substring(lo, a)); lo = b }
    pieces += Py.strip(t.substring(lo))
    pieces.init.toList.flatMap(split_lbrakk) ::: List(pieces.last)
  }

  private def split_lbrakk(p: String): List[String] = {
    val brackets = List("""\<lbrakk>""" -> """\<rbrakk>""", "⟦" -> "⟧")
    brackets.find { case (op, cl) =>
      p.startsWith(op) && Entries.balanced_end(p, op, cl) == p.length
    } match {
      case None => List(p)
      case Some((op, cl)) =>
        val inner = p.substring(op.length, p.length - cl.length)
        val semis = new mutable.ListBuffer[Int]
        scan(inner, { i => if (inner.charAt(i) == ';') semis += i; 0 })
        val parts = new mutable.ListBuffer[String]
        var lo = 0
        for (k <- semis) { parts += Py.strip(inner.substring(lo, k)); lo = k + 1 }
        parts += Py.strip(inner.substring(lo))
        parts.toList.filter(_.nonEmpty)
    }
  }


  /* --- the statement --- */

  /* `(label, terms)` groups separated by `and`, from token `i` up to the
     first token `stop` accepts.  Returns the groups with at least one term,
     and the index it stopped at.

     `name [attrs]? :` labels the group; any other word is a proposition
     written bare — `shows False`, `assumes transitivity` — which Isar accepts
     for a single-token term.  Attributes, parentheses (`(is "?L = ?R")`) and
     punctuation are skipped. */
  private def groups(toks: Vector[Tok], i0: Int, stop: Tok => Boolean
  ): (List[(String, List[String])], Int) = {
    val out = new mutable.ListBuffer[(String, List[String])]
    var label = ""
    var terms = new mutable.ListBuffer[String]
    var i = i0
    while (i < toks.length && !stop(toks(i))) {
      val (kind, text) = toks(i)
      if (kind == "word" && text == "and") {
        out += ((label, terms.toList))
        label = ""
        terms = new mutable.ListBuffer[String]
      }
      else if (kind == "word") {
        var j = i + 1
        if (j < toks.length && toks(j)._1 == "attr") j += 1
        if (terms.isEmpty && j < toks.length && toks(j) == ("punct", ":")) {
          label = text
          i = j
        }
        else terms += text
      }
      else if (kind == "term") terms += Py.collapse_ws(text)
      i += 1
    }
    out += ((label, terms.toList))
    (out.toList.filter(_._2.nonEmpty), i)
  }

  private def is_proof(tok: Tok): Boolean =
    (tok._1 == "word" && PROOF_WORDS(tok._2)) || tok == ("punct", ".") || tok == ("punct", "..")

  /* Parse a goal statement starting at its command word.  None when it is
     not one this reader understands, so a caller can say so rather than print
     a wrong count. */
  def read_statement(text: String): Option[Statement] = {
    val toks = tokens(text)
    if (toks.isEmpty || toks(0)._1 != "word") return None
    var i = 1
    while (i < toks.length && toks(i)._1 == "paren") i += 1      // `(in loc)`
    /* `name [attrs]? :` or `[attrs] :` */
    var j = i
    if (j < toks.length && toks(j)._1 == "word" && !ELEMENTS(toks(j)._2) && !is_proof(toks(j)))
      j += 1
    if (j < toks.length && toks(j)._1 == "attr") j += 1
    if (j > i && j < toks.length && toks(j) == ("punct", ":")) i = j + 1

    val premises = new mutable.ListBuffer[Premise]
    var cases = 0
    var assms = 0
    var that = 0

    def add(kind: String, gs: List[(String, List[String])]): Unit =
      for ((label, terms) <- gs; (t, k0) <- terms.zipWithIndex) {
        val lab = if (terms.length == 1 || label.isEmpty) label else s"$label(${k0 + 1})"
        val cite =
          if (kind == "assumes") { assms += 1; s"assms($assms)" }
          else if (kind == "if") { that += 1; s"that($that)" }
          else ""
        premises += Premise(kind, cite, lab, t)
      }

    def at_element(tok: Tok): Boolean =
      (tok._1 == "word" && ELEMENTS(tok._2)) || is_proof(tok)

    val shows = new mutable.ListBuffer[String]
    if (i < toks.length && toks(i)._1 == "word" && ELEMENTS(toks(i)._2)) {
      while (i < toks.length && !is_proof(toks(i))) {
        val kw = if (toks(i)._1 == "word") toks(i)._2 else ""
        i += 1
        if (kw == "assumes" || kw == "defines" || kw == "shows") {
          val (gs, next) = groups(toks, i, at_element)
          i = next
          if (kw == "shows") shows ++= gs.flatMap(_._2)
          else add(kw, gs)
        }
        else if (kw == "obtains") {
          cases = 1
          while (i < toks.length && !at_element(toks(i))) {
            if (toks(i) == ("punct", "|")) cases += 1
            i += 1
          }
        }
        else while (i < toks.length && !at_element(toks(i))) i += 1
      }
    }
    else {
      def short_stop(tok: Tok): Boolean =
        is_proof(tok) || tok == ("word", "if") || tok == ("word", "for")
      val (gs, next) = groups(toks, i, short_stop)
      i = next
      shows ++= gs.flatMap(_._2)
      if (i < toks.length && toks(i) == ("word", "if")) {
        val (gs2, next2) = groups(toks, i + 1, short_stop)
        i = next2
        add("if", gs2)
      }
    }

    if (shows.isEmpty && cases == 0) None
    else {
      /* Only a single conclusion is split: with several, each would have
         premises of its own. */
      if (shows.length == 1)
        for (p <- split_imp(shows.head).init)
          premises += Premise("term", "", "", Py.collapse_ws(p))
      Some(Statement(premises.toList, shows.length, cases))
    }
  }

  /* The parsed statement of a LEMMA/THEOREM entry, else None.  `decl_end_line`
     stops at a blank line inside the statement, where the proof search does
     not, so read on to the proof line; the proof words end the parse, so a
     one-line proof is no harm. */
  def statement_of(sec: Theory_Section, e: Entry): Option[Statement] =
    if ((e.tag != "LEMMA" && e.tag != "THEOREM") || e.thy_line == 0) None
    else {
      val end = e.decl_end_line max e.proof_line max e.thy_line
      read_statement(sec.live_slice(e.thy_line, end).mkString("\n"))
    }
}
