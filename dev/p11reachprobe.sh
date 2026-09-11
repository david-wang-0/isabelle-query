#!/usr/bin/env bash
#
# dev/p11reachprobe.sh -- direct checks and alternating-project timing for the
# four-entry weak-identity Reach closure memo.
#
# This runner deliberately does not call scala_build.  The P11 orchestrator
# owns the shared build lane; run this only after the selected USER_HOME has a
# current query_base jar.
#
# Usage:
#   dev/p11reachprobe.sh
#   dev/p11reachprobe.sh --bench ROOT_A ROOT_B [REPETITIONS]

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME
: "${P11REACHPROBE_TIMEOUT_SECONDS:=300}"

if ! [[ "$P11REACHPROBE_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "p11reachprobe: P11REACHPROBE_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 2
fi
TIMEOUT="${P11REACHPROBE_TIMEOUT_SECONDS}s"

MODE="${1:-}"
if [ -n "$MODE" ] && [ "$MODE" != "--bench" ]; then
  echo "usage: dev/p11reachprobe.sh [--bench ROOT_A ROOT_B [REPETITIONS]]" >&2
  exit 2
fi
if [ "$MODE" = "--bench" ] && { [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; }; then
  echo "usage: dev/p11reachprobe.sh --bench ROOT_A ROOT_B [REPETITIONS]" >&2
  exit 2
fi

JAR="$(isabelle getenv -b ISABELLE_QUERY_JAR)"
if [ ! -f "$JAR" ]; then
  echo "p11reachprobe: query_base jar is missing for USER_HOME=$USER_HOME" >&2
  echo "p11reachprobe: wait for the orchestrator's shared scala_build" >&2
  exit 2
fi

CLASSES="$(mktemp -d "${TMPDIR:-/tmp}/p11reachprobe.XXXXXX")" || exit 2
trap 'rm -rf "$CLASSES"' EXIT INT TERM

CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p11reachprobe.scala" || exit $?

if [ "$MODE" = "--bench" ]; then
  shift
  timeout --foreground --kill-after=10s "$TIMEOUT" env CLASSPATH="$CLASSES" \
    isabelle java isabelle.query_dev.P11_Reach_Probe --bench "$@"
  exit $?
fi

LOG="$(mktemp "${TMPDIR:-/tmp}/p11reachprobe-log.XXXXXX")" || exit 2
trap 'rm -rf "$CLASSES"; rm -f "$LOG"' EXIT INT TERM

timeout --foreground --kill-after=10s "$TIMEOUT" env CLASSPATH="$CLASSES" \
  isabelle java isabelle.query_dev.P11_Reach_Probe 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"
if [ "$STATUS" -eq 124 ]; then
  echo "p11reachprobe: direct checks timed out after $TIMEOUT" >&2
  exit 1
fi
if [ "$STATUS" -ne 0 ] || ! grep -q '^P11REACHPROBE OK$' "$LOG"; then
  echo "p11reachprobe: direct checks failed (exit $STATUS)" >&2
  exit 1
fi

# Demonstrate that an intentionally perturbed identity-reuse expectation makes
# the same compiled probe fail.  A probe that has never failed is not a gate.
FAIL_LOG="$(mktemp "${TMPDIR:-/tmp}/p11reachprobe-fail-log.XXXXXX")" || exit 2
trap 'rm -rf "$CLASSES"; rm -f "$LOG" "$FAIL_LOG"' EXIT INT TERM
timeout --foreground --kill-after=10s "$TIMEOUT" \
  env P11REACHPROBE_FAILDEMO=1 CLASSPATH="$CLASSES" \
  isabelle java isabelle.query_dev.P11_Reach_Probe >"$FAIL_LOG" 2>&1
FAIL_STATUS="$?"
if [ "$FAIL_STATUS" -eq 124 ]; then
  echo "p11reachprobe: failability run timed out after $TIMEOUT" >&2
  exit 1
fi
if [ "$FAIL_STATUS" -ne 1 ] || \
    ! grep -q '^  FAIL  A survives an intervening B$' "$FAIL_LOG"; then
  echo "p11reachprobe: failability run did not produce the deliberate assertion failure (exit $FAIL_STATUS)" >&2
  exit 1
fi
echo "P11REACHPROBE FAILABILITY OK"
