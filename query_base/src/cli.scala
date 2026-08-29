/*  Title:      query_base/src/cli.scala

The command line: which root to read, which bytes a positional names, and the
argument grammar itself.

The reference implementation is an `argparse` tree, and `argparse`'s observable
behaviour — unambiguous long-option abbreviation, short-flag bundling,
`--opt=value`, positionals gathered across interleaved options, exit 2 on a
usage error — is part of the contract a drop-in replacement has to keep.  So
the parser here is hand-rolled to mirror it rather than borrowed from
`Getopts`, which supports none of that.

Three things are deliberately kept apart, exactly as the reference keeps them:

  * WHICH root to read (`-R`, `$ISABELLE_QUERY_ROOT`, a `.isabelle-query`
    marker, the walk up from the cwd) — configuration;
  * WHERE a `CMD PATH...` token's bytes are (a file, a directory, a bare
    theory name, `-` for stdin) — routing;
  * WHETHER to read them as Isabelle (`syntax` / `infer`) — the command's own
    parse policy.

One resolver serves all three consumers, so `lines` (raw text) and the search
family (syntax-aware) can never drift on what a token means.
*/

package isabelle.query


import isabelle.*

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, InvalidPathException, Path => JPath, Paths}

import scala.collection.mutable


object CLI {
  val version = "0.8.0-scala"
  val prog = "query"

  /* Exit statuses, as the CLI contract fixes them: 0 ran, 1 an unresolved
     subject, 2 a usage error or an unreadable root, 141 a closed stdout. */
  val EXIT_BAD_ROOT = 2
  val EXIT_SIGPIPE = 141


  /* ------------------------------------------------------------------ */
  /* the argument grammar                                               */
  /* ------------------------------------------------------------------ */

  final case class Opt(strings: List[String], dest: String, unary: Boolean = false,
    append: Boolean = false, noop: Boolean = false, help: String = "",
    choices: List[String] = Nil)

  final case class Pos(dest: String, nargs: String, help: String = "",
    choices: List[String] = Nil, default: Option[String] = None)

  /* `context_default` is a per-command number rather than a constant because
     `-U` means two things by family: a preview wants 2 lines, a caller listing
     wants 0.  One flag, one spelling, one help helper — the default is the only
     part that legitimately differs. */
  /* `subs` is non-empty for exactly one verb, `shape`, which is a NESTED
     subcommand group rather than a flat command: its five views share the one
     step-scanner engine but differ in shape (aggregate table vs per-step stream
     vs ranked list vs batch census), so a flat verb with mode flags would blur
     them.  A group takes only the global options itself; everything from the
     sub-verb name on belongs to the sub-parser, which is argparse's
     `nargs=PARSER` and the same rule the top level already follows. */
  final case class Cmd(names: List[String], help: String, opts: List[Opt],
    pos: List[Pos], exclusive: List[List[String]] = Nil, context_default: Int = 2,
    subs: List[Cmd] = Nil)

  final class Ns {
    val flags: mutable.Set[String] = mutable.Set.empty
    val values: mutable.Map[String, String] = mutable.Map.empty
    val appended: mutable.Map[String, mutable.ListBuffer[String]] = mutable.Map.empty
    val positional: mutable.Map[String, List[String]] = mutable.Map.empty

    def bool(d: String): Boolean = flags(d)
    def str(d: String): Option[String] = values.get(d)
    def list(d: String): List[String] = appended.get(d).map(_.toList).getOrElse(Nil)
    def pos(d: String): List[String] = positional.getOrElse(d, Nil)
    def one(d: String): String = pos(d).head
  }

  private def flag(strings: String*)(dest: String, help: String = ""): Opt =
    Opt(strings.toList, dest, help = help)

  private def value(strings: String*)(dest: String, help: String = ""): Opt =
    Opt(strings.toList, dest, unary = true, help = help)

  /* Shared flag groups, spelled once so a subcommand's help cannot drift from
     its neighbour's — the reference implementation's `_add_*_flag` helpers. */
  private val count_flag = flag("-c", "--count")("count", "just print the count")
  private val names_flag = flag("--names")("names", "names + tags + theory only")
  private val all_flag = flag("-a", "--all")("all", "show all matches")
  private val verbatim_flag =
    flag("-V", "--verbatim")("verbatim", "full source slice (statement + proof)")
  private val statement_flag =
    flag("--statement", "--stmt")("statement", "the statement slice (declaration, no proof)")
  private val no_comments_flag =
    flag("--no-comments")("no_comments", "suppress preamble and roadmap")
  private val comments_only_flag =
    flag("--comments-only")("comments_only", "show only preamble + roadmap")
  private val context_flag =
    value("-U", "--context")("context", "lines of preview / context (default 2)")
  private val with_comments_flag =
    flag("--with-comments")("with_comments",
      """also search inside `text` blocks and \<comment> annotations""")
  /* `-n` cannot MEAN line numbers here — every hit already prints its
     `theory.thy:LINE` locus — but rejecting it sends a grep-reflex caller back
     to raw grep, which is the substitution this tool exists to prevent.  So it
     parses and does nothing. */
  private val line_number_noop =
    Opt(List("-n", "--line-number"), "line_number", noop = true,
      help = "accepted and ignored (grep-compatibility no-op)")
  private val theory_scope_flag =
    Opt(List("--theory"), "theory_scope", unary = true, append = true,
      help = "confine the search to theory THY; repeatable")
  private val recursive_flag =
    flag("-r", "--recursive")("recursive", "transitive closure")
  /* Filter short citation names out of the call graph: a length-1 token (`x`,
     `a`, `f`) is a term variable in nearly every proof, so by default single-
     char names are not graph nodes.  0 keeps them; 2 also drops 2-char names.
     Method / keyword / numeral routing is independent of it. */
  private val drop_names_flag =
    value("--drop-names-upto")("drop_names_upto",
      s"exclude citation-graph names of length <= L (default ${Usage_Graph.DROP_NAMES_UPTO}: " +
        "drop single-char variable collisions; 0 keeps all; 2 also drops 2-char names)")
  private val external_flag =
    flag("--external")("external", "cut at the defining theory's boundary")
  /* WRITTEN text only, and the help says so: this tool never runs a prover, so
     a type it printed that the source does not contain would be the one column
     nobody could check. */
  private val sorts_flag =
    flag("--sorts")("sorts",
      "in the name column, add the sort / arity / signature THE SOURCE WRITES " +
        "at that site (`prod :: (topological_space, topological_space) " +
        "topological_space`).  Written text only -- no types are inferred, so a " +
        "site whose source writes none shows none.")

  private val files_pos = Pos("files", "*",
    "restrict the search to .thy files, directories or theory names; `-` reads stdin")

  private val shape_json_flag =
    flag("--json")("json", "emit one JSONL record per line instead of the table")

  private val shape_config_flags: List[Opt] = List(
    value("--config")("config",
      "M3 corpus config (TOML, one [corpus] table per entry); adds the frame_ratio / " +
        "frame_mentioned / frame_changed columns to the JSON where a step shows a " +
        "configuration signal"),
    value("--corpus")("corpus",
      "select the [NAME] table from --config (required when the file defines more " +
        "than one corpus)"))

  private def shape_views: List[Cmd] = List(
    Cmd(List("summary"), "per-theory shape aggregate table",
      List(shape_json_flag.copy(help =
        "emit one per-proof JSONL record per line instead of the table"),
        Opt(List("--scope"), "scope", unary = true, choices = List("proof", "entry"),
          help = "size column region: the proof body (default) or the whole entry " +
            "incl. statement (as `largest` counts)"),
        Opt(List("--content"), "content", unary = true,
          choices = List("all", "code", "prose"),
          help = "size column content: all lines (default), code only (prose " +
            "stripped — the shared text/comment set), or prose only")),
      Nil),
    Cmd(List("steps"),
      "per-step shape records, optionally scoped to a THEORY or THEORY:A..B locus",
      List(all_flag.copy(help =
        "include non-goal steps (context / plumbing / closing); the default shows " +
          "goal steps only, where the shape metrics attach"),
        shape_json_flag.copy(help = "emit one per-step JSONL record per line")) :::
        shape_config_flags,
      List(Pos("span", "?", "optional scope: a bare THEORY name, or a THEORY:A..B / " +
        "THEORY:LINE locus (the same grammar `enclosing` / `lines` use, so a span " +
        "pastes straight in)"))),
    Cmd(List("lemma"),
      "full per-step shape view of one proof, its aggregate footer, and its M6 " +
        "extension curve",
      List(shape_json_flag.copy(help =
        "emit one per-step JSONL record per line (every step of the lemma)")) :::
        shape_config_flags,
      List(Pos("name", "+", "entry name(s); each is matched exact-then-substring, and " +
        "multiple are reported in turn (blank-line separated)"))),
    Cmd(List("widest"),
      "the N widest steps by a chosen metric (the step analogue of `largest`)",
      List(value("-N", "--top")("top", "number of steps to show (default 20)"),
        Opt(List("--metric"), "metric", unary = true,
          choices = List("w2", "w1", "fanin", "live"),
          help = "ranking metric: w2 as-written token width (default), w1 free " +
            "variables, fanin cited facts, live simultaneously-live facts"),
        shape_json_flag.copy(help = "emit the ranked steps as JSONL")),
      List(files_pos)),
    Cmd(List("census"),
      "stream one per-proof JSONL record per entry (whole-AFP distribution run; " +
        "streaming + resumable)",
      List(value("--resume")("resume",
        "skip entries already present in FILE (a prior census JSONL), so " +
          "`census -R AFP/thys --resume out.jsonl >> out.jsonl` picks up a killed " +
          "run where it stopped")),
      Nil))

  val commands: List[Cmd] = List(
    Cmd(List("summary"), "theory overview table (--by-session for an aggregate)",
      List(flag("-S", "--by-session")("by_session", "aggregate by session"),
        flag("-v", "--verbose")("verbose", "with --by-session, expand to per-theory rows"),
        count_flag.copy(help = "print only the grand totals, no table")),
      List(files_pos)),
    Cmd(List("theory"), "show all entries for a theory",
      List(names_flag, count_flag, verbatim_flag, no_comments_flag, comments_only_flag,
        context_flag),
      List(Pos("name", "1", "theory name")),
      List(List("no_comments", "comments_only"))),
    Cmd(List("defs"), "list definitions in a theory",
      List(names_flag, count_flag), List(Pos("theory", "1", "theory name"))),
    Cmd(List("outline"), "section structure with entries",
      List(no_comments_flag, comments_only_flag, context_flag),
      List(Pos("theory", "1", "theory name")),
      List(List("no_comments", "comments_only"))),
    Cmd(List("enclosing", "at"),
      "name the entry (and nearest proof block) that owns each FILE:LINE locus",
      List(flag("-e", "--entry")("entry", "report only the owning entry"),
        flag("-b", "--blocks")("blocks", "report the full nesting path")),
      List(Pos("locus", "+", "one or more `FILE:LINE` or `FILE:A..B` loci")),
      List(List("entry", "blocks"))),
    Cmd(List("largest"), "top N largest entries by span",
      List(value("-N", "--top")("top", "number of entries to show (default 20)")),
      List(files_pos)),
    Cmd(List("find"), "find entries by name (regex; --statement matches the statement)",
      List(all_flag, count_flag, names_flag, verbatim_flag, statement_flag,
        no_comments_flag, comments_only_flag, context_flag, with_comments_flag,
        theory_scope_flag,
        flag("--and")("conjunction", "keep only entries matched by EVERY pattern")),
      List(Pos("pattern", "+", "regex pattern(s)")),
      List(List("no_comments", "comments_only"))),
    Cmd(List("show"), "show one or more specific entries",
      List(all_flag, count_flag, names_flag, verbatim_flag, statement_flag,
        no_comments_flag, comments_only_flag, context_flag),
      List(Pos("name", "+", "entry name(s)")),
      List(List("no_comments", "comments_only"), List("verbatim", "statement"))),
    Cmd(List("grep"), "regex search across live theory source",
      List(with_comments_flag, count_flag,
        names_flag.copy(help = "locations + owning entry only"), line_number_noop),
      List(Pos("pattern", "1", "regex pattern"), files_pos)),
    Cmd(List("sorry"), "list open goals: every live `sorry` with its location",
      List(count_flag.copy(help = "just print the count"), line_number_noop),
      List(files_pos)),
    Cmd(List("lines"), "print line ranges of FILE with a `NR| CONTENT` prefix",
      Nil, List(Pos("args", "+", "`FILE RANGE...` or `FILE:RANGE ...`"))),

    /* -- the usage family ------------------------------------------------ */
    Cmd(List("deps"), "theories these import (direct; -r for transitive); reverse is `uses`",
      List(recursive_flag.copy(help = "transitive closure (all indirect imports)")),
      List(Pos("theory", "+", "theory name(s) or .thy path(s)"))),
    Cmd(List("uses"), "theories that import these (direct; -r for transitive); reverse of `deps`",
      List(recursive_flag.copy(help = "transitive closure (all indirect importers)")),
      List(Pos("theory", "+", "theory name(s) or .thy path(s)"))),
    Cmd(List("graph"), "emit the whole citation or import graph as JSON or DOT",
      List(Opt(List("--format", "-f"), "format", unary = true,
        help = "json (default) or dot", choices = List("json", "dot")),
        drop_names_flag, theory_scope_flag),
      List(Pos("kind", "?", "citation (entry names, from the call graph) or imports " +
        "(theories, from the `imports` clauses).  Default: citation",
        choices = List("citation", "imports"), default = Some("citation")))),
    Cmd(List("refs"), "names a theory cites, grouped by owning theory",
      List(count_flag.copy(help = "just print the distinct referenced-name count"),
        names_flag.copy(help = "bare referenced names, one per line, for piping back in"),
        drop_names_flag,
        external_flag.copy(help =
          "exclude names this theory declares itself, leaving only what it takes " +
            "from elsewhere")),
      List(Pos("theory", "+", "theory name(s) or .thy path(s)"))),
    Cmd(List("callers"), "find proof-body usages",
      List(count_flag, recursive_flag.copy(help = "transitive closure (all indirect callers)"),
        names_flag, drop_names_flag,
        context_flag.copy(help = "show N trailing lines after each match (default 0)"),
        external_flag.copy(help =
          "exclude callers inside the theory that defines NAME.  Only affects the " +
            "non-recursive form; transitive closure via -r ignores this flag.")),
      List(Pos("name", "+", "entry name(s)")), Nil, context_default = 0),
    Cmd(List("callees"), "entries this entry references; reverse is `callers`",
      List(count_flag, names_flag, drop_names_flag,
        recursive_flag.copy(help = "transitive closure (all indirect callees)"),
        external_flag.copy(help =
          "exclude callees defined in NAME's own theory, leaving only cross-theory " +
            "dependencies.  Only affects the non-recursive form.")),
      List(Pos("name", "+", "entry name(s)"))),
    Cmd(List("unused"), "list entries with zero callers",
      List(count_flag,
        recursive_flag.copy(help = "cascade: include entries whose callers are all unused"),
        flag("--by-theory")("by_theory", "group by theory with line counts"),
        flag("--roots")("roots", "forest summary: each root with exclusive subtree size"),
        Opt(List("--keep"), "keep", unary = true, append = true,
          help = "treat these names as live roots (never flag as unused, and stop the " +
            "cascade at them).  Repeatable, or a comma-separated list."),
        drop_names_flag),
      Nil),
    Cmd(List("methods", "method"),
      "proof-method usage tally; `methods NAME` lists that method's uses",
      List(all_flag, count_flag, names_flag),
      List(Pos("name", "?", "a proof method (e.g. simp, auto, induct); omit for the " +
        "ranked tally of every method used"))),

    /* -- the site family (no counterpart in the reference tool) ----------- */
    Cmd(List("instances"),
      "where a locale or class is instantiated (instantiation / instance / " +
        "interpretation / sublocale)",
      List(count_flag.copy(help = "just print the site count"),
        names_flag.copy(help =
          "bare `THEORY:LINE` loci, one per line, for piping into `enclosing`"),
        sorts_flag),
      List(Pos("name", "+", "locale or class name(s).  Reports the DECLARED SOURCE " +
        "sites, which is the complement of Isar's `print_interps`: that needs a " +
        "running prover and shows the processed interpretations, including those an " +
        "imported session installed."))),
    Cmd(List("codeqs"),
      "declared code-equation sites of a constant (`[code]` and kin, plus its " +
        "own default equations)",
      List(count_flag.copy(help = "just print the site count"),
        names_flag.copy(help =
          "bare `THEORY:LINE` loci, one per line, for piping into `enclosing`"),
        sorts_flag),
      List(Pos("name", "+", "constant name(s).  Reports the DECLARED SOURCE sites, " +
        "which is the complement of Isar's `print_codesetup` / `code_thms`: those " +
        "need a running prover and show the PROCESSED setup -- after preprocessing, " +
        "after `[code del]` has taken effect, and including what an imported session " +
        "declared."))),

    /* -- the shape family (a nested group; see `Cmd.subs`) ---------------- */
    Cmd(List("shape"), "proof-shape metrics (summary|steps|lemma|widest|census)",
      Nil, Nil, subs = shape_views))

  /* Registered so they are rejected as NOT YET PORTED rather than as unknown:
     a caller who types a real subcommand of the reference tool must be told
     the phase has not landed, not that they mistyped.  Empty now that `shape`
     has landed; kept because the next breaking addition wants it. */
  val unported: List[(String, String)] = Nil

  private val root_opt =
    value("-R", "--root")("root", "Isabelle session directory to query")
  private val help_opt = flag("-h", "--help")("help", "show this help message and exit")
  private val version_opt = flag("--version")("version", "show the version and exit")
  private val top_version_opt =
    flag("-V", "--version")("version", "show the version and exit")

  /* DISPLAY ONLY, and deliberately not in the grammar: `--no-server` is read
     and removed before any argument is parsed (`strip_no_server` below),
     because it selects WHO runs the query rather than anything about the
     query.  Listing it here is what keeps `isabelle query -h` honest; putting
     it in `resolve_long` would let an abbreviation reach the parser and be
     accepted as a no-op — an invocation that asked not to be served and was
     served anyway, which is the one outcome the flag exists to prevent. */
  private val no_server_opt =
    flag("--no-server")("no_server",
      "run in this process; do not use (or start) the warm `isabelle query` server")

  private def cmd_opts(cmd: Cmd): List[Opt] = cmd.opts ::: List(root_opt, version_opt, help_opt)


  /* ------------------------------------------------------------------ */
  /* the parser                                                         */
  /* ------------------------------------------------------------------ */

  final class Usage_Error(val message: String) extends RuntimeException(null, null, false, false)

  private def usage_error(msg: String): Nothing = throw new Usage_Error(msg)

  private def looks_negative(s: String): Boolean =
    s.length > 1 && s.charAt(0) == '-' &&
      (s.charAt(1).isDigit || (s.charAt(1) == '.' && s.length > 2 && s.charAt(2).isDigit))

  /* One long option by exact name, else by UNAMBIGUOUS prefix — argparse's
     abbreviation rule, which shell history and muscle memory depend on. */
  private def resolve_long(opts: List[Opt], name: String): Opt = {
    val exact = opts.filter(_.strings.contains(name))
    if (exact.nonEmpty) exact.head
    else {
      val prefix =
        opts.filter(_.strings.exists(s => s.startsWith("--") && s.startsWith(name)))
      val distinct = prefix.map(_.dest).distinct
      if (distinct.length == 1) prefix.head
      else if (distinct.isEmpty) usage_error(s"unrecognized argument: $name")
      else usage_error(s"ambiguous option: $name could match " +
        prefix.flatMap(_.strings).filter(_.startsWith("--")).mkString(", "))
    }
  }

  def parse(opts: List[Opt], positionals: List[Pos], args: List[String]): Ns = {
    val ns = new Ns
    val pos_args = new mutable.ListBuffer[String]
    val short: Map[Char, Opt] =
      (for (o <- opts; s <- o.strings if s.length == 2 && s.startsWith("-") && s != "--")
        yield s.charAt(1) -> o).toMap

    def store(o: Opt, v: Option[String]): Unit =
      if (o.noop) ()
      else if (o.unary) {
        val x = v.getOrElse(usage_error(s"argument ${o.strings.head}: expected one argument"))
        if (o.append) ns.appended.getOrElseUpdate(o.dest, new mutable.ListBuffer) += x
        else ns.values(o.dest) = x
      }
      else {
        if (v.isDefined) usage_error(s"argument ${o.strings.head}: ignored explicit argument")
        ns.flags += o.dest
      }

    var rest = args
    var only_positional = false
    while (rest.nonEmpty) {
      val tok = rest.head
      rest = rest.tail
      if (only_positional) pos_args += tok
      else if (tok == "--") only_positional = true
      else if (tok.startsWith("--")) {
        val eq = tok.indexOf('=')
        val (name, inline) =
          if (eq >= 0) (tok.substring(0, eq), Some(tok.substring(eq + 1))) else (tok, None)
        val o = resolve_long(opts, name)
        if (o.unary && inline.isEmpty) {
          if (rest.isEmpty) usage_error(s"argument $name: expected one argument")
          store(o, Some(rest.head)); rest = rest.tail
        }
        else store(o, inline)
      }
      else if (tok.length > 1 && tok.startsWith("-") && !looks_negative(tok)) {
        /* A short cluster: `-ac`, and `-U3` where the tail is the value. */
        var i = 1
        var consumed = false
        while (i < tok.length && !consumed) {
          val c = tok.charAt(i)
          short.get(c) match {
            case None => usage_error(s"unrecognized argument: -$c")
            case Some(o) =>
              if (o.unary) {
                val tail = tok.substring(i + 1)
                if (tail.nonEmpty) store(o, Some(tail))
                else if (rest.nonEmpty) { store(o, Some(rest.head)); rest = rest.tail }
                else usage_error(s"argument -$c: expected one argument")
                consumed = true
              }
              else store(o, None)
          }
          i += 1
        }
      }
      else pos_args += tok
    }

    /* `-h` / `--version` fire while the arguments are being READ and exit, so
       they override whatever else is on the line — including a missing
       required positional.  `query grep -h` must print help, not complain
       that PATTERN is absent. */
    if (ns.flags("help") || ns.flags("version")) return ns

    /* Positionals are distributed over the declared slots in order: a fixed
       slot takes one, and the single variable slot takes what is left over
       after the fixed ones behind it are satisfied. */
    var remaining = pos_args.toList
    for ((p, k) <- positionals.zipWithIndex) {
      val later_required =
        positionals.drop(k + 1).count(q => q.nargs == "1" || q.nargs == "+")
      p.nargs match {
        case "1" =>
          if (remaining.isEmpty) usage_error(s"the following arguments are required: ${p.dest}")
          ns.positional(p.dest) = List(remaining.head)
          remaining = remaining.tail
        case "?" =>
          if (remaining.length > later_required) {
            ns.positional(p.dest) = List(remaining.head)
            remaining = remaining.tail
          }
          else ns.positional(p.dest) = Nil
        case "+" =>
          val take = remaining.length - later_required
          if (take < 1) usage_error(s"the following arguments are required: ${p.dest}")
          ns.positional(p.dest) = remaining.take(take)
          remaining = remaining.drop(take)
        case _ =>
          val take = (remaining.length - later_required) max 0
          ns.positional(p.dest) = remaining.take(take)
          remaining = remaining.drop(take)
      }
    }
    if (remaining.nonEmpty)
      usage_error("unrecognized arguments: " + remaining.mkString(" "))

    /* A `choices=` slot rejects anything else with exit 2 — never a silent
       fallback to the default, which would answer a different question from
       the one asked. */
    def check(what: String, allowed: List[String], v: String): Unit =
      if (!allowed.contains(v))
        usage_error(s"argument $what: invalid choice: '$v' (choose from " +
          allowed.map(c => s"'$c'").mkString(", ") + ")")
    for (o <- opts if o.choices.nonEmpty; v <- ns.str(o.dest))
      check(o.strings.mkString("/"), o.choices, v)
    for (p <- positionals if p.choices.nonEmpty; v <- ns.pos(p.dest))
      check(p.dest, p.choices, v)
    ns
  }


  /* ------------------------------------------------------------------ */
  /* help                                                               */
  /* ------------------------------------------------------------------ */

  private def opt_line(o: Opt): String = {
    val spelling = o.strings.mkString(", ") + (if (o.unary) " VALUE" else "")
    "  " + (if (spelling.length >= 30) spelling + "\n" + " " * 32
            else spelling + " " * (32 - spelling.length - 2)) + o.help
  }

  def top_help(out: Out): Unit = {
    out.println(s"Usage: isabelle query [OPTIONS] COMMAND [ARGS...]")
    out.println("")
    out.println("Query an Isabelle/Isar project: syntax-aware, no build required.")
    out.println("")
    out.println("options:")
    for (o <- List(help_opt, root_opt, top_version_opt, no_server_opt)) out.println(opt_line(o))
    out.println("")
    out.println("commands:")
    for (c <- commands) {
      val names = c.names.mkString(" (", ", ", ")") match {
        case _ if c.names.length == 1 => c.names.head
        case s => c.names.head + " (" + c.names.tail.mkString(", ") + ")"
      }
      out.println("  " + (if (names.length >= 18) names + "\n" + " " * 20
                          else names + " " * (20 - names.length - 2)) + c.help)
    }
    if (unported.nonEmpty) {
      out.println("")
      out.println("not yet ported (see PLAN.md): " +
        unported.map(p => p._1 + " [" + p._2 + "]").mkString(", "))
    }
  }

  /* A nested group's own help: the global options it takes, then its views.
     Reached by `shape -h` and by a bare `shape`, which — like argparse with an
     unchosen subparser — prints this and exits 0. */
  def group_help(out: Out, cmd: Cmd): Unit = {
    out.println(s"Usage: isabelle query ${cmd.names.head} [OPTIONS] " +
      cmd.subs.map(_.names.head).mkString("{", ",", "}") + " ...")
    out.println("")
    out.println(cmd.help)
    out.println("")
    out.println("options:")
    for (o <- List(help_opt, root_opt, version_opt)) out.println(opt_line(o))
    out.println("")
    out.println(s"${cmd.names.head} commands:")
    for (c <- cmd.subs) {
      val n = c.names.head
      out.println("  " + (if (n.length >= 18) n + "\n" + " " * 20
                          else n + " " * (20 - n.length - 2)) + c.help)
    }
  }

  def cmd_help(out: Out, cmd: Cmd, prefix: String = ""): Unit = {
    val pos = cmd.pos.map(p => p.nargs match {
      case "1" => p.dest.toUpperCase
      case "?" => "[" + p.dest.toUpperCase + "]"
      case "+" => p.dest.toUpperCase + " [" + p.dest.toUpperCase + " ...]"
      case _ => "[" + p.dest.toUpperCase + " ...]"
    }).mkString(" ")
    out.println(s"Usage: isabelle query $prefix${cmd.names.head} [OPTIONS] $pos")
    out.println("")
    out.println(cmd.help)
    if (cmd.names.length > 1) out.println("aliases: " + cmd.names.tail.mkString(", "))
    out.println("")
    out.println("positional arguments:")
    for (p <- cmd.pos) out.println("  " + p.dest.toUpperCase + ": " + p.help)
    out.println("")
    out.println("options:")
    for (o <- cmd_opts(cmd)) out.println(opt_line(o))
  }


  /* ------------------------------------------------------------------ */
  /* the active root                                                    */
  /* ------------------------------------------------------------------ */

  def expanduser(s: String): JPath = {
    val home = System.getProperty("user.home")
    val expanded =
      if (s == "~") home
      else if (s.startsWith("~/")) home + s.substring(1)
      else s
    Paths.get(expanded)
  }

  private def resolve(p: JPath): JPath = Discovery.real(p.toAbsolutePath)

  private val marker_names = List(".isabelle-layout", ".isabelle-query")

  /* EVERY environment variable the engine reads, in one place, because "which
     variables does a request carry" is a question the warm server has to
     answer exactly (`server.scala`, and `dev/P6C-STATUS.md`).  The four below
     are per-REQUEST and are forwarded by the thin client;
     `$ISABELLE_QUERY_JAR` and `$ISABELLE_QUERY_SERVER_LIMIT` are read by the
     server ABOUT ITSELF and are deliberately not here.

     `root_env` is the subset that names a DIRECTORY, and it is a separate list
     because the delegating CLI derives a request's root from the first one
     that is set — a check that must not be reached by simply not being the
     namespace variable, or every switch added here would become a root. */
  val root_env: List[String] = List("ISABELLE_LAYOUT_ROOT", "ISABELLE_QUERY_ROOT")
  private val env_roots = root_env
  val NAMESPACE_ENV: String = "ISABELLE_QUERY_NAMESPACE"
  /* `off` turns import-visibility filtering off, restoring the name-only
     attribution the Python reference implements (dev/DIVERGENCES.md D13).
     Deliberately env-only, exactly as `$ISABELLE_QUERY_NAMESPACE` is: a global
     that moves a measurement gets ONE channel as well as one default, and an
     argv flag would be a second one the plugin and the library caller do not
     have. */
  val REACHABILITY_ENV: String = "ISABELLE_QUERY_REACHABILITY"
  val request_env: List[String] = env_roots ::: List(NAMESPACE_ENV, REACHABILITY_ENV)

  val process_env: String => Option[String] =
    k => Option(System.getenv(k)).filter(_.nonEmpty)

  private def search_chain(start: JPath): List[JPath] = {
    val out = new mutable.ListBuffer[JPath]
    var d = start
    while (d != null) { out += d; d = d.getParent }
    out.toList
  }

  private def read_marker(marker: JPath): Option[JPath] =
    try {
      val lines = Py.split_lines(new String(Files.readAllBytes(marker),
        StandardCharsets.UTF_8))._1
      lines.map(Py.strip).find(s => s.nonEmpty && !s.startsWith("#")).map { s =>
        val p = expanduser(s)
        resolve(if (p.isAbsolute) p else marker.getParent.resolve(p))
      }
    }
    catch { case _: Exception => None }

  /* `$ISABELLE_LAYOUT_ROOT` / `$ISABELLE_QUERY_ROOT`, else the nearest project
     marker at or above the cwd (proximity beats spelling), else the nearest
     directory holding a ROOT file, else the cwd itself.

     Split from `default_root()` so a host that is not the process the user
     typed in can still apply the rule: the warm server resolves a request
     against the CLIENT's working directory and the CLIENT's environment, both
     forwarded over the wire.  The POLICY has one definition — this function —
     and the two callers differ only in where the two inputs come from. */
  def default_root_from(start: JPath, env_root: Option[String]): JPath =
    env_root.filter(_.nonEmpty) match {
      case Some(v) => resolve(expanduser(v))
      case None =>
        val here = resolve(start)
        val chain = search_chain(here)
        val marker =
          chain.flatMap(d => marker_names.map(d.resolve)).find(Files.isRegularFile(_))
        marker match {
          case Some(m) => read_marker(m).getOrElse(m.getParent)
          case None =>
            chain.find(d => Files.isRegularFile(d.resolve("ROOT"))).getOrElse(here)
        }
    }

  def default_root(): JPath =
    default_root_from(Paths.get(""), env_roots.iterator.flatMap(process_env(_)).nextOption())

  /* An empty index is an error, never a result: no real Isabelle project has
     zero theories, so reaching this means the root was wrong, unreadable, or
     not a session.  Saying WHICH is the whole point — an empty answer printed
     silently is indistinguishable from a legitimate "nothing found". */
  def diagnose_empty_root(root: JPath): String = {
    if (!Files.exists(root)) "no such directory"
    else if (!Files.isDirectory(root)) "not a directory"
    else {
      val roots = Discovery.discover_roots(root)
      if (roots.isEmpty) {
        val thy = Discovery.walk(root, p => p.getFileName.toString.endsWith(".thy")).headOption
        thy match {
          case None =>
            "no ROOT or ROOTS file, and no .thy files — not an Isabelle session directory"
          case Some(p) =>
            s"no ROOT or ROOTS file, and the .thy files present " +
              s"(e.g. ${p.getFileName}) yielded no theory"
        }
      }
      else {
        val sessions = Discovery.iter_sessions(root)
        if (sessions.isEmpty)
          s"${roots.length} ROOT file(s) found, but none declares a session"
        else {
          val shown = sessions.take(3).map(_.name).mkString(", ")
          val more = if (sessions.length <= 3) "" else s", +${sessions.length - 3} more"
          s"${sessions.length} session(s) declared ($shown$more) but no theory resolved " +
            "— check ROOT's `theories` / `directories` clauses"
        }
      }
    }
  }


  /* ------------------------------------------------------------------ */
  /* loading                                                            */
  /* ------------------------------------------------------------------ */

  final class Session(val err: Out, val out: Out) {
    var root_override: Option[JPath] = None

    /* THE ENVIRONMENT THIS RUN IS ABOUT, which for a served run is not the
       one the JVM was started with.

       A process reads its own environment and that is the whole story; a
       resident server does not, and getting this wrong is a bug the type
       system cannot see.  A server spawned by a client that had
       `$ISABELLE_QUERY_NAMESPACE=committed` set inherited it FOR ITS WHOLE
       LIFE, so every later client -- with a clean environment, and the same
       argv as a cold run -- got the pinned table: a ZF `callers induct`
       answered 1 where the typed command answers 250.

       So the environment is a per-REQUEST value, indirected through here.  A
       command-line run binds the process's own; the warm server binds what the
       client forwarded and NEVER its own, because the server's environment is
       an accident of whoever started it. */
    var env: String => Option[String] = process_env

    /* The custom-command union in force for the NEXT parse.  It is state, and
       deliberately so: the reference implementation keeps one module-level
       table, cleared by a whole-root load and merged into by each directory
       positional, and a single `.thy` path passed on the command line is
       parsed against whatever that table happens to hold.  Rebuilding it per
       file would change how such a file parses. */
    var custom_table: Map[String, String] = Map.empty

    private var index_cache: Option[List[Theory_Section]] = None

    /* The ambient root, for a run with no `-R`.  A CLI run reads the process's
       own cwd and environment; a served run reads the CLIENT's, which is why
       this is a function and not a constant. */
    var ambient_root: () => JPath = () => default_root_from(Paths.get(""), env_root)

    /* `$ISABELLE_LAYOUT_ROOT` / `$ISABELLE_QUERY_ROOT`, in that order, read
       through this run's environment rather than the process's. */
    def env_root: Option[String] = env_roots.iterator.flatMap(env(_)).nextOption()

    /* A warm index, offered by the host for ONE root.  Consulted only when the
       run's active root is that one: a `-R elsewhere` on the same connection
       must fall through to a cold parse rather than answer from the wrong
       tree.  The map is the custom-command keyword union that produced those
       sections — `resolve_file_source` parses a `.thy` named on the command
       line against it, so a seeded index has to seed the table too. */
    var index_provider: JPath => Option[(List[Theory_Section], Map[String, String])] = _ => None

    def active_root: JPath = root_override.getOrElse(ambient_root())

    def fail_root(root: JPath, why: String): Nothing = {
      out.flush()
      err.println(s"isabelle query: $root: $why")
      throw Exit_Code(EXIT_BAD_ROOT)
    }

    def sections_from_dir(dir: JPath, seen: mutable.Set[JPath],
      sections: mutable.ListBuffer[Theory_Section]
    ): Unit = {
      val plan = Theory.plan(dir)
      custom_table = custom_table ++ plan.union
      val parsed =
        Par_List.map((fk: (Discovery.Found, Map[String, String])) =>
          (fk._1, Theory.parse(fk._1, custom_table ++ fk._2)), plan.found)
      for ((found, sec) <- parsed) {
        val rp = Discovery.real(found.path)
        if (!seen(rp)) { seen += rp; sec.foreach(sections += _) }
      }
    }

    /* Exactly ONE session's theories — the unit of work for a batch corpus run
       (`shape census`).  Same three phases as `sections_from_dir`, scoped to
       one session: the custom-command union is rebuilt from THIS session's
       headers rather than accumulated, which is what makes a batch run produce
       what the per-invocation runs it replaces produced.

       `seen` is the caller's dedup set and must be SHARED across sessions,
       exactly as a whole-root load shares one: 47 AFP theory files are
       referenced by two sessions, and a per-session set would parse and emit
       each twice — silent duplicate records that inflate every aggregate. */
    def sections_for_session(si: Discovery.Session_Info, seen: mutable.Set[JPath]
    ): List[Theory_Section] = {
      val found =
        Discovery.session_theories(si).map(p => Discovery.Found(p._1, p._2, Some(si.name)))
      val owned =
        Par_List.map((f: Discovery.Found) =>
          (f, if (f.path.getFileName.toString.endsWith(".thy")) Theory.header_keywords(f.path)
              else Map.empty[String, String]), found)
      val union = owned.foldLeft(Map.empty[String, String])(_ ++ _._2)
      custom_table = union
      val parsed =
        Par_List.map((fk: (Discovery.Found, Map[String, String])) =>
          (fk._1, Theory.parse(fk._1, union)), owned)
      val sections = new mutable.ListBuffer[Theory_Section]
      for ((f, sec) <- parsed) {
        val rp = Discovery.real(f.path)
        if (!seen(rp)) { seen += rp; sec.foreach(sections += _) }
      }
      sections.toList
    }

    def load_index(): List[Theory_Section] = {
      index_cache match {
        case Some(secs) => secs
        case None =>
          val root = active_root
          index_provider(root) match {
            case Some((secs, table)) =>
              custom_table = table
              index_cache = Some(secs)
              secs
            case None =>
              custom_table = Map.empty
              val sections = new mutable.ListBuffer[Theory_Section]
              sections_from_dir(root, mutable.Set.empty[JPath], sections)
              if (sections.isEmpty) fail_root(root, diagnose_empty_root(root))
              val secs = sections.toList
              index_cache = Some(secs)
              secs
          }
      }
    }
  }

  val STDIN_SENTINEL = "-"
  val STDIN_NAME = "<stdin>"
  def stdin_path: JPath = Paths.get(STDIN_NAME)

  final case class File_Source(label: String, path: JPath, preread: Option[Array[String]]) {
    def from_stdin: Boolean = path.getFileName.toString == STDIN_NAME
    def lines: Array[String] =
      preread.getOrElse(Py.split_lines(Theory.read(path))._1)
  }

  private def read_stdin(): Array[String] = {
    val buf = new ByteArrayOutputStream
    val chunk = new Array[Byte](1 << 16)
    val in: InputStream = System.in
    var n = in.read(chunk)
    while (n > 0) { buf.write(chunk, 0, n); n = in.read(chunk) }
    Py.split_lines(new String(buf.toByteArray, StandardCharsets.UTF_8))._1
  }

  private def stem(p: JPath): String = {
    val n = p.getFileName.toString
    val i = n.lastIndexOf('.')
    if (i > 0) n.substring(0, i) else n
  }

  private def path_of(token: String): Option[JPath] =
    try Some(expanduser(token)) catch { case _: InvalidPathException => None }

  /* An existing file resolves to itself; otherwise the token is a bare theory
     NAME (or a path whose stem names one), looked up in the lazily-built
     index — the same spelling `outline` / `show` / `defs` take.  A token that
     is neither is an unresolved SUBJECT, which is exit 1, not a usage error. */
  def resolve_file_source(s: Session, token: String, p: JPath): File_Source = {
    if (Files.exists(p)) File_Source(stem(p), p, None)
    else {
      val index = s.load_index()
      Commands.resolve_theory(index, token) match {
        case Some(sec) => File_Source(stem(sec.path), sec.path, None)
        case None =>
          s.out.flush()
          val hint = Commands.suggest_theory(index, token) match {
            case Some(h) => s" (did you mean $h?)"
            case None => ""
          }
          s.err.println(s"ERROR: not a path or known theory: $token$hint")
          throw Exit_Code(1)
      }
    }
  }

  /* `syntax` always applies the entry grammar — for `largest` / `sorry`, whose
     output IS the entry view, syntax-awareness is intrinsic.  `infer` (only
     `grep`) decides per source from the `.thy` suffix, stdin defaulting to
     syntax-aware because the load-bearing case is a piped theory. */
  def section_from(s: Session, src: File_Source, parse_policy: String): Theory_Section = {
    val syntactic =
      parse_policy == "syntax" || src.from_stdin ||
        src.path.getFileName.toString.endsWith(".thy")
    if (syntactic) {
      src.preread match {
        case Some(lines) => Theory.parse_source(src.label, src.path, lines, s.custom_table)
        case None =>
          Theory.parse_one(src.label, src.path, Theory.read(src.path), s.custom_table)
      }
    }
    else Theory.parse_plain(src.label, src.path, src.lines)
  }

  /* Peel an optional `:A..B` window off a grep PATH.  The suffix is a window
     ONLY when the part before it resolves, so a path that happens to end in a
     colon, the `-` sentinel and a plain bad token all fall through to the
     normal resolver and its existing error. */
  private def split_path_window(s: Session, token: String
  ): (Option[(Int, Option[Int])], String) =
    Commands.parse_locus(token) match {
      case None => (None, token)
      case Some((file_token, lo, hi)) =>
        val resolves =
          path_of(file_token).exists(Files.exists(_)) ||
            Commands.resolve_theory(s.load_index(), file_token).isDefined
        if (resolves) (Some((lo, hi)), file_token) else (None, token)
    }

  def load_sections(s: Session, ns: Ns, parse_policy: String = "infer",
    windows: Boolean = false
  ): List[Theory_Section] = {
    val files = ns.pos("files")
    if (files.isEmpty) s.load_index()
    else {
      val sections = new mutable.ListBuffer[Theory_Section]
      val seen = mutable.Set.empty[JPath]
      var stdin_read = false
      for (token0 <- files) {
        if (token0 == STDIN_SENTINEL) {
          if (!stdin_read) {
            stdin_read = true
            sections += section_from(s,
              File_Source(STDIN_NAME, stdin_path, Some(read_stdin())), parse_policy)
          }
        }
        else {
          val (window, token) =
            if (windows) split_path_window(s, token0) else (None, token0)
          val p0 = path_of(token).getOrElse(Paths.get(token))
          /* The REAL path, not the typed one: the label a section carries (and
             so the `Foo.thy:LINE` a hit prints) is the resolved file's name,
             which is what a symlinked positional must report. */
          val p = Discovery.real(p0.toAbsolutePath)
          if (Files.isDirectory(p)) s.sections_from_dir(p, seen, sections)
          else {
            val src = resolve_file_source(s, token, p)
            val resolved = Discovery.real(src.path.toAbsolutePath)
            if (!seen(resolved)) {
              seen += resolved
              val sec = section_from(s, src, parse_policy)
              sec.line_window = window
              sections += sec
            }
          }
        }
      }
      sections.toList
    }
  }

  /* Narrow the index to the theories `--theory` names.  An unresolvable one is
     reported and skipped rather than silently narrowing to nothing: "no
     matches" from a typo'd scope is the failure mode this tool exists to
     avoid. */
  def scope_to_theories(s: Session, ns: Ns, sections: List[Theory_Section]
  ): List[Theory_Section] = {
    val wanted = ns.list("theory_scope")
    if (wanted.isEmpty) sections
    else {
      val kept = new mutable.ListBuffer[Theory_Section]
      for (name <- wanted) {
        Commands.resolve_theory(sections, name) match {
          case None =>
            s.out.flush()
            s.err.println(s"isabelle query: theory '$name' not found")
          case Some(sec) => if (!kept.exists(_ eq sec)) kept += sec
        }
      }
      kept.toList
    }
  }


  /* ------------------------------------------------------------------ */
  /* dispatch                                                           */
  /* ------------------------------------------------------------------ */

  /* A `type=int` argparse option rejects a non-integer with exit 2; silently
     falling back to the default would answer a different question from the one
     asked, which is the failure mode this tool exists to avoid. */
  private def int_arg(ns: Ns, dest: String, spelling: String, default: Int): Int =
    ns.str(dest) match {
      case None => default
      case Some(v) =>
        Py.parse_int(v).getOrElse(
          usage_error(s"argument $spelling: invalid int value: '$v'"))
    }

  def flags_of(ns: Ns, context_default: Int = 2): Flags = {
    var mode = "first"
    if (ns.bool("all")) mode = "all"
    if (ns.bool("names")) mode = "names"
    if (ns.bool("count")) mode = "count"
    val comments =
      if (ns.bool("no_comments")) "off" else if (ns.bool("comments_only")) "only" else "on"
    val context = int_arg(ns, "context", "-U/--context", context_default)
    /* `--keep a,b --keep c` is one list: repeatable AND comma-separated, so a
       shell loop's output pastes in either way. */
    val keep = ns.list("keep").flatMap(_.split(",", -1)).filter(Py.strip(_).nonEmpty).toSet
    Flags(mode = mode, verbatim = ns.bool("verbatim"), statement = ns.bool("statement"),
      comments = comments, context = context, with_comments = ns.bool("with_comments"),
      recursive = ns.bool("recursive"), external = ns.bool("external"),
      sorts = ns.bool("sorts"),
      by_theory = ns.bool("by_theory"), roots = ns.bool("roots"), keep = keep,
      drop_names_upto =
        int_arg(ns, "drop_names_upto", "--drop-names-upto", Usage_Graph.DROP_NAMES_UPTO))
  }

  private def each[A](out: Out, subjects: List[A])(body: A => Unit): Unit = {
    var i = 0
    for (subject <- subjects) {
      if (i > 0) out.println()
      i += 1
      body(subject)
    }
  }

  /* ------------------------------------------------------------------ */
  /* the method / attribute namespace                                    */
  /* ------------------------------------------------------------------ */

  /* The verbs that reconfigure the method/attribute table for the project.
     EVERY other verb is a pure text/structure query and must not pay for the
     table — and above all must not resolve one.

     `refs` and `graph` DO read the table (they build the same citation graph),
     and are deliberately NOT on this list, because the reference implementation
     does not list them either: they keep the committed default whatever the
     project's base logic is.  So on a non-HOL session `callers -r` routes
     against the Pure floor while `graph` routes against the HOL union, and the
     two disagree about whether `iff` is a method or a fact.  That is
     observable in the graph both commands print, so it is behaviour to
     reproduce, not an internal inconsistency to tidy away. */
  private val namespace_commands: Set[String] =
    Set("callers", "callees", "unused", "methods", "method", "shape")

  /* Bind the router's table for this run, at DISPATCH — after the arguments are
     read and before the command runs, never at start-up, so a `find` or a `grep`
     costs nothing for a table it never reads.

     There is one committed default and both the CLI and a direct engine caller
     get it: the broad HOL-family union (`CONTRIBUTING.md`, "a configurable
     global that moves a measurement gets ONE default").  The one step DOWN is
     explicit and evidence-driven: a project whose declared sessions resolve to
     a base logic that is POSITIVELY not HOL (`ZF`, `FOL`, `CTT`, `Sequents`, …)
     gets the minimal Pure floor instead, because the HOL union would assert
     methods that logic does not have.  An unknown base — an out-of-scope parent
     session name reached under `-R <sub-session>` — is left on the default.

     `$ISABELLE_QUERY_NAMESPACE=committed` pins the default: it short-circuits
     even the step-down, which is what a caller who wants one fixed table across
     a mixed corpus asks for. */
  /* Bind import-visibility filtering for this run, from THIS request's
     environment, in BOTH directions and unconditionally.

     Unconditional because the variable it writes is process-global and a warm
     server serves many clients: binding it only when a client asks for `off`
     would pin the switch for everyone after them — the defect
     `configure_namespace` records at length, avoided here by construction
     rather than by care.  It costs one environment lookup, so there is no verb
     list to keep in step either. */
  def configure_reachability(s: Session): Unit =
    Reach.set_enabled(!Reach.env_disables(s.env(REACHABILITY_ENV)))

  def configure_namespace(s: Session, command: String, sub: String = ""): Unit = {
    /* `shape census` is special, and its binding is UNCONDITIONAL — independent
       of `$ISABELLE_QUERY_NAMESPACE` and of the project's base logic.  A
       whole-corpus run spans many logics with no single session to resolve
       against, and its output is meant to regenerate identically anywhere, so
       it takes the fixed broad union.  Inheriting whatever the project fallback
       picked would mean a census stops regenerating identically. */
    if (command == "shape" && sub == "census") {
      Namespace.use_census_namespace()
      return
    }
    if (!namespace_commands(command)) return
    /* Read through the SESSION's environment, not the process's: in a resident
       server the process's is whatever the first client happened to export,
       and it would pin the table for everyone after them. */
    if (s.env(NAMESPACE_ENV).exists(_.toLowerCase == "committed")) return
    try {
      val sessions = Discovery.iter_sessions(s.active_root)
      val parents = sessions.map(si => si.name -> si.parent.getOrElse("")).toMap
      val nonhol =
        sessions.exists(si =>
          Namespace.is_known_nonhol_base(Namespace.resolve_base_logic(si.name, parents)))
      if (nonhol) {
        Namespace.use_pure_namespace()
        /* Silent for a Pure-only project — the floor is exact there, and there
           is nothing to warn about. */
        val bases =
          sessions.flatMap(_.parent).filter(_ != "Pure").distinct.sorted
        if (bases.nonEmpty) {
          s.out.flush()
          s.err.println(s"isabelle query: no built heap for this project and its base " +
            s"logic (${bases.mkString(", ")}) is not HOL, so the committed HOL " +
            "namespace table does not apply — using the minimal Pure table.")
        }
      }
    }
    catch { case _: Throwable => () }   // binding a table must never fail a query
  }


  def dispatch(s: Session, name: String, ns: Ns, context_default: Int = 2): Unit = {
    val out = s.out
    val err = s.err
    val flags = flags_of(ns, context_default)
    name match {
      case "summary" =>
        Commands.cmd_summary(out, load_sections(s, ns), ns.bool("by_session"),
          ns.bool("verbose"), ns.bool("count"))
      case "theory" =>
        Commands.cmd_theory(out, load_sections(s, ns), ns.one("name"), flags)
      case "defs" =>
        Commands.cmd_defs(out, load_sections(s, ns), ns.one("theory"), flags)
      case "outline" =>
        Commands.cmd_outline(out, err, load_sections(s, ns), ns.one("theory"), flags)
      case "enclosing" | "at" =>
        val mode =
          if (ns.bool("entry")) "entry" else if (ns.bool("blocks")) "blocks" else "nearest"
        Commands.cmd_enclosing(out, err, load_sections(s, ns), ns.pos("locus"), mode)
      case "largest" =>
        val top = int_arg(ns, "top", "-N/--top", 20)
        Commands.cmd_largest(out, load_sections(s, ns, parse_policy = "syntax"), top)
      case "find" =>
        val patterns = ns.pos("pattern")
        if (ns.bool("conjunction") && patterns.length > 1)
          Commands.cmd_find_and(out, err,
            scope_to_theories(s, ns, load_sections(s, ns)), patterns, flags)
        else {
          val sections = scope_to_theories(s, ns, load_sections(s, ns))
          each(out, patterns)(p => Commands.cmd_find(out, err, sections, p, flags))
        }
      case "show" =>
        val sections = load_sections(s, ns)
        each(out, ns.pos("name"))(n => Commands.cmd_show(out, sections, n, flags))
      case "grep" =>
        Commands.cmd_grep(out, err,
          load_sections(s, ns, parse_policy = "infer", windows = true),
          ns.one("pattern"), flags)
      case "sorry" =>
        Commands.cmd_sorry(out, load_sections(s, ns, parse_policy = "syntax"),
          ns.bool("count"))
      case "lines" =>
        val (file_token, ranges) = lines_file_and_ranges(s, ns.pos("args"))
        val src =
          if (file_token == STDIN_SENTINEL)
            File_Source(STDIN_NAME, stdin_path, Some(read_stdin()))
          else resolve_file_source(s, file_token,
            Discovery.real(path_of(file_token).getOrElse(Paths.get(file_token))
              .toAbsolutePath))
        Commands.cmd_lines(out, err, src.lines, ranges)
      case "deps" =>
        val sections = s.load_index()
        each(out, ns.pos("theory"))(t =>
          Usage.cmd_deps(out, sections, t, recursive = ns.bool("recursive")))
      case "uses" =>
        val sections = s.load_index()
        each(out, ns.pos("theory"))(t =>
          Usage.cmd_deps(out, sections, t, reverse = true, recursive = ns.bool("recursive")))
      case "refs" =>
        val sections = s.load_index()
        each(out, ns.pos("theory"))(t => Usage.cmd_refs(out, sections, t, flags))
      case "callers" =>
        val sections = s.load_index()
        each(out, ns.pos("name"))(n => Usage.cmd_callers(out, sections, n, flags))
      case "callees" =>
        val sections = s.load_index()
        each(out, ns.pos("name"))(n => Usage.cmd_callees(out, sections, n, flags))
      case "unused" =>
        Usage.cmd_unused(out, err, s.load_index(), flags)
      case "methods" =>
        Usage.cmd_methods(out, s.load_index(), ns.pos("name").headOption, flags)
      case "instances" =>
        val sections = s.load_index()
        each(out, ns.pos("name"))(n => Sites.cmd_instances(out, err, sections, n, flags))
      case "codeqs" =>
        val sections = s.load_index()
        each(out, ns.pos("name"))(n => Sites.cmd_codeqs(out, err, sections, n, flags))
      case "graph" =>
        val sections = scope_to_theories(s, ns, s.load_index())
        Usage.cmd_graph(out, sections, ns.pos("kind").headOption.getOrElse("citation"),
          ns.str("format").getOrElse("json"), flags)
      case _ => usage_error(s"unknown command: $name")
    }
  }


  /* ------------------------------------------------------------------ */
  /* the shape group                                                    */
  /* ------------------------------------------------------------------ */

  /* Resolve the optional M3 corpus config.  `--config PATH` loads the TOML;
     `--corpus NAME` selects a table.  With a single-table file the corpus is
     inferred; with several a `--corpus` is REQUIRED — fail fast rather than
     pick one silently, since the choice moves a measurement. */
  private def load_shape_config(s: Session, ns: Ns): Option[Shape.Corpus_Config] =
    ns.str("config") match {
      case None => None
      case Some(path) =>
        def fail(msg: String): Nothing = {
          s.out.flush()
          s.err.println(msg)
          throw Exit_Code(1)
        }
        val configs =
          try Toml.read_corpus_configs(Paths.get(path))
          catch { case exn: Toml.Error => fail(s"ERROR: ${exn.message}") }
        ns.str("corpus") match {
          case Some(corpus) =>
            configs.get(corpus) match {
              case Some(c) => Some(c)
              case None =>
                val have =
                  if (configs.isEmpty) "none" else configs.keys.toList.sorted.mkString(", ")
                fail(s"ERROR: no [$corpus] table in $path (have: $have)")
            }
          case None =>
            if (configs.size == 1) Some(configs.values.head)
            else fail(s"ERROR: $path defines ${configs.size} corpora " +
              s"(${configs.keys.toList.sorted.mkString(", ")}); select one with --corpus")
        }
    }

  /* `census`: one process, one SESSION at a time.  The #7 "never answer an
     empty question silently" contract has to be re-derived here, because "the
     index came back empty" is a per-session event rather than a whole-run one:

       * no session at all — not an error: a bare directory of `.thy` files has
         no ROOT and is still a corpus, so it becomes one unnamed group loaded
         by `load_index`, which raises #7's own diagnosis if even the fallback
         finds nothing;
       * every session skipped — nothing was measured and every attempt raised,
         which is the corpus-scale version of the bug #7 fixed, so it is 2;
       * some skipped — the question WAS asked and mostly answered: exit 0, but
         say on stderr how many were lost, so a wrapper is never quietly given a
         short corpus;
       * loaded but zero records — an honest zero (a corpus of definitions has
         no proofs), so exit 0 in silence.  The distinction from the case above
         is the whole point: `loaded` counts sessions that PARSED, not sessions
         that produced output. */
  private def run_census(s: Session, ns: Ns): Unit = {
    val root = s.active_root
    val sessions = Discovery.iter_sessions(root)
    val seen = mutable.Set.empty[JPath]
    val groups: Iterator[(String, () => List[Theory_Section])] =
      if (sessions.nonEmpty)
        sessions.iterator.map(si => (si.name, () => s.sections_for_session(si, seen)))
      else Iterator(("", () => s.load_index()))
    val outcome = Shape_Cmds.cmd_shape_census(s.out, s.err, groups, ns.str("resume"))
    if (outcome.loaded == 0)
      s.fail_root(root, s"all ${outcome.sessions} session(s) failed to load — " +
        "no census records produced")
    if (outcome.skipped > 0) {
      s.out.flush()
      s.err.println(s"isabelle query: census completed with ${outcome.skipped} of " +
        s"${outcome.sessions} session(s) skipped; ${Py.comma(outcome.records)} records " +
        s"from ${outcome.loaded} session(s)")
    }
  }

  def dispatch_shape(s: Session, view: String, ns: Ns): Unit = {
    val out = s.out
    val err = s.err
    view match {
      case "summary" =>
        Shape_Cmds.cmd_shape_summary(out, load_sections(s, ns), ns.bool("json"),
          ns.str("scope").getOrElse("proof"), ns.str("content").getOrElse("all"))
      case "steps" =>
        Shape_Cmds.cmd_shape_steps(out, err, load_sections(s, ns),
          ns.pos("span").headOption, ns.bool("json"), ns.bool("all"),
          load_shape_config(s, ns))
      case "lemma" =>
        /* The config is resolved BEFORE the index is loaded, as the reference
           does: a bad `--config` is a usage-shaped error and must not be
           reported after a whole-corpus parse. */
        val cfg = load_shape_config(s, ns)
        val sections = load_sections(s, ns)
        each(out, ns.pos("name"))(n =>
          Shape_Cmds.cmd_shape_lemma(out, sections, n, ns.bool("json"), cfg))
      case "widest" =>
        /* `widest` ranks STEPS by width — syntax-awareness is intrinsic — and it
           takes trailing PATH positionals like `largest`. */
        Shape_Cmds.cmd_shape_widest(out, load_sections(s, ns, parse_policy = "syntax"),
          int_arg(ns, "top", "-N/--top", 20), ns.str("metric").getOrElse("w2"),
          ns.bool("json"))
      case "census" => run_census(s, ns)
      case _ => usage_error(s"unknown shape view: $view")
    }
  }

  /* `lines FILE RANGE...` or the colon form `lines FILE:RANGE ...`, detected
     by whether the first token parses as a locus.  The colon loci must name
     ONE file: `cmd_lines` reads a single source. */
  private def lines_file_and_ranges(s: Session, tokens: List[String]
  ): (String, List[String]) = {
    if (Commands.parse_locus(tokens.head).isDefined) {
      val loci =
        for (t <- tokens) yield Commands.parse_locus(t) match {
          case Some(l) => l
          case None =>
            s.out.flush()
            s.err.println(s"ERROR: mixed `lines` forms — '$t' is not FILE:RANGE")
            throw Exit_Code(2)
        }
      val files = loci.map(_._1).distinct
      if (files.length > 1) {
        s.out.flush()
        s.err.println("ERROR: `lines` reads one file, got: " + files.sorted.mkString(", "))
        throw Exit_Code(2)
      }
      (loci.head._1, loci.map { case (_, lo, hi) =>
        hi match { case None => s"$lo.."; case Some(h) => s"$lo..$h" } })
    }
    else if (tokens.length < 2) {
      s.out.flush()
      s.err.println("ERROR: `lines` needs at least one RANGE " +
        "(`FILE RANGE...` or `FILE:RANGE ...`)")
      throw Exit_Code(2)
    }
    else (tokens.head, tokens.tail)
  }


  /* ------------------------------------------------------------------ */
  /* entry point                                                        */
  /* ------------------------------------------------------------------ */

  private def find_cmd(name: String): Option[Cmd] = commands.find(_.names.contains(name))

  /* An explicit -R is an ASSERTION by the caller: they said this is a root, so
     if it is not one that is an error rather than an empty answer. */
  private def apply_root(s: Session, root: Option[String]): Unit =
    for (r <- root) {
      val p = expanduser(r)
      if (!Files.exists(p)) s.fail_root(p, "no such directory (given to -R/--root)")
      if (!Files.isDirectory(p)) s.fail_root(p, "not a directory (given to -R/--root)")
      s.root_override = Some(Discovery.real(p.toAbsolutePath))
    }

  /* The whole CLI, minus the two things only a process may do: write to file
     descriptors and exit.  Both callers — `run` below and the warm server's
     `query_run` — go through here, so there is ONE argument grammar, ONE
     dispatch and ONE exit-status rule rather than a second CLI grown beside
     the first.  `prepare` is the host's one hook on the freshly built session
     (the server seeds a warm index and the client's ambient root through it);
     it runs before any argument is read, so nothing it sets can depend on
     them, which is what keeps the hook from becoming a back door into the
     grammar. */
  def run_result(args: List[String], out: Out, err: Out,
    prepare: Session => Unit = _ => ()
  ): Int = {
    var rc = 0
    try {
      val s = new Session(err, out)
      prepare(s)
      /* Before any argument is read, because it is free and because the
         alternative is a per-verb list: every verb that attributes a name to a
         declaration reads this, and the ones that do not are unaffected. */
      configure_reachability(s)
      /* The top level takes its own options until the first positional; from
         the command name on, everything belongs to the subparser — argparse's
         `nargs=PARSER`, which is why `-R` works on either side. */
      var top_root: Option[String] = None
      var rest = args
      var command: Option[String] = None
      var top_help_asked = false
      var version_asked = false
      while (command.isEmpty && rest.nonEmpty) {
        val tok = rest.head
        rest = rest.tail
        def take_root(inline: Option[String]): Unit = inline match {
          case Some(v) => top_root = Some(v)
          case None =>
            if (rest.isEmpty) usage_error("argument -R/--root: expected one argument")
            top_root = Some(rest.head); rest = rest.tail
        }
        if (tok.startsWith("--")) {
          val eq = tok.indexOf('=')
          val (name, inline) =
            if (eq >= 0) (tok.substring(0, eq), Some(tok.substring(eq + 1))) else (tok, None)
          /* Abbreviated the same way a subcommand's options are: `--roo DIR`
             before the command must mean what it means after it. */
          resolve_long(List(help_opt, root_opt, top_version_opt), name).dest match {
            case "help" => top_help_asked = true
            case "version" => version_asked = true
            case _ => take_root(inline)
          }
        }
        else if (tok == "-h") top_help_asked = true
        else if (tok == "-V") version_asked = true
        else if (tok == "-R") take_root(None)
        else if (tok.startsWith("-R") && tok.length > 2) take_root(Some(tok.substring(2)))
        else if (tok.startsWith("-") && tok.length > 1 && !looks_negative(tok))
          usage_error(s"unrecognized argument: $tok")
        else command = Some(tok)
      }

      if (version_asked) out.println(s"$prog $version")
      else if (top_help_asked) top_help(out)
      else command match {
        case None => top_help(out); rc = 1
        case Some(name) =>
          unported.find(_._1 == name) match {
            case Some((_, phase)) =>
              err.println(s"isabelle query: `$name` is not yet ported " +
                s"(phase $phase — see PLAN.md); it must not answer silently")
              rc = EXIT_BAD_ROOT
            case None =>
              find_cmd(name) match {
                case None =>
                  usage_error(s"argument command: invalid choice: '$name' (choose from " +
                    commands.flatMap(_.names).map(n => s"'$n'").mkString(", ") + ")")
                case Some(group_cmd) if group_cmd.subs.nonEmpty =>
                  /* A nested group reads its own options exactly as the top
                     level does — up to the first positional, which is the view
                     name; everything after it belongs to the view's parser, so
                     `-R` works on either side of it too. */
                  var group_root: Option[String] = None
                  var view: Option[String] = None
                  var group_help_asked = false
                  var group_version = false
                  while (view.isEmpty && rest.nonEmpty) {
                    val tok = rest.head
                    rest = rest.tail
                    def take_root(inline: Option[String]): Unit = inline match {
                      case Some(v) => group_root = Some(v)
                      case None =>
                        if (rest.isEmpty) usage_error("argument -R/--root: expected one argument")
                        group_root = Some(rest.head); rest = rest.tail
                    }
                    if (tok.startsWith("--")) {
                      val eq = tok.indexOf('=')
                      val (nm, inline) =
                        if (eq >= 0) (tok.substring(0, eq), Some(tok.substring(eq + 1)))
                        else (tok, None)
                      resolve_long(List(help_opt, root_opt, version_opt), nm).dest match {
                        case "help" => group_help_asked = true
                        case "version" => group_version = true
                        case _ => take_root(inline)
                      }
                    }
                    else if (tok == "-h") group_help_asked = true
                    else if (tok == "-R") take_root(None)
                    else if (tok.startsWith("-R") && tok.length > 2) take_root(Some(tok.substring(2)))
                    else if (tok.startsWith("-") && tok.length > 1 && !looks_negative(tok))
                      usage_error(s"unrecognized argument: $tok")
                    else view = Some(tok)
                  }
                  if (group_version) out.println(s"$prog $version")
                  else if (group_help_asked) group_help(out, group_cmd)
                  else view match {
                    /* A group with no view chosen prints its help and exits 0 —
                       argparse's behaviour for an unchosen subparser. */
                    case None => group_help(out, group_cmd)
                    case Some(v) =>
                      group_cmd.subs.find(_.names.contains(v)) match {
                        case None =>
                          usage_error("argument " +
                            group_cmd.subs.map(_.names.head).mkString("{", ",", "}") +
                            s": invalid choice: '$v' (choose from " +
                            group_cmd.subs.flatMap(_.names).map(n => s"'$n'").mkString(", ") + ")")
                        case Some(sub) =>
                          val ns = parse(cmd_opts(sub), sub.pos, rest ::: Nil)
                          if (ns.bool("help")) cmd_help(out, sub, group_cmd.names.head + " ")
                          else if (ns.bool("version")) out.println(s"$prog $version")
                          else {
                            apply_root(s, top_root.orElse(group_root).orElse(ns.str("root")))
                            configure_namespace(s, group_cmd.names.head, sub.names.head)
                            dispatch_shape(s, sub.names.head, ns)
                          }
                      }
                  }

                case Some(cmd) =>
                  val ns = parse(cmd_opts(cmd), cmd.pos, rest ::: Nil)
                  if (ns.bool("help")) cmd_help(out, cmd)
                  else if (ns.bool("version")) out.println(s"$prog $version")
                  else {
                    for (group <- cmd.exclusive) {
                      val set = group.filter(ns.bool)
                      if (set.length > 1)
                        usage_error("argument " + set.map("--" + _.replace('_', '-')).mkString(
                          "/") + ": not allowed with each other")
                    }
                    apply_root(s, top_root.orElse(ns.str("root")))
                    configure_namespace(s, name)
                    dispatch(s, cmd.names.head, ns, cmd.context_default)
                  }
              }
          }
      }
      out.flush()
    }
    catch {
      case exn: Usage_Error =>
        try out.flush() catch { case _: Broken_Pipe => }
        err.println(s"isabelle query: error: ${exn.message}")
        rc = 2
      case Exit_Code(c) =>
        try out.flush() catch { case _: Broken_Pipe => }
        rc = c
      case _: Broken_Pipe => rc = EXIT_SIGPIPE
    }
    rc
  }

  /* `--no-server` off the front, before anything reads the argv.

     The flag is the user's way of saying "not through the warm server", and
     since P8 the only thing that can honour it is the `lib/Tools/query` shim,
     which routes such an invocation straight here.  By the time this process
     exists the wish has already been granted; what is left is to remove the
     token, because the grammar above does not know it and would report it as
     an unrecognized argument.

     Removed anywhere before a bare `--`, and only in the exact spelling: an
     abbreviation gets the grammar's usual complaint, which is a better answer
     than silently accepting a flag whose meaning this process cannot act on.

     It is still stripped HERE, and not only in the shim, because `Query_Main`
     is a public entry point — a caller who spells the JVM path directly must
     get the same reading of the same argv. */
  def strip_no_server(args: List[String]): List[String] = {
    val out = new mutable.ListBuffer[String]
    var only_pos = false
    for (tok <- args) {
      if (only_pos) out += tok
      else if (tok == "--") { only_pos = true; out += tok }
      else if (tok == "--no-server") ()
      else out += tok
    }
    out.toList
  }

  def run(args: List[String]): Unit = {
    val rc = run_result(args, Out.stdout, Out.stderr)
    if (rc != 0) sys.exit(rc)
  }
}
