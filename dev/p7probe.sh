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
export ISABELLE_QUERY_CLIENT_SERVER="$SERVER"
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-cache.json"

fail=0
checks=0
note() { checks=$((checks + 1)); echo "  ok    $1${2:+  [$2]}"; }
bad()  { checks=$((checks + 1)); fail=$((fail + 1)); echo "  FAIL  $1  [$2]"; }

cleanup() {
  isabelle server -x -n "$SERVER" >/dev/null 2>&1
  # Belt to that brace: a server killed with -9 leaves no socket for `-x` to
  # talk to, so match on the command line as well.
  pkill -f "isabelle_server.*$SERVER" >/dev/null 2>&1
  pkill -f "server -n $SERVER" >/dev/null 2>&1
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
echo "15. nothing is left running"

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

echo
printf '%d checks: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p7probe: FAILURES" >&2
  exit 1
fi
echo "P7PROBE SERVER OK"
exit 0
