#!/usr/bin/env bash
# Private copied component and home; no live/shared compilation or proof builds.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/p13-source.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
export USER_HOME="$WORK/home"
unset CLASSPATH ISABELLE_COMPONENTS ISABELLE_QUERY_JAR ISABELLE_QUERY_BASE_HOME
mkdir -p "$USER_HOME" "$WORK/classes"
cp -R "$REPO/query_base" "$WORK/query_base"
rm -rf "$WORK/query_base/lib/classes"
timeout --foreground --kill-after=10s 30s isabelle components -u "$WORK/query_base"
timeout --foreground --kill-after=10s 180s isabelle scala_build
CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
cp "$REPO/dev/p13-source/probe.scala" "$WORK/probe.scala"
timeout --foreground --kill-after=10s 120s isabelle scalac -d "$WORK/classes" -classpath "$CP" \
  "$WORK/probe.scala" "$REPO"/tests/scala/support/*.scala "$REPO"/tests/scala/parser/*.scala
if [[ $# -gt 0 ]]; then
  timeout --foreground --kill-after=10s 180s env CLASSPATH="$WORK/classes" \
    isabelle java -Xmx512m isabelle.query.P13_Source
fi
if [[ "${1:-}" == --retain-repl ]]; then
  [[ $# == 2 ]] || { echo 'usage: run.sh --retain-repl CORPUS' >&2; exit 2; }
  export P13_SOURCE_CORPUS="$2"
  timeout --foreground --kill-after=10s 180s env CLASSPATH="$WORK/classes" \
    isabelle scala "-J-Xmx${P13_SOURCE_HEAP:-512m}" -e \
    'isabelle.query.P13_Source.main(Array("--retain", sys.env("P13_SOURCE_CORPUS")))'
else
  timeout --foreground --kill-after=10s 180s env CLASSPATH="$WORK/classes" \
    isabelle java "-Xmx${P13_SOURCE_HEAP:-512m}" isabelle.query.P13_Source "$@"
fi
timeout --foreground --kill-after=10s 180s env CLASSPATH="$WORK/classes" \
  isabelle java -Xmx512m isabelle.query.regression.Runner "$REPO/tests/scala" parser
set +e
timeout --foreground --kill-after=10s 30s env CLASSPATH="$WORK/classes" \
  isabelle java -Xmx512m isabelle.query.P13_Source --fail >"$WORK/fail.log" 2>&1
STATUS=$?
set -e
[[ "$STATUS" == 1 ]] && rg -q 'deliberate P13 source failure' "$WORK/fail.log" || {
  cat "$WORK/fail.log" >&2; echo 'p13-source: failability check failed' >&2; exit 1;
}
echo 'P13 SOURCE FAILABILITY OK'
