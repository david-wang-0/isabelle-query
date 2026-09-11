#!/usr/bin/env bash
# Isolated P12 host/client integration. Run after core and client edits are ready.
# PIDE_MCP_COMPONENT must be an isolated archive, never a live checkout.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${P12_REUSE_OUT:-}" ]; then
  # Reuse only a prior isolated build; core/host source changes require a full run.
  OUT="$(cd "$P12_REUSE_OUT" && pwd)"
  [ -d "$OUT/classes" ] && [ -d "$OUT/home" ]
  for component in query_base jedit_query pide_mcp_query; do
    diff -qr "$REPO/$component/src" "$OUT/components/$component/src"
    cmp "$REPO/$component/etc/build.props" "$OUT/components/$component/etc/build.props"
  done
  cp "$REPO/query_base/lib/scripts/query_client.py" "$OUT/components/query_base/lib/scripts/"
  cp -a "$REPO/query_base/lib/Tools/." "$OUT/components/query_base/lib/Tools/"
  RUN_LOG="$OUT/probe-socket.log"
else
  : "${PIDE_MCP_COMPONENT:?supply an isolated PIDE MCP dependency archive}"
  [ -f "$PIDE_MCP_COMPONENT/etc/build.props" ]
  OUT="$(mktemp -d "${TMPDIR:-/tmp}/p12integration.XXXXXX")"
  mkdir -p "$OUT/components" "$OUT/home" "$OUT/classes" "$OUT/fixture"
  cp -a "$REPO/query_base" "$REPO/jedit_query" "$REPO/pide_mcp_query" "$OUT/components/"
  # Remove copied artifacts to verify all three components compile from source.
  rm -rf "$OUT/components/query_base/lib/classes" \
    "$OUT/components/jedit_query/lib/classes" "$OUT/components/pide_mcp_query/lib/classes"
  RUN_LOG="$OUT/probe.log"
fi
echo "P12 artifacts: $OUT"
export USER_HOME="$OUT/home"
export ISABELLE_QUERY_CLIENT_CACHE="$OUT/client-settings.json"
export ISABELLE_QUERY_NO_CDS=1
export P12_INTEGRATION_OUT="$OUT"
if [ -z "${P12_REUSE_OUT:-}" ]; then
  for component in query_base jedit_query; do
    timeout 30s isabelle components -u "$OUT/components/$component"
  done
  timeout 30s isabelle components -u "$PIDE_MCP_COMPONENT"
  timeout 30s isabelle components -u "$OUT/components/pide_mcp_query"
  timeout --kill-after=5s 180s isabelle scala_build
  CP="$(isabelle getenv -b ISABELLE_SETUP_CLASSPATH):$(isabelle getenv -b ISABELLE_CLASSPATH)"
  timeout 45s isabelle scalac -d "$OUT/classes" -classpath "$CP" "$REPO/dev/p12integration.scala"
fi
export P12_HOME_USER="$(isabelle getenv -b ISABELLE_HOME_USER)"
export P12_CLIENT="$OUT/components/query_base/lib/scripts/query_client.py"
export CLASSPATH="$OUT/classes"
# No theory is processed: this is only a saved-source query fixture.
cat > "$OUT/fixture/ROOT" <<'ROOT'
session P12_Fixture = Pure +
  theories Fixture
ROOT
cat > "$OUT/fixture/Fixture.thy" <<'THY'
theory Fixture imports Pure begin
lemma alpha: "PROP P" sorry
end
THY
python3 "$REPO/dev/p12integration.py" 2>&1 | tee "$RUN_LOG"
