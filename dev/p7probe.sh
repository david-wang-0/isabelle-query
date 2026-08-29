#!/usr/bin/env bash
#
# dev/p7probe.sh -- headless checks for the warm server and its thin client.
#
# Sibling of dev/p5probe.sh, dev/p6probe.sh and dev/p6bprobe.sh, which stay as
# they are.  This one covers what P7 added: the four server commands, the
# client's fast path, and every way both are supposed to refuse or fall back.
#
# Lifecycle is this script's responsibility and it is the part worth reading.
# The server runs under a name nobody else uses (p7probe-$$) inside the scratch
# Isabelle user home, so a developer's own `isabelle_query` server is never
# touched, and a trap stops it however the run ends -- including the failure
# paths, which is where a probe usually leaks a process.
#
# Usage:
#   dev/p7probe.sh [AFP_ENTRY] [ZF_CORPUS]
#
# Defaults come from $QUERY_TEST_AFP / $QUERY_TEST_DISTRO, the variables
# dev/difftest.sh uses; no path is hard-coded.  The scratch Isabelle user home
# is $USER_HOME, defaulting to the repository's own .dev -- never the real one.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

AFP="${1:-${QUERY_TEST_AFP:-}/Abstract_Completeness}"
ZF="${2:-${QUERY_TEST_DISTRO:-}/ZF}"

# Refuse rather than skip: an OK that never looked at a corpus is a false green.
missing=""
[ -d "$AFP" ] || missing="$missing  AFP:   $AFP (\$QUERY_TEST_AFP/Abstract_Completeness)"$'\n'
[ -d "$ZF" ]  || missing="$missing  ZF:    $ZF (\$QUERY_TEST_DISTRO/ZF)"$'\n'
if [ -n "$missing" ]; then
  echo "p7probe: the corpora these checks need are not here:" >&2
  printf '%s' "$missing" >&2
  echo "usage: dev/p7probe.sh [AFP_ENTRY] [ZF_CORPUS]  (or set the variables)" >&2
  exit 2
fi

OUT="$REPO/.dev/p7probe-out"
rm -rf "$OUT"
mkdir -p "$OUT" || exit 2

CLIENT="$REPO/query_base/lib/scripts/query_client.py"
JAR="$REPO/query_base/lib/classes/isabelle_query.jar"

# A probe-private server name and a probe-private settings cache: nothing here
# may reach the developer's own server or their real cache file.
SERVER="p7probe-$$"
# A SECOND server, started deliberately under a pinned environment (§9b).  It
# has to be a separate process: the whole question is what a server inherits at
# start-up, which cannot be asked of one that is already running.
ENV_SERVER="p7probe-env-$$"
# P7b: the auto-delegating CLI's own servers.  Separate names because §15 asks
# what a COLD `isabelle query` does when it finds, or fails to find, a server
# -- a question the thin client's server would keep answering for it.
DELEG_SERVER="p7bprobe-$$"
DELEG_ENV_SERVER="p7bprobe-env-$$"
DELEG_DEAD_SERVER="p7bprobe-dead-$$"
export ISABELLE_QUERY_CLIENT_SERVER="$SERVER"
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-cache.json"

# Sections 0-14 are about the SERVER and the THIN CLIENT, and every `isabelle
# query` in them is the cold reference the served answer is compared against.
# Since P7b a bare `isabelle query` would delegate -- to this probe's own
# server -- and every one of those comparisons would quietly become warm
# against warm.  §15 turns it back on, deliberately and per invocation.
export ISABELLE_QUERY_NO_SERVER=1

fail=0
checks=0
note() { checks=$((checks + 1)); echo "  ok    $1${2:+  [$2]}"; }
bad()  { checks=$((checks + 1)); fail=$((fail + 1)); echo "  FAIL  $1  [$2]"; }

cleanup() {
  for s in "$SERVER" "$ENV_SERVER" "$DELEG_SERVER" "$DELEG_ENV_SERVER" \
           "$DELEG_DEAD_SERVER"; do
    isabelle server -x -n "$s" >/dev/null 2>&1
    # Belt to that brace: a server killed with -9 leaves no socket for `-x` to
    # talk to, so match on the command line as well.
    pkill -f "isabelle_server.*$s" >/dev/null 2>&1
    pkill -f "server -n $s" >/dev/null 2>&1
  done
}
trap cleanup EXIT INT TERM

echo "afp corpus: $AFP"
echo "zf corpus:  $ZF"
echo "server:     $SERVER"
echo

isabelle scala_build || exit $?
[ -f "$JAR" ] || { echo "p7probe: no jar at $JAR" >&2; exit 1; }

# --------------------------------------------------------------------------
echo "0. the server starts, and it is the one we asked for"

python3 "$CLIENT" --client-status >"$OUT/status.txt" 2>&1
if grep -q "^server        $SERVER" "$OUT/status.txt"; then
  note "the client started a server under its own name"
else
  bad "the client started a server under its own name" "$(head -2 "$OUT/status.txt" | tr '\n' ' ')"
fi

if isabelle server -l 2>/dev/null | grep -q "\"$SERVER\""; then
  note "and the registry knows it"
else
  bad "and the registry knows it" "$(isabelle server -l 2>&1 | tr '\n' ' ')"
fi

if grep -q "STALE" "$OUT/status.txt"; then
  bad "a freshly started server is not stale" "$(grep component_id "$OUT/status.txt")"
else
  note "a freshly started server is not stale"
fi

# --------------------------------------------------------------------------
echo
python3 "$REPO/dev/p7probe.py" "$SERVER" "$AFP" "$ZF" "$OUT" \
  >"$OUT/protocol.log" 2>&1
proto_rc=$?
cat "$OUT/protocol.log"
proto_checks=$(grep -c '^  ok\|^  FAIL' "$OUT/protocol.log")
checks=$((checks + proto_checks))
proto_fails=$(grep -c '^  FAIL' "$OUT/protocol.log")
fail=$((fail + proto_fails))
if [ "$proto_rc" -ne 0 ] && [ "$proto_fails" = "0" ]; then
  bad "the protocol layer exited cleanly" "exit $proto_rc with no FAIL line"
fi

# --------------------------------------------------------------------------
echo
echo "9. the client, end to end"

isabelle query -R "$AFP" summary >"$OUT/cold-summary.txt" 2>"$OUT/cold-summary.err"
cold_rc=$?
python3 "$CLIENT" -R "$AFP" summary >"$OUT/warm-summary.txt" 2>"$OUT/warm-summary.err"
warm_rc=$?
if cmp -s "$OUT/cold-summary.txt" "$OUT/warm-summary.txt" && [ "$cold_rc" = "$warm_rc" ]; then
  note "warm client stdout and exit equal the cold tool's" "exit $warm_rc"
else
  bad "warm client stdout and exit equal the cold tool's" "$cold_rc vs $warm_rc"
fi

# The tool's own -R must survive the client's path rewriting, from a working
# directory that is NOT the project.
( cd "$OUT" && python3 "$CLIENT" -R "$AFP" theory --names ) >"$OUT/warm-rel.txt" 2>&1
isabelle query -R "$AFP" theory --names >"$OUT/cold-rel.txt" 2>&1
if cmp -s "$OUT/cold-rel.txt" "$OUT/warm-rel.txt"; then
  note "and an absolute -R from an unrelated cwd resolves the same"
else
  bad "and an absolute -R from an unrelated cwd resolves the same" "differs"
fi

# No -R at all: the root comes from the CLIENT's working directory, resolved
# by the CLI's own rule inside the server.  This is the check that would catch
# a served run silently using the server's cwd instead.
( cd "$AFP" && python3 "$CLIENT" summary ) >"$OUT/warm-cwd.txt" 2>&1
if cmp -s "$OUT/cold-summary.txt" "$OUT/warm-cwd.txt"; then
  note "a bare invocation resolves the CLIENT's cwd, not the server's"
else
  bad "a bare invocation resolves the CLIENT's cwd, not the server's" \
    "$(head -1 "$OUT/warm-cwd.txt")"
fi

# A relative path argument is the other half of the same question.
( cd "$(dirname "$AFP")" && python3 "$CLIENT" -R "$(basename "$AFP")" summary ) \
  >"$OUT/warm-relroot.txt" 2>&1
if cmp -s "$OUT/cold-summary.txt" "$OUT/warm-relroot.txt"; then
  note "and a RELATIVE -R resolves against the client's cwd"
else
  bad "and a RELATIVE -R resolves against the client's cwd" \
    "$(head -1 "$OUT/warm-relroot.txt")"
fi

# --------------------------------------------------------------------------
echo
echo "9b. the environment is per REQUEST, not per server process"

# THE DEFECT THIS SECTION EXISTS FOR.  `isabelle server` inherits the
# environment of whichever client happened to start it, and keeps it for life.
# A server first reached by a client with $ISABELLE_QUERY_NAMESPACE=committed
# therefore answered EVERY later client -- with a clean environment and
# identical argv -- as though they had pinned it too: on ZF, `callers induct`
# came back 1 instead of 250, because under the committed HOL table `induct` is
# a method rather than a citation.
#
# It has to be a SECOND server, started deliberately under the pin: the
# question is what a process inherits at start-up, which cannot be asked of one
# already running.

isabelle query -R "$ZF" callers induct >"$OUT/zf-plain.txt" 2>/dev/null
ISABELLE_QUERY_NAMESPACE=committed isabelle query -R "$ZF" callers induct \
  >"$OUT/zf-pinned.txt" 2>/dev/null

# Non-vacuity first: if the pin made no difference to the cold tool, the two
# checks below would pass whatever the server did.
if cmp -s "$OUT/zf-plain.txt" "$OUT/zf-pinned.txt"; then
  bad "the pin changes the COLD answer (else this section proves nothing)" \
    "identical: $(head -1 "$OUT/zf-plain.txt")"
else
  note "the pin changes the COLD answer (else this section proves nothing)" \
    "$(head -1 "$OUT/zf-plain.txt") vs $(head -1 "$OUT/zf-pinned.txt")"
fi

# Start the second server FROM a pinned client, so it inherits the pin.
ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" ISABELLE_QUERY_NAMESPACE=committed \
  python3 "$CLIENT" --client-status >"$OUT/env-status.txt" 2>&1
if grep -q "^server        $ENV_SERVER" "$OUT/env-status.txt"; then
  note "a server started under the pinned environment is up" "$ENV_SERVER"
else
  bad "a server started under the pinned environment is up" \
    "$(head -2 "$OUT/env-status.txt" | tr '\n' ' ')"
fi

# The check: a LATER client with a clean environment must get the clean answer.
# stdout only, as every other parity check compares: the step-down to the Pure
# floor prints a NOTE on stderr, and folding it in would make the warm file
# differ from the cold one for the very reason the check is looking for.
ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" \
  python3 "$CLIENT" -R "$ZF" callers induct \
  >"$OUT/env-plain.txt" 2>"$OUT/env-plain.err"
if cmp -s "$OUT/zf-plain.txt" "$OUT/env-plain.txt" &&
   grep -q "minimal Pure table" "$OUT/env-plain.err"; then
  note "an unpinned client gets the unpinned answer from a pinned server" \
    "$(head -1 "$OUT/env-plain.txt"), and the step-down note reaches it"
else
  bad "an unpinned client gets the unpinned answer from a pinned server" \
    "$(head -1 "$OUT/env-plain.txt")"
fi

# And the other half: the variable is FORWARDED, not merely ignored, so a
# client that does set it still gets what it asked for.
ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" ISABELLE_QUERY_NAMESPACE=committed \
  python3 "$CLIENT" -R "$ZF" callers induct \
  >"$OUT/env-pinned.txt" 2>"$OUT/env-pinned.err"
if cmp -s "$OUT/zf-pinned.txt" "$OUT/env-pinned.txt"; then
  note "and a pinned client still gets the pinned one (forwarded, not ignored)" \
    "$(head -1 "$OUT/env-pinned.txt")"
else
  bad "and a pinned client still gets the pinned one (forwarded, not ignored)" \
    "$(head -1 "$OUT/env-pinned.txt")"
fi

# The root variables travel the same way, and this is what proves the request
# carries them rather than the server reading its own: no -R, and a cwd that is
# NOT the project.
( cd "$OUT" && ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" ISABELLE_QUERY_ROOT="$ZF" \
    python3 "$CLIENT" callers induct ) >"$OUT/env-root.txt" 2>"$OUT/env-root.err"
if cmp -s "$OUT/zf-plain.txt" "$OUT/env-root.txt"; then
  note "\$ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd"
else
  bad "\$ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd" \
    "$(head -1 "$OUT/env-root.txt")"
fi

ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" python3 "$CLIENT" --client-stop \
  >"$OUT/env-stop.txt" 2>&1
sleep 0.3
if [ "$(pgrep -cf "server -n $ENV_SERVER" 2>/dev/null)" = "0" ]; then
  note "and the pinned server is stopped again"
else
  bad "and the pinned server is stopped again" "still running"
fi

# --------------------------------------------------------------------------
echo
echo "10. the refusal reaches the user, and is not an empty answer"

python3 "$CLIENT" --client-limit 1 -R "$AFP" summary \
  >"$OUT/capped.txt" 2>"$OUT/capped.err"
cap_rc=$?
if [ "$cap_rc" = "2" ] && [ ! -s "$OUT/capped.txt" ] &&
   grep -q "too large for a resident index" "$OUT/capped.err"; then
  note "over the cap: exit 2, empty stdout, a diagnostic on stderr"
else
  bad "over the cap: exit 2, empty stdout, a diagnostic on stderr" \
    "exit $cap_rc, $(wc -c <"$OUT/capped.txt") bytes out"
fi

# --------------------------------------------------------------------------
echo
echo "11. cold routing -- what must never go over the socket"

python3 "$CLIENT" --client-verbose --client-cold -R "$AFP" summary \
  >"$OUT/forced-cold.txt" 2>"$OUT/forced-cold.err"
if cmp -s "$OUT/cold-summary.txt" "$OUT/forced-cold.txt" &&
   grep -q "cold path" "$OUT/forced-cold.err"; then
  note "--client-cold takes the cold path and answers the same"
else
  bad "--client-cold takes the cold path and answers the same" \
    "$(tr '\n' ' ' <"$OUT/forced-cold.err")"
fi

python3 "$CLIENT" --client-verbose dump-theories "$AFP" \
  >"$OUT/dump.txt" 2>"$OUT/dump.err"
isabelle query dump-theories "$AFP" >"$OUT/dump-cold.txt" 2>/dev/null
if cmp -s "$OUT/dump.txt" "$OUT/dump-cold.txt" && [ -s "$OUT/dump.txt" ] &&
   grep -q "cold path" "$OUT/dump.err"; then
  note "a development dump routes cold (it writes past the capture)" \
    "$(wc -l <"$OUT/dump.txt") theories"
else
  bad "a development dump routes cold (it writes past the capture)" \
    "$(tr '\n' ' ' <"$OUT/dump.err")"
fi

printf 'theory P7 imports Main begin\nlemma p7_stdin: "True" by simp\nend\n' \
  >"$OUT/stdin.thy"
python3 "$CLIENT" --client-verbose grep p7_stdin - <"$OUT/stdin.thy" \
  >"$OUT/stdin.txt" 2>"$OUT/stdin.err"
if grep -q "cold path" "$OUT/stdin.err" && grep -q "p7_stdin" "$OUT/stdin.txt"; then
  note "a '-' argument routes cold (the server cannot read our stdin)"
else
  bad "a '-' argument routes cold (the server cannot read our stdin)" \
    "$(tr '\n' ' ' <"$OUT/stdin.err")"
fi

# --------------------------------------------------------------------------
echo
echo "12. staleness -- a rebuilt component is not answered from the old one"

# `touch` is the whole of what a rebuild changes as far as the stamp is
# concerned (mtime, and size if the code moved), and it is deterministic where
# a real rebuild is 30 seconds and a mtime race.  dev/P7-STATUS.md records the
# real-rebuild transcript.
before=$(grep '^component_id' "$OUT/status.txt" | awk '{print $2}')
touch "$JAR"
python3 "$CLIENT" --client-verbose -R "$AFP" summary \
  >"$OUT/stale.txt" 2>"$OUT/stale.err"
if grep -q "component rebuilt under the server" "$OUT/stale.err"; then
  note "a changed jar is detected and the server restarted"
else
  bad "a changed jar is detected and the server restarted" \
    "$(tr '\n' ' ' <"$OUT/stale.err")"
fi
if cmp -s "$OUT/cold-summary.txt" "$OUT/stale.txt"; then
  note "and the answer after the restart is still right"
else
  bad "and the answer after the restart is still right" "differs"
fi
python3 "$CLIENT" --client-status >"$OUT/status2.txt" 2>&1
after=$(grep '^component_id' "$OUT/status2.txt" | awk '{print $2}')
if [ "$before" != "$after" ] && ! grep -q STALE "$OUT/status2.txt"; then
  note "the new server carries the new stamp" "$before -> $after"
else
  bad "the new server carries the new stamp" "$before -> $after"
fi

# --------------------------------------------------------------------------
echo
echo "13. failure falls back, and the answer is still right"

# A server killed outright leaves a registry row pointing at nothing.  The
# client must notice, start a replacement and answer -- never hang on the dead
# port, never report an empty result.
pkill -9 -f "server -n $SERVER" >/dev/null 2>&1
sleep 0.3
start=$(date +%s%N)
python3 "$CLIENT" --client-verbose -R "$AFP" summary \
  >"$OUT/killed.txt" 2>"$OUT/killed.err"
elapsed=$(( ($(date +%s%N) - start) / 1000000 ))
if cmp -s "$OUT/cold-summary.txt" "$OUT/killed.txt"; then
  note "after the server is killed mid-session the answer is still right" \
    "${elapsed} ms, $(tr '\n' ' ' <"$OUT/killed.err" | cut -c1-48)"
else
  bad "after the server is killed mid-session the answer is still right" "differs"
fi

# And the true fallback: a client that cannot START a server must run the tool
# cold rather than fail.  The shim refuses exactly the `server` subcommand and
# forwards everything else, so the cold path is exercised for real.
SHIM="$OUT/isabelle-noserver"
{
  echo '#!/usr/bin/env bash'
  echo 'if [ "${1:-}" = "server" ]; then echo "p7probe: no server for you" >&2; exit 1; fi'
  echo "exec isabelle \"\$@\""
} >"$SHIM"
chmod +x "$SHIM"
ISABELLE_TOOL="$SHIM" ISABELLE_QUERY_CLIENT_SERVER="p7probe-absent-$$" \
  python3 "$CLIENT" --client-verbose -R "$AFP" summary \
  >"$OUT/nofallback.txt" 2>"$OUT/nofallback.err"
if cmp -s "$OUT/cold-summary.txt" "$OUT/nofallback.txt" &&
   grep -q "falling back" "$OUT/nofallback.err"; then
  note "a client that cannot start a server falls back to the cold tool" \
    "$(grep 'falling back' "$OUT/nofallback.err" | cut -c1-56)"
else
  bad "a client that cannot start a server falls back to the cold tool" \
    "$(tr '\n' ' ' <"$OUT/nofallback.err" | cut -c1-90)"
fi

# --------------------------------------------------------------------------
echo
echo "14. failability -- the harness must be able to say no"

P7PROBE_FAILDEMO=1 python3 "$REPO/dev/p7probe.py" "$SERVER" "$AFP" "$ZF" "$OUT" \
  >"$OUT/faildemo.log" 2>&1
demo_rc=$?
demo_fails=$(grep -c '^  FAIL' "$OUT/faildemo.log")
if [ "$demo_rc" -ne 0 ] && [ "$demo_fails" = "1" ]; then
  note "one perturbed expectation gives a FAIL and a non-zero exit" \
    "exit $demo_rc, $demo_fails failure"
else
  bad "one perturbed expectation gives a FAIL and a non-zero exit" \
    "exit $demo_rc, $demo_fails failures"
fi

# --------------------------------------------------------------------------
echo
echo "15. the cold CLI delegates by itself (P7b)"

# Everything above asked what the SERVER and the THIN CLIENT do.  This asks
# what a plain `isabelle query` does now that it looks for that server itself:
# it must find one, start one when there is none, notice a rebuilt component,
# forward the request's environment, keep its hands off the invocations that
# cannot be served, and — above all — hand back the same bytes and the same
# exit status the cold tool would have.
#
# `$ISABELLE_QUERY_NO_SERVER=1` is exported for the whole script, so the cold
# side of every comparison below is genuinely cold and delegation happens only
# where these two helpers turn it back on.

deleg() {
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DELEG_SERVER" \
    isabelle query "$@"
}
delegv() {
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DELEG_SERVER" \
    ISABELLE_QUERY_SERVER_VERBOSE=1 isabelle query "$@"
}
port_of() {  # the registry's port for a server name, or nothing
  isabelle server -l 2>/dev/null | sed -n "s/^server \"$1\" = [^:]*:\([0-9]*\) .*/\1/p"
}

# --- 15a. spawn when absent ------------------------------------------------

isabelle server -x -n "$DELEG_SERVER" >/dev/null 2>&1
delegv -R "$AFP" summary >"$OUT/d-spawn.txt" 2>"$OUT/d-spawn.err"
d_port=$(port_of "$DELEG_SERVER")
if grep -q "no server; starting one" "$OUT/d-spawn.err" && [ -n "$d_port" ]; then
  note "a cold CLI with no server starts one, under the shared name" \
    "$DELEG_SERVER on $d_port"
else
  bad "a cold CLI with no server starts one, under the shared name" \
    "$(tr '\n' ' ' <"$OUT/d-spawn.err" | cut -c1-70)"
fi
if cmp -s "$OUT/cold-summary.txt" "$OUT/d-spawn.txt"; then
  note "and that first, spawning invocation already answers correctly"
else
  bad "and that first, spawning invocation already answers correctly" "differs"
fi

# --- 15b. detect an existing one -------------------------------------------

delegv -R "$AFP" summary >"$OUT/d-reuse.txt" 2>"$OUT/d-reuse.err"
if grep -q "^query-delegate: delegated" "$OUT/d-reuse.err" &&
   ! grep -q "starting" "$OUT/d-reuse.err" &&
   [ "$(port_of "$DELEG_SERVER")" = "$d_port" ]; then
  note "the next invocation finds THAT server and starts nothing" \
    "$(grep 'registry\|connect\|query_run' "$OUT/d-reuse.err" | tr '\n' ' ')"
else
  bad "the next invocation finds THAT server and starts nothing" \
    "$(tr '\n' ' ' <"$OUT/d-reuse.err" | cut -c1-70)"
fi

# --- 15c. byte and exit identity, over a spread of verbs -------------------

# The claim this whole mode is judged on.  A spread of invocations chosen to cross
# every family (structure, usage, shape, the two site verbs) and to include the
# three exit statuses: 0, an unresolved subject (1) and a usage error (2).
# stdout, stderr and the status are all compared, byte for byte.
deleg_cases=(
  "summary"
  "theory --names"
  "defs Abstract_Completeness"
  "outline Abstract_Completeness"
  "enclosing Abstract_Completeness:100"
  "largest -N 5"
  "find fair -a"
  "show fair_fenum -V"
  "grep mono"
  "sorry"
  "lines Abstract_Completeness 1..12"
  "deps Abstract_Completeness"
  "uses Propositional_Logic"
  "refs Abstract_Completeness"
  "callers mono"
  "callees fair_fenum"
  "unused"
  "methods --names"
  "instances stream"
  "codeqs fair_fenum"
  "shape summary"
  "shape steps -N 3"
  "show no_such_entry_xyz"
  "no-such-command"
)
d_ok=0
d_bad=""
d_nonempty=0
for spec in "${deleg_cases[@]}"; do
  read -r -a d_argv <<<"$spec"
  d_id=$(printf '%s' "$spec" | tr -c 'A-Za-z0-9._-' '_')
  isabelle query -R "$AFP" "${d_argv[@]}" \
    >"$OUT/d-cold-$d_id.out" 2>"$OUT/d-cold-$d_id.err"
  c_rc=$?
  deleg -R "$AFP" "${d_argv[@]}" \
    >"$OUT/d-warm-$d_id.out" 2>"$OUT/d-warm-$d_id.err"
  w_rc=$?
  [ -s "$OUT/d-cold-$d_id.out" ] && d_nonempty=$((d_nonempty + 1))
  if cmp -s "$OUT/d-cold-$d_id.out" "$OUT/d-warm-$d_id.out" &&
     cmp -s "$OUT/d-cold-$d_id.err" "$OUT/d-warm-$d_id.err" &&
     [ "$c_rc" = "$w_rc" ]; then
    d_ok=$((d_ok + 1))
  else
    d_bad="$d_bad $spec($c_rc/$w_rc)"
  fi
done
if [ "$d_ok" = "${#deleg_cases[@]}" ]; then
  note "${#deleg_cases[@]} invocations: identical stdout, stderr and exit" \
    "$d_nonempty with non-empty stdout"
else
  bad "${#deleg_cases[@]} invocations: identical stdout, stderr and exit" \
    "$((${#deleg_cases[@]} - d_ok)) differ:$d_bad"
fi

# Failability, right here rather than in a separate demo: the comparison above
# is only worth anything if it can say no, and if it was not comparing empty
# files.  Perturb one byte of one captured answer and re-run the same `cmp`.
cp "$OUT/d-warm-summary.out" "$OUT/d-perturbed.out"
printf 'x' >>"$OUT/d-perturbed.out"
if [ "$d_nonempty" -ge 15 ] &&
   ! cmp -s "$OUT/d-cold-summary.out" "$OUT/d-perturbed.out"; then
  note "and the comparison can say no (one byte added is caught)" \
    "$d_nonempty of ${#deleg_cases[@]} answers non-empty"
else
  bad "and the comparison can say no (one byte added is caught)" \
    "$d_nonempty non-empty"
fi

# --- 15d. SIGPIPE survives the delegated path ------------------------------

# A delegated answer arrives whole and is then written out, so the closed pipe
# is met by OUR write, not by the engine's.  141 is what a shell reports for a
# process killed by SIGPIPE and what the cold tool gives, and the two must
# agree.  `.*` rather than `.` as the pattern, because `.` names a directory
# and would take the invocation off the warm path entirely -- which is 15j.

# (i) a reader that is already gone when the first byte is written
deleg -R "$AFP" find '.*' -a 2>/dev/null | head -n 0 >/dev/null
d_pipe0=${PIPESTATUS[0]}
isabelle query -R "$AFP" find '.*' -a 2>/dev/null | head -n 0 >/dev/null
c_pipe0=${PIPESTATUS[0]}
if [ "$d_pipe0" = "141" ] && [ "$c_pipe0" = "141" ]; then
  note "a downstream that has already gone is 141, delegated as cold" \
    "$c_pipe0 / $d_pipe0"
else
  bad "a downstream that has already gone is 141, delegated as cold" \
    "cold $c_pipe0, delegated $d_pipe0"
fi

# (ii) a reader that leaves after three lines of a two-megabyte answer -- far
# past the pipe's own capacity, so the writer is still writing when it goes.
deleg -R "$ZF" find '.*' -a 2>/dev/null | head -3 >"$OUT/d-pipe.txt"
d_pipe_rc=${PIPESTATUS[0]}
isabelle query -R "$ZF" find '.*' -a 2>/dev/null | head -3 >"$OUT/d-pipe-cold.txt"
c_pipe_rc=${PIPESTATUS[0]}
if [ "$d_pipe_rc" = "$c_pipe_rc" ] && [ "$d_pipe_rc" = "141" ] &&
   cmp -s "$OUT/d-pipe.txt" "$OUT/d-pipe-cold.txt"; then
  note "output piped into \`head -3\` exits 141, as cold" "$c_pipe_rc / $d_pipe_rc"
else
  bad "output piped into \`head -3\` exits 141, as cold" \
    "cold $c_pipe_rc, delegated $d_pipe_rc"
fi

# --- 15e. staleness: a rebuilt component under a running server ------------

touch "$JAR"
delegv -R "$AFP" summary >"$OUT/d-stale.txt" 2>"$OUT/d-stale.err"
d_port2=$(port_of "$DELEG_SERVER")
if grep -q "component rebuilt under the server; restarting" "$OUT/d-stale.err"; then
  note "a changed jar is detected and the server shut down and replaced"
else
  bad "a changed jar is detected and the server shut down and replaced" \
    "$(tr '\n' ' ' <"$OUT/d-stale.err" | cut -c1-70)"
fi
if [ -n "$d_port2" ] && [ "$d_port2" != "$d_port" ]; then
  note "and the registry row is the NEW server's, not the dead one's" \
    "$d_port -> $d_port2"
else
  bad "and the registry row is the NEW server's, not the dead one's" \
    "$d_port -> ${d_port2:-none}"
fi
if cmp -s "$OUT/cold-summary.txt" "$OUT/d-stale.txt"; then
  note "and the answer across the restart is still the cold one"
else
  bad "and the answer across the restart is still the cold one" "differs"
fi

# --- 15f. the request's environment, through the DELEGATING CLI ------------

# The P6c defect, asked of the third front end.  A server started BY a pinned
# delegating CLI inherits the pin in its process environment; every later
# request must still get its own answer.  The cold reference files are §9b's.

env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DELEG_ENV_SERVER" \
  ISABELLE_QUERY_NAMESPACE=committed \
  isabelle query -R "$ZF" callers induct \
  >"$OUT/d-env-start.txt" 2>"$OUT/d-env-start.err"
if [ -n "$(port_of "$DELEG_ENV_SERVER")" ] &&
   cmp -s "$OUT/zf-pinned.txt" "$OUT/d-env-start.txt"; then
  note "a second server, started by a PINNED delegating CLI, is up" \
    "$DELEG_ENV_SERVER"
else
  bad "a second server, started by a PINNED delegating CLI, is up" \
    "$(head -1 "$OUT/d-env-start.txt")"
fi

env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DELEG_ENV_SERVER" \
  isabelle query -R "$ZF" callers induct \
  >"$OUT/d-env-plain.txt" 2>"$OUT/d-env-plain.err"
if cmp -s "$OUT/zf-plain.txt" "$OUT/d-env-plain.txt" &&
   grep -q "minimal Pure table" "$OUT/d-env-plain.err"; then
  note "an UNPINNED delegating CLI gets the unpinned answer, and the note" \
    "$(head -1 "$OUT/d-env-plain.txt")"
else
  bad "an UNPINNED delegating CLI gets the unpinned answer, and the note" \
    "$(head -1 "$OUT/d-env-plain.txt")"
fi

env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DELEG_ENV_SERVER" \
  ISABELLE_QUERY_NAMESPACE=committed \
  isabelle query -R "$ZF" callers induct \
  >"$OUT/d-env-pinned.txt" 2>"$OUT/d-env-pinned.err"
if cmp -s "$OUT/zf-pinned.txt" "$OUT/d-env-pinned.txt"; then
  note "and a PINNED one still gets the pinned answer (forwarded, not ignored)" \
    "$(head -1 "$OUT/d-env-pinned.txt")"
else
  bad "and a PINNED one still gets the pinned answer (forwarded, not ignored)" \
    "$(head -1 "$OUT/d-env-pinned.txt")"
fi

# The root variables travel the same way: no -R, and a cwd that is not the
# project.  This is what proves the REQUEST carries them.
( cd "$OUT" && env -u ISABELLE_QUERY_NO_SERVER \
    ISABELLE_QUERY_CLIENT_SERVER="$DELEG_ENV_SERVER" ISABELLE_QUERY_ROOT="$ZF" \
    isabelle query callers induct ) >"$OUT/d-env-root.txt" 2>"$OUT/d-env-root.err"
if cmp -s "$OUT/zf-plain.txt" "$OUT/d-env-root.txt"; then
  note "\$ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd"
else
  bad "\$ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd" \
    "$(head -1 "$OUT/d-env-root.txt")"
fi

isabelle server -x -n "$DELEG_ENV_SERVER" >/dev/null 2>&1

# --- 15g. the bypass list actually bypasses --------------------------------

delegv -R "$AFP" shape census >"$OUT/d-census.jsonl" 2>"$OUT/d-census.err"
isabelle query -R "$AFP" shape census >"$OUT/d-census-cold.jsonl" 2>/dev/null
if grep -q "local: shape census" "$OUT/d-census.err" &&
   [ -s "$OUT/d-census.jsonl" ] &&
   cmp -s "$OUT/d-census.jsonl" "$OUT/d-census-cold.jsonl"; then
  note "a census runs here, not over the socket (a 256 MB reply is slower warm)" \
    "$(wc -l <"$OUT/d-census.jsonl") records"
else
  bad "a census runs here, not over the socket (a 256 MB reply is slower warm)" \
    "$(tr '\n' ' ' <"$OUT/d-census.err" | cut -c1-70)"
fi

delegv grep p7_stdin - <"$OUT/stdin.thy" >"$OUT/d-stdin.txt" 2>"$OUT/d-stdin.err"
if grep -q "local: reads stdin" "$OUT/d-stdin.err" &&
   grep -q "p7_stdin" "$OUT/d-stdin.txt"; then
  note "a '-' argument runs here (the server cannot read our stdin)"
else
  bad "a '-' argument runs here (the server cannot read our stdin)" \
    "$(tr '\n' ' ' <"$OUT/d-stdin.err" | cut -c1-70)"
fi

delegv dump-theories "$AFP" >"$OUT/d-dump.txt" 2>"$OUT/d-dump.err"
if grep -q "local: development dump" "$OUT/d-dump.err" &&
   cmp -s "$OUT/d-dump.txt" "$OUT/dump-cold.txt"; then
  note "a development dump runs here (it writes past any capture)" \
    "$(wc -l <"$OUT/d-dump.txt") theories"
else
  bad "a development dump runs here (it writes past any capture)" \
    "$(tr '\n' ' ' <"$OUT/d-dump.err" | cut -c1-70)"
fi

delegv -h >"$OUT/d-help.txt" 2>"$OUT/d-help.err"
if grep -q "local: help or version" "$OUT/d-help.err" &&
   grep -q -- "--no-server" "$OUT/d-help.txt"; then
  note "-h runs here, and documents the flag that turns this off"
else
  bad "-h runs here, and documents the flag that turns this off" \
    "$(tr '\n' ' ' <"$OUT/d-help.err" | cut -c1-70)"
fi

# --- 15j. a token that names something HERE is not a token to guess about --

# THE DEFECT THIS CHECK EXISTS FOR, found by §15 and present in the P7 thin
# client too.  Both front ends used to rewrite every argument that named an
# existing file into an absolute path, "exactly the set the tool would have
# resolved as paths".  It is not that set: `find .` searches for the REGEX `.`,
# and the rewrite turned it into a search for the caller's own directory --
# answering `No entries matching '/home/...'`, which reads exactly like a
# correct empty result.  Whether a positional is a path or a pattern is a fact
# about the command, so neither front end decides it: such an invocation runs
# cold.

delegv -R "$AFP" find . -a >"$OUT/d-dot.txt" 2>"$OUT/d-dot.err"
isabelle query -R "$AFP" find . -a >"$OUT/d-dot-cold.txt" 2>/dev/null
if grep -q "local: relative to this directory" "$OUT/d-dot.err" &&
   cmp -s "$OUT/d-dot.txt" "$OUT/d-dot-cold.txt" && [ -s "$OUT/d-dot.txt" ]; then
  note "\`find .\` runs here and searches for the PATTERN, not for the cwd" \
    "$(head -1 "$OUT/d-dot.txt")"
else
  bad "\`find .\` runs here and searches for the PATTERN, not for the cwd" \
    "$(head -1 "$OUT/d-dot.txt")"
fi

python3 "$CLIENT" --client-verbose -R "$AFP" find . -a \
  >"$OUT/c-dot.txt" 2>"$OUT/c-dot.err"
if grep -q "relative to this directory" "$OUT/c-dot.err" &&
   cmp -s "$OUT/c-dot.txt" "$OUT/d-dot-cold.txt"; then
  note "and the thin client does the same, for the same reason"
else
  bad "and the thin client does the same, for the same reason" \
    "$(tr '\n' ' ' <"$OUT/c-dot.err" | cut -c1-70)"
fi

# The other half: a token that names NOTHING is not ambiguous, and must still
# be served -- otherwise the rule would take every ordinary query off the warm
# path the moment someone ran it from a directory full of theory files.
delegv -R "$AFP" find fair_fenum -a >"$OUT/d-name.txt" 2>"$OUT/d-name.err"
isabelle query -R "$AFP" find fair_fenum -a >"$OUT/d-name-cold.txt" 2>/dev/null
if grep -q "^query-delegate: delegated" "$OUT/d-name.err" &&
   cmp -s "$OUT/d-name.txt" "$OUT/d-name-cold.txt" && [ -s "$OUT/d-name.txt" ]; then
  note "a name that is not a file is still delegated"
else
  bad "a name that is not a file is still delegated" \
    "$(tr '\n' ' ' <"$OUT/d-name.err" | cut -c1-70)"
fi

# --- 15h. the opt-out, and that it starts nothing --------------------------

# A name nothing has ever run under: if the opt-out leaked, the registry would
# gain a row.  That is the check, not the verbose line.
OPTOUT_SERVER="p7bprobe-optout-$$"
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$OPTOUT_SERVER" \
  ISABELLE_QUERY_SERVER_VERBOSE=1 \
  isabelle query --no-server -R "$AFP" summary \
  >"$OUT/d-flag.txt" 2>"$OUT/d-flag.err"
if grep -q "query-delegate: --no-server" "$OUT/d-flag.err" &&
   [ -z "$(port_of "$OPTOUT_SERVER")" ] &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/d-flag.txt"; then
  note "--no-server answers here and starts no server" "$OPTOUT_SERVER absent"
else
  bad "--no-server answers here and starts no server" \
    "$(tr '\n' ' ' <"$OUT/d-flag.err" | cut -c1-70)"
fi

ISABELLE_QUERY_NO_SERVER=1 ISABELLE_QUERY_CLIENT_SERVER="$OPTOUT_SERVER" \
  ISABELLE_QUERY_SERVER_VERBOSE=1 isabelle query -R "$AFP" summary \
  >"$OUT/d-envoff.txt" 2>"$OUT/d-envoff.err"
if grep -q 'ISABELLE_QUERY_NO_SERVER=1' "$OUT/d-envoff.err" &&
   [ -z "$(port_of "$OPTOUT_SERVER")" ] &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/d-envoff.txt"; then
  note "and \$ISABELLE_QUERY_NO_SERVER=1 does the same for a shell"
else
  bad "and \$ISABELLE_QUERY_NO_SERVER=1 does the same for a shell" \
    "$(tr '\n' ' ' <"$OUT/d-envoff.err" | cut -c1-70)"
fi

# --- 15i. every failure ends in the right answer ---------------------------

# A server killed outright leaves a registry row pointing at nothing.  The next
# invocation must notice, replace it, and answer -- never hang on the dead
# port, never report an empty result.
pkill -9 -f "server -n $DELEG_SERVER" >/dev/null 2>&1
sleep 0.3
d_start=$(date +%s%N)
delegv -R "$AFP" summary >"$OUT/d-killed.txt" 2>"$OUT/d-killed.err"
d_elapsed=$(( ($(date +%s%N) - d_start) / 1000000 ))
if cmp -s "$OUT/cold-summary.txt" "$OUT/d-killed.txt"; then
  note "after the server is killed the answer is still right" \
    "${d_elapsed} ms, $(grep -c 'starting' "$OUT/d-killed.err") restart(s)"
else
  bad "after the server is killed the answer is still right" "differs"
fi

# And the true fallback: a registry row that names something which ACCEPTS a
# connection and then says nothing.  That is a protocol failure rather than a
# dead port, so the CLI cannot rescue it by starting a server -- it has to run
# the query itself.  A python listener plus one row in the scratch registry is
# the whole apparatus; both are removed below.
python3 - "$OUT" "$DELEG_DEAD_SERVER" \
  "$(isabelle getenv -b ISABELLE_HOME_USER)/servers.db" <<'PYEOF' &
import os, socket, sqlite3, sys, time
out, name, db = sys.argv[1], sys.argv[2], sys.argv[3]
sock = socket.socket()
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("127.0.0.1", 0))
sock.listen(5)
con = sqlite3.connect(db, timeout=10.0)
con.execute("DELETE FROM isabelle_servers WHERE name = ?", (name,))
con.execute("INSERT INTO isabelle_servers VALUES (?, ?, ?)",
            (name, sock.getsockname()[1], "not-a-password"))
con.commit()
con.close()
with open(os.path.join(out, "deadserver.ready"), "w") as f:
    f.write("%d\n" % sock.getsockname()[1])
deadline = time.time() + 120
sock.settimeout(1.0)
while time.time() < deadline:
    try:
        client, _ = sock.accept()
    except socket.timeout:
        if os.path.exists(os.path.join(out, "deadserver.stop")):
            break
        continue
    client.close()          # accept, then say nothing at all
sock.close()
PYEOF
dead_pid=$!
rm -f "$OUT/deadserver.ready" "$OUT/deadserver.stop"
for _ in $(seq 1 100); do [ -f "$OUT/deadserver.ready" ] && break; sleep 0.1; done
if [ -f "$OUT/deadserver.ready" ]; then
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DELEG_DEAD_SERVER" \
    ISABELLE_QUERY_SERVER_VERBOSE=1 isabelle query -R "$AFP" summary \
    >"$OUT/d-mute.txt" 2>"$OUT/d-mute.err"
  if cmp -s "$OUT/cold-summary.txt" "$OUT/d-mute.txt" &&
     grep -q "falling back" "$OUT/d-mute.err"; then
    note "a server that accepts and says nothing falls back, and answers" \
      "$(grep 'falling back' "$OUT/d-mute.err" | cut -c1-56)"
  else
    bad "a server that accepts and says nothing falls back, and answers" \
      "$(tr '\n' ' ' <"$OUT/d-mute.err" | cut -c1-90)"
  fi
else
  bad "a server that accepts and says nothing falls back, and answers" \
    "the stand-in listener never came up"
fi
touch "$OUT/deadserver.stop"
kill "$dead_pid" >/dev/null 2>&1
wait "$dead_pid" 2>/dev/null

isabelle server -x -n "$DELEG_SERVER" >/dev/null 2>&1

# --------------------------------------------------------------------------
echo
echo "16. nothing is left running"

python3 "$CLIENT" --client-stop >"$OUT/stop.txt" 2>&1
sleep 0.3
left=$(pgrep -f "server -n $SERVER" 2>/dev/null | wc -l)
if [ "$left" = "0" ]; then
  note "the probe's server is stopped"
else
  bad "the probe's server is stopped" "$left process(es) left"
fi
if isabelle server -l 2>/dev/null | grep -q "\"$SERVER\""; then
  bad "and it is out of the registry" "still listed"
else
  note "and it is out of the registry"
fi

# P7b started three more, under three more names.  A probe that leaves a
# resident JVM holding a corpus-sized index behind it is not a clean run.
left_deleg=0
for s in "$DELEG_SERVER" "$DELEG_ENV_SERVER" "$DELEG_DEAD_SERVER"; do
  isabelle server -x -n "$s" >/dev/null 2>&1
  left_deleg=$((left_deleg + $(pgrep -f "server -n $s" 2>/dev/null | wc -l)))
done
sleep 0.3
if [ "$left_deleg" = "0" ] &&
   ! isabelle server -l 2>/dev/null | grep -q "p7bprobe-"; then
  note "and the three delegation servers are stopped and unregistered"
else
  bad "and the three delegation servers are stopped and unregistered" \
    "$left_deleg process(es) left"
fi

echo
printf '%d checks: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p7probe: FAILURES" >&2
  exit 1
fi
echo "P7PROBE SERVER OK"
exit 0
