r"""Names declared by `axiomatization` — constants and axiom labels.

`axiomatization` declares constants before its `where` and labelled axioms
after it, and every one of those names is citable:

    axiomatization
      f :: "nat \<Rightarrow> nat" and
      Cap :: "nat"
    where
      ax1: "f 0 = 0" and
      Upper: "f 1 = 1"

Two things lost names here, and the first hid the second.  `where` unindented
matched `TOPLEVEL_RE` (`^[a-z]`), so the scan treated it as the *next command*
and stopped — dropping every labelled axiom after it, whatever it was called.
Once that is fixed the name pattern is reached, and it was `[a-z_]+`: no
capital, no digit, no prime, so `Upper` / `ax1` / `f'` matched nothing either.

Every layout below is taken from the AFP, cited by file and line, because the
bug was layout-dependent — a fixture that only wrote `where` indented would
have passed throughout.  Corpus effect, measured by
`scripts/probe_axiom_names.py` over all 962 entries: named AXIOM entries
108 -> 700, over the same 140 theories and the same 374 `axiomatization`
commands, and strictly additive (no name the old scan found was lost).
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import section_from  # noqa: E402

HEAD = "theory T imports Main begin\n"
FOOT = "\nend\n"


def _axioms(snippet):
    """`{name: line}` for the AXIOM entries of `snippet`, umbrella excluded."""
    sec = section_from(HEAD + snippet + FOOT)
    return {e.name: e.thy_line for e in sec.entries
            if e.tag == "AXIOM" and e.name != "axiomatization"}


class WhereDoesNotEndTheCommand(unittest.TestCase):
    """`where` continues the command; unindented it used to end the scan."""

    def test_where_in_column_zero(self):
        # The canonical layout, and the one that broke: `where` at column 0.
        got = _axioms(r'''
axiomatization
  f :: "nat \<Rightarrow> nat" and
  Cap :: "nat"
where
  lower: "f 0 = 0" and
  Upper_case: "f 1 = 1" and
  ax1: "f 2 = 2" and
  prime': "f 3 = 3"
''')
        self.assertEqual(sorted(got), ["Cap", "Upper_case", "ax1", "f",
                                       "lower", "prime'"])

    def test_where_indented(self):
        # Same declaration, `where` indented, so TOPLEVEL_RE never fired and
        # the scan ran to the end — and `lower` was *still* lost, because it
        # shares the `where` line.  Two independent ways to lose a label, which
        # is why the keyword is stripped and re-read rather than skipped.
        got = _axioms(r'''
axiomatization
  f :: "nat"
  where lower: "f = 0"
''')
        self.assertEqual(sorted(got), ["f", "lower"])

    def test_bare_where_line_then_labels(self):
        # AndersonProof.thy:28-31 (Types_Tableaus_and_Goedels_God): the command
        # line ends in `where`, the labels follow, and two of the three start
        # with a capital.  `A1a:"..."` has no space before its colon.
        got = _axioms(r'''
axiomatization where
  A1a:"\<lfloor>P\<rfloor>" and
  A2: "\<lfloor>Q\<rfloor>" and
  T2: "\<lfloor>R\<rfloor>"
''')
        self.assertEqual(sorted(got), ["A1a", "A2", "T2"])


class NameOnTheCommandLine(unittest.TestCase):
    """The `axiomatization` line itself may already carry the first name."""

    def test_label_after_where_on_the_command_line(self):
        # Consensus_Types.thy:11 (Consensus_Refined) — 191 AFP occurrences of
        # this shape.  The label shares the command line, so a scan that starts
        # one line below it never sees the name at all.
        got = _axioms(r'''
axiomatization where process_finite:
  "OFCLASS(process, finite_class)"
''')
        self.assertEqual(sorted(got), ["process_finite"])

    def test_constant_on_the_command_line_with_mixfix(self):
        # Analysis_OCL.thy:148-159 (Featherweight_OCL): a constant with a
        # mixfix template on the command line, `where` sharing a line with the
        # first label, and `and` sharing a line with the second.
        got = _axioms(r'''
axiomatization contents :: "Person \<Rightarrow> Set_Integer" (\<open>(1(_).contents'('))\<close> 50)
where contents_def:
"(self .contents()) = (\<lambda> \<tau>. SOME res. res)"
and cp0_contents:"(X .contents()) \<tau> = ((\<lambda>_. X \<tau>) .contents()) \<tau>"
''')
        self.assertEqual(sorted(got), ["contents", "contents_def",
                                       "cp0_contents"])

    def test_umbrella_and_head_name_share_a_line(self):
        # Regression guard, not a naming case.  When the command line carries a
        # name, two entries land on one `thy_line` — which `_attach_preambles`
        # and `_attach_annotations` did not allow: they sorted `(line, Entry)`
        # tuples, so a tie fell through to comparing `Entry`s and raised
        # `TypeError` mid-parse.  The probe swallowed that as "fewer axioms" on
        # 66 theories.  A `text` preamble and a trailing `\<comment>` are here
        # to make both attachers actually run.
        sec = section_from(HEAD + r'''
text \<open>Finiteness is needed for the maximum.\<close>
axiomatization where process_finite: \<comment> \<open>a note\<close>
  "OFCLASS(process, finite_class)"
''' + FOOT)
        umbrella = next(e for e in sec.entries if e.name == "axiomatization")
        at = [e.name for e in sec.entries if e.thy_line == umbrella.thy_line]
        self.assertEqual(at, ["axiomatization", "process_finite"])


class SeveralNamesOnOneLine(unittest.TestCase):
    """`and` separates items *within* a line as readily as it ends one.

    The residue [record-fields] left behind: the scan matched a name once at
    the start of each line, so everything after the first `and` was lost.
    Only 7 names corpus-wide — authors nearly always break the line — but the
    layout is legal, and a name the tool cannot find has no declaration site to
    exclude, so its own definition reads as a citation of itself.
    """

    def test_constants_sharing_a_line(self):
        got = _axioms(r'''
axiomatization f :: "nat" and g :: "nat" and h :: "nat"
''')
        self.assertEqual(sorted(got), ["f", "g", "h"])

    def test_labels_sharing_a_line_after_where(self):
        got = _axioms(r'''
axiomatization
where ax1: "f 0 = 0" and ax2: "g 0 = 0"
''')
        self.assertEqual(sorted(got), ["ax1", "ax2"])

    def test_they_all_report_the_line_they_are_on(self):
        sec = section_from(HEAD + r'''
axiomatization f :: "nat" and g :: "nat"
''' + FOOT)
        lines = {e.name: e.thy_line for e in sec.entries if e.tag == "AXIOM"}
        self.assertEqual(lines["f"], lines["g"])

    def test_and_inside_a_proposition_does_not_split_it(self):
        # Read on the outer view, so the term — and the `\<and>` connective,
        # whose letters the separator pattern would otherwise match — is
        # blanked before the scan.
        got = _axioms(r'''
axiomatization
where ax1: "P \<and> Q and R"
''')
        self.assertEqual(sorted(got), ["ax1"])


class NotEveryColonIsALabel(unittest.TestCase):

    def test_colon_inside_a_proposition_is_not_a_name(self):
        # Names are read off the OUTER view, where inner syntax is blanked, so
        # a wrapped type ascription inside a proposition cannot mint an entry.
        got = _axioms(r'''
axiomatization
where ax1: "(
    inner :: nat) = 0"
''')
        self.assertEqual(sorted(got), ["ax1"])

    def test_a_following_command_ends_the_scan(self):
        # `lemma foo:` must not be read as another axiom label.
        got = _axioms(r'''
axiomatization where ax1: "P"
lemma foo: "True" by simp
''')
        self.assertEqual(sorted(got), ["ax1"])


if __name__ == "__main__":
    unittest.main()
