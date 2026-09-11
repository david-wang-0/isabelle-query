package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_layout_surface {
  def run(): Unit = {
    test("test_layout_surface.BehaviourUpstreamDoesNotTest.test_document_tag_with_a_space") {

    locally {
withProject(Seq("T.thy" -> "theory % invisible T\nimports Bar\nbegin\nend\n")){ root=>equal(Discovery.thy_imports(root.resolve("T.thy")),List("Bar")) }
    }
    }
    test("test_layout_surface.BehaviourUpstreamDoesNotTest.test_quoted_document_tag") {

    locally {
withProject(Seq("T.thy" -> "theory %\"vis\" T\nimports Bar\nbegin\nend\n")){ root=>equal(Discovery.thy_imports(root.resolve("T.thy")),List("Bar")) }
    }
    }
    disposition("test_layout_surface.LayoutSurface.test_every_imported_name_resolves", "python-only", "Python import/export/AST boundary of the external isabelle_layout package; Scala Discovery is compiled into the component. Retain Python import-contract coverage.")
    disposition("test_layout_surface.LayoutSurface.test_query_imports_nothing_private_from_layout", "python-only", "Python import/export/AST boundary of the external isabelle_layout package; Scala Discovery is compiled into the component. Retain Python import-contract coverage.")
    disposition("test_layout_surface.LayoutSurface.test_the_private_surface_has_not_grown", "python-only", "Python import/export/AST boundary of the external isabelle_layout package; Scala Discovery is compiled into the component. Retain Python import-contract coverage.")
  }
}
