r"""Non-Isar lexical regions: comments, `\<^cancel>`, verbatim, and ML bodies.

Isabelle source contains regions that are not Isar proof text.  A name in such
a region is not a fact citation, and a command word in one is not a command.
The scanner used to read all of them as live source, which produced call-graph
edges the source does not support (`callers` invents a caller, `unused` hides a
dead lemma) and truncated spans (a commented-out `end` cut the declaration
above it, so `show`/`outline`/`enclosing`/`largest` all reported the wrong
extent).

The reproductions below are the four in issue #2, verbatim, with the correct
results stated there — plus the fixtures that issue asks for around them: an
ML body with no entry above it (which passed before the fix, for the wrong
reason), an ML body between two entries, a comment nested two levels deep, and
the guard against over-correcting.

That guard is the important one.  A `"..."` region holds an inner-syntax term,
so `foo` in `lemma bar: "foo = 0"` is a REAL citation.  Redacting strings — or
redacting every cartouche rather than only an ML command's body — would delete
true edges, a failure this suite would otherwise not notice, because every
other test here asserts the ABSENCE of an edge.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from support import (cli, section_from,  # noqa: E402
                     brute_force_call_graph)
from isabelle_query import parsing  # noqa: E402


def ranges(snippet):
    """Non-Isar line ranges of a snippet (1-indexed inclusive)."""
    return parsing.extract_nonisar_ranges(snippet.split("\n"))


def graph_of(sec):
    return cli._build_call_graph([sec])


def entry(sec, name):
    return next(e for e in sec.entries if e.name == name)


def enclosing(sec, line_no):
    """The entry whose span contains `line_no`, or None — what `enclosing` asks."""
    idx = cli._build_line_index([sec]).get(sec.path, [])
    return cli._entry_at_line(idx, line_no)


# --- the four reproductions of issue #2, verbatim ---------------------------

R1 = r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  (* could have used foo here, but did not *)
  by simp

end
'''

R2 = r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True" by simp

ML \<open>
  val msg = "foo is not a fact citation here";
\<close>

end
'''

R3 = r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  \<^cancel>\<open>using foo\<close>
  by simp

end
'''

R4 = r'''theory A
imports Main
begin

lemma bar: "True \<and> True"
proof
(* superseded:
end
  show True by simp
*)
  show True by simp
  show True by simp
qed

end
'''


class Reproductions(unittest.TestCase):
    """The four defects of issue #2, each asserting the correct result."""

    def test_r1_comment_is_not_a_citation(self):
        sec = section_from(R1, "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_r1_comment_does_not_hide_a_dead_lemma(self):
        # The user-visible consequence: `foo` is dead code, and the comment
        # (which says the author did NOT use it) concealed that.
        sec = section_from(R1, "A")
        g = cli._build_call_graph([sec], derived=True)
        self.assertEqual(cli._compute_unused(g), {"foo", "bar"})

    def test_r2_ml_body_is_not_a_citation(self):
        # ML source has its own namespace: the ML identifier `foo` is not the
        # Isabelle fact `foo`.
        sec = section_from(R2, "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_r2_ml_body_ends_the_span_above_it(self):
        # `bar` is one line; before the fix its span ran on to 11, absorbing
        # the whole ML block.
        sec = section_from(R2, "A")
        bar = entry(sec, "bar")
        self.assertEqual((bar.src_start, bar.thy_end), (7, 7))

    def test_r3_cancel_region_is_not_a_citation(self):
        # Isabelle deletes the text in a \<^cancel> region, so a citation there
        # is evidence the proof does NOT use the fact.
        sec = section_from(R3, "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_r4_commented_end_does_not_cut_the_span(self):
        sec = section_from(R4, "A")
        bar = entry(sec, "bar")
        self.assertEqual((bar.src_start, bar.thy_end), (5, 13))

    def test_r4_live_proof_line_has_an_enclosing_entry(self):
        # Line 12 is an ordinary `show` in a live proof.
        sec = section_from(R4, "A")
        e = enclosing(sec, 12)
        self.assertIsNotNone(e)
        self.assertEqual(e.name, "bar")


class MLBodyFamily(unittest.TestCase):
    """Issue #2 asks for these separately: a partial correction passes some."""

    def test_ml_body_with_no_entry_above_it(self):
        # This case passed BEFORE the fix, for the wrong reason: the edge was
        # attributed to the entry enclosing the line, and no entry enclosed it.
        sec = section_from(r'''theory A
imports Main
begin

ML \<open>
  val msg = "foo is not a fact citation here";
\<close>

lemma foo: "True" by simp

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_ml_body_below_the_only_entry(self):
        # Issue #2's own construction: R2 with `lemma bar` removed.  This one
        # passed BEFORE the fix too — the ML body fell inside `foo`'s own
        # (over-long) span, so the mention was discarded as a self-citation.
        # Kept because a correction that re-attributes it would be a regression
        # that no other test here would catch.
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

ML \<open>
  val msg = "foo is not a fact citation here";
\<close>

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_ml_body_between_two_entries(self):
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True" by simp

ML \<open>
  val msg = "foo";
\<close>

lemma baz: "True" by simp

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["foo"], set())
        self.assertEqual((entry(sec, "bar").src_start,
                          entry(sec, "bar").thy_end), (7, 7))
        self.assertIn("baz", {e.name for e in sec.entries})

    def test_ml_file_ends_the_span_above_it(self):
        # `ML_file` takes a path, not a cartouche — no body to skip, but it is
        # still a command, so it must not be absorbed into the lemma above.
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

ML_file "helper.ML"

end
''', "A")
        self.assertEqual(entry(sec, "foo").thy_end, 5)


class NestedAndInline(unittest.TestCase):
    def test_nested_comment_two_levels_deep(self):
        # A non-greedy match to the FIRST `*)` would end the comment on the
        # inner one and read the line after it as live proof text.
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  (* outer
     (* inner *)
     foo is still inside the comment
  *)
  by simp

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())
        self.assertEqual(ranges(r'''(* outer
   (* inner *)
   still comment
*)
live'''), [(1, 4)])

    def test_comment_alone_on_one_line_inside_a_proof(self):
        sec = section_from(r'''theory A
imports Main
begin

lemma foo: "True" by simp

lemma bar: "True"
  proof -
  (* foo would do here *)
    show "True" by simp
  qed

end
''', "A")
        self.assertEqual(graph_of(sec).callers["foo"], set())

    def test_legacy_verbatim_is_not_live(self):
        self.assertEqual(ranges('{* legacy foo *}'), [(1, 1)])


class KeptLive(unittest.TestCase):
    r"""The over-correction guard: these edges must SURVIVE.

    A correction that redacts `"..."` regions, or every cartouche rather than
    only an ML command's body, passes every other test in this file while
    quietly deleting most of the call graph.
    """

    def test_fact_named_in_a_term_is_still_a_citation(self):
        sec = section_from(r'''theory A
imports Main
begin

definition foo :: "nat" where "foo = 0"

lemma bar: "foo = foo" by simp

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["foo"], {"bar"})
        self.assertEqual(g.callers, brute_force_call_graph([sec]).callers)

    def test_operator_section_in_a_term_opens_no_comment(self):
        # `(*)` is HOL's multiplication section.  Reading its `(*` as a comment
        # opener would redact the rest of the theory — deleting `foo`'s edge and
        # every entry below.
        sec = section_from(r'''theory A
imports Main
begin

definition foo :: "nat" where "foo = 0"

lemma bar: "fold (*) [1, 2] foo = foo" by simp

lemma baz: "True" by simp

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["foo"], {"bar"})
        self.assertEqual({e.name for e in sec.entries}, {"foo", "bar", "baz"})
        self.assertEqual(ranges('lemma bar: "fold (*) [1, 2] x = y" by simp'), [])

    def test_operator_section_in_a_cartouche_opens_no_comment(self):
        # Isabelle scans a cartouche as one token, so `(*` inside it is term
        # text, not a comment opener.
        self.assertEqual(
            ranges(r'have \<open>fold (*) [1, 2] x = y\<close> by simp'), [])

    def test_comment_open_inside_a_string_is_not_a_comment(self):
        self.assertEqual(ranges(r'lemma bar: "x = (*) 1 2" by simp'), [])

    def test_trailing_comment_keeps_its_line_live(self):
        # `extract_nonisar_ranges` reports only lines with NO live text, so a
        # line whose comment merely trails real proof text is not in it.  That
        # is what keeps the citation of `foo` below; column-accurate redaction
        # is what removes the phantom `bar` without touching it.
        self.assertEqual(ranges('  by (simp add: foo) (* not bar *)'), [])


class PartialLines(unittest.TestCase):
    r"""Lines that hold live proof text AND a non-Isar region.

    These are the lines column-accurate redaction exists for, and the ones it
    can most easily break: blanking such a line wholesale removes the phantom
    citation *and* the real one beside it.  Every test here asserts the real
    edge SURVIVES, so an over-eager redaction fails loudly rather than quietly
    shrinking the graph.  The complementary "phantom is gone" assertions live
    in `NoPhantomOnAPartialLine`.
    """

    def callers_of(self, body, name):
        sec = section_from('theory A\nimports Main\nbegin\n\n'
                           'lemma helper: "True" by simp\n\n'
                           'lemma other: "True" by simp\n\n'
                           + body + '\n\nend\n', "A")
        return graph_of(sec).callers[name]

    def test_citation_before_a_trailing_comment(self):
        self.assertEqual(
            self.callers_of('lemma user: "True" using helper by simp'
                            ' (* not other *)', "helper"), {"user"})

    def test_citation_before_a_comment_that_runs_on(self):
        # The comment opens on the citing line and closes two lines later.
        self.assertEqual(
            self.callers_of('lemma user: "True" using helper by simp (* why\n'
                            '   other would not do\n'
                            '*)', "helper"), {"user"})

    def test_citation_after_a_comment_closes(self):
        # The mirror image: the live text follows the `*)` on its line.  The
        # declaration stays at column 0 — a `*) lemma user:` would not be read
        # as a declaration at all, but that is command recognition (which is
        # deliberately column-anchored), not redaction.
        self.assertEqual(
            self.callers_of('lemma user: "True"\n'
                            '(* other would not do\n'
                            '*) using helper by simp', "helper"), {"user"})

    def test_citation_beside_an_inline_cancel(self):
        self.assertEqual(
            self.callers_of(r'lemma user: "True" using helper'
                            r' \<^cancel>\<open>and other\<close> by simp',
                            "helper"), {"user"})

    def test_citation_in_a_term_beside_a_comment(self):
        sec = section_from(r'''theory A
imports Main
begin

definition helper :: "nat" where "helper = 0"

lemma user: "helper = 0" by simp (* other would work too *)

end
''', "A")
        self.assertEqual(graph_of(sec).callers["helper"], {"user"})


class NoPhantomOnAPartialLine(unittest.TestCase):
    r"""The other half of `PartialLines`: the redacted name cites nothing.

    Whole-line skipping could not do this.  `_noise_spans` marks a line or does
    not, so a line holding both a real citation and a commented-out one forced a
    choice between keeping both and losing both — and losing a true edge is the
    worse, quieter error, so the phantom was kept deliberately.
    `TheorySection.live_source` removes the choice: the comment is blanked in
    place and the citation beside it is untouched.

    Each test therefore asserts BOTH directions on the SAME line.  Asserting
    only the absence would be satisfied by blanking the whole line, which is
    the regression this pairing exists to catch.
    """

    def graph_of_body(self, body):
        return graph_of(section_from(
            'theory A\nimports Main\nbegin\n\n'
            'lemma helper: "True" by simp\n\n'
            'lemma other: "True" by simp\n\n'
            + body + '\n\nend\n', "A"))

    def test_trailing_comment_is_not_a_citation(self):
        # Moved here from test_known_failures once `live_source` landed: the
        # residual this documented is closed.
        g = self.graph_of_body(
            'lemma user: "True" using helper by (simp) (* not other *)')
        self.assertEqual(g.callers["other"], set())
        self.assertEqual(g.callers["helper"], {"user"})

    def test_inline_cancel_is_not_a_citation(self):
        g = self.graph_of_body(
            r'lemma user: "True" using helper'
            r' \<^cancel>\<open>other\<close> by simp')
        self.assertEqual(g.callers["other"], set())
        self.assertEqual(g.callers["helper"], {"user"})

    def test_inline_ml_body_is_not_a_citation(self):
        # An ML cartouche opened and closed on its command's own line: real in
        # the AFP (`attribute_setup ... = \<open>Scan.succeed ...\<close>`), and
        # never fully-noise, so only column redaction reaches it.
        g = self.graph_of_body(
            'lemma user: "True" using helper by simp\n'
            r'ML \<open>val msg = "other";\<close>')
        self.assertEqual(g.callers["other"], set())
        self.assertEqual(g.callers["helper"], {"user"})

    def test_comment_that_opens_and_runs_on_is_not_a_citation(self):
        g = self.graph_of_body(
            'lemma user: "True" using helper by simp (* why\n'
            '   other would not do\n'
            '*)')
        self.assertEqual(g.callers["other"], set())
        self.assertEqual(g.callers["helper"], {"user"})

    def test_oracle_agrees_on_a_partial_line(self):
        # The brute-force reference reads the same redacted view, so parity is
        # a real check here rather than two copies of one bug.
        sec = section_from('theory A\nimports Main\nbegin\n\n'
                           'lemma helper: "True" by simp\n\n'
                           'lemma other: "True" by simp\n\n'
                           'lemma user: "True" using helper by simp'
                           ' (* not other *)\n\nend\n', "A")
        self.assertEqual(graph_of(sec).callers,
                         brute_force_call_graph([sec]).callers)


class MarginalComments(unittest.TestCase):
    r"""`\<comment> \<open>...\<close>` — the note that trails live proof text.

    This one failed in the FALSE-NEGATIVE direction, which is the worse one.
    A marginal note normally shares its line with the step it annotates
    (`by simp \<comment> \<open>why\<close>`), and the line-granular
    `comment_ranges` marked the whole line — so the `by simp` vanished from the
    method census and any citation beside it vanished from the graph.  The
    tokenizer reports the note by column instead, so only the note goes.
    """

    def test_note_does_not_take_the_step_with_it(self):
        sec = section_from(r'''theory A
imports Main
begin

lemma helper: "True" by simp

lemma other: "True" by simp

lemma user: "True" using helper by simp \<comment> \<open>other is weaker\<close>

end
''', "A")
        g = graph_of(sec)
        self.assertEqual(g.callers["helper"], {"user"})   # the live half
        self.assertEqual(g.callers["other"], set())       # the note

    def test_note_line_still_counts_its_method(self):
        # `_scan_methods` skipped the whole line, so this `simp` was invisible.
        sec = section_from(r'''theory A
imports Main
begin

lemma user: "True" by simp \<comment> \<open>could use auto\<close>

end
''', "A")
        counts, _ = cli._scan_methods([sec])
        self.assertEqual(counts["simp"], 1)
        self.assertEqual(counts["auto"], 0)

    def test_note_alone_on_its_line_is_wholly_non_isar(self):
        self.assertEqual(ranges(r'  \<comment> \<open>round 1\<close>'), [(1, 1)])

    def test_multi_line_note_body_is_covered(self):
        self.assertEqual(ranges(r'''lemma a: "True"
  \<comment> \<open>a note that
     runs over two lines\<close>
  by simp'''), [(2, 3)])

    def test_note_inside_a_quoted_term_is_covered(self):
        # Isabelle's INNER syntax takes cartouche comments too, and a note
        # inside a multi-line `"..."` definition body is a real AFP shape.
        # The tokenizer is in `string` state there, so it has to recognise the
        # marker without leaving the term.
        self.assertEqual(ranges(r'''definition d where
  "d x = (do {
     let y = f x;
     \<comment> \<open>round 1\<close>
     return y
  })"'''), [(4, 4)])

    def test_note_inside_a_cartouche_term_is_covered(self):
        # The commoner inner-syntax case, and the one that needs the enclosing
        # term's nesting depth stashed: a `definition ... where \<open>...`
        # body annotated line by line, with the note itself nesting cartouches.
        self.assertEqual(ranges(
            r'''definition d :: \<open>bool\<close> where
  \<open>d \<equiv>
    \<comment> \<open>\<open>M1\<close> has a non-empty domain\<close>
    W \<noteq> {}\<close>'''), [(3, 3)])

    def test_term_resumes_after_a_note_at_the_right_depth(self):
        # The enclosing term's nesting depth is stashed while the note is
        # scanned, and restored after.  Observing that needs the term NESTED
        # (depth 2) when the note appears: at depth 1 — the everyday shape —
        # restoring 1 and restoring 0 both leave the next `\<close>` ending the
        # term, so this fixture is deliberately synthetic rather than typical.
        # Restore 0 instead, and the inner `\<close>` drops out to outer syntax,
        # where the `(*)` after it opens a comment that eats `baz`.
        sec = section_from(r'''theory A
imports Main
begin

definition d :: "nat" where
  \<open>d = \<open>inner
    \<comment> \<open>a note\<close>
    xs\<close> then fold (*) ys\<close>

lemma baz: "True" by simp

end
''', "A")
        self.assertEqual({e.name for e in sec.entries}, {"d", "baz"})

    def test_note_nested_in_a_live_cartouche_does_not_end_it(self):
        # The marker and its cartouche match as ONE token that ENDS in an
        # opener.  If the enclosing cartouche did not count that as nesting, the
        # note's own `\<close>` would close the TERM a level early — and the
        # `(*)` after it would then open a comment that swallows `baz`.
        sec = section_from(r'''theory A
imports Main
begin

lemma bar: \<open>x \<comment> \<open>note\<close> = fold (*) xs\<close> by simp

lemma baz: "True" by simp

end
''', "A")
        self.assertEqual({e.name for e in sec.entries}, {"bar", "baz"})


class NoPhantomDeclarations(unittest.TestCase):
    r"""A declaration inside a non-Isar region is not a declaration.

    The declaration scan used to skip only `text` blocks, so it read the
    grammar inside comments and ML bodies too.  Both mint entries Isabelle
    never sees: authors supersede a `definition` and leave the old one in a
    `(* ... *)`, and ML declares its own functions with `fun`, which Isabelle
    spells identically.  Such an entry inflates `summary`, shows up in `theory`
    and `find`, and reads as dead code in `unused` that cannot be deleted
    because it already has been.

    The guards matter as much: gating on the tokenizer means a false positive
    now DELETES a real declaration instead of merely adding a phantom, so the
    last two tests pin the lines that must keep declaring.
    """

    def names(self, snippet):
        return [e.name for e in section_from(snippet, "A").entries]

    def test_commented_out_definition_is_not_an_entry(self):
        self.assertEqual(self.names(r'''theory A
imports Main
begin

(*
definition old :: "nat" where "old = 0"
*)

definition live :: "nat" where "live = 1"

end
'''), ["live"])

    def test_ml_fun_is_not_an_entry(self):
        # ML's `fun` and Isabelle's `fun` are the same word; only the enclosing
        # command tells them apart.
        self.assertEqual(self.names(r'''theory A
imports Main
begin

ML \<open>
fun helper ctxt = ctxt
\<close>

fun real_fun :: "nat \<Rightarrow> nat" where "real_fun n = n"

end
'''), ["real_fun"])

    def test_superseded_declaration_resolves_to_the_live_one(self):
        # The AFP case that found this: the old definition is commented out and
        # a new one with the SAME name follows.  First-wins lookup used to
        # return the commented-out line.
        sec = section_from(r'''theory A
imports Main
begin

(*
definition thing :: "nat" where "thing = 0"
*)

definition thing :: "nat" where "thing = 1"

end
''', "A")
        self.assertEqual([e.name for e in sec.entries], ["thing"])
        self.assertEqual(entry(sec, "thing").thy_line, 9)

    def test_comment_opening_mid_line_still_hides_what_follows(self):
        # The opener need not start its line: `... oops (* TODO` swallows the
        # declarations under it, and that is a real AFP shape.
        self.assertEqual(self.names(r'''theory A
imports Main
begin

lemma stub: "True" oops (* TODO: revisit
lemma hidden: "True" by simp
*)

lemma live: "True" by simp

end
'''), ["stub", "live"])

    def test_declaration_with_a_trailing_comment_still_declares(self):
        # The guard.  Only WHOLLY non-Isar lines are gated; a declaration that
        # merely ends in a comment must survive, or the gate would delete real
        # entries — the quieter and worse failure.
        self.assertEqual(self.names(r'''theory A
imports Main
begin

definition kept :: "nat" where "kept = 0" (* still a definition *)

end
'''), ["kept"])

    def test_declaration_after_a_closed_comment_still_declares(self):
        self.assertEqual(self.names(r'''theory A
imports Main
begin

(* prose about the next one *)
definition kept :: "nat" where "kept = 0"

end
'''), ["kept"])


class Attribution(unittest.TestCase):
    r"""Which entry a comment is charged to — and it is DIRECTIONAL.

    Three rules, none of which the non-Isar work was meant to change:

      * a `text \<open>...\<close>` block PRECEDES the entry it documents and is
        charged forward to it (`Entry.preamble`, which moves `src_start`);
      * a `\<comment>` note FOLLOWS the step it annotates and is charged
        backward, into the enclosing proof (`Entry.roadmap`);
      * a plain `(* ... *)` block between two declarations falls in the
        PRECEDING entry's span, because spans run to the next declaration.

    Gating the declaration scan does move spans, in one specific way: a
    commented-out declaration used to be an entry, and an entry is a span
    BOUNDARY, so the entry above it now runs through the region instead of
    stopping at it.  That is the third rule finally applying to a region that
    was previously carved out by a declaration Isabelle never saw — but it is a
    change, so it is pinned here rather than left to be discovered.
    """

    # A note is a roadmap step only STRICTLY inside the proof body
    # (`proof_line < line`), so the live note goes on a `show`, not on the
    # proof's own first line.  That rule predates this work and is unchanged.
    SNIPPET = r'''theory A
imports Main
begin

lemma first: "True"
  proof -
    show "True" by simp \<comment> \<open>a real note\<close>
  qed

(*
definition old :: "nat" where "old = 0"
  \<comment> \<open>a note about deleted text\<close>
*)

text \<open>Documents second.\<close>
lemma second: "True" by simp

end
'''

    def test_preceding_entry_absorbs_the_commented_out_region(self):
        sec = section_from(self.SNIPPET, "A")
        first = entry(sec, "first")
        # Runs through the comment block, stopping before `second`'s preamble.
        self.assertEqual(first.thy_end, 14)

    def test_the_next_entry_keeps_its_forward_attributed_doc(self):
        # The `text` block still belongs to the entry BELOW it: the growth of
        # `first` must stop at `second`'s src_start, not swallow its docstring.
        sec = section_from(self.SNIPPET, "A")
        second = entry(sec, "second")
        self.assertEqual(second.preamble, (15, 15))
        self.assertEqual(second.src_start, 15)

    def test_note_in_a_live_proof_is_a_roadmap_step(self):
        sec = section_from(self.SNIPPET, "A")
        self.assertEqual([c for _, c in entry(sec, "first").roadmap],
                         ["a real note"])

    def test_note_inside_a_commented_out_block_is_not(self):
        # It annotates text that is not there.  Now that the region falls
        # inside `first`'s span, nothing but the scanner's own state can tell
        # this note from the live one above — they are spelled identically.
        sec = section_from(self.SNIPPET, "A")
        self.assertNotIn("a note about deleted text",
                         [c for _, c in entry(sec, "first").roadmap])

    def test_grep_classifies_a_match_not_a_line(self):
        r"""A hit inside a trailing note is prose, though its line is live.

        `_noise_spans` is line-granular, so once `\<comment>` left it, a
        line-level liveness test would have called this match source.  The test
        is per match — the pattern has to survive redaction.
        """
        sec = section_from('theory A\nimports Main\nbegin\n\n'
                           'lemma one: "True" by simp'
                           r' \<comment> \<open>could use helper\<close>'
                           '\n\n'
                           'lemma two: "True" using helper by simp\n\nend\n',
                           "A")
        hits = {ln: live for _f, ln, _t, _o, live, _ in
                cli._grep_sections([sec], re.compile("helper"))}
        self.assertEqual(hits, {5: False, 7: True})

    def test_enclosing_a_commented_out_line_gives_the_entry_above(self):
        # `query enclosing` on a line in the region: the answer is the entry
        # whose span covers it, which is the rule a plain `(* ... *)` block has
        # always followed.  Previously it was the phantom `old`.
        sec = section_from(self.SNIPPET, "A")
        e = enclosing(sec, 9)
        self.assertIsNotNone(e)
        self.assertEqual(e.name, "first")


class Ranges(unittest.TestCase):
    """Unit-level: the line ranges the scanner reports."""

    def test_full_line_comment(self):
        self.assertEqual(ranges('lemma a\n  (* prose *)\n  by simp'), [(2, 2)])

    def test_multi_line_comment_is_one_range(self):
        self.assertEqual(ranges('a\n(* one\ntwo\nthree *)\nb'), [(2, 4)])

    def test_cancel_region(self):
        self.assertEqual(ranges(r'a' '\n' r'  \<^cancel>\<open>using foo\<close>'
                                '\n' r'b'), [(2, 2)])

    def test_ml_body(self):
        self.assertEqual(ranges('ML \\<open>\n  val x = 1;\n\\<close>\nlemma a'),
                         [(2, 3)])

    def test_ml_command_line_itself_stays_live(self):
        # The `ML` keyword must stay visible: it is the span boundary.
        self.assertNotIn(1, [lo for lo, _ in
                             ranges('ML \\<open>\n  val x = 1;\n\\<close>')])

    def test_blank_source_has_no_ranges(self):
        self.assertEqual(ranges('lemma a: "True" by simp'), [])


if __name__ == "__main__":
    unittest.main()
