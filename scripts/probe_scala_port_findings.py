#!/usr/bin/env python3
r"""Which of the Scala port's recorded divergences still reproduce here.

David Wang's Scala port (`https://github.com/david-wang-0/isabelle-query`) ran
a differential matrix against this implementation as its oracle and recorded
every place it deliberately does NOT reproduce us, in `dev/DIVERGENCES.md`.
Most of those entries are our bugs.  This script is the independent check:
each claim gets a minimal fixture and a real call into the current tree, so a
finding is confirmed here rather than taken on trust — and, once fixed, stays
checkable rather than being asserted done.

    python scripts/probe_scala_port_findings.py

CONFIRMED means the defect is still present.  Fixed as of 0.7.x:

    D1  [cartouche-escape]      `\<open>\\<close>` swallowed the rest of the file
    D2  [marker-decl]           `definition\<^marker>\<open>...\<close> name` was missed
    D3  [keyword-kind-quoted]   a quoted `keywords` kind registered no command
    D6  [marker-decl]           a name ran straight through a trailing marker
    D7  [span-ties]             a multi-name `axiomatization` crashed the line index
    D8  [closed-stdout]         NOT ours — the contract was wrong, not the code
    D10 [cascade-level]         `unused -r` depths depended on the hash seed

Still open, with todo tags:

    D5  [comment-newline]       `\<comment>` whose cartouche is on the next line

Exit status is the number of claims still confirmed, so it falls to 0 as they
are fixed.  A claim that flips back to CONFIRMED is a regression.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, parsing  # noqa: E402
from isabelle_query.graph import _build_line_index  # noqa: E402

RESULTS: list[tuple[str, bool, str]] = []


def record(tag: str, confirmed: bool, note: str) -> None:
    RESULTS.append((tag, confirmed, note))
    mark = "CONFIRMED" if confirmed else "not reproduced"
    print(f"{tag:5} {mark:16} {note}")


_KEEP: list[tempfile.TemporaryDirectory] = []
QUERY = str(_ROOT / ".venv" / "bin" / "query")


def _parse_text(name: str, text: str):
    """Parse a theory given as text, via the real `cli._parse_one`.

    The temp dir is held open for the run: `TheorySection.live_source()` is
    lazy and re-reads the file, so a `with` block here would delete the source
    out from under every scanner the probe wants to exercise.
    """
    td = tempfile.TemporaryDirectory(dir=_ROOT)
    _KEEP.append(td)
    p = Path(td.name) / f"{name}.thy"
    p.write_text(text)
    return cli._parse_one(name, p)


# --------------------------------------------------------------- D1
def d1_cartouche_backslash() -> None:
    r"""`\<open>\\<close>` — a cartouche whose body is one backslash."""
    thy = (
        "theory D1\nimports Main\nbegin\n"
        "fun resid (infix \\<open>\\\\<close> 70) where \"resid x = x\"\n"
        "lemma below_the_line: \"True\" by simp\n"
        "lemma also_below: \"True\" by simp\n"
        "end\n"
    )
    sec = _parse_text("D1", thy)
    names = [e.name for e in sec.entries]
    # The two lemmas after the operator line must both be found.
    lost = [n for n in ("below_the_line", "also_below") if n not in names]
    resid = next((e for e in sec.entries if e.name == "resid"), None)
    swallowed = resid is not None and resid.decl_end_line >= 6
    record("D1", bool(lost) or swallowed,
           f"entries={names} resid.decl_end_line="
           f"{resid.decl_end_line if resid else '-'} (file is 7 lines)")


# --------------------------------------------------------------- D2
def d2_marker_glued_to_keyword() -> None:
    r"""`definition\<^marker>\<open>tag important\<close> name`."""
    thy = (
        "theory D2\nimports Main\nbegin\n"
        "definition\\<^marker>\\<open>tag important\\<close> istopology :: "
        "\"bool \\<Rightarrow> bool\" where \"istopology x = x\"\n"
        "lemma \\<^marker>\\<open>tag important\\<close> fold_absorb: \"True\" "
        "by simp\n"
        "end\n"
    )
    sec = _parse_text("D2", thy)
    names = [e.name for e in sec.entries]
    record("D2", "istopology" not in names or "fold_absorb" not in names,
           f"entries={names} (want istopology, fold_absorb)")


# --------------------------------------------------------------- D3
def d3_quoted_keyword_kind() -> None:
    r"""`keywords "alphabet" :: "thy_defn"` — a QUOTED kind."""
    bare = parsing.scan_keywords(
        ["theory T", "  imports Main",
         "  keywords \"alphabet\" \"statespace\" :: thy_defn", "begin"])
    quoted = parsing.scan_keywords(
        ["theory T", "  imports Main",
         "  keywords \"alphabet\" \"statespace\" :: \"thy_defn\"", "begin"])
    record("D3", bool(bare) and not quoted,
           f"unquoted kind -> {bare}; quoted kind -> {quoted}")


# --------------------------------------------------------------- D6
def d6_marker_after_the_name() -> None:
    r"""`definition lipschitzI_on\<^marker>\<open>tag important\<close> :: ...`.

    The other half of [marker-decl], and the half a marker-in-the-keyword
    fixture cannot see: here the command IS recognised, and the name run
    continues through the marker.  Both real spellings, from
    Lipschitz_Interval_Extension:47 and Source_and_Sink_Algebras_Constructions.
    """
    thy = (
        "theory D6\nimports Main\nbegin\n"
        "definition lipschitzI_on\\<^marker>\\<open>tag important\\<close> :: "
        "\"bool\"\n"
        "  where \"lipschitzI_on = True\"\n"
        "lemma coprod_final_sink\\<^marker>\\<open>tag important\\<close>: "
        "\"True\" by simp\n"
        "end\n"
    )
    sec = _parse_text("D6", thy)
    names = [e.name for e in sec.entries]
    record("D6", any("marker" in n for n in names),
           f"entries={names} (want lipschitzI_on, coprod_final_sink)")


# --------------------------------------------------------------- D5
def d5_comment_cartouche_next_line() -> None:
    r"""`\<comment>` and its cartouche separated by a newline."""
    thy = (
        "theory D5\nimports Main\nbegin\n"
        "lemma foo:\n"
        "  shows \"True\"\n"
        "\\<comment>\n"
        "\\<open>\n"
        "  This lemma could easily be generalised.\n"
        "\\<close>\n"
        "  by simp\n"
        "end\n"
    )
    sec = _parse_text("D5", thy)
    foo = next((e for e in sec.entries if e.name == "foo"), None)
    # The prose on lines 6..9 is ONE formal comment, so none of it is live.
    live = sec.live_source()
    prose_live = [i for i in (8,) if "generalised" in live[i - 1]]
    record("D5", bool(prose_live),
           f"foo.decl_end_line={foo.decl_end_line if foo else '-'} "
           f"(want 5); prose still live at lines {prose_live}")


# --------------------------------------------------------------- D7
def d7_line_index_typeerror() -> None:
    """Two entries with identical (src_start, thy_end) -> sort TypeError."""
    # Both real spellings from FOL/ex/Locale_Test/Locale_Test1 (:544 and :719).
    thy = (
        "theory D7\nimports Main\nbegin\n"
        "axiomatization glob_one and glob_inv\n"
        "  where glob_lone: \\<open>prod(glob_one, x) = x\\<close>\n"
        "end\n"
    )
    sec = _parse_text("D7", thy)
    spans = [(e.src_start, e.thy_end, e.name) for e in sec.entries
             if e.thy_line > 0]
    try:
        _build_line_index([sec])
    except TypeError as exc:
        record("D7", True, f"{type(exc).__name__}: {exc}  spans={spans}")
        return
    record("D7", False, f"no crash; spans={spans}")


# --------------------------------------------------------------- D8
def d8_closed_stdout_status() -> None:
    """Exit status when stdout closes early.

    D8 asserts the contract is 141 always.  Measured, it is not, and should not
    be: an answer that fits the 64K pipe buffer is written in full before the
    reader stops, so nothing fails and 0 is correct — exactly what `seq 10 |
    head` does, where `seq 200000 | head` dies of SIGPIPE.  So this checks the
    RIGHT contract (0 under the buffer, 141 over it, silence on stderr either
    way), not D8's.  See `scripts/probe_closed_stdout.py` and
    `tests/test_closed_stdout.py` [closed-stdout].
    """
    afp = Path.home() / "repos" / "afp" / "thys"
    small, large = afp / "Abstract_Completeness", afp / "Coinductive"
    if not small.is_dir() or not large.is_dir():
        record("D8", False, "AFP corpus absent, skipped")
        return
    env = dict(os.environ, PYTHONHASHSEED="0")
    got = {}
    for label, corpus in (("small", small), ("large", large)):
        statuses = []
        for _ in range(3):
            p1 = subprocess.Popen(
                [QUERY, "-R", str(corpus), "shape", "census"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env)
            p2 = subprocess.Popen(["head", "-3"], stdin=p1.stdout,
                                  stdout=subprocess.DEVNULL)
            p1.stdout.close()
            p2.wait()
            statuses.append(p1.wait())
        got[label] = statuses
    wrong = set(got["small"]) != {0} or set(got["large"]) != {141}
    record("D8", wrong,
           f"under 64K -> {got['small']} (want all 0); "
           f"over -> {got['large']} (want all 141)")


# --------------------------------------------------------------- D10
def d10_unused_cascade_nondeterminism() -> None:
    """`unused -r` cascade depths vary with PYTHONHASHSEED."""
    corpus = Path.home() / "repos" / "afp" / "thys" / "Abstract_Completeness"
    if not corpus.is_dir():
        record("D10", False, "AFP corpus absent, skipped")
        return
    outs = set()
    for seed in ("0", "1", "2", "3", "4"):
        r = subprocess.run([QUERY, "-R", str(corpus), "unused", "-r"],
                           capture_output=True, text=True,
                           env=dict(os.environ, PYTHONHASHSEED=seed))
        outs.add(r.stdout)
    stripped = {"\n".join(ln.split("[cascade depth")[0].rstrip()
                          for ln in o.splitlines()) for o in outs}
    record("D10", len(outs) > 1,
           f"{len(outs)} distinct outputs over 5 hash seeds; "
           f"{len(stripped)} distinct with the depth marker stripped")


def main() -> int:
    print(f"{'tag':5} {'status':16} evidence")
    print("-" * 78)
    for fn in (d1_cartouche_backslash, d2_marker_glued_to_keyword,
               d3_quoted_keyword_kind, d5_comment_cartouche_next_line,
               d6_marker_after_the_name, d7_line_index_typeerror,
               d8_closed_stdout_status, d10_unused_cascade_nondeterminism):
        try:
            fn()
        except Exception as exc:  # a crash IS a finding, not a probe failure
            record(fn.__name__.split("_")[0].upper(), True,
                   f"probe raised {type(exc).__name__}: {exc}")
    still = [t for t, ok, _ in RESULTS if ok]
    fixed = [t for t, ok, _ in RESULTS if not ok]
    print("-" * 78)
    print(f"still present {len(still)}/{len(RESULTS)}"
          + (f": {', '.join(still)}" if still else "")
          + (f"; no longer reproducing: {', '.join(fixed)}" if fixed else ""))
    return len(still)


if __name__ == "__main__":
    raise SystemExit(main())
