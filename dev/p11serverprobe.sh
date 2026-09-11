#!/usr/bin/env bash
#
# dev/p11serverprobe.sh -- equivalence and timing for the fused fingerprint.
#
# This assumes the orchestrator has already completed the shared scala_build.
# It compiles only the probe into a private output directory and never starts a
# server, registers a component, builds a heap or touches the baseline archive.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$REPO/.dev/corpora.env" ]; then
  # shellcheck source=/dev/null
  source "$REPO/.dev/corpora.env"
fi

CORPUS="${1:-${QUERY_TEST_DISTRO:-}/HOL}"
REPETITIONS="${2:-9}"
if [ ! -d "$CORPUS" ]; then
  echo "usage: dev/p11serverprobe.sh [CORPUS [REPETITIONS]]" >&2
  echo "  (or set \$QUERY_TEST_DISTRO; the default corpus is its HOL directory)" >&2
  exit 2
fi
case "$REPETITIONS" in
  ''|*[!0-9]*|0)
    echo "p11serverprobe: REPETITIONS must be a positive integer" >&2
    exit 2
    ;;
esac

SCRATCH="$(mktemp -d "$REPO/.dev/p11serverprobe.XXXXXX")" || exit 2
trap 'rm -rf "$SCRATCH"' EXIT
FIXTURE="$SCRATCH/root"
EXTERNAL="$SCRATCH/external/External.thy"
mkdir -p "$FIXTURE/nested" "$SCRATCH/external" "$SCRATCH/link-target" || exit 2
printf 'theory Top imports Main begin end\n' >"$FIXTURE/Top.thy"
printf 'session P11_Top = HOL + theories Top\n' >"$FIXTURE/ROOT"
printf 'nested\n' >"$FIXTURE/ROOTS"
printf 'theory Nested imports Main begin end\n' >"$FIXTURE/nested/Nested.thy"
printf 'session P11_Nested = HOL + theories Nested\n' >"$FIXTURE/nested/ROOT"
printf 'deeper\n' >"$FIXTURE/nested/ROOTS"
printf 'theory External imports Main begin end\n' >"$EXTERNAL"
printf 'ignored\n' >"$FIXTURE/ignored.txt"
printf 'theory Hidden imports Main begin end\n' >"$SCRATCH/link-target/Hidden.thy"
ln -s "$SCRATCH/link-target" "$FIXTURE/linked" || exit 2

CLASSES="$REPO/.dev/p11serverprobe-classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES" || exit 2
CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p11serverprobe.scala" || exit $?

CLASSPATH="$CLASSES" isabelle java isabelle.query_dev.P11_Server_Probe \
  "$FIXTURE" "$EXTERNAL" "$CORPUS" "$REPETITIONS" || exit $?

# Failability: deliberately invert the corpus equality check.  The same probe
# must go red, otherwise a green run has not established that its assertions
# are live.
if P11SERVERPROBE_FAILDEMO=1 CLASSPATH="$CLASSES" \
    isabelle java isabelle.query_dev.P11_Server_Probe \
      "$FIXTURE" "$EXTERNAL" "$CORPUS" 1 >/dev/null 2>&1; then
  echo "p11serverprobe: failability run unexpectedly passed" >&2
  exit 1
fi
echo "P11SERVERPROBE FAILABILITY OK"
