/*  Title:      query_base/src/shape.scala

Proof-shape metrics — per-step measurements over Isar proof bodies.

The `shape` family measures the SHAPE of individual proof steps across seven
incomparable axes: length, depth, width, space, redundancy, automation and
framing.  Everything is source-level, computed by re-aggregating what `entries`
and `usage_graph` already extract, with no Isabelle process.  This module owns
the one primitive the rest of the engine lacks: a *step* scanner.

The authoritative definitions — the exact-vs-estimator split, the reference
(elaborated-term) semantics each estimator approximates, and the known
approximations — live in the reference implementation's `shape.py`, stated at
each metric.  They are NOT restated here: a second prose copy drifts, and the
port's job is to reproduce the computation, not to re-argue it.  What IS
recorded here is every place the Python and the JVM disagree about something
observable (string order, integer division, float printing, dict order), since
that is what a reader of THIS file needs and cannot get from the other one.

The step model in one paragraph: one step per live proof line, keyed on the
leading command sequence, classified into goal / context / plumbing / closing
(the four keyword sets live in `Usage_Graph`, shared with the fact extractor).
A goal-stating command anywhere in the line's COMMAND PREFIX — the text before
its proposition — wins the classification, and locating that proposition is the
delicate part: a `"` or `\<open>` reached before any goal keyword is a fact
reference in command position, not the statement.

Depends on `model`, `entries` (tokenisation primitives), `usage_graph`
(`noise_spans`, `cited_facts_on_line`, `leading_method`) and `namespace` (the
bound method table, read late in `classify_identifier`).  Never on rendering or
the CLI: this file is pure computation, testable against hand-computed values.
*/

package isabelle.query


import isabelle.*

import java.util.regex.Pattern

import scala.collection.mutable


object Shape {
  /* ------------------------------------------------------------------ */
  /* step classification                                                */
  /* ------------------------------------------------------------------ */

  private val BLOCK_OPEN_RE: Pattern = Py.compile("""^proof\b|^\{""")
  private val BLOCK_CLOSE_RE: Pattern = Py.compile("""^qed\b|^\}""")
  /* A proposition is written `"P"` or `\<open>P\<close>`; the command prefix is
     everything before it. */
  private val PROP_START_RE: Pattern = Py.compile("""["]|\\<open>""")
  /* Command-position tokens: identifiers/keywords and the bare-dot terminators. */
  private val CMD_TOKEN_RE: Pattern = Py.compile("""\.\.|\.|[A-Za-z][\w']*""")

  private val OPEN_CART = """\<open>"""
  private val CLOSE_CART = """\<close>"""

  private def find_all(p: Pattern, s: CharSequence): List[String] = {
    val out = new mutable.ListBuffer[String]
    val m = p.matcher(s)
    while (m.find()) out += m.group()
    out.toList
  }

  /* Split a proof line into its command prefix and the column its proposition
     opens at (-1 when it states none).  A delimiter reached BEFORE any goal
     keyword is a fact reference written out (`from \<open>P\<close> have "Q"`),
     so it is skipped — blanked, not deleted, so a column into the prefix is
     still a column into the line.  Cartouches nest, so the skip uses the
     balanced scanner rather than a non-greedy regex. */
  def split_command_prefix(text: String): (String, Int) = {
    val out = new StringBuilder
    var i = 0
    var seen_goal = false
    while (true) {
      val m = Py.search_from(PROP_START_RE, text, i)
      if (m.isEmpty) {
        out ++= text.substring(i)
        return (out.toString, -1)
      }
      val start = m.get.start()
      val segment = text.substring(i, start)
      out ++= segment
      if (!seen_goal)
        seen_goal = find_all(CMD_TOKEN_RE, segment).exists(Usage_Graph.GOAL_KEYWORDS)
      if (seen_goal) return (out.toString, start)
      val end =
        if (text.charAt(start) == '"') {
          val close = text.indexOf('"', start + 1)
          if (close >= 0) close + 1 else -1
        }
        else Entries.balanced_end(text, OPEN_CART, CLOSE_CART, start = start)
      if (end <= start) {
        /* Unterminated on this line: nothing to the right can be read
           reliably, so treat it as the proposition — which keeps the residual
           error one-directional. */
        return (out.toString, start)
      }
      out ++= " " * (end - start)
      i = end
    }
    (out.toString, -1)   // unreachable
  }

  def command_prefix(stripped: String): String = split_command_prefix(stripped)._1

  /* goal / context / plumbing / closing / other, by the leading command
     sequence.  A goal command anywhere in the prefix dominates; otherwise the
     FIRST command token decides.  A terminal method reached later in a prefix
     that no step keyword leads still closes the goal (`unfolding x by simp`). */
  def classify_step_line(stripped: String): String = {
    val prefix = command_prefix(stripped)
    val tokens = find_all(CMD_TOKEN_RE, prefix)
    if (tokens.isEmpty) "other"
    else if (tokens.exists(Usage_Graph.GOAL_KEYWORDS)) "goal"
    else {
      val head = tokens.head
      if (Usage_Graph.CONTEXT_KEYWORDS(head)) "context"
      else if (Usage_Graph.PLUMBING_KEYWORDS(head)) "plumbing"
      else if (Usage_Graph.CLOSING_KEYWORDS(head) || head == "." || head == "..") "closing"
      else if (tokens.exists(t => Usage_Graph.CLOSING_KEYWORDS(t) || t == "..")) "closing"
      else "other"
    }
  }

  /* One classified Isar command inside a proof body.  `fanin` / `fanin_covered`
     / `live` are filled by the later metric passes, in place, which is why
     `analyze_proof` fixes their order. */
  final class Step(
    val theory: String,
    val lemma: String,
    val line: Int,
    val depth: Int,
    val kw: String,
    val kind: String,
    val stmt_start: Int = 0,
    val stmt_end: Int = 0,
    val stmt_text: String = "",
    val label: String = "",
    val goal_cmd: String = "",
    val block: Int = 0,
    val method: String = ""
  ) {
    var fanin: Int = 0
    var fanin_covered: Boolean = true
    var live: Int = 0
  }

  private val STEP_LABEL_RE: Pattern = Py.compile(
    """\b(?:have|show|hence|thus|obtain|consider|interpret)\s+([A-Za-z][\w'.]*)\s*:""")

  /* A goal command whose line ENDS at the command (with an optional label) —
     which is what makes the look-ahead safe.  `also` / `finally` are excluded:
     they are goal keywords that never carry a proposition of their own. */
  private val WRAPPED_GOAL_RE: Pattern = Py.compile(
    """\b(?:have|show|hence|thus|obtain|consider|interpret)(?:\s+[A-Za-z][\w'.]*\s*:)?\s*$""")

  final case class Stmt(start: Int, end: Int, text: String)

  private val NO_STMT = Stmt(0, 0, "")

  def extract_statement(lines: Array[String], start_idx: Int, end_idx: Int): Stmt = {
    val first = lines(start_idx)
    val col = split_command_prefix(first)._2
    if (col < 0) statement_wrapped(lines, start_idx, end_idx)
    else if (first.charAt(col) == '"') balance_quote(lines, start_idx, col, end_idx)
    else balance_cartouche(lines, start_idx, col, end_idx)
  }

  /* A goal whose proposition is wrapped onto a following line.  Deliberately
     narrow: the command line must END at the goal keyword and the next
     non-blank line must OPEN with a delimiter; anything else stays bare, since
     over-reaching here would invent statements. */
  private def statement_wrapped(lines: Array[String], start_idx: Int, end_idx: Int): Stmt = {
    if (!Py.found(WRAPPED_GOAL_RE, Py.rstrip(lines(start_idx)))) NO_STMT
    else {
      var i = start_idx + 1
      val last = (end_idx min (lines.length - 1))
      while (i <= last) {
        val line = lines(i)
        if (Py.strip(line).nonEmpty) {
          val lstripped = Py.lstrip(line)
          if (!Py.matches_start(PROP_START_RE, lstripped)) return NO_STMT
          val col = line.length - lstripped.length
          return (
            if (line.charAt(col) == '"') balance_quote(lines, i, col, end_idx)
            else balance_cartouche(lines, i, col, end_idx))
        }
        i += 1
      }
      NO_STMT
    }
  }

  /* `\"` escapes are not used in Isar statements, so a plain quote count
     suffices. */
  private def balance_quote(lines: Array[String], start_idx: Int, open_col: Int,
    end_idx: Int
  ): Stmt = {
    val parts = new mutable.ListBuffer[String]
    val body_open = open_col + 1
    var i = start_idx
    val last = (end_idx min (lines.length - 1))
    while (i <= last) {
      val segment = lines(i).substring(if (i == start_idx) body_open else 0)
      val close = segment.indexOf('"')
      if (close >= 0) {
        parts += segment.substring(0, close)
        return Stmt(start_idx + 1, i + 1, Py.strip(parts.mkString))
      }
      parts += segment
      i += 1
    }
    /* Unbalanced (ran off the proof body): the first line only. */
    Stmt(start_idx + 1, start_idx + 1, Py.strip(parts.head))
  }

  private def balance_cartouche(lines: Array[String], start_idx: Int, open_col: Int,
    end_idx: Int
  ): Stmt = {
    var depth = 0
    val parts = new mutable.ListBuffer[String]
    var i = start_idx
    val last = (end_idx min (lines.length - 1))
    while (i <= last) {
      val line = lines(i)
      var j = if (i == start_idx) open_col else 0
      val seg_start = j
      while (j < line.length) {
        if (line.startsWith(OPEN_CART, j)) { depth += 1; j += OPEN_CART.length }
        else if (line.startsWith(CLOSE_CART, j)) {
          depth -= 1
          if (depth == 0) {
            parts += line.substring(seg_start, j)
            val text0 = parts.mkString
            val text = if (text0.startsWith(OPEN_CART)) text0.substring(OPEN_CART.length) else text0
            return Stmt(start_idx + 1, i + 1, Py.strip(text))
          }
          j += CLOSE_CART.length
        }
        else j += 1
      }
      parts += line.substring(seg_start)
      i += 1
    }
    Stmt(start_idx + 1, start_idx + 1, "")
  }

  /* Column at which the proof begins on a line it SHARES with the goal
     statement (`lemma foo: "P" by simp`), or 0 when the line can be read from
     column 0 as it always was.  Located on the OUTER view with the same regex
     `entries` used to set `proof_line`, so this re-finds that match rather than
     inventing a second rule.  The two evidence tests are defence in depth: they
     change the answer for no AFP proof today, and are kept so that if the
     invariant in `entries` ever weakens this must undercount, not delete. */
  def inline_proof_col(ctx: Sec_Ctx, entry: Entry): Int = {
    if (entry.proof_line == 0) 0
    else {
      val src = ctx.lines
      if (entry.proof_line > src.length) 0
      else {
        val line = src(entry.proof_line - 1)
        /* The fast path for the 96%: "statement text precedes the proof" is the
           exact negation of "the line begins with the proof", which PROOF_RE
           already decides — with an ANCHORED match, against the unanchored scan
           below.  Asked first, it keeps this off the hot path. */
        if (Py.matches_start(Entries.PROOF_RE, line)) 0
        else {
          val oline = ctx.outer(entry.proof_line - 1)
          Py.search(Entries.PROOF_INLINE_RE, oline) match {
            case None => 0
            case Some(m) if m.start() == 0 => 0
            case Some(m) =>
              val col = m.start()
              if (Py.strip(line.substring(0, col)).isEmpty) 0
              else {
                val decl_end = if (entry.decl_end_line != 0) entry.decl_end_line else entry.thy_line
                if (entry.proof_line <= decl_end) col
                else if (Py.strip(oline.substring(0, col min oline.length)).isEmpty) col
                else 0
              }
          }
        }
      }
    }
  }

  private val CMD_TOKEN_HEAD_RE: Pattern = CMD_TOKEN_RE

  private def leading_kw(stripped: String): String =
    Py.matches_at_start(CMD_TOKEN_HEAD_RE, stripped).map(_.group()).getOrElse("")

  /* The fact-binding goal command of a goal line — the LAST goal keyword in its
     command prefix.  A chained line reads `<chain-word> <core-command>`, so the
     core command is the trailing one: `finally show` stays a `show`
     (discharge), `also have` a `have` (introduce). */
  private def goal_command(stripped: String): String = {
    val cmds = find_all(CMD_TOKEN_RE, command_prefix(stripped)).filter(Usage_Graph.GOAL_KEYWORDS)
    cmds.lastOption.getOrElse("")
  }

  /* Classify the Isar commands in an entry's proof body into a flat list, in
     source order.  A proof written on the statement's own line is scanned from
     the column the proof starts at; the statement half is BLANKED rather than
     sliced off, so this module's line view stays column-identical to
     `source()`, the contract `live_source` / `outer_source` also hold. */
  def scan_steps(ctx: Sec_Ctx, entry: Entry): List[Step] = {
    if (entry.proof_line == 0) Nil
    else {
      val col = inline_proof_col(ctx, entry)
      val lines =
        if (col == 0) ctx.lines
        else {
          val copy = ctx.lines.clone()
          copy(entry.proof_line - 1) = " " * col + copy(entry.proof_line - 1).substring(col)
          copy
        }
      val end0 =
        if (entry.body_end_line != 0) entry.body_end_line
        else if (entry.thy_end != 0) entry.thy_end
        else lines.length
      val end = end0 min lines.length
      val noise = ctx.prose_mask
      val steps = new mutable.ListBuffer[Step]
      /* `open_blocks` counts open `proof`/`{` frames; the lemma's own outermost
         proof is frame 1, so a step directly in it reports depth 0.
         `block_stack` mirrors the frames with a FRESH id each — depth cannot
         tell sibling blocks apart, and M4/M6 require that it can. */
      var open_blocks = 0
      val block_stack = new mutable.ArrayBuffer[Int]
      var next_block = 0
      var ln = entry.proof_line
      while (ln <= end) {
        if (!noise(ln)) {
          val stripped = Py.strip(lines(ln - 1))
          if (stripped.nonEmpty) {
            val step_depth = 0 max (open_blocks - 1)
            val cur_block = if (block_stack.nonEmpty) block_stack.last else 0
            val method = Usage_Graph.leading_method(stripped)
            if (Py.matches_start(BLOCK_CLOSE_RE, stripped)) {
              /* `qed` is a closing step in the block it closes (recorded before
                 the frame pops); a raw `}` is structural only. */
              if (stripped.startsWith("qed"))
                steps += new Step(ctx.sec.theory, entry.name, ln, step_depth, "qed",
                  "closing", block = cur_block, method = method)
              open_blocks = 0 max (open_blocks - 1)
              if (block_stack.nonEmpty) block_stack.remove(block_stack.length - 1)
            }
            else {
              val kind = classify_step_line(stripped)
              val kw = leading_kw(stripped)
              if (kind == "goal") {
                val st = extract_statement(lines, ln - 1, end - 1)
                val label =
                  Py.search(STEP_LABEL_RE, command_prefix(stripped)).map(_.group(1)).getOrElse("")
                steps += new Step(ctx.sec.theory, entry.name, ln, step_depth, kw, kind,
                  st.start, st.end, st.text, label = label,
                  goal_cmd = goal_command(stripped), block = cur_block, method = method)
              }
              else if (kind != "other")
                steps += new Step(ctx.sec.theory, entry.name, ln, step_depth, kw, kind,
                  block = cur_block, method = method)
              if (Py.matches_start(BLOCK_OPEN_RE, stripped)) {
                open_blocks += 1
                block_stack += next_block
                next_block += 1
              }
            }
          }
        }
        ln += 1
      }
      steps.toList
    }
  }


  /* ------------------------------------------------------------------ */
  /* metrics                                                            */
  /* ------------------------------------------------------------------ */

  /* A proposition token is an identifier/symbol RUN or a single punctuation
     character.  The run alternative is tried first, so `\<and>`, `\<^sub>` and
     a name with glued subscripts are one token each. */
  private val STMT_TOKEN_RE: Pattern = Py.compile(Entries.ISA_WORD_CHAR + """+|\S""")

  def stmt_tokens(text: String): List[String] = find_all(STMT_TOKEN_RE, text)

  private def stmt_token_count(text: String): Int = {
    var n = 0
    val m = STMT_TOKEN_RE.matcher(text)
    while (m.find()) n += 1
    n
  }

  /* M2 headline (source width): the token count of a goal step's as-written
     proposition.  Non-goal steps, and goal steps with no explicit proposition,
     measure 0. */
  def w2_src(step: Step): Int =
    if (step.stmt_text.isEmpty) 0 else stmt_token_count(step.stmt_text)

  private def line_facts(step: Step, lines: Array[String]): (Set[String], Boolean) =
    if (step.kind == "other") (Set.empty, true)
    else Usage_Graph.cited_facts_on_line(lines(step.line - 1))

  /* M5a fan-in: the number of distinct facts cited FOR each goal step — the
     union of its own line's facts and those of the standalone plumbing lines
     that serve it.  A closing step is a boundary that discards not-yet-consumed
     plumbing.  Implicit `this` chaining brings an UNNAMED fact, so it adds
     nothing here; that pressure is M5b's. */
  def annotate_fanin(steps: List[Step], lines: Array[String]): Unit = {
    var pending = Set.empty[String]
    var pending_covered = true
    for (s <- steps) {
      val (facts, covered) = line_facts(s, lines)
      if (s.kind == "plumbing") {
        pending = pending | facts
        pending_covered = pending_covered && covered
      }
      else if (s.kind == "goal") {
        s.fanin = (facts | pending).size
        s.fanin_covered = covered && pending_covered
        pending = Set.empty
        pending_covered = true
      }
      else if (s.kind == "closing") {
        pending = Set.empty
        pending_covered = true
      }
    }
  }


  /* --- M5b / M5c: live-fact space, introduce / consume ----------------- */

  private val GOAL_INTRO_CMDS: Set[String] =
    Set("have", "hence", "obtain", "consider", "interpret")
  private val CONTEXT_INTRO_CMDS: Set[String] = Set("assume", "presume", "define", "case")
  private val PLUMBING_INTRO_CMDS: Set[String] = Set("note", "moreover")
  private val THIS_CHAIN_WORDS: Set[String] =
    Set("then", "hence", "thus", "with", "moreover", "ultimately", "also", "finally")

  private val INTRO_LABEL_RE: Pattern =
    Py.compile("""^(?:note|assume|presume|case)\s+\(?\s*([A-Za-z][\w'.]*)\s*[:=]""")

  /* M5c numerator: whether a step BINDS a fact.  Keyed on the goal command, not
     the leading keyword — `from a have b:` has kw="from" yet introduces `b`. */
  def introduces(step: Step): Boolean =
    step.kind match {
      case "goal" => GOAL_INTRO_CMDS(step.goal_cmd)
      case "context" => CONTEXT_INTRO_CMDS(step.kw)
      case "plumbing" => PLUMBING_INTRO_CMDS(step.kw)
      case _ => false
    }

  private def intro_fact_name(step: Step, line: String): String =
    if (step.kind == "goal") { if (GOAL_INTRO_CMDS(step.goal_cmd)) step.label else "" }
    else if (introduces(step))
      Py.matches_at_start(INTRO_LABEL_RE, Py.strip(line)).map(_.group(1)).getOrElse("")
    else ""

  /* Per named fact, its (birth, death) step indices.  Death is the last step
     that cites it — explicitly, or via `this`-chaining, where a chaining step
     consumes whatever is currently bound to `this`.  A never-cited fact dies at
     its birth. */
  private def fact_intervals(steps: List[Step], lines: Array[String]
  ): (mutable.LinkedHashMap[String, Int], mutable.LinkedHashMap[String, Int]) = {
    val births = mutable.LinkedHashMap.empty[String, Int]
    val deaths = mutable.LinkedHashMap.empty[String, Int]
    var current_this: String = null
    var i = 0
    for (s <- steps) {
      val (cited, _) = line_facts(s, lines)
      for (name <- cited if births.contains(name)) deaths(name) = i
      if ((THIS_CHAIN_WORDS(s.kw) || cited("this")) &&
        current_this != null && births.contains(current_this))
        deaths(current_this) = i
      val name = intro_fact_name(s, lines(s.line - 1))
      if (name.nonEmpty) {
        if (!births.contains(name)) births(name) = i
        if (!deaths.contains(name)) deaths(name) = i
        current_this = name
      }
      else if (s.kind == "goal") current_this = null   // anonymous goal rebinds unnamed
      i += 1
    }
    (births, deaths)
  }

  /* M5b: the (max, mean) simultaneously-live named facts across a proof's
     steps, annotating each `step.live`.  An empty proof is (0, 0.0). */
  def live_fact_space(steps: List[Step], lines: Array[String]): (Int, Double) = {
    if (steps.isEmpty) (0, 0.0)
    else {
      val (births, deaths) = fact_intervals(steps, lines)
      val intervals = births.toList.map { case (nm, b) => (b, deaths(nm)) }
      var i = 0
      var total = 0
      var peak = 0
      for (s <- steps) {
        var n = 0
        for ((b, d) <- intervals) if (b <= i && i <= d) n += 1
        s.live = n
        total += n
        if (n > peak) peak = n
        i += 1
      }
      (peak, total.toDouble / steps.length)
    }
  }

  /* M5c tallies: introducing / consuming line counts, their disjoint three-way
     split, and the derived ratio (None when nothing is consumed — an undefined
     ratio, not zero). */
  final case class Intro_Consume(introduce: Int, consume: Int, both: Int,
    introduce_only: Int, consume_only: Int, neither: Int, total: Int
  ) {
    def ratio: Option[Double] =
      if (consume == 0) None else Some(introduce.toDouble / consume)
  }

  def introduce_consume(steps: List[Step], lines: Array[String]): Intro_Consume = {
    var intro = 0; var cons = 0; var both = 0
    var intro_only = 0; var cons_only = 0; var neither = 0; var total = 0
    for (s <- steps) {
      val i = introduces(s)
      val c = line_facts(s, lines)._1.nonEmpty
      total += 1
      if (i) intro += 1
      if (c) cons += 1
      if (i && c) both += 1
      else if (i) intro_only += 1
      else if (c) cons_only += 1
      else neither += 1
    }
    Intro_Consume(intro, cons, both, intro_only, cons_only, neither, total)
  }


  /* --- the automation axis --------------------------------------------- */

  /* Bounded-search automation — the "trivial" discharges.  Kept to this
     documented set so `trivial_frac` stays hand-computable and comparable
     across corpora; the tunable knob if the definition needs to widen. */
  val TRIVIAL_METHODS: Set[String] = Set("simp", "auto", "blast", "force", "fastforce")

  /* Fraction of a proof's DISCHARGED steps closed by a trivial method.  The
     denominator is the steps carrying an extracted method, which is POSITIONAL
     (`Usage_Graph.leading_method`), so the whole automation axis is independent
     of which namespace table is bound.  None when the proof discharges nothing
     at all — an undefined fraction, not 0. */
  def trivial_frac(steps: List[Step]): Option[Double] = {
    val methoded = steps.filter(_.method.nonEmpty)
    if (methoded.isEmpty) None
    else Some(methoded.count(s => TRIVIAL_METHODS(s.method)).toDouble / methoded.length)
  }

  val METHOD_KIND_NAMES: List[String] =
    List("automation", "search", "arith", "structural", "other")

  /* The four core families.  Like TRIVIAL_METHODS these are a tunable knob, not
     a claim about tactic semantics: they name the cross-corpus core so the
     distribution is comparable across entries.  Anything outside them is
     `other` — so `other` means "outside the core families", not "recognised but
     outside them"; an entry's own Eisbach tactic lands there. */
  private val METHOD_KIND_SETS: List[(String, Set[String])] = List(
    "automation" -> Set("simp", "simp_all", "auto", "clarsimp", "clarify"),
    "search" -> Set("blast", "fast", "fastforce", "force", "best", "metis", "meson",
      "smt", "satx", "argo"),
    "arith" -> Set("arith", "linarith", "presburger", "algebra", "sos", "approximation",
      "order", "cooper", "real_asymp"),
    "structural" -> Set("rule", "rule_tac", "drule", "drule_tac", "erule", "erule_tac",
      "frule", "frule_tac", "intro", "elim", "cases", "case_tac", "induct", "induction",
      "induct_tac", "coinduct", "coinduction", "nominal_induct", "standard",
      "unfold_locales", "intro_classes", "intro_locales", "pat_completeness", "split"))

  def method_kind(method: String): String =
    if (method.isEmpty) ""
    else METHOD_KIND_SETS.find(_._2(method)).map(_._1).getOrElse("other")

  /* Histogram of a proof's discharged steps by kind.  Every key is present (0
     when absent), so the schema is uniform; the keys sum to `trivial_frac`'s
     denominator. */
  def method_kind_counts(steps: List[Step]): List[(String, Int)] = {
    val counts = mutable.LinkedHashMap.empty[String, Int]
    for (k <- METHOD_KIND_NAMES) counts(k) = 0
    for (s <- steps if s.method.nonEmpty) {
      val k = method_kind(s.method)
      counts(k) = counts(k) + 1
    }
    METHOD_KIND_NAMES.map(k => k -> counts(k))
  }


  /* --- induction discipline (LiFtEr's source-visible inputs) ------------ */

  final case class Induction(terms: Int, arbitrary: Int, rule: Boolean, recursion: Boolean)

  /* `coinduct` / `coinduction` are the dual and take different modifiers, so
     they stay out of this axis. */
  val INDUCTION_METHODS: Set[String] =
    Set("induct", "induction", "induct_tac", "nominal_induct")

  /* `arbitrary:` and `rule:` are the two scored; the rest are recognised only so
     they correctly terminate the leading run of induction TERMS. */
  private val INDUCT_MODIFIERS: Set[String] =
    Set("arbitrary", "taking", "rule", "avoiding", "pred", "set")

  /* Anchored on the introducer like `leading_method`: an induction that is not
     the step's leading method is not seen (undercount, never overcount).
     Longest alternatives first so `induction` is not shadowed by `induct`. */
  private val INDUCT_INTRO_RE: Pattern = Py.compile(
    """\b(?:by|apply|proof)\b\s*(\(?)\s*(induction|induct_tac|nominal_induct|induct)\b""")

  /* Whitespace-split, keeping a `"..."` compound term as ONE token — its inner
     spaces and commas are part of the term (`induct "(p, t)"` inducts on one). */
  private def split_induct_args(args: String): List[String] = {
    val out = new mutable.ListBuffer[String]
    var i = 0
    val n = args.length
    while (i < n) {
      if (Py.is_space(args.charAt(i))) i += 1
      else if (args.charAt(i) == '"') {
        val j0 = args.indexOf('"', i + 1)
        val j = if (j0 < 0) n else j0 + 1
        out += args.substring(i, j)
        i = j
      }
      else {
        var j = i
        while (j < n && !Py.is_space(args.charAt(j)) && args.charAt(j) != '"') j += 1
        out += args.substring(i, j)
        i = j
      }
    }
    out.toList
  }

  /* `(modifier, inline_value)` when the word opens a modifier sub-list, handling
     both `rule:` + `foo.induct` and a glued `rule:foo.induct`; a term like
     `n::nat` is not a modifier. */
  private def induct_modifier(word: String): (String, String) = {
    val idx = word.indexOf(':')
    if (idx > 0 && INDUCT_MODIFIERS(word.substring(0, idx)))
      (word.substring(0, idx), word.substring(idx + 1))
    else (null, "")
  }

  /* `recursion` is whether a supplied rule is a `*.induct` recursion schema —
     the qualified `f.induct` auto-generated per recursive function / datatype,
     distinct from a library rule like `list_induct2`.  The dot is the
     source-level discriminator. */
  private def parse_induction(args: String): Induction = {
    var section = "terms"
    var n_terms = 0
    var n_arb = 0
    var has_rule = false
    var recursion = false
    for (w <- split_induct_args(args)) {
      val (name, inline) = induct_modifier(w)
      if (name != null) {
        section = name
        if (name == "rule") {
          has_rule = true
          if (inline.contains(".induct")) recursion = true
        }
        else if (name == "arbitrary" && inline.nonEmpty) n_arb += 1
      }
      else if (section == "terms") n_terms += 1
      else if (section == "arbitrary") n_arb += 1
      else if (section == "rule" && w.contains(".induct")) recursion = true
    }
    Induction(n_terms, n_arb, has_rule, recursion)
  }

  /* The argument text of the induction call whose introducer is on line
     `start_0`: the source between the method name and the matching `)`,
     paren-balanced QUOTE-AWARE (the `)` inside `"(p, t)"` must not close the
     call) across continuation lines.  `null` when the call is bare.

     The reference joins the whole rest of the file into one string and slices
     it; this walks the same virtual region line by line and collects only the
     segments, which is the same answer without materialising a copy of the
     file's tail per induction call. */
  private def induction_arg_text(lines: Array[String], start_0: Int): String = {
    val m0 = Py.search(INDUCT_INTRO_RE, lines(start_0))
    if (m0.isEmpty) return null
    val m = m0.get
    if (m.group(1).isEmpty) return null                  // bare: no `(`
    val open_col = m.start(1)
    val arg_start = m.end(2)

    /* Balanced scan over the virtual `"\n".join(lines[start_0:])`: stepping off
       the end of a line consumes the joining newline, which is an ordinary
       character to the scanner — including inside a quote, where the reference's
       string scan also carries the open quote across it. */
    var depth = 0
    var in_quote = false
    var li = start_0
    var ci = open_col
    var close_li = -1
    var close_ci = -1
    var scanning = true
    while (scanning) {
      if (li >= lines.length) scanning = false
      else {
        val line = lines(li)
        if (ci >= line.length) { li += 1; ci = 0 }
        else {
          val c = line.charAt(ci)
          if (in_quote) { if (c == '"') in_quote = false; ci += 1 }
          else if (c == '"') { in_quote = true; ci += 1 }
          else if (c == '(') { depth += 1; ci += 1 }
          else if (c == ')') {
            depth -= 1
            if (depth == 0) { close_li = li; close_ci = ci; scanning = false }
            else ci += 1
          }
          else ci += 1
        }
      }
    }

    /* `region[arg_start:close-1]` — up to but not including the closing `)`. */
    val parts = new mutable.ListBuffer[String]
    var i = start_0
    val last = if (close_li >= 0) close_li else lines.length - 1
    while (i <= last) {
      val line = lines(i)
      val lo = (if (i == start_0) arg_start else 0) min line.length
      val hi = (if (i == close_li) close_ci min line.length else line.length) max lo
      parts += line.substring(lo, hi)
      i += 1
    }
    join_arg_parts(parts.toList)
  }

  /* Join per-line segments into one logical argument list, dropping the
     continuation lines' indentation (interior whitespace is only a separator). */
  private def join_arg_parts(parts: List[String]): String =
    parts.map(Py.strip).filter(_.nonEmpty).mkString(" ")

  /* Every induction invocation in an entry's proof REGION.  Scans the source
     directly rather than the steps, because a common induction form is not a
     step at all: the `proof (induction ...)` opener is depth scaffolding. */
  def scan_inductions(ctx: Sec_Ctx, entry: Entry): List[Induction] = {
    val lines = ctx.lines
    val end0 =
      if (entry.body_end_line != 0) entry.body_end_line
      else if (entry.thy_end != 0) entry.thy_end
      else lines.length
    if (end0 == 0) Nil
    else {
      val end = end0 min lines.length
      val start =
        if (entry.proof_line != 0) entry.proof_line
        else if (entry.thy_line != 0) entry.thy_line
        else 1
      val noise = ctx.prose_mask
      val out = new mutable.ListBuffer[Induction]
      var ln = start
      while (ln <= end) {
        if (!noise(ln) && Py.found(INDUCT_INTRO_RE, lines(ln - 1))) {
          val args = induction_arg_text(lines, ln - 1)
          out += (if (args != null && args.nonEmpty) parse_induction(args)
                  else Induction(0, 0, false, false))
        }
        ln += 1
      }
      out.toList
    }
  }

  final case class Induction_Summary(n: Int, terms_max: Int, arbitrary_max: Int,
    n_rule: Int, n_recursion: Int)

  def summarize_inductions(inductions: List[Induction]): Induction_Summary =
    Induction_Summary(
      n = inductions.length,
      terms_max = inductions.map(_.terms).foldLeft(0)(_ max _),
      arbitrary_max = inductions.map(_.arbitrary).foldLeft(0)(_ max _),
      n_rule = inductions.count(_.rule),
      n_recursion = inductions.count(_.recursion))


  /* --- M1: eigenvariable width + the layered var/const classifier ------- */

  /* Statement-local binders.  The symbolic ones are GLUED to the first bound
     variable by the tokeniser (`\<forall>k` is one token, by the same rule that
     keeps `g\<^sub>1` together), so they are split off by prefix; the ASCII word
     spellings are standalone tokens. */
  private val BINDER_SYMS: List[String] = List(
    """\<forall>""", """\<exists>""", """\<nexists>""", """\<lambda>""", """\<And>""",
    """\<Sum>""", """\<Prod>""", """\<Union>""", """\<Inter>""")
  private val BINDER_WORDS: Set[String] = Set("ALL", "EX", "SOME", "THE", "LEAST", "GREATEST")
  /* `\z`, not `\Z`: Java's `\Z` also matches before a final line terminator,
     where Python's `\Z` is the absolute end of the string. */
  private val NAME_RE: Pattern = Py.compile("""[A-Za-z][\w']*\z""")

  private def is_name(tok: String): Boolean = Py.matches_start(NAME_RE, tok)

  private def binder_prefix(tok: String): (String, String) =
    BINDER_SYMS.find(tok.startsWith) match {
      case Some(sym) => (sym, tok.substring(sym.length))
      case None => (null, "")
    }

  /* The identifier structure of a proposition at token level: distinct
     free-candidate names (first-seen order), schematic `?vars`, and
     statement-local binder-bound names. */
  final case class Stmt_Vars(free: List[String], schematic: List[String], bound: List[String])

  def analyze_tokens(toks: List[String]): Stmt_Vars = {
    val bound = new mutable.ListBuffer[String]
    val bound_set = mutable.Set.empty[String]
    val schematic = new mutable.ListBuffer[String]
    val sch_set = mutable.Set.empty[String]
    val free = new mutable.ListBuffer[String]
    val free_set = mutable.Set.empty[String]

    def bind(nm: String): Unit = if (!bound_set(nm)) { bound_set += nm; bound += nm }

    val arr = toks.toArray
    val n = arr.length
    var i = 0
    while (i < n) {
      val t = arr(i)
      val (sym, rest) = binder_prefix(t)
      if (sym != null || BINDER_WORDS(t)) {
        var j = i + 1
        if (sym != null && rest.nonEmpty) { if (is_name(rest)) bind(rest) }
        else if (j < n && arr(j) == "!") j += 1     // `\<exists> ! x`
        while (j < n && is_name(arr(j))) { bind(arr(j)); j += 1 }
        i = j
      }
      else if (t == "?" && i + 1 < n && is_name(arr(i + 1))) {
        val nm = arr(i + 1)
        if (!sch_set(nm)) { sch_set += nm; schematic += nm }
        i += 2
      }
      else {
        if (is_name(t) && !free_set(t)) { free_set += t; free += t }
        i += 1
      }
    }
    Stmt_Vars(free.toList.filter(f => !bound_set(f) && !sch_set(f)),
      schematic.toList, bound.toList)
  }

  def analyze_statement(text: String): Stmt_Vars = analyze_tokens(stmt_tokens(text))

  /* Per-lemma inputs to the classifier: theory-defined constant names (bucket
     a), context-bound variable names (bucket b), the harvested corpus constant
     list (bucket c). */
  final case class Classify_Ctx(entry_names: Set[String], context_vars: Set[String],
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS)

  /* The layered classifier, in precedence order: a `fix`/`for`/binder-bound name
     is authoritatively a variable even when it shadows a global; proof
     method / keyword / attribute syntax is never an eigenvariable; a name in
     this theory's entry DB or in the corpus constant list is a constant; an
     unknown lowercase name confined to one lemma is most likely a variable.
     Reads the LATE-BOUND namespace, so a CLI `configure_namespace` is seen here
     and the shape classifier can never diverge from the citation router. */
  def classify_identifier(name: String, ctx: Classify_Ctx): (String, String) = {
    if (ctx.context_vars(name)) ("var", "context")
    else if (Namespace.keyword_names(name) || Namespace.proof_methods(name) ||
      Namespace.attribute_names(name)) ("const", "syntax")
    else if (ctx.entry_names(name)) ("const", "entry")
    else if (ctx.corpus_consts(name)) ("const", "corpus")
    else ("var", "default")
  }


  /* --- const_est: distinct constants in the as-written proposition ------ */

  private val ASCII_OP_CONSTS: Set[Char] = "+-*/=<>@".toSet

  /* Greek and variant LETTER symbols: identifier characters (a `\<Gamma>`
     context, an `\<alpha>` ordinal), NOT operators — the tokeniser keeps them as
     bare `\<sym>` because `is_name` gates on an ASCII letter, so without this
     set they would masquerade as operator constants. */
  private val LETTER_SYMS: Set[String] = Set(
    """\<alpha>""", """\<beta>""", """\<gamma>""", """\<delta>""", """\<epsilon>""",
    """\<zeta>""", """\<eta>""", """\<theta>""", """\<iota>""", """\<kappa>""",
    """\<mu>""", """\<nu>""", """\<xi>""", """\<pi>""", """\<rho>""", """\<sigma>""",
    """\<tau>""", """\<upsilon>""", """\<phi>""", """\<chi>""", """\<psi>""",
    """\<omega>""", """\<varepsilon>""", """\<vartheta>""", """\<varphi>""",
    """\<varrho>""", """\<varsigma>""", """\<varpi>""",
    """\<Gamma>""", """\<Delta>""", """\<Theta>""", """\<Lambda>""", """\<Xi>""",
    """\<Pi>""", """\<Sigma>""", """\<Upsilon>""", """\<Phi>""", """\<Psi>""",
    """\<Omega>""")

  /* Exactly one `\<sym>` and nothing else: a spaced, standalone operator token.
     `\<And>c_b`, `\<Gamma>\<^sub>M` and an unspaced `x\<in>y` all fail this, so
     none is mistaken for an operator. */
  private val SINGLE_SYM_RE: Pattern = Py.compile("""\\<\w+>\z""")

  private val BRACKET_PAIRS: Map[String, String] = Map(
    "(" -> ")", "[" -> "]", "{" -> "}",
    """\<open>""" -> """\<close>""",
    """\<lparr>""" -> """\<rparr>""",
    """\<lbrakk>""" -> """\<rbrakk>""")
  private val CLOSERS: Set[String] = BRACKET_PAIRS.values.toSet
  private val BINDER_SET: Set[String] = BINDER_SYMS.toSet

  private def is_operator_const(tok: String): Boolean = {
    if (tok.isEmpty || Character.isLetter(tok.charAt(0)) || Character.isDigit(tok.charAt(0)))
      false
    else if (tok.length == 1 && ASCII_OP_CONSTS(tok.charAt(0))) true
    else if (Py.matches_start(SINGLE_SYM_RE, tok))
      !BRACKET_PAIRS.contains(tok) && !CLOSERS(tok) && !BINDER_SET(tok) && !LETTER_SYMS(tok)
    else false
  }

  private def operator_consts(toks: List[String]): List[String] = {
    val seen = new mutable.ListBuffer[String]
    val set = mutable.Set.empty[String]
    for (t <- toks if is_operator_const(t) && !set(t)) { set += t; seen += t }
    seen.toList
  }

  /* Distinct constants after mapping each operator glyph to its canonical
     Isabelle constant via the committed table — the semantic vocabulary behind
     `const_canon_est`.  A glyph the table does not carry falls back to itself,
     so this only ever DEDUPS a constant. */
  private def canonicalize_consts(names: List[String]): List[String] = {
    val seen = new mutable.ListBuffer[String]
    val set = mutable.Set.empty[String]
    for (nm <- names) {
      val c = Shape_Data.NOTATION.getOrElse(nm, nm)
      if (!set(c)) { set += c; seen += c }
    }
    seen.toList
  }

  final case class W1(free: Int = 0, schematic: Int = 0, bound: Int = 0,
    free_names: List[String] = Nil, const: Int = 0, const_names: List[String] = Nil,
    const_canon: Int = 0, const_canon_names: List[String] = Nil)

  private val EMPTY_W1 = W1()

  /* M1 estimator: distinct free variables in a goal step's as-written
     proposition, with the schematic and bound-variable columns reported
     separately, plus the Width vocabulary sibling `const_est` — free candidates
     tagged const (disjoint from the operator symbols, which are never names)
     plus the notation glyphs. */
  def w1_est(step: Step, ctx: Classify_Ctx): W1 =
    if (step.kind != "goal" || step.stmt_text.isEmpty) EMPTY_W1
    else {
      val toks = stmt_tokens(step.stmt_text)
      val sv = analyze_tokens(toks)
      val prov = sv.free.map(nm => nm -> classify_identifier(nm, ctx)).toMap
      val free_vars = sv.free.filter(nm => prov(nm)._1 == "var")
      val const_names = sv.free.filter(nm => prov(nm)._1 == "const") ::: operator_consts(toks)
      val canon = canonicalize_consts(const_names)
      W1(free_vars.length, sv.schematic.length, sv.bound.length, free_vars,
        const_names.length, const_names, canon.length, canon)
    }

  private def w1_count(toks: List[String], ctx: Classify_Ctx): Int =
    analyze_tokens(toks).free.count(nm => classify_identifier(nm, ctx)._1 == "var")

  /* Only the LEADING run of identifier tokens is the bound-variable list.
     Capturing that run stops at the first quote or symbol, so a same-line
     trailing proposition — `fix VS assume "VS \<subseteq> insert a A"` — cannot
     leak its constants into the context as spurious variables. */
  private val FIX_NAMES_RE: Pattern =
    Py.compile("""^(?:fix|obtain)\s+((?:[A-Za-z][\w']*\s+)*[A-Za-z][\w']*)""")
  /* An identifier must follow the keyword (a real clause is `fixes n :: t`,
     never `fixes = ...`), which rejects stray `fixes`/`for` words in ML
     antiquotations. */
  private val HEADER_FIX_RE: Pattern = Pattern.compile(
    """\b(?:fixes|for)\s+([A-Za-z].*?)(?=\b(?:fixes|assumes|shows|where|for|is)\b|$)""",
    Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL)
  private val IDENT_RE: Pattern = Py.compile("""[A-Za-z][\w']*""")
  private val AND_SPLIT_RE: Pattern = Py.compile("""\band\b""")

  private def fix_line_names(stripped: String): Set[String] =
    Py.matches_at_start(FIX_NAMES_RE, stripped) match {
      case None => Set.empty
      case Some(m) => find_all(IDENT_RE, m.group(1)).toSet
    }

  private def header_fix_names(ctx: Sec_Ctx, entry: Entry): Set[String] = {
    val lines = ctx.lines
    val end = if (entry.decl_end_line != 0) entry.decl_end_line else entry.thy_line
    val lo = (entry.thy_line - 1) max 0
    val hi = end min lines.length
    val header = (if (hi > lo) lines.slice(lo, hi) else Array.empty[String]).mkString(" ")
    val names = mutable.Set.empty[String]
    val m = HEADER_FIX_RE.matcher(header)
    while (m.find()) {
      for (part <- AND_SPLIT_RE.split(m.group(1), -1)) {
        val head = part.split("::", 2)(0)
        val ids = find_all(IDENT_RE, head)
        if (ids.nonEmpty) names += ids.head
      }
    }
    names.toSet
  }

  def build_ctx(ctx: Sec_Ctx, entry: Entry, steps: List[Step],
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Classify_Ctx = {
    val entry_names = ctx.sec.entries.map(_.name).toSet
    var context_vars = header_fix_names(ctx, entry)
    val lines = ctx.lines
    for (s <- steps if s.kind == "context" && (s.kw == "fix" || s.kw == "obtain"))
      context_vars = context_vars | fix_line_names(Py.strip(lines(s.line - 1)))
    Classify_Ctx(entry_names, context_vars, corpus_consts)
  }


  /* --- M4 / M6: cross-step redundancy + extension sensitivity ----------- */

  /* A CHUNK is a bracket-balanced contiguous token span — the inclusive span of
     a matched bracket pair, at every nesting level.  This is the bracket forest
     of the token stream: O(n), deterministic, and each chunk literally
     well-bracketed.  Deliberately cruder than a term DAG. */
  private val MIN_CHUNK_TOKENS = 4
  /* The reference token a chunk is rewritten to: a single non-name symbol, so
     it counts 1 toward w2_src and is ignored by w1_est, exactly as a fresh
     nullary definition would be. */
  private val CHUNK_REF = """\<hole>"""

  final case class Span(text: String, start: Int, end: Int)

  /* Every matched bracket pair as (text, start, end-exclusive), for spans of at
     least `min_len` tokens, in CLOSING order — which is the order the greedy
     extractor's occurrence map is built in, and so the tie-break order. */
  private def bracket_spans(tokens: Array[String], min_len: Int): List[Span] = {
    val spans = new mutable.ListBuffer[Span]
    val stack = new mutable.ArrayBuffer[Int]
    var i = 0
    while (i < tokens.length) {
      val t = tokens(i)
      if (BRACKET_PAIRS.contains(t)) stack += i
      else if (CLOSERS(t) && stack.nonEmpty) {
        val opener = stack.last
        if (BRACKET_PAIRS(tokens(opener)) == t) {
          stack.remove(stack.length - 1)
          if (i - opener + 1 >= min_len)
            spans += Span(tokens.slice(opener, i + 1).mkString(" "), opener, i + 1)
        }
      }
      i += 1
    }
    spans.toList
  }

  private def bracket_chunks(tokens: Array[String], min_len: Int): List[String] =
    bracket_spans(tokens, min_len).map(_.text)

  /* Multiset Jaccard: sum(min counts) / sum(max counts).  None when NEITHER
     statement has any chunk — an undefined overlap, distinct from a genuine 0
     (both have chunks, share none). */
  private def multiset_jaccard(a: List[String], b: List[String]): Option[Double] = {
    val ca = a.groupBy(identity).view.mapValues(_.size).toMap
    val cb = b.groupBy(identity).view.mapValues(_.size).toMap
    val keys = ca.keySet | cb.keySet
    if (keys.isEmpty) None
    else {
      var inter = 0
      var union = 0
      for (k <- keys) {
        val x = ca.getOrElse(k, 0)
        val y = cb.getOrElse(k, 0)
        inter += (x min y)
        union += (x max y)
      }
      Some(inter.toDouble / union)
    }
  }

  /* Greedily keep a non-overlapping subset of occurrences, earliest-start first
     per statement.  Equal-valued spans in one statement are normally disjoint
     already; this guards the nested-equal case. */
  private def non_overlapping(occs: List[(Int, Int, Int)]): List[(Int, Int, Int)] = {
    val chosen = new mutable.ListBuffer[(Int, Int, Int)]
    val last_end = mutable.Map.empty[Int, Int]
    for (occ <- occs.sortBy(o => (o._1, o._2, o._3))) {
      val (si, a, b) = occ
      if (a >= last_end.getOrElse(si, -1)) { chosen += occ; last_end(si) = b }
    }
    chosen.toList
  }

  final case class Extraction(k: Int, compressed: Int, extracted: List[String],
    ref_spans: Array[mutable.ListBuffer[(Int, Int)]])

  /* Greedily factor repeated bracket chunks out of a block's goal statements.
     At each step take the highest-VALUE chunk (`(occurrences - 1) * length`,
     ties broken by chunk text for determinism) with at least two intact
     non-overlapping occurrences, and mark those positions consumed.  Stops
     after `max_k` extractions, or — when `max_k` is negative — once no
     positive-value repeat remains (M4's full DAG).

     The tie-break compares chunk TEXT.  Python orders strings by code point and
     the JVM by UTF-16 unit; the two differ only when a supplementary-plane
     character is compared against U+E000..U+FFFF, which no Isar statement
     contains.  The FIRST candidate wins a tie on both sides because the
     comparison is strict and the occurrence map preserves insertion order. */
  private def greedy_extract(tok_lists: Array[Array[String]], min_len: Int,
    max_k: Int
  ): Extraction = {
    val spans_per = tok_lists.map(t => bracket_spans(t, min_len))
    val consumed = tok_lists.map(t => new Array[Boolean](t.length))
    var compressed = tok_lists.map(_.length).sum
    val extracted = new mutable.ListBuffer[String]
    val ref_spans = Array.fill(tok_lists.length)(new mutable.ListBuffer[(Int, Int)])
    var k = 0
    var go = true
    while (go && (max_k < 0 || k < max_k)) {
      val occ = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[(Int, Int, Int)]]
      var si = 0
      while (si < spans_per.length) {
        for (sp <- spans_per(si)) {
          var free = true
          var p = sp.start
          while (free && p < sp.end) { if (consumed(si)(p)) free = false; p += 1 }
          if (free)
            occ.getOrElseUpdate(sp.text, new mutable.ListBuffer) += ((si, sp.start, sp.end))
        }
        si += 1
      }
      var best_value = 0
      var best_text: String = null
      var best_usable: List[(Int, Int, Int)] = Nil
      var best_length = 0
      for ((text, occs) <- occ) {
        val usable = non_overlapping(occs.toList)
        if (usable.length >= 2) {
          val length = text.split(" ", -1).length
          val value = (usable.length - 1) * length
          if (best_text == null || value > best_value ||
            (value == best_value && text.compareTo(best_text) > 0)) {
            best_value = value
            best_text = text
            best_usable = usable
            best_length = length
          }
        }
      }
      if (best_text == null || best_value <= 0) go = false
      else {
        for ((si2, a, b) <- best_usable) {
          var p = a
          while (p < b) { consumed(si2)(p) = true; p += 1 }
          ref_spans(si2) += ((a, b))
        }
        compressed -= (best_usable.length - 1) * best_length
        extracted += best_text
        k += 1
      }
    }
    Extraction(k, compressed, extracted.toList, ref_spans)
  }

  /* Replace each extracted span with a single reference token, as if named a
     fresh definition. */
  private def rewrite(tokens: Array[String], spans: List[(Int, Int)]): Array[String] = {
    val out = new mutable.ArrayBuffer[String]
    var i = 0
    for ((a, b) <- spans.sortBy(s => (s._1, s._2))) {
      out ++= tokens.slice(i, a)
      out += CHUNK_REF
      i = b
    }
    out ++= tokens.slice(i, tokens.length)
    out.toArray
  }

  final case class Block_Redundancy(block: Int, n_goals: Int, total_tokens: Int,
    compressed_tokens: Int, dag_ratio: Double, overlaps: List[Option[Double]])

  /* Partition a proof's steps by block id, preserving first-appearance order. */
  private def blocks_of(steps: List[Step]): List[List[Step]] = {
    val groups = mutable.LinkedHashMap.empty[Int, mutable.ListBuffer[Step]]
    for (s <- steps) groups.getOrElseUpdate(s.block, new mutable.ListBuffer) += s
    groups.values.map(_.toList).toList
  }

  /* M4 estimator, per proof block over its goal-stating propositions.  Blocks
     with no goal statement are skipped; a lone-goal block has empty `overlaps`
     and a `dag_ratio` reflecting only intra-statement repeats. */
  def cross_step_redundancy(steps: List[Step], min_len: Int = MIN_CHUNK_TOKENS
  ): List[Block_Redundancy] = {
    val out = new mutable.ListBuffer[Block_Redundancy]
    for (block_steps <- blocks_of(steps)) {
      val goals = block_steps.filter(s => s.kind == "goal" && s.stmt_text.nonEmpty)
      if (goals.nonEmpty) {
        val tok_lists = goals.map(g => stmt_tokens(g.stmt_text).toArray).toArray
        val total = tok_lists.map(_.length).sum
        val ex = greedy_extract(tok_lists, min_len, -1)
        val dag = if (ex.compressed != 0) total.toDouble / ex.compressed else 1.0
        val chunks = tok_lists.map(t => bracket_chunks(t, min_len))
        val overlaps =
          (0 until (chunks.length - 1)).map(i => multiset_jaccard(chunks(i), chunks(i + 1))).toList
        out += Block_Redundancy(goals.head.block, goals.length, total, ex.compressed,
          dag, overlaps)
      }
    }
    out.toList
  }

  final case class Extension_Curve(block: Int, n_goals: Int, ks: List[Int],
    w1: List[Int], w2: List[Int])

  val M6_KS: List[Int] = List(0, 1, 2, 4, 8, 16)

  /* M6 estimator, per proof block: the width-vs-k curve.  A heuristic UPPER
     bound on removable width — the extracted definitions' own width is not
     charged back, so the curve shows the best case for naming. */
  def extension_curve(steps: List[Step], ctx: Classify_Ctx, ks: List[Int] = M6_KS,
    min_len: Int = MIN_CHUNK_TOKENS
  ): List[Extension_Curve] = {
    val out = new mutable.ListBuffer[Extension_Curve]
    for (block_steps <- blocks_of(steps)) {
      val goals = block_steps.filter(s => s.kind == "goal" && s.stmt_text.nonEmpty)
      if (goals.nonEmpty) {
        val tok_lists = goals.map(g => stmt_tokens(g.stmt_text).toArray).toArray
        val w1s = new mutable.ListBuffer[Int]
        val w2s = new mutable.ListBuffer[Int]
        for (k <- ks) {
          val ex = greedy_extract(tok_lists, min_len, k)
          val rewritten =
            tok_lists.indices.map(i => rewrite(tok_lists(i), ex.ref_spans(i).toList))
          w1s += rewritten.map(toks => w1_count(toks.toList, ctx)).sum
          w2s += rewritten.map(_.length).sum
        }
        out += Extension_Curve(goals.head.block, goals.length, ks, w1s.toList, w2s.toList)
      }
    }
    out.toList
  }

  /* M6 scalar: the fraction of a proof's total stated width removable by naming
     up to 8 repeated chunks per block, summed over blocks (M6 never crosses
     one).  0.0 when the proof states no width. */
  def removable_w2_at_8(steps: List[Step], ctx: Classify_Ctx): Double = {
    val curves = extension_curve(steps, ctx, ks = List(0, 8))
    val w2_0 = curves.map(_.w2.head).sum
    val w2_8 = curves.map(_.w2(1)).sum
    if (w2_0 == 0) 0.0 else 1 - w2_8.toDouble / w2_0
  }


  /* --- M3: frame ratio (delta-tracing overhead) ------------------------- */

  /* Per-corpus M3 descriptor: the names that mark the configuration type.
     `selectors` drive `mentioned`; `constructors` / `relations` extend the
     applicability signal. */
  final case class Corpus_Config(constructors: Set[String] = Set.empty,
    selectors: Set[String] = Set.empty, relations: Set[String] = Set.empty)

  final case class Frame_Ratio(mentioned: Int, changed: Int, ratio: Double)

  /* M3 for one goal step, or None when the proposition shows no configuration
     signal (not a relation, or mentions nothing configured) — a coverage
     statistic, never a guess.  `:=` is counted on the RAW text, since the
     tokeniser splits it into `:` and `=`. */
  def frame_ratio(step: Step, cfg: Corpus_Config): Option[Frame_Ratio] = {
    if (step.kind != "goal" || step.stmt_text.isEmpty) None
    else {
      val text = step.stmt_text
      val toks = stmt_tokens(text)
      val tokset = toks.toSet
      val has_relation = tokset("=") || cfg.relations.exists(tokset)
      val updates = count_occurrences(text, ":=")
      val indexing = toks.count(_ == "!")
      val selectors = cfg.selectors.toList.map(s => toks.count(_ == s)).sum
      val has_config =
        selectors != 0 || indexing != 0 || updates != 0 || cfg.constructors.exists(tokset)
      if (!(has_relation && has_config)) None
      else {
        val mentioned = selectors + indexing + updates
        Some(Frame_Ratio(mentioned, updates, mentioned.toDouble / (updates max 1)))
      }
    }
  }

  private def count_occurrences(s: String, sub: String): Int = {
    var n = 0
    var i = s.indexOf(sub)
    while (i >= 0) { n += 1; i = s.indexOf(sub, i + sub.length) }
    n
  }

  final case class M3_Summary(n_goals: Int, ratios: List[Double]) {
    def n_computed: Int = ratios.length
    def coverage: Double = if (n_goals == 0) 0.0 else n_computed.toDouble / n_goals
    def max_ratio: Option[Double] = if (ratios.isEmpty) None else Some(ratios.max)
    def mean_ratio: Option[Double] =
      if (ratios.isEmpty) None else Some(ratios.sum / ratios.length)
  }

  /* M3 aggregate: every goal step counts in the denominator; only those with a
     configuration relation contribute a ratio. */
  def frame_ratios(steps: List[Step], cfg: Corpus_Config): M3_Summary = {
    val goals = steps.filter(_.kind == "goal")
    M3_Summary(goals.length, goals.flatMap(s => frame_ratio(s, cfg)).map(_.ratio))
  }


  /* ------------------------------------------------------------------ */
  /* per-proof analysis + the JSONL records                             */
  /* ------------------------------------------------------------------ */

  /* One section's derived views, computed at most once for the run of proofs
     that share it.

     The reference memoises the prose line-set on the section and recomputes
     `outer_source` per call; here BOTH hang off this object, because the Scala
     views are `def`s by design (a cached view is a second full copy of the
     corpus text — see `model.scala`).  `release()` drops the big one when the
     section's last proof has been analysed, so a whole-corpus `summary`, which
     keeps every `Proof_Metrics` alive, does not accumulate a second copy of the
     archive.  The prose mask is one byte per line and stays. */
  final class Sec_Ctx(val sec: Theory_Section) {
    val lines: Array[String] = sec.source

    private var _outer: Array[String] = null
    def outer: Array[String] = {
      if (_outer == null) _outer = sec.outer_source
      _outer
    }

    private var _prose: Array[Boolean] = null
    def prose_mask: Array[Boolean] = {
      if (_prose == null)
        _prose = Entries.line_mask(lines.length, Usage_Graph.noise_spans(sec))
      _prose
    }

    def release(): Unit = { _outer = null }
  }

  /* The full shape analysis of one proof.  `steps` are annotated in place, so
     the pipeline order in `analyze_proof` is load-bearing.  M6 is NOT
     precomputed — it is the one heavy pass and only the `lemma` deep-dive needs
     the full curve, so callers run it on demand. */
  final class Proof_Metrics(
    val ctx: Sec_Ctx,
    val entry: Entry,
    val steps: List[Step],
    val cctx: Classify_Ctx,
    val live_max: Int,
    val live_mean: Double,
    val intro_consume: Intro_Consume,
    val redundancy: List[Block_Redundancy],
    val inductions: List[Induction]
  ) {
    def sec: Theory_Section = ctx.sec
    def theory: String = ctx.sec.theory
    def lemma: String = entry.name
    def goals: List[Step] = steps.filter(_.kind == "goal")
  }

  /* Run the full per-proof pipeline once, in dependency order (fan-in and live
     annotation mutate `steps`, so they precede any per-step record).  None for
     an entry with no proof body at all — a bare definition.  A one-liner `by`
     proof DOES yield a record, via its lone closing step.

     NO axis here reads the method table: `Step.method` is whatever stands in
     introducer position, so binding a different namespace cannot move a shape
     record.  The bound table still matters for `classify_identifier`, which is
     position-blind and genuinely needs one. */
  def analyze_proof(ctx: Sec_Ctx, entry: Entry,
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Option[Proof_Metrics] = {
    val steps = scan_steps(ctx, entry)
    if (steps.isEmpty) None
    else {
      val lines = ctx.lines
      annotate_fanin(steps, lines)
      val (live_max, live_mean) = live_fact_space(steps, lines)
      val cctx = build_ctx(ctx, entry, steps, corpus_consts)
      val ic = introduce_consume(steps, lines)
      val red = cross_step_redundancy(steps)
      val inductions = scan_inductions(ctx, entry)
      Some(new Proof_Metrics(ctx, entry, steps, cctx, live_max, live_mean, ic, red,
        inductions))
    }
  }

  /* Every proof-bearing entry across `sections`, in source order — the shared
     spine of every `shape` verb.  Entries with no proof are silently skipped,
     so a consumer sees only the measurable proofs. */
  def analyze_sections(sections: List[Theory_Section],
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  )(body: Proof_Metrics => Unit): Unit = {
    for (sec <- sections) {
      val ctx = new Sec_Ctx(sec)
      for (entry <- sec.entries) analyze_proof(ctx, entry, corpus_consts).foreach(body)
      ctx.release()
    }
  }

  /* (max, mean, p90) of a value list, or (0, 0, 0) when empty.  p90 is the
     nearest-rank order statistic — deterministic, no interpolation. */
  private def dist(vals: List[Double]): (Double, Double, Double) =
    if (vals.isEmpty) (0.0, 0.0, 0.0)
    else {
      val s = vals.sorted
      val p90 = s((s.length - 1) min (0.9 * s.length).toInt)
      (s.last, s.sum / s.length, p90)
    }

  /* Line and token counts over an inclusive 1-based span, split RAW vs CODE
     (raw minus prose).  Prose is the one shared "not proof" line-set, so the
     code lines here are exactly the lines `grep` / `methods` / the call graph
     treat as live — no second notion of "code". */
  def region_counts(ctx: Sec_Ctx, lo: Int, hi0: Int): (Int, Int, Int, Int) = {
    val lines = ctx.lines
    val hi = hi0 min lines.length
    if (lo < 1 || hi < lo) (0, 0, 0, 0)
    else {
      val prose = ctx.prose_mask
      var n = 0; var nc = 0; var t = 0; var tc = 0
      var ln = lo
      while (ln <= hi) {
        val ntok = stmt_token_count(lines(ln - 1))
        n += 1
        t += ntok
        if (!prose(ln)) { nc += 1; tc += ntok }
        ln += 1
      }
      (n, nc, t, tc)
    }
  }

  /* The per-proof aggregate row — the unit of `shape summary`'s table and the
     `shape census` stream.  Distributions are over the proof's GOAL steps with
     an as-written proposition; `n_bare` counts goal steps with none, and pools
     "bare by construction" with "the scanner found no proposition". */
  final case class Proof_Summary(
    theory: String, lemma: String,
    n_steps: Int, n_goals: Int, n_bare: Int,
    depth_max: Int,
    w2_max: Int, w2_mean: Double, w2_p90: Double,
    w1_max: Int, w1_mean: Double,
    const_max: Int, const_mean: Double,
    const_canon_max: Int, const_canon_mean: Double,
    fanin_max: Int, fanin_mean: Double, fanin_cited: Int,
    live_max: Int, live_mean: Double,
    dag_max: Double,
    intro: Int, consume: Int, both: Int, ratio: Option[Double],
    trivial_frac: Option[Double], removable_w2: Double,
    method_kinds: List[(String, Int)],
    n_induct: Int, induct_terms_max: Int, induct_arbitrary_max: Int,
    induct_rule: Int, induct_recursion: Int,
    proof_lines: Int, proof_lines_code: Int, proof_tokens: Int, proof_tokens_code: Int,
    entry_lines: Int,
    session: Option[String])

  def summarize(pm: Proof_Metrics): Proof_Summary = {
    val goals = pm.goals
    val stated = goals.filter(_.stmt_text.nonEmpty)
    val depth_max = pm.steps.map(_.depth).foldLeft(0)(_ max _) + 1
    val (w2_max, w2_mean, w2_p90) = dist(stated.map(s => w2_src(s).toDouble))
    /* One w1_est pass per stated step yields both the free-var and the constant
       Width distributions. */
    val w1s = stated.map(s => w1_est(s, pm.cctx))
    val (w1_max, w1_mean, _) = dist(w1s.map(_.free.toDouble))
    val (const_max, const_mean, _) = dist(w1s.map(_.const.toDouble))
    val (canon_max, canon_mean, _) = dist(w1s.map(_.const_canon.toDouble))
    val (fanin_max, fanin_mean, _) = dist(goals.map(_.fanin.toDouble))
    val fanin_cited = goals.count(_.fanin != 0)
    val dag_max = if (pm.redundancy.isEmpty) 1.0 else pm.redundancy.map(_.dag_ratio).max
    val ic = pm.intro_consume
    val ind = summarize_inductions(pm.inductions)
    val e = pm.entry
    val (p_lines, p_lines_code, p_tokens, p_tokens_code) =
      if (e.proof_line != 0) region_counts(pm.ctx, e.proof_line, e.body_end_line)
      else (0, 0, 0, 0)
    Proof_Summary(
      pm.theory, pm.lemma, pm.steps.length, goals.length, goals.length - stated.length,
      depth_max,
      w2_max.toInt, w2_mean, w2_p90, w1_max.toInt, w1_mean,
      const_max.toInt, const_mean, canon_max.toInt, canon_mean,
      fanin_max.toInt, fanin_mean, fanin_cited, pm.live_max, pm.live_mean,
      dag_max, ic.introduce, ic.consume, ic.both, ic.ratio,
      trivial_frac(pm.steps), removable_w2_at_8(pm.steps, pm.cctx),
      method_kind_counts(pm.steps),
      ind.n, ind.terms_max, ind.arbitrary_max, ind.n_rule, ind.n_recursion,
      p_lines, p_lines_code, p_tokens, p_tokens_code, e.line_count,
      pm.sec.session)
  }
}
