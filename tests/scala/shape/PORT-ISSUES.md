# Shape port findings

## Census load failures were silently counted as loaded — fixed and verified

Before the fix, the full direct Scala suite exercised all 203 original IDs:
201 passed and these two failed, with no dispositions:

- `test_census_sessions.ExitContract.test_all_sessions_skipped_is_an_error`:
  expected exit 2; observed exit 0.
- `test_census_sessions.ExitContract.test_partial_failure_succeeds_but_says_so`:
  expected `1 of 2 session(s) skipped` on stderr; observed empty stderr.

Reproduce with `dev/scala-tests.sh --suite shape`. The fixtures in
`ShapeCli.scala` declare a theory whose `.thy` path is a directory. This
replaces Python's monkey-patched throwing session loader with a real read
failure and retains its exit, stderr and record assertions. The partial
fixture has a second session with a readable theory and one measurable proof.

### Cause

`Discovery.resolve_session_theory` accepts the existing path. The session
loader `CLI.Session.sections_for_session` called `Theory.parse`, which catches
every throwable and returns `None`. The session returned an empty section
list, so `cmd_shape_census` incremented `loaded` and the CLI could not
distinguish a failed read from an honest zero. Python's
`parsing._add_one_section` calls `_parse_one` without catching its file-read
exception; the census catches that exception at the session boundary. This is
source-derived Python evidence; no Python runtime oracle was run.

### Fix applied

`sections_for_session` now parses each theory with `Theory.parse_one` over
`Theory.read`, so a read or parse failure propagates to the census's
per-session guard, which reports the session as skipped. The keyword-header
union is still built from the session's own headers. The whole session is
parsed (under `Par_List.map`, which re-raises the first failure) before the
shared `seen` set is touched, so a failed session claims no theory and a
later session can still own a theory it shares with the failed one. The
whole-root loader `sections_from_dir` keeps its tolerant `Theory.parse` path:
a corpus sweep must not stop at a single unreadable file.

`Dedup.test_a_theory_claimed_twice_is_emitted_once` gained a subcase over a
four-session ROOT (`Prior`, `Failed`, `Winner`, `Again`) that checks both the
failure atomicity of `seen` through direct `sections_for_session` calls and
the first-owner rule through `shape census`: exit 0, `1 of 4 session(s)
skipped` naming `Failed`, and exactly the records `Prior`/`Prior` and
`Shared`/`Winner`. The two ExitContract tests are unchanged.

Verification: `dev/scala-tests.sh --suite shape` freshly compiled the private
engine and shape suite, then exited 0 with **203 passed, 0 failures, 0 explicit
nonpass dispositions, 31 subcase notes, 203/203 IDs**. All seven original Python
module hashes are unchanged. The coverage manifest still maps every original
ID as `ported`; the added dedup regression is a subcase of its existing ID.

Snapshot comparison confirms that the only CLI edits are the census session
method and its explanatory comment, preserving the preexisting P11 changes.
No Python oracle, compatibility matrix, installation or live-process restart
was used. Parent review and subsequent independent verification are pending.

## D15 resume key description contradicted both implementations — corrected

The final D15 bullet in `dev/DIVERGENCES.md` described census resume keys as
`(session, theory)`. `Shape_Cmds.load_done`, `cmd_shape_census`, and Python's
`shape_cmds._load_done` use `(theory, lemma)`; the bullet now says so. The
Scala port retains the original resume assertions. This is a documentation
correction, not a new semantic disposition.
