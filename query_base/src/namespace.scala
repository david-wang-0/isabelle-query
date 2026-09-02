/*  Title:      query_base/src/namespace.scala

The method / attribute / keyword namespace behind the citation router.

A proof-body token is a fact citation — and so a call-graph edge — only if it
is none of a proof method, an attribute, an outer-syntax keyword, an inline
argument modifier, or a numeral.  The tables below are the reference
implementation's committed scans, ported as DATA (`PLAN.md`: "port tables as
data, logic as logic"); the routing built on them is `Graph.is_citation_name`.

A resolved table is a VALUE (`Namespace.Table`), threaded from
`CLI.resolve_namespace` down to the sites that read one.  It used to be four
process-global `var`s with a `configure` that rebound them, which made "which
table is in force" a property of the PROCESS rather than of the request.

Two committed tables, and which one a request resolves is a real decision:

  * `CENSUS_*` — the broad HOL-family union (HOL, HOL-Library, HOL-Analysis,
    HOL-Eisbach, HOL-Decision_Procs over the Pure floor).  **The default**, for
    every caller: an engine caller must get the table a CLI run gets, or a
    directly-called API silently answers a different question from the command
    line (`CONTRIBUTING.md`, "a configurable global that moves a measurement
    gets ONE default").
  * `PURE_*` — the minimal Pure floor (37 methods: `simp`, `rule`, `unfold`;
    no `auto`/`blast`/`induct`, which are HOL's).  Reached only by stepping
    DOWN explicitly, for a project whose base logic is *positively* not HOL
    (`ZF`, `FOL`, `CTT`, `Sequents`, …) where the HOL union would assert
    methods the logic does not have.

Keywords are logic-invariant (Pure outer syntax), so there is one table for
them and the census scan deliberately carries none.

Provenance: Isabelle2025-2, extracted 2026-07-24 by the reference
implementation's `scripts/extract_isabelle_namespace.py` (`--census` for the
union).  GENERATED CONTENT — regenerate there, not here.
*/

package isabelle.query


object Namespace {
  /* Pure image proof methods (`ML_process -l Pure`) — 37. */
  val PURE_METHODS: Set[String] = Set(
    "-", "assumption", "atomize", "cut_tac", "drule", "drule_tac", "elim", "erule",
    "erule_tac", "fact", "fail", "fold", "frule", "frule_tac", "goal_cases", "insert",
    "intro", "intro_classes", "intro_locales", "raw_tactic", "rename_tac",
    "rotate_tac", "rule", "rule_tac", "simp", "simp_all", "sleep", "standard",
    "subgoal_tac", "subproofs", "succeed", "tactic", "thin_tac", "this", "unfold",
    "unfold_locales", "use"
  )

  /* Pure image attributes — 101. */
  val PURE_ATTRIBUTES: Set[String] = Set(
    "ML_catch_all", "ML_debugger", "ML_environment", "ML_exception_debugger",
    "ML_exception_trace", "ML_print_depth", "ML_read_global", "ML_source_trace",
    "ML_write_global", "OF", "THEN", "abs_def", "atomize", "attribute",
    "case_conclusion", "case_names", "cases_open", "cite_macro", "code",
    "code_only_single_equation", "code_prepend_allowed", "code_strict_drop", "cong",
    "cong_format", "constraints", "consumes", "defn", "dest", "elim", "elim_format",
    "eta_contract", "extraction_expand", "extraction_expand_def", "folded",
    "goals_limit", "intro", "kind", "names_long", "names_short", "names_unique",
    "no_vars", "of", "params", "quick_and_dirty", "rename_abs", "rotated", "rule",
    "rule_format", "rule_trace", "rulify", "show_abbrevs", "show_consts",
    "show_consts_markup", "show_hyps", "show_main_goal", "show_markup",
    "show_question_marks", "show_results", "show_reverted_improvements", "show_sorts",
    "show_structs", "show_tags", "show_types", "show_variants", "simp", "simp_break",
    "simp_debug", "simp_depth_limit", "simp_trace", "simp_trace_depth_limit",
    "simp_trace_new", "simplified", "simproc", "sym", "symmetric",
    "syntax_ambiguity_limit", "syntax_ambiguity_warning", "syntax_ast_stats",
    "syntax_ast_trace", "tagged", "thy_output_break", "thy_output_cartouche",
    "thy_output_display", "thy_output_indent", "thy_output_margin", "thy_output_modes",
    "thy_output_quotes", "thy_output_source", "thy_output_source_cartouche",
    "trace_locales", "trans", "unfold_abs_def", "unfolded", "unify_search_bound",
    "unify_trace", "unify_trace_bound", "unify_trace_failure", "unify_trace_simp",
    "unify_trace_types", "untagged", "where"
  )

  /* Outer-syntax keywords: commands, the proof language, quasi-commands and
     the header bootstrap — 222.  Structural, so never a fact citation. */
  val KEYWORDS: Set[String] = Set(
    "ML_command", "ML_export", "ML_file", "ML_file_debug", "ML_file_no_debug",
    "ML_prf", "ML_val", "ROOTS_file", "SML_export", "SML_file", "SML_file_debug",
    "SML_file_no_debug", "SML_import", "abbreviation", "abbrevs", "adhoc_overloading",
    "alias", "also", "and", "apply", "apply_end", "assume", "assumes",
    "attribute_setup", "axiomatization", "back", "begin", "bibtex_file", "binder",
    "bundle", "by", "case", "chapter", "class", "class_deps", "code_datatype",
    "compile_generated_files", "congproc", "consider", "constrains", "consts",
    "context", "corollary", "declaration", "declare", "default_sort", "defer",
    "define", "defines", "definition", "done", "end", "experiment", "export_classpath",
    "export_generated_files", "external_file", "extract", "extract_type", "finally",
    "find_consts", "find_theorems", "fix", "fixes", "for", "from", "full_prf",
    "generate_file", "global_interpretation", "have", "help", "hence", "hide_class",
    "hide_const", "hide_fact", "hide_type", "identifier", "if", "imports", "in",
    "include", "includes", "including", "infix", "infixl", "infixr", "instance",
    "instantiation", "interpret", "interpretation", "is", "judgment", "keywords",
    "lemma", "lemmas", "let", "local_setup", "locale", "locale_deps", "method_setup",
    "moreover", "named_theorems", "next", "no_adhoc_overloading", "no_notation",
    "no_syntax", "no_translations", "no_type_notation", "nonterminal", "notation",
    "note", "notepad", "notes", "obtain", "obtains", "oops", "open", "open_bundle",
    "opening", "oracle", "output", "overloaded", "overloading", "paragraph",
    "parse_ast_translation", "parse_translation", "passive", "pervasive", "prefer",
    "premises", "presume", "prf", "print_ML_antiquotations", "print_abbrevs",
    "print_antiquotations", "print_ast_translation", "print_attributes",
    "print_bundles", "print_cases", "print_classes", "print_codesetup",
    "print_commands", "print_context", "print_context_tracing", "print_definitions",
    "print_defn_rules", "print_facts", "print_interps", "print_locale",
    "print_locales", "print_methods", "print_options", "print_rules", "print_simpset",
    "print_state", "print_statement", "print_syntax", "print_term_bindings",
    "print_theorems", "print_theory", "print_trans_rules", "print_translation",
    "private", "proof", "prop", "proposition", "qed", "qualified", "realizability",
    "realizers", "rewrites", "scala_build_generated_files", "schematic_goal",
    "section", "setup", "show", "shows", "simproc_setup", "sorry", "structure",
    "subclass", "subgoal", "sublocale", "subparagraph", "subsection", "subsubsection",
    "supply", "syntax", "syntax_consts", "syntax_declaration", "syntax_types", "term",
    "text", "text_raw", "then", "then have", "then show", "theorem", "thm", "thm_deps",
    "thm_oracles", "thus", "thy_deps", "translations", "txt", "typ", "type_alias",
    "type_notation", "type_synonym", "typed_print_translation", "typedecl",
    "ultimately", "unbundle", "unchecked", "unfolding", "unused_thms", "using",
    "weak_congproc", "welcome", "when", "where", "with", "write"
  )

  /* Proof methods across the HOL family — 211. */
  val CENSUS_METHODS: Set[String] = Set(
    "-", "abs_used", "add_my_thms", "add_simp", "algebra", "all", "all_args",
    "apply_A", "approximation", "argo", "arith", "assumption", "atomize",
    "atomize_elim", "auto", "ball_simp", "basic_metric_arith", "best", "bestsimp",
    "blast", "case_tac", "cases", "catch", "changed", "clarify", "clarify_step",
    "clarsimp", "cleaning", "code_simp", "coherent", "coinduct", "coinduction",
    "contradiction", "cooper", "corec_unique", "countable_datatype", "cut_tac",
    "declares_test\\<^sub>1", "deepen", "defer_tac", "descending", "descending_setup",
    "determ", "dist_refl_sym", "dlo", "drule", "drule_tac", "dynamic_thms_test",
    "elim", "elim_exists", "elim_exists_loop", "elim_sup", "erule", "erule_tac",
    "eval", "eventually_elim", "fact", "fail", "fails", "fast", "fastforce", "ferrack",
    "field", "find_fact", "find_goal", "find_points", "find_type", "fol_solver",
    "fold", "foo", "foo_method1", "foo_method3", "force", "frpar", "frpar2", "frule",
    "frule_tac", "goal_cases", "guess_all", "guess_ex", "higher_order_example",
    "hypsubst", "hypsubst_thin", "iff", "ind_cases", "induct", "induct_tac",
    "induction", "induction_schema", "injection", "insert", "inst_existentials",
    "inst_step", "intro", "intro_classes", "intro_locales", "iprover",
    "lexicographic_order", "lifting", "lifting_setup", "lin_real_arith", "linarith",
    "match", "match_test", "match_test'", "measurable", "meson", "metis", "metric",
    "metric_eisbach", "mir", "moura", "my_allE\\<^sub>1", "my_allE\\<^sub>2",
    "my_allE\\<^sub>3", "my_allE\\<^sub>4", "my_intros", "my_intros'",
    "my_simp\\<^sub>1", "my_spec", "my_spec_guess", "my_spec_guess2", "norm",
    "normalization", "order", "partiality_descending", "partiality_descending_setup",
    "pat_completeness", "pre_arith", "prefer_last", "presburger", "print_conclusion",
    "print_fact", "print_headgoal", "print_raw_goal", "print_term", "print_type",
    "prop_solver", "raw_tactic", "real_asymp", "recursion_example",
    "recursion_example'", "reflection", "regularize", "reify", "relation",
    "rename_tac", "repeat_new", "rewr_maxdist", "rewr_metric_eq", "rewrite",
    "rferrack", "ring", "rotate_tac", "rtrancl", "rtranclp", "rule", "rule_my_thms",
    "rule_my_thms'", "rule_tac", "safe", "safe_step", "sat", "satx", "simp",
    "simp_all", "simplesubst", "size_change", "sleep", "slow", "slow_step", "slowsimp",
    "smt", "solve_P", "solves", "sos", "split", "standard", "step", "subgoal_tac",
    "subproofs", "subst", "succeed", "succeeds", "tactic", "test2", "test_method",
    "test_method'", "thin_tac", "this", "timeit", "trancl", "tranclp", "transfer",
    "transfer'", "transfer_end", "transfer_prover", "transfer_prover_end",
    "transfer_prover_eq", "transfer_prover_start", "transfer_start", "transfer_start'",
    "transfer_step", "uint_arith", "unat_arith", "unfold", "unfold_locales", "use",
    "uses_test\\<^sub>1", "uses_test\\<^sub>2", "vector"
  )

  /* Attributes across the HOL family — 390. */
  val CENSUS_ATTRIBUTES: Set[String] = Set(
    "ML_catch_all", "ML_debugger", "ML_environment", "ML_exception_debugger",
    "ML_exception_trace", "ML_print_depth", "ML_read_global", "ML_source_trace",
    "ML_write_global", "OF", "THEN", "abs_def", "ac_simps", "algebra", "algebra_simps",
    "algebra_split_simps", "analytic_intros", "approximation_preproc", "argo_timeout",
    "argo_trace", "arith", "asymp_equiv_intros", "asymp_equiv_simps", "atomize",
    "atomize_elim", "atp_proof_cartouches", "attribute", "bilinear_simps", "bit_simps",
    "blast_depth_limit", "bnf_internals", "bnf_n2m_cache_threshold", "bnf_timing",
    "bnf_typedef_threshold", "bounded_bilinear", "bounded_linear",
    "bounded_linear_intros", "cancelation_simproc_eq_elim", "cancelation_simproc_post",
    "cancelation_simproc_pre", "case_conclusion", "case_names", "case_product",
    "case_translation", "cases", "cases_open", "cite_macro", "code", "code_abbrev",
    "code_computation_unfold", "code_only_single_equation", "code_post",
    "code_pred_def", "code_pred_inline", "code_pred_intro", "code_pred_simp",
    "code_prepend_allowed", "code_preproc_trace", "code_runtime_trace",
    "code_simp_trace", "code_strict_drop", "code_test_ghc", "code_timing",
    "code_unfold", "coercion", "coercion_args", "coercion_delete", "coercion_enabled",
    "coercion_map", "coherent_trace", "coinduct", "cong", "cong_format", "constraints",
    "consumes", "cont_intro", "continuous_intros", "curry", "cvc4_options",
    "cvc5_options", "cvc5_proof_options", "cvc_extensions", "datatype_record_update",
    "declare_facts\\<^sub>1", "default_code_width", "defn", "derivative_intros",
    "dest", "divide_const_simps", "divide_simps", "dummy_smtlib_options", "elim",
    "elim_format", "elims", "eta_contract", "eventuallized",
    "exp_log_eval_constructor", "extraction_expand", "extraction_expand_def",
    "ferrack", "field_simps", "field_split_simps", "folded", "foo",
    "fps_expansion_intros", "friend_of_corec_simps", "function_internals",
    "fundef_cong", "goals_limit", "holomorphic_intros", "hypsubst_thin", "id_simps",
    "iff", "ind_realizer", "induct", "induct_simp", "inductive_internals", "intro",
    "intros", "kind", "kodkod_scala", "landau_divide_simps", "langford",
    "langfordsimp", "lifting_restore", "lifting_restore_internal",
    "linarith_neq_limit", "linarith_split", "linarith_split_limit", "linarith_trace",
    "lipschitz_intros", "mapQ3", "measurable", "measurable_cong", "measurable_debug",
    "measurable_dest", "measurable_split", "measure_function", "meson_max_clauses",
    "meson_trace", "meta", "metis_advisory_simp", "metis_instantiate",
    "metis_instantiate_undefined", "metis_new_skolem", "metis_trace", "metis_verbose",
    "metric_argo_timeout", "metric_nnf", "metric_pre_arith", "metric_prenex",
    "metric_trace", "metric_unfold", "mod_simps", "mono", "mono_set",
    "monomorph_max_duplicated_instances",
    "monomorph_max_new_const_instances_per_round", "monomorph_max_new_instances",
    "monomorph_max_rounds", "monomorph_max_schematics", "monomorph_max_thm_instances",
    "my_thms_named", "my_thms_named'", "names_long", "names_short", "names_unique",
    "native_bv", "nbe_trace", "nitpick_choice_spec", "nitpick_psimp", "nitpick_simp",
    "nitpick_unfold", "no_atp", "no_vars", "of", "order_continuous_intros",
    "order_split_limit", "order_trace", "parametricity_preprocess", "params",
    "partial_function_mono", "pred_set_conv", "presburger", "prolog_system",
    "prolog_timeout", "quick_and_dirty", "quickcheck_abort_potential",
    "quickcheck_allow_existentials", "quickcheck_allow_function_inversion",
    "quickcheck_approximation_active", "quickcheck_approximation_custom_seed",
    "quickcheck_approximation_epsilon", "quickcheck_approximation_precision",
    "quickcheck_batch_tester", "quickcheck_bounded_forall", "quickcheck_depth",
    "quickcheck_exhaustive_active", "quickcheck_fast", "quickcheck_finite_functions",
    "quickcheck_finite_type_size", "quickcheck_finite_types",
    "quickcheck_full_support", "quickcheck_genuine_only", "quickcheck_iterations",
    "quickcheck_locale", "quickcheck_narrowing_active",
    "quickcheck_narrowing_ghc_options", "quickcheck_narrowing_overlord",
    "quickcheck_no_assms", "quickcheck_optimise_equality", "quickcheck_pretty",
    "quickcheck_prolog_active", "quickcheck_quiet", "quickcheck_random_active",
    "quickcheck_report", "quickcheck_size", "quickcheck_slow_smart_exhaustive_active",
    "quickcheck_smart_exhaustive_active", "quickcheck_smart_quantifier",
    "quickcheck_tag", "quickcheck_timeout", "quickcheck_timing",
    "quickcheck_use_subtype", "quickcheck_verbose", "quot_del", "quot_equiv",
    "quot_lifted", "quot_map", "quot_preserve", "quot_respect", "quot_thm",
    "rcong_intros", "real_asymp_eval_eqs", "real_asymp_int_reify",
    "real_asymp_nat_reify", "real_asymp_preproc", "real_asymp_reify_simps",
    "recdef_cong", "recdef_simp", "recdef_wf", "record_codegen", "record_sort_updates",
    "record_timing", "record_type_abbr", "record_type_as_fields", "reflection",
    "reify", "relator_distr", "relator_domain", "relator_eq", "relator_eq_onp",
    "relator_mono", "rename_abs", "rotated", "rule", "rule_format", "rule_trace",
    "rulify", "sat_solver", "sat_trace", "show_abbrevs", "show_cases", "show_consts",
    "show_consts_markup", "show_hyps", "show_main_goal", "show_markup",
    "show_question_marks", "show_results", "show_reverted_improvements", "show_sorts",
    "show_structs", "show_tags", "show_types", "show_variants", "simp", "simp_break",
    "simp_debug", "simp_depth_limit", "simp_trace", "simp_trace_depth_limit",
    "simp_trace_new", "simplified", "simproc", "sledgehammer_atp_completish",
    "sledgehammer_atp_full_names", "sledgehammer_atp_problem_dest_dir",
    "sledgehammer_atp_problem_prefix", "sledgehammer_atp_proof_dest_dir",
    "sledgehammer_fact_duplicates", "sledgehammer_isar_trace",
    "sledgehammer_mash_trace", "sledgehammer_mepo_trace",
    "sledgehammer_minimize_binary_min_facts", "sledgehammer_preplay_trace",
    "sledgehammer_smt_builtins", "sledgehammer_smt_triggers", "smt_arith_combine",
    "smt_arith_multiplication", "smt_arith_simplify", "smt_certificates",
    "smt_cvc_lethe", "smt_debug_arith_verit", "smt_debug_verit",
    "smt_explicit_application", "smt_higher_order", "smt_infer_triggers",
    "smt_monomorph_instances", "smt_monomorph_limit", "smt_nat_as_int", "smt_oracle",
    "smt_problem_dest_dir", "smt_proof_dest_dir", "smt_random_seed",
    "smt_read_only_certificates", "smt_reconstruction_step_timeout", "smt_sat_solver",
    "smt_solver", "smt_spy_verit", "smt_spy_z3", "smt_statistics", "smt_timeout",
    "smt_trace", "smt_verbose", "smt_verit_strategy", "solve_direct_max_solutions",
    "solve_direct_strict_warnings", "sos_debug", "sos_trace", "split", "split_format",
    "split_rule", "subst", "swapped", "sym", "symmetric", "syntax_ambiguity_limit",
    "syntax_ambiguity_warning", "syntax_ast_stats", "syntax_ast_trace", "tagged",
    "tendsto_intros", "termination_simp", "test_code_debug", "thin",
    "thy_output_break", "thy_output_cartouche", "thy_output_display",
    "thy_output_indent", "thy_output_margin", "thy_output_modes", "thy_output_quotes",
    "thy_output_source", "thy_output_source_cartouche", "time_prefix",
    "time_prefix_snd", "time_suffix", "to_pred", "to_set", "trace_locales", "trans",
    "transfer_domain_rule", "transfer_rule", "transferred", "try0_default_timeout",
    "try0_schedule", "typedef_overloaded", "uncurry", "unfold_abs_def", "unfolded",
    "uniform_limit_intros", "unify_search_bound", "unify_trace", "unify_trace_bound",
    "unify_trace_failure", "unify_trace_simp", "unify_trace_types", "untagged",
    "untransferred", "values_timeout", "vampire_smt_dt_options",
    "vampire_smt_nodt_options", "vector_add_divide_simps", "verit_compress_proofs",
    "verit_options", "where", "z3_extensions", "z3_options", "z3_rule"
  )

  /* Method-argument modifiers parsed inline by individual methods, so they have
     no declaration site of their own and appear in no dumped namespace; a
     short, auditable tier-2 list beside the source-derived tables. */
  val ARG_MODIFIERS: Set[String] = Set("add", "del", "only", "OF", "THEN")


  /* --- the resolved table, as a value ------------------------------------ */

  /* ONE table, immutable, carried by parameter.  Until P10 this was four
     `@volatile var`s and a `configure` that rebound them, and every resident
     host had to work around that separately: the warm server restored the
     committed default before each request under one lock, the jEdit plugin
     rebound under its own monitor, and no two projects could be queried at
     once in one JVM.  A value has none of those obligations — the same shape
     P9 gave `Reach`'s mode [p10-namespace-value]. */
  final case class Table(
    methods: Set[String],
    attributes: Set[String],
    /* Held as its own field, and readable as one, because the shape width
       classifier asks a DIFFERENT question from the router: "is this
       identifier term syntax?" is methods ∪ attributes ∪ keywords, and
       deliberately NOT `non_citation`, which also carries `ARG_MODIFIERS`
       (`add`, `del`, `only`) — inline method arguments, which are not
       constants. */
    keywords: Set[String]
  ) {
    /* The router's reject-set.  Lazy, so resolving a table for a `find` or a
       `grep` — a verb that never asks the router anything — costs nothing but
       three field writes. */
    lazy val non_citation: Set[String] = methods | attributes | keywords | ARG_MODIFIERS
  }

  /* The broad HOL-family union: THE default, for the CLI and for a direct
     engine caller alike. */
  val census: Table = Table(CENSUS_METHODS, CENSUS_ATTRIBUTES, KEYWORDS)

  /* The minimal Pure floor — the one explicit step DOWN, for a project whose
     base logic is positively not HOL. */
  val pure: Table = Table(PURE_METHODS, PURE_ATTRIBUTES, KEYWORDS)


  /* --- base-logic classification ----------------------------------------- */

  /* The distribution's non-HOL object logics, recognisable by NAME even from a
     single session's scope — which is what lets a project be stepped down
     without resolving a heap.  `ZF-*` and `FOL*` are caught by prefix. */
  private val nonhol_bases: Set[String] =
    Set("Pure", "FOL", "FOLP", "CTT", "Sequents", "CCL", "Cube", "LCF")

  /* Positively identified as NOT HOL.  Defaults the other way from an
     `is_hol_base` test: an UNKNOWN base (an out-of-scope parent session name
     reached under `-R <sub-session>`) is left to the HOL default rather than
     flagged non-HOL. */
  def is_known_nonhol_base(base: String): Boolean =
    nonhol_bases(base) || base.startsWith("ZF") || base.startsWith("FOL")

  /* Follow a session's parent chain to its ROOT — the first ancestor that the
     corpus does not itself declare.  A session two hops from its base
     (`Forcing` -> `ZF-Constructible`) must classify by the root, which an
     immediate-parent test gets wrong.  Cycle-guarded. */
  def resolve_base_logic(name: String, parents: Map[String, String]): String = {
    val seen = scala.collection.mutable.Set.empty[String]
    var cur = name
    var go = true
    while (go) {
      parents.get(cur) match {
        case Some(p) if p.nonEmpty && !seen(p) => seen += cur; cur = p
        case _ => go = false
      }
    }
    cur
  }
}
