r"""A rendered entry is ONE block, and its preamble says whose it is.

Reported from a downstream project: `find --statement PAT` printed an
unattributed `--- preamble [499-510] ---` block ahead of the first match, "with
no indication which entry it belongs to — it reads as a separate hit."

Two things combined.  `render_entry` put a blank line between the preamble and
the entry header, and `_emit_matches` separates one match from the next with
exactly one blank line — so the two separators were indistinguishable and the
preamble parsed, to a reader or a scripted consumer, as a hit of its own.  It
also carried only line numbers, so even noticing it belonged to something did
not say to what.

The preamble stays ABOVE the header: `text \<open>...\<close>` precedes the
declaration it introduces, and reordering it would misreport the source.

Asserted structurally — block counts and adjacency, not the header wording —
because the bug was in the SHAPE of the output, and a containment assertion
passes on the broken version.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

THY = """theory Pre imports Main begin

text \\<open>Prose introducing the first lemma.\\<close>

lemma alpha: "P a"
  by simp

lemma beta: "P b"
  by simp

text \\<open>Prose introducing the third lemma.\\<close>

lemma gamma: "P c"
  by simp

end
"""


def entry(sec, name):
    [e] = [e for e in sec.entries if e.name == name]
    return e


class PreambleAttribution(unittest.TestCase):

    def setUp(self):
        self.sec = section_from(THY, theory="Pre")

    def _render(self, name):
        return cli.render_entry(self.sec, entry(self.sec, name))

    def test_the_fixture_has_the_preambles_the_test_assumes(self):
        # Guards the rest: if attachment ever changes, these fail loudly
        # rather than leaving the real assertions vacuously true.
        self.assertIsNotNone(entry(self.sec, "alpha").preamble)
        self.assertIsNone(entry(self.sec, "beta").preamble)
        self.assertIsNotNone(entry(self.sec, "gamma").preamble)

    def test_the_preamble_names_its_entry(self):
        head = self._render("alpha").splitlines()[0]
        self.assertIn("preamble", head)
        self.assertIn("alpha", head,
                      "a preamble block must say which entry it introduces")

    def test_an_entry_renders_as_one_block(self):
        # No blank line anywhere between the preamble and the declaration —
        # a blank line is the separator BETWEEN matches.
        self.assertNotIn("\n\n", self._render("alpha"))

    def test_the_preamble_precedes_the_header(self):
        lines = self._render("alpha").splitlines()
        pre = next(i for i, s in enumerate(lines) if "preamble" in s)
        hdr = next(i for i, s in enumerate(lines) if "(LEMMA)" in s)
        self.assertLess(pre, hdr, "source order puts the prose first")

    def test_a_match_list_has_one_block_per_match(self):
        # The reported symptom, end to end: three matches, three blocks.
        import io
        import contextlib
        flags = cli.CmdFlags(mode="all")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cli._emit_matches({"Pre": self.sec},
                              [entry(self.sec, n)
                               for n in ("alpha", "beta", "gamma")],
                              "P", flags)
        blocks = [b for b in buf.getvalue().split("\n\n") if b.strip()]
        self.assertEqual(len(blocks), 3,
                         "a preamble must not read as a match of its own:\n"
                         + buf.getvalue())

    def test_every_block_in_a_match_list_is_attributable(self):
        # Each block must name an entry on its FIRST line, so a consumer
        # splitting on blank lines can always say what it is looking at.
        import io
        import contextlib
        flags = cli.CmdFlags(mode="all")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cli._emit_matches({"Pre": self.sec},
                              [entry(self.sec, n)
                               for n in ("alpha", "beta", "gamma")],
                              "P", flags)
        for block in [b for b in buf.getvalue().split("\n\n") if b.strip()]:
            first = block.splitlines()[0]
            self.assertTrue(
                any(n in first for n in ("alpha", "beta", "gamma")),
                f"unattributed block: {first!r}")


if __name__ == "__main__":
    unittest.main()
