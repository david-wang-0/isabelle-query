#!/usr/bin/env bash
#
# dev/p11mcp-probe.sh -- isolated P11 S5 component and direct-handler checks.
#
# This runner never registers into the real Isabelle user home.  Main supplies
# PIDE_MCP_COMPONENT as an isolated archive of the inspected external revision;
# the original external checkout is neither registered nor built here.

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 2

PIDE_MCP_COMPONENT="${PIDE_MCP_COMPONENT:-$REPO/.dev/p11/mcp-dependency}"
if [ ! -f "$PIDE_MCP_COMPONENT/etc/build.props" ] ||
   [ ! -f "$PIDE_MCP_COMPONENT/ISABELLE_VERSION" ]; then
  echo "p11mcp-probe: PIDE_MCP_COMPONENT must name main's isolated dependency archive" >&2
  echo "got: $PIDE_MCP_COMPONENT" >&2
  exit 2
fi

OUT="$(mktemp -d "${TMPDIR:-/tmp}/p11mcp-out.XXXXXX")" || exit 2
echo "p11mcp-probe: artifacts in $OUT"
ORDINARY_HOME="$OUT/ordinary-home"
OPTIONAL_HOME="$OUT/optional-home"
CLASSES="$OUT/classes"
FIX="$OUT/fixtures/Fix"
OTHER="$OUT/fixtures/Other"
EMPTY="$OUT/fixtures/Empty"
ALIAS="$OUT/fixtures/Fix-Alias"
MISSING="$OUT/fixtures/Missing"
FIFO="$OUT/transport-stdin"

mkdir -p "$ORDINARY_HOME" "$OPTIONAL_HOME" "$CLASSES" "$FIX" "$OTHER" "$EMPTY" || exit 2
ln -s "$FIX" "$ALIAS" || exit 2

# Build copies so no component jars or settings in the shared checkout change.
mkdir -p "$OUT/components" || exit 2
cp -a "$REPO/query_base" "$REPO/pide_mcp_query" "$OUT/components/" || exit 2
QUERY_COMPONENT="$OUT/components/query_base"
ADAPTER_COMPONENT="$OUT/components/pide_mcp_query"

cat >"$FIX/ROOT" <<'ROOT'
session P11_MCP_Fix = Pure +
  theories
    MCP_Fix
ROOT

cat >"$FIX/MCP_Fix.thy" <<'THY'
theory MCP_Fix
  imports Pure
begin

lemma alpha: "PROP P"
  sorry

lemma beta: "PROP P"
  using alpha
  sorry

(* EDIT_MARKER *)

end
THY

cat >"$OTHER/ROOT" <<'ROOT'
session P11_MCP_Other = Pure +
  theories
    MCP_Other
ROOT

cat >"$OTHER/MCP_Other.thy" <<'THY'
theory MCP_Other
  imports Pure
begin

lemma delta: "PROP P"
  sorry

end
THY

echo "1. ordinary component build without optional registration"
export USER_HOME="$ORDINARY_HOME"
isabelle components -u "$QUERY_COMPONENT" || exit $?
isabelle components -u "$PIDE_MCP_COMPONENT" || exit $?
isabelle scala_build || exit $?
ORDINARY_OPTIONAL_JAR="$(isabelle getenv -b ISABELLE_PIDE_MCP_QUERY_JAR)"
if [ -n "$ORDINARY_OPTIONAL_JAR" ]; then
  echo "p11mcp-probe: optional jar unexpectedly visible in ordinary user home" >&2
  exit 1
fi
ORDINARY_QUERY_TOOLS="$(isabelle scala -e '{
  isabelle.Isabelle_System.init()
  val n = isabelle.Isabelle_System.make_services(
    classOf[isabelle.pide.mcp.PIDE_MCP_Tools]).flatMap(_.entries).count(_.name == "query")
  println(n)
}')" || exit $?
if [ "$ORDINARY_QUERY_TOOLS" != "0" ]; then
  echo "p11mcp-probe: query service visible without optional registration: $ORDINARY_QUERY_TOOLS" >&2
  exit 1
fi

echo
echo "2. optional component build with isolated dependency archive"
export USER_HOME="$OPTIONAL_HOME"
isabelle components -u "$QUERY_COMPONENT" || exit $?
isabelle components -u "$PIDE_MCP_COMPONENT" || exit $?
isabelle components -u "$ADAPTER_COMPONENT" || exit $?
isabelle scala_build || exit $?

OPTIONAL_JAR="$(isabelle getenv -b ISABELLE_PIDE_MCP_QUERY_JAR)"
if [ ! -f "$OPTIONAL_JAR" ]; then
  echo "p11mcp-probe: optional component jar was not built: $OPTIONAL_JAR" >&2
  exit 1
fi

CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
isabelle scalac -d "$CLASSES" -classpath "$CP" "$REPO/dev/p11mcp-probe.scala" || exit $?

export P11MCP_FIX="$FIX"
export P11MCP_OTHER="$OTHER"
export P11MCP_EMPTY="$EMPTY"
export P11MCP_MISSING="$MISSING"
export P11MCP_ALIAS="$ALIAS"
export P11MCP_SOURCE="$FIX/MCP_Fix.thy"
export P11MCP_RELATIVE_SOURCE="$(realpath --relative-to="$REPO" "$FIX/MCP_Fix.thy")"

# Keep a writer open but send no bytes.  An accidental System.in read blocks;
# the bounded runner then fails.  No test replaces process-global System.in.
mkfifo "$FIFO" || exit 2
exec 9<>"$FIFO"

LOG="$OUT/mcp-probe.log"
mkdir -p "$(dirname "$LOG")" || exit 2

echo
echo "3. direct handler and service-discovery probe"
set +e
ISABELLE_QUERY_NAMESPACE=committed CLASSPATH="$CLASSES" \
  timeout 120s isabelle java isabelle.query.pide_mcp_dev.P11_MCP_Probe \
  <"$FIFO" 2>&1 | tee "$LOG"
STATUS="${PIPESTATUS[0]}"
set -e

if [ "$STATUS" -ne 0 ] || ! grep -q '^P11MCP OK:' "$LOG"; then
  echo "p11mcp-probe: direct probe failed or timed out (exit $STATUS)" >&2
  exit 1
fi

echo
echo "4. failability check"
FAIL_LOG="$OUT/faildemo.log"
set +e
P11MCP_FAILDEMO=1 ISABELLE_QUERY_NAMESPACE=committed CLASSPATH="$CLASSES" \
  timeout 120s isabelle java isabelle.query.pide_mcp_dev.P11_MCP_Probe \
  <"$FIFO" >"$FAIL_LOG" 2>&1
FAIL_STATUS="$?"
set -e

exec 9>&-

if [ "$FAIL_STATUS" -ne 1 ] ||
   ! grep -q '^  FAIL  deliberate failability' "$FAIL_LOG" ||
   ! grep -q '^P11MCP FAIL: 1 of ' "$FAIL_LOG"; then
  echo "p11mcp-probe: failability run did not fail for exactly the intended assertion" >&2
  sed -n '1,240p' "$FAIL_LOG" >&2
  exit 1
fi

echo "  ok    deliberate wrong expectation fails with exit 1"
echo
echo "P11MCP PROBE OK"
