#!/usr/bin/env bash
#
# P11 S2 graph-scan equivalence and performance probe.
#
# Usage:
#   dev/p11graph.sh BASELINE_USER_HOME CURRENT_USER_HOME [CORPUS]
#
# Both homes must be isolated under this checkout's .dev directory or /tmp.
# The runner registers and builds only query_base from the read-only a60447e
# baseline archive and current checkout, so run it only after the orchestrator
# grants the shared build lane.  Set QUERY_P11_BASELINE to use another archive;
# it defaults to .dev/p11/baseline.  Parsing is excluded from the benchmark.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE_REPO="${QUERY_P11_BASELINE:-$REPO/.dev/p11/baseline}"
EXPECTED_BASELINE=a60447e4f457ac804ffb27c9dc26fa592c6a311c
ISABELLE_CMD="${ISABELLE_CMD:-isabelle}"

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: dev/p11graph.sh BASELINE_USER_HOME CURRENT_USER_HOME [CORPUS]" >&2
  exit 2
fi

BASELINE_HOME="$(cd "$1" 2>/dev/null && pwd)" || {
  echo "p11graph: baseline home does not exist: $1" >&2; exit 2;
}
CURRENT_HOME="$(cd "$2" 2>/dev/null && pwd)" || {
  echo "p11graph: current home does not exist: $2" >&2; exit 2;
}
CORPUS="${3:-${QUERY_TEST_AFP:-}/Abstract_Completeness}"

safe_home() {
  case "$1" in
    "$REPO/.dev"/*|/tmp/*) return 0 ;;
    *) return 1 ;;
  esac
}
if ! safe_home "$BASELINE_HOME" || ! safe_home "$CURRENT_HOME"; then
  echo "p11graph: both USER_HOME paths must be isolated under $REPO/.dev or /tmp" >&2
  exit 2
fi
if [ "$BASELINE_HOME" = "$CURRENT_HOME" ]; then
  echo "p11graph: baseline and current USER_HOME must be distinct" >&2
  exit 2
fi
if [ ! -d "$CORPUS" ]; then
  echo "p11graph: corpus is missing: $CORPUS" >&2
  echo "usage: dev/p11graph.sh BASELINE_USER_HOME CURRENT_USER_HOME [CORPUS]" >&2
  exit 2
fi
if [ ! -f "$BASELINE_REPO/query_base/src/usage_graph.scala" ]; then
  echo "p11graph: baseline archive is missing: $BASELINE_REPO" >&2
  exit 2
fi
if ! git -C "$REPO" cat-file -e "$EXPECTED_BASELINE^{commit}" 2>/dev/null; then
  echo "p11graph: expected baseline commit is unavailable: $EXPECTED_BASELINE" >&2
  exit 2
fi

# The baseline directory has no .git metadata.  Authenticate every tracked
# query_base file against the named commit instead of allowing git to walk up
# into the current checkout and report the wrong tree's HEAD.
BASELINE_BAD=""
while IFS= read -r path; do
  archived="$BASELINE_REPO/$path"
  if [ ! -f "$archived" ] ||
     ! cmp -s "$archived" <(git -C "$REPO" show "$EXPECTED_BASELINE:$path"); then
    BASELINE_BAD="$path"
    break
  fi
done < <(git -C "$REPO" ls-tree -r --name-only "$EXPECTED_BASELINE" -- query_base)
if [ -n "$BASELINE_BAD" ]; then
  echo "p11graph: baseline archive differs from $EXPECTED_BASELINE at $BASELINE_BAD" >&2
  exit 2
fi

OUT="$REPO/.dev/p11graph-out"
FIXTURE="$OUT/fixture"
rm -rf "$OUT"
mkdir -p "$FIXTURE" || exit 2

cleanup() { rm -rf "$OUT/classes-baseline" "$OUT/classes-current"; }
trap cleanup EXIT INT TERM

cat >"$FIXTURE/ROOT" <<'ROOT'
session P11_Graph_Fix = Pure +
  theories Graph_Fix
ROOT

cat >"$FIXTURE/Graph_Fix.thy" <<'THY'
theory Graph_Fix
  imports Pure
begin

definition base where "base = True"
definition base_def where "base_def = True"
definition made where "made = True"
definition repeat_target where "repeat_target = True"
definition sym\<^sub>x where "sym\<^sub>x = True"
definition simp where "simp = True"

lemma use_direct: True
  by (rule repeat_target repeat_target)

lemma use_derived: True
  by (rule made_def)

lemma use_collision: True
  by (rule base_def)

lemma use_symbolic: True
  by (rule sym\<^sub>x)

lemma shadow_method: True
  by simp

lemma shadow_fact: True
  using simp by simp

end
THY

run_isabelle() {
  local home=$1
  shift
  env USER_HOME="$home" "$ISABELLE_CMD" "$@"
}

build_and_run() {
  local label=$1
  local home=$2
  local tree=$3
  local classes="$OUT/classes-$label"
  local log="$OUT/$label.log"

  run_isabelle "$home" components -u "$tree/query_base" || return $?
  run_isabelle "$home" scala_build || return $?
  mkdir -p "$classes" || return 2
  local cp
  cp="$(run_isabelle "$home" getenv -b ISABELLE_SETUP_CLASSPATH):$(run_isabelle "$home" getenv -b ISABELLE_CLASSPATH)"
  run_isabelle "$home" scalac -d "$classes" -classpath "$cp" \
    "$REPO/dev/p11graph.scala" || return $?
  env USER_HOME="$home" CLASSPATH="$classes" "$ISABELLE_CMD" java \
    isabelle.query_dev.P11_Graph_Probe "$CORPUS" "$FIXTURE" \
    "${P11GRAPH_WARMUPS:-2}" "${P11GRAPH_REPS:-5}" >"$log" 2>&1 || {
      cat "$log"; return 1;
    }
  cat "$log"
}

echo "baseline: $EXPECTED_BASELINE"
echo "corpus:   $CORPUS"
echo
echo "== baseline =="
build_and_run baseline "$BASELINE_HOME" "$BASELINE_REPO" || exit $?
echo
echo "== current =="
build_and_run current "$CURRENT_HOME" "$REPO" || exit $?

grep '^EQUIV ' "$OUT/baseline.log" >"$OUT/baseline.equiv"
grep '^EQUIV ' "$OUT/current.log" >"$OUT/current.equiv"
if ! cmp -s "$OUT/baseline.equiv" "$OUT/current.equiv"; then
  echo "p11graph: baseline/current graph digests differ" >&2
  diff -u "$OUT/baseline.equiv" "$OUT/current.equiv" >&2
  exit 1
fi
echo
echo "EQUIVALENCE_OK four whole-graph modes match baseline"

for mode in false true; do
  BASE_ROW="$(grep "^BENCH derived=$mode " "$OUT/baseline.log")"
  CURRENT_ROW="$(grep "^BENCH derived=$mode " "$OUT/current.log")"
  BASE_MS="$(printf '%s\n' "$BASE_ROW" | sed -n 's/.*median_ms=\([^ ]*\).*/\1/p')"
  CURRENT_MS="$(printf '%s\n' "$CURRENT_ROW" | sed -n 's/.*median_ms=\([^ ]*\).*/\1/p')"
  BASE_ALLOC="$(printf '%s\n' "$BASE_ROW" | sed -n 's/.*median_alloc_bytes=\([^ ]*\).*/\1/p')"
  CURRENT_ALLOC="$(printf '%s\n' "$CURRENT_ROW" | sed -n 's/.*median_alloc_bytes=\([^ ]*\).*/\1/p')"
  if [ -z "$BASE_MS" ] || [ -z "$CURRENT_MS" ] || \
     [ -z "$BASE_ALLOC" ] || [ -z "$CURRENT_ALLOC" ]; then
    echo "p11graph: missing benchmark fields for derived=$mode" >&2
    exit 1
  fi
  awk -v mode="$mode" -v bt="$BASE_MS" -v nt="$CURRENT_MS" \
      -v ba="$BASE_ALLOC" -v na="$CURRENT_ALLOC" '
    BEGIN {
      printf "PERFORMANCE derived=%s baseline_ms=%s current_ms=%s speedup=%.3fx baseline_alloc=%s current_alloc=%s alloc_ratio=%.3f\n", \
        mode, bt, nt, bt / nt, ba, na, na / ba
    }'
done
echo "P11GRAPH OK"
