r"""The selector constants a `record` declares [record-fields].

    record ('n,'p,'ba) flowgraph_rec =
      edges :: "('n,'p,'ba) edge set"
      main  :: "'p"

`edges` and `main` are constants — total selector functions on the record type,
cited wherever the record is used — and none was indexed. 507 records in the
AFP declaring 1,824 fields. `[declared-names]` left this out on purpose: a
record's `=` introduces its *parent type* where a datatype's introduces its
alternatives, so pointing the constructor scan at a record reads the parent as a
constructor. It needs a scan of its own, which is what `_record_fields` is.

The name-level effect is in `test_declared_names.py`. What is pinned here is
the **body scan**, because that is where the corpus said the work actually was:
finding the fields is one regex, but reaching them meant four separate reasons a
record's body ended early, each of which silently cost every field the record
declared. Recall over the AFP went 0 -> 1,824 in four steps, and every fixture
below is one of them, cited by file and line.

The user-visible payoff is on both sides of the ledger. For AODV's `rreqs`:
`find` 12 -> 18 (the declaration became findable at all) and `callers` 86 -> 80
— six *fewer*, because a name the tool cannot find has no declaration site to
exclude, so the record's own field line was reported as a caller of the field.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402

from isabelle_query import commands  # noqa: E402

HEAD = "theory T imports Main begin\n"
FOOT = "\nend\n"


def _fields(snippet):
    """The field names of the snippet's single RECORD entry."""
    sec = section_from(HEAD + snippet + FOOT)
    rec = next(e for e in sec.entries if e.tag == "RECORD")
    return [n for n, kind in rec.bindings if kind == "field"]


class TheBodyScanReachesEveryField(unittest.TestCase):
    """Four ways a record's body used to end before its fields did."""

    def test_a_blank_line_does_not_end_the_field_list(self):
        # CakeML/generated/Lem_num.thy:31 — every Lem-generated record is
        # written this way.  A record has no `|` to pick the list back up after
        # a blank, so `_bar_continues` could not serve it; the marker is the
        # field's own `::`.  32 of 507 AFP records, reporting no fields at all.
        self.assertEqual(_fields(r'''
record 'a NumNegate_class=

  numNegate_method ::" 'a \<Rightarrow> 'a "

'''), ["numNegate_method"])

    def test_a_comment_between_fields_does_not_end_the_list(self):
        # Collections/ICF/DatRef.thy:43 — records are annotated field by field.
        # A `\<comment>` is not a command; it stands wherever a token can, so
        # one inside a field list continues it.  11 more records.
        self.assertEqual(_fields(r'''
record 'S while_algo =
  \<comment> \<open>Termination condition\<close>
  wa_cond :: "'S set"
  \<comment> \<open>Step relation\<close>
  wa_step :: "('S \<times> 'S) set"
'''), ["wa_cond", "wa_step"])

    def test_a_quoted_field_name_is_read_from_the_live_view(self):
        # Vcg/ex/XVcgEx.thy:15, SATSolverVerification/SatSolverCode.thy:22.  A
        # field is quoted when its spelling would not pass as a bare identifier
        # — and a quoted span is exactly what the outer view blanks, so this one
        # name has to come from the live view instead.
        self.assertEqual(_fields(r'''
record "globals" =
  "G_'"::"nat"
  "H_'"::"nat"
'''), ["G_'", "H_'"])

    def test_a_comment_before_a_quoted_field(self):
        # SatSolverCode.thy:22 needs both of the above at once: the comment is
        # only skipped if the scan can see that a field follows, and that field
        # is invisible on the outer view.
        self.assertEqual(_fields(r'''
record State =
  \<comment> \<open>Satisfiability flag\<close>
"getSATFlag" :: ExtendedBool
  \<comment> \<open>Formula\<close>
"getF"       :: Formula
'''), ["getSATFlag", "getF"])

    def test_a_field_may_share_the_records_own_name(self):
        # Algebra1.thy:4600 `record 'a carrier = carrier :: "'a set"`.  A type
        # and a constant are different things in different namespaces, so
        # unlike a datatype's constructor this is not excluded.
        self.assertEqual(_fields('''
record 'a carrier =
  carrier :: "'a set"
'''), ["carrier"])

    def test_the_span_covers_the_whole_record(self):
        # The body scan is not only about names: `record state =` at
        # `E_Aodv:16` once measured one line against the twenty it spans, so
        # `show` rendered the head alone and `largest` under-counted it.
        sec = section_from(HEAD + r'''
record point =
  x :: nat

  \<comment> \<open>note\<close>
  y :: nat
''' + FOOT)
        rec = next(e for e in sec.entries if e.tag == "RECORD")
        self.assertEqual((rec.thy_line, rec.decl_end_line), (3, 7))

    def test_a_type_at_the_end_of_a_line_is_not_the_next_fields_name(self):
        # `\s*` matches a newline, so scanning the joined span pairs an
        # unquoted type with the `::` of the *next* field across a comment line
        # (blank on the outer view).  13 phantom fields in the AFP, of which
        # `ExtendedBool` here was one.  The scan is per line for this reason.
        self.assertEqual(_fields(r'''
record State =
"getSATFlag" :: ExtendedBool
  \<comment> \<open>Formula\<close>
"getF" :: Formula
'''), ["getSATFlag", "getF"])


class FieldsAreCitableAndDeclared(unittest.TestCase):
    """Why this is filed as correctness, not as recall."""

    SRC = HEAD + r'''
record state =
  ip    :: "nat"
  rreqs :: "nat set"

lemma uses_it: "rreqs s = rreqs s" by simp
''' + FOOT

    def test_a_field_resolves_to_the_record_that_declares_it(self):
        sec = section_from(self.SRC)
        self.assertEqual(commands._resolve_binding([sec], "rreqs"),
                         ("state", "a field of"))

    def test_the_declaration_is_not_a_caller_of_itself(self):
        # The failure a missing name causes, not merely a miss: with no
        # declaration site to exclude, `rreqs :: "nat set"` — the field's own
        # declaration — was reported as a use of `rreqs`.  On AODV's real
        # `state` that was 6 phantom callers for this one field, one per
        # theory that redeclares the record.
        sec = section_from(self.SRC)
        lines = [ln for _thy, ln, _text in commands._find_callers([sec],
                                                                  "rreqs")]
        self.assertNotIn(5, lines, "the field's own declaration line")
        self.assertEqual(lines, [7])


class TheRecordGrammarIsNotTheDatatypeGrammar(unittest.TestCase):

    def test_a_record_declares_no_constructors(self):
        # The collision that keeps the two scans apart, from the other side:
        # nothing in a record is a constructor, discriminator or selector.
        sec = section_from(HEAD + '''
record cpoint = point +
  col :: nat
''' + FOOT)
        rec = next(e for e in sec.entries if e.tag == "RECORD")
        self.assertEqual({kind for _n, kind in rec.bindings}, {"field"})

    def test_a_datatype_declares_no_fields(self):
        sec = section_from(HEAD + 'datatype t = A "nat" | B\n' + FOOT)
        dt = next(e for e in sec.entries if e.tag == "DATATYPE")
        self.assertNotIn("field", {kind for _n, kind in dt.bindings})


if __name__ == "__main__":
    unittest.main()
