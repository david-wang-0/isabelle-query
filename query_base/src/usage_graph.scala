/*  Title:      query_base/src/graph.scala

Usage analysis over the parsed index: which entry owns a line, what a line
cites, and the name-level call graph the whole `callers` / `callees` /
`unused` / `graph` family reads.

Everything here answers a *usage* question rather than a text question, and it
sits below the commands so the two cannot drift:

  * line -> entry attribution (`build_line_index` / `entry_at_line`) and the
    name indices (`sections_by_theory` / `entry_by_name`);
  * the prose and definition-site exclusion masks shared by single-name search
    and bulk graph construction (`noise_spans` / `noise_ranges` /
    `build_def_sites`);
  * the citation router (`is_citation_name`, over `Namespace.non_citation`)
    and the single-pass call graph (`build_call_graph`);
  * the proof-method census (`scan_methods`) — the router's complement: the
    tokens the router rejects as fact edges are the method uses it tallies;
  * the one breadth-first walk behind every `-r` form (`bfs_depths`).

Two performance notes that are really memory notes, because a corpus-global
call graph is the first thing here that is asked to hold the whole AFP at once:
the per-line candidate scan intersects a tokenised line against the name set
(O(source size), not O(lines x names)), and the def-site / prose masks are flat
per-theory structures rather than a materialised cross product.
*/

package isabelle.query


import isabelle.*

import java.nio.file.{Path => JPath}
import java.util.regex.Pattern

import scala.collection.mutable


object Usage_Graph {
  /* ------------------------------------------------------------------ */
  /* indices                                                            */
  /* ------------------------------------------------------------------ */

  /* Keyed by theory NAME, last section wins.  A NAME is not a section's
     identity — it is unique in a session and not in a corpus, and 461 AFP
     theory names are shared — so this map is only ever right for a question
     that is genuinely about the name rather than about a file.  Two are left:
     `deps` / `uses` / `refs` / `graph imports` walk the IMPORT graph, whose
     nodes really are names (an `imports` clause names a theory, not a path),
     and `entry_by_name` below answers "where is this name declared".
     Everything that indexes a SECTION — the line index, the prose mask, the
     declaration sites, and `callers`' hits — is keyed by `sec.path`
     [name-is-not-identity]; a name key there silently handed 758 of the AFP's
     sections another file's spans. */
  def sections_by_theory(sections: List[Theory_Section]): Map[String, Theory_Section] = {
    val m = mutable.LinkedHashMap.empty[String, Theory_Section]
    for (s <- sections) m(s.theory) = s
    m.toMap
  }

  /* First-wins index of entry name -> (theory, entry): where a name is declared
     in more than one theory the earliest section in load order owns the lookup. */
  def entry_by_name(sections: List[Theory_Section]): Map[String, (String, Entry)] = {
    val m = mutable.LinkedHashMap.empty[String, (String, Entry)]
    for (s <- sections; e <- s.entries if !m.contains(e.name)) m(e.name) = (s.theory, e)
    m.toMap
  }

  /* Prose, not proof: the document blocks, the section HEADINGS, the per-entry
     preambles and the lexical non-Isar regions.  `comment_ranges` is
     deliberately NOT unioned in — a marginal note normally trails live proof
     text, and dropping the line would lose the citation with the note. */
  def noise_spans(sec: Theory_Section): List[(Int, Int)] =
    sec.text_blocks ::: sec.heading_spans ::: sec.nonisar_ranges :::
      sec.entries.flatMap(_.preamble)

  /* Keyed by PATH, like the other two per-section indexes.  This one decides
     prose-vs-live, so a collapsed key does not merely misattribute a hit: it
     SUPPRESSES a real one (another file's `text` block blanks a live line) and
     admits a fake one (a prose mention the real mask covers).  38,068 lines of
     the AFP were classified the wrong way round [name-is-not-identity]. */
  def noise_ranges(sections: List[Theory_Section]): Map[JPath, List[(Int, Int)]] = {
    val m = mutable.LinkedHashMap.empty[JPath, List[(Int, Int)]]
    for (sec <- sections) m(sec.path) = noise_spans(sec)
    m.toMap
  }

  /* Per SECTION, the entry spans sorted for a binary search on a line.  Sorted
     by the two integers ONLY: the reference sorts `(src_start, thy_end, Entry)`
     triples and dies with a TypeError where one `axiomatization a b where …`
     line yields two entries with identical spans (`dev/DIVERGENCES.md`, D7).

     Keyed by `sec.path`: this map is written by one loop over the sections and
     read back by another, so a theory-name key handed 758 of the AFP's 9,910
     sections another FILE's spans — 381,710 of the 449,860 lines in them got a
     different owner [name-is-not-identity].  The sections are in hand at both
     ends, so the name was never doing work a path could not. */
  def build_line_index(sections: List[Theory_Section]
  ): Map[JPath, Array[(Int, Int, Entry)]] = {
    val index = mutable.LinkedHashMap.empty[JPath, Array[(Int, Int, Entry)]]
    for (sec <- sections)
      index(sec.path) =
        sec.entries.filter(_.thy_line > 0).map(e => (e.src_start, e.thy_end, e))
          .sortBy(t => (t._1, t._2)).toArray
    index.toMap
  }

  def entry_at_line(index: Array[(Int, Int, Entry)], line_no: Int): Option[Entry] = {
    var lo = 0
    var hi = index.length
    while (lo < hi) {
      val mid = (lo + hi) / 2
      if (index(mid)._1 <= line_no) lo = mid + 1 else hi = mid
    }
    val idx = lo - 1
    if (idx < 0) None
    else {
      val (start, end, e) = index(idx)
      if (start <= line_no && line_no <= end) Some(e) else None
    }
  }

  /* Per-section definition-site line spans, keyed by entry name, so a search
     for references to a name can exclude the declaration itself.  By PATH, for
     the reason `build_line_index` gives and with the same suppressing effect
     as `noise_ranges`: another file's def sites exclude lines that are genuine
     citations here [name-is-not-identity].

     With `names` given, only those names are tracked AND each entry's extra
     bound names (a `shows … and C:` conjunct, a `| r1: "…"` rule) are charged
     to the parent's span — without that the declaration of a bound name reads
     as a citation of itself.  The broad pass (`names = None`) deliberately does
     not do this, so bound names never leak into the call-graph universe. */
  def build_def_sites(sections: List[Theory_Section], names: Option[Set[String]]
  ): Map[JPath, Map[String, List[(Int, Int)]]] = {
    val out = mutable.LinkedHashMap.empty[JPath, Map[String, List[(Int, Int)]]]
    for (sec <- sections) {
      /* A buffer, not a set: the reference de-duplicates identical spans, and
         the only question ever asked of the result is whether a line falls in
         one of them — an answer a duplicate cannot change.  Over a corpus this
         is one fewer hash structure per declared name. */
      val site_map = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[(Int, Int)]]
      def add(n: String, span: (Int, Int)): Unit =
        site_map.getOrElseUpdate(n, new mutable.ListBuffer) += span
      for (e <- sec.entries if e.thy_line > 0) {
        val span = (e.thy_line, e.thy_end)
        names match {
          case None => add(e.name, span)
          case Some(ns) =>
            if (ns(e.name)) add(e.name, span)
            for (c <- e.bound_names if ns(c)) add(c, span)
        }
      }
      out(sec.path) = site_map.view.mapValues(_.toList).toMap
    }
    out.toMap
  }


  /* ------------------------------------------------------------------ */
  /* the citation router                                                */
  /* ------------------------------------------------------------------ */

  val DROP_NAMES_UPTO = 1

  private def is_all_digits(s: String): Boolean =
    s.nonEmpty && s.forall(Character.isDigit)

  /* Whether a name can denote a cited fact, as opposed to a method / attribute
     / keyword / numeral token — or a name too short to tell from a term
     variable.  A length-1 token (`x`, `a`, the wildcard `_`) is a bound
     variable in nearly every proof, so by default length-1 names are not
     citation nodes; `--drop-names-upto` moves the floor.  The
     method/keyword/numeral routing is independent of it. */
  def is_citation_name(name: String, drop_upto: Int = DROP_NAMES_UPTO): Boolean =
    name.codePointCount(0, name.length) > drop_upto &&
      !Namespace.non_citation(name) && !is_all_digits(name)


  /* ------------------------------------------------------------------ */
  /* positional fact citation                                           */
  /* ------------------------------------------------------------------ */

  /* The call graph asks "which entries MENTION name X" — position-blind.  A
     narrower question is "which facts does this proof step CITE": the arguments
     of `from`/`using`/`with`/`unfolding` and of the closing method.  Only the
     shadowed-name path needs it here (a `definition simp` has to earn its edges
     positionally), but it is the same extractor the width metric will read. */

  val GOAL_KEYWORDS: Set[String] =
    Set("have", "show", "hence", "thus", "obtain", "consider",
      "also", "finally", "interpret")
  val CONTEXT_KEYWORDS: Set[String] =
    Set("fix", "assume", "presume", "define", "let", "case")
  val PLUMBING_KEYWORDS: Set[String] =
    Set("from", "using", "with", "note", "moreover", "ultimately", "then")
  val CLOSING_KEYWORDS: Set[String] = Set("by", "apply", "done", "qed")

  /* `note` is excluded: it INTRODUCES a fact (`note x = …`), it does not cite. */
  private val CITE_LIST_WORDS: Set[String] = Set("from", "with", "using", "unfolding")
  /* A fact list runs to a goal/closing keyword — or to the NEXT cite keyword, so
     `using X unfolding Y` ends X's list rather than swallowing `unfolding`. */
  private val FACT_LIST_STOP: Set[String] =
    GOAL_KEYWORDS | CLOSING_KEYWORDS | CITE_LIST_WORDS | Set("proof", "and")

  /* Methods whose BARE (colon-free) arguments are fact names. */
  private val RULE_METHODS: Set[String] =
    Set("rule", "erule", "drule", "frule", "intro", "elim", "dest",
      "metis", "meson", "subst", "unfold", "fold")
  /* Methods whose bare arguments are terms / flags / induction variables — a
     covered non-fact, not an unclassifiable token. */
  private val TERM_ARG_METHODS: Set[String] =
    Set("simp", "simp_all", "auto", "blast", "fastforce", "force", "fast",
      "clarsimp", "clarify", "safe", "linarith", "arith", "presburger",
      "algebra", "argo", "order", "eval", "normalization", "cases", "case_tac",
      "induct", "induction", "induct_tac", "coinduct", "coinduction",
      "standard", "rule_tac", "subgoal", "hypsubst", "-", "goal_cases")
  /* `NAME:` markers after which the trailing identifiers are facts. */
  private val FACT_MARKER_WORDS: Set[String] =
    Set("simp", "add", "del", "intro", "dest", "elim", "cong", "split")
  /* Bracket attributes whose arguments are FACTS.  Every other attribute
     (`of`, `where`, `symmetric`, …) takes terms or is a bare flag. */
  private val ATTR_FACT_WORDS: Set[String] =
    Set("OF", "THEN", "unfolded", "folded", "simplified")

  private val DQUOTE_STRIP_RE: Pattern = Py.compile("\"[^\"]*\"")
  private val CARTOUCHE_STRIP_RE: Pattern = Py.compile("""\\<open>.*?\\<close>""")
  /* The same two regions captured rather than blanked: the TERM text of a line.
     A name occurring there is a constant or statement text — a real use even
     when the name also happens to be a proof method. */
  private val PROP_TEXT_RE: Pattern = Py.compile("""\"([^\"]*)\"|\\<open>(.*?)\\<close>""")
  /* `ISA_SYMBOL`, not the name atom: this is a RUN scanner over source the
     region scan has already redacted, so it asks only where a token ends.
     Narrowing it to name-legal symbols would split `\<open>foo` into two runs
     and offer `open` as a candidate fact name. */
  private val FACT_TOK_RE: Pattern =
    Py.compile(s"""\\.\\.|${Entries.ISA_WORD_CHAR}(?:${Entries.ISA_SYMBOL}|[\\w'.])*|[():,|\\[\\]]|:""")
  private val STRUCTURAL: Set[String] =
    Set("(", ")", "[", "]", "|", ",", ":", "..")

  private def strip_props(text: String): String =
    CARTOUCHE_STRIP_RE.matcher(DQUOTE_STRIP_RE.matcher(text).replaceAll(" "))
      .replaceAll(" ")

  /* Kept unless it cannot be an Isabelle fact name: a structural token, the
     lone wildcard `_`, or a token led by a digit (a numeral / statement-text
     artefact) or a prime (a type variable).  No length floor: this extractor
     only inspects fact-argument positions, where a single-char token IS a fact. */
  private def looks_like_fact(tok: String): Boolean =
    tok != "_" && !STRUCTURAL(tok) &&
      !(tok.nonEmpty && Character.isDigit(tok.charAt(0))) && !tok.startsWith("'")

  /* Consume a postfix attribute block — `toks(i)` is the opening `[` — adding
     only the arguments of the fact-composing attributes.  Returns the index
     just past the matching `]`. */
  private def consume_attr_block(toks: Array[String], i0: Int,
    facts: mutable.Set[String]
  ): Int = {
    val n = toks.length
    var i = i0
    var depth = 0
    var take = false
    while (i < n) {
      val t = toks(i)
      if (t == "[") { depth += 1; take = false }
      else if (t == "]") {
        depth -= 1
        take = false
        if (depth == 0) return i + 1
      }
      else if (ATTR_FACT_WORDS(t)) take = true
      else if (t == ",") take = false
      else if (take && looks_like_fact(t) && !(i + 1 < n && toks(i + 1) == ":"))
        facts += t
      i += 1
    }
    i                        // unbalanced — consumed to end of line
  }

  /* The fact names cited in the citation positions of one proof line, plus a
     `covered` flag (false when a method that is neither a known rule-method nor
     a known term-arg method is passed bare arguments). */
  def cited_facts_on_line(line: String): (Set[String], Boolean) = {
    val toks = {
      val buf = new mutable.ArrayBuffer[String]
      val m = FACT_TOK_RE.matcher(strip_props(line))
      while (m.find()) buf += m.group()
      buf.toArray
    }
    val facts = mutable.LinkedHashSet.empty[String]
    var covered = true
    var i = 0
    val n = toks.length
    var in_method = false
    var cur_method: String = null
    while (i < n) {
      val t = toks(i)
      if (CITE_LIST_WORDS(t)) {
        var j = i + 1
        var stop = false
        while (j < n && !stop && !FACT_LIST_STOP(toks(j))) {
          val tj = toks(j)
          if (tj == "[") j = consume_attr_block(toks, j, facts)
          else if (STRUCTURAL(tj)) stop = true
          else {
            if (looks_like_fact(tj)) facts += tj
            j += 1
          }
        }
        i = j
      }
      else if (t == "by" || t == "apply") {
        in_method = true
        cur_method = null
        i += 1
      }
      else if (in_method) {
        if (t == "[") i = consume_attr_block(toks, i, facts)
        else if (FACT_MARKER_WORDS(t) && i + 1 < n && toks(i + 1) == ":") {
          var j = i + 2
          var stop = false
          while (j < n && !stop) {
            val tj = toks(j)
            if (FACT_MARKER_WORDS(tj)) stop = true
            else if (CLOSING_KEYWORDS(tj) || tj == ")" || tj == "]" || tj == "|" ||
              tj == ",") stop = true
            else if (tj == "[") j = consume_attr_block(toks, j, facts)
            else {
              if (looks_like_fact(tj)) facts += tj
              j += 1
            }
          }
          i = j
        }
        else if (STRUCTURAL(t)) {
          if (t == "(" || t == ")" || t == "|" || t == ",") cur_method = null
          i += 1
        }
        else if (cur_method == null) { cur_method = t; i += 1 }
        else {
          if (RULE_METHODS(cur_method)) { if (looks_like_fact(t)) facts += t }
          else if (TERM_ARG_METHODS(cur_method)) ()
          else covered = false
          i += 1
        }
      }
      else i += 1
    }
    (facts.toSet, covered)
  }

  private val WORD_RE: Pattern = Py.compile("""[\w']+""")

  /* Which of `names` this line genuinely USES.  `names` are declared entries
     whose spelling is also a proof method or attribute (`lemma foo` where `foo`
     is an Eisbach method; `definition simp`).  A position-blind scan cannot tell
     `by simp` from a use of such an entry, which is why these names were once
     dropped from the graph altogether — at the cost of every real citation of
     them.  Position settles it: a mention counts when it is an explicit fact
     citation, or when it sits inside a quoted proposition or cartouche, where
     the token is a constant or statement text and never a method invocation. */
  def shadowed_uses_on_line(line: String, names: Set[String],
    derived: Boolean = false
  ): Set[String] = {
    val (cited0, _) = cited_facts_on_line(line)
    val cited =
      if (derived) cited0 | names.filter(n => cited0(n + "_def") || cited0(n + "_defs"))
      else cited0
    val used = names & cited
    val rest = names -- used
    if (rest.nonEmpty && (line.contains("\"") || line.contains("""\<open>"""))) {
      val terms = new StringBuilder
      val m = PROP_TEXT_RE.matcher(line)
      while (m.find()) {
        val g = if (m.group(1) != null) m.group(1) else m.group(2)
        if (g != null) { if (terms.nonEmpty) terms += ' '; terms ++= g }
      }
      if (terms.nonEmpty) {
        val words = mutable.Set.empty[String]
        val wm = WORD_RE.matcher(terms.toString)
        while (wm.find()) words += wm.group()
        used | rest.filter(words)
      }
      else used
    }
    else used
  }


  /* ------------------------------------------------------------------ */
  /* the call graph                                                     */
  /* ------------------------------------------------------------------ */

  final case class Call_Graph(
    callers: Map[String, Set[String]],
    callees: Map[String, Set[String]],
    all_names: Set[String]
  )

  val citable_tags: Set[String] = Set("LEMMA", "THEOREM", "FUN", "DEF", "ABBREV")

  /* Doc antiquotations are stripped so a name cited only in rendered
     documentation is not counted as a proof-body call. */
  private val ANTIQ_RE: Pattern =
    Py.compile("""@\{(?:text|thm|term|const)\s+["']?\w+["']?\}""")
  /* The old per-name search matched a name wherever it sat between non-`[\w']`
     characters.  `\` (the start of a `\<…>` symbol) is itself non-`[\w']`, so a
     name can match two ways and BOTH have to be extracted to reproduce every
     edge without inventing any: `SYM_RE` for maximal runs that include symbol
     tokens (`merge_rt_F\<^sub>m` as one token), `WORD_RE` for maximal `[\w']`
     runs (so a bare name abutting a symbol is still found).  A name carrying
     other non-identifier characters is written double-quoted at the use site,
     so whole quoted spellings are looked up too. */
  private val SYM_RE: Pattern = Py.compile(s"${Entries.ISA_WORD_CHAR}+")
  private val QUOTED_RE: Pattern = Py.compile("\"([^\"]+)\"")
  /* The LEXICAL symbol atom, for blanking those tokens before the `[\w']+`
     pass.  A `\<...>` token's body is that SYMBOL's name and never a fact's,
     but `[\w']+` reaches straight into it: `\<lambda>` yields `lambda`,
     `\<le>` yields `le`, `\<^sub>` yields `sub` — and the AFP declares 7
     entries named `lambda`, 37 named `le` and 27 named `sub`, so every
     lambda written anywhere in the corpus was recorded as a citation of all
     seven [symbol-body-tokens].  Blanking loses nothing the word pass exists
     for: `iso_transaction` in `iso_transaction\<^sub>h` is still a maximal
     run, and the symbolic spelling is `SYM_RE`'s job — which is why only the
     word pass reads the blanked line and `SYM_RE` / `QUOTED_RE` keep the raw
     one. */
  private val SYM_TOKEN_RE: Pattern = Py.compile(Entries.ISA_SYMBOL)

  private def add_matches(pat: Pattern, s: String, group: Int,
    names: Set[String], into: mutable.Set[String]
  ): Unit = {
    val m = pat.matcher(s)
    while (m.find()) {
      val tok = m.group(group)
      if (tok != null && names(tok)) into += tok
    }
  }

  def build_call_graph(sections: List[Theory_Section],
    drop_upto: Int = DROP_NAMES_UPTO, derived: Boolean = false,
    reach: String = Reach.DEFAULT_MODE
  ): Call_Graph = {
    /* 1. Candidate names.  A name too short to tell from a term variable, or a
          bare numeral, is not a citable fact, so the universal variable `x`
          mints no edges.  A name that is ALSO a method / attribute / keyword is
          admitted, but into `shadowed`: its mentions are checked positionally
          below.  Dropping it outright stopped `by simp` minting edges to a
          `definition simp` — and erased every genuine citation of any entry
          whose name collides with the bound table. */
    /* Import visibility, under `--reach closure`: the closure is shared
       corpus-wide (`Reach.closure` memoises it), and `declared_at` is built in
       THIS pass rather than a second one — the loop that mints the name
       universe already knows which theory each name came from. */
    val closure = if (reach == "closure") Reach.closure(sections) else null
    val declared_at = mutable.HashMap.empty[String, List[Int]]

    val name_set = mutable.LinkedHashSet.empty[String]
    val shadowed = mutable.LinkedHashSet.empty[String]
    for (sec <- sections) {
      val tid = if (closure != null) closure.id(sec.theory) else -1
      for (e <- sec.entries) {
        /* A DECLARATION of the name, for the visibility question, is an entry
           of ANY tag [citation-reach].  The graph's NODES are the citable tags
           — a `locale rev` is not a fact anything can cite — but the question
           here is the other one: can a theory that declares `comp` as a TYPE
           see the `comp` this line names?  It plainly can, and scoping the
           declared set to the citable tags dropped `COMP -> comp` on
           Category3 and `rerr -> sqn` on AODV, where the only visible
           same-name entry is a TYPE.  The filter may only remove what the
           citing theory positively CANNOT see, and a tag is no evidence about
           that.  (The `"?"` anchor name goes in too, harmlessly: it is never a
           candidate.) */
        if (closure != null) {
          val seen = declared_at.getOrElse(e.name, Nil)
          if (!seen.contains(tid)) declared_at(e.name) = tid :: seen
        }
        if (citable_tags(e.tag) && e.name != "?" &&
          e.name.codePointCount(0, e.name.length) > drop_upto && !is_all_digits(e.name)) {
          name_set += e.name
          if (Namespace.non_citation(e.name)) shadowed += e.name
        }
      }
    }
    val names: Set[String] = name_set.toSet

    /* 1b. Derived-fact spellings.  Isabelle mints `foo_def` from `definition
           foo`, and citing it IS a use of `foo` — often the only one.  The
           dotted families (`foo.simps`) need no help: the `[\w']+` tokeniser
           splits them, leaving a bare `foo`.  The underscore family does not
           split, so map it back explicitly.  An entry genuinely named `foo_def`
           keeps its own identity. */
    val derived_base = mutable.LinkedHashMap.empty[String, String]
    if (derived)
      for (n <- names; suffix <- List("_def", "_defs")) {
        val spelling = n + suffix
        if (!names(spelling)) derived_base(spelling) = n
      }
    val derived_keys: Set[String] = derived_base.keySet.toSet

    val def_sites = build_def_sites(sections, Some(names))
    val text_ranges = noise_ranges(sections)
    val line_index = build_line_index(sections)

    val callers = mutable.LinkedHashMap.empty[String, mutable.Set[String]]
    for (n <- name_set) callers(n) = mutable.LinkedHashSet.empty[String]
    val callees = mutable.LinkedHashMap.empty[String, mutable.Set[String]]

    for (sec <- sections) {
      /* The redacted view, not the raw source: a comment, an `\<^cancel>`
         region or an inline ML body sharing its line with live proof text is
         blanked in place, so `by simp (* see foo *)` stops citing `foo`.  The
         redaction preserves every line and column, so the mask below and the
         1-indexed arithmetic still address the same characters. */
      val lines = sec.live_source
      val t_ranges = text_ranges.getOrElse(sec.path, Nil)
      val d_map = def_sites.getOrElse(sec.path, Map.empty[String, List[(Int, Int)]])
      val idx = line_index.getOrElse(sec.path, Array.empty[(Int, Int, Entry)])
      val text_mask = Entries.line_mask(lines.length, t_ranges)
      /* What THIS theory can see: its import closure and itself.  Bound once
         per section — the row is a bit test per candidate name. */
      val visible: Int => Boolean = if (closure != null) closure.visible_from(sec.theory) else null
      var line_no = 1
      val cand = mutable.LinkedHashSet.empty[String]
      while (line_no <= lines.length) {
        if (!text_mask(line_no)) {
          val line = lines(line_no - 1)
          val stripped = if (line.contains("@{")) ANTIQ_RE.matcher(line).replaceAll("") else line
          /* The word pass reads the SYMBOL-BLANKED line, not the raw one
             [symbol-body-tokens].  Only lines that carry a `\<` pay the
             substitution, and the derived-key pass reads the same string —
             the two are one tokenisation of the same line, so a spelling one
             of them can see and the other cannot would be a bug. */
          val worded =
            if (stripped.contains("""\<""")) SYM_TOKEN_RE.matcher(stripped).replaceAll(" ")
            else stripped
          cand.clear()
          add_matches(WORD_RE, worded, 0, names, cand)
          if (derived_base.nonEmpty) {
            val dv = mutable.LinkedHashSet.empty[String]
            add_matches(WORD_RE, worded, 0, derived_keys, dv)
            for (d <- dv) cand += derived_base(d)
          }
          if (stripped.contains("""\<""")) add_matches(SYM_RE, stripped, 0, names, cand)
          if (stripped.contains("\"")) add_matches(QUOTED_RE, stripped, 1, names, cand)
          /* Import visibility, BEFORE the positional re-read below: a name this
             theory cannot see is not a citation of it whatever position it sits
             in, and dropping it here often empties `cand` and saves the
             re-read entirely. */
          if (visible != null && cand.nonEmpty)
            cand.filterInPlace(n => declared_at.getOrElse(n, Nil).exists(visible))
          if (cand.nonEmpty) {
            /* A candidate whose name is also a method or attribute has to earn
               its edge positionally.  Guarded, so an ordinary line pays one set
               intersection rather than the re-read. */
            var live = true
            if (shadowed.nonEmpty) {
              val sh = cand.filter(shadowed).toSet
              if (sh.nonEmpty) {
                val kept = shadowed_uses_on_line(line, sh, derived)
                cand --= sh
                cand ++= kept
                if (cand.isEmpty) live = false
              }
            }
            if (live) {
              val caller_entry = entry_at_line(idx, line_no)
              if (!caller_entry.exists(_.name == "?")) {
                /* A citation outside every indexed entry is still a real use:
                   the span-bounding outer commands (`instance`, `lemmas`,
                   `declare`, `export_code`) cite facts but declare nothing, so
                   they own no lines.  Dropping their citations makes the cited
                   fact read as unused — an `equal` instance proof is the whole
                   reason its own `equal_*` definition exists.  Attribute them to
                   a synthetic per-theory top-level caller. */
                val caller_name =
                  caller_entry.map(_.name).getOrElse(sec.theory + ":<toplevel>")
                for (name <- cand) {
                  val d_ranges = d_map.getOrElse(name, Nil)
                  if (!d_ranges.exists(r => r._1 <= line_no && line_no <= r._2)) {
                    callers(name) += caller_name
                    callees.getOrElseUpdate(caller_name,
                      mutable.LinkedHashSet.empty[String]) += name
                  }
                }
              }
            }
          }
        }
        line_no += 1
      }
    }

    Call_Graph(
      callers = callers.map(kv => kv._1 -> kv._2.toSet).toMap,
      callees = callees.map(kv => kv._1 -> kv._2.toSet).toMap,
      all_names = names)
  }


  /* ------------------------------------------------------------------ */
  /* the method census                                                  */
  /* ------------------------------------------------------------------ */

  /* A proof method is introduced by one of the three pure proof keywords
     `by` / `apply` / `proof`; the method name is the first token after it,
     optionally wrapped in an opening `(`.  Anchoring on the introducer is what
     makes the scan precise: the method namespace is full of short,
     variable-colliding names (`N`, `order`, `field`, `all`), but in INTRODUCER
     position even a one-letter token is unambiguously the method.  Trade-off:
     only the initial method of each introducer is counted, so a
     combinator-chained (`by (induct x) auto`) or line-wrapped method is
     undercounted — never over-counted, which keeps the ranking trustworthy. */
  private val METHOD_INTRO_RE: Pattern =
    Py.compile("""\b(?:by|apply|proof)\b\s*\(?\s*([\w']+)""")

  /* The first proof method named on a line — the token after a `by` / `apply` /
     `proof` introducer, or "" when the line introduces none (`qed`, a bare `.`,
     `proof -`).  Purely POSITIONAL: in introducer position the token IS the
     method, so it is not checked against the bound table.  That check used to be
     here, and it made the shape automation axis the one whose denominator
     depended on configuration — an entry's own Eisbach tactic left the step with
     no method, and since that is `trivial_frac`'s denominator the step did not go
     unclassified, it left the measure.  Dropping it also makes this agree with
     `cited_facts_on_line`, which has always read the first token after
     `by`/`apply` as the method by position; the two scans are meant to partition
     an introducer line between them, and while one consulted a table and the
     other did not, they could not. */
  def leading_method(line: String): String =
    Py.search(METHOD_INTRO_RE, line).map(_.group(1)).getOrElse("")

  final case class Method_Use(theory: String, line_no: Int, owner: Option[Entry],
    text: String)

  /* `(counts, located)`: the tally over every `by` / `apply` / `proof`
     introducer on a LIVE line, and — for `only` — each of that method's uses.

     The tally is POSITIONAL, not table-filtered: whatever sits in introducer
     position is the method.  Requiring table membership made this verb
     under-report every tactic an entry defines for itself, which is the kind a
     reader most needs to find. */
  def scan_methods(sections: List[Theory_Section], only: Option[String]
  ): (List[(String, Int)], List[Method_Use]) = {
    val counts = mutable.LinkedHashMap.empty[String, Int]
    val located = new mutable.ListBuffer[Method_Use]
    val line_index = build_line_index(sections)
    for (sec <- sections) {
      /* Scan the redacted view, report the real one: `by simp (* or apply auto
         *)` must not count `auto`, but the located hit has to show the user
         their actual line. */
      val lines = sec.live_source
      val raw = sec.source
      val noise_mask = Entries.line_mask(lines.length, noise_spans(sec))
      val idx = line_index.getOrElse(sec.path, Array.empty[(Int, Int, Entry)])
      var line_no = 1
      while (line_no <= lines.length) {
        val line = lines(line_no - 1)
        /* The introducer regex requires one of these whole words, so its letters
           must be present — a cheap necessary condition that skips the regex on
           the many lines holding no proof introducer at all. */
        if (!noise_mask(line_no) &&
          (line.contains("by") || line.contains("apply") || line.contains("proof"))) {
          var hit_only = false
          val m = METHOD_INTRO_RE.matcher(line)
          while (m.find()) {
            val tok = m.group(1)
            counts(tok) = counts.getOrElse(tok, 0) + 1
            if (only.contains(tok)) hit_only = true
          }
          if (hit_only)
            located += Method_Use(sec.theory, line_no, entry_at_line(idx, line_no),
              Py.rstrip(raw(line_no - 1)))
        }
        line_no += 1
      }
    }
    (counts.toList, located.toList)
  }


  /* ------------------------------------------------------------------ */
  /* breadth-first closure                                              */
  /* ------------------------------------------------------------------ */

  /* Shortest-path depths from `seeds` over a graph given as a neighbour
     callback.  The seeds sit at `seed_depth`, made explicit rather than baked
     in because the two families disagree about it: the entry-level closures
     (`callers -r` / `callees -r`) seed at 0, while the import graph seeds at -1
     so a seed's DIRECT neighbours come out at depth 0 ("direct").

     The callback — rather than a prebuilt map — is what lets one walk serve
     both a stored adjacency and a lazily resolved one (forward imports, whose
     resolver records out-of-project edges as a side effect).
     Level-synchronised with a visited guard, so it is safe on a cyclic graph
     and yields true shortest-path depth. */
  def bfs_depths(neighbors: String => Iterable[String], seeds: Iterable[String],
    seed_depth: Int = 0
  ): Map[String, Int] = {
    val depths = mutable.LinkedHashMap.empty[String, Int]
    var frontier = seeds.toList
    var depth = seed_depth
    while (frontier.nonEmpty) {
      val next = new mutable.ListBuffer[String]
      for (node <- frontier if !depths.contains(node)) {
        depths(node) = depth
        next ++= neighbors(node)
      }
      frontier = next.toList
      depth += 1
    }
    depths.toMap
  }
}
