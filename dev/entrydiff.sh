#!/usr/bin/env bash
# Differential check: the Scala engine's entry set and theory set against the
# Python oracle, byte for byte, over one or more corpora.
#
#   dev/entrydiff.sh CORPUS_DIR...
#   dev/entrydiff.sh                 # the standard corpus set, from the
#                                    # environment (see below)
#
# Corpora come from the environment, never from a path written down here:
#   $QUERY_TEST_AFP     an AFP `thys` directory
#   $QUERY_TEST_DISTRO  the Isabelle distribution's `src` directory
#   $QUERY_CORPORA      optional: whitespace-separated list, overriding the
#                       default selection below
#
# The oracle side is `dev/dump_oracle.py`, which runs the FROZEN reference in
# this checkout's `src/` — so unlike `dev/difftest.sh` there is no version to
# pin: the tree IS the oracle.  What it does borrow is an interpreter, for
# `isabelle_layout`, and $QUERY_ORACLE names which `query` to borrow it from
# (default: the bare `query` on PATH).  Same variable, same venv, both
# harnesses:
#
#   QUERY_ORACLE=.dev/oracle/bin/query dev/entrydiff.sh
#
# Exit status is non-zero if any corpus differs.  Diffs are left in
# $QUERY_DIFF_DIR (default: a mktemp dir, reported on stderr) so a failure can
# be inspected rather than re-run.
set -u

repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
oracle="$repo/dev/dump_oracle.py"
outdir=${QUERY_DIFF_DIR:-$(mktemp -d)}
mkdir -p "$outdir"

# The Scala side runs against the repo's own scratch Isabelle home, never the
# user's: a work-in-progress component must not be visible to a real session.
# `--no-server` is not decoration: the dump verbs route cold anyway (they write
# straight past any socket), so saying it here only makes the harness state
# what it already relies on -- and keeps a future dump-shaped verb honest.
run_scala() { USER_HOME="$repo/.dev" isabelle query --no-server "$@" 2>/dev/null; }
run_oracle() { "$oracle" "$@"; }

corpora=("$@")
if [ ${#corpora[@]} -eq 0 ]; then
  if [ -n "${QUERY_CORPORA:-}" ]; then
    read -r -a corpora <<<"$QUERY_CORPORA"
  else
    for d in "${QUERY_TEST_AFP:-}/Abstract_Completeness" \
             "${QUERY_TEST_AFP:-}/AODV" \
             "${QUERY_TEST_AFP:-}/Category3" \
             "${QUERY_TEST_DISTRO:-}/FOL" \
             "${QUERY_TEST_DISTRO:-}/ZF" \
             "${QUERY_TEST_DISTRO:-}/Sequents" \
             "${QUERY_TEST_DISTRO:-}/CTT"; do
      [ -d "$d" ] && corpora+=("$d")
    done
  fi
fi

if [ ${#corpora[@]} -eq 0 ]; then
  echo "entrydiff: no corpora (set \$QUERY_TEST_AFP / \$QUERY_TEST_DISTRO)" >&2
  exit 2
fi

status=0
for corpus in "${corpora[@]}"; do
  tag=$(echo "$corpus" | tr '/' '_' | sed 's/^_//')
  for variant in theories entries entries-spans entries-bindings; do
    case $variant in
      theories)         s=(dump-theories "$corpus");        o=(theories "$corpus") ;;
      entries)          s=(dump-entries "$corpus");         o=(entries "$corpus") ;;
      entries-spans)    s=(dump-entries "$corpus" --spans); o=(entries "$corpus" --spans) ;;
      entries-bindings) s=(dump-entries "$corpus" --bindings)
                        o=(entries "$corpus" --bindings) ;;
    esac
    run_scala "${s[@]}" >"$outdir/$tag.$variant.scala"
    run_oracle "${o[@]}" >"$outdir/$tag.$variant.oracle"
    if diff -u "$outdir/$tag.$variant.oracle" "$outdir/$tag.$variant.scala" \
        >"$outdir/$tag.$variant.diff"; then
      n=$(wc -l <"$outdir/$tag.$variant.scala")
      printf 'ok    %-16s %-16s (%s records)\n' "$variant" "$(basename "$corpus")" "$n"
      rm -f "$outdir/$tag.$variant.diff"
    else
      n=$(grep -c '^[-+][^-+]' "$outdir/$tag.$variant.diff")
      printf 'DIFF  %-16s %-16s (%s differing lines) %s\n' \
        "$variant" "$(basename "$corpus")" "$n" "$outdir/$tag.$variant.diff"
      status=1
    fi
  done
done

[ "$status" -ne 0 ] && echo "diffs under $outdir" >&2
exit "$status"
