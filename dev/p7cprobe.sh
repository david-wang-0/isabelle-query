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
# default, and once with `--reach name`, which must give the unfiltered answer
# the oracle gives.  (P7c shipped that second mode as
# `$ISABELLE_QUERY_REACHABILITY=off`; P9 S3 made it a flag on the five
# attributing verbs and deleted the variable, so the mode is now visible in the
# invocation rather than in the environment.  `instances` and `codeqs` have no
# such flag -- upstream pins it to the attributing verbs -- so where they
# appear below they are asked once, in the default mode.)
#
# The fixtures are a real Isabelle session and every theory in them BUILDS.
# That is a constraint worth naming: the coincidences the filter removes are
# all legal Isabelle -- a bound variable spelled like another tree's constant
# -- because an uncompilable fixture would be evidence about a corpus nobody
# has.  It is also why the site verbs are checked for NOT over-pruning rather
# than for pruning: no compilable `interpretation L` can name an L its theory
# cannot see.
#
# §6 is what is left of watch-out 3 of dev/P7B-STATUS.md.  A request-scoped
# ENGINE VARIABLE had three places to go and the third was "over the socket";
# a FLAG is argv, which the client and the server already forward verbatim, so
# there is nothing new to forward -- but the answer still has to be the same
# warm as cold, and that is what §6 now asserts, here rather than in
# dev/p7probe.sh §9b because the fixture that makes the mode observable at all
# lives in this file.
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

# `on` is the default and says nothing; `off` appends `--reach name`, the
# compatibility mode dev/difftest.sh compares in.  The flag goes LAST, after
# the verb's own positionals, which is where a user would type it.
on()  { isabelle query -R "$FIX" "$@" 2>"$OUT/err.txt"; }
off() { isabelle query -R "$FIX" "$@" --reach name 2>"$OUT/err.txt"; }

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

# same NAME WANT ARGS...  -- one mode only, for a verb that has no `--reach`.
same() {
  local name=$1 want=$2; shift 2
  local got
  got=$(on "$@")
  if [ "$got" = "$want" ]; then note "$name" "$got"
  else bad "$name" "[$got] wanted [$want]"; fi
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
same "and Lonely is one of them, with its two lemmas" 2 theory Lonely -c

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
#
# Asked once each: the site verbs take the closure rule from the engine's own
# default (`Reach.DEFAULT_MODE`) and carry no `--reach` of their own, because
# upstream puts the flag on the five ATTRIBUTING verbs and these two have no
# oracle to be compatible with.  What is under test here is the same thing
# either way -- that the filter does not over-prune.
same "instances gizmo: two sites in two trees, both kept" 2 instances gizmo -c
same "codeqs widget: the default equations of both, both kept" 2 codeqs widget -c

# --------------------------------------------------------------------------
echo
echo "6. the flag rides through the socket, and answers what the cold run does"
# --------------------------------------------------------------------------

# A flag is argv, and argv is what the thin client forwards and the server
# parses through `CLI.run_result` -- so there is no second channel to keep in
# step, which is most of why P9 S3 replaced the variable with it.  What is left
# to check is that the warm path AGREES: the mode must reach the engine, and it
# must not persist past the request that carried it.  The defect that shape
# guards is the one dev/p7probe.sh §9b records for the namespace table -- a
# resident server answering a later client with a pin they never asked for --
# and a flag cannot have it by construction, which is worth demonstrating
# rather than asserting.

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

warm_off=$(client -R "$FIX" callers widget -c --reach name 2>/dev/null)
warm_on=$(client -R "$FIX" callers widget -c 2>/dev/null)
if [ "$warm_off" = "5" ]; then
  note "a client that passes --reach name gets the unfiltered answer" "$warm_off"
else
  bad "a client that passes --reach name gets the unfiltered answer" "$warm_off"
fi
# Ordered deliberately: the `--reach name` request runs FIRST, so this one
# fails if anything about that request outlives it on the resident index.
if [ "$warm_on" = "4" ]; then
  note "and the NEXT client, on that same server, gets the default back" "$warm_on"
else
  bad "and the NEXT client, on that same server, gets the default back" "$warm_on"
fi

# And through the shim's own route to the same server, no `--no-server`: the
# argv is what travels, so this needs no list of variables anywhere.
deleg_off=$(env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SERVER" \
  isabelle query -R "$FIX" callers widget -c --reach name 2>/dev/null)
deleg_on=$(env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SERVER" \
  isabelle query -R "$FIX" callers widget -c 2>/dev/null)
if [ "$deleg_off" = "5" ] && [ "$deleg_on" = "4" ]; then
  note "the warm answer equals the cold answer in both modes" "name $deleg_off / closure $deleg_on"
else
  bad "the warm answer equals the cold answer in both modes" "name $deleg_off / closure $deleg_on"
fi

cleanup

# --------------------------------------------------------------------------
echo
echo "7. the flag grammar: two spellings, and nothing else"
# --------------------------------------------------------------------------

# Two values and no third.  An unrecognised one is exit 2 with the choices
# named -- never a silent fall back to the default, which would answer a
# different question from the one asked.  `--reach closure` written out must
# mean exactly the default, or the default is not what the help says it is.
for spelling in "--reach closure" "--reach=closure"; do
  # shellcheck disable=SC2086
  got=$(isabelle query -R "$FIX" callers widget -c $spelling)
  if [ "$got" = "4" ]; then note "\`$spelling\` is the default, written out" "$got"
  else bad "\`$spelling\` is the default, written out" "$got"; fi
done
for spelling in "--reach name" "--reach=name"; do
  # shellcheck disable=SC2086
  got=$(isabelle query -R "$FIX" callers widget -c $spelling)
  if [ "$got" = "5" ]; then note "\`$spelling\` is the compatibility mode" "$got"
  else bad "\`$spelling\` is the compatibility mode" "$got"; fi
done
for value in "" 0 off no closures Name; do
  got=$(isabelle query -R "$FIX" callers widget -c --reach "$value" 2>"$OUT/err.txt")
  rc=$?
  msg=$(cat "$OUT/err.txt")
  want="isabelle query: error: argument --reach: invalid choice: '$value' (choose from 'closure', 'name')"
  if [ "$rc" = "2" ] && [ -z "$got" ] && [ "$msg" = "$want" ]; then
    note "\`--reach $value\` is refused, exit 2, stdout untouched" "rc $rc"
  else
    bad "\`--reach $value\` is refused, exit 2, stdout untouched" "rc $rc out [$got] err [$msg]"
  fi
done

# The flag is on the five ATTRIBUTING verbs and nowhere else, which upstream
# pins too: `deps` / `uses` read the `imports` clause and attribute nothing,
# `methods` identifies a method by POSITION, and `shape` counts cited TOKENS
# without ever asking which entry one denotes.  A flag on them would be a
# switch with nothing to switch.
for verb in "deps Left" "uses Left" "methods simp" "instances gizmo" "codeqs widget"; do
  # shellcheck disable=SC2086
  isabelle query -R "$FIX" $verb --reach name >"$OUT/o.txt" 2>"$OUT/err.txt"
  rc=$?
  if [ "$rc" = "2" ] && grep -q 'unrecognized argument: --reach' "$OUT/err.txt"; then
    note "\`$verb\` has no --reach, and says so" "rc $rc"
  else
    bad "\`$verb\` has no --reach, and says so" "rc $rc  $(head -1 "$OUT/err.txt")"
  fi
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
# ../BV/Altern`, and before the resolver learned the leaf rule this check was
# 608 against 668.
hol_on=$(isabelle query -R "$CORPUS" callers rev -c 2>/dev/null)
hol_off=$(isabelle query -R "$CORPUS" callers rev -c --reach name 2>/dev/null)
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
# and until [p10-theory-leaf] this engine carried the theory under THAT
# spelling.  It is not what Isabelle calls it: `Sessions` names a
# ROOT-declared theory `Thy_Header.import_name(thy)`
# (src/Pure/Build/sessions.scala:650), the last segment, and the
# `global_theories` check four lines on spells it
# `Path.explode(thy).file_name`.  The theory is now `Nested_Fix` here, as it
# is to `isabelle build`; the reference still says `Nested/Nested_Fix`, which
# is dev/DIVERGENCES.md D15.
#
# The IMPORT side is older and is what §8b was written for.  A theory in a
# sibling directory reaches this one with `imports "../Nested/Nested_Fix"`,
# whose leaf is `Nested_Fix` -- and a leaf tested against a set of PREFIXED
# names missed.  `Reach.import_candidates` maps the four spellings; before it,
# `codeqs quad` here answered 2 where the source has 3, with nothing on
# stderr.  Naming the theory by its leaf does not retire that rule (a header
# may still write `imports "ex/Foo"` for a theory called `Foo`), so this
# section is exactly as load-bearing as it was.
#
# Its OWN root, so every count in §0-§7 stays where it was.  Two constants,
# both hand-computed off the four theories below:
#
#   quad  THE case.  Declared in the theory the ROOT spells
#         `"Nested/Nested_Fix"` -- named `Nested_Fix` -- and cited from
#         `Down_Fix`, which reaches it across directories with `../`.  Three
#         sites; the one that vanished is `Down_Fix:5`.
#   cube  the two NEAR-MISSES, which must go on working.  `Helper_Fix`
#         is NOT declared in the ROOT -- it arrives through the import closure
#         and so registers under its bare leaf -- and it is reached two ways:
#         `../Extra/Helper_Fix` from `Down_Fix` (a `../` import of a
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

# expect_nest NAME WANT_ON WANT_OFF ARGS...   (a verb that has `--reach`)
expect_nest() {
  local name=$1 want_on=$2 want_off=$3; shift 3
  local got_on got_off
  got_on=$(isabelle query -R "$NEST" "$@" 2>>"$OUT/nest-err.txt")
  got_off=$(isabelle query -R "$NEST" "$@" --reach name 2>>"$OUT/nest-err.txt")
  if [ "$got_on" = "$want_on" ] && [ "$got_off" = "$want_off" ]; then
    note "$name" "on $got_on / off $got_off"
  else
    bad "$name" "on [$got_on] wanted [$want_on]; off [$got_off] wanted [$want_off]"
  fi
}

# same_nest NAME WANT ARGS...                 (a verb that does not)
same_nest() {
  local name=$1 want=$2; shift 2
  local got
  got=$(isabelle query -R "$NEST" "$@" 2>>"$OUT/nest-err.txt")
  if [ "$got" = "$want" ]; then note "$name" "$got"
  else bad "$name" "[$got] wanted [$want]"; fi
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

# The engine names the path-declared theory the way `isabelle build` does --
# by its leaf -- and the ROOT's own spelling appears nowhere in the table.
# The reference still prints `Nested/Nested_Fix` here; that is D15, and it is
# the only place this fixture and the oracle disagree.
if printf '%s\n' "$nest_sum" | grep -q '^| Nested_Fix |' &&
   ! printf '%s\n' "$nest_sum" | grep -q 'Nested/Nested_Fix'; then
  note "the path-declared theory is named by its leaf" "Nested_Fix"
else
  bad "the path-declared theory is named by its leaf" \
    "$(printf '%s\n' "$nest_sum" | grep Nested_Fix | tr '\n' ' ')"
fi

# THE hole.  Down_Fix:5 is the row that used to vanish.
same_nest "codeqs quad -- all three sites" 3 codeqs quad -c
expect_nest "callers quad -- both citations survive" 2 2 callers quad -c

nest_want='Nested_Fix:5
Down_Fix:5
Plain_Fix:5'
loci=$(isabelle query -R "$NEST" codeqs quad --names 2>>"$OUT/nest-err.txt")
if [ "$loci" = "$nest_want" ]; then
  note "the relative-path site is reported" "$(printf '%s' "$loci" | tr '\n' ' ')"
else
  bad "the relative-path site is reported" "$(printf '%s' "$loci" | tr '\n' ' ')"
fi
# The same question through a verb that HAS the flag, so the closure is
# exercised in both modes on this fixture too: the hole was in the closure,
# and `--reach name` does not use one.
# `quad` is cited on `Down_Fix:5` and `Plain_Fix:5` and nowhere else:
# `Nested_Fix` holds only the declaration, which the scan excludes, and
# the `quad_def` on each proof line is a different token.  The locus column is
# what the hole ate, so it is the column asserted.
nest_callers_want='Down_Fix:5
Plain_Fix:5'
for mode in closure name; do
  loci=$(isabelle query -R "$NEST" callers quad --reach "$mode" -U 0 \
    2>>"$OUT/nest-err.txt" | tail -n +3 | awk '{print $1}')
  if [ "$loci" = "$nest_callers_want" ]; then
    note "and its citations are reported, --reach $mode" "$(printf '%s' "$loci" | tr '\n' ' ')"
  else
    bad "and its citations are reported, --reach $mode" "$(printf '%s' "$loci" | tr '\n' ' ')"
  fi
done

# The two near-misses, which resolved before this fix and must still.
same_nest "codeqs cube -- a bare-named theory reached by \`../\` and by prefix" 3 codeqs cube -c
expect_nest "callers cube -- same, through the citation router" 2 2 callers cube -c

# Nothing is said on stderr in any mode: the whole point of the finding is that
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
