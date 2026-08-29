#!/usr/bin/env bash
#
# dev/bench.sh -- the three ways to ask this tool a question, timed against
# each other.
#
#   oracle     the Python implementation (`query` on PATH), cold
#   cold       `isabelle query --no-server`, a fresh JVM per invocation
#   warm       query_client.py against a resident server
#   declined   `isabelle query --client-cold`: the client declines (exit 97,
#              nothing written) and the shim runs the query cold (tier
#              `decline`; `delegate` is the old name for this tier, and the
#              mode it used to measure was deleted in P8)
#
# The cold column says `--no-server` for a reason: since P7b that is what makes
# it cold.  Without the flag `isabelle query` is the thin client, and the column
# would be measuring the WARM path under the cold label -- the exact mistake
# dev/P6C-STATUS.md §5 records for the tiny tier's subject.
#
# And "process start" below is process start, not JVM start.  A JVM boots in
# ~30 ms; the ~870 ms a cold invocation pays is `scala_build` (~405 ms), the
# settings shell (~180 ms) and class loading (~250 ms).  dev/P8-STATUS.md has
# the breakdown and the measurements behind it.
#
# Every number is a MEDIAN of $RUNS runs (default 5, minimum 3), wall clock,
# measured around the whole invocation exactly as a user pays for it -- process
# start included, because process start is the thing under discussion.  A
# discarded warm-up run precedes each series, so no column is charged for the
# other's page cache.
#
# Usage:
#   dev/bench.sh [small|full|memory|all]
#
#   tiny     tier (a) alone                         -- seconds; for re-measuring
#            one row without disturbing the rest of the table
#   decline  three rows through the decline route          -- about a minute
#   small    the per-entry tiers (a), (b) and (c)   -- about two minutes
#   full     adds the whole-AFP tier (d)            -- about twenty
#   heavy    tier (e): one big session (src/HOL/Analysis) and the two
#            largest AFP entries, with hot subjects -- about five minutes
#   memory   peak RSS, at the stock heap and at -Xmx512m
#   all      everything (the default)
#
# Corpora come from $QUERY_TEST_AFP / $QUERY_TEST_DISTRO, as everywhere else;
# no path is hard-coded.  The scratch Isabelle user home is $USER_HOME,
# defaulting to the repository's own .dev -- never the real one.  A
# bench-private server name keeps a developer's own server out of it, and a
# trap stops the bench's server however the run ends.
#
# Read the machine's load before trusting any of this: a number taken beside a
# build is not a number.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

TIER="${1:-all}"
RUNS="${RUNS:-5}"

AFP="${QUERY_TEST_AFP:-}"
DISTRO="${QUERY_TEST_DISTRO:-}"

missing=""
[ -d "${AFP:-/nonexistent}" ] || missing="$missing  \$QUERY_TEST_AFP (an AFP thys directory)"$'\n'
[ -d "${DISTRO:-/nonexistent}" ] || missing="$missing  \$QUERY_TEST_DISTRO (the distribution's src)"$'\n'
command -v query >/dev/null || missing="$missing  the Python oracle \`query\` on PATH"$'\n'
if [ -n "$missing" ]; then
  echo "bench: a benchmark without all three columns is not one. Missing:" >&2
  printf '%s' "$missing" >&2
  exit 2
fi

TINY="$AFP/Abstract_Completeness"
MED="$AFP/Category3"
HOL="$DISTRO/HOL"
for d in "$TINY" "$MED" "$HOL"; do
  [ -d "$d" ] || { echo "bench: no corpus at $d" >&2; exit 2; }
done

export BENCH_REPO="$REPO"
OUT="$REPO/.dev/bench-out"
mkdir -p "$OUT" || exit 2
CLIENT="$REPO/query_base/lib/scripts/query_client.py"
SETTINGS="$(isabelle getenv -b ISABELLE_HOME_USER)/etc/settings"

SERVER="bench-$$"
export ISABELLE_QUERY_CLIENT_SERVER="$SERVER"
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-cache.json"
export ISABELLE_QUERY_CLIENT_TIMEOUT=3600

cleanup() {
  isabelle server -x -n "$SERVER" >/dev/null 2>&1
  pkill -f "server -n $SERVER" >/dev/null 2>&1
  # The heap pin is a line in a settings file; leaving it behind would change
  # every later Isabelle invocation in this scratch home.
  restore_heap
}
trap cleanup EXIT INT TERM

HEAP_MARK="# dev/bench.sh heap pin -- removed by the same script"
pin_heap() {
  restore_heap
  {
    echo "$HEAP_MARK"
    echo "ISABELLE_TOOL_JAVA_OPTIONS=\"\$ISABELLE_TOOL_JAVA_OPTIONS $1\"  $HEAP_MARK"
  } >>"$SETTINGS"
}
# `grep -v` exits 1 when it filters EVERYTHING away, which is exactly the case
# here once the pin is the only content -- so the mv must not hang off `&&`.
# It did, and the pin survived a whole run.
restore_heap() {
  [ -f "$SETTINGS" ] || return 0
  grep -v -F "$HEAP_MARK" "$SETTINGS" >"$SETTINGS.bench" 2>/dev/null
  mv -f "$SETTINGS.bench" "$SETTINGS"
}

# --------------------------------------------------------------------------
# timing
# --------------------------------------------------------------------------

# The median of an odd number of runs is a real observation, not an average of
# two; with an even count take the lower middle, which is the conservative
# direction for a claim that something is fast.
median() {
  local n
  n=$(printf '%s\n' "$@" | wc -l)
  printf '%s\n' "$@" | sort -n | sed -n "$(( (n + 1) / 2 ))p"
}

# Milliseconds around one invocation, discarding a warm-up.  stdout goes to a
# file so the shell is never the bottleneck and so the answers can be diffed.
time_ms() {
  local label; label=$(safe_name "$1"); shift
  local i start times=()
  "$@" >"$OUT/$label.out" 2>"$OUT/$label.err"
  for ((i = 0; i < RUNS; i++)); do
    start=$(date +%s%N)
    "$@" >"$OUT/$label.out" 2>"$OUT/$label.err"
    times+=( $(( ($(date +%s%N) - start) / 1000000 )) )
  done
  median "${times[@]}"
}

# `%M` is peak resident set in KB, which is what "how much memory did this
# cost" means to the machine, not to the allocator.
peak_kb() {
  local label; label=$(safe_name "$1"); shift
  /usr/bin/time -f "%M" -o "$OUT/$label.rss" "$@" \
    >"$OUT/$label.out" 2>"$OUT/$label.err"
  tail -1 "$OUT/$label.rss"
}

# A label is prose; a filename is not.  `summary (src/HOL)` as a path produced
# two shell errors and a row of zeroes before this existed.
safe_name() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'; }

row() { printf '| %-42s | %10s | %10s | %10s |\n' "$1" "$2" "$3" "$4"; }

# A row is only worth printing if the three columns AGREE about the answer.
# `md5` of the captured stdout, compared where an oracle column exists.
same() {
  [ "$(md5sum <"$OUT/$(safe_name "$1").out" | cut -d' ' -f1)" = \
    "$(md5sum <"$OUT/$(safe_name "$2").out" | cut -d' ' -f1)" ] &&
    echo "=" || echo "DIFFERS"
}

echo "# isabelle-query benchmark"
echo
echo "date:      $(date -u '+%Y-%m-%d %H:%M UTC')"
echo "host:      $(uname -s) $(uname -r) $(uname -m)"
echo "cpu:       $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//')"
echo "cores:     $(nproc 2>/dev/null)"
echo "memory:    $(awk '/MemTotal/ {printf "%.0f GB", $2/1048576}' /proc/meminfo 2>/dev/null)"
echo "isabelle:  $(isabelle getenv -b ISABELLE_IDENTIFIER)"
echo "oracle:    $(query --version)"
echo "rewrite:   $(isabelle query --no-server -V)"
echo "load:      $(uptime | sed 's/.*average[s]*: *//')"
echo "runs:      median of $RUNS"
echo

# --------------------------------------------------------------------------
# the warm server
# --------------------------------------------------------------------------

case "$TIER" in
  tiny|small|full|heavy|memory|decline|delegate|all) ;;
  *) echo "bench: unknown tier '$TIER' (tiny|small|full|heavy|memory|decline|all)" >&2
     exit 2 ;;
esac

if [ "$TIER" != "memory" ]; then
  python3 "$CLIENT" --client-status >"$OUT/server-status.txt" 2>&1 ||
    { echo "bench: no warm server -- $(cat "$OUT/server-status.txt")" >&2; exit 1; }
  grep -q STALE "$OUT/server-status.txt" &&
    { echo "bench: the server is stale; rebuild or restart it" >&2; exit 1; }
fi

bench3() {  # label, root, then argv
  local label="$1" root="$2"; shift 2
  local o c w
  o=$(time_ms "$label-oracle" query -R "$root" "$@")
  c=$(time_ms "$label-cold" isabelle query --no-server -R "$root" "$@")
  w=$(time_ms "$label-warm" python3 "$CLIENT" --client-limit 0 -R "$root" "$@")
  row "$label" "$o" "$c" "$w"
  local a b
  a=$(same "$label-oracle" "$label-cold")
  b=$(same "$label-cold" "$label-warm")
  [ "$a$b" = "==" ] || row "  ^ ANSWERS DISAGREE: oracle/cold $a, cold/warm $b" "" "" ""
}

bench2() {  # rewrite-only verbs: no oracle column exists to compare with
  local label="$1" root="$2"; shift 2
  local c w
  c=$(time_ms "$label-cold" isabelle query --no-server -R "$root" "$@")
  w=$(time_ms "$label-warm" python3 "$CLIENT" --client-limit 0 -R "$root" "$@")
  row "$label" "n/a" "$c" "$w"
  [ "$(same "$label-cold" "$label-warm")" = "=" ] ||
    row "  ^ ANSWERS DISAGREE cold/warm" "" "" ""
}

if [ "$TIER" = "tiny" ] || [ "$TIER" = "small" ] || [ "$TIER" = "all" ] ||
   [ "$TIER" = "full" ]; then
  echo "## (a) tiny -- Abstract_Completeness (2 theories, 81 entries)"
  echo
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  # The subject has to EXIST in the corpus.  `show expand` did not, so all
  # three columns timed the same "No entries matching" answer -- a row that
  # measured the parse and nothing else, and agreed across columns for the
  # wrong reason.  `fair_fenum` is a 27-line lemma in Abstract_Completeness.
  bench3 "show fair_fenum" "$TINY" show fair_fenum
  bench3 "summary" "$TINY" summary
  bench3 "callers mono" "$TINY" callers mono
  echo
fi

if [ "$TIER" = "small" ] || [ "$TIER" = "all" ] || [ "$TIER" = "full" ]; then
  echo "## (b) medium -- Category3 (28 theories)"
  echo
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  bench3 "callers comp_assoc (206 callers)" "$MED" callers comp_assoc
  bench3 "callers category_axioms" "$MED" callers category_axioms
  bench3 "shape summary" "$MED" shape summary
  echo

  echo "## (c) the two rewrite-only verbs -- src/HOL (1451 theories)"
  echo
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  bench2 "instances comm_monoid" "$HOL" instances comm_monoid
  bench2 "codeqs rev" "$HOL" codeqs rev
  bench3 "summary" "$HOL" summary
  echo
  echo "Recheck cost on a warm src/HOL index (the stat sweep every request pays):"
  python3 - "$HOL" <<'PY'
import os, sys, time
sys.path.insert(0, os.path.join(os.environ["BENCH_REPO"], "query_base", "lib", "scripts"))
import query_client as Q
cached = Q.read_cache()
isabelle = Q.find_isabelle(cached)
conn = Q.connect(isabelle, cached, os.environ["ISABELLE_QUERY_CLIENT_SERVER"],
                 False, 3600.0, False)
root = sys.argv[1]
head, body = conn.command("query_open", {"root": root, "limit": 0})
print("  first open:  %s ms, %s theories, %s entries, %s files fingerprinted"
      % (body.get("build_ms"), body.get("theories"), body.get("entries"),
         body.get("files_checked")))
best = None
for _ in range(5):
    head, body = conn.command("query_open", {"root": root, "limit": 0})
    ms = body.get("check_ms")
    best = ms if best is None else min(best, ms)
print("  recheck:     %s ms (best of 5), %s theories reparsed"
      % (best, body.get("reparsed")))
conn.close()
PY
  echo
fi

if [ "$TIER" = "full" ] || [ "$TIER" = "all" ]; then
  echo "## (d) the whole AFP -- $(find "$AFP" -name '*.thy' | wc -l) theory files"
  echo
  echo "Timed with RUNS=3; the warm column asks for an index over the whole"
  echo "checkout, which is what --client-limit 0 is for."
  echo
  RUNS_SAVED="$RUNS"
  RUNS=3
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  bench3 "summary --by-session" "$AFP" summary --by-session
  bench3 "shape census" "$AFP" shape census
  RUNS="$RUNS_SAVED"
  echo
fi

if [ "$TIER" = "heavy" ] || [ "$TIER" = "all" ]; then
  ANA="$DISTRO/HOL/Analysis"
  AC2="$AFP/AutoCorres2"
  JT="$AFP/JinjaThreads"
  hmissing=""
  for d in "$ANA" "$AC2" "$JT"; do [ -d "$d" ] || hmissing="$hmissing $d"; done
  if [ -n "$hmissing" ]; then
    echo "bench: the heavy tier's corpora are not here:$hmissing" >&2
    exit 2
  fi

  echo "## (e) heavy -- one big session, and the two largest AFP entries"
  echo
  echo "src/HOL/Analysis is 106 theories / 178k lines under a session-less"
  echo "root; AutoCorres2 (120k lines) and JinjaThreads (89k) are the largest"
  echo "AFP entries by theory volume.  Subjects are HOT on purpose (the bench"
  echo "rule from tier (a): a subject that does not exist times the parse and"
  echo "nothing else): \`has_integral\` has 515 callers under the oracle here,"
  echo "\`refines\` 1,063, \`wf_prog\` 200.  Caller rows may print a DISAGREE"
  echo "marker: the rewrite's import-reachability filter (DIVERGENCES D13)"
  echo "drops attributions the citing theory cannot see, by design."
  echo
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  bench3 "Analysis: summary" "$ANA" summary
  bench3 "Analysis: callers has_integral (515)" "$ANA" callers has_integral
  bench3 "Analysis: shape summary" "$ANA" shape summary
  bench3 "AutoCorres2: callers refines (1063)" "$AC2" callers refines
  bench3 "JinjaThreads: summary" "$JT" summary
  bench3 "JinjaThreads: callers wf_prog (200)" "$JT" callers wf_prog
  echo
fi

if [ "$TIER" = "decline" ] || [ "$TIER" = "delegate" ] || [ "$TIER" = "all" ]; then
  echo "## the decline route (P8)"
  echo
  echo "P7b-P7d measured a fourth mode here: a fresh JVM that found the warm"
  echo "server and asked it.  P8 deleted it, so what this tier measures now is"
  echo "the route that replaced it -- the client DECLINING (exit 97, nothing"
  echo "written) and the shim running the query cold.  The number to watch is"
  echo "the third column against the first: a decline must cost what cold"
  echo "costs, because it IS cold plus one python process, and it must answer"
  echo "the same bytes.  \`delegate\` is still accepted as a tier name."
  echo
  printf '| %-42s | %10s | %10s | %10s |\n' "invocation" "cold ms" "warm ms" "declined ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"

  # The warm column reaches the SAME server this script already started,
  # because $ISABELLE_QUERY_CLIENT_SERVER names it for both front ends.
  bench_decline() {  # label, root, then argv
    local label="$1" root="$2"; shift 2
    local c w d
    c=$(time_ms "$label-dcold" isabelle query --no-server -R "$root" "$@")
    w=$(time_ms "$label-dwarm" python3 "$CLIENT" --client-limit 0 -R "$root" "$@")
    d=$(time_ms "$label-decl" isabelle query --client-cold -R "$root" "$@")
    row "$label" "$c" "$w" "$d"
    # The whole claim of this route is byte identity with the cold tool.  A row
    # whose columns disagree is not a timing, it is a bug report.
    [ "$(same "$label-dcold" "$label-decl")" = "=" ] ||
      row "  ^ DECLINED ANSWER DIFFERS FROM COLD" "" "" ""
  }

  bench_decline "show fair_fenum (2 theories)" "$TINY" show fair_fenum
  bench_decline "summary src-HOL" "$HOL" summary
  bench_decline "instances comm_monoid src-HOL" "$HOL" instances comm_monoid
  echo
fi

if [ "$TIER" = "memory" ] || [ "$TIER" = "all" ]; then
  echo "## memory -- peak RSS"
  echo
  echo "Isabelle's own etc/settings OVERWRITES \$ISABELLE_TOOL_JAVA_OPTIONS from"
  echo "the environment, and the JVM ignores \$_JAVA_OPTIONS here, so the only"
  echo "override that takes is a line in \$ISABELLE_HOME_USER/etc/settings."
  echo "This script writes one, runs, and removes it again."
  echo
  printf '| %-42s | %14s | %14s |\n' "invocation" "stock heap MB" "-Xmx512m MB"
  printf '|%s|%s|%s|\n' "-------------------------------------------" \
    "---------------:" "---------------:"

  mem_row() {
    local label="$1" root="$2"; shift 2
    local a b
    restore_heap
    a=$(peak_kb "$label-mem-stock" isabelle query --no-server -R "$root" "$@")
    pin_heap "-Xmx512m"
    b=$(peak_kb "$label-mem-512" isabelle query --no-server -R "$root" "$@")
    restore_heap
    local sl; sl=$(safe_name "$label")
    if ! cmp -s "$OUT/$sl-mem-stock.out" "$OUT/$sl-mem-512.out"; then
      printf '| %-42s | %14s | %14s |\n' "$label  [ANSWERS DIFFER]" \
        "$((a / 1024))" "$((b / 1024))"
    else
      printf '| %-42s | %14s | %14s |\n' "$label" "$((a / 1024))" "$((b / 1024))"
    fi
  }

  mem_row "summary src-HOL" "$HOL" summary
  mem_row "callers comp_assoc Category3" "$MED" callers comp_assoc
  mem_row "summary --by-session whole-AFP" "$AFP" summary --by-session
  echo
  echo "Python oracle, for scale:"
  a=$(peak_kb "oracle-mem-hol" query -R "$HOL" summary)
  printf '  query -R src/HOL summary: %s MB\n' "$((a / 1024))"
  echo
fi

python3 "$CLIENT" --client-stop >/dev/null 2>&1
sleep 1
echo "server stopped: $(pgrep -f "isabelle server -n $SERVER" | wc -l) process(es) left"
