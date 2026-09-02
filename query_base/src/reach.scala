/*  Title:      query_base/src/reach.scala

Import reachability: which declarations a citation site could possibly be
about.

A name in an Isabelle proof denotes something the theory can SEE, and a theory
sees exactly its own declarations plus those of its transitive `imports`.  The
scanners above this file work name-first — they find the token `rev` on a line
and ask which indexed entry is called `rev` — so over a corpus that declares one
word many times they attribute a citation to a declaration the citing theory
could not name.  Whole-AFP `callers rev` is the standard case: `List.rev`,
`Sorting_Algorithms.rev`, and a dozen entry-local `rev`s, all reported as uses
of one another.

This is the necessary condition that removes those, and only those:

    a site in theory T may be attributed to a declaration in theory D
    iff  D = T  or  D is in T's transitive import closure.

It is one-directional by construction — it can only DROP an attribution, never
invent one — so a site that still reaches several same-named declarations stays
multi-attributed, and a site whose name the project does not declare at all is
left alone (there is nothing to be visible or invisible).  Position WITHIN a
theory is not consulted: a citation above the declaration it names is still
attributed to it.  That refinement needs a per-line declaration order the
entry index already has, and is deliberately not built here.

Two deliberate approximations, both on the permissive side, because the filter
must never remove an attribution that could be real:

  * an import spelled as a PATH resolves by its leaf name
    (`import_candidates`) — and so, symmetrically, does a THEORY whose ROOT
    declares it by path (`theories "Nested/Nested_Fix"`), which is carried
    under that spelling and would otherwise be unreachable from any import
    that resolves by leaf.  `deps` / `uses` / `graph imports` take the same
    rule through `resolve_import`, narrowed to one candidate: a token the
    resolver cannot map is not a missing feature but a HOLE, and a hole
    prunes [import-leaf];
  * the graph is keyed by theory NAME, and where a corpus declares the same
    theory name twice (the AFP has many a `Misc`) the adjacency is the UNION of
    every section of that name.  `Usage.import_depths`, which `deps` / `refs`
    read, takes the last-wins section instead; the two agree except on such a
    duplicate, where this one reaches further.
  * a declaration is any entry or bound name of that spelling, whatever its
    tag.  A `rev` that is a LOCALE in one theory therefore keeps a `rev`
    citation alive there even though the citation graph's node is a lemma.

`ISABELLE_QUERY_REACHABILITY=off` restores the unfiltered, name-only
attribution — the semantics the Python reference implements, which is what
`dev/difftest.sh` compares against.  There is ONE default and every front end
gets it (CONTRIBUTING.md, "a configurable global that moves a measurement gets
ONE default"): the CLI, the warm server and a direct library or plugin caller
all read the switch through this object, and only the CLI ever writes it.
*/

package isabelle.query


import isabelle.*

import java.nio.file.Files

import scala.collection.mutable


object Reach {
  /* ------------------------------------------------------------------ */
  /* the switch                                                         */
  /* ------------------------------------------------------------------ */

  /* THE one default.  A library caller that never touches the CLI gets this,
     and so does the plugin; `CLI.configure_reachability` binds the same
     variable per request from `$ISABELLE_QUERY_REACHABILITY`, in BOTH
     directions, so a warm server cannot inherit one client's pin.  Late-bound
     and volatile for the same reason `Namespace`'s table is. */
  val DEFAULT_ENABLED: Boolean = true

  @volatile private var enabled_flag: Boolean = DEFAULT_ENABLED

  def enabled: Boolean = enabled_flag

  def set_enabled(b: Boolean): Unit = { enabled_flag = b }

  /* The one spelling that turns it off, matched case-insensitively; anything
     else — including an empty value — leaves the default alone.  A second
     spelling would be a second thing to get wrong in a harness. */
  def env_disables(value: Option[String]): Boolean =
    value.exists(v => Py.strip(v).toLowerCase == "off")


  /* ------------------------------------------------------------------ */
  /* import resolution                                                  */
  /* ------------------------------------------------------------------ */

  /* The last path segment of a theory name or an `imports` token — what
     `Thy_Header.import_name` keeps.  `"../BV/Altern"` and `"Altern"` denote
     the same theory to Isabelle, and so must here [import-leaf]. */
  def theory_leaf(name: String): String = {
    val s = name.replace('\\', '/')
    val i = s.lastIndexOf('/')
    if (i < 0) s else s.substring(i + 1)
  }

  /* `{leaf -> the theory names carrying a path}`, for resolving an import
     written BARE against a theory a ROOT spelled with a directory.

     Only names that are not already their own leaf go in, so this can never
     shadow a plain name — an exact match is tried first and always wins.  It
     stays small for that reason: 56 of `src/HOL`'s 1,451 theories.  Build it
     once per resolving loop; deriving it per call is a pass over every theory
     name, inside a BFS. */
  def leaf_index(known: Iterable[String]): Map[String, List[String]] = {
    val m = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[String]]
    for (name <- known) {
      val leaf = theory_leaf(name)
      if (leaf != name) m.getOrElseUpdate(leaf, new mutable.ListBuffer) += name
    }
    m.view.mapValues(_.toList).toMap
  }

  /* Every in-project theory an `imports` token could denote, most specific
     spelling first; empty when the token is external.

     `Discovery.thy_imports` returns tokens verbatim, but the section index is
     keyed by theory NAME.  Four spellings reach a loaded theory, tried in
     order:

       * bare, matching directly (`Substrate`);
       * session-qualified, by the tail after the last `.`
         (`Proj_Base.Substrate`);
       * PATH-SPELLED, by its leaf — `imports "../WFair"` in
         `HOL-UNITY`, `imports "variants/a_norreqid/A_Aodv_Loop_Freedom"` in
         AODV.  The `.` rule alone cannot map either, because it finds the `.`
         of `..` and yields `/WFair`;
       * bare against a path-spelled THEORY — a ROOT that says `theories
         "Simple/Reach"` gives the section that name, so a sibling's `imports
         Reach` matches nothing without `by_leaf`.

     The last two are the same rule seen from either end, and it is Isabelle's
     own (`Thy_Header.import_name` takes the last segment).  Without them the
     token is classified external — cosmetic for `deps`, but a HOLE in the
     visibility closure, which may only DROP and so deletes every citation
     across the edge in silence: 2,803 over `src/HOL` and 65,745 over the AFP
     [import-leaf].

     A genuinely external import (`HOL-Library.FuncSet`) names no in-project
     theory by any of the four, so the list is empty and the caller keeps the
     RAW token for the `[out-of-project]` line.  The leaf rules add no new way
     to mistake one for a local theory: they fire only when the token or a
     loaded name contains `/`, which an external session-qualified import never
     does.

     Several candidates come back only from the last rule, where two ROOTs
     spelled distinct theories with the same leaf.  A caller that must print
     ONE dependency takes `resolve_import`; the visibility closure takes them
     all, because it may only drop and a union is the safe side. */
  def import_candidates(imp: String, known: String => Boolean,
    by_leaf: Map[String, List[String]]
  ): List[String] =
    if (known(imp)) List(imp)
    else {
      val dot = imp.lastIndexOf('.')
      val tail = if (dot >= 0) imp.substring(dot + 1) else ""
      if (dot >= 0 && known(tail)) List(tail)
      else {
        val leaf0 = theory_leaf(imp)
        val d = leaf0.lastIndexOf('.')
        val leaf = if (d >= 0) leaf0.substring(d + 1) else leaf0
        if (leaf != imp && known(leaf)) List(leaf)
        else by_leaf.getOrElse(leaf, Nil).sorted
      }
    }

  /* The one in-project theory an `imports` token denotes, or `None`.

     `import_candidates` narrowed to a single answer for the verbs that print a
     dependency (`deps`, `uses`, `graph imports`), which need one edge per
     import clause rather than a set.  Where the token is genuinely ambiguous
     the first candidate in name order is taken, deterministically.

     THE one definition — `Usage.resolve_import` is this function with the
     membership test spelled as a map, because `deps` needs the section too. */
  def resolve_import(imp: String, known: String => Boolean,
    by_leaf: Map[String, List[String]]
  ): Option[String] = import_candidates(imp, known, by_leaf).headOption


  /* ------------------------------------------------------------------ */
  /* the closure                                                        */
  /* ------------------------------------------------------------------ */

  /* Theories are interned to dense integer ids and each theory's closure is
     ONE `java.util.BitSet` row over them.  The alternative — a `Set[String]`
     per theory — is what the whole-AFP number rules out: 10,262 theories at an
     average closure of a few hundred names is hundreds of megabytes of boxed
     strings, against 10,262 x 10,262 bits = 13 MB here, flat and independent of
     how deep the import chains run. */
  final class Closure private[Reach] (
    private val ids: Map[String, Int],
    private val rows: Array[java.util.BitSet],
    private val unreadable: java.util.BitSet
  ) {
    def theories: Int = rows.length

    /* -1 for a theory this closure does not know, which is how an unknown
       theory ends up constraining nothing. */
    def id(theory: String): Int = ids.getOrElse(theory, -1)

    /* AN UNREADABLE HEADER MEANS "UNKNOWN", NEVER "IMPORTS NOTHING".
       `Discovery.thy_imports` answers `Nil` both for a theory that imports
       nothing and for a file it cannot read, and through it the two are the
       same answer — which they must not be.  A section parsed from a BUFFER
       (the plugin hands the engine one for an unsaved file) would otherwise
       silently lose every cross-theory edge it has.  For a rule justified by
       "it can only drop", degrading to "sees nothing" is the one direction
       that breaks the justification, so a walk that met an unreadable header
       anywhere declines to filter at all.  Transitive, because the walk is:
       the row already holds everything reachable, so one BitSet intersection
       answers it. */
    private def unknown(i: Int): Boolean = rows(i).intersects(unreadable)

    /* What a site in `theory` may be attributed to.  An UNKNOWN theory admits
       everything: the filter may only remove an attribution it can positively
       rule out, and about a theory it has never seen it can rule out nothing. */
    def visible_from(theory: String): Int => Boolean = {
      val i = id(theory)
      if (i < 0 || unknown(i)) (_ => true)
      else {
        val row = rows(i)
        (j: Int) => j >= 0 && row.get(j)
      }
    }

    def visible(from: String, to: String): Boolean = visible_from(from)(id(to))

    /* Total closure size, for the memory / cost note in dev/P7C-STATUS.md. */
    def total_edges: Long = rows.foldLeft(0L)(_ + _.cardinality())
  }

  private def build(sections: List[Theory_Section]): Closure = {
    val ids = mutable.LinkedHashMap.empty[String, Int]
    for (sec <- sections) if (!ids.contains(sec.theory)) ids(sec.theory) = ids.size
    val id_map = ids.toMap
    val n = ids.size

    /* The OTHER half of the leaf rule, and the half a leaf taken off the
       IMPORT cannot reach on its own.  A ROOT may declare a theory by PATH —

           theories "Nested/Nested_Fix"

       — and both this engine and the reference then carry it under that
       spelling, `Nested/Nested_Fix`, which is not what Isabelle calls the
       theory (`Thy_Header.import_name` takes the last segment, and so does
       `Sessions`' own `global_theories` check).  A site-bearing theory that
       imports it across a directory writes `imports "../Nested/Nested_Fix"`,
       whose leaf is `Nested_Fix` — and a leaf tested against a set of
       PREFIXED names misses.  That is a hole, and a hole prunes silently:
       `codeqs quad` answered 2 where the source has 3.

       `leaf_index` is that half, and the closure takes EVERY candidate the
       resolver offers rather than the first: this may only WIDEN, and a
       closure that is too large is merely weak where one that is too small
       deletes real citations.  On a corpus whose ROOTs declare no
       path-qualified theory — four of the seven difftest corpora — the index
       is empty and this costs nothing.

       The theory NAME is deliberately left alone: correcting it is a
       `Discovery` change that would move `Locale_Test/Locale_Test` (FOL),
       `LK/Propositional` (Sequents) and `ex/Typechecking` (CTT) off byte
       parity with the reference, which spells them the same way.  See
       `todo.md`, `[theory-name-leaf]`. */
    val by_leaf = leaf_index(ids.keys)

    /* One file read and one header parse per section, and over the whole AFP
       there are ten thousand of them — the only part of this that touches the
       disk, so it is the only part worth parallelising.  The READABILITY test
       has to happen here, beside the read: `Discovery.thy_imports` answers
       `Nil` for a file it cannot open, so downstream "imports nothing" and
       "cannot be read" are indistinguishable, and they mean opposite things to
       a filter that may only drop (`Closure.unknown`). */
    val headers = Par_List.map(
      (sec: Theory_Section) =>
        (Files.isRegularFile(sec.path), Discovery.thy_imports(sec.path)),
      sections)

    /* Per THEORY NAME, not per section, and any unreadable section poisons the
       name: the adjacency is the union of every section of that name, so a
       union missing one section's imports is a closure that is too SMALL. */
    val unreadable = new java.util.BitSet(n)
    val kids = Array.fill(n)(mutable.LinkedHashSet.empty[Int])
    for ((sec, (readable, imps)) <- sections.zip(headers)) {
      val src = id_map(sec.theory)
      if (!readable) unreadable.set(src)
      for (imp <- imps; name <- import_candidates(imp, id_map.contains, by_leaf)) {
        val dst = id_map(name)
        if (dst != src) kids(src) += dst
      }
    }
    val child = Array.tabulate(n)(i => kids(i).toArray)

    /* reach(v) = {v} + the union of reach over v's imports, by ITERATIVE
       post-order DFS: the recursion depth is the import chain, and AFP entries
       run hundreds deep. */
    val rows = Array.fill(n)(new java.util.BitSet(n))
    val state = new Array[Byte](n)              // 0 unseen, 1 on the stack, 2 done
    val node = new Array[Int](n + 1)
    val next = new Array[Int](n + 1)
    var cyclic = false
    var root = 0
    while (root < n) {
      if (state(root) == 0) {
        var sp = 0
        node(0) = root
        next(0) = 0
        state(root) = 1
        while (sp >= 0) {
          val v = node(sp)
          val k = next(sp)
          if (k < child(v).length) {
            next(sp) = k + 1
            val c = child(v)(k)
            if (state(c) == 0) { sp += 1; node(sp) = c; next(sp) = 0; state(c) = 1 }
            else if (state(c) == 1) cyclic = true
          }
          else {
            val row = rows(v)
            row.set(v)
            for (c <- child(v)) row.or(rows(c))
            state(v) = 2
            sp -= 1
          }
        }
      }
      root += 1
    }

    /* A cycle in an `imports` graph is not a legal Isabelle project, but a
       half-written one is a real thing to be handed, and there the DFS folds in
       a row that was still being built.  Under-approximating would PRUNE, so
       the one case that could is repaired by fixed point rather than left. */
    if (cyclic) {
      var changed = true
      while (changed) {
        changed = false
        var v = 0
        while (v < n) {
          val row = rows(v)
          val before = row.cardinality()
          for (c <- child(v)) row.or(rows(c))
          if (row.cardinality() != before) changed = true
          v += 1
        }
      }
    }

    new Closure(id_map, rows, unreadable)
  }

  /* Cached per corpus, keyed by the IDENTITY of the section list: one load of a
     project produces one list, and the warm server and the plugin both hold
     theirs for the life of the index, so every verb of a session shares one
     closure.  The key is WEAK — a stale entry must not pin a re-indexed
     corpus's sources in memory, which for the plugin is the whole difference
     between a cache and a leak. */
  private var cache_key: java.lang.ref.WeakReference[AnyRef] = null
  private var cache_value: Closure = null

  def closure(sections: List[Theory_Section]): Closure = synchronized {
    val key = sections.asInstanceOf[AnyRef]
    if (cache_key == null || (cache_key.get ne key)) {
      cache_value = build(sections)
      cache_key = new java.lang.ref.WeakReference(key)
    }
    cache_value
  }

  /* Development / probe hook: drop the memo so a measurement of the closure
     cost measures the closure and not a hit. */
  def clear_cache(): Unit = synchronized { cache_key = null; cache_value = null }


  /* ------------------------------------------------------------------ */
  /* the filters                                                        */
  /* ------------------------------------------------------------------ */

  /* Every theory that declares `name`, as an entry or as a name one binds — a
     datatype constructor, a `shows` conjunct.  A bound name is a real
     declaration of that spelling, and `codeqs Cons` is precisely the verb that
     asks about one. */
  def declaring_theories(sections: List[Theory_Section], name: String): List[String] = {
    val out = new mutable.ListBuffer[String]
    for (sec <- sections)
      if (sec.entries.exists(e => e.name == name || e.bindings.exists(_._1 == name)) &&
        !out.contains(sec.theory)) out += sec.theory
    out.toList
  }

  /* Which theories a single-name scan (`callers`, `instances`, `codeqs`) may
     report a hit in.  Everything, when the filter is off — and equally when the
     project declares the name NOWHERE: `callers` answers for any token, and a
     token this project does not declare is a mention of something external,
     which no import closure has an opinion about.

     The undeclared case is tested FIRST, before the closure is asked for, and
     that ordering is the difference between a cheap verb and an expensive one:
     building the closure reads every theory header in the corpus, and
     `callers <some token>` — the plugin's commonest call, on whatever word is
     under the caret — is exactly the case that needs none of it. */
  def site_filter(sections: List[Theory_Section], name: String): String => Boolean =
    if (!enabled) (_ => true)
    else {
      val theories = declaring_theories(sections, name)
      if (theories.isEmpty) (_ => true)
      else {
        val c = closure(sections)
        val declared = theories.map(c.id).filter(_ >= 0)
        if (declared.isEmpty) (_ => true)
        else
          (theory: String) => {
            val visible = c.visible_from(theory)
            declared.exists(visible)
          }
      }
    }
}
