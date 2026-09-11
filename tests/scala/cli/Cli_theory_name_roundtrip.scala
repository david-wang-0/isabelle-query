package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_theory_name_roundtrip {
  def run(): Unit = {
    test("test_theory_name_roundtrip.PathSpelledTheoryName.test_an_unknown_nested_name_is_still_unresolved") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
check(Commands.resolve_theory(sections,"LK/Nope").isEmpty)
    }
    }
    test("test_theory_name_roundtrip.PathSpelledTheoryName.test_every_section_name_resolves_to_itself") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
for(s<-sections) check(Commands.resolve_theory(sections,s.theory).get eq s)
    }
    }
    test("test_theory_name_roundtrip.PathSpelledTheoryName.test_the_printed_name_resolves") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
check(Commands.resolve_theory(sections,"LK/Propositional").get eq nested)
    }
    }
    test("test_theory_name_roundtrip.TheOtherFormsStillWork.test_a_bare_name_still_resolves") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
check(Commands.resolve_theory(sections,"Plain").get eq plain)
    }
    }
    test("test_theory_name_roundtrip.TheOtherFormsStillWork.test_a_real_path_resolves_to_its_section") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
check(Commands.resolve_theory(sections,plain.path.toString).get eq plain)
    }
    }
    test("test_theory_name_roundtrip.TheOtherFormsStillWork.test_an_exact_name_beats_the_stem") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
val decoy=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Propositional","Propositional.thy");check(Commands.resolve_theory(List(decoy,nested),"LK/Propositional").get eq nested)
    }
    }
    test("test_theory_name_roundtrip.TheOtherFormsStillWork.test_case_insensitive_bare_name_still_resolves") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
check(Commands.resolve_theory(sections,"plain").get eq plain)
    }
    }
    test("test_theory_name_roundtrip.TheOtherFormsStillWork.test_the_stem_fallback_survives") {
val nested=parse(CliFixtures.test_theory_name_roundtrip_SRC,"LK/Propositional","LK/Propositional.thy");val plain=parse(CliFixtures.test_theory_name_roundtrip_OTHER,"Plain","Plain.thy");val sections=List(nested,plain)
    locally {
check(Commands.resolve_theory(sections,"some/where/Plain.thy").get eq plain)
    }
    }
  }
}
