"""isabelle_query — query the live theory index of an Isabelle/Isar project.

The `query` console command is :func:`isabelle_query.cli.main`; the shared
ROOT / session parsing lives in :mod:`isabelle_query.common`.  Both compute
results by parsing the project's ``.thy`` sources on every invocation,
so output is always in sync with the current theory tree.
"""

__version__ = "0.1.0"
