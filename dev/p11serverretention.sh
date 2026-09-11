#!/usr/bin/env bash
#
# dev/p11serverretention.sh -- deterministic P11 whole-index retention checks.
#
# The orchestrator owns scala_build. This runner requires the selected jar to
# exist, compiles only its probe in a private directory, and bounds every JVM.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME
: "${P11SERVERRETENTION_TIMEOUT_SECONDS:=300}"

if ! [[ "$P11SERVERRETENTION_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "p11serverretention: timeout must be a positive integer" >&2
  exit 2
fi
TIMEOUT="${P11SERVERRETENTION_TIMEOUT_SECONDS}s"

JAR="$(isabelle getenv -b ISABELLE_QUERY_JAR)"
if [ ! -f "$JAR" ]; then
  echo "p11serverretention: query_base jar is missing for USER_HOME=$USER_HOME" >&2
  echo "p11serverretention: wait for the orchestrator's shared scala_build" >&2
  exit 2
fi

CLASSES="$(mktemp -d "${TMPDIR:-/tmp}/p11serverretention-classes.XXXXXX")" || exit 2
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/p11serverretention-fixture.XXXXXX")" || exit 2
trap 'rm -rf "$CLASSES" "$SCRATCH"' EXIT INT TERM
CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p11serverretention.scala" || exit $?

run_mode() {
  local cache="$1"
  shift
  timeout --foreground --kill-after=10s "$TIMEOUT" \
    env ISABELLE_QUERY_SERVER_CACHE_MB="$cache" CLASSPATH="$CLASSES" \
    isabelle java isabelle.query_dev.P11_Server_Retention "$@"
  local status="$?"
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    echo "p11serverretention: $1 timed out after $TIMEOUT" >&2
    exit 1
  fi
  if [ "$status" -ne 0 ]; then
    echo "p11serverretention: $1 failed (exit $status)" >&2
    exit 1
  fi
}

DEFAULT_BYTES=$((128 * 1024 * 1024))
run_mode '' --config "$DEFAULT_BYTES"
run_mode 128 --config "$DEFAULT_BYTES"
run_mode invalid --config "$DEFAULT_BYTES"
run_mode -1 --config "$DEFAULT_BYTES"
# Parses as Long, then overflows when converted from MiB to bytes.
run_mode 8796093022208 --config "$DEFAULT_BYTES"
run_mode 128 --core "$SCRATCH/core"
run_mode 1 --lru "$SCRATCH/lru"
run_mode 0 --zero "$SCRATCH/zero"
run_mode 128 --metadata "$SCRATCH/metadata"

FAIL_LOG="$(mktemp "${TMPDIR:-/tmp}/p11serverretention-fail.XXXXXX")" || exit 2
trap 'rm -rf "$CLASSES" "$SCRATCH"; rm -f "$FAIL_LOG"' EXIT INT TERM
timeout --foreground --kill-after=10s "$TIMEOUT" \
  env ISABELLE_QUERY_SERVER_CACHE_MB=128 P11SERVERRETENTION_FAILDEMO=1 \
    CLASSPATH="$CLASSES" \
  isabelle java isabelle.query_dev.P11_Server_Retention \
    --config "$DEFAULT_BYTES" >"$FAIL_LOG" 2>&1
FAIL_STATUS="$?"
if [ "$FAIL_STATUS" -eq 124 ] || [ "$FAIL_STATUS" -eq 137 ]; then
  echo "p11serverretention: failability run timed out after $TIMEOUT" >&2
  exit 1
fi
if [ "$FAIL_STATUS" -ne 1 ] || \
    ! grep -q '^  FAIL  deliberate failability marker$' "$FAIL_LOG"; then
  echo "p11serverretention: failability run did not produce the intended assertion (exit $FAIL_STATUS)" >&2
  exit 1
fi
echo "P11SERVERRETENTION FAILABILITY OK"
