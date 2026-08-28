/*  Title:      query_base/src/sites.scala

Two "sites in a syntactic role naming a constant" scans, the first capabilities
with no counterpart in the Python reference tool:

  instantiations of a locale / class -- `instantiation` blocks, `instance`
    arities, `interpretation` / `global_interpretation` / `interpret`, and
    `sublocale`;
  code equations of a constant -- declarations carrying a `code` attribute,
    the `declare` / `lemmas` sites that attach one to an existing fact, and the
    implicit default equations of the constant's own `definition` / `fun`.

Both are the same shape as `Usage.find_callers` and obey the same doctrine: the
LIVE view only, so a commented-out or `\<^cancel>`ed decoy is not a site, and a
command word is recognised where a COMMAND can start rather than wherever the
word appears.

What this is NOT: `print_codesetup` / `code_thms` / `print_interps`.  Those run
inside a prover and report the PROCESSED setup -- after preprocessing, after
`[code del]` has taken effect, and including everything an imported session
declared.  This reports the DECLARED SOURCE SITES in the project being read,
which is the complement: it needs no heap and no build, and it sees the sites a
processed view has already folded away.

Both scans need the grammar written down, so the rails from Isabelle's own
`Doc/Isar_Ref` are quoted at each parser.
*/

package isabelle.query


import isabelle.*

import java.util.regex.Pattern

import scala.collection.mutable


object Sites {
  /* ------------------------------------------------------------------ */
  /* what a site is                                                     */
  /* ------------------------------------------------------------------ */

  /* What a row says it IS, when the source does not say.  `?` is the engine's
     own placeholder for a declaration that carries no name (`Entries`, and
     SCANNING.md: "an opener that carries no name is left unnamed rather than
     guessed at"), so a site with nothing written to call it by is spelled the
     same way rather than blank or invented. */
  val UNNAMED: String = "?"

  /* `kind` is the syntactic role -- what makes this line a site.
     `name` is what the row is called: the qualifier / type constructor /
     providing fact the SOURCE writes at that site, in the column `callers`
     puts its owning entry in, so the two verbs read like the rest of the tool.
     `sorts` is the type or sort text written THERE and nowhere else -- shown
     only under `--sorts`, because for most rows there is none and a column of
     blanks is worse than no column. */
  final case class Site(theory: String, line: Int, kind: String, text: String,
    name: String = UNNAMED, sorts: String = ""
  ) {
    /* The name cell.  `--sorts` re-spells it as the source does, `c :: T`;
       with nothing written there it is the bare name, never an inferred type. */
    def label(with_sorts: Boolean): String =
      if (with_sorts && sorts.nonEmpty) name + " :: " + sorts else name
  }

  /* Which declarations may be the subject of each verb.  The CLI refuses
     anything else (exit 1), and the jEdit menu offers the item only for these
     -- one predicate, two front doors. */
  val locale_tags: Set[String] = Set("LOCALE", "CLASS")
  val constant_tags: Set[String] =
    Set("DEF", "FUN", "ABBREV", "IND", "INDSET", "DATATYPE", "RECORD", "AXIOM")

  /* Which declaration commands register DEFAULT code equations, with no
     attribute written.  `definition` registers its defining equation and
     `fun` / `primrec` / `function` register their `.simps` / `.code` with the
     code generator's plugin.  `datatype` registers CONSTRUCTORS
     (`code_datatype`), not equations; `inductive` needs an explicit
     `code_pred`; an `abbreviation` is unfolded before code generation ever
     sees it -- none of the three has default equations to report. */
  val default_code_tags: Set[String] = Set("DEF", "FUN")


  /* ------------------------------------------------------------------ */
  /* resolving the subject                                              */
  /* ------------------------------------------------------------------ */

  /* `how` is non-empty when the name is one Isabelle MINTED rather than one
     the author wrote as a declaration name -- a datatype constructor, a
     `shows` conjunct -- resolved through the engine's own
     `Commands.resolve_binding`.  The SUBJECT stays the typed name: a code
     equation is about the constructor `Cons`, not about the `datatype list`
     that binds it. */
  final case class Subject(name: String, tag: String, theory: String, entry: Entry,
    how: String = "")

  /* EVERY entry of that name, not the first: a project routinely declares the
     same word twice (`rev` is a `primrec` in `List` and a locale-local LEMMA
     in `Groups_List`), and taking whichever came first turned a perfectly good
     subject into "is a LEMMA, not a constant".  The right-kinded declaration
     wins; the wrong-kinded one is only what the diagnostic names. */
  private def find_entries(sections: List[Theory_Section], name: String
  ): List[(String, Entry)] =
    for (sec <- sections; e <- sec.entries if e.name == name) yield (sec.theory, e)

  /* `Right` when the question can be asked, `Left` with the reason when it
     cannot.  A subject the project does not declare is NOT an honest zero: the
     scan would find nothing for a typo and nothing for a locale declared in an
     imported session, and the caller could not tell either from a locale that
     genuinely has no instantiations.  CONTRIBUTING.md, "never return an empty
     success for a question you could not ask". */
  def resolve(sections: List[Theory_Section], name: String, tags: Set[String],
    what: String
  ): Either[String, Subject] = {
    val found = find_entries(sections, name)
    found.find(te => tags(te._2.tag)) match {
      case Some((theory, e)) => Right(Subject(name, e.tag, theory, e))
      case None =>
        /* A bound name -- a constructor, a selector, a mutually declared
           constant -- is a real constant even though it is not an entry. */
        val bound =
          sections.iterator.flatMap(sec =>
            sec.entries.iterator.flatMap(e =>
              e.bindings.iterator.collect {
                case (n, kind) if n == name && tags(e.tag) =>
                  Subject(name, e.tag, sec.theory, e,
                    Commands.binding_kinds.getOrElse(kind, "bound by") + " " + e.name)
              })).nextOption()
        bound.toRight(
          found.headOption match {
            case Some((theory, e)) => s"'$name' is a ${e.tag} in $theory, not $what"
            case None => s"'$name' is not $what declared in this project"
          })
    }
  }


  /* ------------------------------------------------------------------ */
  /* reading a command header                                           */
  /* ------------------------------------------------------------------ */

  /* A command's text, in the two views at once.  Both are joined with the same
     separator from lines of identical length, so an index found in `outer`
     indexes `live` -- which is the whole point of the three views having the
     same shape (`model.scala`).

     `outer` decides structure (a `+` inside a quoted term is not a locale
     expression separator); `live` supplies names, because a QUOTED name --
     `instantiation "fun" :: ...`, `instantiation bool :: "{order_bot, ...}"`
     -- lives exactly where `outer` blanks. */
  final case class Header(start: Int, end: Int, live: String, outer: String)

  /* How many lines of a command header are read.  A locale expression or an
     arity is one or two lines in practice; the cap stops a malformed file from
     turning one site into a whole-theory scan. */
  val HEADER_LINES: Int = 8

  /* Where a header stops: the first outer-syntax token that cannot be part of
     the expression any more.  `where` / `rewrites` / `defines` / `for` end a
     locale expression by the rail; `begin` ends an `instantiation` head; the
     rest are the proof. */
  private val HEADER_STOP_RE: Pattern = Py.compile(
    """(?<![\w'])(where|rewrites|defines|for|begin|by|proof|using|unfolding""" +
      """|apply|done|qed|oops|sorry)(?![\w'])|(?<=\s)\.\.?(?=\s|$)""")

  private def header_at(live: Array[String], outer: Array[String], line: Int,
    lines: Int = HEADER_LINES
  ): Header = {
    val last = (line + lines - 1) min live.length
    val live_buf = new StringBuilder
    val outer_buf = new StringBuilder
    var i = line
    var stop = last
    var go = true
    while (i <= last && go) {
      /* A later line that opens a command of its own is not part of this one.
         Read on the OUTER view: a command word inside a term is not a
         command. */
      if (i > line && starts_command(outer(i - 1))) go = false
      else {
        if (i > line) { live_buf += '\n'; outer_buf += '\n' }
        live_buf ++= live(i - 1)
        outer_buf ++= outer(i - 1)
        stop = i
        i += 1
      }
    }
    val outer_text = outer_buf.toString
    val cut =
      Py.search_from(HEADER_STOP_RE, outer_text, 0) match {
        case Some(m) => m.start
        case None => outer_text.length
      }
    Header(line, stop, live_buf.toString.substring(0, cut), outer_text.substring(0, cut))
  }

  /* The commands that BOUND a header.  Deliberately the engine's own boundary
     table plus the declaration keywords, rather than a second list: one
     grammar, one place it is written down. */
  private val boundary_words: Set[String] =
    Entries.span_boundary_commands ++ Entries.tag_map.keySet ++
      Set("global_interpretation", "interpret", "subclass", "instance",
        "text", "txt", "text_raw", "chapter", "section", "subsection",
        "subsubsection", "paragraph", "subparagraph")

  private def starts_command(outer_line: String): Boolean =
    Py.matches_at_start(Entries.LEADING_CMD_RE, Py.lstrip(outer_line))
      .exists(m => boundary_words(m.group(1)))


  /* ------------------------------------------------------------------ */
  /* reading a name                                                     */
  /* ------------------------------------------------------------------ */

  /* An Isabelle name as written at a USE site: quoted when the word would
     otherwise be reserved (`"fun"`, `"and"`), symbol-bearing
     (`split\<^sub>i_tree`), and -- unlike a declaration name -- possibly
     qualified (`Groups.monoid`).  Exactly `Entries.target_name`'s grammar,
     called through the same two patterns so a name the block scanner reads is
     a name this one reads. */
  private def name_at(live: String, pos: Int): Option[(String, Int)] = {
    if (pos >= live.length) None
    else {
      val rest = live.substring(pos)
      Py.matches_at_start(Entries.QUOTED_NAME_RE, rest) match {
        case Some(m) => Some((m.group(1), pos + m.end))
        case None =>
          Py.matches_at_start(Entries.TARGET_NAME_RE, rest) match {
            case Some(m) =>
              val nm = m.group(0)
              if (Entries.not_a_target_name(nm) ||
                Entries.reserved_name_prefixes.exists(nm.startsWith)) None
              else Some((nm, pos + m.end))
            case None => None
          }
      }
    }
  }

  /* A written name denotes the subject when it is the subject, or a
     QUALIFIED spelling of it (`Groups.monoid` for `monoid`).  Isabelle resolves
     a qualified name to the same locale, so refusing the spelling would drop
     real sites; requiring the last segment to match keeps `foo_monoid` out. */
  def denotes(written: String, subject: String): Boolean =
    written == subject || written.endsWith("." + subject)

  private def skip_space(s: String, from: Int): Int = {
    var i = from
    while (i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\n')) i += 1
    i
  }

  /* Index just past the `)` matching the `(` at `from`, or -1. */
  private def paren_end(outer: String, from: Int): Int = {
    var depth = 0
    var i = from
    while (i < outer.length) {
      val c = outer.charAt(i)
      if (c == '(') depth += 1
      else if (c == ')') { depth -= 1; if (depth == 0) return i + 1 }
      i += 1
    }
    -1
  }


  /* ------------------------------------------------------------------ */
  /* instantiations                                                     */
  /* ------------------------------------------------------------------ */

  /* The commands this verb reads, and the rails they follow
     (`Doc/Isar_Ref/Spec.thy`):

       instantiation (name + and) '::' arity 'begin'
       instance (() | (name + and) '::' arity | name ('<'|'\<subseteq>') name)
       interpretation locale_expr
       interpret locale_expr
       global_interpretation locale_expr definitions?
       sublocale (name ('<'|'\<subseteq>'))? locale_expr definitions?

     `locale_expr` is `(instance + '+') for_fixes`, an instance being
     `(qualifier ':')? name (pos_insts | named_insts) rewrites?`.

     DELIBERATELY EXCLUDED, both directions of the class/locale hierarchy:
     `class D = C + ...`, `locale K = L + ...`, `subclass`, and
     `instance C \<subseteq> D`.  Those EXTEND a locale rather than instantiate
     it -- no type or term is supplied -- and they already show up under
     `callers`, which is where a "who mentions L" question belongs.  Mixing
     them in would make "3 instantiations of monoid" mean two different
     relations at once. */
  private val INST_CMD_RE: Pattern = Py.compile(
    """^(instantiation|instance|interpretation|global_interpretation|interpret""" +
      """|sublocale)(?![\w'])""")

  /* A name as WRITTEN at a use site: like a declaration name, but possibly
     qualified.  Spelled out from `Entries.ISA_MARKUP` rather than reusing the
     compiled `Entries.TARGET_NAME_RE`, because it has to be embedded in longer
     patterns; the grammar is the same one. */
  private val USE_NAME: String =
    s"(?:${Entries.ISA_MARKUP}|[A-Za-z_])(?:${Entries.ISA_MARKUP}|[\\w'.])*"

  /* `sublocale L \<subseteq> M` (and the older `sublocale L < M`): the NAME
     before the arrow is where the interpretation is installed, not what is
     interpreted, so it is stripped before the expression is read.  Both
     spellings are accepted by Isabelle2025-2's rail, and the decoded form is
     accepted too because a buffer handed over from jEdit is decoded text. */
  private val SUBLOCALE_TARGET_RE: Pattern = Py.compile(
    s"""^\\s*(?:$USE_NAME)?\\s*""" +
      """(?:\\<subseteq>|""" + Pattern.quote(Symbol.decode("""\<subseteq>""")) + """|<)\s*""")

  /* Top-level `+` in a locale expression: not inside parentheses, and not
     inside a term (terms are already blanked in the outer view). */
  private def split_plus(outer: String): List[(Int, Int)] = {
    val out = new mutable.ListBuffer[(Int, Int)]
    var depth = 0
    var start = 0
    var i = 0
    while (i < outer.length) {
      val c = outer.charAt(i)
      if (c == '(' || c == '[') depth += 1
      else if (c == ')' || c == ']') depth -= 1
      else if (c == '+' && depth <= 0) { out += ((start, i)); start = i + 1 }
      i += 1
    }
    out += ((start, outer.length))
    out.toList
  }

  /* A qualifier (`add:`, `weak?:`, and the quoted `"and":`) prefixes an
     instance and is not the locale.  Read on the outer view, where a quoted
     qualifier has been blanked to spaces -- so the `:` is still there and the
     name behind it cannot be mistaken for the head.

     It stops AT the colon and does not eat the whitespace after it: in outer a
     quoted locale name IS whitespace, so a trailing `\s*` here walked straight
     past the name in `interpretation q: "open" id` and read `id` instead.  The
     space is skipped on `live`, where the quote is still standing. */
  private val QUALIFIER_RE: Pattern =
    Py.compile("""^\s*(?:[A-Za-z_][\w'.]*)?\s*[?!]?\s*:(?!:)""")

  /* The qualifier as WRITTEN, read back off `live` from the span the pattern
     matched on `outer`.  It is read here and not from the outer view for the
     same reason the locale name is: `interpretation "and": L ..` writes the
     qualifier quoted, and outer blanks exactly that.  The `?` / `!` mandatory-
     ness marker is not part of the name. */
  private def qualifier_name(live: String, from: Int, to: Int): String = {
    var q = Py.strip(live.substring(from min live.length, to min live.length))
    if (q.endsWith(":")) q = Py.rstrip(q.substring(0, q.length - 1))
    if (q.endsWith("?") || q.endsWith("!")) q = Py.rstrip(q.substring(0, q.length - 1))
    name_at(Py.lstrip(q), 0).map(_._1).getOrElse("")
  }

  /* Every instance of this locale expression as (written qualifier, locale).
     The qualifier belongs to the instance, not to the command: in
     `interpretation L1 x + q: L2 y` only the second instance is named, and a
     row that reported `q` for both would be naming the wrong one. */
  def expression_instances(live: String, outer: String): List[(String, String)] = {
    val out = new mutable.ListBuffer[(String, String)]
    for ((a, b) <- split_plus(outer)) {
      val seg_outer = outer.substring(a, b)
      val skip =
        Py.matches_at_start(QUALIFIER_RE, seg_outer).map(_.end).getOrElse(0)
      val qualifier = if (skip == 0) "" else qualifier_name(live, a, a + skip)
      val pos = skip_space(live, a + skip)
      if (pos < b) name_at(live, pos).foreach(nm => out += ((qualifier, nm._1)))
    }
    out.toList
  }

  /* Every locale named at the head of an instance of this expression. */
  def expression_heads(live: String, outer: String): List[String] =
    expression_instances(live, outer).map(_._2)

  /* The classes an arity instantiates: the SORT after `::`, past the argument
     sorts.  `instantiation prod :: (exhaustive, exhaustive) exhaustive`
     instantiates `exhaustive` once -- the sorts in parentheses are CONSTRAINTS
     on the arguments, not instantiations, and reporting them would count a
     constraint as a site.  A sort is a class or a brace-list of them, and both
     are routinely written quoted (`"{order_bot, order_top, linorder}"`), which
     is why the sort is read from `live`. */
  def arity_classes(live: String, outer: String): List[String] = {
    val sep = outer.indexOf("::")
    if (sep < 0) Nil
    else {
      /* Whitespace is skipped on LIVE, never on outer: a quoted sort
         (`:: "{order_bot, order_top, linorder}"`) is blanked in outer, so
         skipping spaces there walks past the very thing being read.  The
         PARENTHESES are matched on outer, where a quoted argument sort cannot
         hide one. */
      var pos = skip_space(live, sep + 2)
      if (pos < outer.length && outer.charAt(pos) == '(') {
        val e = paren_end(outer, pos)
        if (e < 0) return Nil
        pos = skip_space(live, e)
      }
      if (pos >= live.length) Nil
      else {
        val body =
          live.charAt(pos) match {
            case '"' =>
              val e = live.indexOf('"', pos + 1)
              if (e < 0) "" else live.substring(pos + 1, e)
            case '{' =>
              val e = live.indexOf('}', pos + 1)
              if (e < 0) "" else live.substring(pos + 1, e)
            case _ if live.startsWith("""\<open>""", pos) =>
              val e = Entries.balanced_end(live, """\<open>""", """\<close>""", pos)
              if (e < 0) "" else live.substring(pos + 7, e - 8)
            case _ => name_at(live, pos).map(_._1).getOrElse("")
          }
        val inner = Py.strip(body).stripPrefix("{").stripSuffix("}")
        inner.split(",").toList.map(Py.strip).filter(_.nonEmpty)
          .flatMap(s => name_at(s, 0).map(_._1))
      }
    }
  }

  /* An arity as the source writes it, split at the `::`: the type constructor
     being instantiated, and the arity text after it.

     Both halves are VERBATIM (whitespace squashed, because a header may wrap):
     `--sorts` promises the constraints "as they appear in source", so a quoted
     brace sort stays quoted and nothing is normalised into a form Isabelle
     never saw.  The constructor is unquoted when it is a single quoted name
     (`instantiation "fun" :: ...` instantiates `fun`), and otherwise left
     alone -- `nat and int :: mynull` names two, and picking one would be a
     guess. */
  private def squash(s: String): String = Py.strip(s.replaceAll("\\s+", " "))

  /* A command may carry a document marker before its arguments
     (`instantiation\<^marker>\<open>tag unimportant\<close> vec :: ...`).  It is
     part of the COMMAND, not of what follows, and the outer view blanks only
     the cartouche -- so the `\<^marker>` token is still standing where the
     type constructor is read from.  Measured on live and cut from both views,
     which is sound because the views share a length. */
  private val MARKER_RE: Pattern = Py.compile("""^\s*\\<\^marker>\s*""")

  private def marker_end(live: String): Int =
    Py.matches_at_start(MARKER_RE, live) match {
      case None => 0
      case Some(m) =>
        if (live.startsWith("""\<open>""", m.end)) {
          val e = Entries.balanced_end(live, """\<open>""", """\<close>""", m.end)
          if (e < 0) m.end else e
        }
        else m.end
    }

  def arity_parts(live: String, outer: String): (String, String) = {
    val sep = outer.indexOf("::")
    if (sep < 0) ("", "")
    else {
      val raw = squash(live.substring(0, sep min live.length))
      val ctor =
        name_at(raw, 0) match {
          case Some((nm, e)) if Py.strip(raw.substring(e)).isEmpty => nm
          case _ => raw
        }
      (ctor, squash(live.substring((sep + 2) min live.length)))
    }
  }

  /* The `L` of `sublocale L \<subseteq> M`: where the interpretation is
     INSTALLED, which is the same thing the enclosing `context L begin` says
     when the other spelling is used.  Read on live, because the target may be
     a quoted name; the arrow itself is found on outer. */
  private def sublocale_target(live: String, outer: String): String =
    Py.matches_at_start(SUBLOCALE_TARGET_RE, outer) match {
      case None => ""
      case Some(_) => name_at(live, skip_space(live, 0)).map(_._1).getOrElse("")
    }

  /* Every instantiation site of `name` in the project, in section-load order
     (the build's own order, so the listing is stable between runs). */
  def find_instantiations(sections: List[Theory_Section], name: String): List[Site] = {
    val out = new mutable.ListBuffer[Site]
    for (sec <- sections) {
      val live = sec.live_source
      val outer = sec.outer_source
      val raw = sec.source

      /* The chain of enclosing named target blocks, per line -- what
         `context L begin ... sublocale M ... end` writes that
         `sublocale L \<subseteq> M` writes inline.  Built at most once per
         theory, and only for a theory that actually has a site: it is another
         pass over the source, and most theories have none. */
      var stacks: Array[List[(String, String)]] = null
      def enclosing_name(line: Int): String =
        Commands.enclosing_entry(sec, line) match {
          case Some(e) if e.name.nonEmpty && e.name != UNNAMED => e.name
          case _ =>
            if (stacks == null) stacks = Entries.block_stacks(outer, live)
            val idx = line - 1
            if (idx >= 0 && idx < stacks.length)
              stacks(idx).lastOption.map(_._2).getOrElse("")
            else ""
        }

      var i = 1
      while (i <= outer.length) {
        val stripped = Py.lstrip(outer(i - 1))
        Py.matches_at_start(INST_CMD_RE, stripped).foreach { m =>
          val command = m.group(1)
          val head = header_at(live, outer, i)
          /* Past the command keyword, in both views at once: the keyword is
             matched on outer but the head is read from live. */
          val at0 = outer(i - 1).length - stripped.length + m.end
          val at = at0 + marker_end(head.live.substring(at0 min head.live.length))
          val body_live = head.live.substring(at min head.live.length)
          val body_outer = head.outer.substring(at min head.outer.length)
          val arity = command == "instantiation" || command == "instance"
          /* Bare `instance` closes an `instantiation` block (already counted
             at its head) and `instance C \<subseteq> D` is a class inclusion,
             not an instantiation: neither has a `::`, so `arity_classes`
             answers nothing for both. */
          if (arity) {
            if (arity_classes(body_live, body_outer).exists(denotes(_, name))) {
              val (ctor, sorts) = arity_parts(body_live, body_outer)
              out += Site(sec.theory, i, command, Py.rstrip(raw(i - 1)),
                if (ctor.nonEmpty) ctor else UNNAMED, sorts)
            }
          }
          else {
            val target = if (command == "sublocale") sublocale_target(body_live, body_outer) else ""
            val cut =
              if (command == "sublocale")
                Py.matches_at_start(SUBLOCALE_TARGET_RE, body_outer).map(_.end).getOrElse(0)
              else 0
            val instances =
              expression_instances(body_live.substring(cut min body_live.length),
                body_outer.substring(cut min body_outer.length))
            val matched = instances.filter(qh => denotes(qh._2, name))
            if (matched.nonEmpty) {
              /* Written first, derived second: the qualifier the author put on
                 THIS instance, else the target `sublocale L \<subseteq> M`
                 names, else whatever context the site sits in -- the enclosing
                 entry for an `interpret` in a proof, or the enclosing locale
                 for a bare `interpretation` in a block.  A site with none of
                 those is left `?` rather than given the locale's own name,
                 which would make every row say the same word twice. */
              val written = matched.map(_._1).find(_.nonEmpty).getOrElse("")
              val label =
                if (written.nonEmpty) written
                else if (target.nonEmpty) target
                else {
                  val ctx = enclosing_name(i)
                  if (ctx.nonEmpty) ctx else UNNAMED
                }
              out += Site(sec.theory, i, command, Py.rstrip(raw(i - 1)), label)
            }
          }
        }
        i += 1
      }
    }
    out.toList
  }


  /* ------------------------------------------------------------------ */
  /* code equations                                                     */
  /* ------------------------------------------------------------------ */

  /* THE ATTRIBUTE SET, and where the line is drawn.

     Isabelle's code-equation store is `Pure/Isar/code.ML`, whose single
     `Attrib.setup` binds `code` with this parser:

       [code]            add a (possibly abstract) equation
       [code equation]   add an equation
       [code prepend]    add an equation, in front
       [code nbe]        add an equation for normalisation by evaluation
       [code abstract]   add an abstract equation
       [code abstype]    add an abstype certificate
       [code del]        RETRACT an equation
       [code drop: cs]   drop the implementations of constants cs
       [code abort]      declare a constant aborting

     Everything else spelled `code_*` is a DIFFERENT store: `code_unfold`,
     `code_post` and `code_abbrev` are `Attrib.setup`s in
     `Tools/Code/code_preproc.ML` -- the code generator's PREPROCESSOR simpsets,
     which rewrite a term before equations are looked up and are not equations
     of any constant; `code_pred_intro` / `code_pred_inline` belong to the
     predicate compiler.  Reporting them under "code equations of c" would
     answer a different question with the same words, so they are excluded, and
     the token boundary after `code` is what keeps `code_unfold` out.

     `del` / `drop` / `abort` ARE reported, marked as such: a listing that
     showed the equations and hid the retraction would be the more misleading
     of the two, since the retraction is the reason the equation is not in
     force. */
  val equation_attrs: Set[String] =
    Set("", "equation", "prepend", "nbe", "abstract", "abstype")
  val retract_attrs: Set[String] = Set("del", "drop", "abort")

  private val CODE_ATTR_RE: Pattern =
    Py.compile("""^code(?![\w'])\s*([A-Za-z_]*)""")

  /* An attribute occurrence: its rendered spelling for the kind column, and
     whether it declares or retracts. */
  final case class Code_Attr(spelling: String, arg: String, config: Boolean,
    from: Int, to: Int)

  /* Every `code`-family attribute in a command header, with the extent of the
     bracket group it sits in.  Bracket groups are found on the OUTER view, so
     a `[` inside a term opens nothing; `[[...]]` is the CONFIG form
     (`declare [[code drop: f]]`), whose arguments name constants directly. */
  def code_attrs(live: String, outer: String): List[Code_Attr] = {
    val out = new mutable.ListBuffer[Code_Attr]
    var i = 0
    while (i < outer.length) {
      if (outer.charAt(i) == '[') {
        val config = i + 1 < outer.length && outer.charAt(i + 1) == '['
        val start = if (config) i + 2 else i + 1
        var depth = 1
        var j = start
        var end = -1
        while (j < outer.length && end < 0) {
          if (outer.charAt(j) == '[') depth += 1
          else if (outer.charAt(j) == ']') { depth -= 1; if (depth == 0) end = j }
          j += 1
        }
        val stop = if (end < 0) outer.length else end
        /* Split the group on top-level commas: one attribute each. */
        var seg = start
        var k = start
        var d = 0
        while (k <= stop) {
          val c = if (k < stop) outer.charAt(k) else ','
          if (c == '(' || c == '[') d += 1
          else if (c == ')' || c == ']') d -= 1
          if (c == ',' && d <= 0) {
            val a = skip_space(live, seg) min live.length
            val b = k min live.length
            if (a < b)
              Py.matches_at_start(CODE_ATTR_RE, live.substring(a, b)).foreach { m =>
                val arg = m.group(1)
                if (equation_attrs(arg) || retract_attrs(arg))
                  out += Code_Attr(Py.strip("code " + arg), arg, config, a, b)
              }
            seg = k + 1
          }
          k += 1
        }
        i = (if (end < 0) outer.length else end) + 1
      }
      else i += 1
    }
    out.toList
  }

  /* The constants named by a `[[code drop: c1 c2]]` / `[code abort: c]`
     argument list.  A constant there may carry a type ascription and be
     quoted (`"open :: real set \<Rightarrow> bool"`), so the leading name is
     taken and the ascription dropped. */
  def dropped_constants(live: String, attr: Code_Attr): List[String] = {
    val body = live.substring(attr.from min live.length, attr.to min live.length)
    val colon = body.indexOf(':')
    if (colon < 0) Nil
    else {
      val out = new mutable.ListBuffer[String]
      var pos = skip_space(body, colon + 1)
      var go = true
      while (go && pos < body.length) {
        name_at(body, pos) match {
          case Some((nm, next)) =>
            /* A quoted argument is a constant WITH its type
               (`"open :: real set \<Rightarrow> bool"`); the constant is the
               leading name, read by the same reader one level down. */
            out += name_at(Py.lstrip(nm), 0).map(_._1).getOrElse(nm)
            /* Skip the rest of this argument -- a type ascription, or the
               remainder of a quoted term. */
            var p = next
            while (p < body.length && !Py.is_space(body.charAt(p))) p += 1
            pos = skip_space(body, p)
          case None => go = false
        }
      }
      out.toList
    }
  }


  /* --- the head of an equation --- */

  /* THE ATTRIBUTION RULE, and its approximation.

     A code equation belongs to the constant at the HEAD of its left-hand side,
     not to every constant it mentions: `lemma [code]: "f x = g x + h x"` is an
     equation of `f`, and reporting it under `g` and `h` would make the verb
     useless on any constant that appears in a right-hand side.  So the rule
     implemented is the head rule, applied to the source text:

       * take the propositions of the statement (the quoted terms and
         cartouches; only those after `shows`, when there is a `shows`);
       * drop the premises -- everything up to the last top-level `\<Longrightarrow>`
         -- since a conditional code equation's conclusion is the equation;
       * take the left of the first top-level equality (`=`, `\<equiv>`, `==`,
         `\<longleftrightarrow>`);
       * the heads are the identifiers in HEAD POSITION there: the first token,
         and the first token after each `(`.

     The second half of that last rule is what makes `[code abstract]` work:
     an abstract equation reads `Rep_T (f x) = ...`, whose outermost head is
     the projection and whose subject is `f`.  It over-reports by exactly one
     case -- `f (g x) y = ...` names `g` too -- which is the direction this
     repo's other approximations lean (SCANNING.md, "an unlisted method may add
     a spurious citation, never remove a true one").

     What it is NOT is "the constant is mentioned in the statement".  That was
     the cheaper option and it is wrong here in a way it is not wrong for
     `callers`: a code equation is a directed fact about one constant. */
  private val PROP_RE: Pattern = Py.compile("""\"([^\"]*)\"|\\<open>(.*?)\\<close>""")
  private val SHOWS_RE: Pattern = Py.compile("""(?<![\w'])shows(?![\w'])""")
  private val BINDER_RE: Pattern =
    Py.compile("""^\s*\\<(?:And|forall)>[^.]*\.\s*""")
  private val META_IMP: List[String] = List("""\<Longrightarrow>""", "==>")
  private val HEAD_TOKEN_RE: Pattern = Py.compile(s"^(${Entries.ISA_NAME})")

  /* An equality at top level (parenthesis depth 0) in a proposition. `=` is
     rejected where it is part of a longer operator (`==>`, `\<le>`-style
     spellings do not use it, but `~=` and `<=` do occur in old ASCII). */
  private def top_equality(prop: String): Int = {
    var depth = 0
    var i = 0
    while (i < prop.length) {
      val c = prop.charAt(i)
      if (c == '(') depth += 1
      else if (c == ')') depth -= 1
      else if (depth == 0) {
        if (prop.startsWith("""\<equiv>""", i)) return i
        if (prop.startsWith("""\<longleftrightarrow>""", i)) return i
        if (c == '=') {
          val prev = if (i > 0) prop.charAt(i - 1) else ' '
          val next = if (i + 1 < prop.length) prop.charAt(i + 1) else ' '
          if (!"<>!~:=+-*/^".contains(prev) && next != '=' && next != '>') return i
        }
      }
      i += 1
    }
    -1
  }

  private def strip_premises(prop0: String): String = {
    var prop = prop0
    for (imp <- META_IMP) {
      var depth = 0
      var i = 0
      var cut = -1
      while (i < prop.length) {
        val c = prop.charAt(i)
        if (c == '(') depth += 1
        else if (c == ')') depth -= 1
        else if (depth == 0 && prop.startsWith(imp, i)) cut = i + imp.length
        i += 1
      }
      if (cut >= 0) prop = prop.substring(cut)
    }
    Py.matches_at_start(BINDER_RE, prop) match {
      case Some(m) => prop.substring(m.end)
      case None => prop
    }
  }

  private def head_identifiers(lhs: String): List[String] = {
    val out = new mutable.ListBuffer[String]
    def take(at: Int): Unit = {
      val pos = skip_space(lhs, at)
      if (pos < lhs.length)
        Py.matches_at_start(HEAD_TOKEN_RE, lhs.substring(pos)).foreach(m => out += m.group(1))
    }
    take(0)
    var i = 0
    while (i < lhs.length) {
      if (lhs.charAt(i) == '(') take(i + 1)
      i += 1
    }
    out.toList
  }

  def equation_heads(statement: String): List[String] = {
    val body = Py.search(SHOWS_RE, statement) match {
      case Some(m) => statement.substring(m.end)
      case None => statement
    }
    val out = new mutable.ListBuffer[String]
    val m = PROP_RE.matcher(body)
    while (m.find()) {
      val prop = if (m.group(1) != null) m.group(1) else m.group(2)
      if (prop != null && prop.nonEmpty) {
        val concl = strip_premises(prop)
        val eq = top_equality(concl)
        out ++= head_identifiers(if (eq >= 0) concl.substring(0, eq) else concl)
      }
    }
    out.toList.distinct
  }


  /* --- fact names, and the constant behind one --- */

  private val FACT_NAME_RE: Pattern =
    Py.compile(s"(?<![\\w'.])(${Entries.ISA_NAME})(?:\\.(?:${Entries.ISA_NAME}))*")

  /* The fact names a `declare` / `lemmas` command cites, outside its attribute
     brackets.  `lemmas foo [code] = bar baz` names `foo`, `bar` and `baz`, and
     all three are read: which of them carries the equation is a question about
     the theorem, and the site is reported whichever way round it is written. */
  def cited_fact_names(outer: String, from: Int = 0): List[String] = {
    val masked = new StringBuilder(outer)
    var depth = 0
    var i = 0
    while (i < from && i < masked.length) { masked.setCharAt(i, ' '); i += 1 }
    while (i < masked.length) {
      val c = masked.charAt(i)
      if (c == '[') { depth += 1; masked.setCharAt(i, ' ') }
      else if (c == ']') { if (depth > 0) depth -= 1; masked.setCharAt(i, ' ') }
      else if (depth > 0) masked.setCharAt(i, ' ')
      i += 1
    }
    val out = new mutable.ListBuffer[String]
    val m = FACT_NAME_RE.matcher(masked.toString)
    while (m.find()) out += m.group(0)
    out.toList
  }

  /* Derived spellings Isabelle mints from a constant's own declaration; citing
     one of them IS citing the constant.  The dotted family is open-ended
     (`f.simps`, `f.code`, `f.psimps`, `f.induct`), so the test is the prefix
     rather than a list of suffixes. */
  private val underscore_suffixes: List[String] = List("_def", "_defs", "_code")

  private def spells(token: String, subject: String): Boolean =
    token == subject || token.startsWith(subject + ".") ||
      underscore_suffixes.exists(s => token == subject + s)


  /* --- the signature a declaration writes --- */

  /* `c :: T` in a declaration header, and NOTHING inferred: `--sorts` reports
     what the author typed, so a `definition` that leaves the type to Isabelle
     shows none.  Saying so is the point -- a type this tool made up would be
     the one thing in the output nobody could check against the source.

     The `::` must be visible in OUTER, which is what keeps the `::` of
     `lemma foo: "f :: nat \<Rightarrow> bool"` -- inside a term -- from being
     read as the declaration's own; the NAME in front of it is read from LIVE,
     where a quoted declaration name (`definition "open" :: ...`) still
     stands. */
  private val SIG_RE: Pattern =
    Py.compile("""(?:"([^"]+)"|((?:""" + Entries.ISA_MARKUP + """|[A-Za-z_])(?:""" +
      Entries.ISA_MARKUP + """|[\w'.])*))\s*::""")

  private def type_text(live: String, outer: String, from0: Int): String = {
    val from = skip_space(live, from0)
    if (from >= live.length) ""
    else
      live.charAt(from) match {
        case '"' =>
          val e = live.indexOf('"', from + 1)
          if (e < 0) "" else squash(live.substring(from + 1, e))
        case _ if live.startsWith("""\<open>""", from) =>
          val e = Entries.balanced_end(live, """\<open>""", """\<close>""", from)
          if (e < 0) "" else squash(live.substring(from + 7, e - 8))
        case _ =>
          /* Unquoted, so it ends where the header does -- `where`, a proof, or
             the end of what was read. */
          val rest_outer = outer.substring(from min outer.length)
          val cut =
            Py.search_from(HEADER_STOP_RE, rest_outer, 0) match {
              case Some(m) => m.start
              case None => rest_outer.length
            }
          squash(live.substring(from, (from + cut) min live.length))
      }
  }

  def written_type(live: String, outer: String, name: String): String = {
    val m = SIG_RE.matcher(live)
    while (m.find()) {
      val got = if (m.group(1) != null) m.group(1) else m.group(2)
      val sep = m.end - 2
      if (got == name && sep >= 0 && sep + 2 <= outer.length &&
        outer.regionMatches(sep, "::", 0, 2))
        return type_text(live, outer, m.end)
    }
    ""
  }


  /* --- the scan --- */

  /* Every code-equation site of `name`.  Three producers, and the kind column
     says which:

       `default`  the constant's own `definition` / `fun`, whose equations are
                  registered with no attribute written -- the site a reader
                  most often wants and the one a purely attribute-driven scan
                  would miss entirely;
       `[code …]` a declaration carrying a code attribute whose statement's
                  equation head is the constant;
       `[code …]` a `declare` / `lemmas` that attaches one to a named fact of
                  the constant, or a `[[code drop:]]` naming it outright. */
  def find_code_equations(sections: List[Theory_Section], name: String): List[Site] = {
    val by_name = Usage_Graph.entry_by_name(sections)
    val by_theory = Usage_Graph.sections_by_theory(sections)

    /* `live_source` is a `def` by design (model.scala: a cached view is a
       second copy of the corpus), so the one theory being scanned holds its
       view in a local and a CROSS-THEORY lookup memoises only the theories a
       `declare` actually reaches into -- a handful, not the corpus. */
    val live_cache = mutable.LinkedHashMap.empty[String, Array[String]]
    def live_of(theory: String): Array[String] =
      live_cache.getOrElseUpdate(theory,
        by_theory.get(theory).map(_.live_source).getOrElse(Array.empty[String]))

    /* The statement of a declaration, in the LIVE view: a superseded equation
       left behind in a `(* ... *)` note must not supply a head. */
    def statement(live: Array[String], e: Entry): String = {
      val stop =
        (if (e.decl_end_line >= e.thy_line) e.decl_end_line else e.thy_line) min live.length
      if (e.thy_line > stop) "" else (e.thy_line to stop).map(k => live(k - 1)).mkString("\n")
    }

    /* A fact name resolves to the constant when it SPELLS it, or when the
       entry it names is a lemma whose own equation head is the constant --
       `declare card_set [code]` for `card_set: "card (set xs) = ..."`. */
    def fact_denotes(token: String): Boolean =
      spells(token, name) || (by_name.get(token) match {
        case Some((theory, e)) if e.tag == "LEMMA" || e.tag == "THEOREM" =>
          equation_heads(statement(live_of(theory), e)).contains(name)
        case _ => false
      })

    val out = new mutable.ListBuffer[Site]
    for (sec <- sections) {
      val live = sec.live_source
      val outer = sec.outer_source
      val raw = sec.source
      live_cache(sec.theory) = live
      val found = new mutable.ListBuffer[Site]

      /* 1. Declarations: the entry grammar already knows where a statement
            ends (`Entry.decl_end_line`), so there is no second scan for it. */
      for (e <- sec.entries if e.thy_line > 0) {
        var attributed = false
        val stop =
          (if (e.decl_end_line >= e.thy_line) e.decl_end_line else e.thy_line) min live.length
        def head_of(view: Array[String]): String =
          if (e.thy_line > stop) "" else (e.thy_line to stop).map(k => view(k - 1)).mkString("\n")
        /* The declaration's own written signature, for `--sorts`.  Computed
           only for a row that is actually emitted: `written_type` is a regex
           over the header and there are 78,000 headers in `src/HOL`. */
        def signature: String = written_type(head_of(live), head_of(outer), e.name)
        val entry_name = if (e.name.nonEmpty) e.name else UNNAMED
        /* No attribute can be present without the word, and the word is rare:
           this keeps a whole-project scan to one substring test per line. */
        val maybe = e.thy_line <= stop && (e.thy_line to stop).exists(k => live(k - 1).contains("code"))
        if (maybe) {
          val head_live = head_of(live)
          val head_outer = head_of(outer)
          val attrs = code_attrs(head_live, head_outer)
          if (attrs.nonEmpty) {
            /* An attribute on the constant's OWN declaration
               (`definition [code del] ...`) is about that constant, whatever
               shape its defining equation is written in. */
            val subject_here =
              constant_tags(e.tag) && (e.name == name || e.bound_names.contains(name))
            val heads = if (subject_here) Nil else equation_heads(head_live)
            for (attr <- attrs) {
              val hit =
                if (attr.config) dropped_constants(head_live, attr).exists(denotes(_, name))
                else subject_here || heads.contains(name)
              if (hit) {
                attributed = true
                /* The PROVIDING fact, which for a declaration is its own name
                   -- the `lemma` carrying the attribute, or the `definition`
                   when the attribute sits on the defining equation. */
                found += Site(sec.theory, e.thy_line, "[" + attr.spelling + "]",
                  Py.rstrip(raw(e.thy_line - 1)), entry_name, signature)
              }
            }
          }
        }
        /* 2. The implicit default equations of the constant's own declaration
              -- unless the declaration ITSELF carries a code attribute
              (`definition thrice ... where [code]: "..."`).  There is one
              equation there, and printing the same line twice, once as
              `default` and once as `[code]`, would say there are two. */
        if (!attributed && default_code_tags(e.tag) &&
          (e.name == name || e.bound_names.contains(name)))
          found += Site(sec.theory, e.thy_line, "default", Py.rstrip(raw(e.thy_line - 1)),
            entry_name, signature)
      }

      /* 3. `declare` / `lemmas`, which declare no entry and so are invisible
            to the loop above. */
      var i = 1
      while (i <= outer.length) {
        val stripped = Py.lstrip(outer(i - 1))
        if (stripped.startsWith("declare ") || stripped.startsWith("lemmas ") ||
          stripped.startsWith("declare[") || stripped.startsWith("lemmas[")) {
          val head = header_at(live, outer, i)
          /* Past the command word: `declare` is not one of the facts it
             declares an attribute for. */
          val at = outer(i - 1).length - stripped.length +
            (if (stripped.startsWith("declare")) 7 else 6)
          if (head.live.contains("code"))
            for (attr <- code_attrs(head.live, head.outer)) {
              val dropped =
                if (attr.config) dropped_constants(head.live, attr).filter(denotes(_, name))
                else Nil
              val cited = if (attr.config) Nil else cited_fact_names(head.outer, at)
              val hit = dropped.nonEmpty || cited.exists(fact_denotes)
              if (hit) {
                /* The BINDING LABEL: the fact the attribute is attached to,
                   which is the first name the command writes -- `card_set` in
                   `declare card_set [code]`, `eq_fold` in
                   `lemmas eq_fold [code] = ...`.  The later names on a
                   `lemmas` right-hand side are what the label is bound TO, and
                   a row named after one of them would say the site is
                   somewhere it is not.  A `[[code drop: c]]` binds no fact and
                   is named after the constant it drops. */
                val label =
                  if (attr.config) dropped.head
                  else cited.headOption.getOrElse(UNNAMED)
                found += Site(sec.theory, i, "[" + attr.spelling + "]",
                  Py.rstrip(raw(i - 1)), label)
              }
            }
        }
        i += 1
      }
      out ++= found.toList.sortBy(_.line)
    }
    out.toList
  }


  /* ------------------------------------------------------------------ */
  /* the CLI verbs                                                      */
  /* ------------------------------------------------------------------ */

  /* One renderer for both verbs: they answer the same shape of question, so a
     caller who has learned to read one has learned to read the other.

     Four columns: LOCUS, NAME, KIND, source.  The name sits exactly where
     `callers` and `methods` put their owning entry, so a reader who knows one
     listing knows this one -- and the locus stays FIRST, which is what keeps
     `instances L | awk '{print $1}' | xargs isabelle query enclosing` working
     and what `--names` prints on its own.  It is one name per row, not
     `code_thms`' name-then-block layout: the flat row is what pastes.

     `--names` prints the bare loci, one per line, because for a SITE list the
     identity of a hit IS its locus -- and `theory:line` is the tool's own span
     grammar, so the output pipes straight into `enclosing` / `lines`. */
  private def emit(out: Out, sites: List[Site], name: String, noun: String,
    flags: Flags
  ): Unit = {
    if (flags.mode == "count") out.println(sites.length.toString)
    else if (flags.mode == "names") for (s <- sites) out.println(s"${s.theory}:${s.line}")
    else if (sites.isEmpty) out.println(s"No ${noun}s found for '$name'.")
    else {
      val labels = sites.map(_.label(flags.sorts))
      val loc_w = sites.map(s => s"${s.theory}:${s.line}".length).max
      val name_w = labels.map(_.length).max
      val kind_w = sites.map(_.kind.length).max
      out.println(s"${sites.length} $noun(s) of $name:\n")
      for ((s, label) <- sites.zip(labels)) {
        val loc = s"${s.theory}:${s.line}"
        out.println(s"  ${loc + " " * (loc_w - loc.length)}  " +
          s"${label + " " * (name_w - label.length)}  " +
          s"${s.kind + " " * (kind_w - s.kind.length)}  ${Py.strip(s.text)}")
      }
    }
  }

  /* A subject that cannot be resolved is reported on STDERR and exits 1, not
     as an empty listing: exit 0 with "no instantiations" would say the same
     thing for a typo, for a locale from an imported session, and for a locale
     that is genuinely never instantiated.  A KNOWN subject with no sites keeps
     the family's honest zero -- the same message shape and the same exit 0
     `callers` gives an entry nothing calls. */
  private def with_subject(out: Out, err: Out, sections: List[Theory_Section],
    name: String, tags: Set[String], what: String
  )(body: Subject => Unit): Unit =
    resolve(sections, name, tags, what) match {
      case Right(subject) =>
        if (subject.how.nonEmpty) out.println(s"# '$name' is ${subject.how}.")
        body(subject)
      case Left(msg) =>
        out.flush()
        err.println(s"ERROR: $msg")
        throw Exit_Code(1)
    }

  def cmd_instances(out: Out, err: Out, sections: List[Theory_Section], name: String,
    flags: Flags
  ): Unit =
    with_subject(out, err, sections, name, locale_tags, "a locale or class") { _ =>
      emit(out, find_instantiations(sections, name), name, "instantiation", flags)
    }

  def cmd_codeqs(out: Out, err: Out, sections: List[Theory_Section], name: String,
    flags: Flags
  ): Unit =
    with_subject(out, err, sections, name, constant_tags, "a constant") { _ =>
      emit(out, find_code_equations(sections, name), name, "code equation", flags)
    }
}
