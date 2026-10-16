package isabelle.query.regression
import isabelle.query.*
import java.io.StringWriter
import java.nio.file.{Files, Path as JPath, Paths}
import ParserApiFixtures.*
import TestSupport.*

private[regression] object ParserApi {
  private def api(id: String)(body: => Unit): Unit = test("test_api_surface."+id)(body)
  private def fixture: Theory_Section = Theory.parse_source("Doc",Paths.get("<test>"),Py.split_lines(FIXTURE)._1)
  private def project[A](body: JPath => A): A = withProject(Seq(
    "A.thy" -> DECLARER, "B.thy" -> USER,
    "ROOT" -> "session Demo = HOL +\n  theories\n    A\n    B\n"))(body)
  private def oneB(root: JPath): Theory_Section =
    Theory.parse_one("B",root.resolve("B.thy"),Theory.read(root.resolve("B.thy")),
      Theory.header_keywords(root.resolve("B.thy")))
  /* `api.parse_theory(..., root=)` [cut-span] is no new API here: the
     keyword table is a value, so the same answer is `Theory.plan(root)` (every
     header under the root, no body) composed with `parse_one` on the one
     file.  The port's equivalent of its ValueError on a root with nothing
     under it is that empty plan, which this composition refuses. */
  private def rootedB(root: JPath): Theory_Section = {
    val p = Theory.plan(root)
    if (p.found.isEmpty) throw new IllegalArgumentException("no theories under " + root)
    val path = root.resolve("B.thy")
    val session = p.found.map(_._1).find(f =>
      Files.exists(f.path) && Files.isSameFile(f.path, path)).flatMap(_.session)
    Theory.parse_one("B", path, Theory.read(path), p.table(Theory.header_keywords(path)), session)
  }
  private def cut: Theory_Section = Theory.parse_source("Cut",Paths.get("<test>"),Py.split_lines(CUT)._1)
  private def checkedRoot(root: JPath): List[Theory_Section] = {
    // Scala's public CLI.Session is the diagnosed root-loading boundary.
    // Unlike its lower-level Theory.parse_root, it refuses an unusable root.
    val s = new CLI.Session(new Out(new StringWriter),new Out(new StringWriter))
    s.root_override = Some(root)
    s.env = Map("ISABELLE_QUERY_NAMESPACE" -> "committed").get
    s.load_index()
  }
  val pythonReason = "Python __all__, import dependency and sys.modules mechanics have no Scala package analogue; retained in the original Python compatibility tier."
  val pythonIds = List(
    "TheExportedNames.test_every_name_in_all_resolves",
    "TheExportedNames.test_all_lists_exactly_the_surface",
    "ImportingThePackageStaysFree.test_the_package_import_does_not_load_parsing",
    "ImportingThePackageStaysFree.test_the_api_module_does_not_pull_the_cli",
    "TheModuleDagStillHolds.test_api_imports_stay_below_it")
  def run(): Unit = {
    pythonIds.foreach(id => disposition("test_api_surface."+id,"python-only",pythonReason))
    api("TheSpanFieldsAreTheContract.test_entry_carries_every_span_field") {
      val methods=classOf[Entry].getMethods.map(_.getName).toSet
      equal(ENTRY_SPAN_FIELDS.filterNot(methods),Nil)
      ENTRY_SPAN_FIELDS.foreach(name => ParserValues.V(fixture.entries.head).field(name))
      val unknown=raises(ParserValues.V(fixture.entries.head).field("unsupported_field"))
      check(unknown.isInstanceOf[AssertionError])
      contains(unknown.getMessage,"unsupported parser-port operation")
    }
    api("TheSpanFieldsAreTheContract.test_entry_carries_every_derived_span") {
      ENTRY_SPAN_PROPERTIES.foreach { name =>
        val value=ParserValues.V(fixture.entries.head).field(name)
        // `cut_span` is Python's `tuple | None`: an Option, Some on this fixture.
        if (name=="cut_span") {
          equal(classOf[Entry].getMethod(name).getReturnType,classOf[Option[?]],name)
          check(!value.isNone,name)
        }
        else {
          equal(classOf[Entry].getMethod(name).getReturnType,java.lang.Integer.TYPE,name)
          check(value.int > 0,name)
        }
      }
    }
    api("TheSpanFieldsAreTheContract.test_section_carries_every_span_field") {
      val methods=classOf[Theory_Section].getMethods.map(_.getName).toSet
      val mapped=SECTION_SPAN_FIELDS.map {
        case "nonisar_spans" | "inner_spans" => "regions"
        case name => name
      }
      equal(mapped.filterNot(methods),Nil)
      SECTION_SPAN_FIELDS.foreach(name => ParserValues.V(fixture).field(name))
      val unknown=raises(ParserBridge.call("unsupported_function",Vector.empty,Map.empty))
      check(unknown.isInstanceOf[AssertionError])
      contains(unknown.getMessage,"unsupported parser-port operation")
    }
    api("TheSpanFieldsAreTheContract.test_section_carries_every_view") {
      SECTION_VIEWS.foreach { name =>
        val m=if(name=="slice") classOf[Theory_Section].getMethod(name,Integer.TYPE,Integer.TYPE)
          else classOf[Theory_Section].getMethod(name)
        equal(m.getReturnType,classOf[Array[String]],name)
      }
    }
    api("TheSpansAreRight.test_the_preamble_is_attached") { equal(fixture.entries.head.preamble,Some((5,5))) }
    api("TheSpansAreRight.test_the_entry_span_starts_at_the_preamble") {
      val e=fixture.entries.head; equal(e.src_start,5);equal(e.thy_line,7)
    }
    api("TheSpansAreRight.test_the_proof_extent") {
      val e=fixture.entries.head;equal((e.proof_line,e.body_end_line),(10,12))
    }
    api("TheSpansAreRight.test_the_whole_extent_including_the_preamble") {
      val e=fixture.entries.head;equal((e.src_start,e.thy_end),(5,12));equal(e.line_count,8)
    }
    api("TheSpansAreRight.test_slice_round_trips_the_lines") { equal(fixture.slice(7,7).toList,List("lemma documented:")) }
    api("TheSpansAreRight.test_the_cut_span_is_preamble_through_qed") { equal(fixture.entries.head.cut_span,Some((5,12))) }
    def by_name(sec: Theory_Section): Map[String, Entry] = sec.entries.map(e => e.name -> e).toMap
    api("TheCutSpanHoldsOneEntry.test_a_trailing_text_block_is_left_behind") {
      val e=by_name(cut); val first=e("first")
      equal(first.cut_span.get._2,5)
      check(first.cut_span.get._2 < e("second").src_start)
    }
    api("TheCutSpanHoldsOneEntry.test_the_next_entry_takes_its_own_preamble") {
      equal(by_name(cut)("second").cut_span,Some((8,12)))
    }
    api("TheCutSpanHoldsOneEntry.test_one_line_declarations_have_no_cut_span") {
      val on_14=cut.entries.filter(_.thy_line==14)
      equal(on_14.length,2,"fixture no longer yields both entries")
      check(on_14.exists(_.name=="F1"))
      on_14.foreach { e => subcase("name="+e.name) { check(e.shares_line); equal(e.cut_span,None) } }
    }
    api("TheCutSpanHoldsOneEntry.test_entries_on_their_own_lines_are_not_flagged") {
      val e=by_name(cut)
      List("first","second").foreach(n => subcase("name="+n) { check(!e(n).shares_line) })
    }
    api("TheCutSpanHoldsOneEntry.test_an_unplaced_entry_has_no_cut_span") {
      equal(Entry("LEMMA","x","",thy_line=0).cut_span,None)
    }
    api("ParseRootAgreesWithTheCli.test_parse_root_sees_the_sibling_declaration") { project { root =>
      val secs=Theory.parse_root(root).map(s => s.theory -> s).toMap
      check(secs.contains("B"));check(secs("B").entries.exists(_.name=="gadget"))
    }}
    api("ParseRootAgreesWithTheCli.test_parse_theory_alone_does_not") { project { root =>
      Theory.parse_root(root)
      check(!oneB(root).entries.exists(_.name=="gadget"))
    }}
    api("ParseRootAgreesWithTheCli.test_parse_theory_leaves_the_table_as_it_found_it") { project { root =>
      val first=Theory.parse_root(root).map(s => s.theory -> s).toMap
      oneB(root)
      val again=Theory.parse_root(root).map(s => s.theory -> s).toMap
      equal(first("B").entries.map(_.name),again("B").entries.map(_.name))
    }}
    api("ParseRootAgreesWithTheCli.test_parse_theory_with_root_sees_the_sibling_declaration") { project { root =>
      oneB(root)
      val sec=rootedB(root)
      check(sec.entries.exists(_.name=="gadget"))
      equal(sec.session,Some("Demo"))
      val full=Theory.parse_root(root).map(s => s.theory -> s).toMap.apply("B")
      equal(sec.entries.map(e => (e.name,e.thy_line,e.cut_span)),full.entries.map(e => (e.name,e.thy_line,e.cut_span)))
    }}
    api("ParseRootAgreesWithTheCli.test_parse_theory_with_root_leaves_the_table_as_it_found_it") { project { root =>
      rootedB(root)
      check(!oneB(root).entries.exists(_.name=="gadget"))
    }}
    api("ParseRootAgreesWithTheCli.test_parse_theory_with_a_bad_root_raises") { project { root =>
      raises(rootedB(root.resolve("nope")))
      raises(checkedRoot(root.resolve("nope")))
      val empty=Files.createDirectory(root.resolve("empty"))
      raises(rootedB(empty))
      raises(checkedRoot(empty))
    }}
    api("ParseRootAgreesWithTheCli.test_two_roots_do_not_contaminate_each_other") { project { root =>
      val other=Files.createDirectory(root.resolve("other"))
      Files.writeString(other.resolve("C.thy"),"theory C\nimports Main\nbegin\nmydef stray :: \"bool\" where \"stray = True\"\nend\n")
      Files.writeString(other.resolve("ROOT"),"session Other = HOL +\n  theories\n    C\n")
      Theory.parse_root(root)
      val secs=Theory.parse_root(other).map(s => s.theory -> s).toMap
      check(!secs("C").entries.exists(_.name=="stray"),"previous root table survived")
    }}
    api("ParseRootAgreesWithTheCli.test_an_unreadable_root_raises_rather_than_returning_empty") { project { root =>
      raises(checkedRoot(root.resolve("nope")))
    }}
    api("ParseRootAgreesWithTheCli.test_a_root_with_no_theories_raises") { project { root =>
      raises(checkedRoot(Files.createDirectory(root.resolve("empty"))))
    }}
  }
}
