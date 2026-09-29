/*  Title:      query_base/src/proof_locals.scala

Proof-local names bound and never read — `unused --locals` [unused-locals].

`unused` asks which DECLARATIONS nothing cites, over the citation graph
between entries.  This asks the same question one level down, inside a single
proof body: which `have NAME:`, `obtain … where NAME:`, `note NAME =`,
`define NAME` or `let ?NAME` binds a name that nothing in its scope reads.
Isabelle never warns about these, and the edit that produces them is routine:
a long proof copied and then trimmed leaves a prefix of facts whose only
reader was in the part that went.

The walk is a token stream over the proof body in two views at once, the same
split `Sites` uses: `outer_source` decides structure and fact names (a
citation is outer syntax), and the positions `outer_source` blanks but
`live_source` keeps are inner terms, where a `let ?x` abbreviation or a
`define`d variable is read.  Comments, `\<comment>` notes and `text` blocks
are in neither, so a name mentioned only there is still reported.

Three pieces of Isar semantics decide the answer, and a flat "the name occurs
once" count gets each of them wrong:

  * SCOPE.  A name bound inside `proof … qed` or `{ … }` is invisible after
    the block closes, and `next` starts a fresh branch.  The same label reused
    in two `case` branches is two bindings, each needing its own reader.
  * BINDING TIME.  `have a: "P" using a by simp` cites the OLD `a`: the label
    is bound when the statement's proof finishes, not when it is written.
    `note` / `define` / `let` bind at the end of their own command.
  * `this`.  A fact consumed by chaining (`then`, `hence`, `thus`, `with`,
    `moreover`, `ultimately`, `also`, `finally`, or a written `this`) is used
    although its name is not.  That label is redundant rather than dead, and
    the question here is about dead facts, so it is not reported.

A labelled fact given a declaring attribute (`have s [simp]:`) is read by the
rule set it joined and is never reported; a transforming attribute
(`[rule_format]`, `[symmetric]`) adds it to nothing and does not protect it.

The result is a list of CANDIDATES, not a delete list, because the errors run
both ways: a fact used through a bundle, by `case` position, by its statement
(`‹P›`) or by an `obtain`'s hand-written `that` is reported although it is
needed; a token that merely spells the name counts as a reader.

A port of upstream's `proof_locals.py` (0.9.2), rule for rule.  The one
structural difference is that the two views and the prose mask are
materialised ONCE per section and handed to every entry's walk: here a view is
computed per request (`Theory_Section.outer_source`), and recomputing it per
proof would make a whole-project scan quadratic in theory length.
*/

package isabelle.query


import isabelle.*

import java.nio.file.{Path => JPath}
import java.util.regex.Pattern

import scala.collection.mutable


object Proof_Locals {
  /* A name one proof binds.  `line` is 1-indexed in the theory.  A case class
     on purpose: upstream's is a dataclass, and `finish_command`'s dedup
     (`b not in facts`) compares by VALUE, `read` included — two same-line
     `note d = … and d = …` bindings are one there, and so they are here. */
  final case class Local_Binding(
    name: String,       // as written: `a`, `?p`
    kind: String,       // have / hence / obtain / note / define / let
    line: Int,
    entry: String,
    var read: Boolean = false,
    kept: Boolean = false)  // declared into a rule set by one of its attributes

  private final case class Tok(line: Int, col: Int, text: String, inner: Boolean)

  private type Key = (String, String)
  private type Group = mutable.ArrayBuffer[Local_Binding]


  /* ------------------------------------------------------------------ */
  /* the lexicon                                                        */
  /* ------------------------------------------------------------------ */

  /* An identifier by Isabelle's own lexical rule (`Doc/Isar_Ref`, "letter"):
     an ASCII letter, a Greek or `\<A>` / `\<AA>` letter symbol, and inside the
     name also digits, `_`, `'` and `\<^sub>`.  Narrower than
     `Entries.ISA_NAME`, which admits any markup: here `?R\<^sup>*` and
     `\<^bsub>?r\<^esub>` must read `?R` and `?r`, or the abbreviation they use
     goes unseen.  The alternation keeps upstream's order (the single letters,
     the doubled ones, the sorted Greek names); a `>` follows each, so the
     order decides nothing but speed. */
  private val LETTER: String = {
    val ascii = ('a' to 'z').map(_.toString) ++ ('A' to 'Z').map(_.toString)
    val alts = ascii ++ ascii.map(c => c + c) ++
      Entries.LETTER_SYMS.toList.map(s => s.substring(2, s.length - 1)).sorted
    """(?:[A-Za-z]|\\<(?:""" + alts.mkString("|") + ")>)"
  }
  private val NAME: String = s"""$LETTER(?:$LETTER|[0-9_']|\\\\<\\^sub>)*"""

  /* A dotted name is one token (`Suc.IH`, `assms(1)` reads as `assms`); `?x`
     is a schematic abbreviation; the punctuation is what the grammar below
     looks at.  Any other symbol (`\<in>`, `\<^sup>`) is matched whole so its
     letters never surface as a word, and then dropped (the `sym` group). */
  private val TOKEN_RE: Pattern = Py.compile(
    s"""(\\?$NAME)|($NAME(?:\\.$NAME)*)|(?<sym>\\\\<\\^?\\w+>)""" +
      """|(\.\.|::|[.{}()\[\]:=,])""")
  private val TERM_TOKEN_RE: Pattern =
    Py.compile(s"""\\??$NAME|(?<sym>\\\\<\\^?\\w+>)""")

  /* Every word that starts a proof command.  Recognised only outside
     parentheses and brackets, so a method's arguments (`proof (cases x)`)
     never read as one.  `unfolding` is named here as well as through
     `PLUMBING_KEYWORDS`, which gains it with [markup-step-model]: upstream's
     set already carries it, and the union is the same once both are in. */
  private val COMMANDS: Set[String] =
    Usage_Graph.GOAL_KEYWORDS | Usage_Graph.CONTEXT_KEYWORDS |
      Usage_Graph.PLUMBING_KEYWORDS | Usage_Graph.CLOSING_KEYWORDS |
      Set("unfolding", "proof", "next", "sorry", "oops", "include", "including",
        "supply", "subgoal", "defer", "prefer", "back", "apply_end", "presume")
  /* Commands that consume the fact currently bound to `this`. */
  private val CHAIN_READERS: Set[String] =
    Set("then", "hence", "thus", "with", "moreover", "ultimately", "also", "finally")
  /* Commands after which `this` is something no tracked binding supplied. */
  private val THIS_RESETS: Set[String] =
    Set("from", "with", "assume", "presume", "case", "next")
  /* Goals: their labels bind when their proof finishes.  `hence`/`obtain`
     bind like `have`; `show`/`thus`/`consider` are goals whose labels are not
     tracked. */
  private val GOAL_CMDS: Set[String] =
    Set("have", "hence", "show", "thus", "obtain", "consider")
  private val LABELLED_GOALS: Set[String] = Set("have", "hence", "obtain")
  private val TERMINATORS: Set[String] = Set("by", "done", "sorry", "oops", ".", "..")
  /* Attributes that change the fact rather than declare it into a rule set,
     so a label carrying only these is as unread as a bare one. */
  private val TRANSFORMING_ATTRS: Set[String] = Set(
    "rule_format", "symmetric", "THEN", "OF", "of", "where", "unfolded",
    "folded", "simplified", "abs_def", "elim_format", "rotated", "consumes",
    "case_names", "format", "standard", "untagged", "no_vars", "to_pred",
    "to_set", "atomize", "rulify", "transferred", "param")

  /* Python's `str.isalnum()` on the code point before `col` — a letter or any
     numeric character, which is wider than `Character.isLetterOrDigit`. */
  private def word_char_before(s: String, col: Int): Boolean = {
    val cp = s.codePointBefore(col)
    Character.isLetter(cp) || (Character.getType(cp) match {
      case Character.DECIMAL_DIGIT_NUMBER | Character.LETTER_NUMBER |
           Character.OTHER_NUMBER => true
      case _ => false
    }) || cp == '_' || cp == '\'' || cp == '?'
  }

  /* A name token, as opposed to punctuation.  Not an ASCII-letter test: a
     name may begin with a letter SYMBOL, and `define \<epsilon> where` is
     common. */
  private def is_name(tok: String): Boolean =
    tok.nonEmpty && (Character.isLetter(tok.codePointAt(0)) || tok.charAt(0) == '\\')

  /* `local.foo` is the proof-local `foo` — Isar's spelling for reaching past a
     same-named global fact. */
  private def unqualified(name: String): String =
    if (name.startsWith("local.")) name.substring("local.".length) else name


  /* ------------------------------------------------------------------ */
  /* the token stream                                                   */
  /* ------------------------------------------------------------------ */

  /* The two views and the prose mask of one section, built once and shared by
     every proof in it. */
  private final class Views(sec: Theory_Section) {
    val outer: Array[String] = sec.outer_source
    val live: Array[String] = sec.live_source
    val noise: Array[Boolean] = Entries.line_mask(outer.length, Usage_Graph.noise_spans(sec))
  }

  /* The proof body of `entry` as one token stream, in source order. */
  private def body_tokens(v: Views, entry: Entry): Vector[Tok] = {
    val n = v.outer.length
    // Read as far as `thy_end`; `walk` stops at the proof's own close.  The
    // `text` blocks a proof may contain are noise and skipped below.
    val end = (if (entry.thy_end != 0) entry.thy_end else n) min n
    val out = Vector.newBuilder[Tok]
    // The proof line is read whole, even when the statement shares it: the
    // statement's outer words bind nothing, and cutting at the method (as
    // `Shape`'s inline-proof column does) loses a leading `subgoal`.
    var ln = entry.proof_line
    while (ln <= end) {
      if (!v.noise(ln)) {
        val outer = v.outer(ln - 1)
        val live = v.live(ln - 1)
        val toks = new mutable.ArrayBuffer[Tok]
        val m = TOKEN_RE.matcher(outer)
        while (m.find()) {
          if (m.group("sym") == null) toks += Tok(ln, m.start, m.group, inner = false)
        }
        val t = TERM_TOKEN_RE.matcher(live)
        while (t.find()) {
          val c = t.start
          if (t.group("sym") == null && outer.charAt(c) == ' ' &&
              (c == 0 || !word_char_before(live, c)))
            toks += Tok(ln, c, t.group, inner = true)
        }
        out ++= toks.sortBy(_.col)  // stable, as `list.sort` is
      }
      ln += 1
    }
    out.result()
  }


  /* ------------------------------------------------------------------ */
  /* the walk                                                           */
  /* ------------------------------------------------------------------ */

  /* The scan state of one proof body. */
  private final class Walk(val entry: String) {
    val bindings = new mutable.ArrayBuffer[Local_Binding]
    /* Visible names, innermost block last.  A key is `("f", name)` for a fact
       and `("t", name)` for a term; a `define` also answers to its `_def`
       fact. */
    val scopes = mutable.ArrayBuffer(mutable.HashMap.empty[Key, Group])
    /* What opened each scope: the entry's own goal ("goal"), `proof`, `{`,
       `subgoal`.  A subgoal ends with its first terminator, a block does not. */
    val kinds = mutable.ArrayBuffer("goal")
    var end = 0                      // the line the entry's own proof ends on
    /* depth -> labels bound when proved */
    val goals = mutable.HashMap.empty[Int, mutable.ArrayBuffer[(Key, Local_Binding)]]
    /* bound at the end of this command */
    val pending = new mutable.ArrayBuffer[(Key, Group)]
    var this_facts: List[Local_Binding] = Nil

    def read(key: Key): Unit = {
      var k = scopes.length - 1
      while (k >= 0) {
        scopes(k).get(key) match {
          case Some(group) => group.foreach(_.read = true); return
          case None => k -= 1
        }
      }
    }

    def read_this(): Unit = this_facts.foreach(_.read = true)

    def bind(key: Key, group: Group): Unit = scopes.last(key) = group

    def open(kind: String): Unit = { scopes += mutable.HashMap.empty; kinds += kind }

    def close(): Unit =
      if (scopes.length > 1) { scopes.remove(scopes.length - 1); kinds.remove(kinds.length - 1) }

    /* A terminator, or the `qed` of its `proof`, finishes a `subgoal`. */
    def close_subgoal(): Unit = if (kinds.last == "subgoal") close()

    /* Bind what `note` / `define` / `let` wrote, now that it is done. */
    def finish_command(): Unit = {
      if (pending.nonEmpty) {
        val facts = new mutable.ArrayBuffer[Local_Binding]
        for ((key, group) <- pending) {
          bind(key, group)
          if (group.nonEmpty && (group.head.kind == "note" || group.head.kind == "define"))
            for (b <- group if !facts.contains(b)) facts += b
        }
        if (facts.nonEmpty) this_facts = facts.toList
        pending.clear()
      }
    }

    /* The goal open at the current depth is proved: bind its labels. */
    def prove(): Unit =
      goals.remove(scopes.length) match {
        case None =>
        case Some(done) =>
          for ((key, b) <- done) bind(key, mutable.ArrayBuffer(b))
          this_facts = done.map(_._2).toList
      }
  }

  /* The attribute names of the `[…]` opening at `i`, and the index after its
     `]`.  An attribute's name is the first word after `[` or `,`. */
  private def attr_words(toks: Vector[Tok], i: Int): (Set[String], Int) = {
    val words = mutable.LinkedHashSet.empty[String]
    var depth = 0
    var expect = true
    var j = i
    while (j < toks.length) {
      val t = toks(j)
      if (!t.inner) {
        if (t.text == "[") { depth += 1; expect = depth == 1 }
        else if (t.text == "]") {
          depth -= 1
          if (depth == 0) return (words.toSet, j + 1)
        }
        else if (t.text == "," && depth == 1) expect = true
        else if (expect && depth == 1 && is_name(t.text)) { words += t.text; expect = false }
      }
      j += 1
    }
    (words.toSet, j)
  }

  /* A label `NAME [attrs] SEP` starting at `i` — its name, attributes and the
     index after SEP — or None when `i` does not start one. */
  private def label_at(toks: Vector[Tok], i: Int, sep: String
  ): Option[(String, Set[String], Int)] = {
    val t = toks(i)
    if (t.inner || !is_name(t.text)) None
    else {
      var j = i + 1
      var attrs = Set.empty[String]
      while (j < toks.length && toks(j).inner) j += 1
      if (j < toks.length && toks(j).text == "[") {
        val (a, k) = attr_words(toks, j)
        attrs = a; j = k
      }
      if (j < toks.length && !toks(j).inner && toks(j).text == sep) Some((t.text, attrs, j + 1))
      else None
    }
  }

  /* Walk `entry`'s proof from `proof_line` until its own goal is closed.

     The walk reads as far as `thy_end` but STOPS where the proof does.
     `thy_end` is too long: a command that proves something without being an
     entry — an `instance`, a `termination` — sits inside the previous entry's
     span, and its bindings are not this proof's.  Upstream's
     `parsing._proof_close_line` walks the same structure for `body_end_line`
     [body-end-text]; over the AFP the two agree on all 297,954 proofs. */
  private def walk(v: Views, entry: Entry): Walk = {
    val toks = body_tokens(v, entry)
    val w = new Walk(entry.name)
    var cmd = ""
    var nest = 0              // parentheses + brackets: no command starts inside
    var label_next = false    // the next token may be a label
    var premises = false      // after `if` / `when`: labels local to the goal
    var after_where = false
    var variables = false     // names here are being bound, not read
    var after_type = false    // after `::` in a variable list: a type, not a name
    var define_group: Group = new mutable.ArrayBuffer
    // `from x` / `assume` rebind `this` when they FINISH: the `this` in
    // `from this` is still the previous fact.
    var this_stale = false
    var i = 0
    var stop = false
    while (!stop && i < toks.length) {
      val t = toks(i)
      val s = t.text
      if (t.inner) {
        if (!variables) w.read(("t", s))
        i += 1
      }
      else if (s == "(" || s == "[") {
        nest += 1
        label_next = false
        i += 1
      }
      else if (s == ")" || s == "]") {
        nest = (nest - 1) max 0
        i += 1
      }
      else if (nest == 0 && (COMMANDS(s) || s == "{" || s == "}" || s == "." || s == "..")) {
        w.finish_command()
        if (this_stale) { w.this_facts = Nil; this_stale = false }
        if (CHAIN_READERS(s)) w.read_this()
        this_stale = THIS_RESETS(s)
        if (s == "proof" || s == "{" || s == "subgoal") w.open(s)
        else if (s == "qed" || s == "}") {
          w.close()
          if (s == "qed") {
            // Back at the entry's own goal: its outermost block closed.  Asked
            // before `close_subgoal`, which would also reach it.
            if (w.scopes.length == 1) { w.end = t.line; stop = true }
            else {
              w.prove()
              w.close_subgoal()
            }
          }
        }
        else if (s == "next") {
          w.close()
          w.open("proof")
        }
        else if (TERMINATORS(s)) {
          if (w.scopes.length == 1) { w.end = t.line; stop = true }
          else {
            w.prove()
            w.close_subgoal()
          }
        }
        if (!stop) {
          if (GOAL_CMDS(s)) w.goals(w.scopes.length) = new mutable.ArrayBuffer
          cmd = s
          label_next = s == "have" || s == "hence" || s == "note" || s == "let"
          premises = false; after_where = false; after_type = false
          variables = s == "fix" || s == "obtain" || s == "define" || s == "case"
          define_group = new mutable.ArrayBuffer
          i += 1
        }
      }
      else if (nest == 0 &&
          (s == "and" || s == "where" || s == "if" || s == "when" || s == "for" || s == "::")) {
        if (s == "::") after_type = true
        else if (s == "for") { variables = true; label_next = false }
        else if (s == "if" || s == "when") { premises = true; label_next = LABELLED_GOALS(cmd) }
        else if (s == "where") {
          after_where = true; variables = false; after_type = false
          label_next = cmd == "obtain" || cmd == "define"
        }
        else {  // and
          after_type = false
          label_next = cmd == "have" || cmd == "hence" || cmd == "note" || cmd == "let" ||
            ((cmd == "obtain" || cmd == "define") && after_where)
        }
        i += 1
      }
      else {
        var handled = false
        if (label_next && cmd == "let" && s.startsWith("?")) {
          val j = i + 1
          if (j < toks.length && toks(j).text == "=") {
            val b = Local_Binding(s, "let", t.line, entry.name)
            w.bindings += b
            w.pending += ((("t", s), mutable.ArrayBuffer(b)))
            label_next = false
            i = j + 1
            handled = true
          }
        }
        if (!handled && label_next) {
          label_next = false
          val sep = if (cmd == "note") "=" else ":"
          label_at(toks, i, sep) match {
            case None =>
            case Some((name, attrs, after)) =>
              // The attributes' arguments cite facts: `[unfolded I_defs]`.
              var k = i + 1
              while (k < after) {
                val u = toks(k)
                if (u.inner) w.read(("t", u.text))
                else if (is_name(u.text)) w.read(("f", unqualified(u.text)))
                k += 1
              }
              i = after
              handled = true
              if (!premises) {
                val b = Local_Binding(name, cmd, t.line, entry.name,
                  kept = (attrs -- TRANSFORMING_ATTRS).nonEmpty)
                if (cmd == "define") {
                  // A `where` label is another handle on the definitions.
                  w.pending += ((("f", name), define_group))
                }
                else {
                  w.bindings += b
                  if (cmd == "note") w.pending += ((("f", name), mutable.ArrayBuffer(b)))
                  else w.goals.getOrElseUpdate(w.scopes.length, new mutable.ArrayBuffer) +=
                    ((("f", name), b))
                }
              }
          }
        }
        if (!handled) {
          if (variables) {
            // Only the names before `where`: a `for` after it binds a
            // parameter of the defining equation, not a new constant.
            if (cmd == "define" && !after_where && !after_type && is_name(s)) {
              val b = Local_Binding(s, "define", t.line, entry.name)
              w.bindings += b
              define_group += b
              w.pending += ((("t", s), mutable.ArrayBuffer(b)))
              w.pending += ((("f", s + "_def"), mutable.ArrayBuffer(b)))
            }
          }
          else if (s == "this") w.read_this()
          else if (s.startsWith("?")) w.read(("t", s))
          else if (is_name(s)) w.read(("f", unqualified(s)))
          i += 1
        }
      }
    }
    w
  }


  /* ------------------------------------------------------------------ */
  /* the public surface                                                 */
  /* ------------------------------------------------------------------ */

  /* Every name `entry`'s proof binds, each marked read or not. */
  def scan_proof(sec: Theory_Section, entry: Entry): List[Local_Binding] =
    if (entry.proof_line == 0) Nil else walk(new Views(sec), entry).bindings.toList

  /* The line `entry`'s own proof ends on — its outermost `qed`, or the
     terminator that closes its goal — or 0 when the walk runs out first. */
  def proof_end(sec: Theory_Section, entry: Entry): Int =
    if (entry.proof_line == 0) 0 else walk(new Views(sec), entry).end

  /* An inclusive line window, an open upper bound as `None` — the shape of
     `Theory_Section.line_window`. */
  type Window = (Int, Option[Int])

  /* Every binding no reader reaches, over each section's proofs — or, for a
     section with `windows`, over the proofs overlapping any of them.

     A window selects whole proofs rather than binding lines: whether a name is
     read depends on the rest of its proof, and "which of these proofs did I
     leave litter in" is the question a diff hunk or a `largest` span asks. */
  def unread_locals(sections: List[Theory_Section],
    windows: Map[JPath, List[Window]] = Map.empty,
    keep: Set[String] = Set.empty
  ): List[(Theory_Section, Local_Binding)] = {
    val out = new mutable.ListBuffer[(Theory_Section, Local_Binding)]
    for (sec <- sections) {
      val wins = windows.get(sec.path)
      lazy val views = new Views(sec)
      for (e <- sec.entries if e.proof_line != 0) {
        val selected = wins match {
          case None => true
          case Some(ws) =>
            val last = e.thy_end
            ws.exists { case (lo, hi) => last >= lo && hi.forall(e.src_start <= _) }
        }
        if (selected) {
          for (b <- walk(views, e).bindings if !(b.read || b.kept || keep(b.name)))
            out += ((sec, b))
        }
      }
    }
    out.toList
  }
}
