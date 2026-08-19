"""isabelle_query — query the live theory index of an Isabelle/Isar project.

The console command is :func:`isabelle_query.cli.main`, installed under two
names — ``query`` and ``isabelle-query`` — which are the same program and
report whichever was invoked (:func:`isabelle_query._prog.prog_name`).  The
shared ROOT / session / theory-header parsing is not query's own: it lives in
`isabelle-layout <https://pypi.org/project/isabelle-layout/>`_, this tool's one
runtime dependency, and is imported from there directly.  Results are computed
by parsing the project's ``.thy`` sources on every invocation, so output is
always in sync with the current theory tree.

The package version is single-sourced in ``pyproject.toml`` and read at
runtime from the installed distribution metadata (see
:func:`isabelle_query.cli._resolve_version`); there is deliberately no
``__version__`` literal here to drift from it.
"""
