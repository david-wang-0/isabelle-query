"""isabelle_query — query the live theory index of an Isabelle/Isar project.

The console command is :func:`isabelle_query.cli.main`, installed under two
names — ``query`` and ``isabelle-query`` — which are the same program and
report whichever was invoked (:func:`isabelle_query._prog.prog_name`).  The
shared ROOT / session / theory-header parsing is not query's own: it lives in
`isabelle-layout <https://pypi.org/project/isabelle-layout/>`_, this tool's one
runtime dependency, and is imported from there directly.  Results are computed
by parsing the project's ``.thy`` sources on every invocation, so output is
always in sync with the current theory tree.

The Isar span parsing is also importable, for tools that want spans rather
than a report: :mod:`isabelle_query.api` exports ``parse_theory``,
``parse_root``, :class:`~isabelle_query.model.Entry` and
:class:`~isabelle_query.model.TheorySection`, and those four follow the same
stability policy as the CLI.  Everything else in the package is internal.
Nothing is re-exported here on purpose — importing this package must stay free
of the parser, which ``_prog`` and the version lookup do not want.

The package version is single-sourced in ``pyproject.toml`` and read at
runtime from the installed distribution metadata (see
:func:`isabelle_query.cli._resolve_version`); there is deliberately no
``__version__`` literal here to drift from it.
"""
