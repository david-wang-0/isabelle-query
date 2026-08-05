r"""Heap discovery searches BOTH heap directories (issue #4).

Isabelle keeps session heaps in two places, named in its own `etc/settings`:

    ISABELLE_HEAPS="$ISABELLE_HOME_USER/heaps"      # locally built
    ISABELLE_HEAPS_SYSTEM="$ISABELLE_HOME/heaps"    # shipped with the release

`query` consulted only the first.  On a stock install nothing has ever been
built into the user directory, so the prebuilt `HOL` shipped in the distribution
was invisible: no heap found, no dump attempted, and the router fell back to the
committed table without saying so.

None of this is observable on a machine that has built HOL locally — it has the
session in both directories — so every test here constructs a synthetic install
under a temporary directory and never looks at a real heap.  A test that reached
for the developer's own Isabelle would pass on the machine the bug was reported
from and on the machine it was fixed on, while telling us nothing.

`ISABELLE_HEAPS` and `ISABELLE_HEAPS_SYSTEM` are pinned explicitly in every
test, never merely defaulted: a developer with either set in their shell would
otherwise get different results from CI.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
from isabelle_query import _namespace_resolve as nr  # noqa: E402

VID = "Isabelle2099-9"
PLAT = "polyml-9.9.9_test-platform"


def _install(tmp, user_sessions=(), system_sessions=()):
    """A synthetic Isabelle: a versioned `bin/isabelle`, a distribution heaps
    tree beside it, and a separate user heaps tree.  Returns
    ``(binary, user_heaps, system_heaps)``.

    `tmp` is resolved first because `_isabelle_home` resolves the binary, and on
    macOS a temporary directory lives under the `/var` -> `/private/var`
    symlink — comparing a resolved path against an unresolved one fails for a
    reason that has nothing to do with heaps."""
    tmp = Path(tmp).resolve()
    home = Path(tmp) / f"{VID}.app"
    binp = home / "bin" / "isabelle"
    binp.parent.mkdir(parents=True)
    binp.write_text("#!/bin/sh\n")
    system = home / "heaps"
    user = Path(tmp) / "user" / ".isabelle" / VID / "heaps"
    for root, names in ((user, user_sessions), (system, system_sessions)):
        (root / PLAT).mkdir(parents=True, exist_ok=True)
        for n in names:
            (root / PLAT / n).write_bytes(b"HEAP")
    return str(binp), user, system


def _env(**kw):
    """Patch the environment with the two heap variables under full control:
    anything not named is REMOVED, so an ambient setting cannot leak in."""
    ctx = mock.patch.dict(os.environ, {k: v for k, v in kw.items() if v})
    ctx.start()
    for var in ("ISABELLE_HEAPS", "ISABELLE_HEAPS_SYSTEM"):
        if not kw.get(var):
            os.environ.pop(var, None)
    return ctx


class SearchPath(unittest.TestCase):
    def test_user_directory_comes_first(self):
        """Isabelle's own `Store.input_dirs` order — a locally rebuilt session
        shadows the shipped one, so query must agree or it would fingerprint a
        heap that `ML_process` will not load."""
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, system = _install(tmp)
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                dirs = nr._heaps_dirs(VID, binp)
            finally:
                ctx.stop()
            self.assertEqual(dirs, [str(user), str(system)])

    def test_system_directory_is_derived_from_the_binary(self):
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, system = _install(tmp)
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                self.assertEqual(nr._heaps_dirs(VID, binp)[1], str(system))
                self.assertEqual(nr._isabelle_home(binp), system.parent)
            finally:
                ctx.stop()

    def test_an_explicit_system_variable_wins(self):
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, _ = _install(tmp)
            override = str(Path(tmp) / "elsewhere")
            ctx = _env(ISABELLE_HEAPS=str(user),
                       ISABELLE_HEAPS_SYSTEM=override)
            try:
                self.assertEqual(nr._heaps_dirs(VID, binp),
                                 [str(user), override])
            finally:
                ctx.stop()

    def test_one_directory_when_both_names_agree(self):
        """Not cosmetic: the same tree searched twice would double every glob
        and let `_heap_file` report a 'first' match that depends on order."""
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, _ = _install(tmp)
            ctx = _env(ISABELLE_HEAPS=str(user),
                       ISABELLE_HEAPS_SYSTEM=str(user))
            try:
                self.assertEqual(nr._heaps_dirs(VID, binp), [str(user)])
            finally:
                ctx.stop()

    def test_home_resolves_through_a_symlink(self):
        """`~/.local/bin/isabelle` -> the installed tree is the usual setup; a
        home taken from the unresolved path would point at `~/.local`."""
        with tempfile.TemporaryDirectory() as tmp:
            binp, _, system = _install(tmp)
            link_dir = Path(tmp) / "local" / "bin"
            link_dir.mkdir(parents=True)
            link = link_dir / "isabelle"
            link.symlink_to(binp)
            self.assertEqual(nr._isabelle_home(str(link)), system.parent)


class DistributionOnlySession(unittest.TestCase):
    """The reported bug: a session that exists ONLY in the distribution."""

    def test_heap_file_finds_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, system = _install(tmp, system_sessions=("HOL",))
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                found = nr._heap_file(VID, "HOL", binp)
            finally:
                ctx.stop()
            self.assertEqual(found, system / PLAT / "HOL")

    def test_built_sessions_includes_it(self):
        """`_built_sessions` globs separately from `_heap_file`, so fixing one
        does not fix the other — `resolve_augmented` reads this one."""
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, _ = _install(tmp, user_sessions=("Local_Session",),
                                     system_sessions=("HOL", "Pure"))
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                built = nr._built_sessions(VID, binp)
            finally:
                ctx.stop()
            self.assertEqual(built, {"Local_Session", "HOL", "Pure"})

    def test_fingerprint_is_not_empty(self):
        """The user-visible consequence: with no heap found the fingerprint
        degrades to the version id, every install of one release fingerprints
        alike, and the dump is never attempted."""
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, _ = _install(tmp, system_sessions=("HOL",))
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                with mock.patch("subprocess.run",
                                side_effect=AssertionError("spawned!")):
                    fp = nr.isabelle_fingerprint("HOL", isabelle=binp)
                    bare = nr.isabelle_fingerprint("Absent", isabelle=binp)
            finally:
                ctx.stop()
            self.assertTrue(fp)
            self.assertNotEqual(fp, bare)  # the heap really was stat'ed

    def test_absent_session_is_still_absent(self):
        """The complement: searching more places must not invent a heap, or the
        no-build guard would wave through a dump that BUILDS the session."""
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, _ = _install(tmp, system_sessions=("HOL",))
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                self.assertIsNone(nr._heap_file(VID, "Never_Built", binp))
            finally:
                ctx.stop()


class UserHeapWins(unittest.TestCase):
    def test_a_session_in_both_resolves_to_the_user_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, system = _install(tmp, user_sessions=("HOL",),
                                          system_sessions=("HOL",))
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                found = nr._heap_file(VID, "HOL", binp)
            finally:
                ctx.stop()
            self.assertEqual(found, user / PLAT / "HOL")

    def test_built_sessions_does_not_double_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            binp, user, _ = _install(tmp, user_sessions=("HOL",),
                                     system_sessions=("HOL",))
            ctx = _env(ISABELLE_HEAPS=str(user))
            try:
                self.assertEqual(nr._built_sessions(VID, binp), {"HOL"})
            finally:
                ctx.stop()


if __name__ == "__main__":
    unittest.main()
