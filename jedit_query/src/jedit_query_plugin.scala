/*  Title:      jedit_query/src/jedit_query_plugin.scala

Where the jEdit-plugin submodule lives.

`isabelle scala_build` builds COMPONENT modules only (the jars named by an
`etc/build.props`).  The thin shim that jEdit's own `PluginJAR` loader scans —
`$JEDIT_SETTINGS/jars/isabelle_jedit_query.jar` — is a *dynamic* module,
compiled by `isabelle.jedit.JEdit_Main` at start-up, which walks
`Scala_Project.plugins` and builds each one's context.  This class is how this
component joins that walk: it is registered as a classpath service in
`etc/build.props`, so `Isabelle_System.make_services` finds it.

It deliberately mentions no jEdit class, so loading it costs nothing on a
plain command-line `isabelle query` run, where every registered service class
is loaded when the classpath is initialised.
*/

package isabelle.jedit_query


import isabelle.*


class JEdit_Query_Plugin extends
  Scala_Project.Plugin(Path.explode("$JEDIT_QUERY_HOME/jedit_query_plugin"))
