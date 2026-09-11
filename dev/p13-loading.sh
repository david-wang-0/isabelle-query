#!/usr/bin/env bash
# Compile only this probe against an already-built isolated engine.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:?select the parent-coordinated isolated engine USER_HOME}"
export USER_HOME
JAR="$(isabelle getenv -b ISABELLE_QUERY_JAR)"
[[ -f "$JAR" ]] || { echo 'p13-loading: isolated engine jar missing' >&2; exit 2; }
WORK="$(mktemp -d "${TMPDIR:-/tmp}/p13-loading.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/classes"
CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
timeout --kill-after=10s 120s isabelle scalac -d "$WORK/classes" -classpath "$CP" "$REPO/dev/p13-loading.scala"
timeout --kill-after=10s 90s env CLASSPATH="$WORK/classes" isabelle java \
  isabelle.query.P13_Loading "$WORK/fixture"
set +e
timeout --kill-after=10s 30s env CLASSPATH="$WORK/classes" isabelle java \
  isabelle.query.P13_Loading --fail >"$WORK/fail.log" 2>&1
STATUS=$?
set -e
if [[ $STATUS != 1 ]] || ! rg -q 'deliberate failability marker' "$WORK/fail.log"; then
  cat "$WORK/fail.log" >&2
  echo "p13-loading: failability run failed to fail as expected ($STATUS)" >&2
  exit 1
fi
echo 'P13 LOADING FAILABILITY OK'
