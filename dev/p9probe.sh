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

# Citation reachability needs no pin.  S3 turned it into `--reach
# {closure,name}` with `closure` the default on BOTH sides, so a count here
# means the same thing in both columns without a variable; where a fixture
# wants the compatibility mode it says `--reach name` in the invocation, which
# is visible in the case rather than in the environment.

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
#
# S3's four fixtures each set `ROOT_DIR` to their own root; §99 sets it back
# to §1's before it perturbs anything.
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

# expect_names NAME DIR EXPECTED -- the same, without the span/binding columns,
# for an item whose whole effect is on the NAME.  Written as a separate
# assertion rather than a wider one so that "the name is right" and "the extent
# is right" stay separate findings.
expect_names() {
  local name=$1 dir=$2 want=$3
  local got
  got=$(isabelle query dump-entries "$dir" 2>"$OUT/e.txt")
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
echo "6. [marker-decl] a -- a NAME stops at a structural symbol"
# ==========================================================================
#
# The rule: an Isabelle name may interleave identifier characters with symbol
# tokens (`\<phi>step`, `split\<^sub>i_tree`), but the two cartouche
# delimiters and the four formal comments are STRUCTURE and not name
# characters.  A name grammar that admits every `\<...>` runs straight through
# the marker abutting the name: `definition lipschitzI_on\<^marker>\<open>tag
# important\<close> ::` was indexed as `lipschitzI_on\<^marker>\<open>tag`, and
# `lemma shows_box_of_aforms\<comment> \<open>...\<close>:` as
# `shows_box_of_aforms\<comment>`.
#
# So two atoms, and which one a scanner wants follows from what it is doing:
# a NAME scanner reads raw source and must stop at a marker, while a RUN
# scanner (the citation tokeniser, `shape`'s token counter, the user-pattern
# rewrite) asks only where a token ends and must NOT.  Both directions are
# checked below, because narrowing the run scanners by mistake is the failure
# this split invites -- and it is silent, unlike the one it fixes.

mkdir -p "$PARSE/glued" || exit 2
cat >"$PARSE/glued/Glued.thy" <<'THY'
theory Glued
imports Main
begin

definition lipschitzI_on\<^marker>\<open>tag important\<close> :: "bool"
  where "lipschitzI_on = True"

lemma shows_box_of_aforms\<comment> \<open>glued note\<close>: "True"
  by simp

definition split\<^sub>i_tree :: "bool" where "split\<^sub>i_tree = True"

definition \<phi>step :: "bool" where "\<phi>step = True"

lemma cancelled: "True" \<^cancel>\<open>lemma ghost: "False"\<close>
  by simp

lemma runs: "True"
  using \<open>True\<close> lipschitzI_on_def by simp

end
THY

# Hand-computed.  Lines 5 and 8 are the two the rule moves: the name ends where
# the marker begins.  Lines 11 and 13 are the counterweight -- a subscript and
# a Greek letter ARE name characters and must survive the narrowing -- and line
# 15's `\<^cancel>` body must not be indexed as a lemma of its own, which is
# what the six-record set says.
GLUED_WANT=$(cat <<'EXPECT'
Glued:5:DEF:lipschitzI_on:src=5-7:decl_end=6:proof=0:body_end=6:bind=:target=
Glued:8:LEMMA:shows_box_of_aforms:src=8-10:decl_end=8:proof=9:body_end=9:bind=:target=
Glued:11:DEF:split\<^sub>i_tree:src=11-12:decl_end=11:proof=0:body_end=11:bind=:target=
Glued:13:DEF:\<phi>step:src=13-14:decl_end=13:proof=0:body_end=13:bind=:target=
Glued:15:LEMMA:cancelled:src=15-17:decl_end=15:proof=16:body_end=16:bind=:target=
Glued:18:LEMMA:runs:src=18-19:decl_end=18:proof=19:body_end=19:bind=:target=
EXPECT
)

expect_dump "a name ends where its marker begins" "$PARSE/glued" "$GLUED_WANT"

ROOT_DIR="$PARSE/glued"

# The run scanners, in the other direction.  Each of these three would BREAK if
# the lexical atom had been narrowed with the name one:
#
# (a) the citation tokeniser.  `\<open>foo` is one run; split at the delimiter
#     it yields `open` as a candidate fact name, on every cartouche in the
#     corpus.  Nothing declares `open` here, so the honest answer is 0.
expect "a cartouche delimiter is not a citation" 0 "0" "" callers open -c

# (b) the user-pattern rewrite, which escapes markup so `\<^sub>`'s `^` is not
#     read as a start-of-string anchor.  It asks the LEXICAL question, so it
#     must still recognise a symbol the name grammar rejects.
expect "and a subscripted name is still findable by its own spelling" 0 \
  "split\\<^sub>i_tree (DEF) — Glued [src 11..12, body 11..11, 1/2 lines]" "" \
  find 'split\<^sub>i' --names

# (c) `shape`'s proposition-token counter.  Line 19 is
#     `using \<open>True\<close> lipschitzI_on_def by simp`: FIVE tokens --
#     `using`, the cartouche as one run, the fact name, `by`, `simp`.  Under
#     the name atom the cartouche shatters into its delimiters' characters and
#     the count roughly doubles, which is a metric moving for a reason that has
#     nothing to do with the proof.
pt=$(isabelle query -R "$ROOT_DIR" shape summary --json 2>"$OUT/e.txt" |
       sed -n 's/.*"lemma": "runs".*"proof_tokens": \([0-9]*\),.*/\1/p')
if [ "$pt" = "5" ]; then
  note "a cartouche is ONE proposition token, not its characters" "proof_tokens 5"
else
  bad "a cartouche is ONE proposition token, not its characters" \
    "proof_tokens [$pt], wanted 5"
fi

# ==========================================================================
echo
echo "7. [marker-decl] b -- a marked locale/class name, and the target chain"
# ==========================================================================
#
# The rule: a target's name is read from the LIVE view, so once all four formal
# comments redact there (§5) a document marker between the keyword and the name
# is simply gone before the name grammar sees it.  There is no second
# marker-skipping step in `target_name` for exactly that reason: two views with
# their own idea of what a marker is are two answers to one question.
#
# What moves is not only the LOCALE/CLASS entry's name.  Every entry inside the
# block carries the enclosing target, so a mis-read name propagates to the
# `target` field of each of them and to what `enclosing` prints -- which is the
# thing a user is most likely to paste somewhere.  19 real cases in
# HOL/Analysis (`subset_class`, `sigma_algebra`, `Dynkin_system`,
# `finite_measure`, `Retracts`, class `Gamma`, ...).
#
# Four shapes: marker glued to `locale`, glued to `class`, after a SPACE, and
# on `context`, which reopens rather than declares.

mkdir -p "$PARSE/target" || exit 2
cat >"$PARSE/target/Loc.thy" <<'THY'
theory Loc
imports Main
begin

locale\<^marker>\<open>tag important\<close> sigma_algebra =
  fixes M :: "nat set"
  assumes nonempty: "M \<noteq> {}"
begin

lemma inside_marked_locale: "True" by simp

end

class\<^marker>\<open>tag important\<close> Gamma =
  fixes g :: "'a"
begin

definition inside_marked_class :: "'a" where "inside_marked_class = g"

end

locale \<^marker>\<open>tag unimportant\<close> spaced =
  fixes h :: "nat"
begin

lemma inside_spaced: "True" by simp

end

context\<^marker>\<open>tag important\<close> sigma_algebra
begin

lemma reopened: "True" by simp

end

end
THY

# Hand-computed.  Three declared targets (`sigma_algebra`, `Gamma`, `spaced`)
# and four entries inside them, whose `target=` is the name of the block they
# sit in -- including the one inside the REOPENED `context`, which declares no
# entry of its own.  `bind=nonempty/assumption` is in the table because the
# locale head's element scan reads the same lines: a name read wrong there
# would show up as a lost binding rather than as a wrong name.
TARGET_WANT=$(cat <<'EXPECT'
Loc:5:LOCALE:sigma_algebra:src=5-7:decl_end=7:proof=0:body_end=7:bind=nonempty/assumption:target=
Loc:10:LEMMA:inside_marked_locale:src=10-10:decl_end=10:proof=10:body_end=10:bind=:target=sigma_algebra
Loc:14:CLASS:Gamma:src=14-15:decl_end=15:proof=0:body_end=15:bind=:target=
Loc:18:DEF:inside_marked_class:src=18-18:decl_end=18:proof=0:body_end=18:bind=:target=Gamma
Loc:22:LOCALE:spaced:src=22-23:decl_end=23:proof=0:body_end=23:bind=:target=
Loc:26:LEMMA:inside_spaced:src=26-26:decl_end=26:proof=26:body_end=26:bind=:target=spaced
Loc:33:LEMMA:reopened:src=33-33:decl_end=33:proof=33:body_end=33:bind=:target=sigma_algebra
EXPECT
)

expect_dump "the marked target names, and the chain under them" \
  "$PARSE/target" "$TARGET_WANT"

ROOT_DIR="$PARSE/target"

# What the user actually reads.  `enclosing` names the enclosing target, so all
# four shapes are visible here in the form they are pasted in.
expect "enclosing names the marked locale" 0 \
  "Loc:10 → inside_marked_locale (LEMMA) — Loc ▸ locale sigma_algebra [src 10..10, 1 lines]  (in proof)" \
  "" enclosing Loc:10
expect "... the marked class" 0 \
  "Loc:18 → inside_marked_class (DEF) — Loc ▸ class Gamma [src 18..18, 1 lines]  (in statement)" \
  "" enclosing Loc:18
expect "... the space-before-marker locale" 0 \
  "Loc:26 → inside_spaced (LEMMA) — Loc ▸ locale spaced [src 26..26, 1 lines]  (in proof)" \
  "" enclosing Loc:26
expect "... and the marked context, which reopens rather than declares" 0 \
  "Loc:33 → reopened (LEMMA) — Loc ▸ context sigma_algebra [src 33..33, 1 lines]  (in proof)" \
  "" enclosing Loc:33

# ==========================================================================
echo
echo "8. [marker-decl] c -- ONE heading recogniser"
# ==========================================================================
#
# The rule: a heading is a heading.  There were three answers to "is this line
# a heading?" -- a tight pattern for `outline`'s view, a wide one for the prose
# mask, and a third inside `proof_extent` -- on the reasoning that a view wants
# no false positives while a mask cannot afford a false negative.  Both
# instincts are right in isolation and the conclusion was wrong: it is a fact
# about Isar, not about the consumer.
#
# `heading_at` is now that one answer, and it reads the heading COMMAND
# separately from its TITLE, with the formal-comment skip of §5 in between --
# shared with `strip_decl_prefix`, since a marker before a declaration's name
# and a marker before a heading's title are the same grammatical position.  It
# has to read the RAW line: a title is a cartouche, so the view that blanks the
# marker blanks the title with it.
#
# The consequence is not only that `outline` shows a marked heading (14,238 of
# them AFP-wide).  A heading BOUNDS the entry above it, so its `src` end -- and
# `body_end` with it -- shrinks to stop at the heading instead of running past
# it to the next declaration.  211 records in HOL/Analysis.
#
# Four shapes: marker glued to the command, marker after a space, the split
# form with the title on the next line, and an English `chapter` inside a
# `text` block, which is prose and must NOT become a heading.

mkdir -p "$PARSE/heading" || exit 2
cat >"$PARSE/heading/Head.thy" <<'THY'
theory Head
imports Main
begin

subsection\<^marker>\<open>tag unimportant\<close> \<open>Glued Marker\<close>

lemma above: "True"
  by simp

subsection \<^marker>\<open>tag unimportant\<close> \<open>Spaced Marker\<close>

lemma middle: "True"
  by simp

subsection
  \<open>Split Form\<close>

lemma below: "True"
  by simp

text \<open>
chapter \<open>Dynamic Programming\<close> is only prose here
\<close>

lemma last_one: "True"
  by simp

end
THY

# Hand-computed spans.  `above` runs 7..9 because the heading on line 10 bounds
# it; without the marked heading it would run to 11, the line before `middle`.
# `middle` runs 12..14, bounded by the SPLIT heading on line 15.  `last_one`
# starts at 21, not 25: the three-line `text` block above it is short enough
# and adjacent enough to be its preamble, which is the pre-existing rule and is
# in the table so that a heading change moving it would be visible.
HEADING_WANT=$(cat <<'EXPECT'
Head:7:LEMMA:above:src=7-9:decl_end=7:proof=8:body_end=8:bind=:target=
Head:12:LEMMA:middle:src=12-14:decl_end=12:proof=13:body_end=13:bind=:target=
Head:18:LEMMA:below:src=18-20:decl_end=18:proof=19:body_end=19:bind=:target=
Head:25:LEMMA:last_one:src=21-26:decl_end=25:proof=26:body_end=26:bind=:target=
EXPECT
)

expect_dump "a marked heading bounds the entry above it" "$PARSE/heading" "$HEADING_WANT"

ROOT_DIR="$PARSE/heading"

# And the view itself.  Three headings, at 5, 10 and 15; the `chapter` on line
# 22 is inside the `text` block, so it appears as the block's first line of
# prose and not as a fourth heading.
expect "all three shapes are in the outline, and the prose one is not" 0 \
  "$(cat <<'EXPECT'
Outline of Head.thy:

      subsection: Glued Marker  (line 5)
        LEMMA    above  (7..9, 3 lines)
      subsection: Spaced Marker  (line 10)
        LEMMA    middle  (12..14, 3 lines)
      subsection: Split Form  (line 15)
        LEMMA    below  (18..20, 3 lines)
        text     [21..23, 3 lines]: chapter \<open>Dynamic Programming\<close> is only prose here
        LEMMA    last_one  (21..26, 6 lines)
EXPECT
)" "" outline Head

# ==========================================================================
echo
echo "9. [proof-extent-view] -- a proof ends at a COMMAND, and a comment is not one"
# ==========================================================================
#
# The rule: `proof_extent` walks down from the proof line looking for the three
# things that end a proof -- a `text ` block, a heading, a declaration -- and
# each of those is a COMMAND.  A line holding no live Isar text holds no
# command, so the three tests are made only on lines outside the whole-line
# noise mask that `nonisar_ranges` gives.  Read off the raw source instead,
# a commented-out `lemma old_version` ended the proof above it at the comment
# block, and `body_end` -- the documented safe relocation cut -- stopped short.
#
# Only the BOUNDARY tests are masked.  A noise line still advances the extent,
# because whether a trailing comment block belongs to the proof is a separate
# question and answering it here would be a second change hiding inside this
# one.  Both halves are in the table below: `commented_decl` runs to 12, the
# comment block's last line, and not to 7.
#
# Extent.thy has the three noise shapes; Live.thy is the counterweight -- the
# same three boundaries written LIVE, where every one of them must still stop
# the walk, plus a trailing `(* ... *)` on an otherwise live declaration line,
# which must not.

mkdir -p "$PARSE/extent" || exit 2
cat >"$PARSE/extent/Extent.thy" <<'THY'
theory Extent
imports Main
begin

lemma commented_decl: "True"
  using TrueI
  by simp

(* superseded, kept for reference
lemma old_version: "True"
  by auto
*)

lemma commented_heading: "True"
  by simp

(* not ready
subsection \<open>Retracts and intervals\<close>
*)

lemma commented_text: "True"
  by simp

(* draft
text \<open>Some prose that is not live.\<close>
*)

lemma tail: "True" by simp

end
THY

cat >"$PARSE/extent/Live.thy" <<'THY'
theory Live
imports Main
begin

lemma stops_at_decl: "True"
  by simp
lemma next_decl: "True" by simp

lemma stops_at_text: "True"
  by simp
text \<open>Real prose.\<close>

lemma stops_at_heading: "True"
  by simp
subsection \<open>Real heading\<close>

lemma trailing_comment: "True"
  by simp
lemma after_it: "P" (* a note on a live declaration *)
  by simp

lemma marked_heading_stops: "True"
  by simp
subsection\<^marker>\<open>tag unimportant\<close> \<open>Marked heading\<close>

lemma split_heading_stops: "True"
  by simp
subsection
  \<open>Split heading\<close>

lemma last: "True" by simp

end
THY

# Hand-computed, Extent.thy.  `commented_decl`: proof on 6, `thy_end` 13 (the
# next entry starts on 14); the walk passes 7 live, 8 blank, 9..12 noise --
# tested for nothing, but non-blank, so `body_end` is 12.  Read raw, line 10's
# `lemma old_version` would have ended it at 7.  `commented_heading` and
# `commented_text` are the same shape for the other two boundaries: 19 and 26,
# not 15 and 22.  `tail` ends at 28 because `end` on line 30 backs up over the
# blank to 29.
#
# Live.thy.  Each of the three boundaries, written live, stops the walk on the
# line before it: 6, 10, 14.  `after_it` is the guard in the other direction --
# a `(* note *)` at the END of a live declaration line leaves that line live,
# so the noise mask (deliberately whole-line) does not cover it and the
# declaration still bounds the proof above it.  `marked_heading_stops` (23) and
# `split_heading_stops` (27) are §8's recogniser reaching this caller too.
EXTENT_WANT=$(cat <<'EXPECT'
Extent:5:LEMMA:commented_decl:src=5-13:decl_end=5:proof=6:body_end=12:bind=:target=
Extent:14:LEMMA:commented_heading:src=14-20:decl_end=14:proof=15:body_end=19:bind=:target=
Extent:21:LEMMA:commented_text:src=21-27:decl_end=21:proof=22:body_end=26:bind=:target=
Extent:28:LEMMA:tail:src=28-28:decl_end=28:proof=28:body_end=28:bind=:target=
Live:5:LEMMA:stops_at_decl:src=5-6:decl_end=5:proof=6:body_end=6:bind=:target=
Live:7:LEMMA:next_decl:src=7-8:decl_end=7:proof=7:body_end=7:bind=:target=
Live:9:LEMMA:stops_at_text:src=9-10:decl_end=9:proof=10:body_end=10:bind=:target=
Live:13:LEMMA:stops_at_heading:src=11-14:decl_end=13:proof=14:body_end=14:bind=:target=
Live:17:LEMMA:trailing_comment:src=17-18:decl_end=17:proof=18:body_end=18:bind=:target=
Live:19:LEMMA:after_it:src=19-21:decl_end=19:proof=20:body_end=20:bind=:target=
Live:22:LEMMA:marked_heading_stops:src=22-23:decl_end=22:proof=23:body_end=23:bind=:target=
Live:26:LEMMA:split_heading_stops:src=26-27:decl_end=26:proof=27:body_end=27:bind=:target=
Live:31:LEMMA:last:src=31-31:decl_end=31:proof=31:body_end=31:bind=:target=
EXPECT
)

expect_dump "a commented-out boundary does not end a proof; a live one does" \
  "$PARSE/extent" "$EXTENT_WANT"

# The second caller.  `show` recomputes the extent to decide how many proof
# lines it has left to preview, and had the same defect -- one rule, two
# callers, so a fix applied to one of them is half a fix.  `commented_decl`
# has 6 lines after its proof line (7..12), which is what the summary says.
ROOT_DIR="$PARSE/extent"
expect "and \`show\` counts the same extent" 0 \
  "$(cat <<'EXPECT'
--- commented_decl (LEMMA) — Extent.thy [src 5..13, body 5..12, 8/9 lines] ---
lemma commented_decl: "True"
  using TrueI
  [+6 more proof lines]
EXPECT
)" "" show commented_decl

# ==========================================================================
echo
echo "10. [comment-before-name] -- a comment between a keyword and its name"
# ==========================================================================
#
# The rule: a decl keyword may stand alone with its name on a following line,
# and a formal comment routinely sits between the two.  The forward scan tested
# the RAW line for a leading `\<comment>`, which recognises only the comment's
# FIRST line and only one of the four spellings.  A comment that WRAPPED left
# its continuation looking like content, and the name was read out of the
# prose: `HOL/UNITY/WFair:35` indexed `is`, out of "the rest IS generic to all
# forms of fairness", and never indexed `transient` at all.  ZF recovers 49
# names this way, HOL/UNITY 20, HOL/Bali `TypeRel:368` `i` -> `widen`.
#
# So the scan asks the LIVE view instead: a line that is non-blank in the
# source and blank in `live` is a redaction, whichever of the four spellings
# (or a `(* ... *)`) produced it.  And a redacted line does not spend the
# 3-line budget -- a formal comment is ONE token to Isabelle's lexer however
# far it wraps, so charging per line makes the guard fire on well-formed
# source.  Blank and `text` lines still spend it, and a 40-line cap stops the
# walk outright.
#
# Ten shapes, `transient1`..`transient9` and one guard.  This is a NAMES check:
# nothing here moves a span (the two that look as if they do are §11's).

mkdir -p "$PARSE/prename" || exit 2
cat >"$PARSE/prename/Pre.thy" <<'THY'
theory Pre
imports Main
begin

definition
  transient1 :: "nat set" where "transient1 = {}"

definition \<comment> \<open>Generic to all forms.\<close>
  transient2 :: "nat set" where "transient2 = {}"

definition
  \<comment> \<open>Generic to all forms.\<close>
  transient3 :: "nat set" where "transient3 = {}"

definition
  \<comment> \<open>This specifies conditional fairness.  The rest
      is generic to all forms of fairness.\<close>
  transient4 :: "nat set" where "transient4 = {}"

definition
  \<^marker>\<open>tag important\<close>
  transient5 :: "nat set" where "transient5 = {}"

definition
  \<^marker>\<open>tag important
      and more\<close>
  transient6 :: "nat set" where "transient6 = {}"

definition
  (* Adjustment to a clock *)
  transient7 :: "nat set" where "transient7 = {}"

definition

  \<comment> \<open>Specifies conditional fairness.\<close>
  transient8 :: "nat set"
  where "transient8 = {}"

definition




  transient9 :: "nat set" where "transient9 = {}"

definition
  \<comment> \<open>this cartouche never closes
  filler line 0
  filler line 1
  filler line 2
  filler line 3
  filler line 4
  filler line 5

end
THY

# Hand-computed, one line per shape.
#
#   5  keyword alone, name next line                     -- unchanged
#   8  `\<comment>` on the KEYWORD line                   -- unchanged
#  11  `\<comment>` alone on one line                     -- unchanged
#  15  a WRAPPED `\<comment>` (the WFair shape)           -- was `is`
#  20  `\<^marker>` alone on its line                     -- was `?`
#  24  a wrapped `\<^marker>`                             -- was `?`
#  29  a `(* ... *)` line                                 -- was `?`
#  33  blank, then a note, then the name                  -- unchanged
#  39  FOUR blank lines then the name: blanks still spend the budget, so `?`
#  46  an unterminated comment then six lines of prose: the 40-line cap and
#      the redaction between them must leave `?`, not a name invented out of
#      `filler line 0`
PRENAME_WANT=$(cat <<'EXPECT'
Pre:5:DEF:transient1
Pre:8:DEF:transient2
Pre:11:DEF:transient3
Pre:15:DEF:transient4
Pre:20:DEF:transient5
Pre:24:DEF:transient6
Pre:29:DEF:transient7
Pre:33:DEF:transient8
Pre:39:DEF:?
Pre:46:DEF:?
EXPECT
)

expect_names "a comment before the name is skipped, not read" \
  "$PARSE/prename" "$PRENAME_WANT"

ROOT_DIR="$PARSE/prename"

# The two guards, stated as the questions a user would ask.  Neither `is` nor
# `filler` is a declaration here, and both were.
expect "no name is read out of a wrapped comment's prose" 0 "0" "" find '^is$' -c
expect "and none out of an unterminated one's" 0 "0" "" find 'filler' -c
# The counterweight: the eight real names ARE found, so the two zeros above
# are not a scan that stopped early.
expect "the eight real names are all there" 0 "8" "" find '^transient[1-8]$' -c

# ==========================================================================
echo
echo "11. [decl-body-comment] -- a formal comment does not end a declaration"
# ==========================================================================
#
# The rule: a formal comment is not a command, so it cannot END a declaration
# either.  The body scan broke on one, gated to `record` -- where breaking cost
# 11 of the AFP's 507 records every field they declare -- and the gate stayed
# narrow because the other routes had not been measured.  They were: the break
# truncates the keyword-comment-name shape, where the comment sits before the
# name and the recorded body collapses onto the keyword line.
# `HOL/Hoare/SchorrWaite:14`'s `rel` reported body 14..14 for a declaration
# running to 17, and `body_end` is the documented safe relocation cut, so a
# consumer cutting there leaves the declaration behind.
#
# The comment is SKIPPED, not appended: `decl_end` still ends on the last LIVE
# line, so a note TRAILING a declaration does not extend it either.  Both
# directions are in the table -- `rel1` grows from 5 to 8, `emptyPost1` shrinks
# from 28 to 27.
#
# Asking the LIVE view is what makes it cover a wrapped comment, all four
# spellings and `(* ... *)` at once, exactly as §10 does for the name.

mkdir -p "$PARSE/body" || exit 2
cat >"$PARSE/body/Body.thy" <<'THY'
theory Body
imports Main
begin

definition
  \<comment> \<open>Relations.\<close>
  rel1 :: "nat set"
  where "rel1 = {}"

definition
  \<comment> \<open>Relations induced
      by a mapping.\<close>
  rel2 :: "nat set"
  where "rel2 = {}"

definition
  \<^marker>\<open>tag important\<close>
  rel3 :: "nat set"
  where "rel3 = {}"

definition
  (* Relations. *)
  rel4 :: "nat set"
  where "rel4 = {}"

definition emptyPost1 :: nat where
"emptyPost1 = 0"
(* initially set to the lowest value *)

definition emptyPost2 :: nat where
"emptyPost2 = 0"
\<comment> \<open>a note\<close>

datatype tree = ET | MKT nat

subsection \<open>Invariants and auxiliary functions\<close>

primrec height :: "nat" where "height = 0"

definition
  \<comment> \<open>first\<close>
  a :: "nat" where "a = 0"
definition
  \<comment> \<open>second\<close>
  b :: "nat" where "b = 1"

record st =
  wa_cond :: "nat set"
  \<comment> \<open>Termination condition\<close>
  wa_body :: "nat"

fun ff :: "nat \<Rightarrow> nat" where
  "ff 0 = 0"
  \<comment> \<open>a note between two equations\<close>
| "ff (Suc n) = n"

definition

  \<comment> \<open>Specifies conditional fairness.\<close>
  transient :: "nat set"
  where "transient = {}"

end
THY

# Hand-computed `decl_end`, which is also `body_end` on every proof-less route.
#
#   rel1 8, rel2 14, rel3 19, rel4 24  the four spellings of a note BEFORE the
#          name -- one line, wrapped, `\<^marker>`, `(* ... *)`.  All four ran
#          to the keyword line before, and rel3/rel4 were not even recognised
#          as comments by the old leading-`\<comment>` test.
#   emptyPost1 27, emptyPost2 31       the other direction: a note AFTER the
#          declaration is skipped, not appended, so the end stays on the RHS.
#   tree 34, height 38                 the containment guard, reduced from
#          `AVL-Trees/AVL:23`: a heading between two declarations.  The
#          rejected wider variant appends the heading and gives
#          body_end 35 > thy_end 34.
#   a 42, b 45                         two note-before-name declarations back
#          to back: neither body may reach into the other.
#   st 50                              the `record` case the old gate existed
#          for -- both fields still declared.
#   ff 55                              a note BETWEEN two equations of a `fun`.
#   transient 57                       the residual, pinned as it stands.  A
#          BLANK line before the note breaks the scan first, so the body stays
#          collapsed on the keyword line.  The obvious fix -- do not break on a
#          blank while the body is still empty -- was implemented upstream,
#          measured, and REJECTED: it repairs this and takes containment
#          violations from 82 to 719.  The NAME is right (§10 does that part),
#          only the extent is not.
BODY_WANT=$(cat <<'EXPECT'
Body:5:DEF:rel1:src=5-9:decl_end=8:proof=0:body_end=8:bind=:target=
Body:10:DEF:rel2:src=10-15:decl_end=14:proof=0:body_end=14:bind=:target=
Body:16:DEF:rel3:src=16-20:decl_end=19:proof=0:body_end=19:bind=:target=
Body:21:DEF:rel4:src=21-25:decl_end=24:proof=0:body_end=24:bind=:target=
Body:26:DEF:emptyPost1:src=26-29:decl_end=27:proof=0:body_end=27:bind=:target=
Body:30:DEF:emptyPost2:src=30-33:decl_end=31:proof=0:body_end=31:bind=:target=
Body:34:DATATYPE:tree:src=34-35:decl_end=34:proof=0:body_end=34:bind=ET/constructor,MKT/constructor:target=
Body:38:FUN:height:src=38-39:decl_end=38:proof=0:body_end=38:bind=:target=
Body:40:DEF:a:src=40-42:decl_end=42:proof=0:body_end=42:bind=:target=
Body:43:DEF:b:src=43-46:decl_end=45:proof=0:body_end=45:bind=:target=
Body:47:RECORD:st:src=47-51:decl_end=50:proof=0:body_end=50:bind=wa_cond/field,wa_body/field:target=
Body:52:FUN:ff:src=52-56:decl_end=55:proof=0:body_end=55:bind=:target=
Body:57:DEF:transient:src=57-61:decl_end=57:proof=0:body_end=57:bind=:target=
EXPECT
)

expect_dump "a note does not end a declaration, and does not extend one" \
  "$PARSE/body" "$BODY_WANT"

# The containment invariant, asserted rather than read off the table above: for
# every entry in the fixture, the body must stay inside the entry's own span.
# This is the check that rejected the wider variant, and a table alone cannot
# make it -- a future expectation edited to match a regression would edit the
# violation in with it.
viol=$(isabelle query dump-entries "$PARSE/body" --spans 2>"$OUT/e.txt" |
  sed -n 's/^.*:src=[0-9]*-\([0-9]*\):decl_end=[0-9]*:proof=[0-9]*:body_end=\([0-9]*\)$/\1 \2/p' |
  awk '$2 > $1 { n++ } END { print n + 0 }')
if [ "$viol" = "0" ]; then
  note "and no body reaches past its own thy_end" "0 violations"
else
  bad "and no body reaches past its own thy_end" "$viol violations"
fi

# --------------------------------------------------------------------------
# S3 -- the citation graph.  Four fixtures, one per item, each its own ROOT so
# a count in one cannot be moved by an edit to another.
# --------------------------------------------------------------------------

# ==========================================================================
echo
echo "12. [symbol-body-tokens] -- a \\<...> token's body is not a fact name"
# ==========================================================================
#
# `[\w']+` reaches straight into an Isabelle symbol: `\<lambda>` yields
# `lambda`, `\<le>` yields `le`, `\<^sub>` yields `sub`, `\<close>` yields
# `close` -- and the AFP declares 7 entries named `lambda`, 37 named `le` and
# 27 named `sub`, so every lambda written anywhere in the corpus was recorded
# as a citation of all seven.  The graph now runs its word pass over the
# symbol-BLANKED line and `Commands.isa_word_pattern` grows the two matching
# lookbehinds, so `callers` and `unused` cannot disagree about the same lemma.
#
# The GUARD half is the one that matters: the word pass was added for a
# reason, and narrowing it must not undo that reason.  Blanking the delimiters
# must not blank what they contain.
#
# Upstream's Sym / Guard theories verbatim.  Sym declares four entries named
# after symbol bodies and cites none of them; Guard writes the three shapes
# the word pass exists for.  Line numbers are part of the expectation.

SYMFIX="$OUT/symbols"
mkdir -p "$SYMFIX" || exit 2

cat >"$SYMFIX/ROOT" <<'ROOT'
session P9_Sym = HOL +
  options [document = false]
  theories
    Sym
    Guard
ROOT

cat >"$SYMFIX/Sym.thy" <<'THY'
theory Sym
imports Main
begin

definition lambda :: "nat" where "lambda = 0"
definition le :: "nat" where "le = 1"
definition sub :: "nat" where "sub = 2"
definition close :: "nat" where "close = 3"

lemma writes_symbols: "\<forall>x\<^sub>1. (\<lambda>y. y) x\<^sub>1 \<le> x\<^sub>1"
  using \<open>True\<close> by simp

end
THY

cat >"$SYMFIX/Guard.thy" <<'THY'
theory Guard
imports Main
begin

definition iso_transaction :: "nat" where "iso_transaction = 0"
definition "merge_rt_F\<^sub>m" :: "nat" where "merge_rt_F\<^sub>m = 1"
definition inside :: "nat" where "inside = 2"

lemma abuts: "iso_transaction\<^sub>h = iso_transaction\<^sub>h" by simp
lemma symbolic: "merge_rt_F\<^sub>m = merge_rt_F\<^sub>m" by simp
lemma in_cartouche: \<open>inside = inside\<close> by simp

end
THY

ROOT_DIR="$SYMFIX"

echo
echo "12a. the symbol bodies cite nothing"

# Sym:10 writes a lambda, a subscript and a le, and Sym:11 a cartouche.  None
# of the four is a citation, so each of the four entries named after one has
# no caller at all -- and `writes_symbols` cites nothing.
for name in lambda le sub close; do
  expect "callers $name" 0 "No callers found for '$name'." "" callers "$name"
done
expect "callees writes_symbols" 0 \
  "No references found in writes_symbols's body." "" callees writes_symbols

echo
echo "12b. and the three shapes the word pass exists for still work"

expect "a name ABUTTING a symbol is still a maximal run" 0 \
  '1 caller(s) of iso_transaction:

  Guard:9  abuts (LEMMA) 9..9  lemma abuts: "iso_transaction\<^sub>h = iso_transaction\<^sub>h" by simp' \
  "" callers iso_transaction
expect "a SYMBOLIC name is still one sym_re token" 0 \
  '1 caller(s) of merge_rt_F\<^sub>m:

  Guard:10  symbolic (LEMMA) 10..10  lemma symbolic: "merge_rt_F\<^sub>m = merge_rt_F\<^sub>m" by simp' \
  "" callers 'merge_rt_F\<^sub>m'
expect "a name INSIDE a cartouche is still cited" 0 \
  '1 caller(s) of inside:

  Guard:11  in_cartouche (LEMMA) 11..11  lemma in_cartouche: \<open>inside = inside\<close> by simp' \
  "" callers inside

echo
echo "12c. the graph agrees, in BOTH reach modes"

# 11 declared names, 3 of them cited (the three guards): 8 dead.  Asked in
# both modes on purpose -- this is not a visibility artefact, and could not be
# fixed by one.  Before the blanking it was 4, because the four symbol-named
# entries looked cited from Sym:10 and Sym:11.
expect "unused -c, closure"        0 "8" "" unused -c
expect "unused -c, --reach name"   0 "8" "" unused --reach name -c

# ==========================================================================
echo
echo "13. [name-is-not-identity] -- a theory NAME is not a section's identity"
# ==========================================================================
#
# Upstream's alpha/beta fixture verbatim: two entries, each with its own ROOT,
# each declaring a `Base` and a `Preliminaries`.  Alpha's lines 5..8 are a
# `text` block that MENTIONS the cited name; beta's line 6 is a LIVE citation
# of it, at the line alpha calls prose.  One fixture, both directions:
#
#   name-keyed, alpha winning   beta's live citation is masked as alpha's prose
#   name-keyed, beta winning    alpha's prose is unmasked and counted as a use
#
# and either way the OWNER of a line comes from the wrong file.  Keyed by
# `sec.path` there is one right answer and it does not depend on load order,
# which is why every expectation below is stated as "the owner is an entry of
# the file the row names".

NIFIX="$OUT/collide"
mkdir -p "$NIFIX/alpha" "$NIFIX/beta" || exit 2

for e in Alpha Beta; do
  d=$(printf '%s' "$e" | tr 'AB' 'ab')
  printf 'session %s = HOL +\n  theories\n    Base\n    Preliminaries\n' \
    "$e" >"$NIFIX/$d/ROOT"
  printf 'theory Base\nimports Main\nbegin\nlemma target: "True" by simp\nend\n' \
    >"$NIFIX/$d/Base.thy"
done

cat >"$NIFIX/alpha/Preliminaries.thy" <<'THY'
theory Preliminaries
imports Base
begin
lemma a_head: "True" by simp
text \<open>
  a paragraph about target
  still prose
\<close>
lemma a_tail: "True" by simp
end
THY

cat >"$NIFIX/beta/Preliminaries.thy" <<'THY'
theory Preliminaries
imports Base
begin
lemma b_head: "True" by simp

lemma b_cites: "True" using target by simp

lemma b_tail: "True" by simp
end
THY

ROOT_DIR="$NIFIX"

echo
echo "13a. one citation, from the file that makes it"

# ONE live citation of `target` in the whole fixture: beta's line 6.  Alpha's
# line 6 says the word inside a `text` block, which is documentation.  The
# owner is `b_cites`, beta's own entry -- not `a_tail`, which owns line 6 of
# the OTHER file.
expect "callers target" 0 \
  '1 caller(s) of target:

  beta/Preliminaries:6  b_cites (LEMMA) 6..7  lemma b_cites: "True" using target by simp' \
  "" callers target
expect "and --external skips the FILES that declare it, not the name" 0 \
  '1 caller(s) of target:

  beta/Preliminaries:6  b_cites (LEMMA) 6..7  lemma b_cites: "True" using target by simp' \
  "" callers target --external
# Not a visibility artefact either: both `Preliminaries` really do import a
# `Base` that declares `target`, so the closure drops nothing here.
expect "and the same under --reach name" 0 "1" "" callers target --reach name -c

echo
echo "13b. the graph, and the phantom edge that is not there"

# One edge.  Under a name-keyed def-site map, alpha's `lemma a_head` line has
# no def site (beta's map is in force), so `b_head` read as citing `a_head`.
expect "graph citation has exactly the one real edge" 0 \
  'digraph citation {
  rankdir=LR;
  "a_head";
  "a_tail";
  "b_cites";
  "b_head";
  "b_tail";
  "target";
  "b_cites" -> "target";
}' "" graph -f dot
# 6 entries, one of them cited: 5 dead.  It was 3 with the collapse, because
# the phantom edge and the prose mention kept two alive.
expect "unused -c" 0 "5" "" unused -c

echo
echo "13c. the LINE INDEX: every owner is an entry of its own file"

# `grep` and `methods` read the same index, and it is the index the collapse
# moved 381,710 AFP lines in.  Owners in load order: alpha's Base, alpha's
# Preliminaries (a_head at 4, a_tail at 9 -- the prose block belongs to the
# entry it introduces), then beta's.
expect "grep True names the right owner for each file" 0 \
  '7 live match(es) for '"'"'True'"'"':

  alpha/Base.thy:4           target (LEMMA)
    lemma target: "True" by simp
  alpha/Preliminaries.thy:4  a_head (LEMMA)
    lemma a_head: "True" by simp
  alpha/Preliminaries.thy:9  a_tail (LEMMA)
    lemma a_tail: "True" by simp
  beta/Base.thy:4            target (LEMMA)
    lemma target: "True" by simp
  beta/Preliminaries.thy:4   b_head (LEMMA)
    lemma b_head: "True" by simp
  beta/Preliminaries.thy:6   b_cites (LEMMA)
    lemma b_cites: "True" using target by simp
  beta/Preliminaries.thy:8   b_tail (LEMMA)
    lemma b_tail: "True" by simp' \
  "" grep True
expect "and so does methods, which reads the same index" 0 \
  "7 use(s) of method 'simp':

  alpha/Base:4           target (LEMMA) 4..4  lemma target: \"True\" by simp
  alpha/Preliminaries:4  a_head (LEMMA) 4..4  lemma a_head: \"True\" by simp
  alpha/Preliminaries:9  a_tail (LEMMA) 5..9  lemma a_tail: \"True\" by simp
  beta/Base:4            target (LEMMA) 4..4  lemma target: \"True\" by simp
  beta/Preliminaries:4   b_head (LEMMA) 4..5  lemma b_head: \"True\" by simp
  beta/Preliminaries:6   b_cites (LEMMA) 6..7  lemma b_cites: \"True\" using target by simp
  beta/Preliminaries:8   b_tail (LEMMA) 8..8  lemma b_tail: \"True\" by simp" \
  "" methods simp

# The LOCUS column above is QUALIFIED, and that is S4's [disambig-loci]
# landing on the fixture S3 wrote: `beta/Preliminaries:6` names one theory
# where `Preliminaries:6` named two.  These rows were the bare stem until then,
# and they are now byte-identical with the oracle's (checked).

# ==========================================================================
echo
echo "14. [import-leaf] -- a token the resolver cannot map is a hole"
# ==========================================================================
#
# Upstream's LeafFixture: one session, one subdirectory, both spellings of the
# same rule.  The ROOT names a theory `"Sub/Leaf"`, so that IS its name here;
# `Sub/Leaf` reaches its sibling with `imports "../Base"`, and `Bare` reaches
# `Sub/Leaf` by its LEAF.  Neither resolves by an exact name or by the tail
# after the last `.` -- that rule finds the `.` of `..` -- so before the leaf
# rule both were `[out-of-project]` and the closure had a hole across them.
#
# `Alien` is the counterweight: the leaf rules must not invent a local theory
# out of a genuinely external import.

LEAFFIX="$OUT/leaf"
mkdir -p "$LEAFFIX/Sub" || exit 2

printf 'session Demo = HOL +\n  theories\n    Base\n    "Sub/Leaf"\n    Bare\n    Alien\n' \
  >"$LEAFFIX/ROOT"
printf 'theory Base\nimports Main\nbegin\ndefinition base :: "nat" where "base = 0"\nend\n' \
  >"$LEAFFIX/Base.thy"
printf 'theory Leaf\nimports "../Base"\nbegin\nlemma leaf_uses: "base = base" by simp\nend\n' \
  >"$LEAFFIX/Sub/Leaf.thy"
printf 'theory Bare\nimports Leaf\nbegin\nlemma bare_uses: "base = base" by simp\nend\n' \
  >"$LEAFFIX/Bare.thy"
printf 'theory Alien\nimports "../nowhere/Absent" "HOL-Library.FuncSet"\nbegin\nlemma alien_uses: "base = base" by simp\nend\n' \
  >"$LEAFFIX/Alien.thy"

ROOT_DIR="$LEAFFIX"

echo
echo "14a. deps / uses resolve both spellings"

expect "deps Bare -- by the leaf of a path-spelled THEORY" 0 \
  'Direct imports of Bare:
  Sub/Leaf  (5 src lines, 1 entries)  [direct]' "" deps Bare
expect "deps -r Bare -- and on through the ../ import" 0 \
  'Import-transitive dependencies of Bare:
  Sub/Leaf  (5 src lines, 1 entries)  [direct]
  Base  (5 src lines, 1 entries)  [depth 1]
  Main  [out-of-project]' "" deps -r Bare
expect "uses -r Base -- the same edges, reversed" 0 \
  'Theories that import Base (transitively):
  Sub/Leaf  (5 src lines, 1 entries)  [direct]
  Bare  (5 src lines, 1 entries)  [depth 1]' "" uses -r Base

echo
echo "14b. external stays external"

# Neither rule fires without a `/` in the token or in a loaded name, and an
# external session-qualified import has none.  A leaf rule that invented a
# local theory here would be worse than the hole it fixes.
expect "deps Alien -- both tokens keep the raw spelling" 0 \
  'Direct imports of Alien:
  ../nowhere/Absent  [out-of-project]
  HOL-Library.FuncSet  [out-of-project]' "" deps Alien

echo
echo '14c. the closure crosses the edge, and `graph imports` has no phantom'

# `base` is cited on three lines; the two that can SEE `Base` are attributed
# and Alien's is not, because Alien's closure is itself alone.  `--reach name`
# is the counterweight: all three, which is what makes the 2 a filter result
# rather than a hole.
expect "callers base -c, closure" 0 "2" "" callers base -c
expect "callers base -c, --reach name" 0 "3" "" callers base -c --reach name
expect "refs Bare -- the citation reaches past the direct import" 0 \
  'Bare references 1 name(s) from 1 theory/theories:

  Base  [import depth 1]  1
      base  (1)

  Direct imports no citation reaches (1): Sub/Leaf
  Cited but not directly imported (1): Base' "" refs Bare
expect "graph imports -- Sub/Leaf is a node, not an external token" 0 \
  'digraph imports {
  rankdir=LR;
  "Alien";
  "Bare";
  "Base";
  "Sub/Leaf";
  "../nowhere/Absent" [style=dashed];
  "HOL-Library.FuncSet" [style=dashed];
  "Main" [style=dashed];
  "Alien" -> "../nowhere/Absent";
  "Alien" -> "HOL-Library.FuncSet";
  "Bare" -> "Sub/Leaf";
  "Base" -> "Main";
  "Sub/Leaf" -> "Base";
}' "" graph imports -f dot

echo
echo "14d. a shared theory name UNIONS its edges"

# Upstream's UnionFixture: two entries, one `Dup` each, importing different
# targets.  `Cite` imports `Dup` and cites both targets.  A last-wins
# adjacency would carry one of the two and delete the other's citation --
# silently, and which one depends on load order, so this asserts BOTH rather
# than one.  `deps -r`, which prints ONE dependency per clause, keeps the
# last-wins section on both implementations.

UNIONFIX="$OUT/union"
mkdir -p "$UNIONFIX/alpha" "$UNIONFIX/beta" || exit 2

printf 'session Alpha = HOL +\n  theories\n    A_Target\n    Dup\n    Cite\n' \
  >"$UNIONFIX/alpha/ROOT"
printf 'theory A_Target\nimports Main\nbegin\ndefinition a_target :: "nat" where "a_target = 0"\nend\n' \
  >"$UNIONFIX/alpha/A_Target.thy"
printf 'theory Dup\nimports A_Target\nbegin\nend\n' >"$UNIONFIX/alpha/Dup.thy"
printf 'theory Cite\nimports Dup\nbegin\nlemma cites_a: "a_target = a_target" by simp\nlemma cites_b: "b_target = b_target" by simp\nend\n' \
  >"$UNIONFIX/alpha/Cite.thy"
printf 'session Beta = HOL +\n  theories\n    B_Target\n    Dup\n' >"$UNIONFIX/beta/ROOT"
printf 'theory B_Target\nimports Main\nbegin\ndefinition b_target :: "nat" where "b_target = 0"\nend\n' \
  >"$UNIONFIX/beta/B_Target.thy"
printf 'theory Dup\nimports B_Target\nbegin\nend\n' >"$UNIONFIX/beta/Dup.thy"

ROOT_DIR="$UNIONFIX"

expect "cites_a is attributed"  0 "1" "" callers a_target -c
expect "cites_b is attributed too -- the union, not a tiebreak" 0 "1" "" callers b_target -c
expect "deps -r Cite keeps the last-wins section, as both engines do" 0 \
  'Import-transitive dependencies of Cite:
  Dup  (4 src lines, 0 entries)  [direct]
  B_Target  (5 src lines, 1 entries)  [depth 1]
  Main  [out-of-project]' "" deps -r Cite

# ==========================================================================
echo
echo "15. [citation-reach] -- a declaration is an entry of ANY tag"
# ==========================================================================
#
# The declared set the filter consults is not the graph's NODE set.  The nodes
# are facts, so a `locale rev` is not one; but a theory that declares `rev` as
# a LOCALE can plainly see the `rev` a line there names, and scoping the
# declared set to the citable tags dropped the edge whenever the only visible
# same-name entry carried another tag.
#
#   X   datatype colour = Bar | Baz   (line 4)   locale rev   (line 5)
#   Y   lemma rev                     (line 4)
#   Z   imports X; cites rev at 4, mentions Bar at 5
#   W   imports Main only; mentions Bar at 4
#
# Z's `rev` can only be X's LOCALE -- Y is not in Z's closure -- so an edge
# that exists at all proves the tag is not consulted.

DECLFIX="$OUT/declared"
mkdir -p "$DECLFIX" || exit 2

cat >"$DECLFIX/ROOT" <<'ROOT'
session P9_Decl = HOL +
  options [document = false]
  theories
    X
    Y
    Z
    W
ROOT

cat >"$DECLFIX/X.thy" <<'THY'
theory X
imports Main
begin
datatype colour = Bar | Baz
locale rev = fixes r :: nat
end
THY

cat >"$DECLFIX/Y.thy" <<'THY'
theory Y
imports Main
begin
lemma rev: "True" by simp
end
THY

cat >"$DECLFIX/Z.thy" <<'THY'
theory Z
imports X
begin
lemma z_cites_rev: "True" using rev by simp
lemma z_mentions_bar: "Bar = Bar" by simp
end
THY

cat >"$DECLFIX/W.thy" <<'THY'
theory W
imports Main
begin
lemma w_mentions_bar: "Bar = Bar" by simp
end
THY

ROOT_DIR="$DECLFIX"

echo
echo "15a. the edge a citable-tag-only declared set deleted"

expect "callees z_cites_rev -- the LOCALE is what it can see" 0 \
  '1 callee(s) of z_cites_rev:

  rev (LOCALE) — X [L5]' "" callees z_cites_rev
expect "callers -r rev -- the same edge, read backwards" 0 \
  '1 transitive caller(s) of rev:

    z_cites_rev (LEMMA) — Z [L4]' "" callers -r rev
expect "graph citation carries it" 0 \
  'digraph citation {
  rankdir=LR;
  "rev";
  "w_mentions_bar";
  "z_cites_rev";
  "z_mentions_bar";
  "z_cites_rev" -> "rev";
}' "" graph -f dot
expect "and refs rolls it up to X" 0 \
  'Z references 1 name(s) from 1 theory/theories:

  X  [direct import]  1
      rev  (1)' "" refs Z
# 4 citable names, one of them cited: 3 dead.  It was 4 before, because the
# only edge in the fixture was the one being dropped.
expect "unused -c" 0 "3" "" unused -c

echo
echo "15b. D14 -- a BOUND name is a declaration too (deliberate, see DIVERGENCES)"

# `Bar` is no ENTRY: it is a name `datatype colour` BINDS, in X.  Upstream
# consults entries only, so `declared_in["Bar"]` is empty there and the filter
# declines to constrain anything -- oracle 0.8.1 answers 2 here, Z:5 and W:4.
#
# This port counts a bound name as a declaration, so `Bar` IS declared, in X,
# and W imports only Main: W:4 cannot be naming X's constructor.  1 is
# therefore the answer, and it is D13's rule applied to a constructor rather
# than an exception to it.  `codeqs Cons` -- a verb whose whole subject is a
# bound name -- needs the same reading, so `callers` uses it too rather than
# carrying two answers to one question.
expect "callers Bar -- the port drops W (upstream: 2)" 0 \
  '1 caller(s) of Bar:

  Z:5  z_mentions_bar (LEMMA) 5..5  lemma z_mentions_bar: "Bar = Bar" by simp' \
  "" callers Bar
# And the compatibility mode agrees with upstream exactly, which is what makes
# the line above a FILTER decision rather than a missing hit.
expect "callers Bar --reach name -- both, as the oracle has it" 0 \
  '2 caller(s) of Bar:

  Z:5  z_mentions_bar (LEMMA) 5..5  lemma z_mentions_bar: "Bar = Bar" by simp
  W:4  w_mentions_bar (LEMMA) 4..4  lemma w_mentions_bar: "Bar = Bar" by simp' \
  "" callers Bar --reach name

echo
echo "15c. the modes are the same where nothing is out of reach"

# Everything else in this fixture is inside its own closure, so the two modes
# must agree -- a filter that moved these would be dropping something real.
expect "unused -c, --reach name"  0 "3" "" unused --reach name -c
expect "callees z_cites_rev -c, --reach name" 0 "1" "" callees z_cites_rev --reach name -c

# ==========================================================================
echo
echo "16. [disambig-names] -- a label qualifies only as far as it needs"
# ==========================================================================
#
# Fixture C: three entries, each with its own ROOT, between them declaring
# THREE theories called `Base` and TWO called `Examples`, plus one called
# `Unique`.  That is the AFP's own shape -- 461 of its theory names are used
# more than once -- and it is what a single-session fixture cannot show.
#
# The rule, applied by hand.  A section's tuple is its resolved parent
# directory's components with its DECLARED name on the end, and the label is
# the shortest SUFFIX of that tuple which is unique among the sections loaded:
#
#   depth 1   Base x3, Examples x2, Unique x1   -> only `Unique` settles
#   depth 2   alpha/Base, beta/Base, solo/Base,
#             alpha/Examples, beta/Examples     -> all five settle
#
# So the shared root prefix (this probe's own $OUT) never appears: it is on
# every tuple and separates nothing.  The resolver matches the SAME tuple as a
# suffix, which is what makes a printed label valid input.

FIXC="$OUT/fixC"
mkdir -p "$FIXC/alpha" "$FIXC/beta" "$FIXC/solo" || exit 2

for e in alpha beta solo; do
  cat >"$FIXC/$e/Base.thy" <<'THY'
theory Base
imports Main
begin
lemma shared: "True" by simp

lemma structured: "True \<and> True"
proof
  show "True" by simp
  show "True" by simp
qed
end
THY
done

# alpha's `Examples` is FIVE lines and beta's is NINE, with different lemmas
# at every line they share.  That asymmetry is the point: an owner column or a
# context line read out of the wrong file is visible rather than plausible.
cat >"$FIXC/alpha/Examples.thy" <<'THY'
theory Examples
imports Base
begin
lemma a_owner: "True" using shared by simp
end
THY

cat >"$FIXC/beta/Examples.thy" <<'THY'
theory Examples
imports Base
begin
lemma b_pad: "True" by simp

lemma b_owner: "True" using shared by simp

lemma b_after: "True" sorry
end
THY

cat >"$FIXC/solo/Unique.thy" <<'THY'
theory Unique
imports Base
begin
lemma only_one: "True" using shared by simp
end
THY

cat >"$FIXC/alpha/ROOT" <<'ROOT'
session Alpha = HOL +
  theories
    Base
    Examples
ROOT

cat >"$FIXC/beta/ROOT" <<'ROOT'
session Beta = HOL +
  theories
    Base
    Examples
ROOT

cat >"$FIXC/solo/ROOT" <<'ROOT'
session Solo = HOL +
  theories
    Base
    Unique
ROOT

# A file that is not a theory, for `file_locus`: it has no theory name to
# qualify, so it must come back as itself.
printf 'a note mentioning shared\n' >"$FIXC/notes.md"

ROOT_DIR="$FIXC"

# Two projections, for the rows whose TAIL carries trailing blanks (an empty
# statement preview, a blank context line).  Pinning those as literals would
# put invisible bytes in this file that any editor would silently strip, and a
# probe nobody can edit safely is not one.  What §16/§17 are about is the
# LOCUS column, so that is what these compare.
loci() { isabelle query -R "$ROOT_DIR" "$@" 2>/dev/null |
  sed -n 's/^  \([^ ][^ ]*\).*/\1/p'; }
col1() { isabelle query -R "$ROOT_DIR" "$@" 2>/dev/null | awk 'NF {print $1}'; }

# expect_proj NAME PROJ WANT ARGS...
expect_proj() {
  local name=$1 proj=$2 want=$3; shift 3
  local got; got=$("$proj" "$@")
  if [ "$got" = "$want" ]; then note "$name" "$(printf '%s' "$got" | wc -l) rows"
  else bad "$name" "got [$got] wanted [$want]"; fi
}

echo
echo "16a. the emitter -- \`largest\` labels against the LOADED CORPUS"

# Sizes, by hand: `structured` spans 6..10 (5 lines) in each of the three
# `Base`es; `shared` 4..5 (2, the blank line 5 belongs to it); beta's `b_pad`
# 4..5 and `b_owner` 6..7 likewise; the three one-line lemmas are 1.  Ties keep
# load order (alpha, beta, solo -- ROOTs sorted by path).
expect "largest, every colliding name qualified and `Unique` bare" 0 \
'Top 11 largest entries:

 Lines  Tag       Name                                        Theory  (span)
------  --------  ------------------------------------------  ------
     5  LEMMA     structured                                  alpha/Base  (6..10)
     5  LEMMA     structured                                  beta/Base  (6..10)
     5  LEMMA     structured                                  solo/Base  (6..10)
     2  LEMMA     shared                                      alpha/Base  (4..5)
     2  LEMMA     shared                                      beta/Base  (4..5)
     2  LEMMA     b_pad                                       beta/Examples  (4..5)
     2  LEMMA     b_owner                                     beta/Examples  (6..7)
     2  LEMMA     shared                                      solo/Base  (4..5)
     1  LEMMA     a_owner                                     alpha/Examples  (4..4)
     1  LEMMA     b_after                                     beta/Examples  (8..8)
     1  LEMMA     only_one                                    Unique  (4..4)' "" largest

# Scope FIRST, label second.  After `largest -N 3 beta/Examples` the loaded
# list IS that one theory, so nothing collides and the label is bare -- the
# scope is the corpus, and the corpus is what the label is computed over.  A
# label scoped to the ROWS instead would have been `beta/Examples` here and
# `Examples` in a `-N 3` that happened to show only one; that is the reading
# upstream rejected, and the reason is on this line.
expect "a theory scope makes the label bare again" 0 \
'Top 3 largest entries:

 Lines  Tag       Name                                        Theory  (span)
------  --------  ------------------------------------------  ------
     2  LEMMA     b_pad                                       Examples  (4..5)
     2  LEMMA     b_owner                                     Examples  (6..7)
     1  LEMMA     b_after                                     Examples  (8..8)' "" \
  largest -N 3 beta/Examples

# The label is a LOCUS, not a name: `format_name_line` still prints the
# theory as declared, here and in `find --names`, exactly as the oracle does.
expect "a --names line still names the theory as declared" 0 \
'shared (LEMMA) — Base [src 4..5, body 4..4, 1/2 lines]
structured (LEMMA) — Base [src 6..10, 5 lines]' "" theory solo/Base --names

echo
echo "16b. the resolver -- the label is valid input, and only if UNIQUE"

# beta's `Examples` has three entries, alpha's has one.  Before the
# unique-suffix step this answered 1: `beta/Examples` fell past the exact-name
# match to the stem and resolved, silently, to alpha's.
expect "theory beta/Examples -c -- beta's three, not alpha's one" 0 "3" "" \
  theory beta/Examples -c
expect "theory alpha/Examples.thy -c -- the suffix, .thy stripped" 0 "1" "" \
  theory alpha/Examples.thy -c
expect "theory alpha/Base.thy -c" 0 "2" "" theory alpha/Base.thy -c
# A bare name never reaches the suffix step; it is the exact-name branch, and
# first-in-load-order wins, which is alpha's.
expect "theory Examples -c -- a bare name is first-wins, as before" 0 "1" "" \
  theory Examples -c
# `Base.thy` DOES reach it (it carries a suffix) and matches three sections,
# so it is ambiguous and falls THROUGH to the stem fallback -- which is
# first-wins again.  Only a unique hit counts.
expect "theory Base.thy -c -- ambiguous, so it falls through to the stem" 0 "2" "" \
  theory Base.thy -c

# ==========================================================================
echo
echo "17. [disambig-loci] -- every printed theory:line carries the label"
# ==========================================================================
#
# The eight emitters that were still printing a bare name, on the same
# fixture.  Two things are being pinned at once and both matter: the LABEL
# (the locus names one theory) and the ALIGNMENT (`loc_w` is computed over the
# labels, so the column is as wide as the widest qualified locus).

echo
echo "17a. callers -- the locus, the owner, and the context line"

# `shared` is declared in all three `Base`es and cited once in each of the
# three theories that import one.  The owner column and the `-U` context are
# read from the hit's OWN section: alpha's `Examples` is five lines, so line 5
# is its `end`, while beta's line 7 is blank.  Reading either out of the other
# file would show here.
expect "callers shared -- three loci, three owners, one column" 0 \
'3 caller(s) of shared:

  alpha/Examples:4  a_owner (LEMMA) 4..4  lemma a_owner: "True" using shared by simp
  beta/Examples:6   b_owner (LEMMA) 6..7  lemma b_owner: "True" using shared by simp
  Unique:4          only_one (LEMMA) 4..4  lemma only_one: "True" using shared by simp' \
  "" callers shared

expect_proj "callers shared -U 1 -- the context line keeps the label" loci \
'alpha/Examples:4
alpha/Examples:5-
beta/Examples:6
beta/Examples:7-
Unique:4
Unique:5-' callers shared -U 1

echo
echo "17b. methods -- both modes"

# 13 `simp` introducers: four per `Base` (line 4, and lines 8 and 9 inside
# `structured`) is three each = 9, plus alpha:4, beta:4, beta:6 and Unique:4.
expect "methods simp" 0 \
'13 use(s) of method '"'"'simp'"'"':

  alpha/Base:4      shared (LEMMA) 4..5  lemma shared: "True" by simp
  alpha/Base:8      structured (LEMMA) 6..10  show "True" by simp
  alpha/Base:9      structured (LEMMA) 6..10  show "True" by simp
  alpha/Examples:4  a_owner (LEMMA) 4..4  lemma a_owner: "True" using shared by simp
  beta/Base:4       shared (LEMMA) 4..5  lemma shared: "True" by simp
  beta/Base:8       structured (LEMMA) 6..10  show "True" by simp
  beta/Base:9       structured (LEMMA) 6..10  show "True" by simp
  beta/Examples:4   b_pad (LEMMA) 4..5  lemma b_pad: "True" by simp
  beta/Examples:6   b_owner (LEMMA) 6..7  lemma b_owner: "True" using shared by simp
  solo/Base:4       shared (LEMMA) 4..5  lemma shared: "True" by simp
  solo/Base:8       structured (LEMMA) 6..10  show "True" by simp
  solo/Base:9       structured (LEMMA) 6..10  show "True" by simp
  Unique:4          only_one (LEMMA) 4..4  lemma only_one: "True" using shared by simp' \
  "" methods simp

expect "methods simp --names" 0 \
'  alpha/Base:4      shared (LEMMA) 4..5
  alpha/Base:8      structured (LEMMA) 6..10
  alpha/Base:9      structured (LEMMA) 6..10
  alpha/Examples:4  a_owner (LEMMA) 4..4
  beta/Base:4       shared (LEMMA) 4..5
  beta/Base:8       structured (LEMMA) 6..10
  beta/Base:9       structured (LEMMA) 6..10
  beta/Examples:4   b_pad (LEMMA) 4..5
  beta/Examples:6   b_owner (LEMMA) 6..7
  solo/Base:4       shared (LEMMA) 4..5
  solo/Base:8       structured (LEMMA) 6..10
  solo/Base:9       structured (LEMMA) 6..10
  Unique:4          only_one (LEMMA) 4..4' "" methods simp --names

echo
echo "17c. grep and sorry -- a FILE locus, so the suffix comes back"

expect "grep -- the label with .thy restored" 0 \
'3 live match(es) for '"'"'using shared'"'"':

  alpha/Examples.thy:4  a_owner (LEMMA)
    lemma a_owner: "True" using shared by simp
  beta/Examples.thy:6   b_owner (LEMMA)
    lemma b_owner: "True" using shared by simp
  Unique.thy:4          only_one (LEMMA)
    lemma only_one: "True" using shared by simp' "" grep 'using shared'

expect "grep --names" 0 \
'3 live match(es) for '"'"'using shared'"'"':

  alpha/Examples.thy:4  a_owner (LEMMA)
  beta/Examples.thy:6   b_owner (LEMMA)
  Unique.thy:4          only_one (LEMMA)' "" grep 'using shared' --names

# The only `sorry` is beta's, at line 8 -- a bare `Examples.thy:8` would have
# named alpha's five-line file, which has no line 8 at all.
expect "sorry" 0 \
'  beta/Examples.thy:8  b_after (LEMMA)
1 sorry' "" sorry

# `file_locus` is label + the path's own suffix, so a non-`.thy` positional
# reports its actual filename.  It has no theory to qualify and must not
# acquire one.
expect "a non-.thy positional stays itself" 0 \
'1 live match(es) for '"'"'shared'"'"':

  notes.md:1  a note mentioning shared' "" grep shared "$FIXC/notes.md"

echo
echo "17d. enclosing -- the ECHO is the label, not the token typed"

expect "enclosing beta/Examples:6" 0 \
'beta/Examples:6 → b_owner (LEMMA) — beta/Examples [src 6..7, body 6..6, 1/2 lines]  (in proof)' \
  "" enclosing beta/Examples:6
# A bare `Examples` resolves first-wins to ALPHA's, which is five lines -- so
# the past-end message must say `alpha/Examples`, naming what was actually
# resolved rather than echoing what was typed.  That is the whole argument for
# echoing the label.
expect "enclosing Examples:6 -- past the end of ALPHA's five lines" 0 \
"alpha/Examples:6 → (past end of alpha/Examples — 5 lines)" "" enclosing Examples:6
expect "enclosing Examples:99" 0 \
"alpha/Examples:99 → (past end of alpha/Examples — 5 lines)" "" enclosing Examples:99
# An absolute path comes back in the house `theory:line` form.
expect "an absolute path echoes as the label" 0 \
'beta/Examples:6 → b_owner (LEMMA) — beta/Examples [src 6..7, body 6..6, 1/2 lines]  (in proof)' \
  "" enclosing "$FIXC/beta/Examples.thy:6"
# The range form labels both the locus and the scope column, on every row.
expect "enclosing beta/Examples:4..6 -- both overlapping entries" 0 \
'beta/Examples:4..6 → b_pad (LEMMA) — beta/Examples [src 4..5, body 4..4, 1/2 lines]
beta/Examples:4..6 → b_owner (LEMMA) — beta/Examples [src 6..7, body 6..6, 1/2 lines]' \
  "" enclosing beta/Examples:4..6

echo
echo "17e. shape -- steps, widest, lemma"

# `shape steps` labels per PROOF.  Only `structured` has a real proof body, so
# the goal rows are Base:8 and Base:9 in each entry; the rest are the one-line
# proofs' plumbing/closing steps.
expect_proj "shape steps -a -- every locus qualified" col1 \
'location
--------------------
alpha/Base:4
alpha/Base:8
alpha/Base:9
alpha/Base:10
alpha/Examples:4
beta/Base:4
beta/Base:8
beta/Base:9
beta/Base:10
beta/Examples:4
beta/Examples:6
solo/Base:4
solo/Base:8
solo/Base:9
solo/Base:10
Unique:4' shape steps -a

# UPSTREAM RESIDUE, matched deliberately: `shape steps <theory>` filters by
# theory NAME, so scoping to `beta/Examples` also lists alpha's `Examples:4`.
# The oracle does this too (verified), and the port mirrors it rather than
# quietly diverging -- the label is right, the FILTER is the open question.
expect_proj "a theory scope still filters by NAME (upstream residue)" col1 \
'location
--------------------
alpha/Examples:4
beta/Examples:4
beta/Examples:6' shape steps -a beta/Examples

# The sort key stays the theory NAME and the line, so the six equal-width rows
# come out Base:8 x3 then Base:9 x3 -- alpha, beta, solo within each.  A sort
# on the LABEL would interleave them differently for no visible reason.
expect "shape widest -- labelled rows, sorted by the NAME" 0 \
'Top 6 widest steps by w2:

   w2 location               lemma                     statement
----- ---------------------- ------------------------  ---------
    1 alpha/Base:8           structured                True
    1 beta/Base:8            structured                True
    1 solo/Base:8            structured                True
    1 alpha/Base:9           structured                True
    1 beta/Base:9            structured                True
    1 solo/Base:9            structured                True' "" shape widest

# The header line only: the table below it pads an empty statement preview
# with trailing blanks.
hdr=$(isabelle query -R "$FIXC" shape lemma b_owner 2>/dev/null | head -1)
if [ "$hdr" = "b_owner  (LEMMA beta/Examples:6..7)" ]; then
  note "shape lemma names the theory it found the entry in" "$hdr"
else
  bad "shape lemma names the theory it found the entry in" "$hdr"
fi

# ==========================================================================
echo
echo "18. [bare-provenance] -- why a goal step states no proposition"
# ==========================================================================
#
# Fixture D: upstream's own 31-line BARE theory, every step a real AFP
# spelling.  The classification, applied by hand to each goal line -- the rule
# reads the COMMAND PREFIX (a cited cartouche already blanked), drops the goal
# command and any label, and then:
#
#   7  have a: "True" by simp        stated       -> ""
#   8  also                          in _NO_PROPOSITION_CMDS -> construction
#   9  have "True" by simp           stated       -> ""
#   10 interpret dummy_locale        in _NO_PROPOSITION_CMDS -> construction
#   11 finally show ?thesis by simp  rest is `?thesis ...`   -> construction
#   16 have nf: "\<not> False" ...   stated       -> ""
#   17 hence False by simp           head `False` is one term, tail head `by`
#                                    is a proof-tail word -> undelimited
#   18 with <cartouche> show False .. the citation is BLANKED in the prefix, so
#                                    the rest is `False ..`, tail head `..`
#                                    -> undelimited  (off the RAW line this
#                                    would find a cartouche and say unfound)
#   19 thus ?thesis by simp          -> construction
#   24 obtain x where                nothing after the command -> unfound
#   26 have (statement on line 27)   read by `statement_wrapped` -> "" (NOT
#                                    bare at all: the half of the wrapped-
#                                    statement fault that is fixed)
#   28 thus ?thesis by simp          -> construction
#
# which sums per proof to the three histograms below.  `n_bare` is unchanged
# and is exactly that sum -- the field is REFINED, not redefined, so a census
# row written before the split still compares with one written after.

FIXD="$OUT/fixD"
mkdir -p "$FIXD" || exit 2

cat >"$FIXD/ROOT" <<'ROOT'
session Bare = HOL +
  theories
    Bare
ROOT

cat >"$FIXD/Bare.thy" <<'THY'
theory Bare
imports Main
begin

lemma by_construction: "True \<and> True"
proof -
  have a: "True" by simp
  also
  have "True" by simp
  interpret dummy_locale
  finally show ?thesis by simp
qed

lemma undelimited: "True"
proof -
  have nf: "\<not> False" by simp
  hence False by simp
  with \<open>\<not> False\<close> show False ..
  thus ?thesis by simp
qed

lemma unfound: "True"
proof -
  obtain x where
    "x = (0::nat)" by simp
  have
    "True" by simp
  thus ?thesis by simp
qed

end
THY

ROOT_DIR="$FIXD"

echo
echo "18a. the three histograms, per record"

kinds=$(isabelle query -R "$FIXD" shape summary --json 2>/dev/null |
  sed -n 's/.*"lemma": "\([^"]*\)".*"n_bare": \([0-9]*\).*"bare_kinds": {\([^}]*\)}.*/\1 n_bare=\2 {\3}/p')
want_kinds='by_construction n_bare=3 {"construction": 3, "undelimited": 0, "unfound": 0}
undelimited n_bare=3 {"construction": 1, "undelimited": 2, "unfound": 0}
unfound n_bare=2 {"construction": 1, "undelimited": 0, "unfound": 1}'
if [ "$kinds" = "$want_kinds" ]; then
  note "bare_kinds, hand-computed per proof" "3 records"
else
  bad "bare_kinds, hand-computed per proof" "got [$kinds] wanted [$want_kinds]"
fi

# Every key present even at zero (a uniform schema is what makes the column
# joinable), in BARE_KINDS order, and immediately after `method_kinds` --
# which is where a reader of the record finds the field it refines.
n_adj=$(isabelle query -R "$FIXD" shape summary --json 2>/dev/null |
  grep -c '"method_kinds": {[^}]*}, "bare_kinds": {"construction": [0-9]*, "undelimited": [0-9]*, "unfound": [0-9]*}, "n_induct"')
if [ "$n_adj" = "3" ]; then
  note "all three keys, in order, right after method_kinds" "3/3 records"
else
  bad "all three keys, in order, right after method_kinds" "$n_adj/3"
fi

echo
echo "18b. what did NOT change"

# A per-STEP record carries no provenance: `bare` is a property of the step
# that the per-proof histogram aggregates, and adding it to three more record
# shapes would be a schema change nothing asked for.
if isabelle query -R "$FIXD" shape steps --json 2>/dev/null | grep -q 'bare'; then
  bad "step records carry no bare key" "found one"
else
  note "step records carry no bare key" "steps --json"
fi

# The human views are untouched: `n_bare` is the same number it always was.
expect_line() { isabelle query -R "$FIXD" shape lemma "$1" 2>/dev/null | grep -c "$2"; }
if [ "$(expect_line by_construction '^5 goals (3 bare)')" = "1" ]; then
  note "shape lemma still says '5 goals (3 bare)'" "n_bare unchanged"
else
  bad "shape lemma still says '5 goals (3 bare)'" \
    "$(isabelle query -R "$FIXD" shape lemma by_construction 2>/dev/null | grep 'goals')"
fi

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

# `expect_proj` is a second comparator (§16/§17 use it where a row's tail
# carries trailing blanks), so it needs its own perturbation: a projection
# helper that silently returned nothing would make every one of those cases
# green against an empty expectation.
ROOT_DIR="$FIXC"
saved_fail=$fail saved_checks=$checks
fail=0; checks=0
expect_proj "(perturbed) the locus column is NOT bare" loci 'Examples:4' \
  callers shared >/dev/null
perturbed=$fail
fail=$saved_fail; checks=$saved_checks
if [ "$perturbed" = "1" ]; then
  note "a wrong expectation is rejected by \`expect_proj\`" "1 failure, discarded"
else
  bad "a wrong expectation is rejected by \`expect_proj\`" "$perturbed failures"
fi

echo
printf '%d checks: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p9probe: FAILURES" >&2
  exit 1
fi
echo "P9PROBE OK"
exit 0
