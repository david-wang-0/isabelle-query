"""Isabelle method / attribute / keyword namespaces — GENERATED, do not edit.

Regenerate with::

    python3 scripts/extract_isabelle_namespace.py

Provenance:
    Isabelle:    Isabelle2025-2
    Source:      /Users/as456/projects/ndtht/isabelle-src
    Extracted:   2026-06-05
    Methods:     243 (method_setup + Method.setup)
    Attributes:  110 (attribute_setup + Attrib.setup)
    Keywords:    222 (Pure.thy keyword table + thy_header bootstrap)

These power isabelle_query's token *router*: a proof-body token is a fact
citation (a call-graph edge) only if it is none of a proof method, an
attribute, a keyword, or a numeral.  Method occurrences are not discarded —
they feed the `methods` query.
"""

# proof methods (`by simp`, `apply (rule r)`) — never a fact citation, but a
# method-usage datum.
PROOF_METHODS = frozenset({
    'EQgen', 'Fake_insert_simp', 'N', 'NE', 'S43_solve', 'S4_solve',
    'Seq_Finite_induct', 'Seq_case', 'Seq_case_simp', 'Seq_induct',
    'T_solve', 'abstraction', 'add_mp', 'algebra', 'all', 'always',
    'analz_freshCryptK', 'analz_freshK', 'analz_mono_contra',
    'analz_prepare', 'annhoare', 'approximation', 'argo', 'arith',
    'arith_rew', 'atomic_spy_analz', 'atomize_elim', 'auto',
    'auto_invariant', 'basic_possibility', 'best', 'best_dup', 'best_power',
    'best_safe', 'bestsimp', 'beta_rl', 'blast', 'cartouche', 'case_tac',
    'cases', 'catch', 'changed', 'clarify', 'clarify_step', 'clarsimp',
    'clean_ccs', 'cleaning', 'code_simp', 'coherent', 'coinduct',
    'coinduct3', 'coinduction', 'conjI_tac', 'contradiction', 'cooper',
    'corec_unique', 'countable_datatype', 'cut_tac', 'deepen', 'defer_tac',
    'defined', 'depth_solve', 'depth_solve1', 'descending',
    'descending_setup', 'determ', 'disentangle', 'disjE_tac', 'dlo',
    'drule_tac', 'enabled', 'ensures', 'ensures_tac', 'eq_coinduct3',
    'eqintr', 'equal', 'erule_tac', 'eval', 'eventually_elim', 'fails',
    'fast', 'fast_dup', 'fast_prop', 'fastforce', 'ferrack', 'field',
    'find_goal', 'finite_guess', 'finite_guess_debug', 'fixrec_simp',
    'force', 'form', 'fresh_fun_simp', 'fresh_guess', 'fresh_guess_debug',
    'frpar', 'frpar2', 'frule_tac', 'genIs', 'gen_ccs', 'generate_fresh',
    'hoare', 'hyp_arith_rew', 'hyp_rew', 'hypsubst', 'hypsubst_thin', 'iff',
    'incanT', 'ind_cases', 'induct', 'induct_tac', 'induction_schema',
    'inj_rl', 'injection', 'inst_existentials', 'inst_step', 'interfree_aux',
    'intr', 'intro_classes', 'intro_locales', 'invariant', 'lem',
    'lexicographic_order', 'lifting', 'lifting_setup', 'linarith', 'machin',
    'match', 'merge_act_box', 'merge_box', 'merge_stp_box', 'merge_temp_box',
    'meson', 'metis', 'metis_exhaust', 'metric', 'mir', 'mkex_induct',
    'ml_tactic', 'moura', 'mp', 'my_method1', 'my_method2', 'my_method3',
    'my_simp', "my_simp'", 'my_simp_all', 'ncanT', 'nominal_induct',
    'normalization', 'ns_induct', 'oghoare', 'order', 'pair', 'pair_induct',
    'partiality_descending', 'partiality_descending_setup', 'parts_explicit',
    'parts_prepare', 'pat_completeness', 'pc', 'perm_extend_simp',
    'perm_extend_simp_debug', 'perm_simp', 'perm_simp_debug', 'possibility',
    'prefer_last', 'prepare', 'presburger', 'print_headgoal',
    'print_raw_goal', 'prolog', 'ptac', 'rawsat', 'real_asymp',
    'record_auto', 'reflection', 'regularize', 'reify', 'relation',
    'rename_client_map', 'rew', 'rewrite', 'rferrack', 'ring', 'rtrancl',
    'rtranclp', 'rule', 'rule_tac', 'safe', 'safe_step', 'safety', 'sat',
    'satx', 'sc_analz_freshK', 'simp', 'simp_all', 'simplesubst',
    'size_change', 'slow', 'slow_step', 'slowsimp', 'smt', 'sos', 'split',
    'split_idle', 'spy_analz', 'standard', 'step', 'strip_asms',
    'subgoal_tac', 'subst', 'succeeds', 'supports_simp',
    'supports_simp_debug', 'synth_analz_mono_contra', "test_method'",
    'thin_tac', 'timeit', 'trancl', 'tranclp', 'transfer', "transfer'",
    'transfer_end', 'transfer_prover', 'transfer_prover_end',
    'transfer_prover_eq', 'transfer_prover_start', 'transfer_start',
    "transfer_start'", 'transfer_step', 'typecheck', 'typechk', 'uint_arith',
    'unat_arith', 'unfold_locales', 'valid_certificate_tac', 'vcg',
    'vcg_simp', 'vcg_tc', 'vcg_tc_simp', 'vector', 'wfd_strengthen',
})

# attributes (`[OF g]`, `simp del:`, `[simp]`) — modifiers, never citations.
ATTRIBUTES = frozenset({
    'TC', 'action_rewrite', 'action_unlift', 'action_use', 'algebra', 'all',
    'bounded_bilinear', 'bounded_linear', 'cancel_type_definition',
    'case_product', 'case_translation', 'code', 'code_abbrev',
    'code_computation_unfold', 'code_post', 'code_pred_intro',
    'code_preproc_trace', 'code_unfold', 'coercion', 'coercion_args',
    'coercion_delete', 'coercion_map', 'cong', 'cong_format', 'curry',
    'dest', 'elim', 'eqvt', 'eqvt_force', 'eventuallized',
    'extraction_expand', 'extraction_expand_def', 'ferrack', 'flatten',
    'fundef_cong', 'get_split_rule', 'iff', 'import_const', 'import_type',
    'ind_realizer', 'int_rewrite', 'int_unlift', 'int_use',
    'internalize_sort', 'intro', 'langford', 'langfordsimp',
    'lifting_restore', 'lifting_restore_internal', 'linarith_split', 'mapQ3',
    'measurable', 'measurable_cong', 'measurable_dest', 'meta', 'mono',
    'mono_set', 'my_declaration', 'my_rule', 'normalized', 'of',
    'pred_set_conv', 'presburger', 'quot_del', 'quot_lifted', 'quot_map',
    'recdef_cong', 'recdef_simp', 'recdef_wf', 'reflection', 'reify',
    'relator_distr', 'relator_mono', 'rule', 'safe', 'simp', 'simp_break',
    'simp_trace_new', 'simplified', 'simproc', 'smt_certificates',
    'smt_solver', 'split', 'split_format', 'split_rule', 'statefun_simp',
    'swapped', 'sym', 'symmetric', 'temp_rewrite', 'temp_unlift', 'temp_use',
    'thin', 'to_pred', 'to_set', 'trans', 'transfer_domain_rule',
    'transfer_intro', 'transfer_refold', 'transfer_rule', 'transfer_unfold',
    'transferred', 'try_rewrite', 'uncurry', 'unoverload', 'unoverload_type',
    'unsafe', 'untransferred', 'where', 'z3_rule',
})

# outer-syntax keywords (commands, proof language, quasi-commands, bootstrap):
# structural, never a fact citation.
KEYWORDS = frozenset({
    'ML_command', 'ML_export', 'ML_file', 'ML_file_debug',
    'ML_file_no_debug', 'ML_prf', 'ML_val', 'ROOTS_file', 'SML_export',
    'SML_file', 'SML_file_debug', 'SML_file_no_debug', 'SML_import',
    'abbreviation', 'abbrevs', 'adhoc_overloading', 'alias', 'also', 'and',
    'apply', 'apply_end', 'assume', 'assumes', 'attribute_setup',
    'axiomatization', 'back', 'begin', 'bibtex_file', 'binder', 'bundle',
    'by', 'case', 'chapter', 'class', 'class_deps', 'code_datatype',
    'compile_generated_files', 'congproc', 'consider', 'constrains',
    'consts', 'context', 'corollary', 'declaration', 'declare',
    'default_sort', 'defer', 'define', 'defines', 'definition', 'done',
    'end', 'experiment', 'export_classpath', 'export_generated_files',
    'external_file', 'extract', 'extract_type', 'finally', 'find_consts',
    'find_theorems', 'fix', 'fixes', 'for', 'from', 'full_prf',
    'generate_file', 'global_interpretation', 'have', 'help', 'hence',
    'hide_class', 'hide_const', 'hide_fact', 'hide_type', 'identifier', 'if',
    'imports', 'in', 'include', 'includes', 'including', 'infix', 'infixl',
    'infixr', 'instance', 'instantiation', 'interpret', 'interpretation',
    'is', 'judgment', 'keywords', 'lemma', 'lemmas', 'let', 'local_setup',
    'locale', 'locale_deps', 'method_setup', 'moreover', 'named_theorems',
    'next', 'no_adhoc_overloading', 'no_notation', 'no_syntax',
    'no_translations', 'no_type_notation', 'nonterminal', 'notation', 'note',
    'notepad', 'notes', 'obtain', 'obtains', 'oops', 'open', 'open_bundle',
    'opening', 'oracle', 'output', 'overloaded', 'overloading', 'paragraph',
    'parse_ast_translation', 'parse_translation', 'passive', 'pervasive',
    'prefer', 'premises', 'presume', 'prf', 'print_ML_antiquotations',
    'print_abbrevs', 'print_antiquotations', 'print_ast_translation',
    'print_attributes', 'print_bundles', 'print_cases', 'print_classes',
    'print_codesetup', 'print_commands', 'print_context',
    'print_context_tracing', 'print_definitions', 'print_defn_rules',
    'print_facts', 'print_interps', 'print_locale', 'print_locales',
    'print_methods', 'print_options', 'print_rules', 'print_simpset',
    'print_state', 'print_statement', 'print_syntax', 'print_term_bindings',
    'print_theorems', 'print_theory', 'print_trans_rules',
    'print_translation', 'private', 'proof', 'prop', 'proposition', 'qed',
    'qualified', 'realizability', 'realizers', 'rewrites',
    'scala_build_generated_files', 'schematic_goal', 'section', 'setup',
    'show', 'shows', 'simproc_setup', 'sorry', 'structure', 'subclass',
    'subgoal', 'sublocale', 'subparagraph', 'subsection', 'subsubsection',
    'supply', 'syntax', 'syntax_consts', 'syntax_declaration',
    'syntax_types', 'term', 'text', 'text_raw', 'then', 'then have',
    'then show', 'theorem', 'thm', 'thm_deps', 'thm_oracles', 'thus',
    'thy_deps', 'translations', 'txt', 'typ', 'type_alias', 'type_notation',
    'type_synonym', 'typed_print_translation', 'typedecl', 'ultimately',
    'unbundle', 'unchecked', 'unfolding', 'unused_thms', 'using',
    'weak_congproc', 'welcome', 'when', 'where', 'with', 'write',
})
