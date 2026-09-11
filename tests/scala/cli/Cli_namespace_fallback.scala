package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_namespace_fallback {
  def run(): Unit = {
    test("test_namespace_fallback.BindCommittedFallback.test_hol_binds_broad_table") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
check(!Namespace.pure.methods("auto"));val t=bind(List("Multitape_Alphabet_Enlargement"->"Multitape_TM_Substrate"))._1;check(t.methods("auto"));check(t.methods("blast"))
    }
    }
    test("test_namespace_fallback.BindCommittedFallback.test_nonhol_binds_pure_floor_and_warns") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
check(Namespace.census.methods("auto"));val (t,msg)=bind(List("Recursion-Addition"->"ZF"));check(!t.methods("auto"));equal(t.methods,Namespace.pure.methods);contains(msg,"ZF");contains(msg,"not HOL")
    }
    }
    test("test_namespace_fallback.BindCommittedFallback.test_pure_only_project_is_silent_but_still_binds") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
check(Namespace.census.methods("auto"));val (t,msg)=bind(List("SpecCheck"->"Pure"));equal(msg,"");check(!t.methods("auto"))
    }
    }
    test("test_namespace_fallback.IsKnownNonHolBase.test_hol_and_unknown_are_not_flagged") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(b<-List("HOL","HOL-Library","HOL-Analysis","Multitape_TM_Substrate","Collections")) check(!Namespace.is_known_nonhol_base(b),b)
    }
    }
    test("test_namespace_fallback.IsKnownNonHolBase.test_positively_non_hol") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(b<-List("Pure","ZF","ZF-Constructible","FOL","FOLP","CTT","Sequents")) check(Namespace.is_known_nonhol_base(b),b)
    }
    }
    test("test_namespace_fallback.UseBroadFallback.test_empty_project_defaults_broad") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(p<-List(List())) equal(bind(p)._1,Namespace.census)
    }
    }
    test("test_namespace_fallback.UseBroadFallback.test_hol_sub_session_out_of_scope_parent") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(p<-List(List(("Multitape_Alphabet_Enlargement","Multitape_TM_Substrate")))) equal(bind(p)._1,Namespace.census)
    }
    }
    test("test_namespace_fallback.UseBroadFallback.test_hol_whole_project_chains_to_hol") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(p<-List(List(("Multitape_Alphabet_Enlargement","Multitape_TM_Substrate"),("Multitape_TM_Substrate","HOL")))) equal(bind(p)._1,Namespace.census)
    }
    }
    test("test_namespace_fallback.UseBroadFallback.test_mixed_project_vetoes_broad") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(p<-List(List(("HolPart","HOL"),("ZfPart","ZF")))) equal(bind(p)._1,Namespace.pure)
    }
    }
    test("test_namespace_fallback.UseBroadFallback.test_pure_project_vetoes_broad") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(p<-List(List(("SpecCheck","Pure")))) equal(bind(p)._1,Namespace.pure)
    }
    }
    test("test_namespace_fallback.UseBroadFallback.test_zf_project_vetoes_broad") {
def bind(parents:List[(String,String)]):(Namespace.Table,String)=withProject(Seq("ROOT"->parents.map((n,p)=>s"session \"$n\" = \"$p\" +\n").mkString)){root=>
val w=new java.io.StringWriter;val s=new CLI.Session(new Out(w),new Out(new java.io.StringWriter));s.env=_=>None;s.ambient_root=()=>root
(CLI.resolve_namespace(s,"methods"),w.toString)
}
    locally {
for(p<-List(List(("Recursion-Addition","ZF")),List(("Forcing","ZF-Constructible")))) equal(bind(p)._1,Namespace.pure)
    }
    }
  }
}
