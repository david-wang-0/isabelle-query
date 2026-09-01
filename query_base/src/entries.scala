/*  Title:      query_base/src/entries.scala

The declaration grammar: which commands open an entry, what name(s) each binds,
where each ends, and which target block it belongs to.

Layout carries no meaning here.  Isar is whitespace-insensitive, so a command
is recognised wherever a command can START — which is precisely where the outer
view of `regions` has left text standing — at any indentation and at any block
depth.  What ends a declaration is a real terminator: the next command, an
`end`, a `context`, a `lemmas`, an `ML` block.

The three views divide the work, and the division is the point:

  outer   decides WHERE a command begins.  A `lemma` written inside a term or
          a comment is blanked there, so it cannot match.
  source  says WHAT the command says — a definition's name routinely lives
          inside the term that `outer` blanks.
  live    reads a name that is quoted (`locale "functor" =`, `record State =
          "getM" :: ...`), which is the one place blanking terms hides the
          name rather than the noise.

See SCANNING.md for the behaviour this implements; the Python reference
implementation in `src/isabelle_query/parsing.py` is the authority on the
corner cases, and the differential harness in `dev/` is what holds the two
together.
*/

package isabelle.query


import isabelle.*

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable


object Entries {
  /* --- the built-in declaration commands --- */

  val DECL_RE: Pattern = Py.compile(
    "^(definition|abbreviation|function|fun|primrec|inductive_set|inductive|lemma" +
      "|corollary|theorem|axiomatization|datatype|type_synonym|record|locale|class)(?=\\s|$)")

  val tag_map: Map[String, String] = Map(
    "definition" -> "DEF", "abbreviation" -> "ABBREV",
    "function" -> "FUN", "fun" -> "FUN", "primrec" -> "FUN",
    "inductive_set" -> "INDSET", "inductive" -> "IND",
    "lemma" -> "LEMMA", "corollary" -> "LEMMA",
    "theorem" -> "THEOREM",
    "axiomatization" -> "AXIOM",
    "datatype" -> "DATATYPE", "type_synonym" -> "TYPE", "record" -> "RECORD",
    /* A locale/class DECLARES a name.  `context` and `interpretation` are
       deliberately absent: they REOPEN or INSTANTIATE an existing target
       rather than declare one. */
    "locale" -> "LOCALE", "class" -> "CLASS")

  /* A theory may declare commands of its own through Isabelle's keyword table
     (`keywords "AOT_theorem" :: thy_goal`).  The declared KIND maps onto the
     tag families below, following Pure/Isar/keyword.scala.  Proof, diagnostic,
     document and load kinds are intentionally absent: they introduce no citable
     fact, so they must not create an entry. */
  val kind_family: Map[String, String] = Map(
    "thy_goal" -> "THEOREM",
    "thy_goal_stmt" -> "THEOREM",
    "thy_goal_defn" -> "THEOREM",
    "thy_defn" -> "DEF",
    "thy_decl" -> "DEF",
    "thy_decl_block" -> "DEF",
    "thy_stmt" -> "DEF")

  /* Which branch handles a command — derived from the tag, not from a second
     keyword table, so built-in and custom commands route uniformly. */
  def route_for(keyword: String, tag: String): String =
    if (keyword == "axiomatization") "axiom"
    else if (tag == "LOCALE" || tag == "CLASS") "target"
    else if (tag == "DATATYPE" || tag == "TYPE" || tag == "RECORD") "typedecl"
    else if (tag == "LEMMA" || tag == "THEOREM") "goal"
    else "def"


  /* --- lexical atoms shared by every name pattern --- */

  /* The symbols that are STRUCTURE rather than name characters: the two
     cartouche delimiters and the four formal comments.  Each can sit exactly
     where a name is expected — a cartouche statement, an annotation, a
     document marker — and must not be captured as one. */
  val reserved_name_prefixes: List[String] =
    List("""\<open>""", """\<close>""") ::: List(
      """\<comment>""", """\<^cancel>""", """\<^latex>""", """\<^marker>""")

  /* TWO questions, so two atoms.  `ISA_SYMBOL` is LEXICAL — is this an
     Isabelle symbol token? — and `ISA_MARKUP` is GRAMMATICAL: may this token
     occur inside a NAME?  They differ by exactly `reserved_name_prefixes`.

     Conflating them let a name run straight through a structural symbol, so
     `definition lipschitzI_on\<^marker>\<open>tag important\<close> :: ...` was
     indexed as `lipschitzI_on\<^marker>\<open>tag` and
     `lemma shows_box_of_aforms\<comment> \<open>…\<close>:` as
     `shows_box_of_aforms\<comment>`.

     Which one a scanner wants follows from what it is doing, and the split is
     not cosmetic in either direction:

       * a RUN scanner (`Usage_Graph`'s citation tokens and `SYM_RE`, `Shape`'s
         token counter, `Commands`' user-pattern rewrite) asks only where a
         token ENDS, over source the region scan has already redacted.  It
         wants the lexical atom.  Narrowing it there does not help and actively
         harms: `\<open>foo` would stop being one run and start offering `open`
         as a candidate fact name, and `\<comment>` would count as ten tokens
         rather than one;
       * a NAME scanner (below, plus the datatype, target and `Sites` grammars)
         reads RAW source, where a marker really can abut the name, and must
         stop at it. */
  val ISA_SYMBOL: String = """\\<\^?\w+>"""
  private val NOT_STRUCTURAL: String =
    "(?!" + reserved_name_prefixes.map(s => Pattern.quote(s.substring(2))).mkString("|") + ")"
  val ISA_MARKUP: String = s"""\\\\<$NOT_STRUCTURAL\\^?\\w+>"""

  /* One character of a symbol RUN (lexical) / of a NAME (grammatical). */
  val ISA_WORD_CHAR: String = s"(?:$ISA_SYMBOL|[\\w'])"
  val ISA_NAME_CHAR: String = s"(?:$ISA_MARKUP|[\\w'])"
  val ISA_NAME: String = s"(?:$ISA_MARKUP|[A-Za-z])$ISA_NAME_CHAR*"

  val SYM_NAME_RE: Pattern = Py.compile(s"((?:$ISA_MARKUP|\\w)$ISA_NAME_CHAR*)")
  val QUOTED_NAME_RE: Pattern = Py.compile("^\"([^\"]+)\"")

  /* Outer-syntax keywords that are not fact names.  Only the BARE form is
     rejected: a quoted keyword (`fun "for"`) is a legitimate name and is
     parsed by the quoted branch before this guard is reached. */
  val reserved_name_words: Set[String] = Set(
    "assumes", "shows", "fixes", "obtains", "defines", "notes", "constrains",
    "by", "using", "unfolding", "apply", "proof", "qed", "done", "oops", "sorry",
    "where", "for", "and", "if", "then", "else", "next", "case")

  val LABEL_AFTER_RE: Pattern = Py.compile("""\s*(?:\[[^\]]*\]\s*)*:""")


  /* --- statement / proof shape --- */

  val PROOF_RE: Pattern = Py.compile(
    """^\s*(proof\b|by\b|sorry\b|oops\b|using\b|unfolding\b|apply\b|\.\.\s*$)""")
  val PROOF_INLINE_RE: Pattern = Py.compile(
    """(?:^|\s)(?:proof\b|by\b|sorry\b|oops\b|using\b|unfolding\b|apply\b|\.\.\s*$|\.\s*$)""")
  val STATEMENT_CONT_RE: Pattern = Py.compile(
    """^\s*(?:and|shows|assumes|fixes|obtains|defines|notes|where|if|for)\b""")

  val BLANK_RE: Pattern = Py.compile("""^\s*$""")
  val TOPLEVEL_RE: Pattern = Py.compile("^[a-z]")
  val AXIOM_NAME_RE: Pattern = Py.compile("""([A-Za-z_][A-Za-z0-9_']*)\s*:""")
  val AXIOM_AND_RE: Pattern = Py.compile("""(?<![\w'])and(?![\w'])""")
  val WHERE_RE: Pattern = Py.compile("""\bwhere\b""")
  val TRAILING_WHERE_RE: Pattern = Py.compile("""\s+where$""")

  val SHOWS_AT_START_RE: Pattern = Py.compile("""shows\b""")
  val SHOWS_ANYWHERE_RE: Pattern = Py.compile("""\bshows\b""")
  val CONJUNCT_RE: Pattern = Py.compile("""(?:shows|and)\s+(\w[\w']*)\s*:""")

  val RULE_LABEL_RE: Pattern = Py.compile(
    """(?:(?<![\w'])where(?![\w'])|\|)\s*([A-Za-z][\w']*)\s*(?:\[[^\]]*\])?\s*:(?!:)""")
  val BAR_LINE_RE: Pattern = Py.compile("""^\s*\|""")

  val sibling_tags: Set[String] = Set("FUN", "IND", "INDSET")
  val HEAD_END_RE: Pattern = Py.compile("""(?<![\w'])(?:where|for)(?![\w'])""")
  val AND_NAME_RE: Pattern = Py.compile(
    """(?<![\w'])and(?![\w'])\s*(?:"([^"\n]+)"|([A-Za-z][\w']*))""")

  val ALT_HEAD_RE: Pattern = Py.compile(s"""^\\s*(?:($ISA_NAME)\\s*:(?!:)\\s*)?($ISA_NAME)""")
  val SELECTOR_RE: Pattern = Py.compile(s"""\\(\\s*($ISA_NAME)\\s*:(?!:)""")
  val RECORD_FIELD_RE: Pattern = Py.compile(s"""(?<![\\w'])($ISA_NAME)\\s*::""")
  val RECORD_QUOTED_FIELD_RE: Pattern = Py.compile("""\"([^\"\n]+)\"\s*::""")

  val LOCALE_KW_RE: Pattern = Py.compile(
    """(?<![\w'])(assumes|defines|notes|fixes|constrains|for|and)(?![\w'])""")
  val LOCALE_LABEL_RE: Pattern = Py.compile("""\s*([A-Za-z][\w']*)\s*(?:\[[^\]]*\])?\s*:(?!:)""")
  val locale_elem_kind: Map[String, String] =
    Map("assumes" -> "assumption", "defines" -> "definition", "notes" -> "note")

  val DEF_CONNECTIVE_RE: Pattern = Py.compile("""\\<equiv>|\\<rightleftharpoons>|==|=""")
  val TYPEVAR_RE: Pattern = Py.compile("""'[\w']+\s+""")
  val LEAD_TOKEN_RE: Pattern = Py.compile("""^(\S+)""")


  /* --- document prose --- */

  val heading_words = "chapter|section|subsection|subsubsection|paragraph|subparagraph"
  val TITLE_OPEN = """\\<open>|""" + Symbol.open_decoded + "|\""

  /* The heading COMMAND, on its own.  Its title is matched separately by
     `TITLE_OPEN_RE`, because a document marker may sit between the two
     (`subsection\<^marker>\<open>tag unimportant\<close> \<open>Norm\<close>`,
     246 of them in HOL/Analysis) and a marker body may itself hold a
     cartouche, which no regular expression can balance.

     ONE recogniser, `heading_at`, is built on this and is shared by everything
     that asks "is this line a heading?" — `outline`'s view, the prose mask,
     and the proof extent.  There were once two patterns here, tight and wide
     respectively, on the reasoning that a VIEW wants no false positives while
     a MASK cannot afford a false negative.  Both instincts are right in
     isolation and the conclusion was wrong: a heading is a fact about Isar,
     not about the consumer, and a third asker (`proof_extent`) then disagreed
     with both.

     The negative lookahead is what the combined pattern got implicitly from
     demanding an opener next: without it `sections` leads with `section`. */
  val HEADING_LEAD_RE: Pattern =
    Py.compile(s"""^\\s*($heading_words)(?![A-Za-z_0-9'])""")
  val TITLE_OPEN_RE: Pattern = Py.compile(s"""^\\s*($TITLE_OPEN)(.*)""")
  val title_close: Map[String, String] =
    Map("""\<open>""" -> """\<close>""", Symbol.open_decoded -> Symbol.close_decoded,
      "\"" -> "\"")
  val UNESCAPED_QUOTE_RE: Pattern = Py.compile("(?<!\\\\)\"")

  val TEXT_OPEN_RE: Pattern =
    Py.compile("""^\s*(text|text_raw|txt)\s*(?:\\<open>|""" + Symbol.open_decoded + ")")
  val TEXT_BARE_RE: Pattern = Py.compile("""^\s*(text|text_raw|txt)\s*$""")
  val COMMENT_LINE_RE: Pattern =
    Py.compile("""\\<comment>\s*(?:\\<open>|""" + Symbol.open_decoded + """)(.*)$""")
  val CART_TOKEN_RE: Pattern =
    Py.compile("""\\<open>|""" + Symbol.open_decoded + """|\\<close>|""" + Symbol.close_decoded)

  val cart_open: List[String] = List("""\<open>""", Symbol.open_decoded)
  val cart_close: List[String] = List("""\<close>""", Symbol.close_decoded)
  val MARGINAL = """\<comment>"""


  /* --- span boundaries --- */

  /* Outer commands that declare nothing and so are never indexed, but which
     still BOUND the declaration above them: without these an `instance` proof,
     a `lemmas` alias or the `end` of an enclosing block falls inside the
     preceding declaration's span. */
  val span_boundary_commands: Set[String] =
    Set("begin", "end", "instance", "instantiation", "interpretation",
      "sublocale", "locale", "context", "declare", "lemmas", "notation",
      "no_notation", "syntax", "no_syntax", "translations",
      "code_printing", "export_code", "code_datatype", "code_reflect",
      "typedecl", "typedef", "consts", "print_translation") ++
      Regions.ml_body_commands ++ Regions.ml_file_commands

  val LEADING_CMD_RE: Pattern = Py.compile("""^([A-Za-z][A-Za-z_0-9]*)""")


  /* --- target blocks --- */

  val BLOCK_TOKEN_RE: Pattern =
    Py.compile("""(?<![A-Za-z_0-9'@])(begin|end)(?![A-Za-z_0-9'])""")
  val TARGET_OPEN_RE: Pattern = Py.compile(
    """^(theory|locale|class|context|instantiation|overloading""" +
      """|bundle|open_bundle|experiment|notepad)(?![A-Za-z_0-9'])\s*(.*)$""")
  val TARGET_NAME_RE: Pattern =
    Py.compile(s"""(?:$ISA_MARKUP|[A-Za-z_])(?:$ISA_MARKUP|[\\w'.])*""")
  val opener_first_chars: Set[Char] = "bceilnot".toSet
  val target_kinds: Set[String] = Set("locale", "class", "context", "instantiation")
  val anon_openers: Set[String] = Set("overloading", "experiment", "notepad")
  val not_a_target_name: Set[String] = Set(
    "begin", "fixes", "assumes", "notes", "defines", "includes",
    "constrains", "obtains")
  val IN_TARGET_RE: Pattern = Py.compile("""\(\s*in\s+([A-Za-z_][A-Za-z_0-9'.]*)\s*\)""")


  /* ------------------------------------------------------------------ */
  /* small helpers                                                      */
  /* ------------------------------------------------------------------ */

  private def starts_with_any(s: String, prefixes: List[String]): Boolean =
    prefixes.exists(s.startsWith)

  /* bisect_right over a sorted Int array */
  private def bisect_right(keys: Array[Int], x: Int): Int = {
    var lo = 0
    var hi = keys.length
    while (lo < hi) {
      val mid = (lo + hi) / 2
      if (x < keys(mid)) hi = mid else lo = mid + 1
    }
    lo
  }

  /* A 1-indexed line mask, sized so a probe at line n (or an n+1 sentinel)
     stays in bounds. */
  def line_mask(n: Int, spans: Iterable[(Int, Int)]): Array[Boolean] = {
    val mask = new Array[Boolean](n + 2)
    for ((lo0, hi0) <- spans) {
      val lo = lo0 max 1
      val hi = hi0 min n
      var i = lo
      while (i <= hi) { mask(i) = true; i += 1 }
    }
    mask
  }

  /* Index just past the close matching the open token at `start`; -1 if
     unbalanced.  A depth counter is the least machinery a non-regular nesting
     construct needs, and precisely what a regular expression cannot supply.
     `start` lets a caller scan a delimiter that opens part-way into the text
     (the shape scanner skips a cited cartouche in command position). */
  def balanced_end(s: String, open_tok: String, close_tok: String, start: Int = 0): Int = {
    var depth = 0
    var i = start
    while (i < s.length) {
      if (s.startsWith(open_tok, i)) { depth += 1; i += open_tok.length }
      else if (s.startsWith(close_tok, i)) {
        depth -= 1
        i += close_tok.length
        if (depth == 0) return i
      }
      else i += 1
    }
    -1
  }

  def balanced_paren_end(s: String): Int = balanced_end(s, "(", ")")
  def balanced_cartouche_end(s: String): Int = balanced_end(s, """\<open>""", """\<close>""")


  /* ------------------------------------------------------------------ */
  /* names                                                              */
  /* ------------------------------------------------------------------ */

  /* Every formal-comment marker, in both spellings.  A declaration may carry
     any of them before its name: `\<comment>` glosses it, and
     `definition\<^marker>\<open>tag important\<close> foo` tags it for the document
     — which is written without a space after the keyword, so the marker is
     part of what stands between the command and the name. */
  val annotation_markers: List[String] =
    List(Symbol.comment, Symbol.comment_decoded, Symbol.cancel, Symbol.cancel_decoded,
      Symbol.latex, Symbol.latex_decoded, Symbol.marker, Symbol.marker_decoded).distinct

  /* `s.lstrip()` with every leading formal comment removed, along with the
     cartouche each of them owns.

     Isabelle's lexer skips all four of them wherever a token may appear, so
     this is what stands between a command keyword and the thing that actually
     follows it — a declaration's NAME, or a heading's TITLE.  Written once for
     both: they are the same grammatical position, and having two of these was
     how `strip_decl_prefix` came to know about markers while the heading
     recogniser did not.

     Balanced, not "to the first `\<close>`": a marker body may itself hold a
     cartouche, as `\<^marker>\<open>contributor \<open>Martin
     Desharnais\<close>\<close>` does.  A comment that runs past the end of
     this line stops the scan and is left in place, so the caller sees an
     unparseable rest rather than a wrong answer. */
  def skip_formal_comments(s0: String): String = {
    var s = Py.lstrip(s0)
    var go = true
    while (go && s.nonEmpty) {
      annotation_markers.find(s.startsWith) match {
        case None => go = false
        case Some(marker) =>
          var rest = Py.lstrip(s.substring(marker.length))
          val open_tok = cart_open.find(rest.startsWith)
          if (open_tok.isDefined) {
            val k = balanced_end(rest, open_tok.get, cart_close(cart_open.indexOf(open_tok.get)))
            if (k < 0) go = false      // comment runs past this line
            else { rest = Py.lstrip(rest.substring(k)); s = rest }
          }
          else s = rest
      }
    }
    s
  }

  /* Drop the syntactic noise that can sit between a keyword and the name: a
     command modifier `(in foo)` / `(sequential)`, a leading formal comment,
     and — for type declarations — leading type arguments. */
  def strip_decl_prefix(s0: String, typevars: Boolean): String = {
    var s = s0
    var go = true
    while (go && s.nonEmpty) {
      if (s.charAt(0) == '(') {
        val j = balanced_paren_end(s)
        if (j < 0) go = false else s = Py.lstrip(s.substring(j))
      }
      else if (starts_with_any(s, annotation_markers)) {
        val skipped = skip_formal_comments(s)
        if (skipped == s) go = false else s = skipped
      }
      else if (typevars && s.charAt(0) == '\'') {
        Py.matches_at_start(TYPEVAR_RE, s) match {
          case Some(m) => s = s.substring(m.end())
          case None => go = false
        }
      }
      else go = false
    }
    s
  }

  /* A quoted spelling counts as a NAME only when it forms a label — closing
     quote, optional [attributes], then ':'.  Otherwise the quotes hold the
     statement of an anonymous lemma. */
  def name_from(s: String, require_label: Boolean): String = {
    val mq = Py.matches_at_start(QUOTED_NAME_RE, s)
    if (mq.isDefined && (!require_label || Py.matches_at(LABEL_AFTER_RE, s, mq.get.end()).isDefined))
      mq.get.group(1)
    else Py.matches_at_start(SYM_NAME_RE, s) match {
      case None => "?"
      case Some(m) =>
        val name = m.group(1)
        if (starts_with_any(name, reserved_name_prefixes) || reserved_name_words(name)) "?"
        else name
    }
  }

  def parse_name(text_after_tag: String): String =
    name_from(strip_decl_prefix(Py.strip(text_after_tag), typevars = false), require_label = true)

  def parse_typedecl_name(text_after_tag: String): String =
    name_from(strip_decl_prefix(Py.strip(text_after_tag), typevars = true), require_label = false)

  /* Head name of an implicit-name definition written as a quoted equation:
     `abbreviation "language_ltlc \<phi> \<equiv> ..."`.  Returns '?' unless the
     quoted body really contains a definitional connective, so an anonymous
     `lemma "P"` is never mistaken for a definition. */
  def lhs_head_name(text_after_tag: String): String = {
    val s = strip_decl_prefix(Py.strip(text_after_tag), typevars = false)
    Py.matches_at_start(QUOTED_NAME_RE, s) match {
      case Some(mq) if Py.found(DEF_CONNECTIVE_RE, mq.group(1)) =>
        name_from(Py.strip(mq.group(1)), require_label = false)
      case _ => "?"
    }
  }

  def parse_def_name(text_after_tag: String): String = {
    val name = parse_name(text_after_tag)
    if (name != "?") name else lhs_head_name(text_after_tag)
  }


  /* ------------------------------------------------------------------ */
  /* command recognition                                                */
  /* ------------------------------------------------------------------ */

  def match_decl(line: String, table: Map[String, String]): Option[(String, String, String)] =
    Py.matches_at_start(DECL_RE, line) match {
      case Some(m) =>
        val kw = m.group(1)
        val tag = tag_map(kw)
        Some((kw, tag, route_for(kw, tag)))
      case None =>
        if (table.isEmpty) None
        else Py.matches_at_start(LEAD_TOKEN_RE, line).flatMap { m =>
          table.get(m.group(1)).map(tag => (m.group(1), tag, route_for(m.group(1), tag)))
        }
    }

  /* `match_decl` on the first command token of an OUTER-syntax line, plus the
     column the keyword starts at — the views are length-preserving, so one
     index serves both. */
  def match_decl_at(outer_line: String,
    table: Map[String, String]
  ): (Option[(String, String, String)], Int) = {
    val stripped = Py.lstrip(outer_line)
    if (stripped.isEmpty) (None, 0)
    else (match_decl(stripped, table), outer_line.length - stripped.length)
  }

  /* Does this OUTER line open a span-bounding command?  A blank line ends
     nothing in Isar, but `end`, `context`, `lemmas`, an `ML` block and the
     rest genuinely close whatever preceded them. */
  def is_boundary_at(outer_line: String): Boolean =
    Py.matches_at_start(LEADING_CMD_RE, Py.lstrip(outer_line))
      .exists(m => span_boundary_commands(m.group(1)))

  /* A decl keyword may stand alone with the name on a following line.  Bound
     the forward scan so a truncated file cannot run on looking for a name that
     is not there.  Counted over BLANK and `text` lines only: a formal comment
     is one lexer token however far it wraps, so it is skipped without
     charge. */
  private val NAME_LOOKAHEAD_LINES = 3

  /* Absolute cap on the walk, so "skipped without charge" still cannot run
     away on a file whose comment never closes.  Generous against real source:
     the longest pre-name comment measured over the AFP and the distribution is
     4 lines. */
  private val NAME_SCAN_LINES = 40

  /* The name for a decl whose keyword stood alone: scan forward from the
     0-indexed line `start`, skipping blank / formal-comment / `text` lines,
     and parse the name from the FIRST CONTENT LINE.

     `live` is the redacted view, and asking it is what makes a MULTI-LINE
     formal comment work.  Testing the raw text for a leading `\<comment>`
     recognises only the comment's FIRST line, so a comment that wraps left its
     continuation looking like content and the name was read out of the prose:
     `HOL/UNITY/WFair:35` indexed `is`, out of "the rest IS generic to all
     forms of fairness", and never indexed `transient` at all.  It also caught
     only one of the four spellings, so a `\<^marker>` on its own line — which
     HOL/Analysis writes on hundreds of declarations — yielded `?`.  The region
     scan already knows all four, and a `(* ... *)` besides: every such line is
     blank in `live`.

     A redacted line does not spend the budget.  That bound exists so a
     truncated file cannot run on looking for a name that is not there, and a
     formal comment is ONE token to Isabelle's lexer however many lines it
     spans — its own cartouche bounds it — so charging per line would make the
     guard fire on well-formed source.  Blank and `text` lines still spend it,
     and `NAME_SCAN_LINES` caps the walk outright. */
  def lookahead_name(lines: Array[String], start: Int, table: Map[String, String],
    parse_fn: String => String, outer: Array[String], live: Array[String]
  ): String = {
    val limit = lines.length min (start + NAME_SCAN_LINES)
    var budget = NAME_LOOKAHEAD_LINES
    var j = start
    while (j < limit && budget > 0) {
      val stripped = Py.strip(lines(j))
      /* Blank in `live` but not in the source == the region scan redacted it.
         A genuinely empty line is not "redacted". */
      val redacted = stripped.nonEmpty && live != null && Py.strip(live(j)).isEmpty
      if (stripped.isEmpty || redacted || stripped.startsWith(MARGINAL) ||
          Py.matches_start(TEXT_OPEN_RE, lines(j))) {
        if (!redacted) budget -= 1
        j += 1
      }
      else {
        val probe = if (outer != null) outer(j) else lines(j)
        return if (match_decl_at(probe, table)._1.isDefined) "?" else parse_fn(stripped)
      }
    }
    "?"
  }


  /* ------------------------------------------------------------------ */
  /* the extra names one declaration binds                              */
  /* ------------------------------------------------------------------ */

  private def joined(view: Array[String], start: Int, end: Int): String =
    view.slice(start - 1, end min view.length).mkString("\n")

  /* Named rules/equations of a `where`-clause declaration, in source order.
     Read on the OUTER view, so a `|` or an `x:` inside a term cannot be read
     as a rule separator or a label. */
  def rule_labels(outer: Array[String], start: Int, end: Int, own: String): List[String] = {
    if (start < 1 || end < start) Nil
    else {
      val found = new mutable.ListBuffer[String]
      val m = RULE_LABEL_RE.matcher(joined(outer, start, end))
      while (m.find()) {
        val label = m.group(1)
        if (label != own && !found.contains(label)) found += label
      }
      found.toList
    }
  }

  /* Does the next line carrying outer syntax begin `|`?  A rule list is
     routinely spaced out for legibility, and a line beginning `|` cannot start
     a new command, so it can only continue this one. */
  def bar_continues(outer: Array[String], i0: Int): Boolean = {
    var i = i0
    while (i < outer.length && Py.strip(outer(i)).isEmpty) i += 1
    i < outer.length && Py.matches_start(BAR_LINE_RE, outer(i))
  }

  /* A record's field list has no `|` to pick it back up after a blank line;
     the marker is the field's own `::`.  Both views, because a quoted field
     name is blanked on the outer one. */
  def field_continues(outer: Array[String], live: Array[String], i0: Int): Boolean = {
    var i = i0
    while (i < outer.length && Py.strip(outer(i)).isEmpty && Py.strip(live(i)).isEmpty) i += 1
    i < outer.length &&
      (Py.found(RECORD_FIELD_RE, outer(i)) || Py.found(RECORD_QUOTED_FIELD_RE, live(i)))
  }

  /* Constants declared alongside `own` by one command — read from the HEAD
     only, since `inductive_set p for A :: "..." and I :: "..."` fixes
     PARAMETERS with `and`. */
  def and_siblings(outer: Array[String], start: Int, end: Int, own: String,
    tag: String
  ): List[String] = {
    if (start < 1 || end < start || !sibling_tags(tag)) Nil
    else {
      var head = joined(outer, start, end)
      Py.search(HEAD_END_RE, head).foreach(m => head = head.substring(0, m.start()))
      val found = new mutable.ListBuffer[String]
      val m = AND_NAME_RE.matcher(head)
      while (m.find()) {
        val name = if (m.group(1) != null) m.group(1) else m.group(2)
        if (name != own && !found.contains(name)) found += name
      }
      found.toList
    }
  }

  /* The constructors, discriminators and selectors a `datatype` declares.
     Read on the outer view, so argument TYPES — inner syntax, full of names
     that are not constructors — are blanked before the scan. */
  def constructors(outer: Array[String], start: Int, end: Int,
    own: String
  ): List[(String, String)] = {
    if (start < 1 || end < start) Nil
    else {
      val text = joined(outer, start, end)
      if (!text.contains("=")) Nil
      else {
        val found = new mutable.ListBuffer[(String, String)]
        val seen = mutable.Set(own)
        def add(name: String, kind: String): Unit =
          if (name != null && name.nonEmpty && !seen(name)) { seen += name; found += ((name, kind)) }
        for (alt <- text.split("=", 2)(1).split("\\|", -1)) {
          Py.matches_at_start(ALT_HEAD_RE, alt).foreach { m =>
            add(m.group(1), "discriminator")
            add(m.group(2), "constructor")
          }
          val s = SELECTOR_RE.matcher(alt)
          while (s.find()) add(s.group(1), "selector")
        }
        found.toList
      }
    }
  }

  /* The selector constants a `record` declares.  A separate scan from
     `constructors`, not a widening of it: a datatype's `=` introduces its
     alternatives, a record's introduces its PARENT TYPE. */
  def record_fields(outer: Array[String], live: Array[String], start: Int,
    end: Int
  ): List[(String, String)] = {
    if (start < 1 || end < start) Nil
    else {
      val hits = new mutable.ListBuffer[(Int, Int, String)]
      var n = start - 1
      val stop = end min outer.length
      while (n < stop) {
        /* Line by line, NOT over the joined span: `\s*` matches a newline, so a
           joined scan pairs an unquoted type at the end of one line with the
           `::` of the next. */
        val m = RECORD_FIELD_RE.matcher(outer(n))
        while (m.find()) hits += ((n, m.start(1), m.group(1)))
        val q = RECORD_QUOTED_FIELD_RE.matcher(live(n))
        while (q.find()) hits += ((n, q.start(1), q.group(1)))
        n += 1
      }
      val found = new mutable.ListBuffer[(String, String)]
      val seen = mutable.Set.empty[String]
      for ((_, _, name) <- hits.toList.sortBy(h => (h._1, h._2, h._3))) {
        if (!seen(name)) { seen += name; found += ((name, "field")) }
      }
      found.toList
    }
  }

  /* The named facts a locale/class head binds.  An `and` continues whichever
     element introduced it, so the last non-`and` keyword decides the kind —
     and a parameter-binding keyword clears it, so the `and`s of a `fixes`
     group bind nothing. */
  def locale_facts(outer: Array[String], start: Int, end: Int,
    own: String
  ): List[(String, String)] = {
    if (start < 1 || end < start) Nil
    else {
      val found = new mutable.ListBuffer[(String, String)]
      val seen = mutable.Set(own)
      var current = ""
      val text = joined(outer, start, end)
      val m = LOCALE_KW_RE.matcher(text)
      while (m.find()) {
        val kw = m.group(1)
        if (kw != "and") current = locale_elem_kind.getOrElse(kw, "")
        if (current.nonEmpty) {
          Py.matches_at(LOCALE_LABEL_RE, text, m.end()) match {
            case Some(label) if !seen(label.group(1)) =>
              seen += label.group(1)
              found += ((label.group(1), current))
            case _ =>
          }
        }
      }
      found.toList
    }
  }

  /* One `axiomatization` line: the names it declares, and whether it opened
     with a continuation keyword.  Names PLURAL, because `and` separates items
     WITHIN a line as readily as it ends one. */
  def axiom_line(text: String): (List[String], Boolean) = {
    var stripped = Py.strip(text)
    val cont = Py.matches_start(STATEMENT_CONT_RE, stripped)
    val names = new mutable.ListBuffer[String]
    var go = true
    while (go && stripped.nonEmpty) {
      Py.matches_at_start(STATEMENT_CONT_RE, stripped) match {
        case Some(kw) => stripped = Py.lstrip(stripped.substring(kw.end()))
        case None =>
          Py.matches_at_start(AXIOM_NAME_RE, stripped) match {
            case None => go = false
            case Some(m) =>
              names += m.group(1)
              Py.search_from(AXIOM_AND_RE, stripped, m.end()) match {
                case None => go = false
                case Some(nxt) => stripped = Py.lstrip(stripped.substring(nxt.end()))
              }
          }
      }
    }
    (names.toList, cont)
  }


  /* ------------------------------------------------------------------ */
  /* declaration bodies                                                 */
  /* ------------------------------------------------------------------ */

  /* Accumulate a declaration's body from 0-indexed line `i` (the line after
     the declaration line).  Shared by the `def` and `typedecl` routes: what
     ends a `datatype` is what ends a `fun`. */
  def scan_decl_body(lines: Array[String], outer: Array[String], live: Array[String],
    open_at: Array[Boolean], table: Map[String, String], i0: Int, decl_line: Int,
    keyword: String = ""
  ): (Int, Int, List[String]) = {
    var i = i0
    var decl_end_line = decl_line
    val body = new mutable.ListBuffer[String]
    var past_where = false
    var go = true
    while (go && i < lines.length) {
      val cline = lines(i)
      /* Nothing terminates a declaration from INSIDE its own term. */
      val inside = open_at(i)
      if (Py.matches_start(BLANK_RE, cline) && !inside) {
        if (!(bar_continues(outer, i + 1) ||
              (keyword == "record" && field_continues(outer, live, i + 1)))) go = false
        else i += 1
      }
      else if (match_decl_at(outer(i), table)._1.isDefined ||
               (!inside && is_boundary_at(outer(i)))) go = false
      else {
        val stripped = Py.strip(cline)
        if (!inside && stripped.startsWith("text ")) go = false
        /* A formal comment is not a command — Isabelle's lexer skips all four
           of them wherever a token may appear — so one cannot END a
           declaration either, on any route.  Skip it and keep scanning.

           This was a `break` gated to `record`, where breaking cost 11 of the
           AFP's 507 records every field they declare; the gate was left narrow
           because whether the break was right for the other routes had not
           been measured.  It is not: the break truncates the
           keyword-comment-name shape, where the comment sits before the name
           and the recorded body collapses onto the keyword line —
           `HOL/Hoare/SchorrWaite:14`'s `rel` reported body 14..14 for a
           declaration running to 17.  `body_end` is the documented safe
           relocation cut, so a consumer cutting there leaves the declaration
           behind.

           Asking the LIVE view rather than testing for a leading `\<comment>`
           is what makes this cover a WRAPPED comment (only its first line
           carries the marker), the other three spellings, and `(* ... *)`.
           The comment is SKIPPED rather than appended, so `decl_end_line`
           still ends on the last LIVE line and a trailing note does not
           extend it.  Running on into the next declaration is not a risk: the
           decl and boundary tests above already break there. */
        else if (!inside && stripped.nonEmpty && Py.strip(live(i)).isEmpty) i += 1
        else {
          val where_on_this_line = Py.found(WHERE_RE, stripped)
          body += ("  " + stripped)
          i += 1
          decl_end_line = i
          if (keyword == "definition" || keyword == "abbreviation") {
            if (past_where && stripped.contains("\"") && !(i < lines.length && open_at(i)))
              go = false
            else if (where_on_this_line) past_where = true
          }
        }
      }
    }
    (i, decl_end_line, body.toList)
  }


  /* ------------------------------------------------------------------ */
  /* target blocks                                                      */
  /* ------------------------------------------------------------------ */

  /* A target's name is read from the LIVE view (see `target_opener`), so a
     document marker between the keyword and the name -- `locale\<^marker>\
     <open>tag important\<close> sigma_algebra =`, which HOL/Analysis writes on
     19 of its locales and classes -- is already blanked before this sees it:
     `Regions` classifies all four formal comments as noise.  There is no
     marker-skipping step here for that reason, and it is the reason: the two
     views must not each have their own idea of what a marker is.  The
     `reserved_name_prefixes` guard below is the belt to that braces -- it
     declines a bare `\<open>...`, which survives `live` because a cartouche is
     inner syntax rather than noise. */
  def target_name(rest: String): String = {
    Py.matches_at_start(QUOTED_NAME_RE, rest) match {
      case Some(mq) => mq.group(1)
      case None =>
        Py.matches_at_start(TARGET_NAME_RE, rest) match {
          case None => ""
          case Some(mn) =>
            val nm = mn.group(0)
            /* A cartouche survives the live view, so reading a name from live
               must decline `\<open>...` the way `name_from` does. */
            if (not_a_target_name(nm) || starts_with_any(nm, reserved_name_prefixes)) "" else nm
        }
    }
  }

  /* `(kind, name)` if `segment` opens a target block.  `segment` is the OUTER
     view, which is what makes this a command position rather than a word
     inside a term — but outer blanks a QUOTED target name, so when
     `live_segment` is given the name is read from it while the keyword is
     still matched on outer. */
  def target_opener(segment: String, live_segment: String): Option[(String, String)] = {
    val stripped = Py.lstrip(segment)
    Py.matches_at_start(TARGET_OPEN_RE, stripped).map { m =>
      val kind = m.group(1)
      if (anon_openers(kind)) (kind, "")
      else {
        var rest = m.group(2)
        if (live_segment != null) {
          /* Anchor on the END OF THE KEYWORD, not on group 2: the pattern's
             `\s*` is greedy and outer has blanked a quoted name to spaces, so
             group 2 can begin past the very columns the name occupies. */
          val at = segment.length - stripped.length + m.end(1)
          rest = if (at <= live_segment.length) Py.lstrip(live_segment.substring(at)) else ""
        }
        (kind, target_name(rest))
      }
    }
  }

  /* Per line (0-indexed), the chain of enclosing NAMED target blocks.  Every
     target block — whatever command introduced it — opens with the token
     `begin` and closes with `end`, so there is ONE pair to track rather than a
     table of openers and closers.  Read in positional order, because
     `context srules begin context begin` occurs on one line. */
  def block_stacks(outer: Array[String], live: Array[String]): Array[List[(String, String)]] = {
    val stacks = new Array[List[(String, String)]](outer.length)
    val stack = new mutable.ArrayBuffer[(String, String)]
    var cur: List[(String, String)] = Nil
    var pending: Option[(String, String)] = None
    var i = 0
    while (i < outer.length) {
      val line = outer(i)
      stacks(i) = cur
      val lv = if (live != null && i < live.length) live(i) else null
      def seg(a: Int, b: Int): (String, String) =
        (line.substring(a, b), if (lv == null) null else lv.substring(a min lv.length, b min lv.length))
      if (line.contains("begin") || line.contains("end")) {
        var pos = 0
        val m = BLOCK_TOKEN_RE.matcher(line)
        while (m.find()) {
          val (o, l) = seg(pos, m.start())
          target_opener(o, l).foreach(op => pending = Some(op))
          pos = m.end()
          if (m.group(1) == "begin") {
            stack += pending.getOrElse(("?", ""))
            pending = None
          }
          else if (stack.nonEmpty) stack.remove(stack.length - 1)
        }
        val (o, l) = seg(pos, line.length)
        target_opener(o, l).foreach(op => pending = Some(op))
        cur = stack.toList.filter(b => b._2.nonEmpty && target_kinds(b._1))
      }
      else if (opener_first_chars.contains(Py.lstrip(line).headOption.getOrElse(' ')))
        target_opener(line, lv).foreach(op => pending = Some(op))
      i += 1
    }
    stacks
  }

  def attach_targets(entries: List[Entry], outer: Array[String], live: Array[String]): Unit = {
    val stacks = block_stacks(outer, live)
    for (e <- entries) {
      val idx = e.thy_line - 1
      if (idx >= 0 && idx < stacks.length) {
        e.blocks = stacks(idx)
        Py.search(IN_TARGET_RE, outer(idx)).foreach(m => e.in_target = m.group(1))
      }
    }
  }


  /* ------------------------------------------------------------------ */
  /* headings and document blocks                                       */
  /* ------------------------------------------------------------------ */

  /* `(level, opener, rest, offset)` if line `i` is a heading command; `offset`
     is 1 for the split form, whose title opens on the next line.

     `prose` marks the lines already known to be document-block bodies or ML,
     where a COMMAND cannot start — so a heading keyword there is only an
     English word. */
  def heading_at(lines: Array[String], i: Int,
    prose: Array[Boolean]
  ): Option[(String, String, String, Int)] = {
    if (prose != null && prose(i + 1)) None
    else Py.matches_at_start(HEADING_LEAD_RE, lines(i)) match {
      case None => None
      case Some(m) =>
        /* A document marker may sit between the command and its title.
           Skipped here rather than read off the outer view, because a
           heading's TITLE is a cartouche: the view that blanks the marker
           blanks the title with it, so this one has to read the RAW line. */
        val rest = skip_formal_comments(lines(i).substring(m.end()))
        Py.matches_at_start(TITLE_OPEN_RE, rest) match {
          case Some(here) => Some((m.group(1), here.group(1), here.group(2), 0))
          case None =>
            /* The split form: the command alone on its line, its title on the
               next — the same shape `TEXT_BARE_RE` handles for document
               blocks.  Only ever a one-line lookahead, so a bare word cannot
               reach an unrelated title. */
            if (rest.isEmpty && i + 1 < lines.length)
              Py.matches_at_start(TITLE_OPEN_RE, lines(i + 1))
                .map(nxt => (m.group(1), nxt.group(1), nxt.group(2), 1))
            else None
        }
    }
  }

  def extract_sections(lines: Array[String],
    prose: List[(Int, Int)]
  ): List[(String, String, Int)] = {
    val mask = if (prose.isEmpty) null else line_mask(lines.length, prose)
    val out = new mutable.ListBuffer[(String, String, Int)]
    var i = 0
    while (i < lines.length) {
      heading_at(lines, i, mask).foreach { case (level, opener, rest, _) =>
        val close_idx = rest.indexOf(title_close(opener))
        val title = if (close_idx >= 0) rest.substring(0, close_idx) else rest
        out += ((level, Py.strip(title), i + 1))
      }
      i += 1
    }
    out.toList
  }

  /* 0-indexed line of the close matching a cartouche opened at `start`. */
  def find_balanced_close(lines: Array[String], start: Int): Int = {
    var depth = 0
    var i = start
    while (i < lines.length) {
      depth += cart_open.map(count_of(lines(i), _)).sum
      depth -= cart_close.map(count_of(lines(i), _)).sum
      if (depth <= 0) return i
      i += 1
    }
    start
  }

  private def count_of(s: String, tok: String): Int = {
    var n = 0
    var i = s.indexOf(tok)
    while (i >= 0) { n += 1; i = s.indexOf(tok, i + tok.length) }
    n
  }

  /* Counting, not balancing: quoted strings do not nest, so a `"..."` title
     ends at the next unescaped quote wherever it falls. */
  def find_quoted_close(lines: Array[String], start: Int): Int = {
    var count = 0
    val m = UNESCAPED_QUOTE_RE.matcher(lines(start))
    while (m.find()) count += 1
    if (count >= 2) start
    else {
      var i = start + 1
      while (i < lines.length) {
        if (Py.found(UNESCAPED_QUOTE_RE, lines(i))) return i
        i += 1
      }
      start
    }
  }

  /* [(start, end)] (1-indexed inclusive) per balanced cartouche block that
     `opens` reports.  `opens` returns the OFFSET at which the cartouche itself
     opens, because balancing from the wrong line would let a bodyless
     `\<comment>` swallow the next unrelated cartouche. */
  def scan_balanced_blocks(lines: Array[String],
    opens: (Array[String], Int) => Option[Int]
  ): List[(Int, Int)] = {
    val out = new mutable.ListBuffer[(Int, Int)]
    var i = 0
    while (i < lines.length) {
      opens(lines, i) match {
        case None => i += 1
        case Some(at) =>
          val end = find_balanced_close(lines, i + at)
          out += ((i + 1, end + 1))
          i = end + 1
      }
    }
    out.toList
  }

  def extract_text_blocks(lines: Array[String]): List[(Int, Int)] =
    scan_balanced_blocks(lines, (ls, i) =>
      if (Py.matches_start(TEXT_OPEN_RE, ls(i))) Some(0)
      else if (Py.matches_start(TEXT_BARE_RE, ls(i)) && i + 1 < ls.length &&
               cart_open.exists(ls(i + 1).contains)) Some(1)
      else None)

  def extract_heading_spans(lines: Array[String], prose: List[(Int, Int)]): List[(Int, Int)] = {
    val mask = if (prose.isEmpty) null else line_mask(lines.length, prose)
    val out = new mutable.ListBuffer[(Int, Int)]
    var i = 0
    while (i < lines.length) {
      heading_at(lines, i, mask) match {
        case None => i += 1
        case Some((_, opener, _, at)) =>
          val end =
            if (opener == "\"") find_quoted_close(lines, i + at)
            else find_balanced_close(lines, i + at)
          out += ((i + 1, end + 1))
          i = end + 1
      }
    }
    out.toList
  }

  def extract_comment_ranges(lines: Array[String]): List[(Int, Int)] =
    scan_balanced_blocks(lines, (ls, i) => if (ls(i).contains(MARGINAL)) Some(0) else None)

  /* `rest` up to the `\<close>` MATCHING the cartouche already open.
     Cartouches nest, and a marginal note nests more often than not: an
     assumption gloss names the term it is about, and naming a term means
     quoting it. */
  def cartouche_body(rest: String): String = {
    var depth = 1
    val m = CART_TOKEN_RE.matcher(rest)
    while (m.find()) {
      if (cart_open.contains(m.group())) depth += 1
      else {
        depth -= 1
        if (depth == 0) return rest.substring(0, m.start())
      }
    }
    rest
  }

  /* `notes` filters out a `\<comment>` that is itself inside a commented-out
     block or an ML body: text alone cannot tell those apart — they are spelled
     identically — but the region scan can. */
  /* `notes` carries the columns a genuine `\<comment>` opens at, one flat entry
     per column (see `Regions.Spans`).  Membership is a scan of that line's run,
     which is at most a handful of columns — a `Set` per line cost 8 bytes of
     array slot on every line in the corpus to answer a question asked only
     where `COMMENT_LINE_RE` already matched. */
  private def note_at(notes: Regions.Spans, line: Int, col: Int): Boolean = {
    if (line + 1 >= notes.bound.length) false
    else {
      var k = notes.bound(line)
      val end = notes.bound(line + 1)
      var found = false
      while (k < end && !found) { if (notes.lo(k) == col) found = true; k += 1 }
      found
    }
  }

  def extract_comment_lines(lines: Array[String], notes: Regions.Spans): List[(Int, String)] = {
    val out = new mutable.ListBuffer[(Int, String)]
    var i = 0
    while (i < lines.length) {
      Py.search(COMMENT_LINE_RE, lines(i)).foreach { m =>
        if (notes == null || note_at(notes, i, m.start()))
          out += ((i + 1, Py.strip(cartouche_body(m.group(1)))))
      }
      i += 1
    }
    out.toList
  }

  /* 1-indexed lines that open a span-bounding outer command, reported at the
     head of any blank run before the command so the preceding entry's span
     ends on its last real line. */
  def structural_command_lines(lines: Array[String], noise_ranges: List[(Int, Int)],
    outer: Array[String]
  ): List[Int] = {
    val masked = mutable.Set.empty[Int]
    for ((start, end) <- noise_ranges; k <- start to end) masked += k
    val out = new mutable.ListBuffer[Int]
    var i = 0
    while (i < lines.length) {
      val line_no = i + 1
      if (!masked(line_no)) {
        val probe = Py.lstrip(if (outer != null) outer(i) else lines(i))
        Py.matches_at_start(LEADING_CMD_RE, probe).foreach { m =>
          if (span_boundary_commands(m.group(1))) {
            var b = line_no
            while (b > 1 && Py.strip(lines(b - 2)).isEmpty) b -= 1
            out += b
          }
        }
      }
      i += 1
    }
    out.toList
  }


  /* ------------------------------------------------------------------ */
  /* spans, preambles, annotations                                      */
  /* ------------------------------------------------------------------ */

  private def entries_by_line(entries: List[Entry]): (Array[Entry], Array[Int]) = {
    val located = entries.filter(_.thy_line > 0).sortBy(_.thy_line).toArray
    (located, located.map(_.thy_line))
  }

  /* The boundary above an entry is the NEXT entry's src_start — its leading
     `text` preamble if it has one — so a following entry's docstring is
     charged to that entry, not folded into the preceding entry's span. */
  def compute_spans(entries: List[Entry], section_lines: List[Int], total_lines: Int): Unit = {
    val structural =
      (entries.filter(_.thy_line > 0).map(_.src_start) ::: section_lines).distinct.sorted.toArray
    for (e <- entries) {
      /* Bisect on the DECLARATION line, not src_start: the boundary must lie
         strictly after this entry's own decl, so an entry's own preamble start
         never reads as its end. */
      val idx = bisect_right(structural, e.thy_line)
      e.thy_end = if (idx < structural.length) structural(idx) - 1 else total_lines
    }
  }

  /* A text block is a preamble when it is adjacent to the entry below it AND
     small: a 500-line section narrative just before the first definition is
     not that definition's docstring, it is the chapter's introduction. */
  def attach_preambles(entries: List[Entry], lines: Array[String],
    text_blocks: List[(Int, Int)]
  ): Unit = {
    val PREAMBLE_MAX_LINES = 30
    val (located, keys) = entries_by_line(entries)
    for ((tb_start, tb_end) <- text_blocks if tb_end - tb_start + 1 <= PREAMBLE_MAX_LINES) {
      val idx = bisect_right(keys, tb_end)
      if (idx < located.length) {
        val e = located(idx)
        val gap = lines.slice(tb_end, (e.thy_line - 1) max tb_end)
        if (gap.length <= 3 && gap.forall(l => Py.strip(l).isEmpty))
          e.preamble = Some((tb_start, tb_end))
      }
    }
  }

  /* A note is owned by the entry whose span contains it, and tagged by which
     PART of that entry it sits in.  The `proof` test is made BEFORE the `decl`
     one, so the commonest fact in the corpus — a one-liner whose declaration
     line IS its proof line — keeps its roadmap. */
  def attach_annotations(entries: List[Entry], comment_lines: List[(Int, String)]): Unit = {
    val (located, keys) = entries_by_line(entries)
    for ((cline, content) <- comment_lines) {
      val idx = bisect_right(keys, cline) - 1
      if (idx >= 0) {
        val e = located(idx)
        if (cline <= e.thy_end) {
          val kind =
            if (e.proof_line > 0 && cline >= e.proof_line) "proof"
            else if (cline == e.thy_line) "decl"
            else "statement"
          e.annotations = e.annotations ::: List((cline, content, kind))
        }
      }
    }
  }

  /* Walk forward from proof_line; the last line that belongs to the proof.
     Stops at top-level documentation blocks but NOT at in-proof `\<comment>`
     annotations, which are routine inside proof bodies.

     `noise` is the WHOLE-LINE mask built from `nonisar_ranges`, and the three
     boundary tests are made only on lines that are outside it.  A boundary is
     a COMMAND, and a line holding no live Isar text holds no command: a
     commented-out `lemma old_version`, a heading inside `(* ... *)`, a `text `
     inside `(* ... *)`.  Reading them off the raw source ended the proof at
     the comment block instead of at the next real declaration, which shortened
     `body_end` — the documented safe relocation cut — for 287 AFP entries.

     The mask governs the BOUNDARY tests only.  A noise line still advances
     `last` exactly as before: whether a trailing comment block belongs to the
     proof is a separate question, and answering it here would be a second
     change hiding inside this one. */
  def proof_extent(lines: Array[String], proof_line: Int, thy_end: Int,
    noise: Array[Boolean]
  ): Int = {
    var last = proof_line
    var line_no = proof_line + 1
    var go = true
    while (go && line_no <= thy_end && line_no <= lines.length) {
      val cline = lines(line_no - 1)
      val stripped = Py.strip(cline)
      if (!noise(line_no) &&
          (stripped.startsWith("text ") || stripped.startsWith("""text\<open>""") ||
           /* `heading_at`, not a regex of its own: this was a THIRD asker of
              "is this a heading", and it disagreed — a marked heading and a
              split heading both ended a proof for `outline` and the prose mask
              but not here. */
           heading_at(lines, line_no - 1, null).isDefined ||
           /* The column-0 anchor stays.  `thy_end` is already bounded by the
              declaration positions the scan found, so an indented declaration
              never falls strictly inside a proof's span; deanchoring it here
              moves 0 records. */
           Py.matches_start(DECL_RE, cline))) go = false
      else {
        if (stripped.nonEmpty) last = line_no
        line_no += 1
      }
    }
    last
  }


  /* ------------------------------------------------------------------ */
  /* the main scan                                                      */
  /* ------------------------------------------------------------------ */

  def extract_entries(lines: Array[String], outer: Array[String], live: Array[String],
    open_at: Array[Boolean], nonisar_ranges: List[(Int, Int)], table: Map[String, String]
  ): List[Entry] = {
    val entries = new mutable.ListBuffer[Entry]
    var i = 0

    /* Lines the declaration grammar must not be applied to because they are
       not outer syntax at all.  Only the `goal` route reads this: it draws a
       distinction the outer view alone cannot — a line that is wholly PROSE
       versus one that is wholly TERM. */
    val skip = line_mask(lines.length, extract_text_blocks(lines) ::: nonisar_ranges)

    while (i < lines.length) {
      val (md, indent) = match_decl_at(outer(i), table)
      if (md.isEmpty) i += 1
      else {
        val (keyword, tag, route) = md.get
        /* The keyword occupies the same columns in `outer` as in the source —
           the views are length-preserving — so the raw text starts here. */
        val line = lines(i).substring(indent min lines(i).length)
        val decl_line = i + 1

        if (route == "target") {
          /* The name comes from `target_opener`, the same reader
             `block_stacks` uses: one parser for one grammar, so a name it
             declines to read is one no entry should carry either. */
          val opened = target_opener(outer(i), if (i < live.length) live(i) else null)
          val name = opened.map(_._2).filter(_.nonEmpty).getOrElse("?")
          val rest = Py.strip(line.substring(keyword.length min line.length))
          val (ni, decl_end_line, body) =
            scan_decl_body(lines, outer, live, open_at, table, i + 1, decl_line)
          i = ni
          val e = Entry(tag, name, (s"$tag $rest" :: body).mkString("\n"),
            thy_line = decl_line, decl_end_line = decl_end_line)
          e.bindings = locale_facts(outer, decl_line, decl_end_line, name)
          entries += e
        }
        else if (route == "typedecl") {
          val rest0 = Py.strip(line.substring(keyword.length min line.length))
          val rest = TRAILING_WHERE_RE.matcher(rest0).replaceAll("")
          var name = parse_typedecl_name(rest)
          if (name == "?" && strip_decl_prefix(rest, typevars = true).isEmpty)
            name = lookahead_name(lines, i + 1, table, parse_typedecl_name, outer, live)
          val (ni, decl_end_line, body) =
            scan_decl_body(lines, outer, live, open_at, table, i + 1, decl_line, keyword)
          i = ni
          val e = Entry(tag, name, (s"$tag $rest" :: body).mkString("\n"),
            thy_line = decl_line, decl_end_line = decl_end_line)
          e.bindings =
            if (tag == "DATATYPE") constructors(outer, decl_line, decl_end_line, name)
            else if (tag == "RECORD") record_fields(outer, live, decl_line, decl_end_line)
            else Nil
          entries += e
        }
        else if (route == "axiom") {
          /* The anchor for the lines below, and it is NOT called
             `axiomatization`: the command is not a fact and nothing can cite
             it, so a name minted from the keyword is a citable name no
             citation can ever reach — one per `axiomatization` in the corpus
             (11 in FOL, 10 in ZF), counted in `summary` as a declaration.
             `?` is the established spelling for an entry with no name of its
             own, and every site that must skip one already tests for it: the
             call-graph name set (`Usage_Graph`), caller attribution, `unused`,
             and `Commands.owner_field`.  The entry itself stays, so a line
             inside the command still resolves to it in `enclosing`. */
          entries += Entry("AXIOM", "?", "AXIOMATIZATION",
            thy_line = decl_line, decl_end_line = decl_line)
          /* The command line itself may already carry the first name —
             `axiomatization where process_finite:` — so read its remainder
             before stepping below it. */
          val head_from = (indent + keyword.length) min outer(i).length
          val (head_names, _) = axiom_line(outer(i).substring(head_from))
          for (head_name <- head_names) {
            entries += Entry("AXIOM", head_name,
              "  AXIOM " + Py.strip(line.substring(keyword.length min line.length)),
              thy_line = decl_line, decl_end_line = decl_line)
          }
          i += 1
          var go = true
          while (go && i < lines.length) {
            val (found, cont) = axiom_line(outer(i))
            if (found.nonEmpty) {
              for (name <- found) {
                entries += Entry("AXIOM", name, "  AXIOM " + Py.strip(lines(i)),
                  thy_line = i + 1, decl_end_line = i + 1)
              }
              i += 1
            }
            else if (cont) i += 1
            /* The command's END is judged on the live line, not the outer one:
               in the outer view a line that is ALL term blanks to empty, and
               would be taken for the blank that ends the scan. */
            else if (Py.strip(lines(i)).isEmpty || Py.matches_start(TOPLEVEL_RE, lines(i)))
              go = false
            else i += 1
          }
        }
        else if (route == "def") {
          val rest0 = Py.strip(line.substring(keyword.length min line.length))
          val rest = TRAILING_WHERE_RE.matcher(rest0).replaceAll("")
          val parse_fn: String => String =
            if (keyword == "definition" || keyword == "abbreviation") parse_def_name else parse_name
          var name = parse_fn(rest)
          if (name == "?" && strip_decl_prefix(rest, typevars = false).isEmpty)
            name = lookahead_name(lines, i + 1, table, parse_fn, outer, live)
          val (ni, decl_end_line, body) =
            scan_decl_body(lines, outer, live, open_at, table, i + 1, decl_line, keyword)
          i = ni
          val e = Entry(tag, name, (s"$tag $rest" :: body).mkString("\n"),
            thy_line = decl_line, decl_end_line = decl_end_line)
          e.bindings =
            and_siblings(outer, decl_line, decl_end_line, name, tag).map(s => (s, "sibling")) :::
            rule_labels(outer, decl_line, decl_end_line, name).map(s => (s, "rule"))
          entries += e
        }
        else if (route == "goal") {
          val rest = Py.strip(line.substring(keyword.length min line.length))
          val name = parse_name(rest)
          val buf = new mutable.ListBuffer[String]
          buf += s"$tag $rest"
          var decl_end_line = decl_line
          var proof_line = 0
          /* Named conjuncts: scan the `shows` region only. */
          var in_shows = Py.found(SHOWS_ANYWHERE_RE, rest)
          val conjuncts = new mutable.ListBuffer[String]
          if (in_shows) conjuncts ++= Py.find_all(CONJUNCT_RE, rest)
          i += 1

          /* A blank line ends the STATEMENT but not the search for the proof:
             `lemma foo:` / statement / blank / `proof -` is ordinary Isar. */
          var saw_blank = false
          var go = true
          while (go && i < lines.length) {
            val cline = lines(i)
            val stripped = Py.strip(cline)
            val oline = outer(i)
            val inside = open_at(i)
            if (Py.matches_start(BLANK_RE, cline)) {
              /* Only a blank OUTSIDE a term ends the statement: a `do {` block
                 is routinely written with blank lines between its steps. */
              saw_blank = !inside
              i += 1
            }
            else if (Py.matches_start(PROOF_RE, Py.lstrip(oline))) {
              proof_line = i + 1
              go = false
            }
            else if (match_decl_at(oline, table)._1.isDefined ||
                     (!inside && is_boundary_at(oline))) go = false
            else if (skip(i + 1) && Py.strip(oline).isEmpty) {
              /* Wholly prose, so not statement text.  A line that is wholly
                 TERM text has an empty outer view too and must still be
                 accumulated, which is why this asks the noise mask rather than
                 the outer view alone. */
              i += 1
            }
            else if (saw_blank) {
              /* The statement may simply resume — a blank between two `and c:`
                 assumptions is ordinary formatting.  Keep LOOKING for the
                 proof, but do not resume accumulating. */
              if (inside || Py.matches_start(STATEMENT_CONT_RE, stripped)) i += 1
              else go = false
            }
            else {
              if (Py.matches_start(SHOWS_AT_START_RE, stripped)) in_shows = true
              if (in_shows) conjuncts ++= Py.find_all(CONJUNCT_RE, stripped)
              buf += ("  " + stripped)
              i += 1
              decl_end_line = i
            }
          }

          /* One-liner: `lemma foo: "P" by simp` puts the proof on the same
             line as the statement, where a scan that starts on the line BELOW
             the declaration never looks.  Whatever is left of a line once
             inner syntax is blanked IS the outer part, so the proof keyword
             can simply be searched for. */
          if (proof_line == 0) {
            var k = decl_line
            while (proof_line == 0 && k <= decl_end_line) {
              if (k - 1 < outer.length && Py.found(PROOF_INLINE_RE, outer(k - 1))) proof_line = k
              k += 1
            }
          }

          val e = Entry(tag, name, buf.mkString("\n"), thy_line = decl_line,
            decl_end_line = decl_end_line, proof_line = proof_line)
          e.bindings = conjuncts.toList.map(c => (c, "conjunct"))
          entries += e
        }
        else i += 1
      }
    }

    /* A post-pass rather than threading through five constructions: the block
       chain is a property of the line an entry starts on. */
    val result = entries.toList
    attach_targets(result, outer, live)
    result
  }
}
