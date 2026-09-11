package isabelle.query.regression

import isabelle.query.*
import isabelle.{Token, Thy_Header}
import java.io.StringWriter
import java.nio.file.{Files, Path as JPath, Paths}
import java.util.regex.Pattern
import ParserValues.*

/** Actual-value adapters call production methods. Collection/tuple reshaping
  * is explicit; no fallback operation can manufacture an empty actual value.
  */
private[regression] object ParserBridge {
  def spans(s: Regions.Spans): Map[Int,List[(Int,Int)]] =
    (0 until s.bound.length-1).filterNot(s.is_empty).map(i => (i+1) -> s.at(i)).toMap

  def section(text: String, theory: String = "Test"): Theory_Section =
    {
      // Native header lexer supplies the basename expected by header_keywords;
      // the Python fixture helper used a random basename without this check.
      val headerName = Token.explode(Thy_Header.bootstrap_keywords,text)
        .dropWhile(!_.is_command("theory")).drop(1).find(_.is_proper).map(_.content)
        .getOrElse("Fixture")
      TestSupport.withProject(Seq((headerName + ".thy") -> text)) { root =>
      val path = root.resolve(headerName + ".thy")
      TestSupport.parse(text, theory, path.toString, Theory.header_keywords(path))
      }
    }
  def keywordTable(lines: Array[String]): Map[String,String] =
    TestSupport.withProject(Seq("T.thy" -> lines.mkString("\n"))) { root =>
      Theory.header_keywords(root.resolve("T.thy"))
    }
  def enclosing(sections: List[Theory_Section], loci: List[String]): (String,String) = {
    val out=new StringWriter; val err=new StringWriter
    Commands.cmd_enclosing(new Out(out),new Out(err),sections,loci,"entry")
    (out.toString,err.toString)
  }
  def constant(name: String): V = V(name match {
    case "cli.RULE_LABEL_RE" => Entries.RULE_LABEL_RE
    case "cli._LOCALE_LABEL_RE" => Entries.LOCALE_LABEL_RE
    case "cli._SELECTOR_RE" => Entries.SELECTOR_RE
    case "isa_ns.KEYWORDS" => Namespace.census.keywords
    case _ => unsupported("constant " + name)
  })

  def call(name: String, a: Vector[V], kw: Map[String,V]): V = {
    def arg(i: Int, key: String, default: V): V = kw.getOrElse(key,a.lift(i).getOrElse(default))
    def lines(i: Int): Array[String] = a(i).strings.toArray
    def sections: List[Theory_Section] = a(0).sections
    name match {
      case "section_from" => V(section(a(0).str,arg(1,"theory",V("Test")).str))
      case "names" => V(a(0).section.entries.map(_.name))
      case "tags_by_name" => V(a(0).section.entries.map(e => e.name -> e.tag).toMap)
      case "cli._parse_name" => V(Entries.parse_name(a(0).str))
      case "cli._parse_def_name" => V(Entries.parse_def_name(a(0).str))
      case "cli._parse_typedecl_name" => V(Entries.parse_typedecl_name(a(0).str))
      case "parsing._balanced_end" =>
        if (kw.get("quote_aware").exists(_.truth)) unsupported("quote-aware helper requires the dedicated production induction-scanner test")
        V(Entries.balanced_end(a(0).str,a(1).str,a(2).str,arg(3,"start",V(0)).int))
      case "parsing._balanced_paren_end" => V(Entries.balanced_paren_end(a(0).str))
      case "parsing._balanced_cartouche_end" => V(Entries.balanced_cartouche_end(a(0).str))
      case "scan" | "cli.scan_keywords" | "parsing.scan_keywords" => V(keywordTable(lines(0)))
      case "cli.extract_entries" =>
        val table=kw.get("custom").map(_.map.map((k,v) => k.str -> v.str)).getOrElse(Map.empty)
        V(Theory.parse_source("Test",Paths.get("Fixture.thy"),lines(0),table).entries)
      case "parsing.extract_text_blocks" => V(Entries.extract_text_blocks(lines(0)))
      case "parsing.extract_comment_ranges" => V(Entries.extract_comment_ranges(lines(0)))
      case "parsing.extract_sections" => V(Entries.extract_sections(lines(0),a.lift(1).map(_.seq.map(x => (x.at(V(0)).int,x.at(V(1)).int)).toList).getOrElse(Nil)))
      case "parsing.extract_nonisar_ranges" =>
        V(Theory.parse_source("Test",Paths.get("Fixture.thy"),lines(0)).nonisar_ranges)
      case "parsing.scan_regions" =>
        val sec=Theory.parse_source("Test",Paths.get("Fixture.thy"),lines(0))
        vs(V(spans(sec.regions.nonisar)), V(spans(sec.regions.notes)),
          V(spans(sec.regions.inner)),V(sec.regions.open_at))
      case "model._blank_spans" =>
        V(Model.blank_spans(a(0).str,a(1).seq.map(x => (x.at(V(0)).int,x.at(V(1)).int)).toList))
      case "cli._locale_facts" => V(Entries.locale_facts(lines(0),a(1).int,a(2).int,a(3).str))
      case "cli._and_siblings" => V(Entries.and_siblings(lines(0),a(1).int,a(2).int,a(3).str,a(4).str))
      case "cli.render_entry" => V(Render.render_entry(a(0).section,a(1).entry,
        comments=kw.getOrElse("comments",V("on")).str))
      case "cli._format_extent" => V(Render.format_extent(a(0).entry))
      case "cli._locus_role" => V(Commands.locus_role(a(0).entry,a(1).int))
      case "cli._enclosing_entry" => V(Commands.enclosing_entry(a(0).section,a(1).int))
      case "_user_pattern" => V(Commands.user_pattern(a(0).str))
      case "cli._build_line_index" => V(Usage_Graph.build_line_index(sections))
      case "cli._entry_at_line" =>
        val index=a(0).seq.map(row => (row.at(V(0)).int,row.at(V(1)).int,row.at(V(2)).entry)).toArray
        V(Usage_Graph.entry_at_line(index,a(1).int))
      case "cli._build_def_sites" => V(Usage_Graph.build_def_sites(sections,
        kw.get("names").map(_.strings.toSet)))
      case "cli._build_call_graph" | "graph._build_call_graph" =>
        V(Usage_Graph.build_call_graph(sections,
          drop_upto=kw.getOrElse("drop_upto",V(1)).int,
          derived=kw.getOrElse("derived",V(false)).truth, reach="all"))
      case "brute_force_call_graph" =>
        V(ParserReference.graph(sections,kw.getOrElse("drop_upto",V(1)).int,
          kw.getOrElse("derived",V(false)).truth))
      case "cli._compute_unused" =>
        V(Usage.compute_unused(a(0).raw.asInstanceOf[Usage_Graph.Call_Graph],a.lift(1).map(_.strings.toSet).getOrElse(Set.empty)))
      case "cli._find_callers" | "commands._find_callers" =>
        V(Usage.find_callers(sections,a(1).str,reach="all"))
      case "commands._resolve_binding" => V(Commands.resolve_binding(sections,a(1).str))
      case "graph._noise_spans" => V(Usage_Graph.noise_spans(a(0).section))
      case "cli._scan_methods" =>
        val (counts,located)=Usage_Graph.scan_methods(sections,None)
        vs(V(MethodCounts(counts.toMap)),V(located.map(x => (x.path,x.line_no,x.owner,x.text))))
      case "cli._grep_sections" =>
        V(Commands.grep_sections(sections,a(1).raw.asInstanceOf[Pattern]).map(h =>
          (h.path,h.line_no,h.text,h.owner,h.is_live,h.is_thy)))
      case "shape.analyze_proof" => V(Shape.analyze_proof(new Shape.Sec_Ctx(a(0).section),a(1).entry))
      case "len" => V(a(0).seq.size)
      case "list" => V.seq(a(0).seq)
      case "set" => V.set(a.headOption.map(_.seq).getOrElse(Vector.empty))
      case "dict" => a(0).raw match {
        case _: Map[?,?] => a(0)
        case _ => V.dict(a(0).seq.map(v => v.at(V(0)) -> v.at(V(1))))
      }
      case "sorted" => V.seq(a(0).seq.sortWith((x,y) => x.cmp("Lt",y)))
      case "next" => a(0).seq.headOption.getOrElse(throw new NoSuchElementException("empty generator"))
      case "enumerate" =>
        val start=arg(1,"start",V(0)).int
        V.seq(a(0).seq.zipWithIndex.map((v,i) => vs(V(i+start),v)))
      case "zip" =>
        val xs=a.map(_.seq); val n=xs.map(_.size).min
        V.seq((0 until n).map(i => vs(xs.map(_(i))*)))
      case "range" =>
        val (lo,hi)=if(a.size==1) (0,a(0).int) else (a(0).int,a(1).int)
        V((lo until hi).toVector)
      case "sum" => V(a(0).seq.map(_.int).sum)
      case "any" => V(a(0).seq.exists(_.truth))
      case "all" => V(a(0).seq.forall(_.truth))
      case "str" => V(a(0).str)
      case "re.compile" => V(Pattern.compile(a(0).str,Pattern.UNICODE_CHARACTER_CLASS))
      case "re.escape" =>
        // Independent stdlib-equivalent expected-side escaping, not Py.re_escape.
        val special="()[]{}?*+-|^$\\.&~# \t\n\r\u000b\f".toSet
        V(a(0).str.flatMap(c => if(special(c)) "\\"+c else c.toString))
      case _ => unsupported("function " + name)
    }
  }
  // Python Counter's missing-key zero is part of its documented DATA contract,
  // not a fallback for an unknown operation or unknown model field.
  final case class MethodCounts(values: Map[String,Int])
}
