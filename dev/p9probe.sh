#!/usr/bin/env bash
#
# dev/p9probe.sh -- hand-computed checks for the P9 sync with upstream 0.8.1.
#
# Sibling of dev/p5probe.sh, p6probe.sh, p6bprobe.sh, p7probe.sh and
# p7cprobe.sh, which stay as they are.  Those cover the plugin, the server and
# the rewrite-only verbs; this one covers the BEHAVIOUR CHANGES of P9, one `§`
# per item of dev/P9-PLAN.md, each tagged with the upstream tag so that
# `git log --grep='\[count-mode-zero\]'` and this file name the same thing.
#
# Why a probe at all when there is an oracle.  dev/difftest.sh answers "do the
# two agree", which is necessary and is not sufficient: it says nothing about
# WHY the answer is what it is, and a matrix case that changes from one wrong
# answer to a matching wrong answer reads exactly like a fix.  Every
# expectation below was hand-computed off the fixtures this script writes --
# read them, they are the spec -- from the rule in the commit message, and only
# then cross-checked against the 0.8.1 oracle.  The order matters: correct the
# expectation against Isabelle's semantics, never the other way round.
#
# Usage:
#   dev/p9probe.sh [CORPUS]
#
# CORPUS is a real Isabelle project for the round-trip spot check in §3b,
# defaulting to $QUERY_TEST_DISTRO/CTT -- the same variable dev/difftest.sh
# uses; no path is hard-coded.  $QUERY_TEST_AFP / $QUERY_TEST_DISTRO come from
# the environment, or from the repository's gitignored .dev/corpora.env.  The
# scratch Isabelle user home is $USER_HOME, defaulting to the repository's own
# .dev -- never the real one.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

# This probe compares the ENGINE against a hand-computed expectation, so
# `isabelle query` must be the engine in THIS process and not whatever a
# resident server happens to hold -- and must not leave one behind.  Same pin,
# and the same reason, as p5/p6/p6b/p7c.
export ISABELLE_QUERY_NO_SERVER=1

# The method/attribute table is pinned for the reason dev/difftest.sh pins it:
# unpinned it is resolved from whichever session heaps this machine happens to
# have built (dev/DIVERGENCES.md D11), and the fixture's names must be routed
# by one table on every machine.
export ISABELLE_QUERY_NAMESPACE=committed

# The citation-reachability filter is pinned OFF for the same reason
# dev/difftest.sh pins it: until S3 turns it into a flag with one default on
# both sides, the port's default answer is not the oracle's, and a count in
# this file must mean the same thing in both columns.  S3 deletes this line
# along with the variable.
export ISABELLE_QUERY_REACHABILITY=off

# .dev/corpora.env is where this repository records its two corpora.  Read it
# only for what the environment has not already supplied, so a caller can
# override either one.
if [ -f "$REPO/.dev/corpora.env" ]; then
  # shellcheck disable=SC1091
  . "$REPO/.dev/corpora.env"
fi

CORPUS="${1:-${QUERY_TEST_DISTRO:-}/CTT}"

# REFUSE rather than skip: a gate that passes on less than it claims to cover
# is worse than one that refuses, because the refusal is visible and the false
# green is not.
if [ ! -d "$CORPUS" ]; then
  echo "p9probe: the real-corpus round-trip check in §3b needs a corpus that is not here:" >&2
  echo "  CORPUS: $CORPUS (\$QUERY_TEST_DISTRO/CTT, or argument 1)" >&2
  echo "  (\$QUERY_TEST_DISTRO comes from the environment or .dev/corpora.env)" >&2
  echo "usage: dev/p9probe.sh [CORPUS]" >&2
  exit 2
fi

OUT="$(mktemp -d "$REPO/.dev/p9probe.XXXXXX")" || exit 2
trap 'rm -rf "$OUT"' EXIT INT TERM

fail=0
checks=0
note() { checks=$((checks + 1)); echo "  ok    $1${2:+  [$2]}"; }
bad()  { checks=$((checks + 1)); fail=$((fail + 1)); echo "  FAIL  $1  [$2]"; }

# --------------------------------------------------------------------------
# The fixtures.
#
# A -- one theory, two entries.  Upstream's own probe fixture, with the two
# names lengthened by a character: `Usage_Graph.DROP_NAMES_UPTO` is 1 on both
# sides, so a one-character name is not a citation-graph node at all and
# `callees d` would answer "not in the entry index" for a reason that has
# nothing to do with the contract under test.  (Checked: the 0.8.1 oracle says
# the same about `d`.)
#
# B -- the same T, plus a theory the ROOT addresses BY PATH and a decoy of the
# same bare name at the top level.  There is no per-theory `in` clause in the
# session grammar, so `theories "LK/Propositional"` is how a subdirectory is
# addressed, and both engines then carry the theory under that spelling.  The
# decoy is the whole point of §3: a resolver that fell back to the stem would
# answer about `Propositional` when asked about `LK/Propositional`, silently
# and confidently.
#
# Every count below was read off these files before any code ran; the line
# numbers are part of the expectation, so do not insert lines without moving
# them.
# --------------------------------------------------------------------------

FIX="$OUT/fixA"
mkdir -p "$FIX" || exit 2

cat >"$FIX/ROOT" <<'ROOT'
session P9_Fix = HOL +
  options [document = false]
  theories
    T
ROOT

cat >"$FIX/T.thy" <<'THY'
theory T
imports Main
begin
definition dd :: "nat" where "dd = 0"
lemma ll: "dd = dd" by (simp add: dd_def)
end
THY

FIXB="$OUT/fixB"
mkdir -p "$FIXB/LK" || exit 2

cat >"$FIXB/ROOT" <<'ROOT'
session P9_Fix_B = HOL +
  options [document = false]
  theories
    T
    "LK/Propositional"
    Propositional
ROOT

cp "$FIX/T.thy" "$FIXB/T.thy"

cat >"$FIXB/LK/Propositional.thy" <<'THY'
theory Propositional
imports Main
begin
lemma triv: "True" by simp
end
THY

cat >"$FIXB/Propositional.thy" <<'THY'
theory Propositional
imports Main
begin
lemma also_triv: "True" by simp
end
THY

echo "fixtures: $OUT"
echo "corpus:   $CORPUS"
echo

isabelle scala_build || exit $?

# ROOT-relative runs against each fixture.  stdout and stderr are captured
# SEPARATELY and to files, because the whole contract under test is which
# stream a sentence lands on and whether the other one stayed empty -- a
# `2>&1` here would test nothing.
ROOT_DIR="$FIX"
run() { isabelle query -R "$ROOT_DIR" "$@" >"$OUT/o.txt" 2>"$OUT/e.txt"; echo $?; }

# expect NAME RC STDOUT STDERR ARGS...
#
# STDOUT and STDERR are compared with the trailing newline stripped (`$(<f)`),
# which is what a shell capture sees; the one place the trailing bytes matter
# is checked with `od` in §1c.
expect() {
  local name=$1 want_rc=$2 want_out=$3 want_err=$4; shift 4
  local rc got_out got_err
  rc=$(run "$@")
  got_out=$(cat "$OUT/o.txt"); got_err=$(cat "$OUT/e.txt")
  if [ "$rc" = "$want_rc" ] && [ "$got_out" = "$want_out" ] && [ "$got_err" = "$want_err" ]
  then note "$name" "rc $rc"
  else
    bad "$name" \
      "rc $rc/$want_rc out [$got_out] wanted [$want_out]; err [$got_err] wanted [$want_err]"
  fi
}

PROG='isabelle query'

# ==========================================================================
echo "1. [unresolved-subject] -- stderr, empty stdout, exit 1"
# ==========================================================================
#
# The rule: a SUBJECT that cannot be resolved is not a zero result, it is a
# question that was never asked.  Nine verbs, checked BEFORE mode dispatch --
# so `-c` must not print a plausible `0` and `--names` must not print a
# sentence a pipeline would read as a name.  Texts are byte-exact with the
# oracle's `commands._fail_subject` after the program-name prefix, which is
# this tool's own (CONTRIBUTING, "The program name").

echo
echo "1a. the entry-index family"

expect "callees of an unknown name" 1 "" \
  "$PROG: 'zzz' is not in the entry index" callees zzz
expect "and -c does not print a plausible 0" 1 "" \
  "$PROG: 'zzz' is not in the entry index" callees zzz -c
expect "callees -r, same verdict" 1 "" \
  "$PROG: 'zzz' is not in the entry index" callees -r zzz
expect "callers -r, which needs the entry to have a closure" 1 "" \
  "$PROG: 'zzz' is not in the entry index" callers -r zzz

# The counterweight, and it is not an inconsistency: plain `callers` SCANS
# source for a token, so zero mentions is a truthful answer whether or not the
# name is declared.  Unchanged by this item, and it must stay unchanged.
expect "but plain callers keeps its honest zero" 0 "No callers found for 'zzz'." "" \
  callers zzz
expect "and callers -c prints 0, not a refusal" 0 "0" "" callers zzz -c

echo
echo "1b. the theory family"

expect "theory NAME lists the known theories -- all of it on stderr" 1 "" \
  "$(printf "%s: no theory 'zzz'.  Known theories:\n  T" "$PROG")" theory zzz
expect "defs" 1 "" "$PROG: no theory 'zzz'" defs zzz
expect "outline" 1 "" "$PROG: no theory 'zzz'" outline zzz
expect "deps" 1 "" "$PROG: no theory 'zzz'" deps zzz
expect "uses -- the same code path, reversed" 1 "" "$PROG: no theory 'zzz'" uses zzz
expect "refs, in count mode" 1 "" "$PROG: no theory 'zzz'" refs zzz -c

expect "methods NAME" 1 "" \
  "$PROG: 'zzz' is not used as a proof method here, and is not in the resolved proof-method namespace.  Try \`methods\` for the list of methods actually used." \
  methods zzz -c
# The other half of the `methods` rule: a method the project DOES use is
# located, so the refusal above is about the name and not about the verb.
expect "but a method the project uses is counted" 0 "1" "" methods simp -c

echo
echo "1c. batch subjects -- the separator, then the abort"

# `CLI.each` prints a blank line BEFORE each subject after the first, and the
# `Exit_Code` is thrown from inside the loop body, so the separator that
# precedes the failing subject is already on stdout when the run gives up.
# Earlier subjects keep their output; later ones never run.  Hand-computed:
# `ll` cites `dd` (via `dd_def`), so `callees ll -c` is 1; then a blank line;
# then nothing, because `zzz` ends the run before `dd` is reached.
rc=$(run callees ll zzz dd -c)
got=$(od -c <"$OUT/o.txt" | head -2 | tr -s ' ')
if [ "$rc" = "1" ] && [ "$(cat "$OUT/o.txt")" = "$(printf '1\n')" ] &&
   [ "$(cat "$OUT/e.txt")" = "$PROG: 'zzz' is not in the entry index" ]; then
  note "the first subject's count, the separator, then exit 1" "rc $rc"
else
  bad "the first subject's count, the separator, then exit 1" \
    "rc $rc out $got err [$(cat "$OUT/e.txt")]"
fi
# Byte-exact, because "1\n\n" versus "1\n" is exactly the difference between
# the separator having been printed and not.
if [ "$(od -An -c <"$OUT/o.txt" | tr -d ' \n')" = "1\\n\\n" ]; then
  note "and the separator blank line IS among those bytes" '1\n\n'
else
  bad "and the separator blank line IS among those bytes" \
    "$(od -An -c <"$OUT/o.txt" | tr -d ' \n')"
fi
# One diagnostic, not one per remaining subject.
if [ "$(wc -l <"$OUT/e.txt")" = "1" ]; then
  note "exactly one line on stderr" "1"
else
  bad "exactly one line on stderr" "$(wc -l <"$OUT/e.txt")"
fi

echo
echo "1d. what is deliberately NOT in the family"

# Each of these was verified identical on both sides while the item was
# specified, and each is here so that widening the family later has to be a
# deliberate act with its own evidence.
expect "enclosing reports on stderr and still exits 0" 0 "" \
  "zzz:3: no such theory 'zzz'" enclosing zzz:3
expect "shape lemma: a search, so an honest empty" 0 \
  "No proof-bearing entry matching 'zzz'." "" shape lemma zzz
expect "grep -c: a search, so an honest 0" 0 "0" "" grep zzz_no_such_token -c
# `--theory` is a SCOPE, not a subject: an unknown one warns and the run goes
# on, because the answer over the remaining scope is still an answer.
expect "an unknown --theory scope warns and continues" 0 \
  "No entries matching 'dd'." "$PROG: theory 'zzz' not found" \
  find dd --theory zzz

# ==========================================================================
echo
echo "2. [count-mode-zero] -- a count mode prints a number"
# ==========================================================================
#
# The other empty, and the mirror image of §1: `find zzz` IS a real search
# with a real answer, so `-c` must be `0` and `--names` must be nothing.  Only
# the human-readable modes say so in words.

expect "find -c on no match"                0 "0" "" find zzz -c
expect "find --names on no match"           0 ""  "" find zzz --names
expect "find -a --names on no match"        0 ""  "" find zzz -a --names
expect "show -c on no match"                0 "0" "" show zzz -c
expect "show --names on no match"           0 ""  "" show zzz --names
expect "find --and -c on no match"          0 "0" "" find --and zzz qqq -c
expect "and the human modes keep the sentence"      0 "No entries matching 'zzz'." "" find zzz
expect "including -a"                               0 "No entries matching 'zzz'." "" find zzz -a
expect "a match still counts"               0 "1" "" find dd -c

# `unused` is the second renderer that moved.  `--keep dd,ll` makes the
# fixture's two entries live, so the honest answer is zero unused entries.
expect "unused -c with nothing unused"      0 "0" "" unused -c --keep dd,ll
expect "unused -r -c likewise"              0 "0" "" unused -r -c --keep dd,ll
expect "and the default mode keeps the sentence" 0 "No unused entries found." "" \
  unused --keep dd,ll
# NOT reordered upstream, so NOT reordered here: `--roots` is a third renderer
# and `defs` guards on its own filtered list.  Pinned so that changing either
# has to be deliberate.
expect "unused --roots -c is unchanged" 0 "No unused roots found." "" \
  unused --roots -c --keep dd,ll
expect "defs -c on a theory with definitions" 0 "1" "" defs T -c

# ==========================================================================
echo
echo "3. [name-roundtrip] -- the printed name resolves back"
# ==========================================================================
#
# `summary` prints `LK/Propositional`; passing that string back must reach
# THAT theory.  The decoy `Propositional` is what makes the check mean
# something: a resolver that fell through to the stem would answer about the
# top-level file, and a locus that looks paste-able but lands elsewhere is a
# worse answer than an obviously ambiguous one.

ROOT_DIR="$FIXB"

# `summary` has no row filter, so the expectation is a grep for the one row.
# Hand-computed: 5 source lines, no definitions, one lemma, no theorems, and
# `triv` as the key export.
if isabelle query -R "$FIXB" summary 2>/dev/null |
     grep -qx '| LK/Propositional | 5 | 0 | 1 | 0 | triv |'; then
  note "the path-spelled name is what \`summary\` prints" "LK/Propositional"
else
  bad "the path-spelled name is what \`summary\` prints" \
    "$(isabelle query -R "$FIXB" summary 2>/dev/null | grep Propositional | tr '\n' ' ')"
fi

expect "theory LK/Propositional -c"        0 "1" "" theory LK/Propositional -c
expect "and it is LK's lemma, not the decoy's" 0 \
  "triv (LEMMA) — LK/Propositional [src 4..4, 1 lines]" "" \
  theory LK/Propositional --names
expect "the bare decoy still resolves to itself" 0 \
  "also_triv (LEMMA) — Propositional [src 4..4, 1 lines]" "" \
  theory Propositional --names
expect "defs, through the same resolver" 0 \
  "No definitions found in 'LK/Propositional'." "" defs LK/Propositional
expect "uses" 0 "No in-project theory imports LK/Propositional (directly)." "" \
  uses LK/Propositional
expect "refs -c" 0 "0" "" refs LK/Propositional -c
expect "grep, which resolves a FILE positional" 0 "1" "" grep triv LK/Propositional -c

# The rule is EXACT match on the printed name, not last-segment: upstream did
# not add a last-segment rule and neither does this.  `Propositional` above
# resolves because it is a section's exact name, not because it is a suffix of
# `LK/Propositional` -- and a path that names nothing is §1's failure.
expect "a path-spelled name that exists nowhere is §1's refusal" 1 "" \
  "$(printf "%s: no theory 'LK/Nope'.  Known theories:\n  LK/Propositional\n  Propositional\n  T" "$PROG")" \
  theory LK/Nope

echo
echo "3b. the same round trip on a real corpus"

# CTT declares `theories ex/Typechecking` and prints `ex/Typechecking`; before
# this item every verb that took it back said "not found".  This is the case
# the survey found (dev/difftest.sh's CTT `deps-last`, `refs-last`,
# `deps-batch`, `refs-batch`, `uses-batch`), asked directly.
ROOT_DIR="$CORPUS"
thy=$(isabelle query -R "$CORPUS" summary 2>/dev/null |
        sed -n 's/^| \(ex\/[A-Za-z_]*\) |.*/\1/p' | head -1)
if [ -n "$thy" ]; then
  note "the corpus has a path-spelled theory to ask about" "$thy"
  n=$(isabelle query -R "$CORPUS" theory "$thy" -c 2>"$OUT/e.txt")
  rc=$?
  if [ "$rc" = "0" ] && [ -n "$n" ] && [ "$n" -gt 0 ] 2>/dev/null; then
    note "and \`theory\` on it answers with a count" "$n"
  else
    bad "and \`theory\` on it answers with a count" "rc $rc, [$n], $(cat "$OUT/e.txt")"
  fi
else
  bad "the corpus has a path-spelled theory to ask about" "none in \`summary\`"
fi

# ==========================================================================
# S2-S4 sections go here, in plan order, one `§` per item of dev/P9-PLAN.md:
#
#   §4-§11   S2, the parser        ([axiom-names], [marker-decl] a/b/c,
#                                   [proof-extent-view], [comment-before-name],
#                                   [decl-body-comment])
#   §12-§15  S3, the citation graph ([symbol-body-tokens],
#                                   [name-is-not-identity], [import-leaf],
#                                   [citation-reach], `--reach`)
#   §16-§18  S4, loci and shape     ([disambig-names], [disambig-loci],
#                                   [bare-provenance])
#
# Each brings its own fixture (write it into "$OUT", as §1-§3 do) and its own
# hand-computed table.  Keep the failability section LAST.
# ==========================================================================

# --------------------------------------------------------------------------
# S2 -- the parser: fixtures, and the one instrument the whole cluster uses.
# --------------------------------------------------------------------------
#
# Every S2 item moves an ENTRY RECORD -- a name, a declaration end, a span, a
# binding -- so the instrument is the dump verb dev/entrydiff.sh already runs,
# and the expectation is the WHOLE record set of a small theory rather than a
# grep for the one line that moved.  That is deliberate: a rule that fixes one
# name while quietly moving the entry below it looks identical to a fix when
# you only look at the name.
#
# `--spans --bindings` on every dump, so decl_end / body_end / a locale's
# binding list are all in the comparison.  Every number below was read off the
# fixture by hand from the rule in the matching commit message, then
# cross-checked against the 0.8.1 oracle -- in that order.

PARSE="$OUT/parser"
mkdir -p "$PARSE" || exit 2

# expect_dump NAME DIR EXPECTED
#
# EXPECTED is the complete `dump-entries --spans --bindings` output, newline
# separated, trailing newline stripped (what `$(...)` yields on both sides).
# A mismatch reports the diff, on one line, so the failing RECORD is named
# rather than "the sets differ".
expect_dump() {
  local name=$1 dir=$2 want=$3
  local got
  got=$(isabelle query dump-entries "$dir" --spans --bindings 2>"$OUT/e.txt")
  if [ "$got" = "$want" ]; then
    note "$name" "$(printf '%s\n' "$got" | grep -c .) records"
  else
    bad "$name" \
      "$(diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") | tr '\n' '~')"
  fi
}

# ==========================================================================
echo
echo "4. [axiom-names] -- the axiomatization anchor is '?', not a fact name"
# ==========================================================================
#
# The rule: `axiomatization` opens a command, not a fact.  The anchor entry
# that owns the lines of the command therefore has NO NAME of its own -- `?`,
# the spelling already used for an anonymous lemma -- because a name minted
# from the keyword is a citable name that nothing in Isabelle can cite, and it
# is counted in `summary` as a declaration that does not exist.  The anchor
# entry STAYS: the lines below it still have to resolve to something, which is
# what `enclosing Ax:13` asks.
#
# Ax.thy below has two `axiomatization` commands and five real axiom names.

mkdir -p "$PARSE/axiom" || exit 2
cat >"$PARSE/axiom/Ax.thy" <<'THY'
theory Ax
imports Main
begin

axiomatization
  f :: "nat \<Rightarrow> nat" and
  Cap :: "nat"
where
  lower: "f 0 = 0" and
  Upper_case: "f 1 = 1"

axiomatization where process_finite:
  "OFCLASS(process, finite_class)"

lemma l: "True" by simp

end
THY

# Hand-computed.  Line 5 and line 12 are the two anchors -- `?` under this
# item, `axiomatization` before it.  Line 12 carries TWO entries (the anchor
# and `process_finite`, whose name is on the command line itself), which is
# the P1 span tie and is why both are pinned here.  The five named axioms and
# the lemma are untouched by this item and are in the table so that a change
# that moved one of them could not hide behind the two that were meant to
# move.
AXIOM_WANT=$(cat <<'EXPECT'
Ax:5:AXIOM:?:src=5-5:decl_end=5:proof=0:body_end=5:bind=:target=
Ax:6:AXIOM:f:src=6-6:decl_end=6:proof=0:body_end=6:bind=:target=
Ax:7:AXIOM:Cap:src=7-8:decl_end=7:proof=0:body_end=7:bind=:target=
Ax:9:AXIOM:lower:src=9-9:decl_end=9:proof=0:body_end=9:bind=:target=
Ax:10:AXIOM:Upper_case:src=10-11:decl_end=10:proof=0:body_end=10:bind=:target=
Ax:12:AXIOM:?:src=12-14:decl_end=12:proof=0:body_end=12:bind=:target=
Ax:12:AXIOM:process_finite:src=12-14:decl_end=12:proof=0:body_end=12:bind=:target=
Ax:15:LEMMA:l:src=15-15:decl_end=15:proof=15:body_end=15:bind=:target=
EXPECT
)

expect_dump "the anchor of each axiomatization is '?'" "$PARSE/axiom" "$AXIOM_WANT"

# The consequences, in the two verbs a user would notice them in.  `find` is
# the direct one: `axiomatization` was findable as a fact name and now is not.
ROOT_DIR="$PARSE/axiom"
expect "no entry is named after the command" 0 "0" "" find '^axiomatization$' -c

# ... and `enclosing` shows the anchor is still THERE.  Line 13 is inside the
# second command's span (12..14) and belongs to no named axiom, so it resolves
# to the anchor: same locus, same extent, only the name column changed.
expect "but a line inside the command still resolves to the anchor" 0 \
  "Ax:13 → ? (AXIOM) — Ax [src 12..14, body 12..12, 1/3 lines]" "" \
  enclosing Ax:13

# ==========================================================================
echo
echo "5. all FOUR formal comments redact in the live view"
# ==========================================================================
#
# The rule: Isabelle has four formal comments -- `\<comment>` (a marginal
# note), `\<^cancel>` (deleted text), `\<^latex>` (raw LaTeX) and `\<^marker>`
# (a document-build tag) -- each owning the cartouche after it, and the lexer
# skips all four wherever a token may appear.  None of them is live Isar, so
# all four are NOISE: blanked in `live_source` as well as in `outer_source`.
#
# Two of them used to be kept live, on the reading that a LaTeX body or a
# document tag is still document text rather than deleted text.  That confuses
# what the body says with where it stands: `grep` then reports a document tag
# as live source, `callers` counts it as a citation, and every line-granular
# rule built on `nonisar_ranges` cannot see that a `\<^marker>` line holds
# nothing -- which is what §9 and §10 need it to see.
#
# One theory, one word (`widget`), written once in each of the four comment
# spellings and once for real.  Hand-computed: exactly ONE live match -- the
# definition on line 16 -- and four in comments/text, one per spelling.

mkdir -p "$PARSE/redact" || exit 2
cat >"$PARSE/redact/Redact.thy" <<'THY'
theory Redact
imports Main
begin

lemma a1: "True" \<comment> \<open>the note mentions widget\<close>
  by simp

lemma a2: "True" \<^cancel>\<open>lemma ghost: "widget"\<close>
  by simp

lemma\<^marker>\<open>tag widget\<close> a3: "True" by simp

lemma a4: "True" \<^latex>\<open>\emph{widget}\<close>
  by simp

definition widget :: "bool" where "widget = True"

end
THY

ROOT_DIR="$PARSE/redact"

expect "only the real definition is a LIVE match" 0 \
  "$(printf "1 live match(es) for 'widget':\n\n  Redact.thy:16  widget (DEF)\n    definition widget :: \"bool\" where \"widget = True\"")" \
  "" grep widget

# The other four are found, and every one of them is classified as comment/text
# -- so the classification moved, not the search.  The owner column is the
# enclosing lemma in each case, which is what makes the four lines visible at
# all.
expect "and the other four are all comment/text, one per spelling" 0 \
  "$(cat <<'EXPECT'
5 match(es) for 'widget' (1 live, 4 in comments/text):

  Redact.thy:5   a1 (LEMMA)  [in comment/text]
    lemma a1: "True" \<comment> \<open>the note mentions widget\<close>
  Redact.thy:8   a2 (LEMMA)  [in comment/text]
    lemma a2: "True" \<^cancel>\<open>lemma ghost: "widget"\<close>
  Redact.thy:11  a3 (LEMMA)  [in comment/text]
    lemma\<^marker>\<open>tag widget\<close> a3: "True" by simp
  Redact.thy:13  a4 (LEMMA)  [in comment/text]
    lemma a4: "True" \<^latex>\<open>\emph{widget}\<close>
  Redact.thy:16  widget (DEF)
    definition widget :: "bool" where "widget = True"
EXPECT
)" "" grep widget --with-comments

# The citation consequence, and the reason this is a prerequisite rather than a
# cosmetic fix: a name written in a document tag is not a use of that name.
# `widget` is declared here, so a stray edge would be a real one in the graph.
expect "a name inside a formal comment cites nothing" 0 "0" "" callers widget -c

# ==========================================================================
echo
echo "99. failability -- the harness must be able to say no"
# ==========================================================================
#
# A probe that has never failed has not been tested.  Two perturbations, one
# per direction, each asserting that a DELIBERATELY WRONG expectation is
# rejected -- so a `expect` that compared nothing would show up here.

ROOT_DIR="$FIX"

# The perturbation: the pre-P9 answer.  If `find zzz -c` still printed the
# sentence, §2 would be green against the old behaviour and this would be too.
rc=$(run find zzz -c)
if [ "$(cat "$OUT/o.txt")" != "No entries matching 'zzz'." ]; then
  note "the count mode is not the sentence it used to print" "$(cat "$OUT/o.txt")"
else
  bad "the count mode is not the sentence it used to print" "still the sentence"
fi

# And the comparison itself bites: hand `expect` a wrong expectation through a
# nested tally and require that it counts a failure.  Its own output is
# swallowed -- a green run must not print the word FAIL -- and the tallies are
# restored afterwards, so this costs one check and no false red.
saved_fail=$fail saved_checks=$checks
fail=0; checks=0
expect "(perturbed) callees zzz must NOT be a silent 0" 0 "0" "" callees zzz -c >/dev/null
perturbed=$fail
fail=$saved_fail; checks=$saved_checks
if [ "$perturbed" = "1" ]; then
  note "a wrong expectation is rejected by \`expect\`" "1 failure, discarded"
else
  bad "a wrong expectation is rejected by \`expect\`" "$perturbed failures"
fi

echo
printf '%d checks: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p9probe: FAILURES" >&2
  exit 1
fi
echo "P9PROBE OK"
exit 0
