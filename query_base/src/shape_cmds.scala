/*  Title:      query_base/src/shape_cmds.scala

The command layer for `query shape` — five views over the one step-scanner
engine in `shape.scala`, plus the JSONL join contract they share.

This module only FORMATS; every number comes from `Shape`.  It sits above
`commands` (it reuses `parse_locus` / `resolve_theory`) and below `cli`.

Three things here are byte-level reproductions of Python behaviour rather than
ports of an algorithm, and each is a place where the obvious JVM spelling is
silently wrong:

  * `Jsonl` reproduces `json.dumps(obj)` with its DEFAULTS — `", "` / `": "`
    separators, insertion order (not sorted, unlike the `graph` writer's
    `sort_keys=True`), and `ensure_ascii=True`, so every non-ASCII character is
    written `\uXXXX`.  It is therefore a different writer from `Json` in
    `usage.scala`, deliberately, because the reference calls `json.dumps` two
    different ways.
  * floats go through `Py.repr_float`, Python's shortest-round-trip repr.
  * the human tables' percentages and ratios go through `Py.format_fixed`,
    Python's half-EVEN `format(x, '.Nf')`.

`Toml` is a deliberately small reader for the M3 corpus configs: the reference
reads them with `tomllib`, and the Isabelle Scala classpath has no TOML parser.
It covers what an M3 config is — tables of string-list values — and refuses
anything else rather than guessing.
*/

package isabelle.query


import isabelle.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => JPath, Paths}

import scala.collection.mutable


/* --------------------------------------------------------------------- */
/* `json.dumps(obj)` with the stdlib defaults                             */
/* --------------------------------------------------------------------- */

object Jsonl {
  sealed abstract class V
  final case class S(s: String) extends V
  final case class I(n: Long) extends V
  final case class D(x: Double) extends V
  final case class B(b: Boolean) extends V
  case object Null extends V
  final case class O(fields: List[(String, V)]) extends V

  /* `py_encode_basestring_ascii`: the short escapes, everything below a space,
     and everything above `~` — DEL included — as `\uXXXX`, with a surrogate
     pair for a supplementary-plane character (which is what the JVM stores
     anyway, so the pair falls out of iterating chars). */
  def quote(s: String): String = {
    val buf = new StringBuilder
    buf += '"'
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"' => buf ++= "\\\""
        case '\\' => buf ++= "\\\\"
        case '\b' => buf ++= "\\b"
        case '\f' => buf ++= "\\f"
        case '\n' => buf ++= "\\n"
        case '\r' => buf ++= "\\r"
        case '\t' => buf ++= "\\t"
        case _ =>
          if (c >= ' ' && c <= '~') buf += c
          else buf ++= "\\u%04x".format(c.toInt)
      }
      i += 1
    }
    buf += '"'
    buf.toString
  }

  def render(v: V): String = {
    val buf = new StringBuilder
    write(buf, v)
    buf.toString
  }

  private def write(buf: StringBuilder, v: V): Unit =
    v match {
      case S(s) => buf ++= quote(s)
      case I(n) => buf ++= n.toString
      case D(x) => buf ++= Py.repr_float(x)
      case B(b) => buf ++= (if (b) "true" else "false")
      case Null => buf ++= "null"
      case O(fields) =>
        buf += '{'
        var first = true
        for ((k, x) <- fields) {
          if (!first) buf ++= ", "
          first = false
          buf ++= quote(k)
          buf ++= ": "
          write(buf, x)
        }
        buf += '}'
    }

  def opt(x: Option[Double]): V = x match { case Some(d) => D(d); case None => Null }

  /* Just enough of a reader for `--resume`: the reference calls `json.loads`
     per line and takes two string fields off the object, skipping a line that
     does not parse (so a truncated final record from a killed run is
     tolerated).  Returns None on anything malformed. */
  def parse_object(s: String): Option[Map[String, V]] = {
    var i = 0
    def ws(): Unit = while (i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\t' ||
      s.charAt(i) == '\n' || s.charAt(i) == '\r')) i += 1
    def fail(): Nothing = throw new IllegalArgumentException("json")
    def value(): V = {
      ws()
      if (i >= s.length) fail()
      s.charAt(i) match {
        case '"' => S(string())
        case '{' =>
          i += 1; ws()
          val fields = new mutable.ListBuffer[(String, V)]
          if (i < s.length && s.charAt(i) == '}') i += 1
          else {
            var go = true
            while (go) {
              ws()
              val k = string()
              ws()
              if (i >= s.length || s.charAt(i) != ':') fail()
              i += 1
              fields += ((k, value()))
              ws()
              if (i < s.length && s.charAt(i) == ',') i += 1
              else if (i < s.length && s.charAt(i) == '}') { i += 1; go = false }
              else fail()
            }
          }
          O(fields.toList)
        case '[' =>
          i += 1; ws()
          if (i < s.length && s.charAt(i) == ']') { i += 1; Null }
          else {
            var go = true
            while (go) {
              value(); ws()
              if (i < s.length && s.charAt(i) == ',') i += 1
              else if (i < s.length && s.charAt(i) == ']') { i += 1; go = false }
              else fail()
            }
            Null
          }
        case 't' => if (s.startsWith("true", i)) { i += 4; B(true) } else fail()
        case 'f' => if (s.startsWith("false", i)) { i += 5; B(false) } else fail()
        case 'n' => if (s.startsWith("null", i)) { i += 4; Null } else fail()
        case _ =>
          val start = i
          while (i < s.length && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i += 1
          if (i == start) fail()
          try D(s.substring(start, i).toDouble) catch { case _: Exception => fail() }
      }
    }
    def string(): String = {
      if (i >= s.length || s.charAt(i) != '"') fail()
      i += 1
      val buf = new StringBuilder
      var go = true
      while (go) {
        if (i >= s.length) fail()
        val c = s.charAt(i)
        if (c == '"') { i += 1; go = false }
        else if (c == '\\') {
          i += 1
          if (i >= s.length) fail()
          s.charAt(i) match {
            case '"' => buf += '"'; i += 1
            case '\\' => buf += '\\'; i += 1
            case '/' => buf += '/'; i += 1
            case 'b' => buf += '\b'; i += 1
            case 'f' => buf += '\f'; i += 1
            case 'n' => buf += '\n'; i += 1
            case 'r' => buf += '\r'; i += 1
            case 't' => buf += '\t'; i += 1
            case 'u' =>
              if (i + 4 >= s.length) fail()
              buf += Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar
              i += 5
            case _ => fail()
          }
        }
        else { buf += c; i += 1 }
      }
      buf.toString
    }
    try {
      val v = value()
      ws()
      if (i != s.length) None
      else v match { case O(fields) => Some(fields.toMap); case _ => None }
    }
    catch { case _: Exception => None }
  }
}


/* --------------------------------------------------------------------- */
/* the M3 corpus config                                                   */
/* --------------------------------------------------------------------- */

object Toml {
  final class Error(val message: String) extends RuntimeException(null, null, false, false)

  /* One `[corpus]` table per entry, each holding string lists.  Comments,
     blank lines and multi-line arrays are handled; any other construct is an
     error rather than a guess, since a silently-ignored key would change a
     measurement without saying so. */
  def read_corpus_configs(path: JPath): Map[String, Shape.Corpus_Config] = {
    val text =
      try new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      catch { case exn: Exception => throw new Error(s"cannot read $path: ${exn.getMessage}") }
    val tables = mutable.LinkedHashMap.empty[String, mutable.LinkedHashMap[String, List[String]]]
    var current: mutable.LinkedHashMap[String, List[String]] = null
    val (lines, _) = Py.split_lines(text)
    var i = 0
    while (i < lines.length) {
      val line = Py.strip(strip_comment(lines(i)))
      if (line.isEmpty) i += 1
      else if (line.startsWith("[")) {
        if (!line.endsWith("]")) throw new Error(s"bad table header: ${lines(i)}")
        val name = Py.strip(line.substring(1, line.length - 1)).replaceAll("^\"|\"$", "")
        current = mutable.LinkedHashMap.empty[String, List[String]]
        tables(name) = current
        i += 1
      }
      else {
        val eq = line.indexOf('=')
        if (eq < 0) throw new Error(s"not a key/value line: ${lines(i)}")
        val key = Py.strip(line.substring(0, eq))
        var rhs = Py.strip(line.substring(eq + 1))
        /* An array may wrap; join continuation lines until the brackets close. */
        while (rhs.startsWith("[") && !rhs.endsWith("]") && i + 1 < lines.length) {
          i += 1
          rhs = Py.strip(rhs + " " + Py.strip(strip_comment(lines(i))))
        }
        if (current == null) throw new Error(s"key outside a table: ${lines(i)}")
        current(key) = parse_value(rhs)
        i += 1
      }
    }
    tables.view.mapValues(t =>
      Shape.Corpus_Config(
        constructors = t.getOrElse("constructors", Nil).toSet,
        selectors = t.getOrElse("selectors", Nil).toSet,
        relations = t.getOrElse("relations", Nil).toSet)).toMap
  }

  /* A `#` outside a string starts a comment. */
  private def strip_comment(line: String): String = {
    var in_str = false
    var i = 0
    while (i < line.length) {
      val c = line.charAt(i)
      if (c == '"') in_str = !in_str
      else if (c == '#' && !in_str) return line.substring(0, i)
      i += 1
    }
    line
  }

  private def parse_value(rhs: String): List[String] = {
    if (rhs.startsWith("[")) {
      if (!rhs.endsWith("]")) throw new Error(s"unterminated array: $rhs")
      val body = Py.strip(rhs.substring(1, rhs.length - 1))
      if (body.isEmpty) Nil
      else body.split(",", -1).toList.map(Py.strip).filter(_.nonEmpty).map(unquote)
    }
    else List(unquote(rhs))
  }

  private def unquote(s: String): String =
    if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) ||
      (s.startsWith("'") && s.endsWith("'")))) s.substring(1, s.length - 1)
    else s
}


/* --------------------------------------------------------------------- */
/* the five views                                                         */
/* --------------------------------------------------------------------- */

object Shape_Cmds {
  private def padr(s: String, w: Int): String =
    if (s.length >= w) s else s + (" " * (w - s.length))

  private def padl(s: String, w: Int): String =
    if (s.length >= w) s else (" " * (w - s.length)) + s

  private def padl(n: Int, w: Int): String = padl(n.toString, w)

  /* Python's `" ".join(text.split())` — split on RUNS of whitespace, dropping
     the empties, which is not what `String.split("\\s+")` does at either end. */
  private def collapse_ws(text: String): String = {
    val parts = new mutable.ListBuffer[String]
    var i = 0
    while (i < text.length) {
      while (i < text.length && Py.is_space(text.charAt(i))) i += 1
      val start = i
      while (i < text.length && !Py.is_space(text.charAt(i))) i += 1
      if (i > start) parts += text.substring(start, i)
    }
    parts.mkString(" ")
  }

  /* A one-line, length-capped statement preview.  The cap counts CODE POINTS,
     as Python's `len` and slicing do. */
  private def preview(text0: String, cap: Int = 56): String = {
    val text = collapse_ws(text0)
    val n = text.codePointCount(0, text.length)
    if (n <= cap) text
    else text.substring(0, text.offsetByCodePoints(0, cap - 1)) + "…"
  }

  /* Metric extractors for the ranked / tabular views, keyed by `--metric`.
     Each takes a step and its classifier context (only w1 needs the context),
     so one table serves `widest` and the `steps` table. */
  def metric_value(metric: String, s: Shape.Step, ctx: Shape.Classify_Ctx): Int =
    metric match {
      case "w2" => Shape.w2_src(s)
      case "w1" => Shape.w1_est(s, ctx).free
      case "fanin" => s.fanin
      case "live" => s.live
      case _ => 0
    }


  /* --- the JSONL join contract ---------------------------------------- */

  /* One per-step record.  Stable position keys (theory / lemma / line, plus
     block / depth for structure); estimator columns carry `_est`, the exact
     source metrics do not.  Metric fields are 0 / false on non-goal or
     bare-statement steps — a UNIFORM schema is friendlier to a columnar join
     than a sparse one.  The M3 columns are added only under a corpus config,
     so the schema is config-gated and self-describing. */
  def step_record(step: Shape.Step, ctx: Shape.Classify_Ctx, lines: Array[String],
    cfg: Option[Shape.Corpus_Config]
  ): Jsonl.V = {
    val w1 = Shape.w1_est(step, ctx)
    val cited =
      if (step.kind == "other") Set.empty[String]
      else Usage_Graph.cited_facts_on_line(lines(step.line - 1))._1
    val base = List[(String, Jsonl.V)](
      "theory" -> Jsonl.S(step.theory),
      "lemma" -> Jsonl.S(step.lemma),
      "line" -> Jsonl.I(step.line),
      "block" -> Jsonl.I(step.block),
      "depth" -> Jsonl.I(step.depth),
      "kind" -> Jsonl.S(step.kind),
      "kw" -> Jsonl.S(step.kw),
      "goal_cmd" -> Jsonl.S(step.goal_cmd),
      "method" -> Jsonl.S(step.method),
      "label" -> Jsonl.S(step.label),
      "stmt_start" -> Jsonl.I(step.stmt_start),
      "stmt_end" -> Jsonl.I(step.stmt_end),
      "w2_src" -> Jsonl.I(Shape.w2_src(step)),
      "w1_est" -> Jsonl.I(w1.free),
      "w1_schematic_est" -> Jsonl.I(w1.schematic),
      "w1_bound_est" -> Jsonl.I(w1.bound),
      "const_est" -> Jsonl.I(w1.const),
      "const_canon_est" -> Jsonl.I(w1.const_canon),
      "fanin" -> Jsonl.I(step.fanin),
      "fanin_covered" -> Jsonl.B(step.fanin_covered),
      "live" -> Jsonl.I(step.live),
      "introduces" -> Jsonl.B(Shape.introduces(step)),
      "consumes" -> Jsonl.B(cited.nonEmpty))
    val m3 =
      cfg match {
        case None => Nil
        case Some(c) =>
          val fr = Shape.frame_ratio(step, c)
          List[(String, Jsonl.V)](
            "frame_ratio" -> fr.map(f => Jsonl.D(f.ratio)).getOrElse(Jsonl.Null),
            "frame_mentioned" -> fr.map(f => Jsonl.I(f.mentioned)).getOrElse(Jsonl.Null),
            "frame_changed" -> fr.map(f => Jsonl.I(f.changed)).getOrElse(Jsonl.Null))
      }
    Jsonl.O(base ::: m3)
  }

  /* One per-proof record.  `session` comes FIRST — the coarsest key, and the
     one that disambiguates a theory name repeated across AFP entries.  Designed
     as a SUFFICIENT STATISTIC per proof: the analysis layer reads these
     scalars and never re-scans. */
  def summary_record(ps: Shape.Proof_Summary): Jsonl.V =
    Jsonl.O(List[(String, Jsonl.V)](
      "session" -> ps.session.map(Jsonl.S(_)).getOrElse(Jsonl.Null),
      "theory" -> Jsonl.S(ps.theory),
      "lemma" -> Jsonl.S(ps.lemma),
      "n_steps" -> Jsonl.I(ps.n_steps),
      "n_goals" -> Jsonl.I(ps.n_goals),
      "n_bare" -> Jsonl.I(ps.n_bare),
      "depth_max" -> Jsonl.I(ps.depth_max),
      "w2_src_max" -> Jsonl.I(ps.w2_max),
      "w2_src_mean" -> Jsonl.D(ps.w2_mean),
      "w2_src_p90" -> Jsonl.D(ps.w2_p90),
      "w1_est_max" -> Jsonl.I(ps.w1_max),
      "w1_est_mean" -> Jsonl.D(ps.w1_mean),
      "const_est_max" -> Jsonl.I(ps.const_max),
      "const_est_mean" -> Jsonl.D(ps.const_mean),
      "const_canon_est_max" -> Jsonl.I(ps.const_canon_max),
      "const_canon_est_mean" -> Jsonl.D(ps.const_canon_mean),
      "fanin_max" -> Jsonl.I(ps.fanin_max),
      "fanin_mean" -> Jsonl.D(ps.fanin_mean),
      "fanin_cited" -> Jsonl.I(ps.fanin_cited),
      "live_max" -> Jsonl.I(ps.live_max),
      "live_mean" -> Jsonl.D(ps.live_mean),
      "dag_ratio_est_max" -> Jsonl.D(ps.dag_max),
      "introduce" -> Jsonl.I(ps.intro),
      "consume" -> Jsonl.I(ps.consume),
      "both" -> Jsonl.I(ps.both),
      "ratio" -> Jsonl.opt(ps.ratio),
      "trivial_frac" -> Jsonl.opt(ps.trivial_frac),
      "removable_w2_est_at_8" -> Jsonl.D(ps.removable_w2),
      "method_kinds" -> Jsonl.O(ps.method_kinds.map(kv => kv._1 -> Jsonl.I(kv._2))),
      /* Why the `n_bare` steps are bare; the three keys sum to `n_bare`. */
      "bare_kinds" -> Jsonl.O(ps.bare_kinds.map(kv => kv._1 -> Jsonl.I(kv._2))),
      "n_induct" -> Jsonl.I(ps.n_induct),
      "induct_terms_max" -> Jsonl.I(ps.induct_terms_max),
      "induct_arbitrary_max" -> Jsonl.I(ps.induct_arbitrary_max),
      "induct_rule" -> Jsonl.I(ps.induct_rule),
      "induct_recursion" -> Jsonl.I(ps.induct_recursion),
      "proof_lines" -> Jsonl.I(ps.proof_lines),
      "proof_lines_code" -> Jsonl.I(ps.proof_lines_code),
      "proof_tokens" -> Jsonl.I(ps.proof_tokens),
      "proof_tokens_code" -> Jsonl.I(ps.proof_tokens_code),
      "entry_lines" -> Jsonl.I(ps.entry_lines)))


  /* --- summary --------------------------------------------------------- */

  /* The size (in lines) of one proof under the --scope / --content selection.
     Proof scope reuses the counts already on the summary row; entry scope
     recomputes over the whole-entry span (statement + proof + doc). */
  private def proof_size(pm: Shape.Proof_Metrics, ps: Shape.Proof_Summary,
    scope: String, content: String
  ): Int = {
    val (raw, code) =
      if (scope == "proof") (ps.proof_lines, ps.proof_lines_code)
      else {
        val (r, c, _, _) = Shape.region_counts(pm.ctx, pm.entry.src_start, pm.entry.thy_end)
        (r, c)
      }
    if (content == "code") code else if (content == "prose") raw - code else raw
  }

  /* Per-theory aggregate table, or one per-proof JSONL record per line.  Table
     columns are MAXES — the widest single occurrence in each theory — plus
     counts; per-proof means live in the JSONL, which downstream re-aggregates
     however it needs. */
  def cmd_shape_summary(out: Out, sections: List[Theory_Section], as_json: Boolean,
    scope: String, content: String,
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Unit = {
    if (as_json) {
      Shape.analyze_sections(sections, corpus_consts)(pm =>
        out.println(Jsonl.render(summary_record(Shape.summarize(pm)))))
      return
    }
    /* The rows are reduced as the sections stream past rather than kept as
       `Proof_Metrics`, so a whole-corpus run holds one small row per proof
       instead of the analysis of every proof in the archive. */
    var n_proofs = 0
    val by_theory = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[(Shape.Proof_Summary, Int)]]
    Shape.analyze_sections(sections, corpus_consts) { pm =>
      val ps = Shape.summarize(pm)
      n_proofs += 1
      by_theory.getOrElseUpdate(ps.theory, new mutable.ListBuffer) +=
        ((ps, proof_size(pm, ps, scope, content)))
    }
    if (n_proofs == 0) {
      out.println("No proofs with structured steps found.")
      return
    }
    out.println("# Proof shape summary\n")
    out.println(s"$n_proofs proofs across ${by_theory.size} theories  " +
      "(source-level shape metrics, parsed live)\n")
    out.println("Per-theory maxes: the widest single step, most-cited step, peak live " +
      "facts,\nwidest block (M4 DAG ratio), and the longest " +
      s"$scope in $content lines.  `--json` emits one per-proof record " +
      "for real analysis.\n")
    out.println("| Theory | Proofs | Goals | depth:max | Bare% | w2:max | w1:max | " +
      "fanin:max | live:max | dag:max | lines:max |")
    out.println("|--------|-------:|------:|----------:|------:|-------:|-------:|" +
      "----------:|---------:|--------:|----------:|")
    for ((theory, rows) <- by_theory) out.println(summary_row(theory, rows.toList))
    if (by_theory.size > 1)
      out.println(summary_row("**TOTAL**", by_theory.values.flatten.toList))
  }

  /* One aggregate row — counts summed, metrics (including the selected size)
     maxed. */
  private def summary_row(label: String, rows: List[(Shape.Proof_Summary, Int)]): String = {
    val pss = rows.map(_._1)
    val goals = pss.map(_.n_goals).sum
    val bare = pss.map(_.n_bare).sum
    val bare_pct = if (goals == 0) 0.0 else 100.0 * bare / goals
    s"| $label | ${pss.length} | $goals | " +
      s"${pss.map(_.depth_max).max} | ${Py.format_fixed(bare_pct, 0)}% | " +
      s"${pss.map(_.w2_max).max} | ${pss.map(_.w1_max).max} | " +
      s"${pss.map(_.fanin_max).max} | " +
      s"${pss.map(_.live_max).max} | " +
      s"${Py.format_fixed(pss.map(_.dag_max).max, 2)} | " +
      s"${rows.map(_._2).max} |"
  }


  /* --- steps ----------------------------------------------------------- */

  /* Resolve a SPAN token to (theory, lo, hi).  Two forms, the same grammar
     `enclosing` / `lines` use: a `THEORY:A..B` locus, or a bare `THEORY`.  An
     open `THEORY:A..` resolves `hi` to the theory end.  An unresolvable token
     fails fast, matching the FILES resolver. */
  private def resolve_span(out: Out, err: Out, sections: List[Theory_Section],
    span: Option[String]
  ): (Option[String], Int, Int) =
    span match {
      case None => (None, -1, -1)
      case Some(tok) =>
        Commands.parse_locus(tok) match {
          case Some((file_token, lo, hi0)) =>
            val sec = Commands.resolve_theory(sections, file_token)
            val theory = sec.map(_.theory).getOrElse(file_token)
            val hi = hi0.getOrElse(sec.map(_.thy_lines).getOrElse(1000000000))
            (Some(theory), lo, hi)
          case None =>
            Commands.resolve_theory(sections, tok) match {
              case Some(sec) => (Some(sec.theory), -1, -1)
              case None =>
                out.flush()
                err.println(s"ERROR: not a theory or FILE:A..B locus: $tok")
                throw Exit_Code(1)
            }
        }
    }

  def cmd_shape_steps(out: Out, err: Out, sections: List[Theory_Section],
    span: Option[String], as_json: Boolean, all_steps: Boolean,
    cfg: Option[Shape.Corpus_Config],
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Unit = {
    val (theory, lo, hi) = resolve_span(out, err, sections, span)
    /* The label, not `step.theory`: a `Step` carries a theory NAME, which is
       the JSON record's position key and must stay one, but the human table
       prints a locus and 461 AFP theory names are shared [disambig-loci]. */
    val labels = Render.locus_labels(sections)
    val triples =
      new mutable.ListBuffer[(Shape.Step, Shape.Classify_Ctx, Array[String], String)]
    Shape.analyze_sections(sections, corpus_consts) { pm =>
      if (theory.isEmpty || pm.theory == theory.get) {
        val lines = pm.ctx.lines
        val label = labels.getOrElse(pm.sec.path, pm.theory)
        for (s <- pm.steps) {
          if ((lo < 0 || (lo <= s.line && s.line <= hi)) && (all_steps || s.kind == "goal"))
            triples += ((s, pm.cctx, lines, label))
        }
      }
    }
    if (as_json) {
      for ((s, ctx, lines, _) <- triples)
        out.println(Jsonl.render(step_record(s, ctx, lines, cfg)))
      return
    }
    if (triples.isEmpty) {
      out.println("No steps in scope.")
      return
    }
    out.println(padr("location", 20) + " " + padr("kind", 8) + " " + padl("w2", 4) + " " +
      padl("w1", 4) + " " + padl("fan", 4) + " " + padl("live", 4) + "  statement")
    out.println(padr("-" * 20, 20) + " " + padr("-" * 8, 8) + " " + padl("-" * 4, 4) + " " +
      padl("-" * 4, 4) + " " + padl("-" * 4, 4) + " " + padl("-" * 4, 4) + "  " + "-" * 9)
    for ((s, ctx, _, label) <- triples) {
      val loc = label + ":" + s.line
      val w1 = Shape.w1_est(s, ctx).free
      out.println(padr(loc, 20) + " " + padr(s.kind, 8) + " " + padl(Shape.w2_src(s), 4) +
        " " + padl(w1, 4) + " " + padl(s.fanin, 4) + " " + padl(s.live, 4) + "  " +
        preview(s.stmt_text))
    }
  }


  /* --- lemma ----------------------------------------------------------- */

  /* Exact match first, then unique-ish substring — the same lookup `show`
     makes. */
  private def resolve_lemma(sections: List[Theory_Section], name: String
  ): Option[(Theory_Section, Entry)] = {
    def first(p: Entry => Boolean): Option[(Theory_Section, Entry)] =
      sections.iterator.flatMap(sec => sec.entries.iterator.filter(p).map(e => (sec, e)))
        .nextOption()
    first(_.name == name).orElse(first(e => e.name != "?" && e.name.contains(name)))
  }

  def cmd_shape_lemma(out: Out, sections: List[Theory_Section], name: String,
    as_json: Boolean, cfg: Option[Shape.Corpus_Config],
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Unit = {
    resolve_lemma(sections, name) match {
      case None => out.println(s"No proof-bearing entry matching '$name'.")
      case Some((sec, entry)) =>
        val ctx = new Shape.Sec_Ctx(sec)
        Shape.analyze_proof(ctx, entry, corpus_consts) match {
          case None => out.println(s"'${entry.name}' has no structured proof body.")
          case Some(pm) =>
            val lines = ctx.lines
            if (as_json) {
              for (s <- pm.steps) out.println(Jsonl.render(step_record(s, pm.cctx, lines, cfg)))
            }
            else {
              val ps = Shape.summarize(pm)
              val thy = Render.locus_labels(sections).getOrElse(sec.path, sec.theory)
              out.println(s"${entry.name}  (${entry.tag} $thy:${entry.src_start}.." +
                s"${entry.thy_end})\n")
              out.println(padl("line", 5) + " " + padr("kind", 8) + " " + padl("w2", 4) + " " +
                padl("w1", 4) + " " + padl("fan", 4) + " " + padl("live", 4) + "  statement")
              for (s <- pm.steps) {
                val w1 = Shape.w1_est(s, pm.cctx).free
                out.println(padl(s.line, 5) + " " + padr(s.kind, 8) + " " +
                  padl(Shape.w2_src(s), 4) + " " + padl(w1, 4) + " " + padl(s.fanin, 4) +
                  " " + padl(s.live, 4) + "  " + preview(s.stmt_text))
              }
              out.println(s"\n${ps.n_goals} goals (${ps.n_bare} bare)  " +
                s"w2 max ${ps.w2_max} mean ${Py.format_fixed(ps.w2_mean, 1)}  " +
                s"w1 max ${ps.w1_max}  fan-in max ${ps.fanin_max}  " +
                s"live max ${ps.live_max} mean ${Py.format_fixed(ps.live_mean, 1)}")
              val ratio = ps.ratio.map(Py.format_fixed(_, 2)).getOrElse("n/a")
              out.println(s"M4 dag:max ${Py.format_fixed(ps.dag_max, 2)}   " +
                s"M5c introduce/consume ${ps.intro}/${ps.consume} (ratio $ratio)")
              print_m6(out, pm)
            }
        }
    }
  }

  /* The M6 width-vs-k curve for the proof's widest block (largest raw summed
     w2), or nothing when no block has goal statements.  Ties go to the FIRST
     such block, as Python's `max` does. */
  private def print_m6(out: Out, pm: Shape.Proof_Metrics): Unit = {
    val curves =
      Shape.extension_curve(pm.steps, pm.cctx).filter(c => c.w2.nonEmpty && c.w2.head > 0)
    if (curves.nonEmpty) {
      var c = curves.head
      for (x <- curves.tail if x.w2.head > c.w2.head) c = x
      val ks = c.ks.map(k => padl(k, 4)).mkString("  ")
      val w2 = c.w2.map(v => padl(v, 4)).mkString("  ")
      out.println(s"M6 widest block (${c.n_goals} goals)  k: $ks")
      out.println(padl("", 26) + "w2: " + w2)
    }
  }


  /* --- widest ---------------------------------------------------------- */

  /* The N widest goal steps in scope, ranked by `metric`.  Ties break by source
     position for determinism. */
  def cmd_shape_widest(out: Out, sections: List[Theory_Section], top: Int, metric: String,
    as_json: Boolean, corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Unit = {
    val labels = Render.locus_labels(sections)
    val rows = new mutable.ListBuffer[(Int, String, Int, Shape.Step, Shape.Classify_Ctx,
      Array[String], String)]
    Shape.analyze_sections(sections, corpus_consts) { pm =>
      val lines = pm.ctx.lines
      val label = labels.getOrElse(pm.sec.path, pm.theory)
      for (s <- pm.goals)
        rows += ((metric_value(metric, s, pm.cctx), s.theory, s.line, s, pm.cctx, lines,
          label))
    }
    /* Widest first; ties by (theory, line) ascending for a stable order.  The
       sort key stays the theory NAME, not the label: the tie-break is about
       determinism, and a qualified label would reorder equal-width rows for no
       reason a reader could see [disambig-loci]. */
    val ranked =
      rows.toList.sortBy(r => (-r._1, r._2, r._3)).take(top max 0)
    if (as_json) {
      for (r <- ranked) out.println(Jsonl.render(step_record(r._4, r._5, r._6, None)))
      return
    }
    if (ranked.isEmpty) {
      out.println("No goal steps found.")
      return
    }
    out.println(s"Top ${ranked.length} widest steps by $metric:\n")
    out.println(padl(metric, 5) + " " + padr("location", 22) + " " + padr("lemma", 24) +
      "  statement")
    out.println(padl("-" * 5, 5) + " " + padr("-" * 22, 22) + " " + padr("-" * 24, 24) +
      "  " + "-" * 9)
    for ((value, _, line, s, _, _, label) <- ranked)
      out.println(padl(value, 5) + " " + padr(label + ":" + line, 22) + " " +
        padr(s.lemma, 24) + "  " + preview(s.stmt_text))
  }


  /* --- census ---------------------------------------------------------- */

  /* What a census actually managed to do.  Returned rather than exited on,
     because the exit code is the CLI's to choose. */
  final case class Census_Outcome(sessions: Int, loaded: Int, skipped: Int, records: Int)

  /* Stream one per-proof record per entry, one SESSION at a time in a single
     process.  `groups` yields (name, load) pairs where `load` is a THUNK, and
     three things follow from that which are unavailable if the caller hands
     over parsed sections: memory stays bounded by the largest single session;
     a session that cannot even be LOADED is isolated, because the thunk is
     called inside the guard; and start-up is paid once rather than per entry.

     Output is flushed per session, not per record: a killed run still leaves a
     valid JSONL prefix, but a whole-archive run does not pay a syscall per
     proof.  Skips go to stderr — never stdout, which is the record stream and
     must stay machine-readable — and are counted; the caller decides what a run
     of nothing means. */
  def cmd_shape_census(out: Out, err: Out,
    groups: Iterator[(String, () => List[Theory_Section])],
    resume: Option[String],
    corpus_consts: Set[String] = Shape_Data.CORPUS_CONSTANTS
  ): Census_Outcome = {
    val done = resume.map(load_done).getOrElse(Set.empty[(String, String)])
    var sessions = 0
    var loaded = 0
    var skipped = 0
    var records = 0
    for ((name, load) <- groups) {
      sessions += 1
      try {
        val sections = load()
        Shape.analyze_sections(sections, corpus_consts) { pm =>
          if (!done((pm.theory, pm.lemma))) {
            out.println(Jsonl.render(summary_record(Shape.summarize(pm))))
            records += 1
          }
        }
        out.flush()
        loaded += 1
      }
      catch {
        /* NOT a session failure — the consumer went away.  `census | head` is
           the ordinary way to eyeball a corpus run, and swallowing this would
           report every remaining session as skipped and then fail a run that
           had worked perfectly. */
        case exn: Broken_Pipe => throw exn
        case exn: Exit_Code => throw exn
        case exn: Throwable =>
          /* Broad on purpose.  A census exists to survive a corpus, and the
             failures are open-ended; narrowing this would trade a named
             exception for losing every later session. */
          skipped += 1
          err.println(s"isabelle query: session '$name' skipped: " +
            s"${exn.getClass.getSimpleName}: ${exn.getMessage}")
      }
    }
    Census_Outcome(sessions, loaded, skipped, records)
  }

  /* The (theory, lemma) keys already present in a prior census.  A missing or
     unreadable file is an empty set (start fresh); a malformed line is skipped,
     so a truncated final record from a kill is tolerated. */
  private def load_done(path: String): Set[(String, String)] = {
    val out = mutable.Set.empty[(String, String)]
    try {
      val text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
      for (line0 <- Py.split_lines(text)._1) {
        val line = Py.strip(line0)
        if (line.nonEmpty) {
          for (obj <- Jsonl.parse_object(line)) {
            (obj.get("theory"), obj.get("lemma")) match {
              case (Some(Jsonl.S(t)), Some(Jsonl.S(l))) => out += ((t, l))
              case _ =>
            }
          }
        }
      }
    }
    catch { case _: Exception => () }
    out.toSet
  }
}
