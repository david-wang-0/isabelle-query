package isabelle.query.regression
import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import TestSupport.*

/** Opt-in port of all five corpus tests, retaining their full-tree loops,
  * 120-file reference subset and original ceilings. Never part of fast runs.
  */
private[regression] object ParserCorpus {
  val reason = "Opt-in full corpus assertions require ISABELLE_QUERY_CORPUS; AOT span case additionally requires AOT/AOT_PLM.thy."
  val methods = List("test_unparsed_name_rate_is_low","test_bodies_stay_inside_their_own_spans",
    "test_no_locale_prefix_leaks","test_custom_commands_bound_aot_spans",
    "test_fast_call_graph_matches_oracle_on_subset")
  def id(method: String): String = "test_corpus.Corpus."+method
  def run(): Unit = {
    methods.foreach(m => disposition(id(m),"optional",reason))
    for (setting <- sys.env.get("ISABELLE_QUERY_CORPUS") if setting.nonEmpty;
      root = Paths.get(setting) if Files.isDirectory(root)) {
      val stream=Files.walk(root)
      val files=try stream.iterator.asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".thy"))
        .toList.sortBy(_.toString) finally stream.close()
      if(files.nonEmpty) {
        val table=files.foldLeft(Map.empty[String,String])((m,p) => m ++ Theory.header_keywords(p))
        def section(p: JPath): Theory_Section =
          Theory.parse_one(p.getFileName.toString.stripSuffix(".thy"),p,Theory.read(p),table)
        def readable: List[Theory_Section] = files.flatMap { p =>
          try Some(section(p)) catch { case _: java.io.IOException => None }
        }
        test(id("test_unparsed_name_rate_is_low")) {
          var total=0;var unparsed=0
          for(sec <- readable;e <- sec.entries) { total+=1;if(e.name=="?") unparsed+=1 }
          check(total>0)
          check(unparsed.toDouble/total < 0.07,s"unparsed $unparsed/$total exceeds 7 percent")
        }
        test(id("test_bodies_stay_inside_their_own_spans")) {
          val over=files.flatMap { p =>
            val sec=try Some(section(p)) catch { case NonFatal(_) => None }
            sec.toList.flatMap(s => s.entries.filter(e => e.thy_end != 0 && e.body_end_line>e.thy_end)
              .map(e => s"${s.theory}:${e.thy_line} ${e.name} ${e.body_end_line}>${e.thy_end}"))
          }
          check(over.size<120,s"${over.size} span overlaps, first ${over.take(5)}")
        }
        test(id("test_no_locale_prefix_leaks")) {
          for(sec <- readable;e <- sec.entries if e.name!="?")
            check(!e.name.matches("\\(in\\s.*"),s"locale prefix ${e.name} in ${sec.path}")
        }
        files.find(_.toString.replace('\\','/').endsWith("AOT/AOT_PLM.thy")).foreach { path =>
          test(id("test_custom_commands_bound_aot_spans")) {
            val sec=section(path)
            val target=sec.entries.filter(_.name=="beta-C-cor:3")
            equal(target.size,1,"beta-C-cor:3 should be a single entry")
            val span=target.head.thy_end-target.head.thy_line+1
            check(span<50,s"AOT span $span was not collapsed")
            check(sec.entries.exists(_.name=="beta-C-cor:1"))
          }
        }
        test(id("test_fast_call_graph_matches_oracle_on_subset")) {
          val secs=files.take(120).map(section)
          val fast=Usage_Graph.build_call_graph(secs,reach="all")
          val ref=ParserReference.graph(secs)
          for((name,callers) <- fast.callers)
            check(callers.subsetOf(ref.callers.getOrElse(name,Set.empty)),s"invented callers for $name")
          val total=ref.callers.values.map(_.size).sum
          val dropped=ref.callers.iterator.map((n,cs) => (cs -- fast.callers.getOrElse(n,Set.empty)).size).sum
          check(dropped <= math.max(2,(total*0.005).toInt),s"dropped $dropped/$total caller edges")
        }
      }
    }
  }
}
