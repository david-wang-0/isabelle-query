#!/usr/bin/env bash
# Compile only the selected suites in a content-addressed private Isabelle home.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUITES=(parser graph shape cli)
if [[ $# -gt 0 ]]; then
  if [[ $# != 2 || $1 != --suite || ! $2 =~ ^(parser|graph|shape|cli)$ ]]; then
    echo 'usage: dev/scala-tests.sh [--suite parser|graph|shape|cli]' >&2; exit 2
  fi
  SUITES=("$2")
fi
exec python3 "$REPO/tests/scala/support/build.py" "$REPO" "${SUITES[@]}"
