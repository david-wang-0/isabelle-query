#!/usr/bin/env bash
#
# dev/p6bprobe.sh -- headless checks for P6b: find instantiations, find code
# equations.
#
# Sibling of dev/p5probe.sh and dev/p6probe.sh, which stay as they are: those
# are the P5/P6 regression gates.  This one covers the two REWRITE-ONLY verbs,
# which have no Python counterpart and therefore no oracle and no differential
# case -- so every expectation here is hand-computed, and the fixture theories
# this script writes are the place they were computed from.
#
# Three parts:
#   * the fixtures, written below (read them: they are the spec);
#   * dev/p6bprobe.scala, which pins the parsers, the two scans and the plugin
#     seam;
#   * the CLI, whose exit codes and stdout only a process can observe --
#     including the exit-code contract (unknown subject -> 1) and the
#     CLI-vs-panel cross-check.
#
# There is no display here, so nothing Swing is exercised; that is the manual
# checklist in dev/P6B-STATUS.md.
#
# Usage:
#   dev/p6bprobe.sh [CORPUS]
#
# CORPUS is a real Isabelle project for the spot checks, defaulting to
# $QUERY_TEST_AFP/Category3 -- the same variable dev/difftest.sh uses; no path
# is hard-coded.  The scratch Isabelle user home is $USER_HOME, defaulting to
# the repository's own .dev -- never the real one.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

CORPUS="${1:-${QUERY_TEST_AFP:-}/Category3}"
DISTRO_HOL="${QUERY_TEST_DISTRO:-}/HOL"

# REFUSE rather than skip.  Sections 6 guards its two corpus blocks with `-d`,
# so without the corpora this probe used to run its fixture checks, skip the
# ones that touch a real project, and still print OK -- an "OK" that had not
# looked at Category3 or src/HOL and could not have.  A gate that passes on
# less than it claims to cover is worse than one that refuses: the refusal is
# visible and the false green is not.
missing=""
[ -d "$CORPUS" ] || missing="$missing  CORPUS: $CORPUS (\$QUERY_TEST_AFP/Category3, or argument 1)"$'\n'
[ -d "$DISTRO_HOL" ] || missing="$missing  DISTRO: $DISTRO_HOL (\$QUERY_TEST_DISTRO/HOL)"$'\n'
if [ -n "$missing" ]; then
  echo "p6bprobe: the real-corpus checks in section 6 need corpora that are not here:" >&2
  printf '%s' "$missing" >&2
  echo "usage: dev/p6bprobe.sh [CORPUS]  (and set \$QUERY_TEST_AFP / \$QUERY_TEST_DISTRO)" >&2
  exit 2
fi

OUT="$REPO/.dev/p6bprobe-out"
FIX="$OUT/fixtures"
rm -rf "$OUT"
mkdir -p "$FIX" || exit 2

fail=0
checks=0
note() { checks=$((checks + 1)); echo "  ok    $1${2:+  [$2]}"; }
bad()  { checks=$((checks + 1)); fail=$((fail + 1)); echo "  FAIL  $1  [$2]"; }

# --------------------------------------------------------------------------
# The fixtures.  Every count in dev/p6bprobe.scala was read off these files
# before any code ran; the line numbers are part of the expectation, so do not
# insert lines without moving them.
# --------------------------------------------------------------------------

cat >"$FIX/ROOT" <<'ROOT'
session P6B_Fix = HOL +
  theories
    Sites_Fix
    Code_Fix
ROOT

cat >"$FIX/Sites_Fix.thy" <<'THY'
theory Sites_Fix
  imports Main
begin

section \<open>A heading that says interpretation magma is not a command\<close>

locale magma =
  fixes f :: "'a \<Rightarrow> 'a \<Rightarrow> 'a"

locale semi = magma +
  assumes assoc: "f (f x y) z = f x (f y z)"

locale magma\<^sub>2 =
  fixes h :: "'a \<Rightarrow> 'a"

locale "open" =
  fixes g :: "'a \<Rightarrow> 'a"

class mynull =
  fixes null :: 'a

interpretation nat_magma: magma "(+)" ..

global_interpretation int_magma: magma "(*)" ..

sublocale semi \<subseteq> magma f ..

sublocale magma < dual: magma "\<lambda>x y. f y x" ..

interpretation m2: magma\<^sub>2 id ..

interpretation q: "open" id ..

lemma uses_interpret: "True"
proof -
  interpret loc: magma "(+)" ..
  show ?thesis by simp
qed

instantiation nat :: mynull
begin
definition null_nat :: nat where "null_nat = (0::nat)"
instance ..
end

instantiation bool :: "{mynull, ord}"
begin
definition null_bool :: bool where "null_bool = False"
instance ..
end

instance prod :: (mynull, mynull) mynull ..

text \<open>
  interpretation magma "(-)" ..
\<close>

lemma decoy: "True"
  (* interpretation magma "(-)" .. *)
  by simp

\<^cancel>\<open>interpretation magma "(div)" ..\<close>

end
THY

cat >"$FIX/Code_Fix.thy" <<'THY'
theory Code_Fix
  imports Main
begin

fun fib :: "nat \<Rightarrow> nat" where
  "fib 0 = 0"
| "fib (Suc 0) = 1"
| "fib (Suc (Suc n)) = fib n + fib (Suc n)"

definition twice :: "nat \<Rightarrow> nat" where
  "twice n = n + n"

definition thrice :: "nat \<Rightarrow> nat" where [code]: "thrice n = n + n + n"

definition half :: "nat \<Rightarrow> nat" where
  "half n = n div 2"

definition x\<^sub>1 :: nat where
  "x\<^sub>1 = 1"

datatype mytree = Leaf | Node mytree mytree

lemma twice_alt [code]: "twice n = 2 * n"
  by (simp add: twice_def)

lemma not_a_code_eq [code_unfold]: "twice n = n + n"
  by (simp add: twice_def)

lemma mentions_only [code]: "thrice n = twice n + n"
  by (simp add: twice_def thrice_def)

lemma cond_code [code]: "n > 0 \<Longrightarrow> half n = n div 2"
  by (simp add: half_def)

lemma x1_code [code]: "x\<^sub>1 = 1"
  by (simp add: x\<^sub>1_def)

lemma node_code [code]: "Node l r = Node l r"
  by simp

declare fib.simps [code del]

lemmas twice_lemmas [code] = twice_alt

declare [[code drop: twice]]

lemma decoy_code: "True"
  (* declare twice_def [code] *)
  by simp

\<^cancel>\<open>lemma cancelled_code [code]: "twice n = 0"\<close>

end
THY

echo "fixtures: $FIX"
echo "corpus:   $CORPUS"
echo

isabelle scala_build || exit $?

# The shim jar jEdit loads is a dynamic module built by JEdit_Main at start-up,
# not by scala_build; section 4 reads its resources back out.  Build it or fail
# -- never skip and still report OK.
isabelle scala -e '{ isabelle.Isabelle_System.init();
  isabelle.Scala_Project.plugins.foreach(p => p.context().build()) }' || exit $?

SHIM="$(isabelle getenv -b JEDIT_SETTINGS)/jars/isabelle_jedit_query.jar"
if [ ! -f "$SHIM" ]; then
  echo "p6bprobe: the plugin shim jar was not built: $SHIM" >&2
  exit 1
fi

CLASSES="$REPO/.dev/p6bprobe-classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES" || exit 2

CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
CP="$CP:$(isabelle getenv -b JEDIT_JARS)"

isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p6bprobe.scala" || exit $?

export P6BPROBE_FIX="$FIX"
export P6BPROBE_OUT="$OUT"

LOG="$REPO/.dev/p6bprobe.log"
CLASSPATH="$CLASSES" isabelle java isabelle.jedit_query_dev.P6B_Probe 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"

if ! grep -q "P6BPROBE OK" "$LOG" || [ "$STATUS" -ne 0 ]; then
  echo "p6bprobe: FAILURES (exit $STATUS, log $LOG)" >&2
  exit 1
fi

# --------------------------------------------------------------------------
# The CLI: stdout, exit codes, and agreement with the panel.
# --------------------------------------------------------------------------

echo
echo "5. the CLI -- output, exit codes, and agreement with the panel"

q() { isabelle query -R "$FIX" "$@" 2>"$OUT/err.txt"; }

expect_rc() {   # expect_rc NAME RC ARGS...
  local name=$1 want=$2; shift 2
  q "$@" >"$OUT/out.txt"
  local got=$?
  if [ "$got" = "$want" ]; then note "$name" "exit $got"
  else bad "$name" "exit $got, wanted $want"; fi
}

expect_out() {  # expect_out NAME EXPECTED ARGS...
  local name=$1 want=$2; shift 2
  local got; got=$(q "$@")
  if [ "$got" = "$want" ]; then note "$name" "$got"
  else bad "$name" "got [$got], wanted [$want]"; fi
}

# The exit-code contract.  `callers` prints "No callers found" and exits 0 for a
# name it has never heard of, and that is right for a pure text scan; these two
# verbs cannot do the same, because a locale from an IMPORTED session would look
# exactly like a locale nobody instantiates.
expect_rc  "a known subject with sites exits 0"            0 instances magma
expect_rc  "a known subject with NO sites also exits 0"    0 instances semi
expect_out "and says so, in the callers phrasing"          "No instantiations found for 'semi'." instances semi
expect_rc  "an unknown subject exits 1"                    1 instances no_such_locale_xyz
expect_rc  "a wrong-kinded subject exits 1"                1 instances uses_interpret
expect_rc  "an unknown constant exits 1"                   1 codeqs no_such_const_xyz
expect_rc  "a LEMMA is not a constant"                     1 codeqs twice_alt
expect_rc  "a known constant with no equations exits 0"    0 codeqs Leaf

if [ -s "$OUT/err.txt" ]; then :; fi

# stderr must be non-empty exactly where the exit code is 1, and stdout empty.
# NOT through `q`, which redirects stderr itself: a redirection on the call is
# overridden by the one inside the function, and the check would read an empty
# file and fail for the wrong reason.
isabelle query -R "$FIX" instances no_such_locale_xyz >"$OUT/o1.txt" 2>"$OUT/e1.txt"
if [ ! -s "$OUT/o1.txt" ] && grep -q "not a locale or class" "$OUT/e1.txt"; then
  note "a refusal goes to stderr, and stdout stays empty"
else
  bad "a refusal goes to stderr, and stdout stays empty" "$(head -1 "$OUT/o1.txt")"
fi

# `-c` on an unresolved subject must NOT print a plausible 0.
q codeqs no_such_const_xyz -c >"$OUT/o2.txt" 2>/dev/null
if [ ! -s "$OUT/o2.txt" ]; then note "-c on an unresolved subject prints nothing"
else bad "-c on an unresolved subject prints nothing" "$(cat "$OUT/o2.txt")"; fi

# ... but a KNOWN subject with no sites prints an honest 0.
expect_out "-c on a known subject with no sites prints 0" "0" instances semi -c

# The counts, from the other side of the fixture.
expect_out "instances magma -c"  "5" instances magma -c
expect_out "instances mynull -c" "3" instances mynull -c
expect_out "codeqs twice -c"     "4" codeqs twice -c
expect_out "codeqs fib -c"       "2" codeqs fib -c

# --names is the loci, and they are the tool's own span grammar: they must
# round-trip through `enclosing`.
q instances magma --names >"$OUT/cli-inst.txt"
q codeqs twice --names >"$OUT/cli-code.txt"

if diff -u "$OUT/cli-inst.txt" "$OUT/panel-inst.txt" >"$OUT/inst-diff.txt" &&
   diff -u "$OUT/cli-code.txt" "$OUT/panel-code.txt" >"$OUT/code-diff.txt"; then
  note "the CLI and the panel report the same loci" \
    "$(wc -l <"$OUT/cli-inst.txt" | tr -d ' ') + $(wc -l <"$OUT/cli-code.txt" | tr -d ' ')"
else
  bad "the CLI and the panel report the same loci" "$(head -6 "$OUT/inst-diff.txt" "$OUT/code-diff.txt" | tr '\n' ' ')"
fi

# shellcheck disable=SC2046
if isabelle query -R "$FIX" enclosing $(cat "$OUT/cli-inst.txt") >"$OUT/encl.txt" 2>/dev/null &&
   [ "$(grep -c . "$OUT/encl.txt")" = "$(grep -c . "$OUT/cli-inst.txt")" ]; then
  note "every locus pastes into \`enclosing\`" "$(grep -c . "$OUT/encl.txt") loci"
else
  bad "every locus pastes into \`enclosing\`" "$(head -2 "$OUT/encl.txt")"
fi

# --------------------------------------------------------------------------
# Real-corpus spot checks.  Hand-verified against the sources, and chosen
# because each is a case a grep gets WRONG.
# --------------------------------------------------------------------------

echo
echo "6. real corpora -- spot checks verified by hand"

{
  # Category3: 38 interpretation-family lines mention `category`, but one of
  # them is `sublocale category \<subseteq> identity_functor C ..` -- a site of
  # identity_functor, not of category.  This is the case that separates the
  # scan from the grep, so both halves are checked.
  grep -rn -E '^[[:space:]]*(interpret|interpretation|global_interpretation|sublocale)\b' \
    "$CORPUS" --include='*.thy' 2>/dev/null |
    grep -E '(^|[^_[:alnum:]])category([^_[:alnum:]]|$)' |
    sed -E 's|.*/([A-Za-z0-9_]+)\.thy:([0-9]+):.*|\1:\2|' | sort >"$OUT/grep-cat.txt"
  isabelle query -R "$CORPUS" instances category --names 2>/dev/null | sort >"$OUT/q-cat.txt"
  only_grep=$(comm -23 "$OUT/grep-cat.txt" "$OUT/q-cat.txt")
  only_q=$(comm -13 "$OUT/grep-cat.txt" "$OUT/q-cat.txt")
  if [ "$only_grep" = "Functor:265" ] && [ -z "$only_q" ]; then
    note "Category3: instances category is the grep MINUS the sublocale target" \
      "$(grep -c . "$OUT/q-cat.txt") sites, grep found $(grep -c . "$OUT/grep-cat.txt")"
  else
    bad "Category3: instances category is the grep MINUS the sublocale target" \
      "only-grep=[$only_grep] only-query=[$only_q]"
  fi

  # And the excluded line is a site of the locale it actually interprets.
  if isabelle query -R "$CORPUS" instances identity_functor --names 2>/dev/null |
       grep -qx "Functor:265"; then
    note "and Functor:265 IS a site of identity_functor"
  else
    bad "and Functor:265 IS a site of identity_functor" "not reported"
  fi
}

# A distribution constant: `rev` is a primrec in List with one [code] lemma.
# `rev` is ALSO a locale-local LEMMA in Groups_List, which is exactly the
# collision that must not turn a good subject into "is a LEMMA, not a
# constant".
{
  got=$(isabelle query -R "$DISTRO_HOL" codeqs rev --names 2>/dev/null)
  if echo "$got" | grep -qx "List:87" && echo "$got" | grep -qx "List:3249"; then
    note "src/HOL: codeqs rev finds the primrec and rev_conv_fold [code]" \
      "$(echo "$got" | grep -c .) sites"
  else
    bad "src/HOL: codeqs rev finds the primrec and rev_conv_fold [code]" "$(echo "$got" | tr '\n' ' ')"
  fi

  if isabelle query -R "$DISTRO_HOL" instances comm_monoid --names 2>/dev/null |
       head -1 | grep -q ':'; then
    note "src/HOL: instances comm_monoid answers with loci"
  else
    bad "src/HOL: instances comm_monoid answers with loci" "no loci"
  fi
}

# --------------------------------------------------------------------------
# Failability: a probe that has never failed has not been tested.
# --------------------------------------------------------------------------

echo
echo "7. failability -- the harness must be able to say no"

P6BPROBE_FAILDEMO=1 CLASSPATH="$CLASSES" isabelle java isabelle.jedit_query_dev.P6B_Probe \
  >"$OUT/faildemo.log" 2>&1
demo_rc=$?
demo_fails=$(grep -c '^  FAIL' "$OUT/faildemo.log")
if [ "$demo_rc" -ne 0 ] && [ "$demo_fails" = "2" ]; then
  note "one perturbed expectation gives two FAILs and a non-zero exit" \
    "exit $demo_rc, $demo_fails failures"
else
  bad "one perturbed expectation gives two FAILs and a non-zero exit" \
    "exit $demo_rc, $demo_fails failures"
fi

echo
printf '%d checks in the shell layer: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p6bprobe: FAILURES" >&2
  exit 1
fi
echo "P6BPROBE CLI-PARITY OK"
exit 0
