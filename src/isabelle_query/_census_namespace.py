"""Broad (HOL-family union) method / attribute namespace — the router's
**import-time default** and the table `shape census` binds.  GENERATED, do not
edit.

Unlike the minimal Pure fallback in ``_isabelle_namespace.py`` (which the *per-
project* verbs narrow to a session-exact table at runtime), the whole-corpus
census needs one **fixed, broad, reproducible** table: a census spans many
logics with no single session to resolve against, and its output ships in
``data/`` so it must regenerate identically with **no Isabelle**.  This is that
table — the union of the base-logic heaps below, over the Pure floor.

It is also what ``graph`` binds at import, so a caller using the package as a
library gets the same numbers a ``query`` run prints without configuring
anything; the Pure floor is reached deliberately, via
``graph.use_pure_namespace()``, and only a positively non-HOL project wants it.

Why a union is correct here (not just convenient): of the three census axes that
read the table, M5a fan-in never consults it (a separate fixed extractor), and
the automation axis reads it only in ``by``/``apply``/``proof`` *introducer*
position — where a match is a real method by construction, so a broader table
only adds correct recognitions.  Only the M1/M5b free-identifier estimator is
position-blind, and there a union can over-exclude a variable whose name
collides with a foreign-logic method; that sliver is measured, not assumed
(see ``scripts/census_table_sensitivity.py``).

Regenerate with::

    python3 scripts/extract_isabelle_namespace.py --census

Provenance:
    Isabelle:    Isabelle2025-2
    Extracted:   2026-07-24
    Union of:    HOL, HOL-Library, HOL-Analysis, HOL-Eisbach, HOL-Decision_Procs
    Methods:     211 (union of the above heaps' method name spaces)
    Attributes:  390 (union of the above heaps' attribute name spaces)

Keywords are logic-invariant (Pure outer syntax), so the census reuses
``_isabelle_namespace.KEYWORDS`` and this module carries none.
"""

# proof methods across the HOL family — `by auto`, `by (induct n)`, `by metis`.
PROOF_METHODS = frozenset({
    '-', 'abs_used', 'add_my_thms', 'add_simp', 'algebra', 'all', 'all_args',
    'apply_A', 'approximation', 'argo', 'arith', 'assumption', 'atomize',
    'atomize_elim', 'auto', 'ball_simp', 'basic_metric_arith', 'best',
    'bestsimp', 'blast', 'case_tac', 'cases', 'catch', 'changed', 'clarify',
    'clarify_step', 'clarsimp', 'cleaning', 'code_simp', 'coherent',
    'coinduct', 'coinduction', 'contradiction', 'cooper', 'corec_unique',
    'countable_datatype', 'cut_tac', 'declares_test\\<^sub>1', 'deepen',
    'defer_tac', 'descending', 'descending_setup', 'determ', 'dist_refl_sym',
    'dlo', 'drule', 'drule_tac', 'dynamic_thms_test', 'elim', 'elim_exists',
    'elim_exists_loop', 'elim_sup', 'erule', 'erule_tac', 'eval',
    'eventually_elim', 'fact', 'fail', 'fails', 'fast', 'fastforce',
    'ferrack', 'field', 'find_fact', 'find_goal', 'find_points', 'find_type',
    'fol_solver', 'fold', 'foo', 'foo_method1', 'foo_method3', 'force',
    'frpar', 'frpar2', 'frule', 'frule_tac', 'goal_cases', 'guess_all',
    'guess_ex', 'higher_order_example', 'hypsubst', 'hypsubst_thin', 'iff',
    'ind_cases', 'induct', 'induct_tac', 'induction', 'induction_schema',
    'injection', 'insert', 'inst_existentials', 'inst_step', 'intro',
    'intro_classes', 'intro_locales', 'iprover', 'lexicographic_order',
    'lifting', 'lifting_setup', 'lin_real_arith', 'linarith', 'match',
    'match_test', "match_test'", 'measurable', 'meson', 'metis', 'metric',
    'metric_eisbach', 'mir', 'moura', 'my_allE\\<^sub>1', 'my_allE\\<^sub>2',
    'my_allE\\<^sub>3', 'my_allE\\<^sub>4', 'my_intros', "my_intros'",
    'my_simp\\<^sub>1', 'my_spec', 'my_spec_guess', 'my_spec_guess2', 'norm',
    'normalization', 'order', 'partiality_descending',
    'partiality_descending_setup', 'pat_completeness', 'pre_arith',
    'prefer_last', 'presburger', 'print_conclusion', 'print_fact',
    'print_headgoal', 'print_raw_goal', 'print_term', 'print_type',
    'prop_solver', 'raw_tactic', 'real_asymp', 'recursion_example',
    "recursion_example'", 'reflection', 'regularize', 'reify', 'relation',
    'rename_tac', 'repeat_new', 'rewr_maxdist', 'rewr_metric_eq', 'rewrite',
    'rferrack', 'ring', 'rotate_tac', 'rtrancl', 'rtranclp', 'rule',
    'rule_my_thms', "rule_my_thms'", 'rule_tac', 'safe', 'safe_step', 'sat',
    'satx', 'simp', 'simp_all', 'simplesubst', 'size_change', 'sleep',
    'slow', 'slow_step', 'slowsimp', 'smt', 'solve_P', 'solves', 'sos',
    'split', 'standard', 'step', 'subgoal_tac', 'subproofs', 'subst',
    'succeed', 'succeeds', 'tactic', 'test2', 'test_method', "test_method'",
    'thin_tac', 'this', 'timeit', 'trancl', 'tranclp', 'transfer',
    "transfer'", 'transfer_end', 'transfer_prover', 'transfer_prover_end',
    'transfer_prover_eq', 'transfer_prover_start', 'transfer_start',
    "transfer_start'", 'transfer_step', 'uint_arith', 'unat_arith', 'unfold',
    'unfold_locales', 'use', 'uses_test\\<^sub>1', 'uses_test\\<^sub>2',
    'vector',
})

# attributes across the HOL family — `[simp]`, `[intro]`, `[measurable]`.
ATTRIBUTES = frozenset({
    'ML_catch_all', 'ML_debugger', 'ML_environment', 'ML_exception_debugger',
    'ML_exception_trace', 'ML_print_depth', 'ML_read_global',
    'ML_source_trace', 'ML_write_global', 'OF', 'THEN', 'abs_def',
    'ac_simps', 'algebra', 'algebra_simps', 'algebra_split_simps',
    'analytic_intros', 'approximation_preproc', 'argo_timeout', 'argo_trace',
    'arith', 'asymp_equiv_intros', 'asymp_equiv_simps', 'atomize',
    'atomize_elim', 'atp_proof_cartouches', 'attribute', 'bilinear_simps',
    'bit_simps', 'blast_depth_limit', 'bnf_internals',
    'bnf_n2m_cache_threshold', 'bnf_timing', 'bnf_typedef_threshold',
    'bounded_bilinear', 'bounded_linear', 'bounded_linear_intros',
    'cancelation_simproc_eq_elim', 'cancelation_simproc_post',
    'cancelation_simproc_pre', 'case_conclusion', 'case_names',
    'case_product', 'case_translation', 'cases', 'cases_open', 'cite_macro',
    'code', 'code_abbrev', 'code_computation_unfold',
    'code_only_single_equation', 'code_post', 'code_pred_def',
    'code_pred_inline', 'code_pred_intro', 'code_pred_simp',
    'code_prepend_allowed', 'code_preproc_trace', 'code_runtime_trace',
    'code_simp_trace', 'code_strict_drop', 'code_test_ghc', 'code_timing',
    'code_unfold', 'coercion', 'coercion_args', 'coercion_delete',
    'coercion_enabled', 'coercion_map', 'coherent_trace', 'coinduct', 'cong',
    'cong_format', 'constraints', 'consumes', 'cont_intro',
    'continuous_intros', 'curry', 'cvc4_options', 'cvc5_options',
    'cvc5_proof_options', 'cvc_extensions', 'datatype_record_update',
    'declare_facts\\<^sub>1', 'default_code_width', 'defn',
    'derivative_intros', 'dest', 'divide_const_simps', 'divide_simps',
    'dummy_smtlib_options', 'elim', 'elim_format', 'elims', 'eta_contract',
    'eventuallized', 'exp_log_eval_constructor', 'extraction_expand',
    'extraction_expand_def', 'ferrack', 'field_simps', 'field_split_simps',
    'folded', 'foo', 'fps_expansion_intros', 'friend_of_corec_simps',
    'function_internals', 'fundef_cong', 'goals_limit', 'holomorphic_intros',
    'hypsubst_thin', 'id_simps', 'iff', 'ind_realizer', 'induct',
    'induct_simp', 'inductive_internals', 'intro', 'intros', 'kind',
    'kodkod_scala', 'landau_divide_simps', 'langford', 'langfordsimp',
    'lifting_restore', 'lifting_restore_internal', 'linarith_neq_limit',
    'linarith_split', 'linarith_split_limit', 'linarith_trace',
    'lipschitz_intros', 'mapQ3', 'measurable', 'measurable_cong',
    'measurable_debug', 'measurable_dest', 'measurable_split',
    'measure_function', 'meson_max_clauses', 'meson_trace', 'meta',
    'metis_advisory_simp', 'metis_instantiate',
    'metis_instantiate_undefined', 'metis_new_skolem', 'metis_trace',
    'metis_verbose', 'metric_argo_timeout', 'metric_nnf', 'metric_pre_arith',
    'metric_prenex', 'metric_trace', 'metric_unfold', 'mod_simps', 'mono',
    'mono_set', 'monomorph_max_duplicated_instances',
    'monomorph_max_new_const_instances_per_round',
    'monomorph_max_new_instances', 'monomorph_max_rounds',
    'monomorph_max_schematics', 'monomorph_max_thm_instances',
    'my_thms_named', "my_thms_named'", 'names_long', 'names_short',
    'names_unique', 'native_bv', 'nbe_trace', 'nitpick_choice_spec',
    'nitpick_psimp', 'nitpick_simp', 'nitpick_unfold', 'no_atp', 'no_vars',
    'of', 'order_continuous_intros', 'order_split_limit', 'order_trace',
    'parametricity_preprocess', 'params', 'partial_function_mono',
    'pred_set_conv', 'presburger', 'prolog_system', 'prolog_timeout',
    'quick_and_dirty', 'quickcheck_abort_potential',
    'quickcheck_allow_existentials', 'quickcheck_allow_function_inversion',
    'quickcheck_approximation_active',
    'quickcheck_approximation_custom_seed',
    'quickcheck_approximation_epsilon', 'quickcheck_approximation_precision',
    'quickcheck_batch_tester', 'quickcheck_bounded_forall',
    'quickcheck_depth', 'quickcheck_exhaustive_active', 'quickcheck_fast',
    'quickcheck_finite_functions', 'quickcheck_finite_type_size',
    'quickcheck_finite_types', 'quickcheck_full_support',
    'quickcheck_genuine_only', 'quickcheck_iterations', 'quickcheck_locale',
    'quickcheck_narrowing_active', 'quickcheck_narrowing_ghc_options',
    'quickcheck_narrowing_overlord', 'quickcheck_no_assms',
    'quickcheck_optimise_equality', 'quickcheck_pretty',
    'quickcheck_prolog_active', 'quickcheck_quiet',
    'quickcheck_random_active', 'quickcheck_report', 'quickcheck_size',
    'quickcheck_slow_smart_exhaustive_active',
    'quickcheck_smart_exhaustive_active', 'quickcheck_smart_quantifier',
    'quickcheck_tag', 'quickcheck_timeout', 'quickcheck_timing',
    'quickcheck_use_subtype', 'quickcheck_verbose', 'quot_del', 'quot_equiv',
    'quot_lifted', 'quot_map', 'quot_preserve', 'quot_respect', 'quot_thm',
    'rcong_intros', 'real_asymp_eval_eqs', 'real_asymp_int_reify',
    'real_asymp_nat_reify', 'real_asymp_preproc', 'real_asymp_reify_simps',
    'recdef_cong', 'recdef_simp', 'recdef_wf', 'record_codegen',
    'record_sort_updates', 'record_timing', 'record_type_abbr',
    'record_type_as_fields', 'reflection', 'reify', 'relator_distr',
    'relator_domain', 'relator_eq', 'relator_eq_onp', 'relator_mono',
    'rename_abs', 'rotated', 'rule', 'rule_format', 'rule_trace', 'rulify',
    'sat_solver', 'sat_trace', 'show_abbrevs', 'show_cases', 'show_consts',
    'show_consts_markup', 'show_hyps', 'show_main_goal', 'show_markup',
    'show_question_marks', 'show_results', 'show_reverted_improvements',
    'show_sorts', 'show_structs', 'show_tags', 'show_types', 'show_variants',
    'simp', 'simp_break', 'simp_debug', 'simp_depth_limit', 'simp_trace',
    'simp_trace_depth_limit', 'simp_trace_new', 'simplified', 'simproc',
    'sledgehammer_atp_completish', 'sledgehammer_atp_full_names',
    'sledgehammer_atp_problem_dest_dir', 'sledgehammer_atp_problem_prefix',
    'sledgehammer_atp_proof_dest_dir', 'sledgehammer_fact_duplicates',
    'sledgehammer_isar_trace', 'sledgehammer_mash_trace',
    'sledgehammer_mepo_trace', 'sledgehammer_minimize_binary_min_facts',
    'sledgehammer_preplay_trace', 'sledgehammer_smt_builtins',
    'sledgehammer_smt_triggers', 'smt_arith_combine',
    'smt_arith_multiplication', 'smt_arith_simplify', 'smt_certificates',
    'smt_cvc_lethe', 'smt_debug_arith_verit', 'smt_debug_verit',
    'smt_explicit_application', 'smt_higher_order', 'smt_infer_triggers',
    'smt_monomorph_instances', 'smt_monomorph_limit', 'smt_nat_as_int',
    'smt_oracle', 'smt_problem_dest_dir', 'smt_proof_dest_dir',
    'smt_random_seed', 'smt_read_only_certificates',
    'smt_reconstruction_step_timeout', 'smt_sat_solver', 'smt_solver',
    'smt_spy_verit', 'smt_spy_z3', 'smt_statistics', 'smt_timeout',
    'smt_trace', 'smt_verbose', 'smt_verit_strategy',
    'solve_direct_max_solutions', 'solve_direct_strict_warnings',
    'sos_debug', 'sos_trace', 'split', 'split_format', 'split_rule', 'subst',
    'swapped', 'sym', 'symmetric', 'syntax_ambiguity_limit',
    'syntax_ambiguity_warning', 'syntax_ast_stats', 'syntax_ast_trace',
    'tagged', 'tendsto_intros', 'termination_simp', 'test_code_debug',
    'thin', 'thy_output_break', 'thy_output_cartouche', 'thy_output_display',
    'thy_output_indent', 'thy_output_margin', 'thy_output_modes',
    'thy_output_quotes', 'thy_output_source', 'thy_output_source_cartouche',
    'time_prefix', 'time_prefix_snd', 'time_suffix', 'to_pred', 'to_set',
    'trace_locales', 'trans', 'transfer_domain_rule', 'transfer_rule',
    'transferred', 'try0_default_timeout', 'try0_schedule',
    'typedef_overloaded', 'uncurry', 'unfold_abs_def', 'unfolded',
    'uniform_limit_intros', 'unify_search_bound', 'unify_trace',
    'unify_trace_bound', 'unify_trace_failure', 'unify_trace_simp',
    'unify_trace_types', 'untagged', 'untransferred', 'values_timeout',
    'vampire_smt_dt_options', 'vampire_smt_nodt_options',
    'vector_add_divide_simps', 'verit_compress_proofs', 'verit_options',
    'where', 'z3_extensions', 'z3_options', 'z3_rule',
})
