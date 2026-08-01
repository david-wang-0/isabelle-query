#!/usr/bin/env python3
"""Delete 1-indexed inclusive line ranges from a file, in place.

A refactor aid for the cli.py module split: extracting a block means writing
it into a new module and *removing* it from cli.py.  Removing a large
contiguous span by an exact-text Edit is transcription-error-prone (Unicode
arrows, inline comments), so this deletes by line number instead — exactly,
with no re-typing of the moved code.

Ranges are applied in descending start order so earlier ranges keep the line
numbers they had in the pristine file.  Usage:

    python3 scripts/refactor_delete_lines.py FILE A-B [C-D ...]
"""

import sys
from pathlib import Path


def main() -> None:
    path = Path(sys.argv[1])
    ranges = []
    for spec in sys.argv[2:]:
        a, b = spec.split("-")
        ranges.append((int(a), int(b)))
    lines = path.read_text().splitlines(keepends=True)
    # Descending by start: a later deletion never shifts an earlier one's
    # indices, so all ranges refer to the same (pristine) numbering.
    for a, b in sorted(ranges, reverse=True):
        del lines[a - 1:b]
    path.write_text("".join(lines))
    print(f"{path}: deleted {len(ranges)} range(s); now "
          f"{len(lines)} lines")


if __name__ == "__main__":
    main()
