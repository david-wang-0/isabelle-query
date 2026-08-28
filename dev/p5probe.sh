#!/usr/bin/env bash
#
# dev/p5probe.sh -- headless checks for the jEdit plugin's engine-facing layer.
#
# There is no display here (no X server, no xvfb-run), so the plugin's Swing
# half cannot be exercised in this repository.  Everything BELOW the Swing half
# can be, and this runs it: the caret-word grammar, project discovery from a
# file path, the warm index (cache, invalidation, dirty-buffer overlay), the
# usages search against the engine's own entry point, and the per-project
# namespace binding.
#
# Usage:
#   dev/p5probe.sh [HOL_CORPUS] [NON_HOL_CORPUS]
#
# Defaults come from $QUERY_TEST_AFP / $QUERY_TEST_DISTRO, the same variables
# dev/difftest.sh uses; no path is hard-coded.  The scratch Isabelle user home
# is $USER_HOME, defaulting to the repository's own .dev -- never the real one.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${USER_HOME:=$REPO/.dev}"
export USER_HOME

HOL="${1:-${QUERY_TEST_AFP:-}/Abstract_Completeness}"
NONHOL="${2:-${QUERY_TEST_DISTRO:-}/CTT}"

if [ ! -d "$HOL" ] || [ ! -d "$NONHOL" ]; then
  echo "usage: dev/p5probe.sh [HOL_CORPUS] [NON_HOL_CORPUS]" >&2
  echo "  (or set \$QUERY_TEST_AFP and \$QUERY_TEST_DISTRO)" >&2
  exit 2
fi

export P5PROBE_HOL="$HOL"
export P5PROBE_NONHOL="$NONHOL"

echo "HOL corpus:     $HOL"
echo "non-HOL corpus: $NONHOL"
echo

# The plugin's core jar must exist and be on the classpath.  `isabelle scalac`
# and `isabelle java` both merge $ISABELLE_SETUP_CLASSPATH, which lists every
# registered component's module, so a plain scala_build is all it takes.
isabelle scala_build || exit $?

# The jar jEdit's own PluginJAR loader scans is a DYNAMIC module: scala_build
# does not produce it, `isabelle.jedit.JEdit_Main` does, at start-up.  Build it
# here.  Section 7 of the probe reads its resources back out of it, and a check
# that quietly skips itself when its input is missing -- while the script still
# prints OK -- is not a check at all.
isabelle scala -e '{ isabelle.Isabelle_System.init();
  isabelle.Scala_Project.plugins.foreach(p => p.context().build()) }' || exit $?

SHIM="$(isabelle getenv -b JEDIT_SETTINGS)/jars/isabelle_jedit_query.jar"
if [ ! -f "$SHIM" ]; then
  echo "p5probe: the plugin shim jar was not built: $SHIM" >&2
  exit 1
fi

CLASSES="$REPO/.dev/p5probe-classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES" || exit 2

CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
CP="$CP:$(isabelle getenv -b JEDIT_JARS)"

isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p5probe.scala" || exit $?

LOG="$REPO/.dev/p5probe.log"
CLASSPATH="$CLASSES" isabelle java isabelle.jedit_query_dev.P5_Probe 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"

if ! grep -q "P5PROBE OK" "$LOG" || [ "$STATUS" -ne 0 ]; then
  echo "p5probe: FAILURES (exit $STATUS, log $LOG)" >&2
  exit 1
fi

# Close the loop against the CLI itself: the plugin path and `isabelle query
# callers` must agree on the same subject in the same corpus.  The CLI is the
# gate-verified side (dev/difftest.sh), so this is the parity check that
# matters -- the probe only proves the plugin agrees with the engine call it
# makes, this proves the whole path agrees with the shipped verb.
SUBJECT="$(sed -n 's/^PROBE-SUBJECT //p' "$LOG" | head -1)"
HITS="$(sed -n 's/^PROBE-HITS //p' "$LOG" | head -1)"
CLI_HITS="$(isabelle query -R "$HOL" callers "$SUBJECT" -c 2>/dev/null | tail -1)"

echo
echo "cross-check: callers $SUBJECT -- plugin $HITS, isabelle query $CLI_HITS"
if [ "$HITS" = "$CLI_HITS" ]; then
  echo "P5PROBE CLI-PARITY OK"
  exit 0
else
  echo "p5probe: the plugin and the CLI disagree on callers $SUBJECT" >&2
  exit 1
fi
