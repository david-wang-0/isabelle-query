#!/usr/bin/env python3
r"""Corpus probe: did the non-Isar work move any comment's ATTRIBUTION?

Attribution is the hard-won part of this parser, and it is directional:

  * a `text \<open>...\<close>` block PRECEDES the entry it documents, and is
    charged forward to it (`Entry.preamble`, which sets `src_start`);
  * a `\<comment>` note FOLLOWS the step it annotates, and is charged backward
    into the enclosing entry's proof body (`Entry.roadmap`);
  * a plain `(* ... *)` block between two entries has always fallen inside the
    PRECEDING entry's span, because spans run to the next declaration.

`query enclosing`, `outline`, `show` and `largest` all read those spans, so a
silent shift here is expensive.  Two changes could have caused one:

  1. gating the declaration scan on the tokenizer removes phantom entries, and
     a phantom entry was a span BOUNDARY — so the entry above it now extends
     through the region the phantom used to own;
  2. `comment_ranges` left `_noise_spans`, which `_proof_blocks` reads.

This parses each theory both ways and diffs spans, preambles and roadmaps for
the entries that exist in BOTH, so the comparison is about attribution rather
than about the entries that (correctly) went away.

Usage:  probe_attribution.py [N_ENTRIES]
"""
import sys
from collections import Counter
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT / "src"))

from isabelle_query import cli, commands, parsing  # noqa: E402

AFP = Path.home() / "repos" / "afp" / "thys"
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 120

_real_entries = parsing.extract_entries


def ungated(lines, custom=None, nonisar_ranges=None):
    """extract_entries as it behaved before the gate: no region is skipped."""
    return _real_entries(lines, custom, nonisar_ranges=[])


def parse(path, thy, gate):
    parsing.extract_entries = _real_entries if gate else ungated
    try:
        return cli._parse_one(thy, path)
    finally:
        parsing.extract_entries = _real_entries


_real_noise = commands._noise_spans


def with_comment_ranges(sec):
    """`_noise_spans` as it behaved before `comment_ranges` was removed."""
    return _real_noise(sec) + list(sec.comment_ranges)


def blocks_of(sec, old_noise):
    """`_proof_blocks` for every entry, under one notion of noise.

    This is `enclosing -b`'s drill-down.  It reads `_noise_spans` to skip prose
    while matching `proof` / `qed` / `{` / `}`, so removing `comment_ranges`
    could change which blocks it finds — and a `qed \\<comment> \\<open>...`
    line was previously skipped ENTIRELY, taking its `qed` with it.
    """
    commands._noise_spans = with_comment_ranges if old_noise else _real_noise
    try:
        return {e.name: commands._proof_blocks(sec, e)
                for e in sec.entries if e.proof_line}
    finally:
        commands._noise_spans = _real_noise


n_thy = n_common = 0
same = Counter()
block_same = block_changed = block_recovered = 0
block_samples: list[tuple[str, str, object, object]] = []
changed: list[tuple[str, str, tuple, tuple]] = []
preamble_changed: list[tuple[str, str]] = []
roadmap_changed: list[tuple[str, str, int, int]] = []

for ent in sorted(d for d in AFP.iterdir() if d.is_dir())[:LIMIT]:
    for thy_path in sorted(ent.rglob("*.thy")):
        try:
            new = parse(thy_path, thy_path.stem, True)
            old = parse(thy_path, thy_path.stem, False)
        except Exception:  # noqa: BLE001
            continue
        n_thy += 1
        # Key on (name, thy_line): the same declaration in both parses.
        o = {(e.name, e.thy_line): e for e in old.entries if e.name != "?"}
        for e in new.entries:
            k = (e.name, e.thy_line)
            if k not in o:
                continue
            n_common += 1
            b = o[k]
            if (e.src_start, e.thy_end) == (b.src_start, b.thy_end):
                same["span"] += 1
            else:
                changed.append((f"{thy_path.stem}", e.name,
                                (b.src_start, b.thy_end),
                                (e.src_start, e.thy_end)))
            if e.preamble != b.preamble:
                preamble_changed.append((thy_path.stem, e.name))
            if len(e.roadmap) != len(b.roadmap):
                roadmap_changed.append((thy_path.stem, e.name,
                                        len(b.roadmap), len(e.roadmap)))

        # Drill-down blocks, on the SAME parse, under the two noise notions.
        now, before = blocks_of(new, False), blocks_of(new, True)
        for name, bl in now.items():
            was = before.get(name)
            if bl == was:
                block_same += 1
                continue
            block_changed += 1
            # `None` means the scan went unbalanced and the caller falls back
            # to the entry-level answer; going None -> a real list is a
            # RECOVERY, not a regression.
            if was is None and bl is not None:
                block_recovered += 1
            elif len(block_samples) < 10:
                block_samples.append((thy_path.stem, name, was, bl))

print(f"theories={n_thy}  entries compared={n_common:,}")
print(f"  span unchanged:  {same['span']:,}")
print(f"  span CHANGED:    {len(changed):,}")
print(f"  preamble CHANGED: {len(preamble_changed):,}  "
      "(forward attribution of a `text` doc — expected 0)")
print(f"  roadmap CHANGED:  {len(roadmap_changed):,}  "
      "(backward attribution of a `\\<comment>` note — expected 0)")
print(f"\n`enclosing -b` drill-down blocks (comment_ranges in vs out of "
      f"_noise_spans):")
print(f"  unchanged: {block_same:,}   changed: {block_changed:,}   "
      f"of which recovered from an unbalanced scan: {block_recovered:,}")
for thy, name, was, bl in block_samples:
    print(f"  ~~ {thy}/{name}: {was} -> {bl}")
print("\nspan changes (old -> new); a grown thy_end means the entry absorbed "
      "\nthe region a phantom declaration used to own:")
for thy, name, b, a in changed[:20]:
    grew = "grew" if a[1] > b[1] else ("shrank" if a[1] < b[1] else "start")
    print(f"  {thy}:{name:<32} {b} -> {a}  [{grew}]")
for thy, name in preamble_changed[:10]:
    print(f"  !! preamble moved: {thy}/{name}")
for thy, name, bn, an in roadmap_changed[:10]:
    print(f"  !! roadmap moved: {thy}/{name}  {bn} -> {an}")
