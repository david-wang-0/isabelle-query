r"""A theory name the tool PRINTS has to resolve when handed back.

A ROOT may address a theory in a subdirectory by path — there is no per-theory
`in` clause in the grammar — so `theories "LK/Propositional"` is ordinary
Isabelle:

    Locale_Test/Locale_Test   (FOL)
    LK/Propositional          (Sequents)
    ex/Typechecking           (CTT)
    Simple/Reach              (HOL-UNITY)

Discovery carries such a theory under that spelling, and `summary` prints it.
`_resolve_theory` then branched on `"/" in name` straight to the PATH form,
tried the filesystem, fell back to the stem (`Locale_Test`), matched no
section, and returned None — so:

    $ query -R $ISABELLE/src/FOL summary | grep /
    | Locale_Test/Locale_Test | 24 | 0 | 2 | 0 |  |

    $ query -R $ISABELLE/src/FOL theory "Locale_Test/Locale_Test"
    Theory 'Locale_Test/Locale_Test' not found.  Known theories:
      ...
      Locale_Test/Locale_Test          <- it is IN the list it just printed

A name listed as known and rejected as input is the sharp end of it: the tool
disagrees with itself inside one command's output.  It also breaks the
round-trip convention the locus grammar rests on — the tool's output is
supposed to be valid input.

The fix is to try the whole argument as a NAME before giving up, so a
separator in a theory name stops being fatal.  Deliberately NOT fixed here:
whether such a theory should be *called* `Locale_Test/Locale_Test` at all.
Isabelle's `Thy_Header.import_name` takes the last segment, so arguably it
should be `Locale_Test` — but that renames rows in `summary`, in every locus
and in `theory`'s own listing, and it is a separate change with its own corpus
diff.  This one only makes the tool accept what it already emits.

Related: David Wang's Scala port reaches the same conclusion from the import
side and records the naming half as `[theory-name-leaf]`; its reachability
filter carries a leaf alias so an import across such an edge is not silently
pruned.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402
from isabelle_query.commands import _resolve_theory  # noqa: E402

SRC = r"""theory Propositional
imports Main
begin

lemma triv: \<open>True\<close> by simp

end
"""

OTHER = r"""theory Plain
imports Main
begin

lemma also_triv: \<open>True\<close> by simp

end
"""


class PathSpelledTheoryName(unittest.TestCase):

    def setUp(self):
        self.nested = section_from(SRC, "LK/Propositional")
        self.plain = section_from(OTHER, "Plain")
        self.sections = [self.nested, self.plain]

    def test_the_printed_name_resolves(self):
        self.assertIs(_resolve_theory(self.sections, "LK/Propositional"),
                      self.nested)

    def test_every_section_name_resolves_to_itself(self):
        """The round-trip property, stated over the whole index."""
        for s in self.sections:
            self.assertIs(_resolve_theory(self.sections, s.theory), s,
                          f"{s.theory} does not resolve to itself")

    def test_an_unknown_nested_name_is_still_unresolved(self):
        self.assertIsNone(_resolve_theory(self.sections, "LK/Nope"))


class TheOtherFormsStillWork(unittest.TestCase):
    """The name form must not shadow the path form it now follows."""

    def setUp(self):
        self.nested = section_from(SRC, "LK/Propositional")
        self.plain = section_from(OTHER, "Plain")
        self.sections = [self.nested, self.plain]

    def test_a_real_path_resolves_to_its_section(self):
        self.assertIs(_resolve_theory(self.sections, str(self.plain.path)),
                      self.plain)

    def test_the_stem_fallback_survives(self):
        # `some/where/Plain.thy` is not a real path here, so it falls back to
        # the stem — the behaviour that existed before this change.
        self.assertIs(_resolve_theory(self.sections, "some/where/Plain.thy"),
                      self.plain)

    def test_a_bare_name_still_resolves(self):
        self.assertIs(_resolve_theory(self.sections, "Plain"), self.plain)

    def test_case_insensitive_bare_name_still_resolves(self):
        self.assertIs(_resolve_theory(self.sections, "plain"), self.plain)

    def test_an_exact_name_beats_the_stem(self):
        """`A/B` must prefer the section actually called `A/B`."""
        decoy = section_from(OTHER, "Propositional")
        sections = [decoy, self.nested]
        self.assertIs(_resolve_theory(sections, "LK/Propositional"),
                      self.nested)


if __name__ == "__main__":
    unittest.main()
