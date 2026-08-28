/*  Title:      jedit_query/src/query_options.scala

Where the plugin's settings come from, and what happens when one is wrong.

Two stores, in this precedence:

  1. the ISABELLE option (`jedit_query/etc/options`, read through
     `PIDE.options`) -- but only when it has been CHANGED from its own shipped
     default, which is exactly "someone said something on the Isabelle side";
  2. the JEDIT property (`isabelle-project-query.*`, defaults in
     `plugin.props`), which is what this plugin's own option pane writes;
  3. the built-in default compiled in here.

The jEdit property is the live store and the reason the order is not simply
"Isabelle wins": the panel must work before `PIDE._plugin` exists (P5's rule --
a right-click has to answer while the prover is still starting, or has never
been started), and at that moment the Isabelle options are unreadable.  Making
an *unchanged* Isabelle option silent is what keeps the two stores from
fighting: they ship identical defaults (dev/p6probe.sh checks that they do), so
before either is touched every layer agrees, and afterwards whichever one the
user actually edited is the one that speaks.

A value that is not legal is REPORTED, not silently swallowed: it goes to
jEdit's activity log and to the status line, once per distinct mistake, and
resolution falls through to the next store rather than jumping straight to the
built-in default.  A typo in a property used to mean "the default, silently",
which is indistinguishable from the setting having no effect.
*/

package isabelle.jedit_query


import isabelle.*
import isabelle.jedit.PIDE

import org.gjt.sp.jedit.jEdit
import org.gjt.sp.util.Log

import scala.jdk.CollectionConverters.*


object Query_Options {
  /* Every jEdit property this plugin owns starts here.  Spelt out rather than
     derived from `Query_Dockable.NAME`, so that reading a property does not
     drag the Swing half of the plugin into class initialisation; that the two
     agree is a probe check, not a compile-time coupling. */
  val PREFIX: String = "isabelle-project-query."

  /* `isabelle-project-query.gesture.double-click` <-> Isabelle option
     `jedit_query_gesture_double_click`.  Isabelle option names are
     identifiers, so the separators collapse to `_`. */
  def option_name(name: String): String =
    "jedit_query_" + name.replace('-', '_').replace('.', '_')

  def property_name(name: String): String = PREFIX + name


  /* ------------------------------------------------------------------ */
  /* the warning channel                                                */
  /* ------------------------------------------------------------------ */

  /* Bounded, and deduplicated by the exact mistake: `of_click` runs on every
     mouse press, and a misspelt gesture must not fill the log with one line
     per click. */
  private val LOG_LIMIT = 64
  private val seen = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]
  private val log = new java.util.concurrent.ConcurrentLinkedQueue[String]

  def warnings: List[String] = log.iterator.asScala.toList

  def forget_warnings(): Unit = { seen.clear(); log.clear() }

  def warn(key: String, message: String): Unit = {
    if (seen.putIfAbsent(key, java.lang.Boolean.TRUE) == null) {
      log.add(message)
      while (log.size > LOG_LIMIT) log.poll()
      try Log.log(Log.WARNING, this, "Project Query: " + message)
      catch { case _: Throwable => () }
      status(message)
    }
  }

  /* The status line of whichever view is in front.  Best effort by design:
     there may be no view at all (start-up, or a headless probe), and a setting
     that cannot be reported is still a setting that has been logged. */
  def status(message: String): Unit =
    try {
      val view = jEdit.getActiveView
      if (view != null)
        GUI_Thread.later {
          val bar = view.getStatus
          if (bar != null) bar.setMessageAndClear("Project Query: " + message)
        }
    }
    catch { case _: Throwable => () }


  /* ------------------------------------------------------------------ */
  /* the two stores                                                     */
  /* ------------------------------------------------------------------ */

  def property(name: String): Option[String] =
    try Option(jEdit.getProperty(property_name(name))).map(_.trim).filter(_.nonEmpty)
    catch { case _: Throwable => None }

  def set_property(name: String, value: Option[String]): Unit =
    try {
      value match {
        case Some(v) => jEdit.setProperty(property_name(name), v)
        case None => jEdit.unsetProperty(property_name(name))
      }
    }
    catch { case _: Throwable => () }

  /* `(value, default)` of the Isabelle option, when the prover plugin is up
     and knows the name.  Everything here is wrapped: this is called from the
     EDT during a mouse press, and an option table that is half-initialised
     must degrade to "no opinion", never to an exception in a click handler. */
  def isabelle_option(name: String): Option[(String, String)] =
    try {
      for {
        plugin <- PIDE.get_plugin
        options <- Option(plugin.options)
        entry <- options.value.get(option_name(name))
        if !entry.unknown
      } yield (entry.value.trim, entry.default_value.trim)
    }
    catch { case _: Throwable => None }

  /* The candidate values in precedence order, each tagged with the store it
     came from so a warning can name it. */
  private def candidates(name: String): List[(String, String)] = {
    val isabelle_side =
      isabelle_option(name) match {
        case Some((value, default)) if value != default =>
          List(("Isabelle option " + option_name(name), value))
        case _ => Nil
      }
    isabelle_side ::: property(name).toList.map(v => ("jEdit property " + property_name(name), v))
  }


  /* ------------------------------------------------------------------ */
  /* typed resolution                                                   */
  /* ------------------------------------------------------------------ */

  /* One of a fixed set of names.  An illegal value warns and the NEXT store
     gets its turn, so a typo in an Isabelle option does not also discard a
     perfectly good jEdit property. */
  def choice(name: String, values: List[String], fallback: String): String = {
    val legal = values.toSet
    var result: Option[String] = None
    for ((source, value) <- candidates(name) if result.isEmpty) {
      if (legal(value)) result = Some(value)
      else
        warn(name + "=" + value,
          source + " = " + quote(value) + " is not one of " + values.mkString(", ") +
            " -- ignored")
    }
    result.getOrElse(fallback)
  }

  def integer(name: String, fallback: Int): Int = {
    var result: Option[Int] = None
    for ((source, value) <- candidates(name) if result.isEmpty) {
      Value.Int.unapply(value) match {
        case Some(n) => result = Some(n)
        case None =>
          warn(name + "=" + value,
            source + " = " + quote(value) + " is not an integer -- ignored")
      }
    }
    result.getOrElse(fallback)
  }
}
