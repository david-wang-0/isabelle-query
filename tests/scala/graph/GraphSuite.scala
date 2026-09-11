package isabelle.query.regression

object GraphSuite {
  def run(): Unit = {
    GraphBfsDepths.run()
    GraphCallGraph.run()
    GraphCitationReach.run()
    GraphFactCitations.run()
    GraphGraphExport.run()
    GraphMethods.run()
    GraphNameIsNotIdentity.run()
    GraphPerf.run()
    GraphShadowedNames.run()
    GraphSymbolBodyTokens.run()
    GraphTheoryRefs.run()
    GraphUnusedCascadeDepth.run()
  }
}
