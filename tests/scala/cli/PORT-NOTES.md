# CLI/discovery regression port

Every original CLI/discovery unittest ID is registered in `CliSuite` and mapped
in `tests/scala/coverage/cli.json`. Fixtures preserve source bytes and the tests
use request-local sessions, writers, roots, namespace values, and stdin hooks.
No Python oracle is executed by this suite.

## Documented differences

`dev/DIVERGENCES.md` D15 names a ROOT path spelling such as `Sub/Leaf` by its
Isabelle leaf name `Leaf`. Five `test_import_leaf` IDs assert the leaf identity,
path resolution and unchanged import reachability instead of requiring the
Python ROOT spelling. They retain explicit known-difference dispositions as
well as meaningful Scala assertions.

The parity contract in `PLAN.md` excludes byte-identical help text (also
recorded in `dev/P6B-STATUS.md`). Scala describes trailing paths as `FILES` and
uses its own concise positional help instead of Python argparse's `PATH`
metavar and shared gate-loop prose. Two `test_cli_families` IDs retain explicit
known-difference dispositions while checking every original command's trailing
path grammar and nonempty help. They are not counted as ordinary passing IDs.

## Retained external tiers

Python console-script names/packaging, import-surface inspection, and the Python
namespace heap/cache/dump mechanism remain Python-only. Their original tests
continue to describe those mechanisms; Scala uses compiled immutable namespace
tables. Namespace selection, fallback, statement classification, request
isolation and saved-source discovery remain Scala assertions.

The nine `test_closed_stdout` IDs remain process tests: real pipe capacity,
SIGPIPE timing and the original external-corpus producers cannot be proved by
in-process execution. They remain separate from the routine Scala pass count.
