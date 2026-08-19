r"""`find --and`: the entries matching EVERY pattern, once [find-conjunction].

Several PATTERNs default to a **disjunction** — run each search in turn, one
report per pattern, blank-line separated.  That is the "batch of searches"
idiom, and it replaces a shell `for` loop, so it stays the default.

The `find_theorems`-shaped question is the other one: *the* entry that mentions
all of these.  From a real hunt for a length lemma in a large corpus,
`find --statement length encode_entry` is useless — pattern 1 alone returns
every `length` in the corpus — so the answer comes out as
`query find encode | grep length`, which is the pipe this tool exists to
retire.

What is pinned here is that `--and` intersects **hit sets**, not regexes.  The
patterns may match different parts of an entry — one the name, one a bound
name, one elsewhere in the statement — and in any order, none of which a
single concatenated regex can express.
"""

import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import cli, sections_from  # noqa: E402

SRC = {
    "T": (
        'theory T imports Main begin\n'
        'lemma length_of_encode_entry: "True" by simp\n'
        'lemma length_alone: "True" by simp\n'
        'lemma encode_entry_alone: "True" by simp\n'
        'record state =\n'
        '  rreqs :: "nat set"\n'
        'lemma stated: "length xs = encode_entry ys" by simp\n'
        'lemma stated_partly: "length xs = 0" by simp\n'
        'end\n'),
    "U": (
        'theory U imports Main begin\n'
        'lemma length_of_encode_entry_too: "True" by simp\n'
        'end\n'),
}


def _capture(fn, *args, **kwargs):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args, **kwargs)
    return buf.getvalue()


class Fixture(unittest.TestCase):

    def setUp(self):
        self.sections = sections_from(SRC)

    def _and(self, *patterns, **kw):
        return _capture(cli.cmd_find_and, self.sections, list(patterns),
                        cli.CmdFlags(**kw))

    def _or(self, pattern, **kw):
        return _capture(cli.cmd_find, self.sections, pattern,
                        cli.CmdFlags(**kw))


class TheIntersection(Fixture):

    def test_an_entry_matching_both_is_kept(self):
        out = self._and("length", "encode_entry", mode="names")
        self.assertIn("length_of_encode_entry", out)

    def test_an_entry_matching_only_one_is_dropped(self):
        out = self._and("length", "encode_entry", mode="names")
        self.assertNotIn("length_alone", out)
        self.assertNotIn("encode_entry_alone", out)

    def test_the_result_is_the_intersection_of_the_two_searches(self):
        both = set(self._and("length", "encode_entry", mode="names").split("\n"))
        lengths = set(self._or("length", mode="names").split("\n"))
        encodes = set(self._or("encode_entry", mode="names").split("\n"))
        self.assertEqual(both - {""}, (lengths & encodes) - {""})

    def test_order_does_not_matter(self):
        self.assertEqual(self._and("length", "encode_entry", mode="names"),
                         self._and("encode_entry", "length", mode="names"))

    def test_three_patterns_narrow_further(self):
        two = self._and("length", "encode_entry", mode="count").strip()
        three = self._and("length", "encode_entry", "too",
                          mode="count").strip()
        self.assertEqual((two, three), ("2", "1"))

    def test_no_common_entry_says_so_once(self):
        out = self._and("length", "zzzznope")
        self.assertEqual(out.count("No entries matching"), 1)
        self.assertIn("length AND zzzznope", out)


class PatternsMayMatchDifferentParts(Fixture):

    def test_a_name_and_a_bound_name_intersect(self):
        # `state` matches the record's name, `rreqs` only the field it binds.
        # Intersecting hit sets sees one entry; a concatenated regex could not,
        # because the two never appear in the same string.
        out = self._and("state", "rreqs", mode="names")
        self.assertIn("state", out)

    def test_the_statement_slice_is_searched_when_asked(self):
        # Asserted as an exact set, not with `assertIn`: `stated` is a
        # substring of `stated_partly`, so a containment check here would pass
        # on the very entry the conjunction is supposed to exclude.
        out = self._and("length", "encode_entry", statement=True, mode="names")
        got = {ln.split(" ", 1)[0] for ln in out.strip().split("\n")}
        # `stated` has both words in its proposition; `stated_partly` has one.
        # The two `length_of_encode_entry*` lemmas are here because the
        # statement slice includes the declaration line, so their NAMES carry
        # both words — the slice is "declaration, not proof", not "proposition
        # only".
        self.assertEqual(got, {"stated", "length_of_encode_entry",
                               "length_of_encode_entry_too"})


class ComposesWithTheOtherFlags(Fixture):

    def test_count(self):
        self.assertEqual(
            self._and("length", "encode_entry", mode="count").strip(), "2")

    def test_names(self):
        out = self._and("length", "encode_entry", mode="names")
        self.assertEqual(len(out.strip().split("\n")), 2)

    def test_a_theory_scope_narrows_the_conjunction(self):
        import argparse
        ns = argparse.Namespace(theory_scope=["U"])
        scoped = cli._scope_to_theories(ns, self.sections)
        out = _capture(cli.cmd_find_and, scoped,
                       ["length", "encode_entry"], cli.CmdFlags(mode="names"))
        self.assertIn("length_of_encode_entry_too", out)
        self.assertNotIn("— T ", out)


class TheDefaultIsStillDisjunction(Fixture):

    def test_without_the_flag_each_pattern_reports_separately(self):
        parser = cli._build_parser()
        ns = parser.parse_args(["find", "a", "b"])
        self.assertFalse(ns.conjunction)

    def test_one_pattern_with_the_flag_is_an_ordinary_find(self):
        # Nothing to intersect, so the conjunctive path must not change the
        # answer — `_run_find` falls through when there is a single pattern.
        parser = cli._build_parser()
        ns = parser.parse_args(["find", "length", "--and"])
        self.assertTrue(ns.conjunction)
        self.assertEqual(len(ns.pattern), 1)


class TheFlagSpelling(unittest.TestCase):

    def test_the_dest_is_not_the_python_keyword(self):
        # `--and`'s natural dest is `and`, which no handler can write as
        # `ns.and`.  A dest nobody can spell is a trap for the next caller.
        ns = cli._build_parser().parse_args(["find", "x", "--and"])
        self.assertTrue(ns.conjunction)
        self.assertFalse(hasattr(ns, "and"))

    def test_there_is_no_short_A_flag(self):
        # `-A` is grep's after-context.  Squatting on it would silently give a
        # grep-reflex caller a different result set than the one they asked
        # for — the same reasoning that keeps `-n` off `--names`.
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                cli._build_parser().parse_args(["find", "x", "-A"])


class ProseObeysTheConjunctionToo(Fixture):

    def test_a_note_needs_every_pattern_on_it(self):
        # A union here would quietly reintroduce the OR the flag turned off.
        secs = sections_from({"C": (
            'theory C imports Main begin\n'
            'text \\<open>the length of an encode_entry\\<close>\n'
            'text \\<open>the length of nothing\\<close>\n'
            'lemma both_words: "True" by simp\n'
            'end\n')})
        out = _capture(cli.cmd_find_and, secs, ["length", "encode_entry"],
                       cli.CmdFlags(mode="names", with_comments=True))
        self.assertIn("encode_entry", out)
        self.assertNotIn("length of nothing", out)


if __name__ == "__main__":
    unittest.main()
