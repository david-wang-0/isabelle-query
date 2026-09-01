r"""What `query ... | head` exits, and why `0` is right [closed-stdout].

`CONTRIBUTING.md` and `README.md` fixed the status at 141 whenever a downstream
reader closes the pipe.  That contract was wrong, and the code was right — the
finding here is a documentation defect, not a behaviour one, which is the
opposite of what the item assumed.

Measured against standard producers rather than argued
(`scripts/probe_closed_stdout.py`):

    seq 10       | head -3   -> 0        (output fits the 64K pipe buffer)
    seq 200000   | head -3   -> SIGPIPE  (141 as a shell reports it)
    yes          | head -3   -> SIGPIPE
    python large | head -3   -> 1, plus "Exception ignored while flushing"

and query, over five AFP corpora straddling the buffer, five runs each, fully
deterministic on both sides:

    Abstract_Completeness   36,933 B -> 0
    AVL-Trees               54,693 B -> 0
    Flyspeck-Tame          837,945 B -> 141
    Coinductive          1,351,002 B -> 141

So `0` for a small answer is not a truncated success — **nothing was truncated
on this side**.  The command wrote its whole answer into the pipe buffer and
finished; `head` chose to read three lines.  Forcing 141 there would report
failure for a run that succeeded, and would differ from every Unix filter.

What a Python program must not do is the fourth reference row: exit 1 or 120
with `Exception ignored while flushing sys.stdout` on stderr, which is neither
the C behaviour nor a status a caller can interpret.  `main`'s handler prevents
it by pointing fd 1 at /dev/null before exiting, and `NoShutdownNoise` is what
keeps that so.

These tests run the installed console script, because the behaviour under test
is the *process exit status* — nothing below `main` can be asked about it.
"""

import os
import subprocess
import sys
import unittest
from pathlib import Path

_REPO = Path(__file__).resolve().parent.parent
_QUERY = _REPO / ".venv" / "bin" / "query"
_AFP = Path.home() / "repos" / "afp" / "thys"
# 64K is the pipe buffer on Linux and macOS; a corpus either side of it.
_SMALL = _AFP / "Abstract_Completeness"     # ~37 KB of census
_LARGE = _AFP / "Coinductive"               # ~1.3 MB


def piped(argv, head_n=3):
    """(producer status, its stderr) with `head -n` closing the pipe."""
    p1 = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p2 = subprocess.Popen(["head", f"-{head_n}"], stdin=p1.stdout,
                          stdout=subprocess.DEVNULL)
    p1.stdout.close()
    p2.wait()
    err = p1.stderr.read().decode("utf-8", "replace")
    p1.stderr.close()
    return p1.wait(), err


def census(root):
    return [str(_QUERY), "-R", str(root), "shape", "census"]


needs_query = unittest.skipUnless(_QUERY.is_file(), "console script not built")
needs_small = unittest.skipUnless(_SMALL.is_dir(), "AFP corpus absent")
needs_large = unittest.skipUnless(_LARGE.is_dir(), "AFP corpus absent")


@needs_query
class ReferenceBehaviour(unittest.TestCase):
    """What the tool is being held to — measured, not assumed.

    Without these the other class is a bare assertion about numbers.  The claim
    is "query behaves like a Unix filter", so the filter has to be in the test.
    """

    def test_a_small_producer_exits_zero(self):
        self.assertEqual(piped(["seq", "10"])[0], 0)

    def test_a_large_producer_dies_of_sigpipe(self):
        st, _ = piped(["seq", "200000"])
        # `Popen.wait` reports a signal as a negative number; a shell renders
        # the same thing as 128 + signal = 141.
        self.assertIn(st, (-13, 141))


@needs_query
@needs_small
class ASmallAnswerExitsZero(unittest.TestCase):
    """And that is correct.  Do not 'fix' this to 141."""

    def test_status_is_zero(self):
        st, _err = piped(census(_SMALL))
        self.assertEqual(st, 0, "a command that wrote its whole answer before "
                                "the reader stopped did not fail")

    def test_it_is_deterministic(self):
        env = dict(os.environ, PYTHONHASHSEED="0")
        self.assertEqual({piped(census(_SMALL))[0] for _ in range(3)}, {0})

    def test_stderr_stays_silent(self):
        _st, err = piped(census(_SMALL))
        self.assertEqual(err.strip(), "")


@needs_query
@needs_large
class ALargeAnswerExitsOneFortyOne(unittest.TestCase):

    def test_status_is_141(self):
        st, _err = piped(census(_LARGE))
        self.assertEqual(st, 141)

    def test_it_is_deterministic(self):
        self.assertEqual({piped(census(_LARGE))[0] for _ in range(3)}, {141})


@needs_query
@needs_large
class NoShutdownNoise(unittest.TestCase):
    r"""The failure mode the handler exists to prevent.

    Python's default is to let `BrokenPipeError` reach interpreter shutdown,
    where the second flush of `sys.stdout` prints `Exception ignored while
    flushing sys.stdout` and the process exits 1 or 120.  `main` points fd 1 at
    /dev/null before exiting, which is what silences it — closing stdout does
    not work, since `sys.stdout` and `sys.__stdout__` are the same object.
    """

    def test_nothing_is_printed_on_stderr(self):
        _st, err = piped(census(_LARGE))
        self.assertNotIn("Exception ignored", err)
        self.assertEqual(err.strip(), "")

    def test_the_status_is_never_pythons_default(self):
        st, _err = piped(census(_LARGE))
        self.assertNotIn(st, (1, 120),
                         "an unhandled BrokenPipeError reached shutdown")


if __name__ == "__main__":
    unittest.main()
