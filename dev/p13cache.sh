#!/usr/bin/env bash
# Requires prebuilt copied component jars. Never compiles shared modules or
# starts a server, live jEdit or prover. The parent coordinates scala_build.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME
: "${P13CACHE_TIMEOUT_SECONDS:=180}"
[[ "$P13CACHE_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || exit 2
for var in ISABELLE_QUERY_JAR JEDIT_QUERY_JAR; do
  jar="$(isabelle getenv -b "$var")"
  if [[ ! -f "$jar" ]]; then
    echo "p13cache: missing $var; compile copied components first" >&2
    exit 2
  fi
done
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/p13cache.XXXXXX")"
trap 'rm -rf "$SCRATCH"' EXIT
mkdir "$SCRATCH/classes"
CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH):$(isabelle getenv -b JEDIT_JARS)"
timeout --foreground --kill-after=10s "${P13CACHE_TIMEOUT_SECONDS}s" \
  isabelle scalac -d "$SCRATCH/classes" -classpath "$CP" "$REPO/dev/p13cache.scala"
export CLASSPATH="$SCRATCH/classes${CLASSPATH:+:$CLASSPATH}"
export ISABELLE_QUERY_SERVER_CACHE_MB=128
timeout --foreground --kill-after=10s "${P13CACHE_TIMEOUT_SECONDS}s" \
  isabelle java isabelle.query_dev.P13_Cache "$SCRATCH/fixture"
set +e
P13CACHE_FAILDEMO=1 timeout --foreground --kill-after=10s "${P13CACHE_TIMEOUT_SECONDS}s" \
  isabelle java isabelle.query_dev.P13_Cache "$SCRATCH/fail" >"$SCRATCH/fail.log" 2>&1
status=$?
set -e
if [[ "$status" != 1 ]] || ! rg -q 'AssertionError: deliberate failability marker' "$SCRATCH/fail.log"; then
  cat "$SCRATCH/fail.log" >&2
  echo "p13cache: failability failed (exit $status)" >&2
  exit 1
fi
echo 'P13CACHE FAILABILITY OK'
