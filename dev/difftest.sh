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
# THE ORACLE comes from $QUERY_ORACLE (default: the bare `query` on PATH), and
# the run REFUSES (exit 2) unless it reports the version pinned in
# $ORACLE_VERSION below.  The oracle is the frozen `src/isabelle_query/` tree
# in this repo, so an oracle from somewhere else is a different tool wearing
# the same name -- and a matrix run against the wrong one is not a red gate,
# it is a plausible one, which is worse.  Same spirit as refusing without
# corpora: never skip into a colour that has not been earned.
#
# Build one from THIS tree, in the repo's own gitignored scratch directory:
#
#   python3 -m venv .dev/oracle
#   .dev/oracle/bin/pip install -e .
#   QUERY_ORACLE=.dev/oracle/bin/query dev/difftest.sh
#
# (An editable install, so the oracle follows the tree rather than a copy of
# it; nothing about that path is written down anywhere but here, and nothing
# outside `.dev/` is touched.)
#
#   $QUERY_DIFFTEST_WARM=1  run the Scala side through the WARM SERVER
#                       (shim -> thin client -> server) instead of `--no-server`.
#                       $QUERY_DIFFTEST_DELEGATE=1 is the old name for it.
#                       Same matrix, taken over the socket: it is the
#                       end-to-end proof that the delegated path answers what
#                       the oracle answers.  See `run_scala` below.
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
#
# BOTH sides are pinned to the committed method/attribute table.  Unpinned, the
# oracle binds tables dumped from whichever declared sessions happen to have
# BUILT HEAPS on this machine (DIVERGENCES.md D11), so the same matrix would
# pass on one machine and report false failures on another.
#
# The pin has to be SYMMETRIC, and P4 is where that started to matter.  Pinning
# only the oracle short-circuits its step-DOWN to the Pure floor as well, so on
# a non-HOL corpus the oracle kept the broad HOL union while the rewrite —
# correctly reproducing what the reference does on a clean machine — stepped
# down.  Every table-reading verb then compared two DIFFERENT tables: invisible
# for `callers` / `methods` on the gate corpora, but plainly visible in `shape
# steps` on ZF, where `field` is a proof method under the union and a free
# variable under the floor (`u \<in> field(r)`: w1 2 against 3).  Pinning both
# sides compares one table against itself, which is what a differential test is
# for.  The step-down path is then not exercised here; it is checked by running
# both sides UNPINNED, which is a machine-dependent measurement (D11) and so
# belongs in the phase status notes rather than in the gate.
#
# The ORACLE BINARY itself is $QUERY_ORACLE, defaulting to the bare `query` on
# PATH; its version is checked against $ORACLE_VERSION below before any case
# runs.
oracle_bin=${QUERY_ORACLE:-query}
ORACLE_VERSION=0.8.1
run_oracle() { ISABELLE_QUERY_NAMESPACE=committed "$oracle_bin" "$@"; }

# WHICH JVM ANSWERS, and why it is a switch rather than a default.
#
# Since P7b a bare `isabelle query` delegates to the warm server when there is
# one.  The matrix is about the ENGINE agreeing with the oracle, so by default
# it says `--no-server`: a run that quietly used a resident index would be
# testing the transport as well, would leave a corpus-sized server behind it,
# and -- worst -- would make the result depend on whether a server happened to
# be up.
#
# QUERY_DIFFTEST_DELEGATE=1 flips it, and that run answers a different and
# equally necessary question: does the WARM path give the end user the same
# answers the oracle gives?  It is the same 2,113-case matrix, taken through
# the socket.  The server is probe-private and stopped on the way out, for the
# reason dev/p7probe.sh gives at greater length.
#
# Since P7d a bare `isabelle query` resolves to the THIN CLIENT, so the warm
# run above exercises shim -> client -> server, which is the path a user's
# fingers actually take.  The default `--no-server` run is routed past the
# client by the shim itself, so the cold column needs nothing.
#
# P7b-P7d also had a JVM that delegated, reachable here with
# ISABELLE_QUERY_NO_CLIENT=1; P8 deleted it, and that spelling now takes the
# matrix through the plain cold engine -- which is what the default already
# does, so there is no third column any more.
#
# $QUERY_DIFFTEST_WARM is the name; $QUERY_DIFFTEST_DELEGATE is what it was
# called while there was something to delegate to, and is still honoured
# because it is in people's shell history.
if [ "${QUERY_DIFFTEST_WARM:-${QUERY_DIFFTEST_DELEGATE:-0}}" = "1" ]; then
  export ISABELLE_QUERY_CLIENT_SERVER="difftest-$$"
  unset ISABELLE_QUERY_NO_SERVER
  delegate_cleanup() {
    USER_HOME="$repo/.dev" isabelle server -x -n "$ISABELLE_QUERY_CLIENT_SERVER" \
      >/dev/null 2>&1
    pkill -f "server -n $ISABELLE_QUERY_CLIENT_SERVER" >/dev/null 2>&1
  }
  trap delegate_cleanup EXIT INT TERM
  SCALA_SERVER_FLAG=()
  echo "difftest: WARM, through server $ISABELLE_QUERY_CLIENT_SERVER" >&2
else
  SCALA_SERVER_FLAG=(--no-server)
fi
# IMPORT REACHABILITY IS NOT PINNED, and that is new in P9 S3.
#
# A citation site in theory T is attributed to a declaration in theory D only
# when T can SEE D -- D is T's own theory or in its transitive `imports`
# closure (dev/DIVERGENCES.md D13).  P7c shipped that here and upstream
# shipped it in 0.8.0 as [citation-reach], so BOTH engines now default to
# `closure` and there is nothing asymmetric left to pin: the matrix compares
# the same question on both sides by running neither of them in a special
# mode.
#
# Until S3 this file exported ISABELLE_QUERY_REACHABILITY=off on the rewrite
# side alone, because a differential matrix can only measure a difference and
# the oracle had no such notion.  The variable no longer exists; the
# compatibility mode is `--reach name`, spelled identically on both sides, and
# the `*-reach-name` cases below take it -- which is a better pin than the old
# one, because it is visible in the case id rather than in the environment.
run_scala() {
  ISABELLE_QUERY_NAMESPACE=committed \
    USER_HOME="$repo/.dev" isabelle query "${SCALA_SERVER_FLAG[@]}" "$@"
}

# `### Missing Isabelle component` lines are this scratch home's own noise, not
# the tool's diagnostics.
strip_noise() { grep -v '^### Missing Isabelle component' || true; }

corpora=("$@")
if [ ${#corpora[@]} -eq 0 ]; then
  if [ -n "${QUERY_CORPORA:-}" ]; then
    read -r -a corpora <<<"$QUERY_CORPORA"
  else
    # FOL and ZF carry the P2 matrix but cannot carry the P3 one: the oracle's
    # line index dies on their multi-name `axiomatization` (D7), so every verb
    # that builds a call graph or a method census is a traceback there.
    # `Sequents` and `CTT` are the two distribution sessions verified free of
    # it, and are here so the usage family has non-HOL gate corpora at all.
    for d in "${QUERY_TEST_AFP:-}/Abstract_Completeness" \
             "${QUERY_TEST_AFP:-}/AODV" \
             "${QUERY_TEST_AFP:-}/Category3" \
             "${QUERY_TEST_DISTRO:-}/FOL" \
             "${QUERY_TEST_DISTRO:-}/ZF" \
             "${QUERY_TEST_DISTRO:-}/Sequents" \
             "${QUERY_TEST_DISTRO:-}/CTT"; do
      [ -d "$d" ] && corpora+=("$d")
    done
  fi
fi
if [ ${#corpora[@]} -eq 0 ]; then
  echo "difftest: no corpora (set \$QUERY_TEST_AFP / \$QUERY_TEST_DISTRO)" >&2
  exit 2
fi

# --------------------------------------------------------------------------
# The oracle is pinned to a VERSION, and a mismatch is a refusal.
#
# The frozen reference tree moves when upstream moves (0.7.0 -> 0.8.1 at
# [p9-merge]), and every expectation in this matrix is that release's output.
# An older `query` still runs, still answers, and still diffs -- it just diffs
# against the wrong contract, so the failures it reports name the oracle's age
# rather than the port's defects.  Refuse instead, before any corpus is read.
# --------------------------------------------------------------------------
oracle_version=$("$oracle_bin" --version 2>/dev/null | awk '{print $NF}')
if [ -z "$oracle_version" ]; then
  echo "difftest: cannot run the oracle '$oracle_bin' (\$QUERY_ORACLE)" >&2
  echo "difftest: make one from this tree --" >&2
  echo "difftest:   python3 -m venv .dev/oracle && .dev/oracle/bin/pip install -e ." >&2
  echo "difftest:   QUERY_ORACLE=.dev/oracle/bin/query dev/difftest.sh" >&2
  exit 2
fi
if [ "$oracle_version" != "$ORACLE_VERSION" ]; then
  echo "difftest: oracle '$oracle_bin' is $oracle_version, expected $ORACLE_VERSION" >&2
  echo "difftest: the matrix pins the frozen src/isabelle_query/ tree's version;" >&2
  echo "difftest: set \$QUERY_ORACLE to one built from THIS tree --" >&2
  echo "difftest:   python3 -m venv .dev/oracle && .dev/oracle/bin/pip install -e ." >&2
  echo "difftest:   QUERY_ORACLE=.dev/oracle/bin/query dev/difftest.sh" >&2
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
# `paragraph` is a heading the recogniser accepts and the outline renderer's
# indent table does not, so `outline` dies on it — in BOTH implementations, with
# the same partial stdout and the same exit 1.  Kept in the matrix because that
# agreement is the thing under test, and it is easy to "fix" one side by
# accident.
cat >"$FIX_NOROOT/Para.thy" <<'THY'
theory Para
  imports Main
begin

section \<open>A section\<close>

definition one :: nat where "one = 1"

paragraph \<open>A paragraph, which the outline indent table has no entry for\<close>

lemma one_pos: "one > 0" by (simp add: one_def)

end
THY
cp "$FIX_NOROOT/Solo.thy" "$FIX_MARKER/sub/Solo.thy"
printf 'sub\n' >"$FIX_MARKER/.isabelle-query"
cat >"$FIX_MD" <<'MD'
# notes

The entry grammar does not apply to prose, so `grep` over this file is
plain line matching with no owning-entry column.
MD

# M3 corpus configs for the `shape steps` / `shape lemma` --config surface.
# The committed one is the real thing; the other three exercise the selection
# rules and the two ways a config can be rejected.
M3_CONFIG=$repo/configs/m3.toml
FIX_TOML_MULTI=$fixtures/multi.toml
FIX_TOML_BAD=$fixtures/bad.toml
FIX_TOML_MISSING=$fixtures/no-such-config.toml
cat >"$FIX_TOML_MULTI" <<'TOML'
# Two tables, so a bare --config is ambiguous and must be refused.
[Alpha]
constructors = ["Cfg"]
selectors = ["fst", "snd"]
relations = ["sim"]

[Beta]
selectors = ["hd"]   # trailing comment, and no constructors/relations keys
TOML
printf 'this is not a toml document\n' >"$FIX_TOML_BAD"
rm -f "$FIX_TOML_MISSING"

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
  LEMMA_THY=""; LEMMA_LO=""; LEMMA_HI=""; LEMMA_NAME=""
  local size tag name thy span
  for r in "${rows[@]}"; do
    read -r size tag name thy span <<<"$r"
    [ "$name" = "?" ] && continue
    [ -z "$NAME1" ] && NAME1=$name
    if [ -z "$NAME2" ] && [ "$name" != "$NAME1" ]; then NAME2=$name; fi
    if [ -z "$LEMMA_THY" ] && { [ "$tag" = LEMMA ] || [ "$tag" = THEOREM ]; }; then
      LEMMA_THY=$thy
      LEMMA_NAME=$name
      LEMMA_LO=${span#(}; LEMMA_LO=${LEMMA_LO%%..*}
      LEMMA_HI=${span%)}; LEMMA_HI=${LEMMA_HI##*..}
    fi
  done
  [ -z "$LEMMA_NAME" ] && LEMMA_NAME=$NAME1
  [ -z "$NAME1" ] && NAME1=$THY1
  [ -z "$NAME2" ] && NAME2=$NAME1
  if [ -z "$LEMMA_THY" ]; then
    LEMMA_THY=$THY1; LEMMA_LO=1; LEMMA_HI=2
  fi
  # A line well inside the biggest proof — the drill-down case.
  MID=$(( (LEMMA_LO + LEMMA_HI) / 2 ))
  [ "$MID" -lt "$LEMMA_LO" ] && MID=$LEMMA_LO

  # The most- and least-used proof method, for the `methods NAME` form.  On a
  # corpus where the oracle's line index crashes (D7) this comes back empty and
  # falls back to `simp`, which is in every method table there is — the case
  # still runs, it just stops being corpus-derived where the oracle cannot say.
  METHODS=()
  mapfile -t METHODS < <(run_oracle -R "$corpus" methods --names 2>/dev/null)
  METH1=${METHODS[0]:-simp}
  if [ ${#METHODS[@]} -gt 0 ]; then
    METHLAST=${METHODS[$(( ${#METHODS[@]} - 1 ))]}
  else
    METHLAST=$METH1
  fi

  # A real path to THY1, for the path-form positionals, and a slice of it for
  # the stdin cases.  The SUBJECT is still oracle-derived; only where its file
  # sits on disk is a filesystem fact.
  THY1_PATH=$(find "$corpus" -name "$THY1.thy" -print -quit 2>/dev/null)
  [ -z "$THY1_PATH" ] && THY1_PATH=$FIX_NOROOT/Solo.thy
  STDIN_FILE=$outdir/stdin.thy
  head -400 "$THY1_PATH" >"$STDIN_FILE"
  # A symlink to it, because a positional's LABEL comes from the resolved file
  # (so `Foo.thy:LINE`, not `Link.thy:LINE`) and that is easy to get wrong.
  THY1_LINK=$outdir/link/Link.thy
  mkdir -p "$outdir/link"; rm -f "$THY1_LINK"
  ln -s "$THY1_PATH" "$THY1_LINK"

  # A PREFIX of this corpus's own per-proof census, for `shape census
  # --resume`: the flag skips records already present, so resuming from a
  # prefix must emit exactly the rest.  Derived from the ORACLE like every
  # other subject, so the fixture cannot encode the rewrite's answer.  `head`
  # closes the pipe, which stops the producer early on a big corpus — the
  # fixture only has to be a valid prefix, and on the smallest corpora it is
  # the whole census, which exercises the everything-skipped path too.
  # (`$tag` is shadowed by a local in the loop above, hence the basename.)
  CENSUS_PREFIX=$outdir/$(basename "$corpus").census-prefix.jsonl
  run_oracle -R "$corpus" shape census 2>/dev/null | head -n 200 >"$CENSUS_PREFIX"
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
  # The two empties, from the SEARCH side [count-mode-zero]: an absent pattern
  # is a real search with a real answer, so `-c` is `0` and `--names` is
  # nothing -- not the sentence the human modes print.
  c find-count-zero        find zzz_no_such_name_zzz -c
  c find-names-zero        find zzz_no_such_name_zzz --names

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
  c show-count-zero        show zzz_no_such_name_zzz -c

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
  c grep-symlink           grep "$NAME1" "$THY1_LINK"
  c lines-symlink          lines "$THY1_LINK" 1..4

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
  g top-abbrev-root        plain  --roo "$CORPUS" summary -c
  g top-root-glued         plain  "-R$CORPUS" summary -c
  g top-root-equals        plain  "--root=$CORPUS" summary -c
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
  g paragraph-outline      plain  -R "$FIX_NOROOT" outline Para
  g paragraph-summary      plain  -R "$FIX_NOROOT" summary
  g closed-stdout          pipe   find . . . . -a -V

  # -- deps / uses (the IMPORT graph) -------------------------------------
  c deps-default           deps "$THY1"
  c deps-recursive         deps -r "$THY1"
  c deps-recursive-long    deps --recursive "$THY1"
  c deps-last              deps "$THYLAST"
  c deps-batch             deps "$THY1" "$THYLAST"
  c deps-unknown           deps No_Such_Theory_Xyz
  c uses-default           uses "$THY1"
  c uses-recursive         uses -r "$THY1"
  c uses-last              uses "$THYLAST"
  c uses-batch             uses "$THY1" "$THYLAST"
  c uses-unknown           uses No_Such_Theory_Xyz

  # -- refs (the citation graph, rolled up per theory) ---------------------
  c refs-default           refs "$THY1"
  c refs-count             refs "$THY1" -c
  c refs-names             refs "$THY1" --names
  c refs-external          refs "$THY1" --external
  c refs-last              refs "$THYLAST"
  c refs-drop0             refs "$THY1" --drop-names-upto 0
  c refs-batch             refs "$THY1" "$THYLAST"
  c refs-unknown           refs No_Such_Theory_Xyz
  c refs-reach-name        refs "$THY1" --reach name

  # -- callers -------------------------------------------------------------
  c callers-default        callers "$NAME1"
  c callers-count          callers "$NAME1" -c
  c callers-names          callers "$NAME1" --names
  c callers-external       callers "$NAME1" --external
  c callers-context        callers "$NAME1" -U 2
  c callers-context-glued  callers "$NAME1" -U2
  c callers-recursive      callers -r "$NAME1"
  c callers-recursive-count callers -r "$NAME1" -c
  c callers-recursive-names callers -r "$NAME1" --names
  c callers-drop0          callers "$NAME1" --drop-names-upto 0
  c callers-drop-equals    callers "$NAME1" --drop-names-upto=2
  c callers-drop-abbrev    callers "$NAME1" --drop 0
  c callers-batch          callers "$NAME1" "$NAME2"
  c callers-unknown        callers zzz_no_such_name_zzz
  c callers-bad-drop       callers "$NAME1" --drop-names-upto abc
  # [citation-reach]: the compatibility mode, spelled the same on both sides.
  c callers-reach-name     callers "$NAME1" --reach name
  c callers-bad-reach      callers "$NAME1" --reach bogus

  # -- callees -------------------------------------------------------------
  c callees-default        callees "$NAME1"
  c callees-count          callees "$NAME1" -c
  c callees-names          callees "$NAME1" --names
  c callees-external       callees "$NAME1" --external
  c callees-recursive      callees -r "$NAME1"
  c callees-recursive-names callees -r "$NAME1" --names
  c callees-batch          callees "$NAME1" "$NAME2"
  c callees-unknown        callees zzz_no_such_name_zzz
  c callees-reach-name     callees "$NAME1" --reach name

  # -- unused --------------------------------------------------------------
  c unused-default         unused
  c unused-count           unused -c
  c unused-recursive       unused -r
  c unused-recursive-count unused -r -c
  c unused-by-theory       unused --by-theory
  c unused-by-theory-abbrev unused --by-t
  c unused-recursive-by-theory unused -r --by-theory
  c unused-roots           unused --roots
  c unused-roots-count     unused --roots -c
  c unused-keep            unused --keep "$NAME1"
  c unused-keep-list       unused --keep "$NAME1,$NAME2"
  c unused-keep-repeat     unused --keep "$NAME1" --keep "$NAME2"
  c unused-keep-unknown    unused --keep zzz_no_such_name_zzz
  c unused-drop2           unused --drop-names-upto 2
  c unused-reach-name      unused --reach name -c
  c ambiguous-unused-r     unused --r

  # -- methods / method ----------------------------------------------------
  c methods-default        methods
  c methods-all            methods -a
  c methods-count          methods -c
  c methods-names          methods --names
  c methods-all-names      methods -a --names
  c methods-named          methods "$METH1"
  c methods-named-count    methods "$METH1" -c
  c methods-named-names    methods "$METH1" --names
  c methods-least-used     methods "$METHLAST"
  c methods-alias          method "$METH1"
  c methods-table-only     methods presburger
  c methods-unknown        methods zzz_no_such_method_zzz

  # -- graph ---------------------------------------------------------------
  c graph-default          graph
  c graph-citation         graph citation
  c graph-imports          graph imports
  c graph-dot              graph -f dot
  c graph-imports-dot      graph imports -f dot
  c graph-format-long      graph --format dot
  c graph-format-equals    graph --format=dot
  c graph-format-glued     graph -fdot
  c graph-theory-scope     graph --theory "$THY1"
  c graph-drop0            graph --drop-names-upto 0
  c graph-bad-kind         graph no_such_kind
  c graph-bad-format       graph -f xml
  c graph-reach-name       graph --reach name

  # -- shape summary -------------------------------------------------------
  c shape-summary          shape summary
  c shape-summary-json     shape summary --json
  c shape-summary-scope-entry   shape summary --scope entry
  c shape-summary-scope-proof   shape summary --scope proof
  c shape-summary-content-code  shape summary --content code
  c shape-summary-content-prose shape summary --content prose
  c shape-summary-scope-content shape summary --scope entry --content code
  c shape-summary-scope-equals  shape summary --scope=entry
  c shape-summary-json-scope    shape summary --json --scope entry
  c shape-summary-bad-scope     shape summary --scope no_such_scope
  c shape-summary-bad-content   shape summary --content no_such_content

  # -- shape steps ---------------------------------------------------------
  c shape-steps            shape steps
  c shape-steps-all        shape steps -a
  c shape-steps-all-long   shape steps --all
  c shape-steps-json       shape steps --json
  c shape-steps-json-all   shape steps -a --json
  c shape-steps-theory     shape steps "$THY1"
  c shape-steps-theory-last shape steps "$THYLAST"
  c shape-steps-locus      shape steps "$LEMMA_THY:$LEMMA_LO..$LEMMA_HI"
  c shape-steps-open-hi    shape steps "$LEMMA_THY:$LEMMA_LO.."
  c shape-steps-single-line shape steps "$LEMMA_THY:$MID"
  c shape-steps-locus-json shape steps "$LEMMA_THY:$LEMMA_LO..$LEMMA_HI" --json
  c shape-steps-empty-span shape steps "$THY1:999990..999999"
  c shape-steps-unknown    shape steps No_Such_Theory_Xyz
  c shape-steps-not-locus  shape steps not-a-locus
  # --config: the M3 frame_ratio columns, and every way the selection can fail.
  c shape-steps-config     shape steps --config "$M3_CONFIG" --json
  c shape-steps-config-plain shape steps --config "$M3_CONFIG"
  c shape-steps-config-corpus shape steps --config "$M3_CONFIG" --corpus Cook_Levin --json
  c shape-steps-config-multi-pick \
                           shape steps --config "$FIX_TOML_MULTI" --corpus Alpha --json
  c shape-steps-config-multi-pick2 \
                           shape steps --config "$FIX_TOML_MULTI" --corpus Beta --json
  c shape-steps-config-no-corpus shape steps --config "$FIX_TOML_MULTI"
  c shape-steps-config-bad-corpus shape steps --config "$M3_CONFIG" --corpus No_Such_Corpus
  c shape-steps-config-missing shape steps --config "$FIX_TOML_MISSING"
  c shape-steps-config-bad shape steps --config "$FIX_TOML_BAD"
  c shape-steps-config-equals shape steps "--config=$M3_CONFIG" --json
  c shape-steps-ambiguous-co shape steps --co "$M3_CONFIG"

  # -- shape lemma ---------------------------------------------------------
  c shape-lemma            shape lemma "$LEMMA_NAME"
  c shape-lemma-json       shape lemma "$LEMMA_NAME" --json
  c shape-lemma-largest    shape lemma "$NAME1"
  c shape-lemma-batch      shape lemma "$NAME1" "$NAME2"
  c shape-lemma-substring  shape lemma "${LEMMA_NAME:0:3}"
  c shape-lemma-config     shape lemma "$LEMMA_NAME" --config "$M3_CONFIG" --json
  c shape-lemma-unknown    shape lemma zzz_no_such_name_zzz
  c shape-lemma-no-arg     shape lemma

  # -- shape widest --------------------------------------------------------
  c shape-widest           shape widest
  c shape-widest-n3        shape widest -N 3
  c shape-widest-n-glued   shape widest -N3
  c shape-widest-top-long  shape widest --top 5
  c shape-widest-n0        shape widest -N 0
  c shape-widest-huge      shape widest -N 100000
  c shape-widest-w1        shape widest --metric w1
  c shape-widest-fanin     shape widest --metric fanin
  c shape-widest-live      shape widest --metric live
  c shape-widest-w2        shape widest --metric w2
  c shape-widest-metric-equals shape widest --metric=w1
  c shape-widest-json      shape widest --json -N 5
  c shape-widest-scoped    shape widest "$THY1" -N 5
  c shape-widest-path      shape widest "$THY1_PATH" -N 5
  c shape-widest-bad-n     shape widest -N abc
  c shape-widest-bad-metric shape widest --metric no_such_metric

  # -- shape census --------------------------------------------------------
  c shape-census           shape census
  c shape-census-resume    shape census --resume "$CENSUS_PREFIX"
  c shape-census-resume-missing shape census --resume "$FIX_TOML_MISSING"
  c shape-census-resume-garbage shape census --resume "$FIX_TOML_BAD"

  # -- the shape group's own argument grammar ------------------------------
  c shape-bad-view         shape no_such_view
  g shape-root-before-view plain  shape -R "$CORPUS" summary
  g shape-root-after-view  plain  shape summary -R "$CORPUS"
  g shape-root-glued-group plain  shape "-R$CORPUS" summary
  g shape-root-equals-view plain  shape summary "--root=$CORPUS"
  g shape-bad-group-flag   plain  shape --json summary
  g shape-census-pipe      pipe   shape census
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

# The CASE_ID and CORPUS columns are shell GLOBS, so one line can pin a whole
# command family on a corpus the oracle cannot run it on (`callers-*  FOL`).
# `*` alone keeps its old meaning as the everything glob.  Matching in the shell
# rather than in awk is what makes the pattern a glob rather than a literal.
_pin_lookup() {   # $1 = case id, $2 = corpus basename; prints the reason
  local id=$1 corpus=$2 pid pcorpus rest
  [ -f "$pins_file" ] || return 1
  while read -r pid pcorpus rest; do
    case $pid in ''|'#'*) continue ;; esac
    [ -n "$pcorpus" ] || continue
    # shellcheck disable=SC2053  (glob match is the point)
    [[ $id == $pid ]] || continue
    # shellcheck disable=SC2053
    [[ $corpus == $pcorpus ]] || continue
    printf '%s\n' "$rest"
    return 0
  done <"$pins_file"
  return 1
}

is_pinned() { _pin_lookup "$1" "$2" >/dev/null; }

pin_reason() { _pin_lookup "$1" "$2"; }

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
