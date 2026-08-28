/*  Title:      query_base/src/theory.scala

One theory's parse, and a whole root's parse in parallel.

The order matters and is the same order the reference implementation uses:
the region scan runs FIRST, because one pass feeds three consumers — the
character columns (`live_source`), the whole-noise LINES derived from them (the
line-granular masks), and the declaration scan, which must not read a
commented-out `definition` or an ML `fun` as an entry.  Preambles are attached
before spans, because a preamble fixes an entry's `src_start` and `src_start`
is the boundary the entry ABOVE ends at.
*/

package isabelle.query


import isabelle.*

import java.nio.file.{Path => JPath}

import scala.collection.mutable


object Theory {
  /* A theory's own `keywords` clause, read by Isabelle's header parser.  Only
     the kinds that introduce a citable fact map to a tag; proof, diagnostic,
     document and load kinds must NOT create an entry. */
  def header_keywords(path: JPath): Map[String, String] =
    try {
      val node = Document.Node.Name(path.toString, theory = Discovery.theory_stem(path))
      val header = Thy_Header.read(node, Scan.char_reader(File.read(Path.explode(path.toString))))
      (for {
        (name, spec) <- header.keywords
        tag <- Entries.kind_family.get(spec.kind)
      } yield name -> tag).toMap
    }
    catch { case _: Throwable => Map.empty }

  def read(path: JPath): String = File.read(Path.explode(path.toString))

  def parse_one(theory: String, path: JPath, text: String,
    table: Map[String, String] = Map.empty, session: Option[String] = None
  ): Theory_Section = {
    val (lines, starts) = Py.split_lines(text)
    parse_lines(theory, path, text, lines, starts, table, session)
  }

  /* A section built from ALREADY-SPLIT lines — the stdin path, where the
     content never had a file to re-read.  Re-joining with `\n` is exactly what
     the reference implementation's `splitlines()` round trip amounts to: the
     universal-newline read has already normalised the breaks. */
  def parse_source(theory: String, path: JPath, lines: Array[String],
    table: Map[String, String] = Map.empty
  ): Theory_Section = {
    val text = lines.mkString("\n")
    /* The offsets are computed, not re-derived by splitting: a trailing EMPTY
       line survives here and would not survive a round trip through
       `split_lines`, which drops the empty tail a final break leaves — and the
       region scan indexes `starts` by line, so one line of drift is an
       out-of-bounds read rather than a wrong answer. */
    val starts = new Array[Int](lines.length)
    var off = 0
    for (i <- lines.indices) { starts(i) = off; off += lines(i).length + 1 }
    parse_lines(theory, path, text, lines, starts, table, None)
  }

  /* A NON-theory source (a Markdown memo passed to `grep`): no entry grammar,
     no outline, no text blocks.  The Isabelle declaration grammar does not
     apply to prose, and inventing entries for it would put fictional owners in
     the search output. */
  def parse_plain(theory: String, path: JPath, lines: Array[String]): Theory_Section =
    new Theory_Section(theory, path, Nil, lines, Regions.empty_result, is_thy = false)

  private def parse_lines(theory: String, path: JPath, text: String,
    lines: Array[String], starts: Array[Int],
    table: Map[String, String], session: Option[String]
  ): Theory_Section = {
    val regions = Regions.scan(text, lines, starts)
    val nonisar_ranges = Regions.nonisar_ranges(lines, regions.nonisar)
    val outer = Model.blank_all(lines, regions.inner)
    val live = Model.blank_all(lines, regions.nonisar)

    val entries =
      Entries.extract_entries(lines, outer, live, regions.open_at, nonisar_ranges, table)

    val text_blocks = Entries.extract_text_blocks(lines)
    /* Document bodies and ML come first because a heading is only a heading
       when a COMMAND introduces it: both heading scans take these spans as the
       places where one cannot start. */
    val prose = text_blocks ::: nonisar_ranges
    val outline = Entries.extract_sections(lines, prose)
    val heading_spans = Entries.extract_heading_spans(lines, prose)
    val comment_ranges = Entries.extract_comment_ranges(lines)
    val comment_lines = Entries.extract_comment_lines(lines, regions.notes)

    Entries.attach_preambles(entries, lines, text_blocks)
    Entries.compute_spans(entries,
      outline.map(_._3) :::
        Entries.structural_command_lines(lines, comment_ranges ::: nonisar_ranges, outer),
      lines.length)
    Entries.attach_annotations(entries, comment_lines)

    for (e <- entries) {
      e.theory = theory
      e.body_end_line =
        if (e.proof_line > 0) Entries.proof_extent(lines, e.proof_line, e.thy_end)
        else if (e.decl_end_line > 0) e.decl_end_line
        else e.thy_line
    }

    new Theory_Section(theory, path, entries, lines, regions,
      outline = outline, text_blocks = text_blocks, heading_spans = heading_spans,
      comment_ranges = comment_ranges, nonisar_ranges = nonisar_ranges,
      session = session)
  }


  /* A whole root, parsed in parallel.

     The custom-command table is a UNION over every discovered header, mirroring
     Isabelle's session-wide `Keywords.++`: a theory that USES `AOT_theorem` is
     parsed correctly even though the command is DECLARED in a different
     theory's header, which a per-file table cannot do.  It is therefore built
     in a first pass, before any body is parsed. */
  final case class Plan(
    found: List[(Discovery.Found, Map[String, String])],
    union: Map[String, String]
  ) {
    def table(own: Map[String, String]): Map[String, String] =
      if (own.isEmpty) union else union ++ own
  }

  def plan(root_dir: JPath): Plan = {
    val owned =
      Par_List.map((f: Discovery.Found) =>
        (f, if (f.path.getFileName.toString.endsWith(".thy")) header_keywords(f.path)
            else Map.empty[String, String]),
        Discovery.theories(root_dir))
    Plan(owned, owned.foldLeft(Map.empty[String, String])(_ ++ _._2))
  }

  /* One theory, or nothing if it cannot be read or parsed — a corpus sweep
     must not stop at a single unreadable file. */
  def parse(f: Discovery.Found, table: Map[String, String]): Option[Theory_Section] =
    try Some(parse_one(f.name, f.path, read(f.path), table, f.session))
    catch { case _: Throwable => None }

  def parse_root(root_dir: JPath): List[Theory_Section] = {
    val p = plan(root_dir)
    Par_List.map((fk: (Discovery.Found, Map[String, String])) =>
      parse(fk._1, p.table(fk._2)), p.found).flatten
  }
}
