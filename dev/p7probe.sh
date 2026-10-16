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
# The router itself.  §13 and §16 run it as a script rather than as
# `isabelle query`, because `bin/isabelle` recomputes the component's
# variables and an override from outside would never reach it.
SHIM_TOOL="$REPO/query_base/lib/Tools/query"

# A probe-private server name and a probe-private settings cache: nothing here
# may reach the developer's own server or their real cache file.
SERVER="p7probe-$$"
# A SECOND server, started deliberately under a pinned environment (§9b).  It
# has to be a separate process: the whole question is what a server inherits at
# start-up, which cannot be asked of one that is already running.
ENV_SERVER="p7probe-env-$$"
# §15's own servers.  Separate names because §15 drives the FRONT DOOR --
# shim, client, decline, cold -- and would otherwise be answered by the server
# §0-§14 keep warm for their own comparisons.  DEAD_SERVER is never a server at
# all: it is a registry row pointing at a listener that says nothing (§15e).
SHIM_SERVER="p8probe-$$"
DEAD_SERVER="p8probe-dead-$$"
# §13 names a server it expects NEVER to exist -- the whole check is that the
# client cannot start one.  It is in the cleanup list anyway: the name was
# absent from it until P8, and the one run where §13's stub failed to take
# effect left a resident JVM holding an index behind, which is exactly what
# §19 says a clean run does not do.  A probe must clean up after the failure
# it is testing for, not only after the success.
ABSENT_SERVER="p7probe-absent-$$"
export ISABELLE_QUERY_CLIENT_SERVER="$SERVER"
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-cache.json"

# Sections 0-14 are about the SERVER and the THIN CLIENT, and every `isabelle
# query` in them is the cold reference the served answer is compared against.
# Without this the shim would route those references to the client -- to this
# probe's own server -- and every comparison would quietly become warm against
# warm.  With it, a plain `isabelle query` is shim -> JVM -> local: the same
# engine, in the same kind of process, having consulted nothing.
#
# §15 and §16 are where the front door's own routing is the subject, so they
# turn it back off, deliberately and per invocation (`env -u`).
export ISABELLE_QUERY_NO_SERVER=1

# The export is for the cold REFERENCES only.  Every direct call of the thin
# client in §0-§14, §18 and §19 means the warm path, and since 85b62a5 the
# client reads the variable too: it is `--no-server` spelled for a shell (README,
# "nothing warm at all" in `lib/Tools/query`), so under it a run declines with
# exit 97 and an action (`--client-status`, `--client-stop`) is refused with
# "client action requires the warm path".  That is the client keeping the
# documented contract, not a defect in it -- it was this script that never
# scoped the export, and 17 checks failed for it [p7probe-scoping].  So the
# client is called through `client`, which drops the variable for that one
# process, the same `env -u` §15 and §16 spell out by hand.
client() { env -u ISABELLE_QUERY_NO_SERVER python3 "$CLIENT" "$@"; }

fail=0
checks=0
note() { checks=$((checks + 1)); echo "  ok    $1${2:+  [$2]}"; }
bad()  { checks=$((checks + 1)); fail=$((fail + 1)); echo "  FAIL  $1  [$2]"; }

# THE PROBE'S OWN SERVERS ARE MANUALLY MANAGED, because since 85b62a5 nothing
# else outlives a request.  A server the client starts is an owned fallback:
# it holds the client's private stdin pipe and exits when that invocation ends
# (README, "The warm server").  Until this was noticed the script still let
# `--client-status` start the server §1-§14 compare against, so §0 looked for a
# registry row that had already gone, §12 touched the jar under a server that
# no longer existed, and §9b's "pinned server" was a fresh unpinned one by the
# time the clean client asked -- passing, and proving nothing
# [p7probe-scoping].  So `serve` does what the README tells a user to do for
# cross-command caching, `isabelle query_server -n NAME` with stdin held open,
# and `unserve` closes that stdin, which is the server's documented way out.
declare -A HOLD=()
serve() {
  local name="$1" fifo="$OUT/hold-$1" fd i
  rm -f "$fifo" "$OUT/serve-$name.out"
  mkfifo "$fifo" || return 1
  # The server must not inherit the write end of ANOTHER server's pipe, or
  # closing that one would never reach its server while this one lives.
  ( for f in "${HOLD[@]}"; do exec {f}>&-; done
    exec isabelle query_server -n "$name" ) <"$fifo" >"$OUT/serve-$name.out" 2>&1 &
  exec {fd}>"$fifo"
  HOLD[$name]=$fd
  # The greeting is printed once the registry row is written; bounded, so a
  # server that never comes up is a FAIL rather than a hang.
  for i in $(seq 1 600); do
    grep -q 'password' "$OUT/serve-$name.out" 2>/dev/null && return 0
    sleep 0.1
  done
  return 1
}
# Bounded wait until no server JVM of this name is left.  A server halts within
# its own 3-second bound once told to stop, so a fixed `sleep 0.3` was a race
# that §19 lost.
gone() {
  local i
  for i in $(seq 1 50); do
    [ "$(pgrep -cf "Query_Server_Main -n $1\$" 2>/dev/null)" = "0" ] && return 0
    sleep 0.1
  done
  return 1
}
unserve() {
  local fd="${HOLD[$1]:-}"
  [ -n "$fd" ] && exec {fd}>&-
  unset "HOLD[$1]"
  gone "$1"
}
# A name for §0's owned fallback, which must NOT outlive its invocation.
OWNED_SERVER="p7probe-owned-$$"

cleanup() {
  for s in "${!HOLD[@]}"; do unserve "$s"; done
  for s in "$SERVER" "$ENV_SERVER" "$SHIM_SERVER" "$DEAD_SERVER" \
           "$ABSENT_SERVER" "$OWNED_SERVER"; do
    isabelle server -x -n "$s" >/dev/null 2>&1
    # Belt to that brace: a server killed with -9 leaves no socket for `-x` to
    # talk to, so match on the command line as well.
    pkill -f "isabelle_server.*$s" >/dev/null 2>&1
    pkill -f "Query_Server_Main -n $s\$" >/dev/null 2>&1
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

# First the contract that made the rest of this section manual: a server the
# CLIENT starts is answered from and then gone, row and process both.
ISABELLE_QUERY_CLIENT_SERVER="$OWNED_SERVER" client --client-status \
  >"$OUT/owned-status.txt" 2>&1
if grep -q "^server        $OWNED_SERVER" "$OUT/owned-status.txt" &&
   ! isabelle server -l 2>/dev/null | grep -q "\"$OWNED_SERVER\"" &&
   [ "$(pgrep -cf "Query_Server_Main -n $OWNED_SERVER\$" 2>/dev/null)" = "0" ]; then
  note "a client-started server answers, then ends with its invocation"
else
  bad "a client-started server answers, then ends with its invocation" \
    "$(head -1 "$OUT/owned-status.txt"); $(pgrep -cf "Query_Server_Main -n $OWNED_SERVER\$") left"
fi

if serve "$SERVER"; then
  note "the probe's dedicated server is up (isabelle query_server)"
else
  bad "the probe's dedicated server is up (isabelle query_server)" \
    "$(head -2 "$OUT/serve-$SERVER.out" | tr '\n' ' ')"
fi

client --client-status >"$OUT/status.txt" 2>&1
if grep -q "^server        $SERVER" "$OUT/status.txt"; then
  note "the client reaches it under its own name"
else
  bad "the client reaches it under its own name" "$(head -2 "$OUT/status.txt" | tr '\n' ' ')"
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
client -R "$AFP" summary >"$OUT/warm-summary.txt" 2>"$OUT/warm-summary.err"
warm_rc=$?
if cmp -s "$OUT/cold-summary.txt" "$OUT/warm-summary.txt" && [ "$cold_rc" = "$warm_rc" ]; then
  note "warm client stdout and exit equal the cold tool's" "exit $warm_rc"
else
  bad "warm client stdout and exit equal the cold tool's" "$cold_rc vs $warm_rc"
fi

# The tool's own -R must survive the client's path rewriting, from a working
# directory that is NOT the project.
( cd "$OUT" && client -R "$AFP" theory --names ) >"$OUT/warm-rel.txt" 2>&1
isabelle query -R "$AFP" theory --names >"$OUT/cold-rel.txt" 2>&1
if cmp -s "$OUT/cold-rel.txt" "$OUT/warm-rel.txt"; then
  note "and an absolute -R from an unrelated cwd resolves the same"
else
  bad "and an absolute -R from an unrelated cwd resolves the same" "differs"
fi

# No -R at all: the root comes from the CLIENT's working directory, resolved
# by the CLI's own rule inside the server.  This is the check that would catch
# a served run silently using the server's cwd instead.
( cd "$AFP" && client summary ) >"$OUT/warm-cwd.txt" 2>&1
if cmp -s "$OUT/cold-summary.txt" "$OUT/warm-cwd.txt"; then
  note "a bare invocation resolves the CLIENT's cwd, not the server's"
else
  bad "a bare invocation resolves the CLIENT's cwd, not the server's" \
    "$(head -1 "$OUT/warm-cwd.txt")"
fi

# A relative path argument is the other half of the same question.
( cd "$(dirname "$AFP")" && client -R "$(basename "$AFP")" summary ) \
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

# Start the second server UNDER the pin, so it inherits it.  Through P7 a
# pinned client started it; since 85b62a5 that server would end with the
# client, and the clean client below would get a fresh, clean one of its own
# and pass for the wrong reason -- so it is a manually managed server, which is
# also exactly the "started from some terminal, outlives it" case the defect
# was about.
ISABELLE_QUERY_NAMESPACE=committed serve "$ENV_SERVER"
ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" ISABELLE_QUERY_NAMESPACE=committed \
  client --client-status >"$OUT/env-status.txt" 2>&1
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
  client -R "$ZF" callers induct \
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
  client -R "$ZF" callers induct \
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
    client callers induct ) >"$OUT/env-root.txt" 2>"$OUT/env-root.err"
if cmp -s "$OUT/zf-plain.txt" "$OUT/env-root.txt"; then
  note "\$ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd"
else
  bad "\$ISABELLE_QUERY_ROOT is the request's, from an unrelated cwd" \
    "$(head -1 "$OUT/env-root.txt")"
fi

# Non-vacuity: the three answers above came from the pinned server only if it
# was there for all of them.  Had it gone, the client would have started a
# clean fallback under the same name, and every check above would pass.
if [ "$(pgrep -cf "Query_Server_Main -n $ENV_SERVER\$" 2>/dev/null)" != "0" ]; then
  note "and the pinned server answered all three (it is still the one up)"
else
  bad "and the pinned server answered all three (it is still the one up)" \
    "gone before the stop"
fi

ISABELLE_QUERY_CLIENT_SERVER="$ENV_SERVER" client --client-stop \
  >"$OUT/env-stop.txt" 2>&1
if gone "$ENV_SERVER"; then
  note "and the pinned server is stopped again"
else
  bad "and the pinned server is stopped again" "still running"
fi
unserve "$ENV_SERVER"

# --------------------------------------------------------------------------
echo
echo "10. the refusal reaches the user, and is not an empty answer"

client --client-limit 1 -R "$AFP" summary \
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

# Each check asks two things at once, and since P8 they are two different
# processes' jobs: the CLIENT must decline (`cold path` on its stderr) and the
# SHIM must then produce the cold answer.  So these run through the front door
# rather than against the script, which on its own would exit 97 and write
# nothing -- that half of the contract is §15a's subject.
front() { env -u ISABELLE_QUERY_NO_SERVER isabelle query "$@"; }

front --client-verbose --client-cold -R "$AFP" summary \
  >"$OUT/forced-cold.txt" 2>"$OUT/forced-cold.err"
if cmp -s "$OUT/cold-summary.txt" "$OUT/forced-cold.txt" &&
   grep -q "cold path" "$OUT/forced-cold.err"; then
  note "--client-cold takes the cold path and answers the same"
else
  bad "--client-cold takes the cold path and answers the same" \
    "$(tr '\n' ' ' <"$OUT/forced-cold.err")"
fi

front --client-verbose dump-theories "$AFP" \
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

# A census reply is corpus-sized and slower through the socket than cold
# (dev/BENCH.md), so the front door must run it cold without being asked.
front --client-verbose -R "$AFP" shape census \
  >"$OUT/census-client.jsonl" 2>"$OUT/census-client.err"
isabelle query -R "$AFP" shape census >"$OUT/census-cold.jsonl" 2>/dev/null
if grep -q "cold path" "$OUT/census-client.err" && [ -s "$OUT/census-client.jsonl" ] &&
   cmp -s "$OUT/census-cold.jsonl" "$OUT/census-client.jsonl"; then
  note "a shape census routes cold (a 256 MB reply does not belong on a socket)" \
    "$(wc -l <"$OUT/census-client.jsonl") records"
else
  bad "a shape census routes cold (a 256 MB reply does not belong on a socket)" \
    "$(tr '\n' ' ' <"$OUT/census-client.err" | cut -c1-70)"
fi

# `-` reads stdin, and the decline has to keep it readable: the client exits
# without consuming it, so the JVM the shim runs next still finds the theory on
# fd 0.  A decline that had read one byte would break this check.
printf 'theory P7 imports Main begin\nlemma p7_stdin: "True" by simp\nend\n' \
  >"$OUT/stdin.thy"
front --client-verbose grep p7_stdin - <"$OUT/stdin.thy" \
  >"$OUT/stdin.txt" 2>"$OUT/stdin.err"
if grep -q "cold path" "$OUT/stdin.err" && grep -q "p7_stdin" "$OUT/stdin.txt"; then
  note "a '-' argument routes cold, with stdin intact for the cold run"
else
  bad "a '-' argument routes cold, with stdin intact for the cold run" \
    "$(tr '\n' ' ' <"$OUT/stdin.err")"
fi

# --------------------------------------------------------------------------
echo
echo "12. staleness -- a rebuilt component is not answered from the old one"

# `touch` is the whole of what a rebuild changes as far as the stamp is
# concerned (mtime, and size if the code moved), and it is deterministic where
# a real rebuild is 30 seconds and a mtime race.  dev/P7-STATUS.md records the
# real-rebuild transcript.
#
# Since 85b62a5 a stale MANUALLY MANAGED server is not restarted behind its
# owner's back: the client skips it and declines ("The client skips dead,
# stale, or incompatible ... hosts", README), the front door answers cold, and
# a new jar reaches the server at its owner's next restart ("Updated
# components must be loaded at the host's next coordinated restart").  Through
# P7 this section expected the client to restart the server itself, which is
# the behaviour that commit retired.
before=$(grep '^component_id' "$OUT/status.txt" | awk '{print $2}')
touch "$JAR"
client --client-verbose -R "$AFP" summary \
  >"$OUT/stale.txt" 2>"$OUT/stale.err"
stale_rc=$?
if [ "$stale_rc" = "97" ] && [ ! -s "$OUT/stale.txt" ] &&
   grep -q "falling back" "$OUT/stale.err"; then
  note "a server older than the jar is not answered from: the client declines" \
    "rc $stale_rc, $(grep -o 'falling back.*' "$OUT/stale.err" | cut -c1-50)"
else
  bad "a server older than the jar is not answered from: the client declines" \
    "rc $stale_rc, $(tr '\n' ' ' <"$OUT/stale.err" | cut -c1-90)"
fi
front -R "$AFP" summary >"$OUT/stale-front.txt" 2>/dev/null
if cmp -s "$OUT/cold-summary.txt" "$OUT/stale-front.txt"; then
  note "and the front door still gives the right answer, cold"
else
  bad "and the front door still gives the right answer, cold" "differs"
fi
# The owner's restart.
unserve "$SERVER"
serve "$SERVER"
client --client-status >"$OUT/status2.txt" 2>&1
after=$(grep '^component_id' "$OUT/status2.txt" | awk '{print $2}')
if [ -n "$after" ] && [ "$before" != "$after" ] && ! grep -q STALE "$OUT/status2.txt"; then
  note "the restarted server carries the new stamp" "$before -> $after"
else
  bad "the restarted server carries the new stamp" "$before -> $after"
fi

# --------------------------------------------------------------------------
echo
echo "13. failure falls back, and the answer is still right"

# A server killed outright leaves a registry row pointing at nothing.  The
# client must notice, start a replacement and answer -- never hang on the dead
# port, never report an empty result.  The kill has to have hit something:
# the pattern is the server JVM's own command line (`Query_Server_Main -n
# NAME`), and the `server -n NAME` this used through P7 matched nothing once
# servers were launched as `isabelle query_server` -- so the check passed with
# no server killed at all.
killed=0
pkill -9 -f "Query_Server_Main -n $SERVER\$" >/dev/null 2>&1 && killed=1
sleep 0.3
start=$(date +%s%N)
client --client-verbose -R "$AFP" summary \
  >"$OUT/killed.txt" 2>"$OUT/killed.err"
elapsed=$(( ($(date +%s%N) - start) / 1000000 ))
if [ "$killed" = "1" ] && grep -q "starting dedicated server $SERVER" "$OUT/killed.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/killed.txt"; then
  note "after the server is killed mid-session the answer is still right" \
    "${elapsed} ms, $(tr '\n' ' ' <"$OUT/killed.err" | cut -c1-48)"
else
  bad "after the server is killed mid-session the answer is still right" \
    "killed $killed, $(tr '\n' ' ' <"$OUT/killed.err" | cut -c1-60)"
fi
# The replacement was the client's own and ended with it; §14, §18 and §19 want
# the probe's server back, as its owner would restart it.
unserve "$SERVER"
serve "$SERVER"

# And the true fallback: a client that cannot START a server must run the tool
# cold rather than fail.  The stub refuses exactly the two launchers -- the
# client has started its fallback with `query_server` since 85b62a5, and a stub
# that refused only `server` let it start one regardless -- and forwards
# everything else, so the cold path is exercised for real.
SHIM="$OUT/isabelle-noserver"
{
  echo '#!/usr/bin/env bash'
  echo 'case "${1:-}" in server | query_server) echo "p7probe: no server for you" >&2; exit 1 ;; esac'
  echo "exec isabelle \"\$@\""
} >"$SHIM"
chmod +x "$SHIM"
# NOT through `isabelle query`: `bin/isabelle` exports its own $ISABELLE_TOOL
# when it dispatches, so a stub passed from outside never reaches the client.
# `isabelle env` puts the command INSIDE the settings environment, where the
# override survives, and the shim is then run as the script it is.
isabelle env -u ISABELLE_QUERY_NO_SERVER ISABELLE_TOOL="$SHIM" \
  ISABELLE_QUERY_CLIENT_SERVER="$ABSENT_SERVER" \
  ISABELLE_QUERY_CLIENT_CACHE="$OUT/absent-cache.json" \
  bash "$SHIM_TOOL" --client-verbose -R "$AFP" summary \
  >"$OUT/nofallback.txt" 2>"$OUT/nofallback.err"
if cmp -s "$OUT/cold-summary.txt" "$OUT/nofallback.txt" &&
   grep -q "falling back" "$OUT/nofallback.err"; then
  note "a client that cannot start a server declines, and the shim answers" \
    "$(grep 'falling back' "$OUT/nofallback.err" | cut -c1-56)"
else
  bad "a client that cannot start a server declines, and the shim answers" \
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
echo "15. the decline protocol: the client asks, the shim answers (P8)"

# Through P7 this section asked what the JVM front end did when it went looking
# for a server ITSELF -- `delegate.scala`, a second copy of the thin client's
# routing policy, in Scala.  P8 deleted that copy.  The client is now the only
# thing that talks to a server, and when it will not serve a request it says so
# with an exit status and writes NOTHING, leaving `lib/Tools/query` to run the
# cold path.  Routing flows one way and lives in one file.
#
# The properties below are the ones §15 always checked -- byte and exit
# identity, SIGPIPE, an opt-out that starts nothing, every failure ending in
# the right answer -- asked of the route that replaced it.  What is gone is
# what only the deleted layer could have got wrong: a second implementation of
# spawning, staleness detection, environment forwarding and the bypass list.
# §9 to §14 check all four against the one implementation that is left, so
# nothing here is coverage lost.
#
# 15c is new, and it is coverage GAINED: SIGPIPE used to be checked only on the
# delegated path, which means the warm client -- now the only writer on the
# fast path that is not the engine itself -- was never asked.

port_of() {  # the registry's port for a server name, or nothing
  isabelle server -l 2>/dev/null | sed -n "s/^server \"$1\" = [^:]*:\([0-9]*\) .*/\1/p"
}
shim() {     # the front door with the warm path ON, on this section's server
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SHIM_SERVER" \
    isabelle query "$@"
}
# This section's server is manually managed for the reason §0 gives: a
# client-started one would be gone after every invocation, and §15e's kill
# would have nothing to kill.
if serve "$SHIM_SERVER"; then
  note "this section's dedicated server is up" "$SHIM_SERVER"
else
  bad "this section's dedicated server is up" \
    "$(head -2 "$OUT/serve-$SHIM_SERVER.out" | tr '\n' ' ')"
fi

# --- 15a. a decline is a status, and stdout stays empty ---------------------

# THE CONTRACT THE WHOLE SECTION RESTS ON, and it is asked of the client
# DIRECTLY -- through the shim the status is consumed and could not be seen.
# Any status but 97 would be read as an answer; any byte on stdout would be
# duplicated by the cold run that follows.
#
# 97 is written here as a literal on purpose.  It is a wire constant, shared
# between `query_client.py` (EXIT_RUN_COLD) and `lib/Tools/query`
# ($EXIT_RUN_COLD), and a probe that read it from either could not catch the
# two drifting apart.
env -u ISABELLE_QUERY_NO_SERVER python3 "$CLIENT" --client-cold \
  -R "$AFP" summary >"$OUT/p8-decline.txt" 2>"$OUT/p8-decline.err"
decline_rc=$?
if [ "$decline_rc" = "97" ] && [ ! -s "$OUT/p8-decline.txt" ]; then
  note "a declining client exits 97 with empty stdout" "--client-cold"
else
  bad "a declining client exits 97 with empty stdout" \
    "rc=$decline_rc, $(wc -c <"$OUT/p8-decline.txt") byte(s) on stdout"
fi

# The same, for a decline the caller did not ask for: a bypassed verb.
env -u ISABELLE_QUERY_NO_SERVER python3 "$CLIENT" \
  dump-theories "$AFP" >"$OUT/p8-decline2.txt" 2>"$OUT/p8-decline2.err"
decline2_rc=$?
if [ "$decline2_rc" = "97" ] && [ ! -s "$OUT/p8-decline2.txt" ]; then
  note "and a bypassed verb declines the same way" "dump-theories"
else
  bad "and a bypassed verb declines the same way" \
    "rc=$decline2_rc, $(wc -c <"$OUT/p8-decline2.txt") byte(s) on stdout"
fi

# ... and that the check above can say no.  A verb the client DOES serve must
# not exit 97, or "97" would be measuring nothing.
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SHIM_SERVER" \
  python3 "$CLIENT" -R "$AFP" summary >"$OUT/p8-served.txt" 2>/dev/null
served_rc=$?
if [ "$served_rc" != "97" ] && [ -s "$OUT/p8-served.txt" ]; then
  note "and a served verb does not (else 97 would mean nothing)" "rc=$served_rc"
else
  bad "and a served verb does not (else 97 would mean nothing)" \
    "rc=$served_rc, $(wc -c <"$OUT/p8-served.txt") byte(s)"
fi

# --- 15b. byte and exit identity, over a spread of verbs -------------------

# The claim this whole mode is judged on, asked of the decline route: the shim
# runs the client, the client declines, the shim runs the JVM.  A spread of
# invocations chosen to cross every family (structure, usage, shape, the two
# site verbs) and to include three exit statuses: 0, an unresolved subject (1)
# and a usage error (2).  stdout, stderr and the status are compared, byte for
# byte, against the same invocation run cold.
p8_cases=(
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
  "instances stream -r"
  "codeqs fair_fenum"
  "shape summary"
  "shape steps -N 3"
  "show no_such_entry_xyz"
  "no-such-command"
)
p8_ok=0
p8_bad=""
p8_nonempty=0
for spec in "${p8_cases[@]}"; do
  read -r -a p8_argv <<<"$spec"
  p8_id=$(printf '%s' "$spec" | tr -c 'A-Za-z0-9._-' '_')
  isabelle query -R "$AFP" "${p8_argv[@]}" \
    >"$OUT/p8-cold-$p8_id.out" 2>"$OUT/p8-cold-$p8_id.err"
  c_rc=$?
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SHIM_SERVER" \
    isabelle query --client-cold -R "$AFP" "${p8_argv[@]}" \
    >"$OUT/p8-decl-$p8_id.out" 2>"$OUT/p8-decl-$p8_id.err"
  d_rc=$?
  [ -s "$OUT/p8-cold-$p8_id.out" ] && p8_nonempty=$((p8_nonempty + 1))
  if cmp -s "$OUT/p8-cold-$p8_id.out" "$OUT/p8-decl-$p8_id.out" &&
     cmp -s "$OUT/p8-cold-$p8_id.err" "$OUT/p8-decl-$p8_id.err" &&
     [ "$c_rc" = "$d_rc" ]; then
    p8_ok=$((p8_ok + 1))
  else
    p8_bad="$p8_bad $spec($c_rc/$d_rc)"
  fi
done
if [ "$p8_ok" = "${#p8_cases[@]}" ]; then
  note "${#p8_cases[@]} declined invocations: identical stdout, stderr and exit" \
    "$p8_nonempty with non-empty stdout"
else
  bad "${#p8_cases[@]} declined invocations: identical stdout, stderr and exit" \
    "$((${#p8_cases[@]} - p8_ok)) differ:$p8_bad"
fi

# Failability, right here rather than in a separate demo: the comparison above
# is only worth anything if it can say no, and if it was not comparing empty
# files.  Perturb one byte of one captured answer and re-run the same `cmp`.
cp "$OUT/p8-decl-summary.out" "$OUT/p8-perturbed.out"
printf 'x' >>"$OUT/p8-perturbed.out"
if [ "$p8_nonempty" -ge 15 ] &&
   ! cmp -s "$OUT/p8-cold-summary.out" "$OUT/p8-perturbed.out"; then
  note "and the comparison can say no (one byte added is caught)" \
    "$p8_nonempty of ${#p8_cases[@]} answers non-empty"
else
  bad "and the comparison can say no (one byte added is caught)" \
    "$p8_nonempty non-empty"
fi

# --- 15c. SIGPIPE, on the WARM path and on the declined one ----------------

# NEW IN P8, and the gap it fills is the one the deleted layer was hiding.  A
# warm answer arrives whole and is then written out by the CLIENT, so a closed
# pipe is met by python's write, not by the engine's -- a path nothing used to
# exercise, because §15d only ever asked the delegate.  141 is what a shell
# reports for a process killed by SIGPIPE and what the cold tool gives, and all
# of them must agree.  `.*` rather than `.` as the pattern, because `.` names a
# directory and would take the invocation off the warm path entirely (§11).

# (i) a reader that is already gone when the first byte is written
shim -R "$AFP" find '.*' -a 2>/dev/null | head -n 0 >/dev/null
w_pipe0=${PIPESTATUS[0]}
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SHIM_SERVER" \
  isabelle query --client-cold -R "$AFP" find '.*' -a 2>/dev/null | head -n 0 >/dev/null
d_pipe0=${PIPESTATUS[0]}
isabelle query -R "$AFP" find '.*' -a 2>/dev/null | head -n 0 >/dev/null
c_pipe0=${PIPESTATUS[0]}
if [ "$w_pipe0" = "141" ] && [ "$d_pipe0" = "141" ] && [ "$c_pipe0" = "141" ]; then
  note "a downstream that has already gone is 141: warm, declined and cold" \
    "$c_pipe0 / $w_pipe0 / $d_pipe0"
else
  bad "a downstream that has already gone is 141: warm, declined and cold" \
    "cold $c_pipe0, warm $w_pipe0, declined $d_pipe0"
fi

# (ii) a reader that leaves after three lines of a two-megabyte answer -- far
# past the pipe's own capacity, so the writer is still writing when it goes.
shim -R "$ZF" find '.*' -a 2>/dev/null | head -3 >"$OUT/p8-pipe-warm.txt"
w_pipe_rc=${PIPESTATUS[0]}
isabelle query -R "$ZF" find '.*' -a 2>/dev/null | head -3 >"$OUT/p8-pipe-cold.txt"
c_pipe_rc=${PIPESTATUS[0]}
if [ "$w_pipe_rc" = "$c_pipe_rc" ] && [ "$w_pipe_rc" = "141" ] &&
   cmp -s "$OUT/p8-pipe-warm.txt" "$OUT/p8-pipe-cold.txt"; then
  note "output piped into \`head -3\` exits 141, warm as cold" \
    "$c_pipe_rc / $w_pipe_rc"
else
  bad "output piped into \`head -3\` exits 141, warm as cold" \
    "cold $c_pipe_rc, warm $w_pipe_rc"
fi

# --- 15d. the opt-out, and that it starts nothing --------------------------

# A name nothing has ever run under: if the opt-out leaked, the registry would
# gain a row.  That is the check.  Since P8 there is no delegate note to grep
# for, so the evidence is the absence of the row plus the right answer -- which
# is the stronger reading anyway: the old check could pass on a note printed by
# a layer that then went and started a server regardless.
OPTOUT_SERVER="p8probe-optout-$$"
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$OPTOUT_SERVER" \
  isabelle query --no-server -R "$AFP" summary \
  >"$OUT/p8-flag.txt" 2>"$OUT/p8-flag.err"
if [ -z "$(port_of "$OPTOUT_SERVER")" ] &&
   ! grep -q "^query-client:" "$OUT/p8-flag.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/p8-flag.txt"; then
  note "--no-server answers here and starts no server" "$OPTOUT_SERVER absent"
else
  bad "--no-server answers here and starts no server" \
    "$(tr '\n' ' ' <"$OUT/p8-flag.err" | cut -c1-70)"
fi

ISABELLE_QUERY_NO_SERVER=1 ISABELLE_QUERY_CLIENT_SERVER="$OPTOUT_SERVER" \
  isabelle query -R "$AFP" summary \
  >"$OUT/p8-envoff.txt" 2>"$OUT/p8-envoff.err"
if [ -z "$(port_of "$OPTOUT_SERVER")" ] &&
   ! grep -q "^query-client:" "$OUT/p8-envoff.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/p8-envoff.txt"; then
  note "and \$ISABELLE_QUERY_NO_SERVER=1 does the same for a shell"
else
  bad "and \$ISABELLE_QUERY_NO_SERVER=1 does the same for a shell" \
    "$(tr '\n' ' ' <"$OUT/p8-envoff.err" | cut -c1-70)"
fi

# The other opt-out, which routes past the client but NOT past the engine: it
# must answer, and it must answer cold, because nothing downstream of the shim
# looks for a server any more.  This is the check that would have caught the
# deleted layer coming back.
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_NO_CLIENT=1 \
  ISABELLE_QUERY_CLIENT_SERVER="$OPTOUT_SERVER" \
  isabelle query -R "$AFP" summary \
  >"$OUT/p8-noclient.txt" 2>"$OUT/p8-noclient.err"
if [ -z "$(port_of "$OPTOUT_SERVER")" ] &&
   ! grep -q "^query-client:" "$OUT/p8-noclient.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/p8-noclient.txt"; then
  note "\$ISABELLE_QUERY_NO_CLIENT=1 answers cold, and starts no server" \
    "no delegate left to find one"
else
  bad "\$ISABELLE_QUERY_NO_CLIENT=1 answers cold, and starts no server" \
    "$(tr '\n' ' ' <"$OUT/p8-noclient.err" | cut -c1-70)"
fi

# --- 15e. every failure ends in the right answer ---------------------------

# A server killed outright leaves a registry row pointing at nothing.  The next
# invocation must notice, replace it, and answer -- never hang on the dead
# port, never report an empty result.  As in §13, the kill must hit something.
p8_killed=0
pkill -9 -f "Query_Server_Main -n $SHIM_SERVER\$" >/dev/null 2>&1 && p8_killed=1
sleep 0.3
p8_start=$(date +%s%N)
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$SHIM_SERVER" \
  isabelle query --client-verbose -R "$AFP" summary \
  >"$OUT/p8-killed.txt" 2>"$OUT/p8-killed.err"
p8_elapsed=$(( ($(date +%s%N) - p8_start) / 1000000 ))
if [ "$p8_killed" = "1" ] && grep -q "starting" "$OUT/p8-killed.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/p8-killed.txt"; then
  note "after the server is killed the answer is still right" \
    "${p8_elapsed} ms, $(grep -c 'starting' "$OUT/p8-killed.err") restart(s)"
else
  bad "after the server is killed the answer is still right" \
    "killed $p8_killed, $(tr '\n' ' ' <"$OUT/p8-killed.err" | cut -c1-60)"
fi

# And the true decline: a registry row that names something which ACCEPTS a
# connection and then says nothing.  That is a protocol failure rather than a
# dead port, so the client cannot rescue it by starting a server -- it has to
# hand the invocation back, and the SHIM has to notice and run it.  A python
# listener plus one row in the scratch registry is the whole apparatus; both
# are removed below.
python3 - "$OUT" "$DEAD_SERVER" \
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
    # Read the password the client sends, so our reply is not racing an RST,
    # then answer something that is not "OK".  A greeting that fails is a
    # protocol failure: unlike a dead port, starting a server would not fix it,
    # so the client must decline rather than retry.
    try:
        client.settimeout(5.0)
        client.recv(65536)
        client.sendall(b"NOPE not a server\n")
    except OSError:
        pass
    client.close()
sock.close()
PYEOF
dead_pid=$!
rm -f "$OUT/deadserver.ready" "$OUT/deadserver.stop"
for _ in $(seq 1 100); do [ -f "$OUT/deadserver.ready" ] && break; sleep 0.1; done
if [ -f "$OUT/deadserver.ready" ]; then
  env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_CLIENT_SERVER="$DEAD_SERVER" \
    timeout -k 10 180 isabelle query --client-verbose -R "$AFP" summary \
    >"$OUT/p8-mute.txt" 2>"$OUT/p8-mute.err"
  if cmp -s "$OUT/cold-summary.txt" "$OUT/p8-mute.txt" &&
     grep -q "falling back" "$OUT/p8-mute.err"; then
    note "a server that accepts and says nothing declines, and the shim answers" \
      "$(grep 'falling back' "$OUT/p8-mute.err" | cut -c1-52)"
  else
    bad "a server that accepts and says nothing declines, and the shim answers" \
      "$(tr '\n' ' ' <"$OUT/p8-mute.err" | cut -c1-90)"
  fi
else
  bad "a server that accepts and says nothing declines, and the shim answers" \
    "the stand-in listener never came up"
fi
touch "$OUT/deadserver.stop"
kill "$dead_pid" >/dev/null 2>&1
wait "$dead_pid" 2>/dev/null

isabelle server -x -n "$SHIM_SERVER" >/dev/null 2>&1

# --------------------------------------------------------------------------
echo
echo "16. the front door: a bare \`isabelle query\` is the thin client (P7d)"

# `bin/isabelle` dispatches to an external tool before it starts a JVM, and
# the component ships one under the tool's own name (query_base/lib/Tools/
# query).  These checks pin the ROUTING -- who answers, under which switch --
# and above all that every route TERMINATES.  The shim's JVM tail must be the
# dispatcher-free `Query_Main`: BOTH dispatchers (`bin/isabelle` and
# `isabelle.Isabelle_Tool`) resolve external tools first, so a name-based
# JVM tail re-enters the shim as a child and stacks one live JVM per second
# until the machine dies.  `timeout` is the harness against that regression:
# it must FAIL here, not freeze the host.

# --- 16a. the tool name reaches the client, and the answer is the cold one
env -u ISABELLE_QUERY_NO_SERVER timeout -k 10 120 \
  isabelle query --client-verbose -R "$AFP" summary \
  >"$OUT/shim-warm.txt" 2>"$OUT/shim-warm.err"
if grep -q "^query-client: warm" "$OUT/shim-warm.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/shim-warm.txt"; then
  note "\`isabelle query\` resolves to the client and answers warm" \
    "$(grep '^query-client: warm' "$OUT/shim-warm.err" | cut -c1-40)"
else
  bad "\`isabelle query\` resolves to the client and answers warm" \
    "$(tr '\n' ' ' <"$OUT/shim-warm.err" | cut -c1-90)"
fi

# --- 16b. --no-server through the front door lands in the JVM, locally
env -u ISABELLE_QUERY_NO_SERVER timeout -k 10 120 \
  isabelle query --no-server -R "$AFP" summary \
  >"$OUT/shim-flag.txt" 2>"$OUT/shim-flag.err"
if ! grep -q "^query-client:" "$OUT/shim-flag.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/shim-flag.txt"; then
  note "--no-server routes past the client and answers locally"
else
  bad "--no-server routes past the client and answers locally" \
    "$(tr '\n' ' ' <"$OUT/shim-flag.err" | cut -c1-90)"
fi

# --- 16c. $ISABELLE_QUERY_NO_CLIENT=1 is the JVM front end, and it is terminal
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_NO_CLIENT=1 timeout -k 10 120 \
  isabelle query -R "$AFP" summary >"$OUT/shim-jvm.txt" 2>"$OUT/shim-jvm.err"
if ! grep -q "^query-client:" "$OUT/shim-jvm.err" &&
   cmp -s "$OUT/cold-summary.txt" "$OUT/shim-jvm.txt"; then
  note "\$ISABELLE_QUERY_NO_CLIENT=1 is the JVM front end, answering locally"
else
  bad "\$ISABELLE_QUERY_NO_CLIENT=1 is the JVM front end, answering locally" \
    "$(tr '\n' ' ' <"$OUT/shim-jvm.err" | cut -c1-90)"
fi

# --- 16d. the hop that must NOT happen: a decline never re-enters the tool
# name.  Before P8 the client exec'd `isabelle query` here and the run took two
# hops through this file, held to two by an environment mark.  Now the shim
# never re-enters itself, so the mark must be ABSENT from the child that
# answers -- which is what the JVM tail reports when asked.  Both the answer
# and the timeout matter: the timeout is what turns a reintroduced loop into a
# failing check instead of a dead machine.
env -u ISABELLE_QUERY_NO_SERVER timeout -k 10 120 \
  isabelle query --client-verbose dump-theories "$AFP" \
  >"$OUT/shim-dump.txt" 2>"$OUT/shim-dump.err"
shim_dump_rc=$?
if [ "$shim_dump_rc" = "0" ] &&
   grep -q "^query-client: cold path" "$OUT/shim-dump.err" &&
   ! grep -q "refusing to recurse" "$OUT/shim-dump.err" &&
   cmp -s "$OUT/dump-cold.txt" "$OUT/shim-dump.txt"; then
  note "a decline is answered in one hop, without re-entering the tool name" \
    "$(wc -l <"$OUT/shim-dump.txt") theories"
else
  bad "a decline is answered in one hop, without re-entering the tool name" \
    "rc=$shim_dump_rc $(tr '\n' ' ' <"$OUT/shim-dump.err" | cut -c1-80)"
fi

# --- 16e. the tripwire: a re-entered JVM path refuses instead of spawning
# Kept although P8 leaves no route that can reach it: it was earned twice, the
# hard way, and it is five lines against 445 live JVMs.  Checking it still
# fires is what keeps it from rotting into a comment.
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_SHIM_REENTRY=1 \
  timeout -k 10 120 isabelle query --no-server -R "$AFP" summary \
  >"$OUT/shim-trip.txt" 2>"$OUT/shim-trip.err"
trip_rc=$?
if [ "$trip_rc" = "2" ] && [ ! -s "$OUT/shim-trip.txt" ] &&
   grep -q "refusing to recurse" "$OUT/shim-trip.err"; then
  note "a re-entered JVM path refuses loudly (exit 2, empty stdout)"
else
  bad "a re-entered JVM path refuses loudly (exit 2, empty stdout)" \
    "exit $trip_rc, $(tr '\n' ' ' <"$OUT/shim-trip.err" | cut -c1-70)"
fi

# --- 16f. a client option with no client to run it is an error, not a silent
# drop.  The shim strips `--client-*` off the argv before the JVM tail, which
# is what lets a decline run the tool's arguments without the client's; the
# failure mode that buys is a client option quietly vanishing when python3 is
# missing.  A PATH with no python3 is the whole apparatus.
# Again not through `isabelle query`: the settings shell recomputes
# $ISABELLE_QUERY_BASE_HOME from the component, so an override only
# survives INSIDE that environment.  An empty base home is a missing client
# script, which is the same `client_available=false` branch a missing python3
# reaches, without breaking the PATH every other tool here needs.
mkdir -p "$OUT/noclient"
isabelle env ISABELLE_QUERY_BASE_HOME="$OUT/noclient" \
  ISABELLE_QUERY_CLIENT_CACHE="$OUT/noclient-cache.json" \
  timeout -k 10 120 bash "$SHIM_TOOL" --client-status \
  >"$OUT/shim-noclient.txt" 2>"$OUT/shim-noclient.err"
noclient_rc=$?
if [ "$noclient_rc" = "2" ] && [ ! -s "$OUT/shim-noclient.txt" ] &&
   grep -q "needs the thin client" "$OUT/shim-noclient.err"; then
  note "a client option with no client to run it is refused, not dropped" "exit 2"
else
  bad "a client option with no client to run it is refused, not dropped" \
    "exit $noclient_rc, $(tr '\n' ' ' <"$OUT/shim-noclient.err" | cut -c1-70)"
fi

# ... and that the SAME base home without a client option still answers, so the
# check above is testing the option and not merely a broken component.
isabelle env ISABELLE_QUERY_BASE_HOME="$OUT/noclient" \
  ISABELLE_QUERY_CLIENT_CACHE="$OUT/noclient-cache.json" \
  timeout -k 10 120 bash "$SHIM_TOOL" -R "$AFP" summary \
  >"$OUT/shim-noclient2.txt" 2>"$OUT/shim-noclient2.err"
if cmp -s "$OUT/cold-summary.txt" "$OUT/shim-noclient2.txt"; then
  note "and with no client at all the query still answers, cold"
else
  bad "and with no client at all the query still answers, cold" \
    "$(tr '\n' ' ' <"$OUT/shim-noclient2.err" | cut -c1-70)"
fi

# --- 16g. an ACTION the routing has switched off is refused too
# The client is perfectly runnable here; it is the request that contradicts
# itself.  Found by this probe: the first spelling of the split let this fall
# through to the JVM with an empty argv, which printed the usage text and
# exited 0 -- an answer to a question nobody asked.
ISABELLE_QUERY_NO_SERVER=1 timeout -k 10 120 \
  isabelle query --client-status >"$OUT/shim-offact.txt" 2>"$OUT/shim-offact.err"
offact_rc=$?
if [ "$offact_rc" = "2" ] && [ ! -s "$OUT/shim-offact.txt" ] &&
   grep -q "switched off for this invocation" "$OUT/shim-offact.err"; then
  note "a client action with the warm path switched off is refused" "exit 2"
else
  bad "a client action with the warm path switched off is refused" \
    "exit $offact_rc, $(tr '\n' ' ' <"$OUT/shim-offact.err" | cut -c1-70)"
fi

# ... and a NON-action client option in the same position is moot, not fatal:
# the query still has to be answered.
ISABELLE_QUERY_NO_SERVER=1 timeout -k 10 120 \
  isabelle query --client-verbose -R "$AFP" summary \
  >"$OUT/shim-offopt.txt" 2>"$OUT/shim-offopt.err"
if cmp -s "$OUT/cold-summary.txt" "$OUT/shim-offopt.txt"; then
  note "and a non-action client option there is moot, not fatal"
else
  bad "and a non-action client option there is moot, not fatal" \
    "$(tr '\n' ' ' <"$OUT/shim-offopt.err" | cut -c1-70)"
fi

# --------------------------------------------------------------------------
echo
echo "17. the cold path's caches (P8)"

# The cold path gained two caches: a timestamp check that skips `scala_build`
# when nothing under the component is newer than its jar, and an AppCDS archive
# that memory-maps the Isabelle/Scala classes instead of re-reading 53 jars.
# Both are caches of derived things, so the ONLY property that matters here is
# that neither can change an answer.
#
# The archive is regenerated whenever the jar is newer than it -- §12 has
# already touched the jar by this point, so reaching here with a usable archive
# also demonstrates the regeneration.

CDS="$(isabelle getenv -b ISABELLE_HOME_USER)/isabelle-query/query-cds.jsa"

# The reference: the same query with the cache switched off entirely.
env -u ISABELLE_QUERY_NO_SERVER ISABELLE_QUERY_NO_CDS=1 \
  isabelle query --no-server -R "$AFP" summary \
  >"$OUT/cds-off.txt" 2>"$OUT/cds-off.err"
cds_off_rc=$?

# ... and with it on, which also builds it if §12's touch invalidated it.
env -u ISABELLE_QUERY_NO_SERVER isabelle query --no-server -R "$AFP" summary \
  >"$OUT/cds-on.txt" 2>"$OUT/cds-on.err"
cds_on_rc=$?

if [ -s "$CDS" ]; then
  note "an AppCDS archive is generated on the cold path" \
    "$(( $(wc -c <"$CDS") / 1048576 )) MB"
else
  bad "an AppCDS archive is generated on the cold path" "no archive at $CDS"
fi

if [ "$cds_off_rc" = "$cds_on_rc" ] &&
   cmp -s "$OUT/cds-off.txt" "$OUT/cds-on.txt" &&
   cmp -s "$OUT/cds-off.err" "$OUT/cds-on.err" &&
   [ -s "$OUT/cds-on.txt" ]; then
  note "and the answer is byte-identical with it and without it" \
    "$(wc -c <"$OUT/cds-on.txt") bytes, exit $cds_on_rc"
else
  bad "and the answer is byte-identical with it and without it" \
    "exit $cds_off_rc vs $cds_on_rc"
fi

# --- 17a. THE REGRESSION TEST: a damaged archive must not reach stdout ------
#
# The JVM writes unified logging to STDOUT, so an unreadable archive prints
#   [0.000s][warning][cds] Unable to read generic CDS file map header ...
# ahead of the first line of the answer.  `-Xlog:disable` in the shim is what
# stops it, and this is the check that keeps that flag from being tidied away.
# Three shapes of damage, because they fail at different points in the JVM's
# validation: a byte flipped inside, a truncated file, and an empty one.
# The JVM writes the archive READ-ONLY, so it cannot be damaged in place --
# an earlier spelling tried, `truncated` and `empty` silently did nothing, and
# two checks passed while testing a perfectly valid archive.  Hence: build the
# damaged file somewhere writable, move it into place, and ASSERT THE DAMAGE
# TOOK before drawing any conclusion from the run.
cds_damage() {  # label, command writing a damaged archive to $OUT/dmg.jsa
  cp "$CDS" "$CDS.bak" 2>/dev/null || return 1
  rm -f "$OUT/dmg.jsa"
  eval "$2"
  chmod u+w "$OUT/dmg.jsa" 2>/dev/null
  if cmp -s "$CDS.bak" "$OUT/dmg.jsa"; then
    bad "a $1 archive changes neither stdout nor stderr" \
      "the damage did not take -- this check would prove nothing"
    rm -f "$CDS.bak"
    return 0
  fi
  rm -f "$CDS"
  cp "$OUT/dmg.jsa" "$CDS"
  env -u ISABELLE_QUERY_NO_SERVER isabelle query --no-server -R "$AFP" summary \
    >"$OUT/cds-dmg.txt" 2>"$OUT/cds-dmg.err"
  local rc=$?
  local verdict=""
  cmp -s "$OUT/cds-off.txt" "$OUT/cds-dmg.txt" || verdict="stdout differs"
  cmp -s "$OUT/cds-off.err" "$OUT/cds-dmg.err" || verdict="$verdict stderr differs"
  [ "$rc" = "$cds_off_rc" ] || verdict="$verdict exit $rc"
  if [ -z "$verdict" ]; then
    note "a $1 archive changes neither stdout nor stderr" "exit $rc"
  else
    bad "a $1 archive changes neither stdout nor stderr" \
      "$verdict; first line: $(head -c 90 "$OUT/cds-dmg.txt")"
  fi
  rm -f "$CDS"
  mv -f "$CDS.bak" "$CDS"
}

if [ -s "$CDS" ]; then
  cds_damage "corrupted" \
    'cp "$CDS.bak" "$OUT/dmg.jsa"; chmod u+w "$OUT/dmg.jsa"
     printf GARBAGEGARBAGE | dd of="$OUT/dmg.jsa" bs=1 seek=200 conv=notrunc 2>/dev/null'
  cds_damage "truncated" 'head -c 5000 "$CDS.bak" >"$OUT/dmg.jsa"'
  cds_damage "empty"     ': >"$OUT/dmg.jsa"'
else
  bad "a damaged archive changes neither stdout nor stderr" "no archive to damage"
fi

# --- 17b. failability: the suppression is load-bearing ----------------------
#
# The three checks above are only worth anything if the corruption they look
# for is real.  Run the SAME damaged archive through the same JVM entry WITHOUT
# `-Xlog:disable`, and the warning must appear -- on stdout, ahead of the
# answer.  If this stops reproducing, the flag can go; until then it may not.
if [ -s "$CDS" ]; then
  : >"$OUT/cds-probe.jsa"
  # Read through `isabelle getenv`, not from this shell: the probe runs outside
  # the settings environment, and under `set -u` an unset
  # $ISABELLE_TOOL_JAVA_OPTIONS aborts the script -- which it did, taking §19
  # with it.
  eval "declare -a PROBE_JAVA_ARGS=($(isabelle getenv -b ISABELLE_TOOL_JAVA_OPTIONS 2>/dev/null))"
  isabelle java "${PROBE_JAVA_ARGS[@]}" \
    -Xshare:auto -XX:SharedArchiveFile="$OUT/cds-probe.jsa" \
    isabelle.query.Query_Main --no-server -R "$AFP" summary \
    >"$OUT/cds-nolog.txt" 2>"$OUT/cds-nolog.err"
  if grep -q 'warning..cds' "$OUT/cds-nolog.txt"; then
    note "and without -Xlog:disable the warning DOES land on stdout" \
      "$(head -c 52 "$OUT/cds-nolog.txt")"
  else
    bad "and without -Xlog:disable the warning DOES land on stdout" \
      "no warning seen -- the three checks above may be proving nothing"
  fi
fi

# --------------------------------------------------------------------------
echo
echo "18. the P15 contracts, served (0.9.4-0.9.7) [p15-s6]"

# What P15 changed in the ENGINE reaches a warm client only if the server's
# `CLI.run_result` keeps every part of it: the bytes on stdout, the status, and
# which of the two streams a diagnostic lands on.  The engine's own tests and
# the difftest see the cold process only, so each contract is asked twice here,
# cold (the export above) and served (`client`), and the two must agree byte
# for byte on stdout and on stderr (bar the settings shell's `### ` noise,
# which only a cold run prints) as well as on the exit status.  A client that
# DECLINED would exit 97 having written nothing, so an equal status is also
# the evidence that the answer was served and not merely reproduced cold.
#
# The expectations are hand-computed from Abstract_Completeness.thy, not read
# back from either run: the comparison alone would pass on two identical wrong
# answers.
p15() {
  local id="$1"; shift
  isabelle query -R "$AFP" "$@" >"$OUT/p15-$id.cold" 2>"$OUT/p15-$id.cold.raw"
  p15_cold_rc=$?
  client -R "$AFP" "$@" >"$OUT/p15-$id.warm" 2>"$OUT/p15-$id.warm.raw"
  p15_warm_rc=$?
  grep -v '^### ' "$OUT/p15-$id.cold.raw" >"$OUT/p15-$id.cold.err"
  grep -v '^### ' "$OUT/p15-$id.warm.raw" >"$OUT/p15-$id.warm.err"
  if [ "$p15_cold_rc" = "$p15_warm_rc" ] &&
     cmp -s "$OUT/p15-$id.cold" "$OUT/p15-$id.warm" &&
     cmp -s "$OUT/p15-$id.cold.err" "$OUT/p15-$id.warm.err"; then
    note "$id: served = cold (stdout, stderr, exit)" "exit $p15_warm_rc"
  else
    bad "$id: served = cold (stdout, stderr, exit)" \
      "cold $p15_cold_rc, warm $p15_warm_rc; $(diff "$OUT/p15-$id.cold" "$OUT/p15-$id.warm" | head -2 | tr '\n' ' ')"
  fi
}

# --- a batch with an unknown subject is all or nothing [subject-batch] ------
# `countable_R` and `NE_R` are lemmas (lines 53-54) that cite no other entry,
# so alone they print `0`, a blank line, `0` -- non-empty, which is what makes
# the empty stdout below the contract and not an empty answer.  `zzz_nope` and
# `qqq_nope` name nothing; both are reported, in argument order.
p15 batch-known callees countable_R NE_R -c
if [ "$(cat "$OUT/p15-batch-known.warm")" = "0

0" ]; then
  note "the known half alone answers 0, blank, 0 (non-vacuity)"
else
  bad "the known half alone answers 0, blank, 0 (non-vacuity)" \
    "$(od -An -c <"$OUT/p15-batch-known.warm" | tr -d ' \n')"
fi
p15 batch-unknown callees countable_R zzz_nope NE_R qqq_nope -c
if [ "$p15_warm_rc" = "1" ] && [ ! -s "$OUT/p15-batch-unknown.warm" ] &&
   [ "$(cat "$OUT/p15-batch-unknown.warm.err")" = "isabelle query: 'zzz_nope' is not in the entry index
isabelle query: 'qqq_nope' is not in the entry index" ]; then
  note "served: stdout empty, both unknowns on stderr in order, exit 1"
else
  bad "served: stdout empty, both unknowns on stderr in order, exit 1" \
    "rc $p15_warm_rc, $(wc -c <"$OUT/p15-batch-unknown.warm") bytes out, [$(tr '\n' ' ' <"$OUT/p15-batch-unknown.warm.err")]"
fi

# --- a bad regex in a batch is a usage error, and stdout stays empty --------
# `R` alone matches 35 entries (`find R` prints the first and `[+34 more`);
# `(` is an unclosed group.  The bad pattern comes SECOND, so a batch that
# streamed would already have written R's block.
p15 badre find R '('
if [ "$p15_warm_rc" = "2" ] && [ ! -s "$OUT/p15-badre.warm" ] &&
   grep -q "invalid regex '('" "$OUT/p15-badre.warm.err"; then
  note "served: a bad regex after a good one -- exit 2, stdout empty"
else
  bad "served: a bad regex after a good one -- exit 2, stdout empty" \
    "rc $p15_warm_rc, $(wc -c <"$OUT/p15-badre.warm") bytes out"
fi

# --- callers -f tsv [callers-tsv] -------------------------------------------
# One row per caller, seven tab-separated fields, every row naming its
# subject.  `callers R -c` is 23 (the plugin's own count, dev/p5probe.sh).
p15 tsv callers -f tsv R
tsv_rows=$(wc -l <"$OUT/p15-tsv.warm")
tsv_bad=$(awk -F'\t' 'NF != 7 || $1 != "R"' "$OUT/p15-tsv.warm" | wc -l)
if [ "$tsv_rows" = "23" ] && [ "$tsv_bad" = "0" ] &&
   grep -q "^R	countable_R	LEMMA	Abstract_Completeness	Abstract_Completeness	1	Abstract_Completeness:53\$" \
     "$OUT/p15-tsv.warm"; then
  note "served: 23 rows of seven fields, each led by its subject" "$tsv_rows rows"
else
  bad "served: 23 rows of seven fields, each led by its subject" \
    "$tsv_rows rows, $tsv_bad malformed"
fi
p15 tsv-count callers -c -f tsv R countable_R
if [ "$(cat "$OUT/p15-tsv-count.warm")" = "R	23
countable_R	0" ]; then
  note "served: -c -f tsv is one subject<TAB>count row per subject, no blocks"
else
  bad "served: -c -f tsv is one subject<TAB>count row per subject, no blocks" \
    "$(od -An -c <"$OUT/p15-tsv-count.warm" | tr -d ' \n' | cut -c1-60)"
fi

# --- show --premises [show-premises] -----------------------------------------
# lemma ipath_mkTree_saturated (line 332): four named assumptions over two
# `assumes` lines, one `shows`.
p15 premises show --premises ipath_mkTree_saturated
if [ "$(tail -n +2 "$OUT/p15-premises.warm")" = '  assms(1) s:   s \<in> S
  assms(2) rs:  fair rs
  assms(3) i:   ipath (mkTree rs s) steps
  assms(4) r:   r \<in> R
  shows: 1 conclusion' ]; then
  note "served: assms(1)..(4) keyed by label, then 1 conclusion"
else
  bad "served: assms(1)..(4) keyed by label, then 1 conclusion" \
    "$(tail -n +2 "$OUT/p15-premises.warm" | head -2 | tr '\n' ' ')"
fi

# --- a near name under --names: the note is a diagnostic [show-near-names] --
# No entry is named `Satur`; five contain it, case-insensitively: saturated,
# Saturated (lines 68-69), ipath_mkTree_saturated (332),
# ipath_mkTree_Saturated (352), epath_completeness_Saturated (357).  Under
# --names stdout is the five name lines and nothing else, so a pipe stays a
# list; the note goes to stderr.
p15 near-names show --names Satur
if [ "$(wc -l <"$OUT/p15-near-names.warm")" = "5" ] &&
   ! grep -q '^#' "$OUT/p15-near-names.warm" &&
   [ "$(cat "$OUT/p15-near-names.warm.err")" = "# no entry named 'Satur'; 5 whose name contains it:" ]; then
  note "served: five name lines on stdout, the note on stderr only"
else
  bad "served: five name lines on stdout, the note on stderr only" \
    "$(wc -l <"$OUT/p15-near-names.warm") lines, err [$(tr '\n' ' ' <"$OUT/p15-near-names.warm.err")]"
fi

# --------------------------------------------------------------------------
echo
echo "19. nothing is left running"

# Non-vacuity: there is a server to stop (§13 put it back), and it is
# `--client-stop` that stops it, not the stdin `cleanup` closes later.
up=$(pgrep -f "Query_Server_Main -n $SERVER\$" 2>/dev/null | wc -l)
client --client-stop >"$OUT/stop.txt" 2>&1
stop_rc=$?
gone "$SERVER"
left=$(pgrep -f "Query_Server_Main -n $SERVER\$" 2>/dev/null | wc -l)
if [ "$up" != "0" ] && [ "$stop_rc" = "0" ] && [ "$left" = "0" ]; then
  note "the probe's server is stopped" "--client-stop, rc $stop_rc"
else
  bad "the probe's server is stopped" \
    "$up up before, rc $stop_rc, $left process(es) left"
fi
unserve "$SERVER"
if isabelle server -l 2>/dev/null | grep -q "\"$SERVER\""; then
  bad "and it is out of the registry" "still listed"
else
  note "and it is out of the registry"
fi

# §15 started more, under names of its own.  A probe that leaves a resident
# JVM holding a corpus-sized index behind it is not a clean run.
left_shim=0
for s in "$SHIM_SERVER" "$DEAD_SERVER" "$ABSENT_SERVER"; do
  isabelle server -x -n "$s" >/dev/null 2>&1
  left_shim=$((left_shim + $(pgrep -f "Query_Server_Main -n $s\$" 2>/dev/null | wc -l)))
done
sleep 0.3
if [ "$left_shim" = "0" ] &&
   ! isabelle server -l 2>/dev/null | grep -q "p8probe-"; then
  note "and §15's servers are stopped and unregistered"
else
  bad "and §15's servers are stopped and unregistered" \
    "$left_shim process(es) left"
fi

echo
printf '%d checks: %d failing\n' "$checks" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "p7probe: FAILURES" >&2
  exit 1
fi
echo "P7PROBE SERVER OK"
exit 0
