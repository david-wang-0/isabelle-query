#!/usr/bin/env bash
# Focused production Swing-model regression; use an isolated Isabelle home.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/p13-pane.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
export USER_HOME="$OUT/home"
export ISABELLE_QUERY_NO_SERVER=1
mkdir -p "$USER_HOME" "$OUT/classes" "$OUT/components"
cp -a "$REPO/query_base" "$REPO/jedit_query" "$OUT/components/"
cp "$REPO/dev/p13-pane.scala" "$OUT/p13-pane.scala"
isabelle components -u "$OUT/components/query_base"
isabelle components -u "$OUT/components/jedit_query"
isabelle scala_build
CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH):$(isabelle getenv -b JEDIT_JARS)"
isabelle scalac -d "$OUT/classes" -classpath "$CP" "$OUT/p13-pane.scala"
CLASSPATH="$OUT/classes" isabelle java isabelle.jedit_query_dev.P13_Pane
if CLASSPATH="$OUT/classes" isabelle java isabelle.jedit_query_dev.P13_Pane --faildemo >"$OUT/faildemo.log" 2>&1; then
  echo "p13-pane: perturbed expectation unexpectedly passed" >&2
  exit 1
fi
grep -q 'assertion failed: stack refresh replaces one exact group' "$OUT/faildemo.log"
echo 'P13-PANE FAILABILITY OK'
