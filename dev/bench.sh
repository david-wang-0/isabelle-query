#!/usr/bin/env bash
#
# dev/bench.sh -- query routes measured separately with byte comparisons.
#
#   oracle     the Python implementation (`query` on PATH), cold
#   cold       `isabelle query --no-server`, a fresh JVM per invocation
#   warm       query_client.py reuses a manually benchmark-owned retained host
#   auto       no selected host exists: the client owns startup through teardown
#              (tiny/HOL summary, fresh explicit name for each sample)
#
# No embedded jEdit/PIDE discovery latency is measured by this harness.
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
#   dev/bench.sh [tiny|small|full|afp|heavy|decline|auto|memory|all]
#
#   tiny     tier (a) alone                         -- seconds; for re-measuring
#            one row without disturbing the rest of the table
#   decline  three rows through the decline route          -- about a minute
#   small    the per-entry tiers (a), (b) and (c)   -- about two minutes
#   full     adds the whole-AFP tier (d)            -- about an hour
#   afp      tier (d) ALONE                          -- about fifty minutes,
#            dominated by the census row (heap depends on supplied settings)
#   heavy    tier (e): src/HOL/Analysis, AutoCorres2, and JinjaThreads
#            with hot subjects -- about five minutes
#   auto     tiny/HOL summary via owned auto fallback (also included in all)
#   memory   peak RSS, at the stock heap and at -Xmx512m
#   all      everything (the default)
#
# Corpora come from $QUERY_TEST_AFP / $QUERY_TEST_DISTRO, as everywhere else;
# no path is hard-coded.  The scratch Isabelle user home is $USER_HOME,
# supplied as a unique private home for this run (with verified components). A
# bench-private server name keeps a developer's own server out of it, and a
# trap closes the owned launcher pipe however the run ends. BENCH_CLIENT can
# point at the verified copied component's query_client.py. Set a deliberate
# ISABELLE_QUERY_SERVER_CACHE_MB budget (default 128 MiB); whole-AFP query_open
# may need more. Refusal fails the warm run, never silently times an unretained
# request. This is normalized retained source size, not the process heap/RSS.
# Each memory invocation has a BENCH_MEMORY_TIMEOUT deadline (positive integer
# seconds, default 600). Timeouts fail the sample and leave later cases running.
#
# Read the machine's load before trusting any of this: a number taken beside a
# build is not a number.

set -euo pipefail
shopt -s inherit_errexit

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:?supply a unique private benchmark home with verified components}"
[[ "$USER_HOME" = /* && -d "$USER_HOME" && "$USER_HOME" != "$HOME" ]] || {
  echo "bench: USER_HOME must be an existing absolute private home" >&2; exit 2;
}
# Atomic lease: never reuse a developer home or another benchmark run's home.
mkdir "$USER_HOME/.query-benchmark-owned" || exit 2
umask 077
export USER_HOME
if [ "${ISABELLE_QUERY_NO_SERVER:-}" = 1 ] || [ "${ISABELLE_QUERY_NO_CLIENT:-}" = 1 ]; then
  echo "bench: unset ISABELLE_QUERY_NO_SERVER/NO_CLIENT to measure warm and auto routes" >&2
  exit 2
fi

TIER="${1:-all}"
RUNS="${RUNS:-5}"
[[ "$RUNS" =~ ^[0-9]+$ && "$RUNS" -ge 3 ]] || { echo "bench: RUNS must be at least 3" >&2; exit 2; }
: "${ISABELLE_QUERY_SERVER_CACHE_MB:=128}"
export ISABELLE_QUERY_SERVER_CACHE_MB
[[ "$ISABELLE_QUERY_SERVER_CACHE_MB" =~ ^[0-9]+$ ]] || exit 2
: "${BENCH_READY_TIMEOUT:=60}"
[[ "$BENCH_READY_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || exit 2
BENCH_MEMORY_TIMEOUT="${BENCH_MEMORY_TIMEOUT-600}"
case "$TIER" in memory|all)
  [[ "$BENCH_MEMORY_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || {
    echo "bench: BENCH_MEMORY_TIMEOUT must be a positive integer in seconds" >&2
    exit 2
  };;
esac

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
OUT=$(mktemp -d "$USER_HOME/bench-out.XXXXXXXX")
CLIENT="${BENCH_CLIENT:-$REPO/query_base/lib/scripts/query_client.py}"
export BENCH_CLIENT="$CLIENT"
SETTINGS="$(isabelle getenv -b ISABELLE_HOME_USER)/etc/settings"
case "$SETTINGS" in "$USER_HOME"/*) ;; *) echo "bench: Isabelle home escaped private USER_HOME" >&2; exit 2;; esac
mkdir -p "$(dirname "$SETTINGS")"
source "$REPO/dev/owned-server.sh"
export ISABELLE_QUERY_CLIENT_TIMEOUT=3600
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-cache.json"
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  local owned_pid="$OWNED_SERVER_PID"
  if ! owned_server_stop; then
    echo "bench: owned server failed; inspect private artifacts" >&2
    rc=1
  elif [ -n "$owned_pid" ]; then
    echo "process cleanup: owned launcher group stopped; supervisor reaped"
  else
    echo "process cleanup: no retained host launched"
  fi
  restore_heap || rc=1
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

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
  local rc=0
  grep -v -F "$HEAP_MARK" "$SETTINGS" >"$SETTINGS.bench" || rc=$?
  [ "$rc" -le 1 ] || return "$rc"
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
  local i failed=0 times=()
  capture "$label" warmup "$@"
  [ "$CAPTURE_FAILED" -eq 0 ] || failed=1
  for ((i = 0; i < RUNS; i++)); do
    capture "$label" "$i" "$@"
    [ "$CAPTURE_FAILED" -eq 0 ] || failed=1
    times+=( "$CAPTURE_MS" )
  done
  if [ "$failed" -eq 0 ]; then median "${times[@]}"; else echo FAILED; fi
}

# `%M` is peak resident set in KB, which is what "how much memory did this
# cost" means to the machine, not to the allocator.
# The supervisor is outside /usr/bin/time, so its RSS is not in the result.
memory_run() {
  python3 - "$BENCH_MEMORY_TIMEOUT" "$@" <<'PY_MEMORY'
import os, signal, subprocess, sys, time

seconds = int(sys.argv[1])
proc = None
launching = False
interrupted = None
timed_out = False

def stop(signum, _frame):
    global interrupted
    interrupted = signum
    if not launching:
        raise SystemExit(128 + signum)

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
try:
    # Defer signal exceptions until the child handle is assigned, without
    # passing a blocked TERM/INT mask to the child.
    launching = True
    try:
        proc = subprocess.Popen(sys.argv[2:], stdin=subprocess.DEVNULL,
                                start_new_session=True)
    finally:
        launching = False
    if interrupted is not None:
        raise SystemExit(128 + interrupted)
    deadline = time.monotonic() + seconds
    while os.waitid(os.P_PID, proc.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            timed_out = True
            print("bench: memory deadline exceeded (%s s); stopping owned process group" % seconds,
                  file=sys.stderr, flush=True)
            break
        time.sleep(min(.05, remaining))
finally:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    if proc is not None:
        # WNOWAIT keeps the leader unreaped, reserving its PID/group identity
        # until the LAST signal. Never look up a PID or group by name.
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        if timed_out or interrupted is not None:
            time.sleep(1)
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait(timeout=2)

rc = proc.returncode
sys.exit(124 if timed_out else (128 - rc if rc < 0 else rc))
PY_MEMORY
}

peak_kb() {
  local label; label=$(safe_name "$1"); shift
  capture "$label" rss memory_run /usr/bin/time -f "%M" -o "$OUT/$label.rss" "$@"
  if [ "$CAPTURE_FAILED" -eq 0 ]; then
    tail -1 "$OUT/$label.rss"
  else
    echo FAILED
  fi
}

# A label is prose; a filename is not.  `summary (src/HOL)` as a path produced
# two shell errors and a row of zeroes before this existed.
safe_name() { printf '%s' "${CASE_PREFIX:-meta}-$1" | tr -c 'A-Za-z0-9._-' '_'; }

row() { printf '| %-42s | %10s | %10s | %10s |\n' "$1" "$2" "$3" "$4"; }

# Keep every sample and its status, including warmups. Command failures do
# not skip later samples/cases; any failed series has no valid timing.
# A later success cannot
# overwrite evidence of an earlier failure or nondeterministic stdout.
capture() {
  local label="$1" sample="$2" rc=0 start; shift 2
  CAPTURE_FAILED=0
  start=$(date +%s%N)
  "$@" >"$OUT/$label.$sample.out" 2>"$OUT/$label.$sample.err" || rc=$?
  CAPTURE_MS=$(( ($(date +%s%N) - start) / 1000000 ))
  printf '%s\t%s\t%s\n' "$label" "$sample" "$rc" >>"$OUT/status.tsv"
  if [ "$rc" -ne 0 ]; then
    printf 'command failed: %s sample %s exited %s\n' "$label" "$sample" "$rc" >>"$OUT/failures.txt"
    echo "bench: $label sample $sample exited $rc (see private artifacts)" >&2
    CAPTURE_FAILED=1
    return 0
  fi
  if [ -f "$OUT/$label.out" ] && ! cmp -s "$OUT/$label.out" "$OUT/$label.$sample.out"; then
    echo "unstable stdout: $label sample $sample" >>"$OUT/failures.txt"
    CAPTURE_FAILED=1
    return 0
  fi
  cp "$OUT/$label.$sample.out" "$OUT/$label.out"
}

# D15 may explain oracle naming differences, but a label alone is no proof.
# Preserve and fail every mismatch; keep oracle/D15 review separate from a
# cold/warm disagreement, which is always a transport equivalence failure.
same() {
  if cmp -s "$OUT/$(safe_name "$1").out" "$OUT/$(safe_name "$2").out"; then
    echo "="
  else
    local kind="unexpected cold/warm or heap disagreement"
    case "$1" in *-oracle) kind="oracle disagreement: review D15 theory-leaf naming; not waived";; esac
    printf '%s: %s / %s\n' "$kind" "$1" "$2" >>"$OUT/failures.txt"
    echo "DIFFERS ($kind)"
  fi
}

echo "# isabelle-query benchmark"
echo
echo "date:      $(date -u '+%Y-%m-%d %H:%M UTC')"
echo "host:      $(uname -s) $(uname -r) $(uname -m)"
echo "cpu:       $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//')"
echo "cores:     $(nproc 2>/dev/null)"
echo "memory:    $(awk '/MemTotal/ {printf "%.0f GB", $2/1048576}' /proc/meminfo 2>/dev/null)"
identifier=$(isabelle getenv -b ISABELLE_IDENTIFIER)
echo "isabelle:  $identifier"
oracle_version=$(query --version)
echo "oracle:    $oracle_version"
rewrite_version=$(isabelle query --no-server -V)
echo "rewrite:   $rewrite_version"
echo "load:      $(uptime | sed 's/.*average[s]*: *//')"
echo "runs:      median of $RUNS (whole AFP: 3)"
echo "warm mode: manually owned retained host (startup excluded from warm column)"
echo "discovery: embedded jEdit/PIDE latency not measured"
echo "retention: requested budget $ISABELLE_QUERY_SERVER_CACHE_MB MiB (default 128), request limit 0"
echo "           normalized retained source; separate from JVM heap and peak RSS"
echo "artifacts: $OUT"
echo

# --------------------------------------------------------------------------
# the warm server
# --------------------------------------------------------------------------

case "$TIER" in
  tiny|small|full|afp|heavy|memory|decline|delegate|auto|all) ;;
  *) echo "bench: unknown tier '$TIER' (tiny|small|full|afp|heavy|memory|decline|auto|all)" >&2
     exit 2 ;;
esac

if [ "$TIER" != "memory" ] && [ "$TIER" != "auto" ]; then
  owned_server_start "$CLIENT" "$OUT" "$BENCH_READY_TIMEOUT"
  cat "$OUT/server-ready.json"
  echo
fi

# THE WARM COLUMN, run the way the shim runs it.
#
# Since P8 the client does not execute the cold path itself: for an invocation
# on its bypass list (a census, a dump, anything reading stdin) it exits
# $EXIT_RUN_COLD having written NOTHING, and `lib/Tools/query` runs the query.
# A warm column that stopped at the decline would time an empty file -- which
# is exactly what it did on 2026-08-30, reporting `shape census` warm at 29 ms
# against a 0-byte output.  This script's own cold/warm comparison caught it,
# which is the entire reason that comparison is here.
#
# So finish the decline, as the shim would.  For every invocation the client
# actually serves this is one `[ $rc -ne 97 ]` and changes nothing.
EXIT_RUN_COLD=97
warm_run() {  # root, then argv; time_ms has already redirected our output
  python3 "$CLIENT" --client-limit 0 -R "$1" "${@:2}"
  local rc=$?
  [ "$rc" -ne "$EXIT_RUN_COLD" ] && return "$rc"
  isabelle query --no-server -R "$1" "${@:2}"
}

declare -A OPENED_ROOTS=()
ensure_index() {
  local root="$1"
  [ "${OPENED_ROOTS[$root]:-}" = yes ] && return 0
  python3 - "$CLIENT" "$root" <<'PY_OPEN'
import importlib.util, os, sys, time
spec = importlib.util.spec_from_file_location("bench_client", sys.argv[1])
Q = importlib.util.module_from_spec(spec)
spec.loader.exec_module(Q)
cached = Q.read_cache()
name = os.environ["ISABELLE_QUERY_CLIENT_SERVER"]
endpoint = Q.registry_lookup(Q.find_isabelle(cached), cached, name)
if endpoint is None:
    raise SystemExit("bench: owned server disappeared")
conn = Q.probe(name, endpoint, time.monotonic() + 5)
try:
    Q.ready_connection(conn, 3600.0, False, False)
    head, body = conn.command("query_open", {"root": sys.argv[2], "limit": 0})
    if head != "OK":
        raise SystemExit("bench: warm query_open refused: %s" % body)
finally:
    conn.close()
PY_OPEN
  OPENED_ROOTS[$root]=yes
}

bench3() {  # label, root, then argv
  local label="$1" root="$2"; shift 2
  ensure_index "$root"
  local o c w
  o=$(time_ms "$label-oracle" query -R "$root" "$@")
  c=$(time_ms "$label-cold" isabelle query --no-server -R "$root" "$@")
  w=$(time_ms "$label-warm" warm_run "$root" "$@")
  row "$label" "$o" "$c" "$w"
  local a b
  a=$(same "$label-oracle" "$label-cold")
  b=$(same "$label-cold" "$label-warm")
  [ "$a$b" = "==" ] || row "  ^ ANSWERS DISAGREE: oracle/cold $a, cold/warm $b" "" "" ""
  return 0
}

bench2() {  # rewrite-only verbs: no oracle column exists to compare with
  local label="$1" root="$2"; shift 2
  ensure_index "$root"
  local c w
  c=$(time_ms "$label-cold" isabelle query --no-server -R "$root" "$@")
  w=$(time_ms "$label-warm" warm_run "$root" "$@")
  row "$label" "n/a" "$c" "$w"
  [ "$(same "$label-cold" "$label-warm")" = "=" ] ||
    row "  ^ ANSWERS DISAGREE cold/warm" "" "" ""
}

if [ "$TIER" = "tiny" ] || [ "$TIER" = "small" ] || [ "$TIER" = "all" ] ||
   [ "$TIER" = "full" ]; then
  CASE_PREFIX=tiny
  echo "## (a) tiny -- Abstract_Completeness"
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
  CASE_PREFIX=medium
  echo "## (b) medium -- Category3"
  echo
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  bench3 "callers comp_assoc" "$MED" callers comp_assoc
  bench3 "callers category_axioms" "$MED" callers category_axioms
  bench3 "shape summary" "$MED" shape summary
  echo

  CASE_PREFIX=hol
  echo "## (c) the two rewrite-only verbs -- src/HOL"
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
sys.path.insert(0, os.path.dirname(os.environ["BENCH_CLIENT"]))
import query_client as Q
cached = Q.read_cache()
isabelle = Q.find_isabelle(cached)
name = os.environ["ISABELLE_QUERY_CLIENT_SERVER"]
endpoint = Q.registry_lookup(isabelle, cached, name)
if endpoint is None:
    raise SystemExit("owned benchmark server missing")
conn = Q.probe(name, endpoint, time.monotonic() + 5)
Q.ready_connection(conn, 3600.0, False, False)
root = sys.argv[1]
head, body = conn.command("query_open", {"root": root, "limit": 0})
assert head == "OK", body
print("  first open:  %s ms, %s theories, %s entries, %s files fingerprinted"
      % (body.get("build_ms"), body.get("theories"), body.get("entries"),
         body.get("files_checked")))
best = None
for _ in range(5):
    head, body = conn.command("query_open", {"root": root, "limit": 0})
    assert head == "OK", body
    ms = body["check_ms"]
    best = ms if best is None else min(best, ms)
print("  recheck:     %s ms (best of 5), %s theories reparsed"
      % (best, body.get("reparsed")))
conn.close()
PY
  echo
fi

if [ "$TIER" = "full" ] || [ "$TIER" = "all" ] || [ "$TIER" = "afp" ]; then
  CASE_PREFIX=afp
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

  CASE_PREFIX=heavy
  echo "## (e) heavy -- Analysis, AutoCorres2, JinjaThreads"
  echo
  echo "Hot subjects: has_integral, refines, wf_prog; counts depend on the corpus."
  echo "Oracle differences are retained for review, including D15 theory-leaf"
  echo "naming. No disagreement is silently accepted as a timing result."
  echo
  row "invocation" "oracle ms" "cold ms" "warm ms"
  printf '|%s|%s|%s|%s|\n' "-------------------------------------------" \
    "-----------:" "-----------:" "-----------:"
  bench3 "Analysis: summary" "$ANA" summary
  bench3 "Analysis: callers has_integral" "$ANA" callers has_integral
  bench3 "Analysis: shape summary" "$ANA" shape summary
  bench3 "AutoCorres2: callers refines" "$AC2" callers refines
  bench3 "JinjaThreads: summary" "$JT" summary
  bench3 "JinjaThreads: callers wf_prog" "$JT" callers wf_prog
  echo
fi

if [ "$TIER" = "decline" ] || [ "$TIER" = "delegate" ] || [ "$TIER" = "all" ]; then
  CASE_PREFIX=decline
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
    ensure_index "$root"
    local c w d
    c=$(time_ms "$label-dcold" isabelle query --no-server -R "$root" "$@")
    w=$(time_ms "$label-dwarm" python3 "$CLIENT" --client-limit 0 -R "$root" "$@")
    d=$(time_ms "$label-decl" isabelle query --client-cold -R "$root" "$@")
    row "$label" "$c" "$w" "$d"
    same "$label-dcold" "$label-dwarm" >/dev/null
    # The whole claim of this route is byte identity with the cold tool.  A row
    # whose columns disagree is not a timing, it is a bug report.
    [ "$(same "$label-dcold" "$label-decl")" = "=" ] ||
      row "  ^ DECLINED ANSWER DIFFERS FROM COLD" "" "" ""
  }

  bench_decline "show fair_fenum" "$TINY" show fair_fenum
  bench_decline "summary src-HOL" "$HOL" summary
  bench_decline "instances comm_monoid src-HOL" "$HOL" instances comm_monoid
  echo
fi

# Each timed invocation starts with an absent explicit selection. Explicit
# discovery only looks up that exact name, so it cannot reuse the warm host.
# UUID generation/verification stay outside the invocation timer.
auto_sample() {
  local label="$1" sample="$2" root="$3" name rc=0
  name="auto-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
  ISABELLE_QUERY_CLIENT_SERVER="$name" \
    capture "$label" "$sample" isabelle query -R "$root" summary
  rc="$CAPTURE_FAILED"
  # Verify the client removed its endpoint even if the query failed.
  ISABELLE_QUERY_CLIENT_SERVER="$name" python3 - "$CLIENT" <<'PY_AUTO' || rc=1
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location("bench_client", sys.argv[1])
Q = importlib.util.module_from_spec(spec)
spec.loader.exec_module(Q)
cached = Q.read_cache()
if Q.registry_lookup(Q.find_isabelle(cached), cached, os.environ["ISABELLE_QUERY_CLIENT_SERVER"]):
    raise SystemExit("bench: auto fallback left its registry endpoint behind")
PY_AUTO
  if [ "$rc" -ne 0 ]; then
    echo "auto sample failed or leaked endpoint: $label / $sample" >>"$OUT/failures.txt"
  fi
  AUTO_FAILED="$rc"
}
bench_auto() {
  local label="$1" root="$2" cold sample failed=0 times=() auto_label auto_ms
  cold=$(time_ms "$label-cold" isabelle query --no-server -R "$root" summary)
  auto_label=$(safe_name "$label-auto")
  auto_sample "$auto_label" warmup "$root"
  [ "$AUTO_FAILED" -eq 0 ] || failed=1
  for ((sample = 0; sample < RUNS; sample++)); do
    auto_sample "$auto_label" "$sample" "$root"
    [ "$AUTO_FAILED" -eq 0 ] || failed=1
    times+=( "$CAPTURE_MS" )
  done
  auto_ms=FAILED
  [ "$failed" -eq 0 ] && auto_ms=$(median "${times[@]}")
  printf '| %-30s | %10s | %10s |\n' "$label" "$cold" "$auto_ms"
  same "$label-cold" "$label-auto" >/dev/null
}
if [ "$TIER" = "auto" ] || [ "$TIER" = "all" ]; then
  CASE_PREFIX=auto
  echo "## owned auto fallback -- absent explicit name for each invocation"
  echo "Includes client discovery, launcher startup, query, and owned teardown."
  echo "Uses the private home; does not reuse the manually retained warm host."
  printf '| %-30s | %10s | %10s |\n' "summary" "cold ms" "auto ms"
  printf '|%s|%s|%s|\n' "-------------------------------" "-----------:" "-----------:"
  bench_auto "tiny summary" "$TINY"
  bench_auto "HOL summary" "$HOL"
  echo
fi

rss_mb() {
  if [[ "$1" =~ ^[0-9]+$ ]]; then echo "$(( $1 / 1024 ))"; else echo FAILED; fi
}

if [ "$TIER" = "memory" ] || [ "$TIER" = "all" ]; then
  CASE_PREFIX=memory
  echo "## memory -- peak RSS"
  echo "Per-invocation deadline: $BENCH_MEMORY_TIMEOUT seconds; timeout is FAILED (exit 124)."
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
    if [ "$(same "$label-mem-stock" "$label-mem-512")" != "=" ]; then
      printf '| %-42s | %14s | %14s |\n' "$label  [ANSWERS DIFFER]" \
        "$(rss_mb "$a")" "$(rss_mb "$b")"
    else
      printf '| %-42s | %14s | %14s |\n' "$label" "$(rss_mb "$a")" "$(rss_mb "$b")"
    fi
  }

  mem_row "summary src-HOL" "$HOL" summary
  mem_row "callers comp_assoc Category3" "$MED" callers comp_assoc
  mem_row "summary --by-session whole-AFP" "$AFP" summary --by-session
  echo
  echo "Python oracle, for scale:"
  a=$(peak_kb "oracle-mem-hol" query -R "$HOL" summary)
  printf '  query -R src/HOL summary: %s MB\n' "$(rss_mb "$a")"
  echo
fi

if [ -s "$OUT/failures.txt" ]; then
  cat "$OUT/failures.txt" >&2
  exit 1
fi
echo "comparisons: all selected stdout pairs agree; every captured invocation exited 0"
echo "All selected cases passed; owned server cleanup follows."
