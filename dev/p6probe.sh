#!/usr/bin/env bash
#
# dev/p6probe.sh -- headless checks for the P6 IDE features.
#
# Sibling of dev/p5probe.sh, which stays as it is: that one is P5's regression
# gate and must keep passing untouched.  This one covers what P6 added -- the
# fuzzy matcher, find-definition's rendered content, the peek popup's content,
# the gesture table's resolution and its invalid-value path, the index cap, and
# the plugin resources the option pane brought.
#
# There is no display here (no X server, no xvfb-run), so nothing Swing is
# exercised; that is the manual checklist in dev/P6-STATUS.md.
#
# Usage:
#   dev/p6probe.sh [HOL_CORPUS]
#
# The default comes from $QUERY_TEST_AFP, the same variable dev/difftest.sh
# uses; no path is hard-coded.  The scratch Isabelle user home is $USER_HOME,
# defaulting to the repository's own .dev -- never the real one.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

HOL="${1:-${QUERY_TEST_AFP:-}/Abstract_Completeness}"

if [ ! -d "$HOL" ]; then
  echo "usage: dev/p6probe.sh [HOL_CORPUS]  (or set \$QUERY_TEST_AFP)" >&2
  exit 2
fi

OUT="$REPO/.dev/p6probe-out"
rm -rf "$OUT"
mkdir -p "$OUT" || exit 2

export P6PROBE_HOL="$HOL"
export P6PROBE_OUT="$OUT"

echo "corpus: $HOL"
echo

isabelle scala_build || exit $?

# The shim jar jEdit loads is a dynamic module built by JEdit_Main at start-up,
# not by scala_build; section 7 reads its resources back out.  Build it or fail
# -- never skip and still report OK.
isabelle scala -e '{ isabelle.Isabelle_System.init();
  isabelle.Scala_Project.plugins.foreach(p => p.context().build()) }' || exit $?

SHIM="$(isabelle getenv -b JEDIT_SETTINGS)/jars/isabelle_jedit_query.jar"
if [ ! -f "$SHIM" ]; then
  echo "p6probe: the plugin shim jar was not built: $SHIM" >&2
  exit 1
fi

CLASSES="$REPO/.dev/p6probe-classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES" || exit 2

CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
CP="$CP:$(isabelle getenv -b JEDIT_JARS)"

isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p6probe.scala" || exit $?

LOG="$REPO/.dev/p6probe.log"
CLASSPATH="$CLASSES" isabelle java isabelle.jedit_query_dev.P6_Probe 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"

if ! grep -q "P6PROBE OK" "$LOG" || [ "$STATUS" -ne 0 ]; then
  echo "p6probe: FAILURES (exit $STATUS, log $LOG)" >&2
  exit 1
fi

# Close the loop against the CLI.  The panel's find-definition claims to show a
# declaration and its body; `isabelle query show NAME -V` is the gate-verified
# verb for the same question, and its output is a header line followed by the
# verbatim slice.  The panel stops at the BODY end, which is at or before the
# slice end, so its lines must be a prefix of the CLI's.
NAME="$(sed -n 's/^PROBE-DEF-NAME //p' "$LOG" | head -1)"
LINES="$(sed -n 's/^PROBE-DEF-LINES //p' "$LOG" | head -1)"

if [ -z "$NAME" ] || [ -z "$LINES" ]; then
  echo "p6probe: the probe named no definition subject" >&2
  exit 1
fi

isabelle query -R "$HOL" show "$NAME" -V 2>/dev/null | tail -n +2 | head -n "$LINES" \
  > "$OUT/cli-body.txt"

echo
if diff -u "$OUT/cli-body.txt" "$OUT/def-body.txt" > "$OUT/def-diff.txt"; then
  echo "cross-check: find definition of $NAME -- $LINES lines match isabelle query show -V"
  echo "P6PROBE CLI-PARITY OK"
  exit 0
else
  echo "p6probe: the panel and the CLI disagree about $NAME" >&2
  head -40 "$OUT/def-diff.txt" >&2
  exit 1
fi
