package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import java.io.StringWriter
import TestSupport.*

private[regression] object CliSupport {
  def command(name: String): CLI.Cmd = CLI.commands.find(_.names.contains(name)).get
  def args(name: String, values: String*): CLI.Ns = {
    val cmd = command(name)
    CLI.parse(cmd.opts, cmd.pos, values.toList)
  }
  def lines(text: String): List[String] = text.linesIterator.toList
  def matches(text: String, pattern: String): Boolean =
    java.util.regex.Pattern.compile(pattern).matcher(text).find()
  def capture(body: (Out, Out) => Unit): CliResult = {
    val out = new StringWriter; val err = new StringWriter
    var exit = 0
    try body(new Out(out), new Out(err)) catch { case Exit_Code(n) => exit = n }
    CliResult(exit, out.toString, err.toString)
  }
  def load(root: JPath): List[Theory_Section] = {
    val s = new CLI.Session(new Out(new StringWriter), new Out(new StringWriter))
    s.env = _ => None
    s.ambient_root = () => root
    s.load_index()
  }
  def fileSections(root:JPath, tokens:List[String], windows:Boolean=false):List[Theory_Section] = {
    val s=new CLI.Session(new Out(new StringWriter),new Out(new StringWriter))
    s.env=_=>None; s.ambient_root=()=>root
    val n=new CLI.Ns; n.positional("files")=tokens
    CLI.load_sections(s,n,windows=windows)
  }
  def countOf(text:String, part:String):Int = text.sliding(part.length).count(_ == part)
  def entry(sec: Theory_Section, name: String): Entry = sec.entries.find(_.name == name).get
  def source(text: String, name: String = "T")(body: (JPath, Theory_Section) => Unit): Unit =
    withProject(Seq((name + ".thy") -> text)) { root =>
      body(root, load(root).find(_.theory == name).get)
    }
  def query(root: JPath, words: String*): CliResult = cli(words.toList, root)
  def names(output: String): List[String] = lines(output).flatMap { line =>
    "^(?:--- )?(\\S+) \\([A-Z]+\\) —".r.findFirstMatchIn(line).map(_.group(1))
  }
  def locus(token: String): Option[(String, Int, Option[Int])] = Commands.parse_locus(token)
  def printed_loci(out: String, context: Boolean = false): List[String] =
    lines(out).flatMap(_.trim.split("\\s+").toList).filter { tok =>
      val tail = tok.split(":", -1).last
      tok.contains(":") && tail.nonEmpty && tail.endsWith("-") == context &&
        tail.stripSuffix("-").matches("[0-9]+")
    }
  /** `tests/support.py`'s `blank_terms`: the outer view of a one-line header,
    * simulated -- every `"..."` term and `\<open>...\<close>` cartouche
    * blanked to spaces, which is what `Theory_Section.outer_source` does to the
    * same characters.  For pinning the site grammars
    * (`Sites.expression_heads`, `Sites.code_attrs`, ...) on strings: each
    * takes the live text and the outer text of one span. */
  def blank_terms(s: String): String = {
    val buf = new StringBuilder
    var i = 0
    var quoted = false
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '"') { quoted = !quoted; buf += ' '; i += 1 }
      else if (!quoted && s.startsWith("\\<open>", i)) {
        val e = Entries.balanced_end(s, "\\<open>", "\\<close>", i)
        val stop = if (e < 0) s.length else e
        while (i < stop) { buf += ' '; i += 1 }
      }
      else { buf += (if (quoted) ' ' else c); i += 1 }
    }
    buf.toString
  }
}
