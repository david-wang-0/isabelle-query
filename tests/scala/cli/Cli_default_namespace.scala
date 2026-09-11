package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_default_namespace {
  def run(): Unit = {
    test("test_default_namespace.ExplicitTableSelection.test_the_default_still_governs_classify_identifier") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
val ctx=Shape.Classify_Ctx(Set.empty,Set.empty,Set.empty);equal(Shape.classify_identifier("auto",ctx),("const","syntax"));equal(Shape.classify_identifier("auto",ctx.copy(namespace=Namespace.pure)),("var","default"))
    }
    }
    test("test_default_namespace.ExplicitTableSelection.test_the_shape_method_axis_ignores_the_bound_table") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
check(!Namespace.pure.methods("auto"));val floor=pm("triv",Namespace.pure);check(Namespace.census.methods("auto"));val union=pm("triv",Namespace.census);equal(floor.steps.map(_.method),List("auto"));equal(floor.steps.map(_.method),union.steps.map(_.method));equal(Shape.trivial_frac(floor.steps),Some(1.0));equal(Shape.trivial_frac(floor.steps),Shape.trivial_frac(union.steps))
    }
    }
    test("test_default_namespace.ExplicitTableSelection.test_use_census_namespace_restores_the_default") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
val floor=Namespace.pure;check(!floor.methods("auto"));val restored=Namespace.census;equal(restored.methods,Namespace.CENSUS_METHODS);check(restored.methods("auto"))
    }
    }
    test("test_default_namespace.ExplicitTableSelection.test_use_pure_namespace_steps_down_to_the_floor") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
val t=Namespace.pure;equal(t.methods,Namespace.PURE_METHODS);check(!t.methods("auto"))
    }
    }
    test("test_default_namespace.ImportTimeDefault.test_default_is_the_broad_union_not_the_pure_floor") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
equal(Namespace.census.methods,Namespace.CENSUS_METHODS);equal(Namespace.census.attributes,Namespace.CENSUS_ATTRIBUTES);check(Namespace.census!=Namespace.pure)
    }
    }
    test("test_default_namespace.ImportTimeDefault.test_keywords_stay_the_pure_table") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
equal(Namespace.census.keywords,Namespace.pure.keywords)
    }
    }
    test("test_default_namespace.ImportTimeDefault.test_the_automation_methods_are_recognised") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
for(m<-List("auto","blast","metis","induct","force","fastforce")){check(!Namespace.pure.methods(m));check(Namespace.census.methods(m))}
    }
    }
    test("test_default_namespace.LibraryCallerGetsMethods.test_a_one_liner_by_auto_carries_its_method") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
equal(pm("triv").steps.map(_.method),List("auto"))
    }
    }
    test("test_default_namespace.LibraryCallerGetsMethods.test_structured_proof_methods_and_kinds") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
val p=pm("structured");equal(p.steps.map(_.method).filter(_.nonEmpty),List("blast","metis"));val counts=Shape.method_kind_counts(p.steps).toMap;equal(counts("search"),2);equal(counts.values.sum,2)
    }
    }
    test("test_default_namespace.LibraryCallerGetsMethods.test_trivial_frac_is_1_not_none") {
val sec=parse(CliFixtures.test_default_namespace_THY,"T","T.thy")
def pm(n:String,ns:Namespace.Table=Namespace.census):Shape.Proof_Metrics={val r=Shape.analyze_proof(new Shape.Sec_Ctx(sec),entry(sec,n),namespace=ns);check(r.nonEmpty);r.get}
    locally {
equal(Shape.trivial_frac(pm("triv").steps),Some(1.0))
    }
    }
  }
}
