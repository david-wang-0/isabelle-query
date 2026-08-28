#!/usr/bin/env bash
# Differential check: the Scala rewrite's STDOUT and EXIT STATUS against the
# Python oracle, over a matrix of (corpus x invocation) pairs.
#
#   dev/difftest.sh                    # the standard corpus set
#   dev/difftest.sh CORPUS_DIR...      # named corpora
#   dev/difftest.sh -v ...             # print the diff of each failing case
#   dev/difftest.sh -k PATTERN ...     # only cases whose id matches PATTERN
#
# Corpora come from the environment, never from a path written down here:
#   $QUERY_TEST_AFP     an AFP `thys` directory
#   $QUERY_TEST_DISTRO  the Isabelle distribution's `src` directory
#   $QUERY_CORPORA      optional: whitespace-separated list, overriding the
#                       default selection below
#
# SUBJECTS (theory names, entry names, loci) are derived per corpus from the
# ORACLE's own output, so the same matrix ports to any corpus without a name
# being written down here.
#
# A case is a tab-separated record `ID <TAB> ARG...`.  Both sides are run with
# the same argv; stdout is compared byte for byte and the exit statuses must
# agree.  stderr is compared only for NON-EMPTINESS, and only where the
# oracle's is non-empty: `PLAN.md` fixes the wording of stdout, not of
# diagnostics.
#
# Known, documented divergences are PINNED in dev/difftest-pins; a pinned case
# that differs is reported as `pin` and does not fail the run, and a pinned
# case that AGREES is reported as `stale` and does (a pin that no longer
# describes anything is a lie about the state of the port).
#
# Exit status is non-zero if any unpinned case differs, or any pin is stale.

set -u

repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
pins_file=${QUERY_PINS:-$repo/dev/difftest-pins}
outdir=${QUERY_DIFF_DIR:-$(mktemp -d)}
mkdir -p "$outdir"

verbose=0
select_pat=""
while [ $# -gt 0 ]; do
  case $1 in
    -v) verbose=1; shift ;;
    -k) select_pat=$2; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done

# The Scala side runs against the repo's own scratch Isabelle home, never the
# user's: a work-in-progress component must not be visible to a real session.
run_oracle() { query "$@"; }
run_scala() { USER_HOME="$repo/.dev" isabelle query "$@"; }

# `### Missing Isabelle component` lines are this scratch home's own noise, not
# the tool's diagnostics.
strip_noise() { grep -v '^### Missing Isabelle component' || true; }

corpora=("$@")
if [ ${#corpora[@]} -eq 0 ]; then
  if [ -n "${QUERY_CORPORA:-}" ]; then
    read -r -a corpora <<<"$QUERY_CORPORA"
  else
    for d in "${QUERY_TEST_AFP:-}/Abstract_Completeness" \
             "${QUERY_TEST_AFP:-}/AODV" \
             "${QUERY_TEST_AFP:-}/Category3" \
             "${QUERY_TEST_DISTRO:-}/FOL" \
             "${QUERY_TEST_DISTRO:-}/ZF"; do
      [ -d "$d" ] && corpora+=("$d")
    done
  fi
fi
if [ ${#corpora[@]} -eq 0 ]; then
  echo "difftest: no corpora (set \$QUERY_TEST_AFP / \$QUERY_TEST_DISTRO)" >&2
  exit 2
fi

# --------------------------------------------------------------------------
# Fixtures — the roots and streams the global-behaviour cases need.  Built
# here rather than named in the matrix so the script owns nothing outside its
# own scratch directory.
# --------------------------------------------------------------------------

fixtures=$outdir/fixtures
FIX_EMPTY=$fixtures/empty
FIX_NOROOT=$fixtures/rootless
FIX_MARKER=$fixtures/marked
FIX_MD=$fixtures/notes.md
mkdir -p "$FIX_EMPTY" "$FIX_NOROOT" "$FIX_MARKER/sub"
cat >"$FIX_NOROOT/Solo.thy" <<'THY'
theory Solo
  imports Main
begin

text \<open>A theory in a directory with no ROOT: found by the fallback glob.\<close>

definition two :: nat where "two = 2"

lemma two_pos: "two > 0" by (simp add: two_def)

end
THY
cp "$FIX_NOROOT/Solo.thy" "$FIX_MARKER/sub/Solo.thy"
printf 'sub\n' >"$FIX_MARKER/.isabelle-query"
cat >"$FIX_MD" <<'MD'
# notes

The entry grammar does not apply to prose, so `grep` over this file is
plain line matching with no owning-entry column.
MD

# --------------------------------------------------------------------------
# Subject derivation — everything below comes out of the oracle, per corpus.
# --------------------------------------------------------------------------

derive_subjects() {
  local corpus=$1
  local -a rows

  # Theory names, in the order `summary` prints them (= discovery order).
  mapfile -t THEORIES < <(
    run_oracle -R "$corpus" summary 2>/dev/null |
      awk -F' *\\| *' '/^\| [^-]/ && $2 != "Theory" { print $2 }')
  THY1=${THEORIES[0]:-}
    THYLAST=${THEORIES[$(( ${#THEORIES[@]} - 1 ))]:-$THY1}

  # The largest entries: `SIZE TAG NAME THEORY (LO..HI)`.
  mapfile -t rows < <(
    run_oracle -R "$corpus" largest -N 40 2>/dev/null |
      awk 'NF >= 5 && $1 ~ /^[0-9]+$/ { print $1, $2, $3, $4, $5 }')

  NAME1=""; NAME2=""
  LEMMA_THY=""; LEMMA_LO=""; LEMMA_HI=""
  local size tag name thy span
  for r in "${rows[@]}"; do
    read -r size tag name thy span <<<"$r"
    [ "$name" = "?" ] && continue
    [ -z "$NAME1" ] && NAME1=$name
    if [ -z "$NAME2" ] && [ "$name" != "$NAME1" ]; then NAME2=$name; fi
    if [ -z "$LEMMA_THY" ] && { [ "$tag" = LEMMA ] || [ "$tag" = THEOREM ]; }; then
      LEMMA_THY=$thy
      LEMMA_LO=${span#(}; LEMMA_LO=${LEMMA_LO%%..*}
      LEMMA_HI=${span%)}; LEMMA_HI=${LEMMA_HI##*..}
    fi
  done
  [ -z "$NAME1" ] && NAME1=$THY1
  [ -z "$NAME2" ] && NAME2=$NAME1
  if [ -z "$LEMMA_THY" ]; then
    LEMMA_THY=$THY1; LEMMA_LO=1; LEMMA_HI=2
  fi
  # A line well inside the biggest proof — the drill-down case.
  MID=$(( (LEMMA_LO + LEMMA_HI) / 2 ))
  [ "$MID" -lt "$LEMMA_LO" ] && MID=$LEMMA_LO

  # A real path to THY1, for the path-form positionals, and a slice of it for
  # the stdin cases.  The SUBJECT is still oracle-derived; only where its file
  # sits on disk is a filesystem fact.
  THY1_PATH=$(find "$corpus" -name "$THY1.thy" -print -quit 2>/dev/null)
  [ -z "$THY1_PATH" ] && THY1_PATH=$FIX_NOROOT/Solo.thy
  STDIN_FILE=$outdir/stdin.thy
  head -400 "$THY1_PATH" >"$STDIN_FILE"
}

# --------------------------------------------------------------------------
# The matrix.  One `c ID ARG...` per case.
# --------------------------------------------------------------------------

# A case record is `ID <TAB> MODE <TAB> ARG...`.  MODE says HOW to run the two
# sides, which is the only thing the global-behaviour cases need that the plain
# ones do not: `root` prepends `-R CORPUS`, `plain` passes argv untouched, and
# the rest exercise the ways a root is found or a stream is fed.
c() { local id=$1; shift; printf '%s\troot' "$id"; printf '\t%s' "$@"; printf '\n'; }
g() { local id=$1 mode=$2; shift 2
      printf '%s\t%s' "$id" "$mode"; printf '\t%s' "$@"; printf '\n'; }

emit_cases() {
  # -- summary ------------------------------------------------------------
  c summary-default        summary
  c summary-count          summary -c
  c summary-by-session     summary --by-session
  c summary-by-session-abbrev summary --by-sess
  c summary-by-session-v   summary -S -v
  c summary-S-c            summary -S -c
  c summary-file           summary "$THY1"

  # -- theory -------------------------------------------------------------
  c theory-default         theory "$THY1"
  c theory-names           theory "$THY1" --names
  c theory-count           theory "$THY1" -c
  c theory-verbatim        theory "$THY1" -V
  c theory-no-comments     theory "$THY1" --no-comments
  c theory-context         theory "$THY1" -U 5
  c theory-last            theory "$THYLAST"
  c theory-unknown         theory No_Such_Theory_Xyz

  # -- defs ---------------------------------------------------------------
  c defs-default           defs "$THY1"
  c defs-names             defs "$THY1" --names
  c defs-count             defs "$THY1" -c
  c defs-unknown           defs No_Such_Theory_Xyz

  # -- outline ------------------------------------------------------------
  c outline-default        outline "$THY1"
  c outline-no-comments    outline "$THY1" --no-comments
  c outline-context0       outline "$THY1" -U 0
  c outline-last           outline "$THYLAST"
  c outline-unknown        outline No_Such_Theory_Xyz

  # -- enclosing / at -----------------------------------------------------
  c enclosing-mid          enclosing "$LEMMA_THY:$MID"
  c enclosing-entry        enclosing "$LEMMA_THY:$MID" -e
  c enclosing-blocks       enclosing "$LEMMA_THY:$MID" -b
  c enclosing-range        enclosing "$LEMMA_THY:$LEMMA_LO..$LEMMA_HI"
  c enclosing-open-range   enclosing "$LEMMA_THY:$LEMMA_LO.."
  c enclosing-open-lo      enclosing "$LEMMA_THY:..$LEMMA_HI"
  c enclosing-line1        enclosing "$THY1:1"
  c enclosing-past-end     enclosing "$THY1:99999"
  c enclosing-batch        enclosing "$THY1:1" "$LEMMA_THY:$MID" "$THY1:2"
  c enclosing-alias-at     at "$LEMMA_THY:$MID"
  c enclosing-rg-marker    enclosing "$LEMMA_THY:$MID:"
  c enclosing-bad-locus    enclosing "not-a-locus"
  c enclosing-unknown-thy  enclosing "No_Such_Theory_Xyz:3"

  # -- largest ------------------------------------------------------------
  c largest-default        largest
  c largest-top3           largest -N 3
  c largest-top-glued      largest -N3
  c largest-scoped         largest "$THY1"
  c largest-top-long       largest --top 5

  # -- lines --------------------------------------------------------------
  c lines-range            lines "$THY1" 1..12
  c lines-multi            lines "$THY1" 1..5 20..24
  c lines-single           lines "$THY1" 7
  c lines-open-hi          lines "$THY1" "$(( LEMMA_HI > 3 ? LEMMA_HI - 3 : 1 )).."
  c lines-open-lo          lines "$THY1" ..4
  c lines-colon-form       lines "$THY1:1..6"
  c lines-past-end         lines "$THY1" 999990..999999
  c lines-bad-range        lines "$THY1" 9..2

  # -- grep ---------------------------------------------------------------
  c grep-name              grep "$NAME1"
  c grep-count             grep "$NAME1" -c
  c grep-names             grep "$NAME1" --names
  c grep-with-comments     grep "$NAME1" --with-comments
  c grep-noop-n            grep "$NAME1" -n
  c grep-scoped-thy        grep "$NAME1" "$THY1"
  c grep-window            grep "$NAME1" "$THY1:1..80"
  c grep-alternation       grep "lemma\\|definition"
  c grep-anchored          grep '^lemma'
  c grep-no-match          grep zzz_no_such_token_zzz
  c grep-bad-regex         grep '['
  c grep-unknown-file      grep "$NAME1" No_Such_Theory_Xyz

  # -- sorry --------------------------------------------------------------
  c sorry-default          sorry
  c sorry-count            sorry -c
  c sorry-scoped           sorry "$THY1"
  c sorry-noop-n           sorry -n

  # -- find ---------------------------------------------------------------
  c find-name              find "$NAME1"
  c find-all               find "$NAME1" -a
  c find-names             find "$NAME1" --names
  c find-count             find "$NAME1" -c
  c find-verbatim          find "$NAME1" -V
  c find-comments-only     find "$NAME1" --comments-only
  c find-no-comments       find "$NAME1" --no-comments
  c find-context           find "$NAME1" -U 6
  c find-statement         find --statement "$NAME1"
  c find-with-comments     find "$NAME1" --with-comments
  c find-theory-scope      find "$NAME1" --theory "$THY1"
  c find-batch             find "$NAME1" "$NAME2"
  c find-and               find --and --statement "$NAME1" "$NAME2"
  c find-dot               find .
  c find-no-match          find zzz_no_such_name_zzz
  c find-bad-regex         find '['

  # -- show ---------------------------------------------------------------
  c show-name              show "$NAME1"
  c show-all               show "$NAME1" -a
  c show-names             show "$NAME1" --names
  c show-count             show "$NAME1" -c
  c show-verbatim          show "$NAME1" -V
  c show-statement         show "$NAME1" --statement
  c show-comments-only     show "$NAME1" --comments-only
  c show-no-comments       show "$NAME1" --no-comments
  c show-context           show "$NAME1" -U 8
  c show-batch             show "$NAME1" "$NAME2"
  c show-substring         show "${NAME1:0:3}"
  c show-unknown           show zzz_no_such_name_zzz

  # -- path positionals (the file/dir routing, not a bare theory name) -----
  c grep-path              grep "$NAME1" "$THY1_PATH"
  c grep-dir               grep "$NAME1" "$CORPUS"
  c grep-non-thy           grep "the" "$FIX_MD"
  c largest-path           largest -N 3 "$THY1_PATH"
  c sorry-path             sorry "$THY1_PATH"
  c summary-path           summary "$THY1_PATH"
  c enclosing-path         enclosing "$THY1_PATH:2"
  c lines-path             lines "$THY1_PATH" 2..5
  c theory-path            theory "$THY1_PATH"

  # -- more flag shapes ---------------------------------------------------
  c theory-comments-only   theory "$THY1" --comments-only
  c outline-comments-only  outline "$THY1" --comments-only
  c find-a-names           find "$NAME1" -a --names
  c find-markup            find '\\<^sub>'
  c find-context0          find "$NAME1" -U 0
  c show-context0          show "$NAME1" -U 0
  c grep-cartouche         grep '\\<open>'
  c grep-equals-context    find --context=4 "$NAME1"

  # -- the argument grammar itself ----------------------------------------
  c dashdash               grep -- "$NAME1"
  c bundled-shorts         show "$NAME1" -ac
  c excl-slice             show "$NAME1" -V --statement
  c excl-drilldown         enclosing "$LEMMA_THY:$MID" -e -b
  c excl-comments          theory "$THY1" --no-comments --comments-only
  c bad-int-context        find "$NAME1" -U abc
  c bad-int-top            largest -N abc
  c unknown-flag           find "$NAME1" --no-such-flag
  c ambiguous-prefix       find "$NAME1" --co
  c and-with-comments      find --and --with-comments "$NAME1" "$NAME2"
  c grep-names-comments    grep "$NAME1" --names --with-comments
  c lines-colon-multi      lines "$THY1:1..3" "$THY1:10..12"
  c summary-two-files      summary "$THY1" "$THYLAST"

  # -- global behaviour ---------------------------------------------------
  g root-after-subcommand  plain  summary -c -R "$CORPUS"
  g bad-root               plain  -R /no/such/root/xyz summary
  g bad-root-not-dir       plain  -R "$THY1_PATH" summary
  g empty-root             plain  -R "$FIX_EMPTY" summary
  g rootless-glob          plain  -R "$FIX_NOROOT" summary
  g rootless-glob-session  plain  -R "$FIX_NOROOT" summary -S
  g env-root               env    summary -c
  g env-root-find          env    find "$NAME1" --names
  g cwd-discovery          cwd    summary -c
  g cwd-discovery-outline  cwd    outline "$THY1"
  g marker-file            marker summary -S
  g stdin-grep             stdin  grep "lemma" -
  g stdin-lines            stdin  lines - 1..8
  g stdin-sorry            stdin  sorry -
  # Four full renders of the corpus, so the producer is certain to fill the
  # 64K pipe buffer even on the smallest one — otherwise the case is a race
  # between a short write and `head` exiting, and both sides "pass" at 0.
  g closed-stdout          pipe   find . . . . -a -V
}

# --------------------------------------------------------------------------
# Running one side of one case.  The MODE column decides how the root is found
# and where stdin comes from; everything else is the same argv on both sides.
# --------------------------------------------------------------------------

exec_side() {
  local side=$1 mode=$2; shift 2
  local rc
  case $mode in
    root)   set -- -R "$CORPUS" "$@" ;;
    stdin|pipe) set -- -R "$CORPUS" "$@" ;;
    *)      ;;
  esac
  case $mode in
    env)
      if [ "$side" = oracle ]; then ISABELLE_QUERY_ROOT=$CORPUS run_oracle "$@" </dev/null
      else ISABELLE_QUERY_ROOT=$CORPUS run_scala "$@" </dev/null; fi ;;
    cwd)
      if [ "$side" = oracle ]; then (cd "$CORPUS" && run_oracle "$@" </dev/null)
      else (cd "$CORPUS" && run_scala "$@" </dev/null); fi ;;
    marker)
      if [ "$side" = oracle ]; then (cd "$FIX_MARKER" && run_oracle "$@" </dev/null)
      else (cd "$FIX_MARKER" && run_scala "$@" </dev/null); fi ;;
    stdin)
      if [ "$side" = oracle ]; then run_oracle "$@" <"$STDIN_FILE"
      else run_scala "$@" <"$STDIN_FILE"; fi ;;
    pipe)
      # A closed stdout mid-stream: the status under test is the PRODUCER'S,
      # which is what `head` throwing away the rest of a long listing makes a
      # daily occurrence.
      if [ "$side" = oracle ]; then run_oracle "$@" </dev/null | head -3
      else run_scala "$@" </dev/null | head -3; fi
      rc=${PIPESTATUS[0]}
      return "$rc" ;;
    *)
      if [ "$side" = oracle ]; then run_oracle "$@" </dev/null
      else run_scala "$@" </dev/null; fi ;;
  esac
}

# --------------------------------------------------------------------------
# Pins
# --------------------------------------------------------------------------

is_pinned() {   # $1 = case id, $2 = corpus basename
  [ -f "$pins_file" ] || return 1
  awk -v id="$1" -v corpus="$2" '
    /^[[:space:]]*(#|$)/ { next }
    { if (($1 == id || $1 == "*") && ($2 == corpus || $2 == "*")) found = 1 }
    END { exit found ? 0 : 1 }' "$pins_file"
}

pin_reason() {
  awk -v id="$1" -v corpus="$2" '
    /^[[:space:]]*(#|$)/ { next }
    ($1 == id || $1 == "*") && ($2 == corpus || $2 == "*") {
      $1 = ""; $2 = ""; sub(/^  */, ""); print; exit }' "$pins_file"
}

# --------------------------------------------------------------------------
# The run
# --------------------------------------------------------------------------

total=0; clean=0; pinned=0; stale=0; failed=0
declare -a failures=()

for CORPUS in "${corpora[@]}"; do
  tag=$(basename "$CORPUS")
  derive_subjects "$CORPUS"
  printf '== %s  (thy=%s name=%s locus=%s:%s)\n' \
    "$tag" "$THY1" "$NAME1" "$LEMMA_THY" "$MID"

  while IFS=$'\t' read -r -a rec; do
    id=${rec[0]}
    mode=${rec[1]}
    args=("${rec[@]:2}")
    [ -n "$select_pat" ] && [[ $id != $select_pat ]] && continue
    total=$((total + 1))

    o_out="$outdir/$tag.$id.oracle"; o_err="$outdir/$tag.$id.oracle.err"
    s_out="$outdir/$tag.$id.scala";  s_err="$outdir/$tag.$id.scala.err"
    exec_side oracle "$mode" "${args[@]}" >"$o_out" 2>"$o_err"; o_rc=$?
    exec_side scala "$mode" "${args[@]}" >"$s_out" 2>"$s_err.raw"; s_rc=$?
    strip_noise <"$s_err.raw" >"$s_err"; rm -f "$s_err.raw"

    same_out=0; cmp -s "$o_out" "$s_out" && same_out=1
    same_rc=0; [ "$o_rc" = "$s_rc" ] && same_rc=1
    same_err=1
    if [ -s "$o_err" ] && [ ! -s "$s_err" ]; then same_err=0; fi

    if [ "$same_out" = 1 ] && [ "$same_rc" = 1 ] && [ "$same_err" = 1 ]; then
      if is_pinned "$id" "$tag"; then
        stale=$((stale + 1))
        reason=$(pin_reason "$id" "$tag")
        failures+=("$tag/$id: STALE PIN, the case now agrees [$reason]")
      else
        clean=$((clean + 1))
        rm -f "$o_out" "$s_out" "$o_err" "$s_err"
      fi
    elif is_pinned "$id" "$tag"; then
      pinned=$((pinned + 1))
    else
      failed=$((failed + 1))
      why=""
      [ "$same_out" = 0 ] && why="stdout"
      [ "$same_rc" = 0 ] && why="${why:+$why+}exit-$o_rc-vs-$s_rc"
      [ "$same_err" = 0 ] && why="${why:+$why+}stderr-empty"
      failures+=("$tag/$id: $why  [${args[*]}]")
      if [ "$verbose" = 1 ]; then
        printf '   --- %s/%s ---\n' "$tag" "$id"
        diff -u "$o_out" "$s_out" | head -40
      fi
    fi
  done < <(emit_cases)
done

printf '\n%d cases: %d clean, %d pinned, %d failing, %d stale pins\n' \
  "$total" "$clean" "$pinned" "$failed" "$stale"
if [ ${#failures[@]} -gt 0 ]; then
  printf '\nFAIL:\n'
  printf '  %s\n' "${failures[@]}"
  printf '\nartefacts under %s\n' "$outdir"
  exit 1
fi
exit 0
