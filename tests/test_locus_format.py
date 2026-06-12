"""`callers` / `methods` / `grep` print a clean, round-trippable locus.

Option 1 of the locus-roundtrip design: the location is a marker-free
`theory:line` (the form `grep` / `methods --names` already used), the
owner is a separate `name (TAG) lo..hi` field (its span pastes into
`lines` / `enclosing`), and the dangling ripgrep match-colon + the jammed
`[in owner]` are gone.  Context lines (`callers -U`) keep rg's `-` marker
but still round-trip, because `_parse_locus` strips it.

All three located-hit commands render the owner through the one
`_owner_field` helper, so the span shows uniformly — `grep` joined them
after initially carrying a span-less `name (TAG)` inline.
"""

import contextlib
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, section_from  # noqa: E402

# `bar`'s proof cites `foo`, and both prove `by simp` — so this one theory
# exercises a caller hit (with context lines) and a method tally.
CALLS = """theory Calls imports Main begin

lemma foo: "(0::nat) = 0" by simp

lemma bar:
  "(1::nat) = 1"
  using foo by simp

end
"""


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


def _sec():
    return section_from(CALLS, "Calls")


def _entry(name):
    return next(e for e in _sec().entries if e.name == name)


def _match_loci(out):
    """First token of each non-context hit line (locus not ending in `-`)."""
    loci = []
    for line in out.splitlines():
        toks = line.split()
        if toks and toks[0].startswith("Calls:") and not toks[0].endswith("-"):
            loci.append(toks[0])
    return loci


class CallersFormat(unittest.TestCase):

    def _callers(self, name, context=0):
        f = cli.CmdFlags()
        f.context = context
        return _capture(cli.cmd_callers, [_sec()], name, f)

    def test_owner_field_carries_tag_and_span(self):
        bar = _entry("bar")
        out = self._callers("foo")
        self.assertIn(f"bar (LEMMA) {bar.thy_line}..{bar.thy_end}", out)

    def test_old_bracket_form_gone(self):
        self.assertNotIn("[in ", self._callers("foo"))

    def test_match_locus_is_clean_and_round_trips(self):
        bar = _entry("bar")
        loci = _match_loci(self._callers("foo"))
        self.assertEqual(len(loci), 1)
        loc = loci[0]
        self.assertFalse(loc.endswith(":"))          # no dangling rg marker
        file, lo, hi = cli._parse_locus(loc)
        self.assertEqual(file, "Calls")
        self.assertEqual(lo, hi)
        self.assertTrue(bar.thy_line <= lo <= bar.thy_end)   # inside the owner

    def test_no_dangling_match_colon_anywhere(self):
        # The old format was `Calls:LINE:` — a `theory:line:` token.
        out = self._callers("foo")
        self.assertIsNone(re.search(r"Calls:\d+:", out))

    def test_context_line_keeps_marker_and_round_trips(self):
        out = self._callers("foo", context=2)
        marked = [t for line in out.splitlines() for t in line.split()
                  if t.startswith("Calls:") and t.endswith("-")]
        self.assertTrue(marked)                      # rg `-` context marker kept
        for t in marked:
            self.assertIsNotNone(cli._parse_locus(t))  # ...and still parses


class MethodsFormat(unittest.TestCase):

    def _methods(self, name, mode="first"):
        f = cli.CmdFlags()
        f.mode = mode
        return _capture(cli.cmd_methods, [_sec()], name, f)

    def test_default_mode_owner_field(self):
        foo = _entry("foo")
        out = self._methods("simp")
        self.assertIn(f"foo (LEMMA) {foo.thy_line}..{foo.thy_end}", out)
        self.assertNotIn("[in ", out)                # old bracket form gone
        self.assertIsNone(re.search(r"Calls:\d+:", out))

    def test_names_mode_uses_same_owner_field(self):
        bar = _entry("bar")
        out = self._methods("simp", mode="names")
        self.assertIn(f"bar (LEMMA) {bar.thy_line}..{bar.thy_end}", out)


class GrepFormat(unittest.TestCase):
    """grep routes its owner column through the same `_owner_field`, so a
    match's owner carries the pasteable `lo..hi` span like callers/methods."""

    def _grep(self, pattern, mode="first"):
        f = cli.CmdFlags()
        f.mode = mode
        return _capture(cli.cmd_grep, [_sec()], pattern, f)

    def test_default_owner_field_carries_span(self):
        # `lemma foo:` is owned by foo; `using foo` (in bar's proof) by bar.
        foo, bar = _entry("foo"), _entry("bar")
        out = self._grep("foo")
        self.assertIn(f"foo (LEMMA) {foo.thy_line}..{foo.thy_end}", out)
        self.assertIn(f"bar (LEMMA) {bar.thy_line}..{bar.thy_end}", out)

    def test_names_mode_owner_field_carries_span(self):
        bar = _entry("bar")
        out = self._grep("foo", mode="names")
        self.assertIn(f"bar (LEMMA) {bar.thy_line}..{bar.thy_end}", out)


if __name__ == "__main__":
    unittest.main()
