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
        /^[[:space:]]*(document_files|sessions|options|description|directories)/ { in_theories = 0 }
        in_theories && /^[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/ {
            gsub(/^[[:space:]]+/, "")
            gsub(/[[:space:]]+$/, "")
            if ($0 != "") print $0
        }
    ' "$root_file"
}

# Parse ROOT and return theory sub-directories declared by the
# `directories "<sub>"` clause (one per line).  Empty if ROOT
# declares no subdirectories.
# Usage: get_root_directories [directory]
get_root_directories() {
    local dir="${1:-.}"
    local root_file="$dir/ROOT"

    [[ -f "$root_file" ]] || { echo "ERROR: no ROOT file in $dir" >&2; return 1; }

    awk '
        /^[[:space:]]*directories/ {
            in_dirs = 1
            sub(/^[[:space:]]*directories/, "")
        }
        /^[[:space:]]*(theories|document_files|sessions|options|description|chapter|session)/ {
            in_dirs = 0
        }
        in_dirs {
            line = $0
            while (match(line, /"[^"]+"/)) {
                name = substr(line, RSTART+1, RLENGTH-2)
                if (name != "") print name
                line = substr(line, RSTART+RLENGTH)
            }
        }
    ' "$root_file"
}

# Return .thy file paths for theories listed in ROOT.  Each
# theory name is searched first at the session root, then in
# each sub-directory declared by ROOT's `directories` clause.
# Usage: get_build_files [directory]
get_build_files() {
    local dir="${1:-.}"

    # Collect declared sub-directories once; empty is fine.
    local subdirs
    subdirs=$(get_root_directories "$dir")

    get_root_theories "$dir" | while IFS= read -r name; do
        local thy_file="$dir/${name}.thy"
        if [[ ! -f "$thy_file" ]] && [[ -n "$subdirs" ]]; then
            while IFS= read -r sub; do
                [[ -z "$sub" ]] && continue
                if [[ -f "$dir/$sub/${name}.thy" ]]; then
                    thy_file="$dir/$sub/${name}.thy"
                    break
                fi
            done <<< "$subdirs"
        fi
        [[ -f "$thy_file" ]] && echo "$thy_file"
    done
}
