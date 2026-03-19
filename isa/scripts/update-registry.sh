#!/bin/sh
# Wrapper: delegates to update-registry.py
exec python3 "$(dirname "$0")/update-registry.py" "$@"
