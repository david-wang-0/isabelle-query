/*  Title:      jedit_query/src/query_plugin.scala

Plugin lifecycle.

Deliberately thin.  The plugin owns no PIDE state and installs no session
consumers: the index is built from FILES and buffers, not from the document
model, which is what lets a query answer before the prover is ready.  What it
does own is the worker thread and the parse cache, and both have to be released
when jEdit unloads the plugin.

`.activate=startup` fires before the Isabelle session exists, so nothing here
may touch `PIDE.session`; the jump path checks for the plugin instead of
assuming it (`Query_Editor`).

Buffers are watched for one thing only: a save invalidates nothing, because the
index is keyed by mtime and size and will notice by itself, but a CLOSED buffer
must stop being overlaid — and that happens for free, since the overlay is
recollected from the live buffer list on every query.  The one message worth
handling is plugin shutdown.
*/

package isabelle.jedit_query


import org.gjt.sp.jedit.{EBMessage, EBPlugin}


class Query_Plugin extends EBPlugin {
  override def handleMessage(message: EBMessage): Unit = {}

  override def start(): Unit = { Query_Plugin._instance = Some(this) }

  override def stop(): Unit = {
    Query_Plugin._instance = None
    Query_Index.forget_all()
  }
}


object Query_Plugin {
  @volatile private var _instance: Option[Query_Plugin] = None
  def instance: Option[Query_Plugin] = _instance
}
