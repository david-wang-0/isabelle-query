package isabelle.query.regression

object ShapeSuite {
  def run(): Unit = {
    ShapeCore.run()
    ShapeCli.run()
    ShapeProvenance.run()
    ShapeInduction.run()
    ShapeHidden.run()
    ShapeInline.run()
  }
}
