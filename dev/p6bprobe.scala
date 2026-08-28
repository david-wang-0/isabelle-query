/*  Title:      dev/p6bprobe.scala

Headless probe for P6b -- find instantiations, find code equations.

These are the first verbs in this fork with NO Python counterpart, so there is
no oracle and no differential case: everything below is a hand-computed
expectation.  The fixture theories `Sites_Fix.thy` / `Code_Fix.thy` are written
by `dev/p6bprobe.sh` and every count here was worked out by reading them before
the code was run -- which is why the two bugs the fixtures caught (a qualifier
pattern that walked past a quoted locale name, an arity scan that walked past a
quoted sort) were caught at all.

Three layers:

  1. the parsers, as pure functions over strings -- the grammar pinned outright,
     including the spellings a corpus is full of and a first draft never
     handles (a quoted type constructor, a brace sort, a symbol-bearing name,
     a compound locale expression, an old-spelling `sublocale L < M`);
  2. the two engine scans over the fixtures, where the answer is a list of
     loci that was written down first;
  3. the plugin seam -- the two new `Result_Kind`s, their captions, the site
     tag on a leaf, the refusal path, and the resources (`actions.xml`,
     `plugin.props`) that a menu entry needs to exist.

The CLI cross-check and the exit-code pins are in the shell script, where the
process exit code is observable.
*/

package isabelle.jedit_query_dev


object P6B_Probe {
  def main(args: Array[String]): Unit = {
  import isabelle.*
  import isabelle.query.{Sites, Theory_Section}
  import isabelle.jedit_query.{Query_Dockable, Query_Index, Query_Name_Search, Query_Search}

  import java.nio.file.{Files, Paths}

  Isabelle_System.init()

  var failures = 0

  def check(name: String, ok: Boolean, detail: String = ""): Unit = {
    if (ok) println("  ok    " + name + (if (detail.isEmpty) "" else "  [" + detail + "]"))
    else { failures += 1; println("  FAIL  " + name + "  [" + detail + "]") }
  }

  val fix_root = Paths.get(System.getenv("P6BPROBE_FIX"))
  val out_dir = Paths.get(System.getenv("P6BPROBE_OUT"))

  /* One deliberately wrong expectation, on demand, so the harness can be shown
     to fail.  A probe that has never failed has not been tested. */
  val faildemo = System.getenv("P6BPROBE_FAILDEMO") == "1"
  def expect(n: Int): Int = if (faildemo) n + 1 else n


  /* ---------------- 1. the parsers ---------------- */

  println("1. Sites -- the grammars, pinned")

  /* `expression_heads` takes the LIVE text and the OUTER text of the same
     span; in a probe the outer view is simulated by blanking terms, which is
     exactly what `Theory_Section.outer_source` does to the same characters. */
  def blank_terms(s: String): String = {
    val buf = new StringBuilder
    var i = 0
    var quoted = false
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '"') { quoted = !quoted; buf += ' ' ; i += 1 }
      else if (!quoted && s.startsWith("""\<open>""", i)) {
        val e = isabelle.query.Entries.balanced_end(s, """\<open>""", """\<close>""", i)
        val stop = if (e < 0) s.length else e
        while (i < stop) { buf += ' '; i += 1 }
      }
      else { buf += (if (quoted) ' ' else c); i += 1 }
    }
    buf.toString
  }

  def heads(text: String): List[String] = Sites.expression_heads(text, blank_terms(text))
  def arity(text: String): List[String] = Sites.arity_classes(text, blank_terms(text))

  check("a bare locale expression", heads(" magma f") == List("magma"), heads(" magma f").mkString(","))
  check("a qualifier is not the locale",
    heads(" add: comm_monoid plus 0") == List("comm_monoid"),
    heads(" add: comm_monoid plus 0").mkString(","))
  check("a `?` qualifier is not the locale",
    heads(" weak?: weak_complete_lattice") == List("weak_complete_lattice"),
    heads(" weak?: weak_complete_lattice").mkString(","))
  check("a QUOTED qualifier is not the locale",
    heads(""" "and": semilattice_neutr \<open>(AND)\<close>""") == List("semilattice_neutr"),
    heads(""" "and": semilattice_neutr \<open>(AND)\<close>""").mkString(","))
  check("a QUOTED locale name is read",
    heads(""" q: "open" id""") == List("open"),
    heads(""" q: "open" id""").mkString(","))
  check("a symbol-bearing locale name is read",
    heads(" m2: magma\\<^sub>2 id") == List("magma\\<^sub>2"),
    heads(" m2: magma\\<^sub>2 id").mkString(","))
  check("a qualified locale name is read whole",
    heads(" Groups.monoid plus") == List("Groups.monoid"),
    heads(" Groups.monoid plus").mkString(","))
  check("a compound expression names every head",
    heads(" L1 x + qual: L2 y") == List("L1", "L2"),
    heads(" L1 x + qual: L2 y").mkString(","))
  check("a `+` inside a term is not a separator",
    heads(""" folding "\<lambda>x. x + 1" 0""") == List("folding"),
    heads(""" folding "\<lambda>x. x + 1" 0""").mkString(","))

  check("a qualified name denotes the plain one",
    Sites.denotes("Groups.monoid", "monoid") && Sites.denotes("monoid", "monoid") &&
      !Sites.denotes("foo_monoid", "monoid"), "")

  check("an arity with no argument sorts", arity(" nat :: mynull") == List("mynull"),
    arity(" nat :: mynull").mkString(","))
  check("argument sorts are CONSTRAINTS, not instantiations",
    arity(" prod :: (exhaustive, exhaustive) exhaustive") == List("exhaustive"),
    arity(" prod :: (exhaustive, exhaustive) exhaustive").mkString(","))
  check("a quoted brace sort is several classes",
    arity(""" bool :: "{order_bot, order_top, linorder}" """) ==
      List("order_bot", "order_top", "linorder"),
    arity(""" bool :: "{order_bot, order_top, linorder}" """).mkString(","))
  check("a quoted type constructor does not hide the sort",
    arity(""" "fun" :: (type, ord) ord""") == List("ord"),
    arity(""" "fun" :: (type, ord) ord""").mkString(","))
  check("a quoted ARGUMENT sort does not hide the target",
    arity(""" "fun" :: ("{equal,exhaustive}", exhaustive) exhaustive""") == List("exhaustive"),
    arity(""" "fun" :: ("{equal,exhaustive}", exhaustive) exhaustive""").mkString(","))
  check("a class inclusion has no arity", arity(""" bifinite \<subseteq> profinite""").isEmpty,
    arity(""" bifinite \<subseteq> profinite""").mkString(","))
  check("bare `instance` has no arity", arity(" ..").isEmpty, "")

  /* The head of an equation. */
  def eq(text: String): List[String] = Sites.equation_heads(text)

  check("the head of a plain equation", eq(""" "twice n = 2 * n" """) == List("twice"),
    eq(""" "twice n = 2 * n" """).mkString(","))
  check("a right-hand side is NOT a head",
    !eq(""" "thrice n = twice n + n" """).contains("twice"),
    eq(""" "thrice n = twice n + n" """).mkString(","))
  check("premises are dropped",
    eq(""" "n > 0 \<Longrightarrow> half n = n div 2" """) == List("half"),
    eq(""" "n > 0 \<Longrightarrow> half n = n div 2" """).mkString(","))
  check("an abstract equation names the projection AND the constant",
    eq(""" "rep_pos (mk n) = max 1 n" """) == List("rep_pos", "mk"),
    eq(""" "rep_pos (mk n) = max 1 n" """).mkString(","))
  check("a meta equality is an equality",
    eq(""" "f x \<equiv> g x" """) == List("f"), eq(""" "f x \<equiv> g x" """).mkString(","))
  check("a boolean equation is an equality",
    eq(""" "p x \<longleftrightarrow> q x" """) == List("p"),
    eq(""" "p x \<longleftrightarrow> q x" """).mkString(","))
  check("a cartouche is a proposition too",
    eq(""" \<open>f x = y\<close> """) == List("f"), eq(""" \<open>f x = y\<close> """).mkString(","))
  check("several propositions give several heads",
    eq(""" "f 0 = a" "g n = b" """) == List("f", "g"),
    eq(""" "f 0 = a" "g n = b" """).mkString(","))
  check("only the `shows` part counts",
    eq(""" assumes "p x = y" shows "f x = z" """) == List("f"),
    eq(""" assumes "p x = y" shows "f x = z" """).mkString(","))
  check("a symbol-bearing head is read whole",
    eq(""" "x\<^sub>1 = 1" """) == List("x\\<^sub>1"),
    eq(""" "x\<^sub>1 = 1" """).mkString(","))

  /* The attribute set. */
  def attrs(text: String): List[String] =
    Sites.code_attrs(text, blank_terms(text)).map(_.spelling)

  check("a bare [code]", attrs("lemma foo [code]: ") == List("code"),
    attrs("lemma foo [code]: ").mkString(","))
  check("every declaring spelling",
    List("equation", "prepend", "nbe", "abstract", "abstype")
      .forall(a => attrs("lemma foo [code " + a + "]: ") == List("code " + a)), "")
  check("the retracting spellings",
    attrs("declare f.simps [code del]") == List("code del") &&
      attrs("declare [[code drop: f]]") == List("code drop") &&
      attrs("declare [[code abort: f]]") == List("code abort"), "")
  check("code_unfold is the PREPROCESSOR, not an equation",
    attrs("lemma foo [code_unfold]: ").isEmpty, attrs("lemma foo [code_unfold]: ").mkString(","))
  check("nor are code_abbrev / code_post / code_pred_intro",
    attrs("lemma foo [code_abbrev]: ").isEmpty && attrs("lemma foo [code_post]: ").isEmpty &&
      attrs("declare i [code_pred_intro]").isEmpty, "")
  check("a comma list keeps the code one and drops the rest",
    attrs("lemma foo [simp, code, code_unfold]: ") == List("code"),
    attrs("lemma foo [simp, code, code_unfold]: ").mkString(","))
  check("the config form is marked as such",
    Sites.code_attrs("declare [[code drop: f]]", blank_terms("declare [[code drop: f]]"))
      .forall(_.config), "")
  /* `code drop:` takes a whitespace-separated list of constants, each of which
     may be quoted and carry a type ascription. */
  val drop_text = """declare [[code drop: "open :: real set \<Rightarrow> bool" g]]"""
  check("a constant with a type ascription is still named", {
    val a = Sites.code_attrs(drop_text, blank_terms(drop_text))
    a.length == 1 && Sites.dropped_constants(drop_text, a.head) == List("open", "g")
  }, Sites.code_attrs(drop_text, blank_terms(drop_text))
      .map(Sites.dropped_constants(drop_text, _)).mkString(";"))


  /* ---------------- 2. the scans, over the fixtures ---------------- */

  println("2. the fixture project -- counts computed by hand first")

  val index = Query_Index(fix_root)
  val snapshot = index.refreshed(Map.empty)
  check("the fixture project indexes",
    snapshot.theories == 3 && snapshot.entries > 0,
    snapshot.theories.toString + " theories, " + snapshot.entries.toString + " entries")

  def loci(sites: List[Sites.Site]): List[String] =
    sites.map(s => s.theory + ":" + s.line.toString)
  def kinds(sites: List[Sites.Site]): List[String] = sites.map(_.kind)

  def inst(name: String): List[Sites.Site] =
    Sites.find_instantiations(snapshot.sections, name)
  def code(name: String): List[Sites.Site] =
    Sites.find_code_equations(snapshot.sections, name)

  /* magma: interpretation, global_interpretation, two sublocales (the second
     in the older `<` spelling, and BOTH with the target stripped) and an
     `interpret` inside a proof.  The `locale semi = magma + ...` extension is
     NOT a site, nor is the text block, the `(* ... *)` note or the
     `\<^cancel>`ed line. */
  check("magma has five instantiation sites",
    inst("magma").length == expect(5), loci(inst("magma")).mkString(", "))
  check("and they are the five commands, in source order",
    kinds(inst("magma")) ==
      List("interpretation", "global_interpretation", "sublocale", "sublocale", "interpret"),
    kinds(inst("magma")).mkString(", "))
  check("a locale EXTENSION (`locale semi = magma + ...`) is not a site",
    !loci(inst("magma")).contains("Sites_Fix:10"), "")
  check("a commented-out, a cancelled and a `text` decoy are not sites",
    inst("magma").forall(s => s.line < 55), loci(inst("magma")).mkString(", "))

  check("`sublocale L \\<subseteq> M` is a site of M, not of L",
    inst("semi").isEmpty && loci(inst("magma")).contains("Sites_Fix:26"),
    loci(inst("semi")).mkString(", "))

  check("a symbol-bearing locale is found",
    loci(inst("magma\\<^sub>2")) == List("Sites_Fix:30"),
    loci(inst("magma\\<^sub>2")).mkString(", "))
  check("a quoted locale name is found",
    loci(inst("open")) == List("Sites_Fix:32"), loci(inst("open")).mkString(", "))

  /* mynull: two `instantiation` blocks (the second with a quoted brace sort)
     and one standalone `instance` arity.  The `instance ..` that closes each
     block is not a second site, and the argument sorts of the `prod` arity are
     constraints. */
  check("mynull has three instantiation sites",
    loci(inst("mynull")) == List("Sites_Fix:40", "Sites_Fix:46", "Sites_Fix:52"),
    loci(inst("mynull")).mkString(", "))
  check("and `instance ..` inside a block is not one of them",
    kinds(inst("mynull")) == List("instantiation", "instantiation", "instance"),
    kinds(inst("mynull")).mkString(", "))

  /* twice: its own `definition` (default), a `[code]` lemma whose head it is,
     a `lemmas` that renames that lemma, and a `[[code drop:]]`.  NOT the
     `[code]` lemma that only mentions it on a right-hand side, NOT the
     `[code_unfold]` one, and neither decoy. */
  check("twice has four code-equation sites",
    loci(code("twice")) == List("Code_Fix:10", "Code_Fix:23", "Code_Fix:43", "Code_Fix:45"),
    loci(code("twice")).mkString(", "))
  check("and the kinds say which is which",
    kinds(code("twice")) == List("default", "[code]", "[code]", "[code drop]"),
    kinds(code("twice")).mkString(", "))
  check("a constant on a RIGHT-hand side is not a code equation of it",
    !loci(code("twice")).contains("Code_Fix:29"), "")
  check("[code_unfold] is not a code equation",
    !loci(code("twice")).contains("Code_Fix:26"), "")

  check("fib: the `fun` default and the `[code del]` that retracts it",
    loci(code("fib")) == List("Code_Fix:5", "Code_Fix:41") &&
      kinds(code("fib")) == List("default", "[code del]"),
    loci(code("fib")).mkString(", ") + " / " + kinds(code("fib")).mkString(", "))

  check("a declaration carrying [code] does not ALSO report a default",
    loci(code("thrice")) == List("Code_Fix:13", "Code_Fix:29") &&
      kinds(code("thrice")) == List("[code]", "[code]"),
    loci(code("thrice")).mkString(", ") + " / " + kinds(code("thrice")).mkString(", "))

  check("a conditional equation is attributed to its conclusion's head",
    loci(code("half")) == List("Code_Fix:15", "Code_Fix:32"),
    loci(code("half")).mkString(", "))
  check("a symbol-bearing constant is found",
    loci(code("x\\<^sub>1")) == List("Code_Fix:18", "Code_Fix:35"),
    loci(code("x\\<^sub>1")).mkString(", "))
  check("a datatype constructor is a constant",
    loci(code("Node")) == List("Code_Fix:38"), loci(code("Node")).mkString(", "))

  /* The exit-code contract, at the level the engine decides it.  The shell
     script pins the process exit codes that follow from these. */
  check("an unknown subject is refused, not answered with zero",
    Sites.resolve(snapshot.sections, "no_such_thing", Sites.locale_tags, "a locale or class")
      .isLeft, "")
  check("a KNOWN subject of the wrong kind is refused, and the message says which", {
    val e = Sites.resolve(snapshot.sections, "twice_alt", Sites.constant_tags, "a constant")
    e.isLeft && e.left.exists(m => m.contains("LEMMA") && m.contains("Code_Fix"))
  }, Sites.resolve(snapshot.sections, "twice_alt", Sites.constant_tags, "a constant")
      .left.getOrElse(""))
  check("a known subject with no sites is an honest zero, not a refusal", {
    val e = Sites.resolve(snapshot.sections, "Leaf", Sites.constant_tags, "a constant")
    e.isRight && code("Leaf").isEmpty
  }, "")
  check("a bound name resolves through the declaration that binds it",
    Sites.resolve(snapshot.sections, "Leaf", Sites.constant_tags, "a constant")
      .exists(_.how.contains("constructor of mytree")), "")


  /* ---------------- 2b. row names and written sorts (P6c) ---------------- */

  println("2b. the row name, its fallback chain, and --sorts")

  def insts(text: String): List[(String, String)] =
    Sites.expression_instances(text, blank_terms(text))

  check("a qualifier is the instance's own name",
    insts(""" nat_magma: magma "(+)" """) == List(("nat_magma", "magma")),
    insts(""" nat_magma: magma "(+)" """).mkString(","))
  check("a QUOTED qualifier is read unquoted",
    insts(""" "and": semilattice_neutr x""") == List(("and", "semilattice_neutr")),
    insts(""" "and": semilattice_neutr x""").mkString(","))
  check("the `?` mandatory marker is not part of the name",
    insts(" weak?: weak_complete_lattice x") == List(("weak", "weak_complete_lattice")),
    insts(" weak?: weak_complete_lattice x").mkString(","))
  check("an unqualified instance carries no name of its own",
    insts(" magma f") == List(("", "magma")), insts(" magma f").mkString(","))
  check("each instance of a compound expression is named separately",
    insts(" L1 x + q: L2 y") == List(("", "L1"), ("q", "L2")),
    insts(" L1 x + q: L2 y").mkString(","))

  def parts(text: String): (String, String) = Sites.arity_parts(text, blank_terms(text))

  check("an arity splits into the type constructor and the sorts as WRITTEN",
    parts(" prod :: (topological_space, topological_space) topological_space") ==
      (("prod", "(topological_space, topological_space) topological_space")),
    parts(" prod :: (topological_space, topological_space) topological_space").toString)
  check("a quoted type constructor is still the constructor",
    parts(""" "fun" :: (type, ord) ord""") == (("fun", "(type, ord) ord")),
    parts(""" "fun" :: (type, ord) ord""").toString)
  check("a quoted brace sort is not normalised away",
    parts(""" bool :: "{mynull, ord}" """) == (("bool", """"{mynull, ord}"""")),
    parts(""" bool :: "{mynull, ord}" """).toString)

  def wt(text: String, n: String): String = Sites.written_type(text, blank_terms(text), n)

  check("a written signature is read from the declaration head",
    wt("""definition twice :: "nat \<Rightarrow> nat" where""", "twice") ==
      """nat \<Rightarrow> nat""",
    wt("""definition twice :: "nat \<Rightarrow> nat" where""", "twice"))
  check("an unquoted type ends where the header does",
    wt("definition null_nat :: nat where", "null_nat") == "nat",
    wt("definition null_nat :: nat where", "null_nat"))
  check("a `::` inside a TERM is not the declaration's own signature",
    wt("""lemma foo: "f :: nat \<Rightarrow> bool" """, "f").isEmpty,
    wt("""lemma foo: "f :: nat \<Rightarrow> bool" """, "f"))
  check("a declaration that writes no type is given none",
    wt("""lemma twice_alt [code]: "twice n = 2 * n" """, "twice_alt").isEmpty, "")

  def names_of(sites: List[Sites.Site]): List[String] = sites.map(_.name)
  def labels_of(sites: List[Sites.Site], sorts: Boolean): List[String] =
    sites.map(_.label(sorts))

  /* `Names_Fix.thy` is written for exactly this: one site per link of the
     chain, in this order. */
  check("the name chain: block, qualifier, `?`, qualifier, enclosing entry",
    names_of(inst("plain")) ==
      List("holder", "sub", Sites.UNNAMED, "inner", "anon_interpret"),
    names_of(inst("plain")).mkString(", "))
  check("a bare `interpretation L ..` is left UNNAMED, not given L's own name",
    inst("plain").find(_.line == 18).map(_.name).contains(Sites.UNNAMED),
    inst("plain").find(_.line == 18).map(_.name).getOrElse("<missing>"))
  check("a qualified sublocale is named by its qualifier, not by its target",
    inst("plain").find(_.line == 20).map(_.name).contains("inner"),
    inst("plain").find(_.line == 20).map(_.name).getOrElse("<missing>"))
  check("and an unqualified `sublocale L \\<subseteq> M` is named by L",
    names_of(inst("magma")).contains("semi"), names_of(inst("magma")).mkString(", "))

  check("an arity row is named after the type constructor it instantiates",
    names_of(inst("mynull")) == List("nat", "bool", "prod"),
    names_of(inst("mynull")).mkString(", "))
  check("--sorts re-spells an arity row as the source writes it",
    labels_of(inst("mynull"), true) ==
      List("nat :: mynull", """bool :: "{mynull, ord}"""",
        "prod :: (mynull, mynull) mynull"),
    labels_of(inst("mynull"), true).mkString(" | "))
  check("and without it the cell is the bare subject",
    labels_of(inst("mynull"), false) == List("nat", "bool", "prod"),
    labels_of(inst("mynull"), false).mkString(", "))
  check("an interpretation writes no sort, so --sorts adds nothing to one",
    labels_of(inst("plain"), true) == labels_of(inst("plain"), false), "")

  check("a code row is named after the fact that provides the equation",
    names_of(code("twice")) == List("twice", "twice_alt", "twice_lemmas", "twice"),
    names_of(code("twice")).mkString(", "))
  check("a `declare` row takes the BINDING LABEL it attaches to",
    names_of(code("fib")) == List("fib", "fib.simps"),
    names_of(code("fib")).mkString(", "))
  check("a `default` row takes the defining entry's own name",
    names_of(code("half")) == List("half", "cond_code"),
    names_of(code("half")).mkString(", "))
  check("--sorts adds the written signature, and only where one is written",
    labels_of(code("twice"), true) ==
      List("""twice :: nat \<Rightarrow> nat""", "twice_alt", "twice_lemmas", "twice"),
    labels_of(code("twice"), true).mkString(" | "))


  /* ---------------- 3. the plugin seam ---------------- */

  println("3. Query_Search / Query_Dockable -- the two new result kinds")

  check("both new kinds open COLLAPSED, as usages do",
    !Query_Search.Result_Kind.Instantiations.expand_groups &&
      !Query_Search.Result_Kind.Code_Equations.expand_groups, "")

  check("a site set counts sites, a usages set hits, a definition lines",
    Query_Dockable.count_caption(Query_Search.Result_Kind.Instantiations, 5, 1) ==
      "5 sites in 1 theory" &&
      Query_Dockable.count_caption(Query_Search.Result_Kind.Code_Equations, 1, 1) ==
        "1 site in 1 theory" &&
      Query_Dockable.count_caption(Query_Search.Result_Kind.Usages, 2, 2) ==
        "2 hits in 2 theories" &&
      Query_Dockable.count_caption(Query_Search.Result_Kind.Definition, 3, 1) == "3 lines",
    Query_Dockable.count_caption(Query_Search.Result_Kind.Instantiations, 5, 1))

  check("an empty set of each kind says what it has none of",
    Query_Dockable.empty_noun(Query_Search.Result_Kind.Instantiations) == "instantiations" &&
      Query_Dockable.empty_noun(Query_Search.Result_Kind.Code_Equations) == "code equations" &&
      Query_Dockable.empty_noun(Query_Search.Result_Kind.Usages) == "usages" &&
      Query_Dockable.empty_noun(Query_Search.Result_Kind.Definition) == "declaration", "")

  val inst_result = Query_Search.instantiations(snapshot, "magma")
  check("the panel's instantiation set is the engine's list, grouped by theory",
    inst_result.hits == inst("magma").length && inst_result.theories == 1 &&
      inst_result.refused.isEmpty,
    inst_result.hits.toString + " hits in " + inst_result.theories.toString + " theories")
  check("every row carries its site kind as a tag",
    inst_result.groups.flatMap(_.hits).map(_.tag) == kinds(inst("magma")),
    inst_result.groups.flatMap(_.hits).map(_.tag).mkString(", "))
  check("every row is the source line it points at",
    inst_result.groups.flatMap(_.hits).forall(h =>
      snapshot.section(h.theory).exists(sec =>
        sec.lines(h.line - 1).trim == h.text.trim)), "")
  check("the label names the verb and the subject",
    inst_result.label.startsWith("instantiations of magma"), inst_result.label)
  check("the tag reaches the rendered leaf", {
    val hit = inst_result.groups.head.hits.head
    Query_Dockable.hit_html("magma", hit).contains("<i>" + hit.tag + "</i>")
  }, Query_Dockable.hit_html("magma", inst_result.groups.head.hits.head))

  val code_result = Query_Search.code_equations(snapshot, "twice")
  check("the panel's code-equation set matches the engine's",
    code_result.hits == expect(4) && code_result.groups.flatMap(_.hits).map(_.tag) ==
      List("default", "[code]", "[code]", "[code drop]"),
    code_result.groups.flatMap(_.hits).map(_.tag).mkString(", "))

  /* The refusal path: NOT an empty result set. */
  val refused = Query_Search.instantiations(snapshot, "twice")
  check("a wrong-kinded subject is refused in the panel, with the reason",
    refused.refused.nonEmpty && refused.is_empty &&
      refused.refused.contains("not a locale or class"),
    refused.refused)
  check("and a refusal is distinguishable from an empty answer",
    Query_Search.code_equations(snapshot, "Leaf").refused.isEmpty &&
      Query_Search.code_equations(snapshot, "Leaf").is_empty, "")

  check("the menu predicate is the engine's, so menu and CLI cannot disagree",
    Query_Search.is_subject(snapshot, "magma", Sites.locale_tags) &&
      !Query_Search.is_subject(snapshot, "magma", Sites.constant_tags) &&
      Query_Search.is_subject(snapshot, "twice", Sites.constant_tags) &&
      !Query_Search.is_subject(snapshot, "twice", Sites.locale_tags) &&
      !Query_Search.is_subject(snapshot, "no_such_thing", Sites.locale_tags), "")


  /* --- the row name and the Sorts toggle, in the panel (P6c) --- */

  val null_result = Query_Search.instantiations(snapshot, "mynull")
  check("the panel's rows carry the CLI's names",
    null_result.groups.flatMap(_.hits).map(_.name) == names_of(inst("mynull")),
    null_result.groups.flatMap(_.hits).map(_.name).mkString(", "))
  check("and its written sorts, kept SEPARATE so a toggle need not re-query",
    null_result.groups.flatMap(_.hits).map(_.sorts) == inst("mynull").map(_.sorts),
    null_result.groups.flatMap(_.hits).map(_.sorts).mkString(" | "))
  check("the Sorts toggle spells a row exactly as `--sorts` does", {
    val hits = null_result.groups.flatMap(_.hits)
    hits.map(Query_Dockable.hit_name(_, false)) == labels_of(inst("mynull"), false) &&
      hits.map(Query_Dockable.hit_name(_, true)) == labels_of(inst("mynull"), true)
  }, null_result.groups.flatMap(_.hits).map(Query_Dockable.hit_name(_, true)).mkString(" | "))
  check("the name reaches the rendered leaf, BEFORE the italic role", {
    val hit = null_result.groups.head.hits.head
    val html = Query_Dockable.hit_html("mynull", hit)
    html.contains(hit.name) && html.indexOf(hit.name) < html.indexOf("<i>")
  }, Query_Dockable.hit_html("mynull", null_result.groups.head.hits.head))
  check("and the toggle changes that leaf without a second query", {
    /* The `bool` row: its sort is quoted, so the HTML escaping is exercised at
       the same time.  Pinned as a PREFIX rather than by `contains`, because the
       source line quoted on the right of the row has a `::` of its own -- the
       first draft of this check passed on that one. */
    val hit = null_result.groups.flatMap(_.hits)(1)
    val head = "<html>" + hit.line.toString + ": "
    Query_Dockable.hit_html("mynull", hit, true)
      .startsWith(head + "bool :: &quot;{mynull, ord}&quot;&nbsp;&nbsp;<i>") &&
      Query_Dockable.hit_html("mynull", hit, false).startsWith(head + "bool&nbsp;&nbsp;<i>")
  }, Query_Dockable.hit_html("mynull", null_result.groups.flatMap(_.hits)(1), true))
  check("a usages row has no name, so nothing about it changed",
    Query_Search.usages(snapshot, "twice").groups.flatMap(_.hits).forall(_.name.isEmpty), "")


  /* --- search by name, from the panel (P6c) --- */

  println("3b. Query_Name_Search -- the panel's name field")

  val snap = Some(snapshot)
  check("an exact declaration name resolves to itself",
    Query_Name_Search.resolve(snap, "twice") == "twice" &&
      Query_Name_Search.resolve(snap, "  twice  ") == "twice",
    Query_Name_Search.resolve(snap, "twice"))
  check("a partial one resolves through go-to-symbol's own ranking",
    Query_Name_Search.resolve(snap, "twicealt") == "twice_alt",
    Query_Name_Search.resolve(snap, "twicealt"))
  check("a name that matches nothing is passed through unchanged",
    Query_Name_Search.resolve(snap, "zz_no_such_name") == "zz_no_such_name" &&
      Query_Name_Search.resolve(snap, "") == "", "")
  check("a COLD index resolves nothing and refuses nothing",
    Query_Name_Search.resolve(None, "twice") == "twice", "")

  check("usages and definition are offered for ANY name",
    Query_Name_Search.finders(snap, "zz_no_such_name").map(_.label) ==
      List("Find usages", "Find external usages", "Find definition"),
    Query_Name_Search.finders(snap, "zz_no_such_name").map(_.label).mkString(", "))
  check("the site verbs are offered exactly where the context menu offers them", {
    val loc = Query_Name_Search.finders(snap, "magma").map(_.label)
    val con = Query_Name_Search.finders(snap, "twice").map(_.label)
    loc.contains("Find instantiations") && !loc.contains("Find code equations") &&
      con.contains("Find code equations") && !con.contains("Find instantiations")
  }, Query_Name_Search.finders(snap, "magma").map(_.label).mkString(", "))
  check("a cold index offers only the ungated three, as the menu does",
    Query_Name_Search.finders(None, "magma") == Query_Name_Search.ungated, "")
  check("an empty field offers nothing at all",
    Query_Name_Search.finders(snap, "").isEmpty, "")
  check("the hint says what the typed text resolved to, and what it is",
    Query_Name_Search.hint(snap, "twice").contains("DEF") &&
      Query_Name_Search.hint(snap, "twicealt").startsWith("→ twice_alt"),
    Query_Name_Search.hint(snap, "twice") + " / " + Query_Name_Search.hint(snap, "twicealt"))
  check("the candidate list is the fuzzy one, and is bounded",
    Query_Name_Search.candidates(snap, "t", 3).length <= 3 &&
      Query_Name_Search.candidates(None, "t", 3).isEmpty, "")


  /* ---------------- 4. the plugin resources ---------------- */

  println("4. the plugin jar -- the two new actions and their menu entries")

  val shim =
    Paths.get(Isabelle_System.getenv("JEDIT_SETTINGS"))
      .resolve("jars").resolve("isabelle_jedit_query.jar")
  check("the plugin shim jar is built", Files.isRegularFile(shim), shim.toString)

  if (Files.isRegularFile(shim)) {
    val zip = new java.util.zip.ZipFile(shim.toFile)
    def resource(name: String): String = {
      val entry = zip.getEntry(name)
      if (entry == null) ""
      else {
        val in = zip.getInputStream(entry)
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        finally in.close()
      }
    }
    /* Continuation lines are JOINED first.  A `.properties` value may be
       written `key= \` and continued, which is how the plugin menu is written
       -- and a `(?m)^key=(.*)$` read of it captures the lone backslash, leaves
       an EMPTY menu list, and every "menu entry names a real action" check
       passes vacuously.  `dev/p6probe.scala` has that bug; it is fixed there
       too, and this is why the check below prints the list it examined. */
    val props = resource("plugin.props").replaceAll("""\\\r?\n[ \t]*""", "")
    val actions = resource("actions.xml")
    def prop(name: String): Option[String] =
      ("(?m)^" + java.util.regex.Pattern.quote(name) + "=(.*)$").r
        .findFirstMatchIn(props).map(_.group(1).trim)

    val action_names =
      """<ACTION NAME="([^"]+)"""".r.findAllMatchIn(actions).map(_.group(1)).toList
    val new_actions =
      List("isabelle-project-query.find-instantiations",
        "isabelle-project-query.find-code-equations")
    check("both new actions exist", new_actions.forall(action_names.contains),
      action_names.mkString(", "))
    check("and each has a label",
      new_actions.forall(a => prop(a + ".label").exists(_.nonEmpty)),
      new_actions.flatMap(a => prop(a + ".label")).mkString(" / "))
    check("and neither ships a default shortcut (three chords are enough)",
      new_actions.forall(a => prop(a + ".shortcut").isEmpty), "")

    val menu =
      prop("plugin.isabelle.jedit_query_plugin.Plugin.menu").toList
        .flatMap(_.split("\\s+")).map(_.trim)
        .filter(s => s.nonEmpty && s != "-" && s != "\\")
    check("the menu is non-empty (the check below is not vacuous)",
      menu.length >= 8, menu.mkString(", "))
    check("both are on the plugin menu", new_actions.forall(menu.contains),
      menu.mkString(", "))
    check("every menu entry still names an action or the dockable",
      menu.forall(m => action_names.contains(m) || m == Query_Dockable.NAME),
      menu.mkString(", "))

    /* P6c: the panel's name field needs a keyboard route, and the Sorts
       toggle needs a default that is written down rather than assumed. */
    val search_action = "isabelle-project-query.search-by-name"
    check("the search-by-name action exists, is labelled and is on the menu",
      action_names.contains(search_action) &&
        prop(search_action + ".label").exists(_.nonEmpty) && menu.contains(search_action),
      prop(search_action + ".label").getOrElse("<no label>"))
    check("and ships no default shortcut either",
      prop(search_action + ".shortcut").isEmpty, "")
    check("the Sorts toggle has a written default, and it is off",
      prop(Query_Dockable.SORTS_PROPERTY).contains("false"),
      prop(Query_Dockable.SORTS_PROPERTY).getOrElse("<unset>"))

    /* The action bodies are BeanShell, so a renamed method fails at
       plugin-load time and nowhere else: resolve them here instead. */
    val bodies =
      """<ACTION NAME="([^"]+)">\s*<CODE>\s*([^<]*)</CODE>""".r.findAllMatchIn(actions)
        .map(m => (m.group(1), m.group(2).trim)).toMap
    val target_re = """([\w.]+)\.(\w+)\(view""".r
    check("both action bodies resolve to a real method",
      (new_actions ::: List(search_action)).forall { a =>
      bodies.get(a).flatMap(b => target_re.findFirstMatchIn(b)).exists { m =>
        try {
          val cls = Class.forName(m.group(1) + "$")
          cls.getMethods.exists(meth =>
            meth.getName == m.group(2) && meth.getParameterCount == 1)
        }
        catch { case _: Throwable => false }
      }
    }, new_actions.flatMap(bodies.get).mkString(" | "))
    zip.close()
  }


  /* --- what the shell script cross-checks against the CLI --- */

  Files.write(out_dir.resolve("panel-inst.txt"),
    inst_result.groups.flatMap(_.hits)
      .map(h => h.theory + ":" + h.line.toString).mkString("", "\n", "\n").getBytes("UTF-8"))
  Files.write(out_dir.resolve("panel-code.txt"),
    code_result.groups.flatMap(_.hits)
      .map(h => h.theory + ":" + h.line.toString).mkString("", "\n", "\n").getBytes("UTF-8"))

  println()
  println(if (failures == 0) "P6BPROBE OK" else "P6BPROBE FAILURES: " + failures.toString)

  /* `sys.exit`, not a return: reading `Query_Dockable` starts AWT, whose event
     thread is not a daemon, so a probe that merely falls off the end of `main`
     never exits.  `dev/p6probe.scala` ends the same way for the same reason. */
  System.out.flush()
  sys.exit(if (failures == 0) 0 else 1)
  }
}
