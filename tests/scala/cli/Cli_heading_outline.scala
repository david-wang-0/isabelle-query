package isabelle.query.regression

import isabelle.query.*
import java.nio.file.{Files, Path as JPath, Paths}
import TestSupport.*
import CliSupport.*

private[regression] object Cli_heading_outline {
  def run(): Unit = {
    test("test_heading_outline.OutlineDoesNotInventHeadings.test_a_textbook_citation_in_prose_is_not_a_chapter") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
val s=parse(CliFixtures.test_heading_outline_HEAD+"\ntext \\<open>\n  The correctness proof closely follows Kleinberg and Tardos:\n  chapter \"Dynamic Programming\".\n\\<close>\nlemma m: \"True\" by simp\n"+CliFixtures.test_heading_outline_FOOT);equal(Entries.extract_sections(s.source,s.text_blocks),Nil)
    }
    }
    test("test_heading_outline.OutlineDoesNotInventHeadings.test_the_dof_star_form_is_not_accepted") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
val s=parse(CliFixtures.test_heading_outline_HEAD+"\nsection*[morphisms::technical]\\<open>Proofs over Ontologies\\<close>\nlemma m: \"True\" by simp\n"+CliFixtures.test_heading_outline_FOOT);equal(Entries.extract_sections(s.source,s.text_blocks),Nil)
    }
    }
    test("test_heading_outline.OutlineSeesEverySpelling.test_every_spelling_is_found") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
equal(found.size,6)
    }
    }
    test("test_heading_outline.OutlineSeesEverySpelling.test_the_command_word_is_reported") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
equal(found.map(_._1),List("chapter","section","subsection","subsubsection","paragraph","subparagraph"))
    }
    }
    test("test_heading_outline.OutlineSeesEverySpelling.test_the_split_opener_is_reported_at_its_command_word") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
equal(sec.source(found.last._3-1).trim,"subparagraph")
    }
    }
    test("test_heading_outline.OutlineSeesEverySpelling.test_the_title_is_clean_of_its_delimiters") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
equal(found.map(_._2),List("Spaced ASCII cartouche","No space before the cartouche","Unicode cartouche","Indented","Quoted string","Split opener"))
    }
    }
    test("test_heading_outline.TheTwoConsumersAgree.test_same_heading_lines_from_both") {
val sec=parse(CliFixtures.test_heading_outline_ALL_SPELLINGS);val found=Entries.extract_sections(sec.source,sec.text_blocks)
    locally {
for(src<-List("theory T imports Main begin\n\nchapter \\<open>Spaced ASCII cartouche\\<close>\nsection\\<open>No space before the cartouche\\<close>\nsubsection ‹Unicode cartouche›\n  subsubsection \\<open>Indented\\<close>\nparagraph \"Quoted string\"\nsubparagraph\n  \\<open>Split opener\\<close>\nlemma m: \"True\" by simp\n\nend\n", "theory T imports Main begin\ntext \\<open>\n  see chapter \"Dynamic Programming\"\n\\<close>\nlemma m: \"True\" by simp\n\nend\n", "theory T imports Main begin\nsection \\<open>One that\n  wraps\\<close>\nsection \"Another that\nwraps\"\nlemma m: \"True\" by simp\n\nend\n")){val s=parse(src);equal(Entries.extract_sections(s.source,s.text_blocks).map(_._3).toSet,s.heading_spans.map(_._1).toSet)}
    }
    }
  }
}
