#!/usr/bin/env python3
r"""Corpus probe: proofs written on the STATEMENT's own line, and how many of
them `shape` drops (issue #5).

`parsing` already finds these — `_PROOF_INLINE_RE` recovers a `proof_line` for
`lemma foo: "P" by simp`, and does so over the whole declaration span, so the
proof may sit on a CONTINUATION line rather than the declaration line:

    lemma symcl_converse:
      "symcl (r\<inverse>) = (symcl r)\<inverse>" by auto

`shape._scan_steps` then reads that line from column 0, so `_command_prefix`
cuts at the first `"` and hands `_classify_step_line` a `lemma foo: ` (or, for
the continuation form, an empty string).  Neither is a step family, the scan
yields no steps, and `analyze_proof` returns None — the entry vanishes from
every `shape` verb, including `census`.

The two forms matter separately because a fix keyed on `proof_line == thy_line`
would repair only the first.  This counts both, and reports what the dropped
lines classify as today, so the fix can be checked against a known population
rather than a guess.

Usage:  probe_inline_proofs.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, shape  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120


def _cause(line: str) -> str:
    """Why a proof line still yields no step — the residual after the inline
    fix, which is a DIFFERENT defect and wants its own decision."""
    tokens = shape._CMD_TOKEN_RE.findall(shape._command_prefix(line))
    if not tokens:
        return "no command token at all (statement text only)"
    if tokens[-1] == ".":
        return "ends in a bare `.` (excluded: `.` also splits `Foo.bar`)"
    if tokens[0] in ("oops", "sorry"):
        return f"aborted proof (`{tokens[0]}`) — arguably has no shape"
    return f"unclassified head `{tokens[0]}`"


def main() -> None:
    n_thy = n_proofs = 0
    n_inline = n_decl_line = n_continuation = n_term_prefix = 0
    n_inline_dropped = n_ordinary_dropped = 0
    classes: Counter = Counter()
    residual: Counter = Counter()
    by_entry: Counter = Counter()
    samples: list[str] = []

    def note_drop(entry_name: str, line: str) -> None:
        by_entry[entry_name] += 1
        classes[shape._classify_step_line(line)] += 1
        residual[_cause(line)] += 1
        if len(samples) < 12:
            samples.append(f"{line[:84]}")

    for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
        for thy_path in sorted(ent.rglob("*.thy")):
            try:
                sec = cli._parse_one(thy_path.stem, thy_path)
            except Exception:  # noqa: BLE001
                continue
            n_thy += 1
            src = sec.source()
            for e in sec.entries:
                if not e.proof_line:
                    continue
                n_proofs += 1
                # The authoritative test, not a re-derived one: nonzero exactly
                # when statement text precedes the proof on its line.
                col = shape._inline_proof_col(sec, e)
                dropped = not shape._scan_steps(sec, e)
                if not col:
                    n_ordinary_dropped += dropped
                    if dropped:
                        note_drop(ent.name, src[e.proof_line - 1].strip())
                    continue
                n_inline += 1
                if e.proof_line == e.thy_line:
                    n_decl_line += 1
                elif e.proof_line <= (e.decl_end_line or e.thy_line):
                    n_continuation += 1
                else:
                    n_term_prefix += 1
                if not dropped:
                    continue
                n_inline_dropped += 1
                note_drop(ent.name, src[e.proof_line - 1][col:].strip())

    pct = lambda n, d: f"{100.0 * n / max(d, 1):.2f}%"  # noqa: E731
    print(f"theories={n_thy:,}   entries with a proof_line={n_proofs:,}")
    print(f"\nproof shares a line with the statement  {n_inline:>7,}"
          f"  ({pct(n_inline, n_proofs)} of proofs)")
    print(f"  on the declaration line               {n_decl_line:>7,}")
    print(f"  on a later line of the declaration    {n_continuation:>7,}"
          f"   <- a `proof_line == thy_line` fix misses these")
    print(f"  after a bare term, past decl_end_line {n_term_prefix:>7,}"
          f"   <- and a decl-span fix misses these")
    print(f"\ndropped by _scan_steps (no steps -> no shape record):")
    print(f"  of the inline proofs                  {n_inline_dropped:>7,}"
          f"  ({pct(n_inline_dropped, n_inline)} of them)")
    print(f"  of all other proofs                   {n_ordinary_dropped:>7,}"
          f"  ({pct(n_ordinary_dropped, n_proofs - n_inline)})")
    print(f"  inline share of the total drop        "
          f"{pct(n_inline_dropped, n_inline_dropped + n_ordinary_dropped):>7}")
    print("\nwhat the dropped line classifies as today:")
    for k, c in classes.most_common():
        print(f"  {k:<10} {c:>7,}")
    print("\nresidual causes (NOT the inline defect — a separate call):")
    for k, c in residual.most_common():
        print(f"  {c:>5,}  {k}")
    print("\nworst-hit entries:")
    for name, c in by_entry.most_common(8):
        print(f"  {name:<40} {c:>6,}")
    print("\nsamples:")
    for s in samples:
        print(f"  {s}")


if __name__ == "__main__":
    main()
