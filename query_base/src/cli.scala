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
    append: Boolean = false, noop: Boolean = false, help: String = "")

  final case class Pos(dest: String, nargs: String, help: String = "")

  final case class Cmd(names: List[String], help: String, opts: List[Opt],
    pos: List[Pos], exclusive: List[List[String]] = Nil)

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

  private val files_pos = Pos("files", "*",
    "restrict the search to .thy files, directories or theory names; `-` reads stdin")

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
      Nil, List(Pos("args", "+", "`FILE RANGE...` or `FILE:RANGE ...`"))))

  /* Registered so they are rejected as NOT YET PORTED rather than as unknown:
     a caller who types a real subcommand of the reference tool must be told
     the phase has not landed, not that they mistyped. */
  val unported: List[(String, String)] = List(
    "deps" -> "P3", "uses" -> "P3", "graph" -> "P3", "refs" -> "P3",
    "callers" -> "P3", "callees" -> "P3", "unused" -> "P3",
    "methods" -> "P3", "method" -> "P3", "shape" -> "P4")

  private val root_opt =
    value("-R", "--root")("root", "Isabelle session directory to query")
  private val help_opt = flag("-h", "--help")("help", "show this help message and exit")
  private val version_opt = flag("--version")("version", "show the version and exit")
  private val top_version_opt =
    flag("-V", "--version")("version", "show the version and exit")

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
    for (o <- List(help_opt, root_opt, top_version_opt)) out.println(opt_line(o))
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

  def cmd_help(out: Out, cmd: Cmd): Unit = {
    val pos = cmd.pos.map(p => p.nargs match {
      case "1" => p.dest.toUpperCase
      case "?" => "[" + p.dest.toUpperCase + "]"
      case "+" => p.dest.toUpperCase + " [" + p.dest.toUpperCase + " ...]"
      case _ => "[" + p.dest.toUpperCase + " ...]"
    }).mkString(" ")
    out.println(s"Usage: isabelle query ${cmd.names.head} [OPTIONS] $pos")
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
  private val env_roots = List("ISABELLE_LAYOUT_ROOT", "ISABELLE_QUERY_ROOT")

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
     directory holding a ROOT file, else the cwd itself. */
  def default_root(): JPath = {
    val env = env_roots.map(System.getenv).find(v => v != null && v.nonEmpty)
    env match {
      case Some(v) => resolve(expanduser(v))
      case None =>
        val here = resolve(Paths.get(""))
        val chain = search_chain(here)
        val marker =
          chain.flatMap(d => marker_names.map(d.resolve)).find(Files.isRegularFile(_))
        marker match {
          case Some(m) => read_marker(m).getOrElse(m.getParent)
          case None =>
            chain.find(d => Files.isRegularFile(d.resolve("ROOT"))).getOrElse(here)
        }
    }
  }

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

    /* The custom-command union in force for the NEXT parse.  It is state, and
       deliberately so: the reference implementation keeps one module-level
       table, cleared by a whole-root load and merged into by each directory
       positional, and a single `.thy` path passed on the command line is
       parsed against whatever that table happens to hold.  Rebuilding it per
       file would change how such a file parses. */
    var custom_table: Map[String, String] = Map.empty

    private var index_cache: Option[List[Theory_Section]] = None

    def active_root: JPath = root_override.getOrElse(default_root())

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

    def load_index(): List[Theory_Section] = {
      index_cache match {
        case Some(secs) => secs
        case None =>
          custom_table = Map.empty
          val root = active_root
          val sections = new mutable.ListBuffer[Theory_Section]
          sections_from_dir(root, mutable.Set.empty[JPath], sections)
          if (sections.isEmpty) fail_root(root, diagnose_empty_root(root))
          val secs = sections.toList
          index_cache = Some(secs)
          secs
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
    val lines = src.lines
    if (syntactic) {
      if (src.preread.isDefined || src.from_stdin)
        Theory.parse_source(src.label, src.path, lines, s.custom_table)
      else Theory.parse_one(src.label, src.path, Theory.read(src.path), s.custom_table)
    }
    else Theory.parse_plain(src.label, src.path, lines)
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
          val p = Discovery.real(p0.toAbsolutePath)
          if (Files.isDirectory(p)) s.sections_from_dir(p, seen, sections)
          else {
            val src = resolve_file_source(s, token, p0)
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

  def flags_of(ns: Ns): Flags = {
    var mode = "first"
    if (ns.bool("all")) mode = "all"
    if (ns.bool("names")) mode = "names"
    if (ns.bool("count")) mode = "count"
    val comments =
      if (ns.bool("no_comments")) "off" else if (ns.bool("comments_only")) "only" else "on"
    val context =
      ns.str("context").flatMap(Py.parse_int).getOrElse(2)
    Flags(mode = mode, verbatim = ns.bool("verbatim"), statement = ns.bool("statement"),
      comments = comments, context = context, with_comments = ns.bool("with_comments"),
      recursive = ns.bool("recursive"), external = ns.bool("external"))
  }

  private def each[A](out: Out, subjects: List[A])(body: A => Unit): Unit = {
    var i = 0
    for (subject <- subjects) {
      if (i > 0) out.println()
      i += 1
      body(subject)
    }
  }

  def dispatch(s: Session, name: String, ns: Ns): Unit = {
    val out = s.out
    val err = s.err
    val flags = flags_of(ns)
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
        val top = ns.str("top").flatMap(Py.parse_int).getOrElse(20)
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
            path_of(file_token).getOrElse(Paths.get(file_token)))
        Commands.cmd_lines(out, err, src.lines, ranges)
      case _ => usage_error(s"unknown command: $name")
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

  def run(args: List[String]): Unit = {
    val out = Out.stdout
    val err = Out.stderr
    var rc = 0
    try {
      val s = new Session(err, out)
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
        if (tok == "-h" || tok == "--help") top_help_asked = true
        else if (tok == "-V" || tok == "--version") version_asked = true
        else if (tok == "-R" || tok == "--root") {
          if (rest.isEmpty) usage_error("argument -R/--root: expected one argument")
          top_root = Some(rest.head); rest = rest.tail
        }
        else if (tok.startsWith("--root=")) top_root = Some(tok.substring(7))
        else if (tok.startsWith("-R") && tok.length > 2) top_root = Some(tok.substring(2))
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
                    val root = top_root.orElse(ns.str("root"))
                    for (r <- root) {
                      /* An explicit -R is an ASSERTION by the caller: they said
                         this is a root, so if it is not one that is an error
                         rather than an empty answer. */
                      val p = expanduser(r)
                      if (!Files.exists(p)) s.fail_root(p, "no such directory (given to -R/--root)")
                      if (!Files.isDirectory(p)) s.fail_root(p, "not a directory (given to -R/--root)")
                      s.root_override = Some(Discovery.real(p.toAbsolutePath))
                    }
                    dispatch(s, cmd.names.head, ns)
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
    if (rc != 0) sys.exit(rc)
  }
}
