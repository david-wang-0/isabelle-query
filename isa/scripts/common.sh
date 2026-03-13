#!/bin/bash
# common.sh - Shared helpers for analysis scripts
#
# Source this file: source "$(dirname "$0")/common.sh"
#
# Provides functions to enumerate only the .thy files listed in ROOT,
# so WIP and orphaned files are automatically excluded from analysis.

# Parse ROOT and return theory names (one per line).
# Usage: get_root_theories [directory]
get_root_theories() {
    local dir="${1:-.}"
    local root_file="$dir/ROOT"

    [[ -f "$root_file" ]] || { echo "ERROR: no ROOT file in $dir" >&2; return 1; }

    awk '
        /^[[:space:]]*theories/ { in_theories = 1; next }
        /^[[:space:]]*(document_files|sessions|options|description)/ { in_theories = 0 }
        in_theories && /^[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/ {
            gsub(/^[[:space:]]+/, "")
            gsub(/[[:space:]]+$/, "")
            if ($0 != "") print $0
        }
    ' "$root_file"
}

# Return .thy file paths for theories listed in ROOT.
# Usage: get_build_files [directory]
get_build_files() {
    local dir="${1:-.}"

    get_root_theories "$dir" | while IFS= read -r name; do
        local thy_file="$dir/${name}.thy"
        [[ -f "$thy_file" ]] && echo "$thy_file"
    done
}
