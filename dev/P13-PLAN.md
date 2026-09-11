# P13: reduce query memory

Preserve the source-only CLI and plugin contract: querying must work on broken,
unbuilt, and mid-refactor theories without a prover or semantic markup. Exact
source excerpts, dirty-buffer snapshots, path/session identity, Unicode,
ordering, command output, and each command's existing `-U/--context` default
must remain unchanged.

## Implemented design

- Source is retained as immutable independent blocks of at most 32,768 UTF-16
  code units, compressed when worthwhile and decoded on demand. Construction
  avoids a whole-theory joined string; exact snippets and full views remain
  available, including after the backing file changes or is deleted.
- Parsing uses a fixed queue of at most four worker loops and returns results in
  input order. Ordinary unreadable or malformed source remains skippable;
  cancellation, interruption, fatal failures, and fatal nodes anywhere in a
  bounded/cycle-safe cause chain propagate. Failed work does not publish a
  partial index.
- Server and jEdit refreshes stage complete immutable replacements and publish
  atomically. Cache keys include source and project identity; dirty buffers use
  exact text. In-flight invalidation publishes a coherent snapshot with an
  empty cache and reparses on the next refresh.
- The source cache budget counts logical normalized UTF-8 source bytes. It is
  not a compressed-storage, heap, RSS, graph, decoded-view, or refresh-overlap
  limit. Peak allocation and retained heap therefore require separate evidence.
- Repeating callers refreshes one scoped group, including changed, empty,
  failed, and recovered answers. Unrelated groups and expansion state remain.
  Delete removes a selection; deleting an entire result set cancels its pending
  refresh, but a deleted child group or hit may reappear when refresh completes.
  Clear removes all results and cancels all pending results. Excerpt reads
  decode only requested spans.

Optional semantic indexing and larger storage redesigns remain deferred to
[Maybe](MAYBE.md). Identifier postings cannot replace arbitrary regex search.

## Release gates

- [x] Visible Delete beside Clear; scoped callers refresh and stale-result guards.
- [x] Exact compressed snapshots and bounded excerpt reads.
- [x] Fixed worker queue capped at four, full-cause-chain fatal propagation,
  and atomic server/jEdit refresh with invalidation behavior. The worker-queue
  candidate passed 101 focused checks in normal and cap-1 runs; Astra approved
  its scheduler delta with zero findings.
- [x] Final worker-scheduler production freeze: integrated `make test` passed
  with 1,177 Scala passes, 78 explicit nonpass dispositions, 49 transport
  tests, and no failures. Isolated P12 integration passed 108 checks; the owned
  launcher suite passed 13 tests.
- [x] Earlier code reviews complete: Astra approved with no unresolved defect;
  Fable found no new correctness defect and accepted both targeted fixes by
  inspection. Astra and Fable separately approved the scheduler delta. Prior
  reviews of unchanged code/docs remain scoped to their earlier freeze.
- [x] Full selected-source benchmark and memory gate: 255/255 captures and
  69/69 series completed; all 43 comparison pairs were audited. Whole-AFP
  summary passed at 512 MiB, and retained heap fell from 647.6 to 450.0 MiB in
  the 4 GiB observation. Documented D12/D15 differences only.
- [x] Actual isolated jEdit GUI clickthrough passed repeated callers, dirty and
  empty refreshes, exact excerpts, navigation, Delete, and Clear; cleanup passed.
- [x] Compatibility input guard approved by Astra; focused missing/copy/drift
  regressions pass, and the corrected freeze includes `configs/`.
- [x] Corrected forced Python comparison passed: all 28 entry projections and
  all 2,149 cases over seven corpora, comprising 1,968 clean comparisons and
  181 documented D8/D15 pins, with zero failures or stale pins. Schema-2 config,
  oracle, layout, corpus and harness identities passed; the post-copy cheap
  check, owned shutdown marker, empty registry and exact process scan were clean.
- [x] All implementation, review, test, lifecycle, GUI, benchmark, memory and
  compatibility gates are complete.

Routine compatibility remains upstream-change-gated for ordinary development.
P13 publication is the explicit exception above: old P11 evidence and the cheap
unchanged-0.8.1 identity check are insufficient. Keep frozen Python at 0.8.1 and
Scala CLI/plugin metadata at 0.8.1-scala.0.2.
