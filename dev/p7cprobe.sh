#!/usr/bin/env bash
#
# dev/p7cprobe.sh -- headless checks for P7c: import-reachability filtering of
# usage attribution.
#
# Sibling of dev/p5probe.sh, p6probe.sh, p6bprobe.sh and p7probe.sh, which stay
# as they are.  This one covers a REWRITE-ONLY behaviour: the Python oracle
# attributes a citation by name alone, so there is no oracle for the filtered
# answer and no differential case for it (dev/difftest.sh pins the whole matrix
# to the compatibility mode, and says why).  Every expectation here is
# HAND-COMPUTED off the fixture theories this script writes -- read them, they
# are the spec -- and each is asked twice: once with the filter, which is the
# default, and once with `$ISABELLE_QUERY_REACHABILITY=off`, which must give
# the unfiltered answer the oracle gives.
#
# The fixtures are a real Isabelle session and every theory in them BUILDS.
# That is a constraint worth naming: the coincidences the filter removes are
# all legal Isabelle -- a bound variable spelled like another tree's constant
# -- because an uncompilable fixture would be evidence about a corpus nobody
# has.  It is also why the site verbs are checked for NOT over-pruning rather
# than for pruning: no compilable `interpretation L` can name an L its theory
# cannot see.
#
# This file also discharges watch-out 3 of dev/P7B-STATUS.md for
# `$ISABELLE_QUERY_REACHABILITY` -- the third place a new engine variable has
# to be checked is "over the socket", and §6 does that here rather than in
# dev/p7probe.sh §9b because the fixture that makes the variable observable at
# all lives in this file.
#
# Usage:
#   dev/p7cprobe.sh [CORPUS]
#
# CORPUS is a real Isabelle project for the spot check in §7, defaulting to
# $QUERY_TEST_DISTRO/HOL -- the same variable dev/difftest.sh uses; no path is
# hard-coded.  The scratch Isabelle user home is $USER_HOME, defaulting to the
# repository's own .dev -- never the real one.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

# §0-§5 and §7-§8b compare the ENGINE against a hand-computed expectation, so
# `isabelle query` must be the engine in THIS process and not whatever a
# resident server happens to hold -- and must not leave one behind.  Same pin,
# and the same reason, as p5/p6/p6b.  §6 turns it back on, per invocation.
export ISABELLE_QUERY_NO_SERVER=1

# The method/attribute table is pinned for the reason dev/difftest.sh pins it:
# unpinned it is resolved from whichever session heaps this machine happens to
# have built (dev/DIVERGENCES.md D11), and the fixture's names must be routed
# by one table on every machine.
export ISABELLE_QUERY_NAMESPACE=committed

CORPUS="${1:-${QUERY_TEST_DISTRO:-}/HOL}"

# REFUSE rather than skip: a gate that passes on less than it claims to cover
# is worse than one that refuses, because the refusal is visible.
if [ ! -d "$CORPUS" ]; then
  echo "p7cprobe: the real-corpus check in section 7 needs a corpus that is not here:" >&2
  echo "  CORPUS: $CORPUS (\$QUERY_TEST_DISTRO/HOL, or argument 1)" >&2
  echo "usage: dev/p7cprobe.sh [CORPUS]  (or set \$QUERY_TEST_DISTRO)" >&2
  exit 2
fi

OUT="$REPO/.dev/p7cprobe-out"
FIX="$OUT/fixtures"
rm -rf "$OUT"
mkdir -p "$FIX" || exit 2

CLIENT="$REPO/query_base/lib/scripts/query_client.py"

# A probe-private server name and a probe-private settings cache: nothing here
# may reach the developer's own server or their real cache file.
SERVER="p7cprobe-$$"
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-cache.json"

cleanup() {
  isabelle server -x -n "$SERVER" >/dev/null 2>&1
  # Belt to that brace: a server that died without closing its socket leaves
  # nothing for `-x` to talk to, so match on the command line as well.
  pkill -f "isabelle_server.*$SERVER" >/dev/null 2>&1
  pkill -f "server -n $SERVER" >/dev/null 2>&1
}
trap cleanup EXIT INT TERM

fail=0
checks=0
note() { checks=$((checks + 1)); echo "  ok    $1${2:+  [$2]}"; }
bad()  { checks=$((checks + 1)); fail=$((fail + 1)); echo "  FAIL  $1  [$2]"; }

# --------------------------------------------------------------------------
# The fixtures: two disjoint import trees over one base, each declaring the
# same names.
#
#                          Base
#                        /   |   \
#                    Left  Right  Lonely
#                     |
#                    Top
#
# Left and Right both declare `widget` and `gizmo`, and cannot see each other.
# Top sees Left's and only Left's.  Lonely sees NEITHER, and mentions both
# `widget` and `widget_gadget` as BOUND VARIABLES -- which is exactly the
# coincidence a name-level scan cannot tell from a citation, and exactly what
# the filter removes.
#
# Every count below was read off these files before any code ran; the line
# numbers are part of the expectation, so do not insert lines without moving
# them.
# --------------------------------------------------------------------------

cat >"$FIX/ROOT" <<'ROOT'
(* Top, Right and Lonely are declared; Left and Base arrive through the
   `imports` closure -- exactly the set `isabelle build` compiles. *)
session P7C_Fix = HOL +
  options [document = false]
  theories
    Top
    Right
    Lonely
ROOT

cat >"$FIX/Base.thy" <<'THY'
theory Base
  imports Main
begin

definition base_const :: nat where
  "base_const = 0"

end
THY

cat >"$FIX/Left.thy" <<'THY'
theory Left
  imports Base
begin

text \<open>A same-theory mention ABOVE the declaration: `widget` is a bound
  variable here, so this theory builds.  Attribution is per theory and not per
  line, so the hit stays -- with the filter and without it.\<close>

lemma left_early: "\<forall>widget. widget \<longrightarrow> widget"
  by simp

definition widget :: "nat \<Rightarrow> nat" where
  "widget n = n"

lemma left_uses: "widget 0 = 0"
  by (simp add: widget_def)

definition widget_gadget :: "nat \<Rightarrow> nat" where
  "widget_gadget n = Suc n"

locale gizmo =
  fixes z :: nat

end
THY

cat >"$FIX/Right.thy" <<'THY'
theory Right
  imports Base
begin

definition widget :: "bool \<Rightarrow> bool" where
  "widget b = b"

lemma right_uses: "widget True"
  by (simp add: widget_def)

locale gizmo =
  fixes w :: bool

interpretation right_gizmo: gizmo True ..

end
THY

cat >"$FIX/Top.thy" <<'THY'
theory Top
  imports Left
begin

lemma top_uses: "widget 1 = 1"
  by (simp add: widget_def)

interpretation top_gizmo: gizmo 1 ..

end
THY

cat >"$FIX/Lonely.thy" <<'THY'
theory Lonely
  imports Base
begin

text \<open>Imports neither Left nor Right, so `widget` and `widget_gadget` here
  are bound variables and nothing else.  A name-level scan reads them as
  citations; the import closure says they cannot be.\<close>

lemma lonely_uses: "\<forall>widget. widget \<longrightarrow> widget"
  by simp

lemma lonely_gadget: "\<forall>widget_gadget. widget_gadget \<longrightarrow> widget_gadget"
  by simp

end
THY

echo "fixtures: $FIX"
echo "corpus:   $CORPUS"
echo "server:   $SERVER"
echo

isabelle scala_build || exit $?

# `on` is the default and says nothing; `off` is the compatibility mode
# dev/difftest.sh pins the whole matrix to.  Both go through the ONE switch.
on()  { isabelle query -R "$FIX" "$@" 2>"$OUT/err.txt"; }
off() { ISABELLE_QUERY_REACHABILITY=off isabelle query -R "$FIX" "$@" 2>"$OUT/err.txt"; }

# expect NAME WANT_ON WANT_OFF ARGS...
expect() {
  local name=$1 want_on=$2 want_off=$3; shift 3
  local got_on got_off
  got_on=$(on "$@")
  got_off=$(off "$@")
  if [ "$got_on" = "$want_on" ] && [ "$got_off" = "$want_off" ]; then
    note "$name" "on $got_on / off $got_off"
  else
    bad "$name" "on [$got_on] wanted [$want_on]; off [$got_off] wanted [$want_off]"
  fi
}

# --------------------------------------------------------------------------
echo "0. the fixture is the set \`isabelle build\` would compile"
# --------------------------------------------------------------------------

# 3 declared + Left and Base through the closure.  If this moves, every count
# below is measuring a different project.
head=$(on summary | grep -m1 'entries across')
if [ "$head" = "12 entries across 5 theories  (parsed live from .thy files)" ]; then
  note "3 declared theories, Left and Base by import, 12 entries" "$head"
else
  bad "3 declared theories, Left and Base by import, 12 entries" "$head"
fi
expect "and Lonely is one of them, with its two lemmas" 2 2 theory Lonely -c

# --------------------------------------------------------------------------
echo
echo "1. \`callers\` -- a hit is kept only where the name can be seen"
# --------------------------------------------------------------------------

# `widget` is declared in Left and in Right; both declaration sites are excluded
# by the scan itself, so the name-level hits are
#   Left:9    left_early   a bound variable, ABOVE the declaration
#   Left:15   left_uses    the statement
#   Right:8   right_uses   the statement
#   Top:5     top_uses     the statement
#   Lonely:9  lonely_uses  a bound variable
# Left, Right and Top each see a declaration of `widget`.  Lonely sees neither.
expect "5 name-level hits, 4 of them possible" 4 5 callers widget -c

# LOCI only, not whole rows: the locus column is padded to the widest entry, so
# adding one longer locus re-pads every other line and a whole-row diff would
# report all five as changed.
on  callers widget -U 0 | tail -n +3 | awk '{print $1}' >"$OUT/on.txt"
off callers widget -U 0 | tail -n +3 | awk '{print $1}' >"$OUT/off.txt"
pruned=$(grep -v -x -F -f "$OUT/on.txt" "$OUT/off.txt")
if [ "$pruned" = "Lonely:9" ]; then
  note "and the one that goes is exactly Lonely's bound variable" "$pruned"
else
  bad "and the one that goes is exactly Lonely's bound variable" "[$pruned]"
fi

# --------------------------------------------------------------------------
echo
echo "2. same theory, whatever the position"
# --------------------------------------------------------------------------

# `left_early` names `widget` four lines ABOVE the `definition` that binds it,
# and is kept.  Attribution is per THEORY; the linear-position refinement is
# documented future work and is deliberately not built.
left_on=$(grep -c '^Left:' "$OUT/on.txt")
left_off=$(grep -c '^Left:' "$OUT/off.txt")
if [ "$left_on" = "2" ] && [ "$left_off" = "2" ]; then
  note "both of Left's hits survive, including the one above the declaration" \
    "on $left_on / off $left_off"
else
  bad "both of Left's hits survive, including the one above the declaration" \
    "on $left_on / off $left_off"
fi

# --------------------------------------------------------------------------
echo
echo "3. \`unused\` may honestly GROW"
# --------------------------------------------------------------------------

# `widget_gadget` is declared in Left and cited nowhere Left can be seen from.
# Its only other mention is Lonely's bound variable, which used to keep it
# alive: 7 unused entries without the filter, 8 with.
expect "one more entry is dead once the impossible caller goes" 8 7 unused -c
if on unused | grep -q '^DEF *widget_gadget *Left'; then
  note "and it is widget_gadget, declared in Left" "DEF widget_gadget Left"
else
  bad "and it is widget_gadget, declared in Left" "$(on unused | tail -1)"
fi
if off unused | grep -q 'widget_gadget'; then
  bad "which the compatibility mode does not report" "still listed"
else
  note "which the compatibility mode does not report" "absent, as the oracle has it"
fi

# --------------------------------------------------------------------------
echo
echo "4. \`callees\`, \`refs\` and \`graph\` read the same router"
# --------------------------------------------------------------------------

expect "callees of Lonely's lemma: nothing it could be citing" 0 1 \
  callees lonely_uses -c
expect "refs Lonely: no cross-theory reference it could make"  0 2 \
  refs Lonely -c

# Four edges with the filter -- `left_early`, `left_uses`, `right_uses` and
# `top_uses`, all naming `widget`; six without, the two extra being Lonely's
# two bound variables.  Read out of the JSON rather than grepped: the same
# names appear in the `nodes` array, so a line count would count them twice.
count_edges() { python3 -c 'import json,sys; print(len(json.load(sys.stdin)["edges"]))'; }
edges_on=$(on  graph citation | count_edges)
edges_off=$(off graph citation | count_edges)
if [ "$edges_on" = "4" ] && [ "$edges_off" = "6" ]; then
  note "graph citation loses exactly the two impossible edges" "on $edges_on / off $edges_off"
else
  bad "graph citation loses exactly the two impossible edges" "on $edges_on / off $edges_off"
fi

# --------------------------------------------------------------------------
echo
echo "5. the site verbs share the filter, and it does not over-prune"
# --------------------------------------------------------------------------

# `gizmo` is declared twice, in disjoint trees, and interpreted once in each.
# Top's site sees Left's `gizmo`; Right's sees its own.  Both are possible, so
# both stay -- and this is the canary for a site filter that prunes too hard.
expect "instances gizmo: two sites in two trees, both kept" 2 2 instances gizmo -c
expect "codeqs widget: the default equations of both, both kept" 2 2 codeqs widget -c

# --------------------------------------------------------------------------
echo
echo "6. the switch is per REQUEST, over the socket as well"
# --------------------------------------------------------------------------

# P7b watch-out 3: an engine variable has three places to go, and the third is
# a check that it survives the socket.  The defect this guards is the one
# dev/p7probe.sh §9b records for the namespace table -- a resident server
# reading its OWN environment, or keeping the first client's, and answering
# every later client with a pin they never asked for.

client() {
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SERVER" \
    python3 "$CLIENT" "$@"
}

client --client-status >"$OUT/status.txt" 2>&1
if grep -q "^server        $SERVER" "$OUT/status.txt"; then
  note "the probe-private server is up" "$SERVER"
else
  bad "the probe-private server is up" "$(head -2 "$OUT/status.txt" | tr '\n' ' ')"
fi

warm_off=$(ISABELLE_QUERY_REACHABILITY=off client -R "$FIX" callers widget -c 2>/dev/null)
warm_on=$(client -R "$FIX" callers widget -c 2>/dev/null)
if [ "$warm_off" = "5" ]; then
  note "a client that sets it gets the unfiltered answer (forwarded)" "$warm_off"
else
  bad "a client that sets it gets the unfiltered answer (forwarded)" "$warm_off"
fi
# Ordered deliberately: the `off` request runs FIRST, so this one fails if the
# server keeps a request's value past that request.
if [ "$warm_on" = "4" ]; then
  note "and the NEXT client, on that same server, gets the default back" "$warm_on"
else
  bad "and the NEXT client, on that same server, gets the default back" "$warm_on"
fi

# The delegating CLI reads `CLI.request_env` directly, so it forwards the
# variable with no list of its own -- P7b's reason for putting the contract
# there.  Same server, no `--no-server`.
deleg_off=$(env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SERVER" \
  ISABELLE_QUERY_REACHABILITY=off isabelle query -R "$FIX" callers widget -c 2>/dev/null)
deleg_on=$(env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SERVER" \
  isabelle query -R "$FIX" callers widget -c 2>/dev/null)
if [ "$deleg_off" = "5" ] && [ "$deleg_on" = "4" ]; then
  note "the delegating CLI forwards it too, and not stickily" "off $deleg_off / on $deleg_on"
else
  bad "the delegating CLI forwards it too, and not stickily" "off $deleg_off / on $deleg_on"
fi

cleanup

# --------------------------------------------------------------------------
echo
echo "7. one spelling turns it off, and only one"
# --------------------------------------------------------------------------

# `off` is matched case-insensitively; every other value, including the empty
# one, leaves the default alone.  A second spelling would be a second thing for
# a harness to get wrong.
for value in OFF Off off; do
  got=$(ISABELLE_QUERY_REACHABILITY="$value" isabelle query -R "$FIX" callers widget -c)
  if [ "$got" = "5" ]; then note "\`$value\` turns it off" "$got"
  else bad "\`$value\` turns it off" "$got"; fi
done
for value in "" 0 no maybe on; do
  got=$(ISABELLE_QUERY_REACHABILITY="$value" isabelle query -R "$FIX" callers widget -c)
  if [ "$got" = "4" ]; then note "\`$value\` does not" "$got"
  else bad "\`$value\` does not" "$got"; fi
done

# --------------------------------------------------------------------------
echo
echo "8. a real corpus: inside ONE import tree the filter removes nothing"
# --------------------------------------------------------------------------

# Everything in the distribution's HOL imports Main, so every theory sees
# `List.rev`: a corpus with one root is exactly where the answer must NOT move.
# This is the canary for a closure built wrong -- an import spelling the
# resolver fails to map is a hole, and a hole silently PRUNES.  It caught one:
# `HOL-MicroJava` reaches across its own subdirectories with `imports
# ../BV/Altern`, and before `Reach.import_target` learned the leaf rule this
# check was 608 against 668.
hol_on=$(isabelle query -R "$CORPUS" callers rev -c 2>/dev/null)
hol_off=$(ISABELLE_QUERY_REACHABILITY=off isabelle query -R "$CORPUS" callers rev -c 2>/dev/null)
if [ -n "$hol_on" ] && [ "$hol_on" = "$hol_off" ]; then
  note "callers rev over the corpus is unchanged by the filter" "$hol_on"
else
  bad "callers rev over the corpus is unchanged by the filter" "on $hol_on, off $hol_off"
fi

# --------------------------------------------------------------------------
echo
echo "8b. a theory the ROOT declares by PATH"
# --------------------------------------------------------------------------

# The same hole from the other end, and the one §8 did not catch.  Not because
# `src/HOL` lacks the shape -- `HOL-UNITY` declares `"Simple/Reach"`,
# `"Comp/Alloc"` and a dozen more by path -- but because §8 asks about ONE
# name, and there the hole was eating a different one (`UNITY/WFair`'s `is`,
# which `unused` called dead and no longer does).  A canary on a single name
# is worth what it costs; this is the question asked directly.
#
# A ROOT may address a theory in a subdirectory by PATH -- there is no
# per-theory `in` clause in the grammar:
#
#     theories "Nested/Nested_Fix"
#
# and both this engine and the reference then carry the theory under THAT
# spelling.  It is not what Isabelle calls it: `Thy_Header.import_name` takes
# the last path segment, and `Sessions`' own `global_theories` check spells it
# `Path.explode(thy).file_name`.  A theory in a sibling directory reaches it
# with `imports "../Nested/Nested_Fix"`, whose leaf is `Nested_Fix` -- and a
# leaf tested against a set of PREFIXED names misses.  Before the alias table
# in `Reach.build`, `codeqs quad` here answered 2 where the source has 3, with
# nothing on stderr.  (The wrong NAME is left alone on purpose; correcting it
# would move FOL, Sequents and CTT off byte parity with the reference, which
# spells them the same way.  See todo.md `[theory-name-leaf]`.)
#
# Its OWN root, so every count in §0-§7 stays where it was.  Two constants,
# both hand-computed off the four theories below:
#
#   quad  THE case.  Declared in `Nested/Nested_Fix`, which the ROOT spells
#         with its directory, and cited from `Deep/Down_Fix`, which reaches it
#         across directories with `../`.  Three sites; the one that vanished
#         is `Deep/Down_Fix:5`.
#   cube  the two NEAR-MISSES, which must go on working.  `Extra/Helper_Fix`
#         is NOT declared in the ROOT -- it arrives through the import closure
#         and so registers under its bare leaf -- and it is reached two ways:
#         `../Extra/Helper_Fix` from `Deep/Down_Fix` (a `../` import of a
#         bare-named theory) and `Extra/Helper_Fix` from `Plain_Fix` (a
#         prefixed spelling with no `../`).  Three sites, before and after.
#
# The code equations restate their definition on purpose: this fixture is
# about WHERE a site is, not what it says, and an equation that needs a real
# proof would put a proof method between the reader and the expectation.

NEST="$OUT/nested"
mkdir -p "$NEST/Nested" "$NEST/Deep" "$NEST/Extra" || exit 2

cat >"$NEST/ROOT" <<'ROOT'
session P7C_Nest = HOL +
  options [document = false]
  theories
    "Nested/Nested_Fix"
    "Deep/Down_Fix"
    Plain_Fix
ROOT

cat >"$NEST/Nested/Nested_Fix.thy" <<'THY'
theory Nested_Fix
  imports Main
begin

definition quad :: "nat \<Rightarrow> nat" where
  "quad n = 4 * n"

end
THY

cat >"$NEST/Extra/Helper_Fix.thy" <<'THY'
theory Helper_Fix
  imports Main
begin

definition cube :: "nat \<Rightarrow> nat" where
  "cube n = n * n * n"

end
THY

cat >"$NEST/Deep/Down_Fix.thy" <<'THY'
theory Down_Fix
  imports "../Nested/Nested_Fix" "../Extra/Helper_Fix"
begin

lemma quad_alt [code]: "quad n = 2 * (2 * n)"
  by (simp add: quad_def)

lemma cube_alt [code]: "cube n = n * n * n"
  by (simp add: cube_def)

end
THY

cat >"$NEST/Plain_Fix.thy" <<'THY'
theory Plain_Fix
  imports "Nested/Nested_Fix" "Extra/Helper_Fix"
begin

lemma quad_plain [code]: "quad n = 4 * n"
  by (simp add: quad_def)

lemma cube_plain [code]: "cube n = n * n * n"
  by (simp add: cube_def)

end
THY

: >"$OUT/nest-err.txt"

# expect_nest NAME WANT_ON WANT_OFF ARGS...
expect_nest() {
  local name=$1 want_on=$2 want_off=$3; shift 3
  local got_on got_off
  got_on=$(isabelle query -R "$NEST" "$@" 2>>"$OUT/nest-err.txt")
  got_off=$(ISABELLE_QUERY_REACHABILITY=off isabelle query -R "$NEST" "$@" 2>>"$OUT/nest-err.txt")
  if [ "$got_on" = "$want_on" ] && [ "$got_off" = "$want_off" ]; then
    note "$name" "on $got_on / off $got_off"
  else
    bad "$name" "on [$got_on] wanted [$want_on]; off [$got_off] wanted [$want_off]"
  fi
}

# The fixture, before anything is asked of it: 3 declared theories, Helper_Fix
# by import, 6 entries.  If this moves, every count below measures something
# else.
nest_sum=$(isabelle query -R "$NEST" summary 2>>"$OUT/nest-err.txt")
nest_head=$(printf '%s\n' "$nest_sum" | grep -m1 'entries across')
if [ "$nest_head" = "6 entries across 4 theories  (parsed live from .thy files)" ]; then
  note "3 declared theories, Helper_Fix by import, 6 entries" "$nest_head"
else
  bad "3 declared theories, Helper_Fix by import, 6 entries" "$nest_head"
fi

# The engine spells the path-declared theory the way the ROOT does, and the
# reference spells it the same way (verified: the two `summary` tables are
# byte-identical on this fixture).  Pinned so that correcting the NAME has to
# be a deliberate act with its own parity evidence, not a side effect.
if printf '%s\n' "$nest_sum" | grep -q '^| Nested/Nested_Fix |'; then
  note "the path-declared theory keeps the ROOT's spelling (a known wart)" "Nested/Nested_Fix"
else
  bad "the path-declared theory keeps the ROOT's spelling (a known wart)" "not in summary"
fi

# THE hole.  Deep/Down_Fix:5 is the row that used to vanish.
expect_nest "codeqs quad -- all three sites, filter or no filter" 3 3 codeqs quad -c
expect_nest "callers quad -- both citations survive" 2 2 callers quad -c

nest_want='Nested/Nested_Fix:5
Deep/Down_Fix:5
Plain_Fix:5'
for mode in on off; do
  if [ "$mode" = off ]; then
    loci=$(ISABELLE_QUERY_REACHABILITY=off isabelle query -R "$NEST" codeqs quad --names 2>>"$OUT/nest-err.txt")
  else
    loci=$(isabelle query -R "$NEST" codeqs quad --names 2>>"$OUT/nest-err.txt")
  fi
  if [ "$loci" = "$nest_want" ]; then
    note "the relative-path site is reported, filter $mode" "$(printf '%s' "$loci" | tr '\n' ' ')"
  else
    bad "the relative-path site is reported, filter $mode" "$(printf '%s' "$loci" | tr '\n' ' ')"
  fi
done

# The two near-misses, which resolved before this fix and must still.
expect_nest "codeqs cube -- a bare-named theory reached by \`../\` and by prefix" 3 3 codeqs cube -c
expect_nest "callers cube -- same, through the citation router" 2 2 callers cube -c

# Nothing is said on stderr either way: the whole point of the finding is that
# a hole in the closure prunes SILENTLY, so a probe that only counted rows
# would not have noticed had the fix traded one silence for another.
if [ ! -s "$OUT/nest-err.txt" ]; then
  note "and nothing on stderr in either mode" "empty"
else
  bad "and nothing on stderr in either mode" "$(head -2 "$OUT/nest-err.txt" | tr '\n' ' ')"
fi

# --------------------------------------------------------------------------
echo
echo "9. failability -- the harness must be able to say no"
# --------------------------------------------------------------------------

# Every `expect` above passes only if BOTH sides match, so a filter that did
# nothing would fail §1, §3 and §4.  Demonstrated rather than asserted: ask for
# the filtered answer and compare it against the unfiltered expectation.
got=$(on callers widget -c)
if [ "$got" != "5" ]; then
  note "the filtered answer is not the unfiltered one" "$got, not 5"
else
  bad "the filtered answer is not the unfiltered one" "both $got"
fi

# And the fixture is not answering by accident: a name it does not contain is 0
# on both sides, not the default of anything.
got=$(on callers no_such_name_xyz -c)
if [ "$got" = "0" ]; then
  note "a name the fixture does not declare answers 0 on both sides" "$got"
else
  bad "a name the fixture does not declare answers 0 on both sides" "$got"
fi

echo
printf '%d checks: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p7cprobe: FAILURES" >&2
  exit 1
fi
echo "P7CPROBE OK"
exit 0
