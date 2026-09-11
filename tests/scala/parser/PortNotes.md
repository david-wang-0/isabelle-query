# Parser regression port

The frozen Scala cases preserve the original test IDs, source strings, expected
values, assertions, and loops. `ParserValues` adapts collections and records;
`ParserBridge` actuals invoke production `Theory`, `Entries`, `Regions`, `Shape`,
`Render`, `Commands`, and `Usage_Graph` APIs. Unknown fields or operations throw
an explicit assertion failure. `ParserReference` is the original deliberately
independent per-name graph algorithm; it does not call `build_call_graph`.

`section_from` uses the native header lexer to select a matching temporary
basename before calling `Theory.header_keywords`, because the native header
reader checks file/theory agreement and the Python fixture helper did not.
Fixture source bytes are not rewritten. Explicit `extract_entries(custom=...)`
uses only its supplied table. Every generated case gets a fresh local variable
map; this is test-local state, not process environment or a parser cache.

## Explicit dispositions and API adaptations

- Five Python export/import dependency checks remain `python-only`. Mixed span
  and root semantics are ported, including cross-theory keyword unions and
  independent roots. Diagnosed unusable-root behavior is exercised through
  public `CLI.Session.load_index`, the Scala diagnosed root-loading boundary;
  low-level `Theory.parse_root` is used for ordinary successful parses.
- Five corpus cases retain all original loops, the 120-file independent-reference
  subset, and the original thresholds. They are `optional` and run when
  `ISABELLE_QUERY_CORPUS` selects a directory. The AOT-specific case also needs
  `AOT/AOT_PLM.thy`, as in the original. These are not counted as fast-suite passes.
- Two Python cached-list identity checks are `known-difference`, authorized for
  the documented `Theory_Section` computed-view design. Scala checks exact byte
  equality, distinct result arrays, and that mutating a result cannot mutate
  another result or stored source. No shared divergence document is edited.
- The quote-aware balanced-scanner case reaches production's public
  `Shape.scan_inductions` with the exact quoted-close term from the original
  seven-character fixture. An arbitrary clause after the quoted close must
  survive, proving that the quote did not terminate the method call.
- Method counts use the original Python Counter data semantics: a missing
  count is zero, while an unknown model field or bridge operation still fails.

## Regression findings and resolution

Two original `@expectedFailure` cases retain their desired assertions under
`TestSupport.expectedFailure`. They are explicit nonpass dispositions; an
unexpected success fails the suite. Only the shared `CheckFailure` qualifies,
so unknown bridge operations and runtime faults cannot masquerade as XFAIL.

- `test_decl_body_comment.TheBlankLineVariantIsStillOpen.test_a_blank_before_the_note`
  still requires the name `transient` and body end >= 7. The known blank-before-
  note gap truncates that body; no broad span change was made because the
  original test records the rejected fix's containment regression.
- `test_known_failures.RecoverableParserGaps.test_infix_abbreviation_operator_name`
  still requires `\<oplus>`, rather than accepting operand `x` as the operator.
  Mixfix-aware equation parsing remains outside this change.

The two previously failing legacy-verbatim cases now pass their original
predicates. The authorized production change in `query_base/src/regions.scala`
adds a legacy token alternative at native token boundaries and marks it as
non-Isar noise. It does not mask raw text globally or replace native handling
of quotes, comments, or cartouches. Input without `{*` uses the unchanged
`Token.explode` path, and lexer-error recovery stays unchanged.

`ParserLegacy` extends those same two IDs with closed, multiline, unterminated,
and quote-looking legacy content; quoted/backquoted/cartouche literals remain
live. Exact source columns, `open_at` flags, source preservation, equal line
lengths, and following-declaration visibility are asserted. These are ordinary
passing regressions, not divergence dispositions.

## Maintenance and validation

`python3 tests/scala/parser/generate_parser.py` checks frozen-source
reproducibility and manifest IDs; `--write` regenerates only the mechanical
cases. It reads the original source AST, never imports the Python tests or runs
an oracle. `source-audit.json` pins all 27 source hashes and enumerates every
original assertion/loop site. Changed upstream source requires an explicit audit
update before regeneration; handwritten cases must be reviewed alongside it.
The routine `dev/scala-tests.sh --suite parser` never invokes this generator.

The parser-only runner compiled the suite and reconciled 462/462 runtime IDs
with the manifest. Final full-group validation passed with 448 ordinary passes and 14 explicit nonpass
dispositions: five Python-only, five optional corpus, two computed-view
differences, and two strict original expected failures. The runner recorded 18
subcase notes, zero failures, and exact 462/462 ID agreement. An isolated two-entry corpus
fixture exercised all five opt-in code paths with 11 assertions and no failures;
this checks the optional runner path, not full-AFP corpus acceptance. No original
Python test, default-home build, or full compatibility matrix was run.

The affected legacy validation passed 76 cases and 608 assertions. All 27 original
Python source hashes still match the migration inventory. Added shared files
contain no personal paths or private inventory links.
