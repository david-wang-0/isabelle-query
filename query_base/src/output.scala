/*  Title:      query_base/src/output.scala

Standard output, standard error, and the two things the CLI contract says
about them: a closed pipe is not an error, and an exit status is data.

`Out` writes UTF-8 straight to the file descriptor rather than through
`System.out`.  `PrintStream` swallows an `IOException` into a flag nobody
reads, so a `query grep … | head -1` would run to completion writing into a
closed pipe and exit 0 — where the reference implementation dies at 141, the
status a shell reports for a process killed by SIGPIPE.  Letting the write
throw is what makes the two agree.

`Exit_Code` is thrown rather than calling `System.exit` at the point of
failure: buffered output written before the failure must still reach the
terminal, and only the top level knows when the buffer is empty.
*/

package isabelle.query


import java.io.{BufferedWriter, FileDescriptor, FileOutputStream, IOException,
  OutputStreamWriter, Writer}
import java.nio.charset.StandardCharsets


final case class Exit_Code(code: Int) extends RuntimeException(null, null, false, false)

/* A downstream reader closed the pipe.  Distinct from any other I/O failure
   because the exit status differs: 141, not 2. */
final class Broken_Pipe extends RuntimeException(null, null, false, false)


class Out(writer: Writer) {
  private def guard[A](body: => A): A =
    try body
    catch { case _: IOException => throw new Broken_Pipe }

  def print(s: String): Unit = guard(writer.write(s))

  def println(s: String): Unit = guard { writer.write(s); writer.write("\n") }

  def println(): Unit = println("")

  def flush(): Unit = guard(writer.flush())
}


object Out {
  private def fd_writer(fd: FileDescriptor): Writer =
    new BufferedWriter(
      new OutputStreamWriter(new FileOutputStream(fd), StandardCharsets.UTF_8), 1 << 16)

  def stdout: Out = new Out(fd_writer(FileDescriptor.out))

  /* stderr is unbuffered in the sense that matters here: a diagnostic must
     survive the exit that follows it, so it is flushed on every line. */
  def stderr: Out =
    new Out(new OutputStreamWriter(new FileOutputStream(FileDescriptor.err),
      StandardCharsets.UTF_8)) {
      override def println(s: String): Unit = { super.println(s); flush() }
    }
}


/* The uniform flag bundle threaded from the CLI into each command. */
final case class Flags(
  mode: String = "first",          // first / all / count / names
  verbatim: Boolean = false,
  statement: Boolean = false,      // find: match the statement; show: render it
  comments: String = "on",         // on / off / only
  context: Int = 2,
  with_comments: Boolean = false,
  recursive: Boolean = false,
  external: Boolean = false,
  sorts: Boolean = false,        // instances / codeqs: show the WRITTEN sorts
  by_theory: Boolean = false,      // --by-theory (unused)
  roots: Boolean = false,          // --roots (unused)
  keep: Set[String] = Set.empty,   // --keep (unused: live roots)
  drop_names_upto: Int = Usage_Graph.DROP_NAMES_UPTO,  // --drop-names-upto (call graph)
  /* --reach: `closure` scopes citation attribution by what the citing theory
     can SEE, `name` matches by name alone.  A VALUE rather than a global, so
     the library caller, the plugin and the warm server all get the same
     default without anything to rebind. */
  reach: String = Reach.DEFAULT_MODE
)
